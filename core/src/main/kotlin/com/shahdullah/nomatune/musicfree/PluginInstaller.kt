/*
 * NomaTune (2026)
 * © Shahdullah — github.com/shahdullah
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.shahdullah.nomatune.musicfree

interface BoundedDownloader {
    suspend fun download(url: String, maximumBytes: Int): ByteArray
}

class PluginInstaller(
    private val downloader: BoundedDownloader,
    private val runtime: PluginRuntime,
    private val registry: PluginRegistry,
    private val urlPolicy: SourceUrlPolicy,
    private val limits: PluginLimits = PluginLimits(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun install(entry: SubscriptionPlugin): InstallResult {
        val url = urlPolicy.requirePluginUrl(entry.url).toASCIIString()
        val bytes = downloader.download(url, limits.maxPluginBytes)
        if (bytes.size > limits.maxPluginBytes) throw MusicFreeException("Plug-in is too large")
        val script = bytes.toString(Charsets.UTF_8)
        val info = runtime.inspect(script)
        if (info.capabilities.none { it == "search" } || "getMediaSource" !in info.capabilities) {
            throw MusicFreeException("Plug-in must provide search and getMediaSource")
        }
        val metadata = PluginMetadata(
            id = info.id,
            name = info.name,
            version = info.version,
            sourceUrl = url,
            sha256 = sha256Hex(bytes),
            installedAtEpochMs = now(),
        )
        return registry.install(StoredPlugin(metadata, script))
    }
}
