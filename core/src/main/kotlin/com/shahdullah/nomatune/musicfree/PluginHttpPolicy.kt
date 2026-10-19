/*
 * NomaTune (2026)
 * © Shahdullah — github.com/shahdullah
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.shahdullah.nomatune.musicfree

data class SafePluginHttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String>,
)

class PluginHttpPolicy(private val urlPolicy: SourceUrlPolicy) {
    fun sanitize(method: String, url: String, headers: Map<String, String>): SafePluginHttpRequest {
        val normalizedMethod = method.uppercase()
        if (normalizedMethod !in ALLOWED_METHODS) throw MusicFreeException("Plug-in HTTP method is not allowed")
        val safeHeaders = headers.mapNotNull { (name, value) ->
            val normalizedName = name.trim().lowercase()
            if (normalizedName !in ALLOWED_HEADERS || value.contains('\n') || value.contains('\r')) null
            else normalizedName to value.take(MAX_HEADER_VALUE_CHARS)
        }.toMap()
        return SafePluginHttpRequest(
            normalizedMethod,
            urlPolicy.requireMediaUrl(url).toASCIIString(),
            safeHeaders,
        )
    }

    companion object {
        private const val MAX_HEADER_VALUE_CHARS = 4_096
        private val ALLOWED_METHODS = setOf("GET", "POST")
        private val ALLOWED_HEADERS = setOf(
            "accept",
            "accept-language",
            "content-type",
            "origin",
            "referer",
            "user-agent",
        )
    }
}
