/*
 * NomaTune (2026)
 * © Shahdullah — github.com/shahdullah
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.shahdullah.nomatune.musicfree

interface PluginStorage {
    fun all(): List<StoredPlugin>
    fun put(plugin: StoredPlugin)
    fun remove(id: String): Boolean
}

class InMemoryPluginStorage(initial: List<StoredPlugin> = emptyList()) : PluginStorage {
    private val values = linkedMapOf<String, StoredPlugin>().apply {
        initial.forEach { put(it.metadata.id, it) }
    }

    override fun all(): List<StoredPlugin> = synchronized(values) { values.values.toList() }
    override fun put(plugin: StoredPlugin) = synchronized(values) { values[plugin.metadata.id] = plugin }
    override fun remove(id: String): Boolean = synchronized(values) { values.remove(id) != null }
}

/** Hash-deduplicated registry. Reinstalling the same provider id atomically replaces its script. */
class PluginRegistry(private val storage: PluginStorage) {
    fun plugins(): List<StoredPlugin> = storage.all().sortedBy { it.metadata.name.lowercase() }

    fun install(plugin: StoredPlugin): InstallResult {
        val sameHash = storage.all().firstOrNull { it.metadata.sha256 == plugin.metadata.sha256 }
        if (sameHash != null) return InstallResult.AlreadyInstalled(sameHash.metadata)
        val previous = storage.all().firstOrNull { it.metadata.id == plugin.metadata.id }
        storage.put(plugin)
        return if (previous == null) InstallResult.Installed(plugin.metadata)
        else InstallResult.Replaced(previous.metadata, plugin.metadata)
    }

    fun remove(id: String): Boolean = storage.remove(id)
}

sealed interface InstallResult {
    data class Installed(val plugin: PluginMetadata) : InstallResult
    data class AlreadyInstalled(val plugin: PluginMetadata) : InstallResult
    data class Replaced(val previous: PluginMetadata, val plugin: PluginMetadata) : InstallResult
}
