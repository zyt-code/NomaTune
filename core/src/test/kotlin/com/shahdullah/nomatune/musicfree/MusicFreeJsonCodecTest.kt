package com.shahdullah.nomatune.musicfree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicFreeJsonCodecTest {
    private val codec = MusicFreeJsonCodec("netease")

    @Test
    fun `search page keeps protocol fields and converts duration to milliseconds`() {
        val page = codec.decodeTrackPage(
            """
            {"isEnd":false,"data":[{
              "id":"42","title":"Track","artist":"A / B","album":"Album",
              "artwork":"https://example.invalid/a.jpg","duration":125,
              "sourceId":"needed-by-plugin"
            }]}
            """.trimIndent(),
        )

        val track = page.items.single()
        assertEquals("42", track.sourceTrackId)
        assertEquals("netease", track.sourceId)
        assertEquals(listOf("A", "B"), track.artists)
        assertEquals(125_000L, track.durationMs)
        assertEquals("\"needed-by-plugin\"", track.extra["sourceId"])
        assertEquals(2, page.nextPage)
    }

    @Test
    fun `media and lyric calls round-trip extra fields`() {
        val track = SourceTrack(
            sourceId = "netease",
            sourceTrackId = "42",
            title = "Track",
            artists = listOf("A"),
            durationMs = 125_000,
            extra = mapOf("sourceId" to "\"needed\""),
        )

        val playbackArgs = codec.trackArgument(track)
        assertTrue(playbackArgs.contains("\"quality\":\"standard\"") || playbackArgs.endsWith(",\"standard\"]"))
        assertTrue(playbackArgs.contains("\"sourceId\":\"needed\""))
        assertTrue(playbackArgs.contains("\"duration\":125"))

        val playback = codec.decodePlayback("""{"url":"https://cdn.example/a","userAgent":"Plugin","headers":{"referer":"https://example.invalid"}}""")
        assertEquals("https://cdn.example/a", playback.url)
        assertEquals("Plugin", playback.headers["user-agent"])
        assertEquals("raw line", codec.decodeLyrics("""{"rawLrc":"raw line","translation":"翻译"}""")?.substringBefore('\n'))
    }
}
