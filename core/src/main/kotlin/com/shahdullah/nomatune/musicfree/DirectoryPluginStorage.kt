/*
 * NomaTune (2026)
 * © Shahdullah — github.com/shahdullah
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.shahdullah.nomatune.musicfree

import java.io.File
import java.util.Properties

class DirectoryPluginStorage(private val directory: File) : PluginStorage {
    override fun all(): List<StoredPlugin> {
        directory.mkdirs()
        return directory.listFiles { file -> file.extension == "properties" }.orEmpty().mapNotNull { metadataFile ->
            runCatching {
                val properties = Properties().apply { metadataFile.inputStream().use(::load) }
                val id = properties.getProperty("id") ?: return@runCatching null
                val script = File(directory, "${safeName(id)}.js").takeIf(File::isFile)?.readText()
                    ?: return@runCatching null
                StoredPlugin(
                    PluginMetadata(
                        id = id,
                        name = properties.getProperty("name") ?: id,
                        version = properties.getProperty("version")?.takeIf(String::isNotEmpty),
                        sourceUrl = properties.getProperty("sourceUrl") ?: return@runCatching null,
                        sha256 = properties.getProperty("sha256") ?: return@runCatching null,
                        installedAtEpochMs = properties.getProperty("installedAt")?.toLongOrNull() ?: 0L,
                        enabled = properties.getProperty("enabled")?.toBooleanStrictOrNull() ?: true,
                    ),
                    script,
                )
            }.getOrNull()
        }
    }

    override fun put(plugin: StoredPlugin) {
        directory.mkdirs()
        val base = safeName(plugin.metadata.id)
        val scriptTarget = File(directory, "$base.js")
        val metadataTarget = File(directory, "$base.properties")
        val scriptTemp = File(directory, "$base.js.tmp")
        val metadataTemp = File(directory, "$base.properties.tmp")
        scriptTemp.writeText(plugin.script)
        Properties().apply {
            setProperty("id", plugin.metadata.id)
            setProperty("name", plugin.metadata.name)
            setProperty("version", plugin.metadata.version.orEmpty())
            setProperty("sourceUrl", plugin.metadata.sourceUrl)
            setProperty("sha256", plugin.metadata.sha256)
            setProperty("installedAt", plugin.metadata.installedAtEpochMs.toString())
            setProperty("enabled", plugin.metadata.enabled.toString())
        }.also { props -> metadataTemp.outputStream().use { props.store(it, "NomaTune MusicFree plug-in") } }
        if (!scriptTemp.renameTo(scriptTarget)) throw MusicFreeException("Unable to store plug-in script")
        if (!metadataTemp.renameTo(metadataTarget)) {
            scriptTarget.delete()
            throw MusicFreeException("Unable to store plug-in metadata")
        }
    }

    override fun remove(id: String): Boolean {
        val base = safeName(id)
        val scriptRemoved = File(directory, "$base.js").let { !it.exists() || it.delete() }
        val metadataRemoved = File(directory, "$base.properties").let { !it.exists() || it.delete() }
        return scriptRemoved && metadataRemoved
    }

    private fun safeName(id: String): String = sha256Hex(id.toByteArray()).take(32)
}
