/*
 * NomaTune (2026)
 * © Shahdullah — github.com/shahdullah
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.shahdullah.nomatune.musicfree

/** Stable model shared by local and plug-in backed sources. */
data class SourceTrack(
    val sourceId: String,
    val sourceTrackId: String,
    val title: String,
    val artists: List<String>,
    val album: String? = null,
    val artworkUrl: String? = null,
    /** MusicFree duration is seconds; this model stores milliseconds. */
    val durationMs: Long? = null,
    /** Opaque plug-in fields required by a later getMediaSource/getLyric call. */
    val extra: Map<String, String> = emptyMap(),
)

data class ResolvedPlayback(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val userAgent: String? = null,
)

data class SourceLyrics(
    val rawLrc: String? = null,
    val translation: String? = null,
) {
    fun merged(): String? {
        val lyric = rawLrc?.trim()?.takeIf(String::isNotEmpty) ?: return null
        val translated = translation?.trim()?.takeIf(String::isNotEmpty) ?: return lyric
        return "$lyric\n$translated"
    }
}

data class SourcePage<T>(
    val items: List<T>,
    val nextPage: Int? = null,
)

/** The player and UI depend on this interface, never on a provider-specific model. */
interface MusicSource {
    val id: String
    val displayName: String

    suspend fun searchTracks(query: String, page: Int = 1): SourcePage<SourceTrack>

    suspend fun resolvePlayback(track: SourceTrack): ResolvedPlayback

    suspend fun lyrics(track: SourceTrack): String? = null
}

interface MusicSourceRegistry {
    fun enabledSources(): List<MusicSource>
    fun source(id: String): MusicSource?
}
