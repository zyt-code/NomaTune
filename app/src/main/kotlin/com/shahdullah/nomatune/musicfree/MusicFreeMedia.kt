/*
 * NomaTune (2026)
 * © Shahdullah — github.com/shahdullah
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.shahdullah.nomatune.musicfree

import android.os.Bundle
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata.MEDIA_TYPE_MUSIC
import com.shahdullah.nomatune.extensions.ExtraIsMusicVideo
import com.shahdullah.nomatune.models.MediaMetadata
import java.util.concurrent.ConcurrentHashMap

fun SourceTrack.toResolvedMediaItem(playback: ResolvedPlayback): MediaItem {
    val stableId = musicFreeMediaId(sourceId, sourceTrackId)
    val metadata = MediaMetadata(
        id = stableId,
        title = title,
        artists = artists.ifEmpty { listOf("Unknown artist") }.map { MediaMetadata.Artist(null, it) },
        duration = durationMs?.div(1_000L)?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: -1,
        thumbnailUrl = artworkUrl,
        album = album?.let { MediaMetadata.Album("$stableId:album", it) },
        sourceId = sourceId,
        sourceTrackId = sourceTrackId,
        playbackHeaders = playback.headers,
        sourceExtra = extra,
    )
    return MediaItem.Builder()
        .setMediaId(stableId)
        .setUri(playback.url)
        .setCustomCacheKey(stableId)
        .setTag(metadata)
        .setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artists.joinToString(", "))
                .setSubtitle(artists.joinToString(", "))
                .setAlbumTitle(album)
                .setArtworkUri(artworkUrl?.toUri())
                .setIsPlayable(true)
                .setMediaType(MEDIA_TYPE_MUSIC)
                .setExtras(Bundle().apply { putBoolean(ExtraIsMusicVideo, false) })
                .build(),
        )
        .build()
}

fun musicFreeMediaId(sourceId: String, sourceTrackId: String): String =
    "musicfree:${sha256Hex("$sourceId\u0000$sourceTrackId".toByteArray()).take(32)}"

object MusicFreePlaybackCache {
    private val values = ConcurrentHashMap<String, ResolvedPlayback>()
    fun put(mediaId: String, playback: ResolvedPlayback) { values[mediaId] = playback }
    fun get(mediaId: String): ResolvedPlayback? = values[mediaId]
}

fun MediaMetadata.toSourceTrack(): SourceTrack? {
    val provider = sourceId ?: return null
    val trackId = sourceTrackId ?: return null
    return SourceTrack(
        sourceId = provider,
        sourceTrackId = trackId,
        title = title,
        artists = artists.map { it.name },
        album = album?.title,
        artworkUrl = thumbnailUrl,
        durationMs = duration.takeIf { it > 0 }?.times(1_000L),
        extra = sourceExtra,
    )
}
