package com.shahdullah.nomatune.musicfree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginRegistryTest {
    @Test
    fun `deduplicates hash and replaces matching plugin id`() {
        val registry = PluginRegistry(InMemoryPluginStorage())
        val first = plugin("provider", "one", "hash-one")
        val duplicate = plugin("another", "same bytes", "hash-one")
        val replacement = plugin("provider", "two", "hash-two")

        assertTrue(registry.install(first) is InstallResult.Installed)
        assertTrue(registry.install(duplicate) is InstallResult.AlreadyInstalled)
        assertTrue(registry.install(replacement) is InstallResult.Replaced)
        assertEquals(listOf("two"), registry.plugins().map { it.script })
    }

    private fun plugin(id: String, script: String, hash: String) = StoredPlugin(
        PluginMetadata(id, id, "1", "https://example.com/$id.js", hash, 1),
        script,
    )
}
