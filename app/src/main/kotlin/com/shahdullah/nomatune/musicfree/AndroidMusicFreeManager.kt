/*
 * NomaTune (2026)
 * © Shahdullah — github.com/shahdullah
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.shahdullah.nomatune.musicfree

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.headers
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.util.concurrent.Executors

data class SubscriptionImportSummary(
    val installed: Int,
    val unchanged: Int,
    val replaced: Int,
    val rejected: Int,
    val failures: List<String>,
)

/** Application-facing composition root. The dedicated client has no cookie jar or app credentials. */
class AndroidMusicFreeManager private constructor(context: Context) {
    private val limits = PluginLimits()
    // Cleartext is disabled by default. A future UI may create a policy with explicit hosts after
    // informed user consent; Android's manifest also blocks cleartext transport globally.
    private val urlPolicy = SourceUrlPolicy()
    private val storage = DirectoryPluginStorage(context.filesDir.resolve("musicfree/plugins"))
    private val registry = PluginRegistry(storage)
    private val pluginHttpPolicy = PluginHttpPolicy(urlPolicy)
    private val client = HttpClient(OkHttp) {
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = limits.maxNetworkMillis
            connectTimeoutMillis = limits.maxNetworkMillis
            socketTimeoutMillis = limits.maxNetworkMillis
        }
        engine {
            config {
                followRedirects(false)
                followSslRedirects(false)
                retryOnConnectionFailure(false)
            }
        }
    }
    // Separate from Dispatchers.IO: playback resolution already occupies an IO thread, and a
    // nested runBlocking on that same dispatcher can stall the limited thread pool.
    private val pluginHttpDispatcher: ExecutorCoroutineDispatcher =
        Executors.newFixedThreadPool(4) { runnable ->
            Thread(runnable, "musicfree-http").apply { isDaemon = true }
        }.asCoroutineDispatcher()
    private val runtime = RhinoMusicFreeRuntime(limits, PluginHttpBridge { requestJson ->
        runBlocking(pluginHttpDispatcher) { executePluginHttp(requestJson) }
    })
    private val downloader = object : BoundedDownloader {
        override suspend fun download(url: String, maximumBytes: Int): ByteArray =
            downloadBounded(url, maximumBytes, plugin = true)
    }
    private val installer = PluginInstaller(downloader, runtime, registry, urlPolicy, limits)

    fun installed(): List<PluginMetadata> = registry.plugins().map(StoredPlugin::metadata)

    suspend fun importSubscription(url: String): SubscriptionImportSummary = withContext(Dispatchers.IO) {
        val subscriptionUrl = urlPolicy.requireSubscriptionUrl(url).toASCIIString()
        val bytes = downloadBounded(subscriptionUrl, limits.maxSubscriptionBytes, plugin = false)
        val subscription = SubscriptionParser(urlPolicy, limits).parse(subscriptionUrl, bytes)
        var installed = 0
        var unchanged = 0
        var replaced = 0
        val failures = mutableListOf<String>()
        subscription.plugins.forEach { entry ->
            runCatching { installer.install(entry) }
                .onSuccess {
                    when (it) {
                        is InstallResult.Installed -> installed++
                        is InstallResult.AlreadyInstalled -> unchanged++
                        is InstallResult.Replaced -> replaced++
                    }
                }
                .onFailure { failures += "${entry.url}: ${it.message ?: "install failed"}" }
        }
        SubscriptionImportSummary(installed, unchanged, replaced, subscription.rejected.size, failures)
    }

    fun remove(id: String): Boolean = registry.remove(id)

    fun sources(): List<MusicSource> = registry.plugins()
        .filter { it.metadata.enabled }
        .map { plugin ->
            MusicFreeAdapter(
                id = plugin.metadata.id,
                displayName = plugin.metadata.name,
                script = plugin.script,
                runtime = runtime,
                json = MusicFreeJsonCodec(plugin.metadata.id),
            )
        }

    fun source(id: String): MusicSource? = sources().firstOrNull { it.id == id }

    suspend fun search(query: String): Pair<List<SourceTrack>, List<String>> = withContext(Dispatchers.IO) {
        val results = mutableListOf<SourceTrack>()
        val failures = mutableListOf<String>()
        sources().forEach { source ->
            runCatching { source.searchTracks(query.trim(), 1) }
                .onSuccess { results += it.items }
                .onFailure { failures += "${source.displayName}: ${it.message ?: "search failed"}" }
        }
        results.distinctBy { it.sourceId to it.sourceTrackId } to failures
    }

    suspend fun resolve(track: SourceTrack): ResolvedPlayback = withContext(Dispatchers.IO) {
        val source = source(track.sourceId) ?: throw MusicFreeException("Source is no longer installed")
        val playback = source.resolvePlayback(track)
        val safeUrl = urlPolicy.requireMediaUrl(playback.url).toASCIIString()
        val safeHeaders = pluginHttpPolicy.sanitize("GET", safeUrl, playback.headers).headers
            .toMutableMap()
        playback.userAgent?.let { safeHeaders.putIfAbsent("user-agent", it) }
        playback.copy(url = safeUrl, headers = safeHeaders, userAgent = playback.userAgent).also {
            MusicFreePlaybackCache.put(musicFreeMediaId(track.sourceId, track.sourceTrackId), it)
        }
    }

    suspend fun lyrics(track: SourceTrack): String? = withContext(Dispatchers.IO) {
        source(track.sourceId)?.lyrics(track)
    }

    private suspend fun executePluginHttp(requestJson: String): String {
        val config = runCatching { Json.parseToJsonElement(requestJson).jsonObject }
            .getOrElse { throw MusicFreeException("Plug-in supplied invalid HTTP configuration", it) }
        var method = config["method"]?.jsonPrimitive?.contentOrNull?.uppercase() ?: "GET"
        var current = config["url"]?.jsonPrimitive?.contentOrNull
            ?: throw MusicFreeException("Plug-in HTTP URL is required")
        current = appendQuery(current, config["params"] as? JsonObject)
        val requestedHeaders = (config["headers"] as? JsonObject).orEmpty()
            .mapValues { (_, value) -> value.jsonPrimitive.content }
        val requestBody = config["data"]?.let { element ->
            when (element) {
                is JsonPrimitive -> element.content
                JsonNull -> ""
                else -> element.toString()
            }
        }
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val safeRequest = pluginHttpPolicy.sanitize(method, current, requestedHeaders)
            val validated = java.net.URI(safeRequest.url)
            val response = client.request(safeRequest.url) {
                this.method = HttpMethod.parse(safeRequest.method)
                headers {
                    append(HttpHeaders.Accept, "application/json,text/plain;q=0.9,*/*;q=0.1")
                    append(HttpHeaders.UserAgent, "NomaTune-MusicFree/1")
                    safeRequest.headers.forEach { (name, value) -> append(name, value) }
                }
                if (method == "POST" && requestBody != null) setBody(requestBody)
            }
            if (response.status.value in 300..399) {
                if (redirectCount == MAX_REDIRECTS) throw MusicFreeException("Too many plug-in HTTP redirects")
                current = response.headers[HttpHeaders.Location]
                    ?.let { validated.resolve(it).toASCIIString() }
                    ?: throw MusicFreeException("Redirect is missing Location")
                if (response.status.value in setOf(301, 302, 303)) method = "GET"
                return@repeat
            }
            if (response.status.value !in 200..299) throw MusicFreeException("Plug-in HTTP ${response.status.value}")
            val bytes = readBoundedResponse(response.bodyAsChannel(), limits.maxResponseBytes)
            val text = bytes.toString(Charsets.UTF_8)
            val data: JsonElement = runCatching { Json.parseToJsonElement(text) }.getOrElse { JsonPrimitive(text) }
            return buildJsonObject {
                put("status", response.status.value)
                put("data", data)
                put("headers", buildJsonObject {
                    response.headers.entries().forEach { (name, values) ->
                        put(name, buildJsonArray { values.forEach { add(JsonPrimitive(it)) } })
                    }
                })
            }.toString()
        }
        throw MusicFreeException("Unable to complete plug-in HTTP request")
    }

    private suspend fun downloadBounded(initialUrl: String, maximumBytes: Int, plugin: Boolean): ByteArray {
        var current = initialUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val validated = if (plugin) urlPolicy.requirePluginUrl(current) else urlPolicy.requireSubscriptionUrl(current)
            val response = client.get(validated.toASCIIString()) {
                headers.append(HttpHeaders.Accept, if (plugin) "application/javascript,text/javascript;q=0.9,*/*;q=0.1" else "application/json")
                headers.append(HttpHeaders.UserAgent, "NomaTune-MusicFree/1")
            }
            if (response.status.value in 300..399) {
                if (redirectCount == MAX_REDIRECTS) throw MusicFreeException("Too many redirects")
                current = response.headers[HttpHeaders.Location]
                    ?.let { validated.resolve(it).toASCIIString() }
                    ?: throw MusicFreeException("Redirect is missing Location")
                return@repeat
            }
            if (response.status.value !in 200..299) throw MusicFreeException("HTTP ${response.status.value}")
            response.headers[HttpHeaders.ContentLength]?.toLongOrNull()?.let {
                if (it > maximumBytes) throw MusicFreeException("Download exceeds $maximumBytes bytes")
            }
            return readBoundedResponse(response.bodyAsChannel(), maximumBytes)
        }
        throw MusicFreeException("Unable to download source")
    }

    private suspend fun readBoundedResponse(channel: io.ktor.utils.io.ByteReadChannel, maximumBytes: Int): ByteArray {
        val output = ByteArrayOutputStream(minOf(maximumBytes, 64 * 1024))
        val buffer = ByteArray(8 * 1024)
        while (!channel.isClosedForRead) {
            val read = channel.readAvailable(buffer, 0, buffer.size)
            if (read <= 0) continue
            if (output.size() + read > maximumBytes) throw MusicFreeException("Download exceeds $maximumBytes bytes")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun appendQuery(url: String, params: JsonObject?): String {
        if (params.isNullOrEmpty()) return url
        val separator = if ('?' in url) '&' else '?'
        return buildString {
            append(url)
            append(separator)
            append(params.entries.joinToString("&") { (key, value) ->
                "${encode(key)}=${encode(value.jsonPrimitive.content)}"
            })
        }
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    companion object {
        private const val MAX_REDIRECTS = 4
        @Volatile private var instance: AndroidMusicFreeManager? = null

        fun get(context: Context): AndroidMusicFreeManager = instance ?: synchronized(this) {
            instance ?: AndroidMusicFreeManager(context.applicationContext).also { instance = it }
        }
    }
}
