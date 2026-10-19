/*
 * NomaTune (2026)
 * © Shahdullah — github.com/shahdullah
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package com.shahdullah.nomatune.musicfree

import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.security.MessageDigest

/**
 * A deliberately small MusicFree CommonJS runtime.
 *
 * It exposes no Java classes, Android context, files, cookies, tokens or network client. Network
 * access is supplied separately by a permission-aware host bridge in the Android layer. Rhino is
 * kept in interpreter mode so instruction callbacks can terminate runaway scripts.
 */
class RhinoMusicFreeRuntime(
    private val limits: PluginLimits = PluginLimits(),
    private val httpBridge: PluginHttpBridge? = null,
) : PluginRuntime {
    private val factory = BoundedContextFactory(limits.maxExecutionMillis)

    override fun inspect(script: String): RuntimePluginInfo {
        requireSize(script)
        return factory.runBounded { cx ->
            // Compile for syntax validation but do not execute untrusted top-level initialization.
            // Real MusicFree bundles commonly call require("axios"/"crypto-js") at top level;
            // metadata inspection must not grant those modules or trigger network/side effects.
            cx.compileString(normalizeAsyncSyntax(script), "musicfree-plugin.js", 1, null)
            val name = stringMetadata(script, "platform")
                ?: stringMetadata(script, "name")
                ?: throw MusicFreeException("Plug-in does not declare platform/name")
            val id = stringMetadata(script, "id") ?: stableId(name)
            val capabilities = KNOWN_FUNCTIONS.filterTo(linkedSetOf()) { function ->
                Regex("""(?m)\b${Regex.escape(function)}\s*[:=]""").containsMatchIn(script) ||
                    Regex("""(?m)\b(?:async\s+)?function\s+${Regex.escape(function)}\s*\(""").containsMatchIn(script)
            }
            RuntimePluginInfo(id, name, stringMetadata(script, "version"), capabilities)
        }
    }

    override fun invoke(script: String, function: String, argumentsJson: String): String {
        requireSize(script)
        if (function !in KNOWN_FUNCTIONS) throw MusicFreeException("Unsupported plug-in function")
        return factory.runBounded { cx ->
            val exports = evaluate(cx, script)
            val callable = ScriptableObject.getProperty(exports, function) as? BaseFunction
                ?: throw MusicFreeException("Plug-in does not provide $function")
            val parsedArguments = cx.evaluateString(
                exports,
                "JSON.parse(${quote(argumentsJson)})",
                "arguments.json",
                1,
                null,
            )
            val arguments = when (parsedArguments) {
                is Scriptable -> (0 until parsedArgumentsIds(parsedArguments))
                    .map { parsedArguments.get(it, parsedArguments) }
                    .toTypedArray()
                else -> arrayOf(parsedArguments)
            }
            val result = callable.call(cx, exports, exports, arguments)
            if (result.javaClass.simpleName.contains("Promise", ignoreCase = true)) {
                throw MusicFreeException("Asynchronous plug-in functions require the Android HTTP bridge")
            }
            val holder = cx.newObject(exports)
            ScriptableObject.putProperty(holder, "value", result)
            Context.toString(cx.evaluateString(holder, "JSON.stringify(value)", "result.json", 1, null))
        }
    }

    private fun evaluate(cx: Context, script: String): Scriptable {
        cx.setClassShutter { false }
        val scope = cx.initSafeStandardObjects(null, false)
        httpBridge?.let { bridge ->
            val hostFunction = object : BaseFunction() {
                override fun call(
                    context: Context,
                    functionScope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<out Any>,
                ): Any = bridge.execute(Context.toString(args.firstOrNull() ?: "{}"))
            }
            ScriptableObject.putProperty(scope, "__hostHttp", hostFunction)
            cx.evaluateString(scope, AXIOS_SHIM, "musicfree-host.js", 1, null)
        }
        val module = cx.newObject(scope)
        val initialExports = cx.newObject(scope)
        ScriptableObject.putProperty(module, "exports", initialExports)
        ScriptableObject.putProperty(scope, "module", module)
        ScriptableObject.putProperty(scope, "exports", initialExports)
        ScriptableObject.putProperty(scope, "__hostHash", hostHashFunction())
        cx.evaluateString(scope, HOST_PRELUDE, "musicfree-host-prelude.js", 1, null)
        cx.evaluateString(scope, "'use strict';\n${normalizeAsyncSyntax(script)}", "musicfree-plugin.js", 1, null)
        return ScriptableObject.getProperty(module, "exports") as? Scriptable
            ?: throw MusicFreeException("Plug-in must assign an object to module.exports")
    }

    private fun hostHashFunction(): BaseFunction = object : BaseFunction() {
        override fun call(
            cx: Context,
            scope: Scriptable,
            thisObj: Scriptable,
            args: Array<out Any>,
        ): Any {
            val algorithm = when (Context.toString(args.getOrNull(0) ?: "SHA-256")) {
                "MD5", "SHA-1", "SHA-256" -> Context.toString(args[0])
                else -> throw MusicFreeException("Unsupported plug-in hash")
            }
            val value = Context.toString(args.getOrNull(1) ?: "")
            return MessageDigest.getInstance(algorithm)
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }

    private fun requireSize(script: String) {
        if (script.toByteArray(Charsets.UTF_8).size > limits.maxPluginBytes) {
            throw MusicFreeException("Plug-in is too large")
        }
    }

    private fun Scriptable.stringProperty(name: String): String? {
        val value = ScriptableObject.getProperty(this, name)
        return if (value == Scriptable.NOT_FOUND || value == null) null else Context.toString(value)
    }

    private fun parsedArgumentsIds(arguments: Scriptable): Int =
        arguments.ids.filterIsInstance<Int>().maxOrNull()?.plus(1) ?: 0

    private fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }

    private fun stableId(name: String): String =
        name.lowercase().replace(Regex("[^a-z0-9._-]+"), "-").trim('-').ifEmpty {
            "plugin-${sha256Hex(name.toByteArray()).take(12)}"
        }

    private fun stringMetadata(script: String, key: String): String? =
        Regex("""(?m)\b${Regex.escape(key)}\s*:\s*(["'])(.*?)\1""")
            .find(script)
            ?.groupValues
            ?.get(2)
            ?.trim()
            ?.takeIf(String::isNotEmpty)

    /** MusicFree's axios bridge is synchronous on Android, so async/await is lowered accordingly. */
    private fun normalizeAsyncSyntax(script: String): String = script
        .replace(Regex("""\basync\s+"""), "")
        .replace(Regex("""\bawait\s+"""), "")

    private class BoundedContextFactory(private val timeoutMs: Long) : ContextFactory() {
        private val deadlineNanos = ThreadLocal<Long>()

        override fun makeContext(): Context = super.makeContext().apply {
            optimizationLevel = -1
            instructionObserverThreshold = 10_000
            languageVersion = Context.VERSION_ES6
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            if (System.nanoTime() > (deadlineNanos.get() ?: Long.MAX_VALUE)) {
                throw MusicFreeException("Plug-in execution exceeded ${timeoutMs}ms")
            }
        }

        fun <T> runBounded(block: (Context) -> T): T {
            deadlineNanos.set(System.nanoTime() + timeoutMs * 1_000_000)
            return try {
                call { cx -> block(cx) }
            } finally {
                deadlineNanos.remove()
            }
        }
    }

    companion object {
        private val KNOWN_FUNCTIONS = setOf("search", "getMediaSource", "getLyric", "getAlbumInfo", "getMusicSheetInfo")
        private const val AXIOS_SHIM = """
            function __musicfreeRequest(config) {
              var response = JSON.parse(__hostHttp(JSON.stringify(config || {})));
              response.statusText = response.statusText || '';
              response.config = config || {};
              return response;
            }
            function __axios(config) { return __musicfreeRequest(config); }
            __axios.get = function(url, config) {
              config = config || {}; config.url = url; config.method = 'GET';
              return __musicfreeRequest(config);
            };
            __axios.post = function(url, data, config) {
              config = config || {}; config.url = url; config.data = data; config.method = 'POST';
              return __musicfreeRequest(config);
            };
            __axios.request = __axios;
            __axios.create = function() { return __axios; };
            __axios.default = __axios;
            __axios.defaults = { timeout: 5000 };
            __axios.interceptors = { response: { use: function() {} }, request: { use: function() {} } };
        """

        /**
         * MusicFree injects require/env/process. Only the modules that real plug-ins commonly need
         * for signing and HTML/query parsing are provided, and none of them can touch files,
         * Java classes, cookies or application credentials.
         */
        private const val HOST_PRELUDE = """
            function __bytesToHex(bytes) {
              var out = '';
              for (var i = 0; i < bytes.length; i++) out += ('0' + (bytes[i] & 255).toString(16)).slice(-2);
              return out;
            }
            function __bytesToBase64(bytes) {
              var table = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';
              var out = '';
              for (var i = 0; i < bytes.length; i += 3) {
                var a = bytes[i] & 255, b = i + 1 < bytes.length ? bytes[i + 1] & 255 : 0, c = i + 2 < bytes.length ? bytes[i + 2] & 255 : 0;
                var n = (a << 16) | (b << 8) | c;
                out += table[(n >> 18) & 63] + table[(n >> 12) & 63] +
                  (i + 1 < bytes.length ? table[(n >> 6) & 63] : '=') +
                  (i + 2 < bytes.length ? table[n & 63] : '=');
              }
              return out;
            }
            function __textBytes(value) { return new TextEncoder().encode(String(value)); }
            function __hash(algorithm, value) {
              return String(__hostHash(algorithm, String(value)));
            }
            var __CryptoJS = {
              MD5: function(value) { return { toString: function() { return __hash('MD5', value); } }; },
              SHA1: function(value) { return { toString: function() { return __hash('SHA-1', value); } }; },
              SHA256: function(value) { return { toString: function() { return __hash('SHA-256', value); } }; },
              enc: {
                Hex: {},
                Utf8: { parse: function(value) { return String(value); }, stringify: function(value) { return String(value); } },
                Base64: {
                  stringify: function(value) { return __bytesToBase64(__textBytes(value)); },
                  parse: function(value) { return String(value); }
                }
              }
            };
            __CryptoJS.MD5.toString = function() { return ''; };
            var __he = {
              decode: function(value) {
                return String(value)
                  .replace(/&nbsp;/g, ' ').replace(/&amp;/g, '&').replace(/&lt;/g, '<')
                  .replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&#39;/g, "'")
                  .replace(/&#(\d+);/g, function(_, code) { return String.fromCharCode(Number(code)); });
              },
              encode: function(value) {
                return String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');
              }
            };
            var __qs = {
              stringify: function(value) {
                var parts = [];
                Object.keys(value || {}).forEach(function(key) {
                  var item = value[key];
                  if (item === undefined || item === null) return;
                  parts.push(encodeURIComponent(key) + '=' + encodeURIComponent(String(item)));
                });
                return parts.join('&');
              },
              parse: function(value) {
                var result = {};
                String(value || '').replace(/^\?/, '').split('&').forEach(function(part) {
                  if (!part) return;
                  var index = part.indexOf('=');
                  var key = decodeURIComponent(index < 0 ? part : part.slice(0, index));
                  result[key] = index < 0 ? '' : decodeURIComponent(part.slice(index + 1));
                });
                return result;
              }
            };
            function __unsupported(name) { throw new Error('Unsupported safe module: ' + name); }
            function require(name) {
              if (name === 'axios') {
                if (typeof __axios !== 'function') throw new Error('Plug-in network access is not enabled');
                return __axios;
              }
              if (name === 'crypto-js') return __CryptoJS;
              if (name === 'he') return __he;
              if (name === 'qs') return __qs;
              if (name === 'dayjs') {
                var dayjs = function() { return { format: function() { return ''; }, unix: function() { return 0; } }; };
                dayjs.unix = function() { return dayjs(); };
                dayjs.default = dayjs;
                return dayjs;
              }
              if (name === '@react-native-cookies/cookies') {
                var unavailable = function() { throw new Error('Plug-in cookie storage is not available'); };
                return { get: unavailable, set: unavailable, flush: unavailable, default: null };
              }
              __unsupported(name);
            }
            var env = {
              getUserVariables: function() { return {}; },
              get userVariables() { return {}; },
              os: 'android',
              lang: 'zh-CN'
            };
            env.getUserVariables.userVariables = {};
            var process = { platform: 'android', env: env };
        """
    }
}

/** JSON-only capability; implementations must independently enforce URL, header and size policy. */
fun interface PluginHttpBridge {
    fun execute(requestJson: String): String
}
