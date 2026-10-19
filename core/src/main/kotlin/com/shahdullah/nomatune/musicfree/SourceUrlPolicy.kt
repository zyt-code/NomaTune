/*
 * NomaTune (2026)
 * © Shahdullah — github.com/shahdullah
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.shahdullah.nomatune.musicfree

import java.net.IDN
import java.net.InetAddress
import java.net.URI

/**
 * Network policy for untrusted subscription and plug-in URLs.
 *
 * HTTPS is the default. HTTP is deliberately opt-in per host because some existing music1.json
 * feeds contain legacy cleartext URLs. URL credentials, fragments, local names and private IPs
 * are always rejected. Redirect targets must be validated again by the caller.
 */
class SourceUrlPolicy(
    cleartextHostAllowlist: Set<String> = emptySet(),
) {
    private val cleartextHosts = cleartextHostAllowlist.map(::normalizeHost).toSet()

    fun requireSubscriptionUrl(raw: String): URI {
        val uri = requireRemoteUrl(raw, allowCleartext = false)
        val path = uri.path.lowercase()
        if (SUPPORTED_SUBSCRIPTION_SUFFIXES.none(path::endsWith)) {
            throw MusicFreeException("Subscription URL must end in .json, .jsons or .listensoul.json")
        }
        return uri
    }

    fun requirePluginUrl(raw: String): URI = requireRemoteUrl(raw, allowCleartext = true)

    fun requireMediaUrl(raw: String): URI = requireRemoteUrl(raw, allowCleartext = true)

    private fun requireRemoteUrl(raw: String, allowCleartext: Boolean): URI {
        val uri = runCatching { URI(raw.trim()) }
            .getOrElse { throw MusicFreeException("Invalid URL", it) }
        val scheme = uri.scheme?.lowercase() ?: throw MusicFreeException("URL scheme is required")
        val host = uri.host?.let(::normalizeHost) ?: throw MusicFreeException("URL host is required")
        if (uri.userInfo != null || uri.fragment != null) {
            throw MusicFreeException("Credentials and fragments are not allowed in source URLs")
        }
        if (isLocalHost(host)) throw MusicFreeException("Local and private hosts are not allowed")
        when (scheme) {
            "https" -> Unit
            "http" -> if (!allowCleartext || host !in cleartextHosts) {
                throw MusicFreeException("Cleartext HTTP is not allowed for $host")
            }
            else -> throw MusicFreeException("Only HTTP(S) source URLs are supported")
        }
        return uri
    }

    private fun isLocalHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".local")) return true
        val looksLiteral = host.any { it == ':' } || host.all { it.isDigit() || it == '.' }
        // Do not resolve normal DNS names here; callers also validate the actual connected address.
        val literal = if (looksLiteral) runCatching { InetAddress.getByName(host) }.getOrNull() else null
        return looksLiteral && literal != null &&
            (literal.isAnyLocalAddress || literal.isLoopbackAddress || literal.isLinkLocalAddress || literal.isSiteLocalAddress)
    }

    private fun normalizeHost(host: String): String = IDN.toASCII(host.trim().trimEnd('.')).lowercase()

    companion object {
        val SUPPORTED_SUBSCRIPTION_SUFFIXES = setOf(".json", ".jsons", ".listensoul.json")
    }
}
