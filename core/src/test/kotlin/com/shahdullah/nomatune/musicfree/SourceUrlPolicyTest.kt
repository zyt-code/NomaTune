package com.shahdullah.nomatune.musicfree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SourceUrlPolicyTest {
    private val policy = SourceUrlPolicy(setOf("allowed.example"))

    @Test
    fun `requires a supported HTTPS subscription suffix`() {
        assertEquals(
            "https://example.com/music1.listensoul.json",
            policy.requireSubscriptionUrl("https://example.com/music1.listensoul.json").toString(),
        )
        assertThrows(MusicFreeException::class.java) {
            policy.requireSubscriptionUrl("http://example.com/music1.json")
        }
        assertThrows(MusicFreeException::class.java) {
            policy.requireSubscriptionUrl("https://example.com/music1.txt")
        }
    }

    @Test
    fun `cleartext is host allowlisted and private targets are rejected`() {
        assertEquals(
            "http://allowed.example/source.js",
            policy.requirePluginUrl("http://allowed.example/source.js").toString(),
        )
        assertThrows(MusicFreeException::class.java) {
            policy.requirePluginUrl("http://other.example/source.js")
        }
        assertThrows(MusicFreeException::class.java) {
            policy.requirePluginUrl("https://127.0.0.1/source.js")
        }
        assertThrows(MusicFreeException::class.java) {
            policy.requirePluginUrl("https://user:secret@example.com/source.js")
        }
    }
}
