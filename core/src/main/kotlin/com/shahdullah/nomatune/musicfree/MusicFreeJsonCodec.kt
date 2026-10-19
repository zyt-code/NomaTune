/*
 * NomaTune (2026)
 * © Shahdullah — github.com/shahdullah
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.shahdullah.nomatune.musicfree

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Maps the MusicFree plug-in contract:
 * search(query, page, type) -> { isEnd, data },
 * getMediaSource(musicItem, quality) -> { url, headers, userAgent },
 * getLyric(musicItem) -> { rawLrc, translation }.
 *
 * Duration in the plug-in protocol is seconds. Extra serializable fields are preserved because
 * many plug-ins need them to resolve a playable URL.
 */
class MusicFreeJsonCodec(private val sourceId: String) : JsonCodec {
    private val json = Json { ignoreUnknownKeys = true }

    override fun searchArguments(query: String, page: Int): String =
        buildJsonArray {
            add(JsonPrimitive(query))
            add(JsonPrimitive(page))
            add(JsonPrimitive("music"))
        }.toString()

    override fun trackArgument(track: SourceTrack): String = buildJsonArray {
        add(musicItem(track))
        add(JsonPrimitive("standard"))
    }.toString()

    override fun lyricArgument(track: SourceTrack): String =
        buildJsonArray { add(musicItem(track)) }.toString()

    override fun decodeTrackPage(jsonText: String): SourcePage<SourceTrack> {
        val root = parse(jsonText)
        val container = root as? JsonObject
        val list = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["data"] ?: root["musicList"]) as? JsonArray
            else -> null
        } ?: throw MusicFreeException("Plug-in search did not return an item array")
        val items = list.mapNotNull(::decodeTrack)
        val isEnd = container?.get("isEnd")?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull()
        val nextPage = when {
            isEnd == true || items.isEmpty() -> null
            else -> (container?.number("page")?.plus(1) ?: 2L).toInt()
        }
        return SourcePage(items, nextPage)
    }

    override fun decodePlayback(jsonText: String): ResolvedPlayback {
        val root = parse(jsonText)
        if (root is JsonNull) throw MusicFreeException("Plug-in did not return a media source")
        if (root is JsonPrimitive && root.isString) return ResolvedPlayback(root.content)
        val obj = root as? JsonObject ?: throw MusicFreeException("Invalid media source response")
        val url = obj.text("url") ?: throw MusicFreeException("Media source URL is missing")
        val headers = linkedMapOf<String, String>()
        (obj["headers"] as? JsonObject)?.forEach { (name, value) ->
            (value as? JsonPrimitive)?.contentOrNull?.let { headers[name] = it }
        }
        obj.text("userAgent")?.let { headers.putIfAbsent("user-agent", it) }
        return ResolvedPlayback(url, headers, obj.text("userAgent"))
    }

    override fun decodeLyrics(jsonText: String): String? {
        val root = parse(jsonText)
        return when (root) {
            JsonNull -> null
            is JsonPrimitive -> root.contentOrNull
            is JsonObject -> SourceLyrics(
                rawLrc = root.text("rawLrc") ?: root.text("lrc") ?: root.text("lyric"),
                translation = root.text("translation"),
            ).merged()
            else -> null
        }
    }

    private fun musicItem(track: SourceTrack): JsonObject = buildJsonObject {
        put("id", track.sourceTrackId)
        put("platform", track.sourceId)
        put("title", track.title)
        put("artist", track.artists.joinToString(", "))
        track.album?.let { put("album", it) }
        track.artworkUrl?.let { put("artwork", it) }
        track.durationMs?.let { put("duration", it / 1_000.0) }
        track.extra.forEach { (key, value) ->
            if (key !in RESERVED_FIELDS) put(key, parseExtra(value))
        }
    }

    private fun decodeTrack(element: JsonElement): SourceTrack? {
        val item = element as? JsonObject ?: return null
        val id = item.text("id") ?: return null
        val title = item.text("title") ?: item.text("name") ?: return null
        val durationSeconds = item.double("duration")
        return SourceTrack(
            sourceId = sourceId,
            sourceTrackId = id,
            title = title,
            artists = item.artistNames(),
            album = item.text("album") ?: (item["album"] as? JsonObject)?.text("name"),
            artworkUrl = item.text("artwork") ?: item.text("coverImg"),
            durationMs = durationSeconds?.let { (it * 1_000.0).toLong() },
            extra = item.extraFields(),
        )
    }

    private fun parse(value: String): JsonElement = runCatching { json.parseToJsonElement(value) }
        .getOrElse { throw MusicFreeException("Plug-in returned invalid JSON", it) }

    private fun parseExtra(value: String): JsonElement =
        runCatching { json.parseToJsonElement(value) }.getOrElse { JsonPrimitive(value) }

    private fun JsonObject.text(key: String): String? =
        (get(key) as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty)

    private fun JsonObject.number(key: String): Long? = (get(key) as? JsonPrimitive)?.longOrNull

    private fun JsonObject.double(key: String): Double? =
        (get(key) as? JsonPrimitive)?.doubleOrNull ?: number(key)?.toDouble()

    private fun JsonObject.artistNames(): List<String> {
        val value = get("artist") ?: get("artists") ?: get("singer") ?: return emptyList()
        return when (value) {
            is JsonPrimitive -> value.content.split(',', '/', '、', '&').map(String::trim).filter(String::isNotEmpty)
            is JsonArray -> value.mapNotNull {
                when (it) {
                    is JsonPrimitive -> it.contentOrNull
                    is JsonObject -> it.text("name")
                    else -> null
                }
            }
            is JsonObject -> listOfNotNull(value.text("name"))
            else -> emptyList()
        }
    }

    private fun JsonObject.extraFields(): Map<String, String> =
        entries.mapNotNull { (key, value) ->
            if (key in RESERVED_FIELDS || value is JsonNull) null else key to value.toString()
        }.toMap()

    companion object {
        private val RESERVED_FIELDS = setOf(
            "id", "platform", "title", "name", "artist", "artists", "singer",
            "album", "artwork", "coverImg", "duration",
        )
    }
}
