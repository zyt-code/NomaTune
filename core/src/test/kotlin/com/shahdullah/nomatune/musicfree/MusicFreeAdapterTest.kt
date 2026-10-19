package com.shahdullah.nomatune.musicfree

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class MusicFreeAdapterTest {
    @Test
    fun `adapter keeps runtime values behind json boundary`() = runBlocking {
        val calls = mutableListOf<String>()
        val runtime = object : PluginRuntime {
            override fun inspect(script: String) = error("unused")
            override fun invoke(script: String, function: String, argumentsJson: String): String {
                calls += "$function:$argumentsJson"
                return function
            }
        }
        val codec = object : JsonCodec {
            override fun searchArguments(query: String, page: Int) = "$query/$page"
            override fun trackArgument(track: SourceTrack) = track.sourceTrackId
            override fun decodeTrackPage(json: String) = SourcePage(listOf(SourceTrack("p", "1", json, listOf("a"))))
            override fun decodePlayback(json: String) = ResolvedPlayback("https://example.com/$json")
            override fun decodeLyrics(json: String) = json
        }
        val adapter = MusicFreeAdapter("p", "Provider", "script", runtime, codec)

        assertEquals("search", adapter.searchTracks("hello", 2).items.single().title)
        assertEquals("https://example.com/getMediaSource", adapter.resolvePlayback(SourceTrack("p", "1", "t", listOf("a"))).url)
        assertEquals(listOf("search:hello/2", "getMediaSource:1"), calls)
    }
}
