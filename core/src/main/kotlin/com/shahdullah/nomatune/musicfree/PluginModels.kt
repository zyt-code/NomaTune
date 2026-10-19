/*
 * NomaTune (2026)
 * © Shahdullah — github.com/shahdullah
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.shahdullah.nomatune.musicfree

import java.security.MessageDigest

data class SubscriptionPlugin(
    val url: String,
    val declaredName: String? = null,
    val declaredVersion: String? = null,
    val description: String? = null,
)

data class ParsedSubscription(
    val sourceUrl: String,
    val plugins: List<SubscriptionPlugin>,
    val rejected: List<RejectedPlugin> = emptyList(),
)

data class RejectedPlugin(val url: String?, val reason: String)

data class PluginMetadata(
    val id: String,
    val name: String,
    val version: String?,
    val sourceUrl: String,
    val sha256: String,
    val installedAtEpochMs: Long,
    val enabled: Boolean = true,
)

data class StoredPlugin(
    val metadata: PluginMetadata,
    val script: String,
)

fun sha256Hex(value: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }

class MusicFreeException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)
