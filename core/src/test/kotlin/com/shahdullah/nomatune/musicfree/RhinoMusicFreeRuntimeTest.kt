package com.shahdullah.nomatune.musicfree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RhinoMusicFreeRuntimeTest {
    private val runtime = RhinoMusicFreeRuntime(PluginLimits(maxExecutionMillis = 100))

    @Test
    fun `inspects commonjs plugin without Java access`() {
        val script = """
            module.exports = {
              platform: 'Example', version: '1.0',
              search: function(q, page) { return {q:q, page:page}; },
              getMediaSource: function(track) { return {url:track.url}; }
            };
        """.trimIndent()

        val info = runtime.inspect(script)
        assertEquals("Example", info.name)
        assertTrue("search" in info.capabilities)
        assertEquals("{\"q\":\"hello\",\"page\":2}", runtime.invoke(script, "search", "[\"hello\",2]"))
    }

    @Test
    fun `inspection validates bundled plugin without executing require or side effects`() {
        val script = """
            const axios = require('axios');
            throw new Error('top level must not run while installing');
            async function search(query, page) { return (await axios.get('/search')).data; }
            async function getMediaSource(track) { return {url: track.url}; }
            module.exports = { platform: '网络音源', version: '2.0', search: search, getMediaSource: getMediaSource };
        """.trimIndent()

        val info = runtime.inspect(script)
        assertEquals("网络音源", info.name)
        assertTrue("getMediaSource" in info.capabilities)
    }

    @Test
    fun `terminates runaway script`() {
        val script = "module.exports = { platform: 'Loop', search: function() { while (true) {} }, getMediaSource: function() {} };"
        assertThrows(MusicFreeException::class.java) { runtime.invoke(script, "search", "[]") }
    }

    @Test
    fun `axios shim crosses only json bridge and lowers async function`() {
        var request = ""
        val bridgedRuntime = RhinoMusicFreeRuntime(
            PluginLimits(maxExecutionMillis = 100),
            PluginHttpBridge {
                request = it
                """{"status":200,"data":{"items":[{"id":"1","title":"Track"}]},"headers":{}}"""
            },
        )
        val script = """
            const axios = require('axios');
            async function search(query) {
              return (await axios.get('https://api.example/search', {params:{q:query}, headers:{Cookie:'blocked'}})).data;
            }
            function getMediaSource(track) { return {url: track.url}; }
            module.exports = { platform: 'Bridge', search: search, getMediaSource: getMediaSource };
        """.trimIndent()

        assertEquals("{\"items\":[{\"id\":\"1\",\"title\":\"Track\"}]}", bridgedRuntime.invoke(script, "search", "[\"hello\"]"))
        assertTrue(request.contains("https://api.example/search"))
        assertTrue(request.contains("Cookie")) // Filtering is the host policy's responsibility.
    }

    @Test
    fun `crypto-js qs and he stay inside the sandbox`() {
        val script = """
            const CryptoJS = require('crypto-js');
            const qs = require('qs');
            const he = require('he');
            module.exports = {
              platform: 'Tools',
              search: function() {
                return {
                  hash: CryptoJS.MD5('music').toString(),
                  query: qs.stringify({q: 'a b'}),
                  text: he.decode('A &amp; B')
                };
              },
              getMediaSource: function() { return {url: 'https://example.invalid/a'}; }
            };
        """.trimIndent()

        val result = runtime.invoke(script, "search", "[]")
        assertTrue(result.contains("\"hash\":\"18d6769919266cd0bd6cd78aa405d5d0\""))
        assertTrue(result.contains("q=a+b") || result.contains("q=a%20b"))
        assertTrue(result.contains("A & B"))
    }
}
