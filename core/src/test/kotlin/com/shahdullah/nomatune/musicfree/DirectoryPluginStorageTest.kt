package com.shahdullah.nomatune.musicfree

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DirectoryPluginStorageTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun `round trips plugin and prevents id path traversal`() {
        val root = temporaryFolder.newFolder("plugins")
        val storage = DirectoryPluginStorage(root)
        val metadata = PluginMetadata("../outside", "Provider", "1", "https://example.com/p.js", "abc", 42)

        storage.put(StoredPlugin(metadata, "module.exports = {}"))

        assertEquals(metadata, storage.all().single().metadata)
        assertTrue(root.listFiles().orEmpty().all { it.parentFile == root })
        assertTrue(storage.remove("../outside"))
        assertTrue(storage.all().isEmpty())
    }
}
