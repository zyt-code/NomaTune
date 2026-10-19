package com.shahdullah.nomatune.musicfree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginHttpPolicyTest {
    private val policy = PluginHttpPolicy(SourceUrlPolicy())

    @Test
    fun `allows only safe methods urls and headers`() {
        val request = policy.sanitize(
            "post",
            "https://api.example/search",
            mapOf("Content-Type" to "application/json", "Cookie" to "secret", "Authorization" to "Bearer secret"),
        )
        assertEquals("POST", request.method)
        assertEquals(mapOf("content-type" to "application/json"), request.headers)
        assertFalse(request.headers.keys.any { it == "cookie" || it == "authorization" })
        assertThrows(MusicFreeException::class.java) {
            policy.sanitize("DELETE", "https://api.example/item", emptyMap())
        }
        assertThrows(MusicFreeException::class.java) {
            policy.sanitize("GET", "https://127.0.0.1/item", emptyMap())
        }
    }
}
