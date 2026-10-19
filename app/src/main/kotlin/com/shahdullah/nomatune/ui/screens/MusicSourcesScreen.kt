/*
 * NomaTune (2026)
 * © Shahdullah — github.com/shahdullah
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.shahdullah.nomatune.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.shahdullah.nomatune.musicfree.AndroidMusicFreeManager
import com.shahdullah.nomatune.musicfree.PluginMetadata
import com.shahdullah.nomatune.musicfree.SourceTrack
import com.shahdullah.nomatune.musicfree.toResolvedMediaItem
import com.shahdullah.nomatune.LocalDatabase
import com.shahdullah.nomatune.LocalPlayerConnection
import com.shahdullah.nomatune.db.entities.LyricsEntity
import com.shahdullah.nomatune.playback.queues.ListQueue
import kotlinx.coroutines.launch

@Composable
fun MusicSourcesScreen(navController: NavController) {
    val context = LocalContext.current
    val manager = remember(context) { AndroidMusicFreeManager.get(context) }
    val scope = rememberCoroutineScope()
    val playerConnection = LocalPlayerConnection.current
    val database = LocalDatabase.current
    var url by remember { mutableStateOf("") }
    var plugins by remember { mutableStateOf<List<PluginMetadata>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<SourceTrack>>(emptyList()) }
    LaunchedEffect(manager) { plugins = manager.installed() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Text("MusicFree sources", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Import a music1.json subscription. Plug-ins run locally in a restricted JavaScript runtime; application credentials are never exposed.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("HTTPS subscription URL") },
                supportingText = { Text(".json, .jsons and .listensoul.json are supported") },
                singleLine = true,
                enabled = !importing,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = url.isNotBlank() && !importing,
                    onClick = {
                        importing = true
                        message = null
                        scope.launch {
                            runCatching { manager.importSubscription(url) }
                                .onSuccess { result ->
                                    plugins = manager.installed()
                                    message = "Installed ${result.installed}, updated ${result.replaced}, unchanged ${result.unchanged}, rejected ${result.rejected}." +
                                        if (result.failures.isEmpty()) "" else " ${result.failures.size} failed."
                                }
                                .onFailure { message = it.message ?: "Import failed" }
                            importing = false
                        }
                    },
                ) { Text("Import") }
                if (importing) CircularProgressIndicator()
            }
            message?.let { Text(it, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall) }
        }
        item {
            OutlinedButton(onClick = { navController.navigate("local_songs") }) { Text("Open local music") }
        }
        if (plugins.isEmpty()) {
            item { Text("No plug-ins installed", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(plugins, key = PluginMetadata::id) { plugin ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(plugin.name, style = MaterialTheme.typography.titleMedium)
                        Text(plugin.version?.let { "Version $it" } ?: "Version not declared", style = MaterialTheme.typography.bodySmall)
                        Text(plugin.sourceUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(
                            onClick = {
                                manager.remove(plugin.id)
                                plugins = manager.installed()
                            },
                            modifier = Modifier.padding(top = 8.dp),
                        ) { Text("Remove") }
                    }
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                Text("Search installed sources", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Song, artist or album") },
                    singleLine = true,
                    enabled = !searching,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Button(
                    enabled = query.isNotBlank() && !searching,
                    onClick = {
                        searching = true
                        scope.launch {
                            runCatching { manager.search(query) }
                                .onSuccess { (tracks, failures) ->
                                    results = tracks
                                    message = when {
                                        failures.isNotEmpty() -> "${tracks.size} results; ${failures.size} sources failed. ${failures.first()}"
                                        else -> "${tracks.size} results"
                                    }
                                }
                                .onFailure { message = it.message ?: "Search failed" }
                            searching = false
                        }
                    },
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Search") }
            }
            items(results, key = { "${it.sourceId}:${it.sourceTrackId}" }) { track ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.then(with(this) { Modifier.weight(1f) })) {
                            Text(track.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                track.artists.joinToString(", ").ifBlank { "Unknown artist" },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            track.album?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                        Button(
                            enabled = playerConnection != null,
                            onClick = {
                                scope.launch {
                                    runCatching {
                                        val playback = manager.resolve(track)
                                        val mediaItem = track.toResolvedMediaItem(playback)
                                        manager.lyrics(track)?.takeIf(String::isNotBlank)?.let { lyrics ->
                                            database.query {
                                                upsert(LyricsEntity(mediaItem.mediaId, lyrics, LyricsEntity.Source.REMOTE.value))
                                            }
                                        }
                                        playerConnection?.playQueue(ListQueue(track.title, listOf(mediaItem)))
                                    }.onFailure { message = it.message ?: "Unable to play track" }
                                }
                            },
                        ) { Text("Play") }
                    }
                }
            }
        }
    }
}
