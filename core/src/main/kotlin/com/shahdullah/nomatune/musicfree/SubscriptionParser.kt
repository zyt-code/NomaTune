/*
 * NomaTune (2026)
 * © Shahdullah — github.com/shahdullah
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.shahdullah.nomatune.musicfree

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class SubscriptionParser(
    private val urlPolicy: SourceUrlPolicy,
    private val limits: PluginLimits = PluginLimits(),
) {
    fun parse(sourceUrl: String, bytes: ByteArray): ParsedSubscription {
        val normalizedSource = urlPolicy.requireSubscriptionUrl(sourceUrl).toASCIIString()
        if (bytes.size > limits.maxSubscriptionBytes) {
            throw MusicFreeException("Subscription exceeds ${limits.maxSubscriptionBytes} bytes")
        }
        val root = runCatching { Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject }
            .getOrElse { throw MusicFreeException("Invalid subscription JSON", it) }
        val entries = root["plugins"] as? JsonArray
            ?: throw MusicFreeException("Subscription must contain a plugins array")
        if (entries.size > limits.maxPluginsPerSubscription) {
            throw MusicFreeException("Subscription contains too many plug-ins")
        }
        val seen = linkedSetOf<String>()
        val rejected = mutableListOf<RejectedPlugin>()
        val plugins = entries.mapIndexedNotNull { index, element ->
            val item = element as? JsonObject
                ?: throw MusicFreeException("plugins[$index] must be an object")
            val url = item.stringOrNull("url")
                ?: throw MusicFreeException("plugins[$index].url is required")
            val normalized = try {
                urlPolicy.requirePluginUrl(url).toASCIIString()
            } catch (error: MusicFreeException) {
                rejected += RejectedPlugin(url, error.message ?: "URL rejected")
                return@mapIndexedNotNull null
            }
            SubscriptionPlugin(
                url = normalized,
                declaredName = item.stringOrNull("name"),
                declaredVersion = item.stringOrNull("version"),
                description = item.stringOrNull("desc"),
            ).also {
                if (!seen.add(normalized)) throw MusicFreeException("Duplicate plug-in URL: $normalized")
            }
        }
        return ParsedSubscription(normalizedSource, plugins, rejected)
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.jsonPrimitive?.content?.trim()?.takeIf(String::isNotEmpty)
}

data class PluginLimits(
    val maxSubscriptionBytes: Int = 256 * 1024,
    val maxPluginBytes: Int = 512 * 1024,
    val maxPluginsPerSubscription: Int = 100,
    val maxExecutionMillis: Long = 15_000,
    val maxResponseBytes: Int = 2 * 1024 * 1024,
    val maxNetworkMillis: Long = 15_000,
)
