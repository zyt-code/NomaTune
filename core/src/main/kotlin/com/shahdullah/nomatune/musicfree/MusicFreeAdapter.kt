/*
 * NomaTune (2026)
 * © Shahdullah — github.com/shahdullah
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.shahdullah.nomatune.musicfree

/** Boundary around the script engine so the rest of the app never depends on Rhino values. */
interface PluginRuntime {
    fun inspect(script: String): RuntimePluginInfo
    fun invoke(script: String, function: String, argumentsJson: String): String
}

data class RuntimePluginInfo(
    val id: String,
    val name: String,
    val version: String?,
    val capabilities: Set<String>,
)

class MusicFreeAdapter(
    override val id: String,
    override val displayName: String,
    private val script: String,
    private val runtime: PluginRuntime,
    private val json: JsonCodec,
) : MusicSource {
    override suspend fun searchTracks(query: String, page: Int): SourcePage<SourceTrack> =
        json.decodeTrackPage(runtime.invoke(script, "search", json.searchArguments(query, page)))

    override suspend fun resolvePlayback(track: SourceTrack): ResolvedPlayback =
        json.decodePlayback(runtime.invoke(script, "getMediaSource", json.trackArgument(track)))

    override suspend fun lyrics(track: SourceTrack): String? =
        runCatching { json.decodeLyrics(runtime.invoke(script, "getLyric", json.lyricArgument(track))) }
            .getOrNull()
}

/** Keeps protocol JSON mapping independently testable from script execution. */
interface JsonCodec {
    fun searchArguments(query: String, page: Int): String
    fun trackArgument(track: SourceTrack): String
    fun lyricArgument(track: SourceTrack): String = trackArgument(track)
    fun decodeTrackPage(json: String): SourcePage<SourceTrack>
    fun decodePlayback(json: String): ResolvedPlayback
    fun decodeLyrics(json: String): String?
}
