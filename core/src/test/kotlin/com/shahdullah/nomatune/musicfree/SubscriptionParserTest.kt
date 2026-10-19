package com.shahdullah.nomatune.musicfree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SubscriptionParserTest {
    private val parser = SubscriptionParser(SourceUrlPolicy(setOf("legacy.example")))

    @Test
    fun `parses music1 compatible plugins array`() {
        val result = parser.parse(
            "https://example.com/music1.json",
            """{"plugins":[{"url":"https://cdn.example/a.js","name":"Hint","version":"1"},{"url":"http://legacy.example/b.js","desc":"legacy"}]}"""
                .toByteArray(),
        )

        assertEquals(2, result.plugins.size)
        assertEquals("https://cdn.example/a.js", result.plugins[0].url)
        assertEquals("legacy", result.plugins[1].description)
    }

    @Test
    fun `rejects duplicate plugin urls`() {
        val json = """{"plugins":[{"url":"https://cdn.example/a.js"},{"url":"https://cdn.example/a.js"}]}"""
        assertThrows(MusicFreeException::class.java) {
            parser.parse("https://example.com/music1.jsons", json.toByteArray())
        }
    }
}
