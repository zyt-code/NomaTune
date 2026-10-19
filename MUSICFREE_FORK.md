# MusicFree source fork

This repository is a GPL-3.0 fork of NomaTune. It deliberately keeps the upstream Material 3 Expressive UI, Media3 player, local library, downloads, lyrics, Last.fm and ListenBrainz integration. The normal product entry points no longer expose YouTube Music, Spotify or other built-in online discovery providers. The home destination is now the MusicFree source manager and the remaining search surface is local-only.

## Architecture

`core/musicfree` is independent from Android UI and provider-specific models:

- `MusicSource` is the stable search, playback-resolution and lyric contract.
- `SubscriptionParser` accepts remote `.json`, `.jsons` and `.listensoul.json` manifests and reads `plugins[].url`. It preserves `name`, `version` and `desc` only as untrusted hints; installed metadata always comes from the downloaded plug-in itself.
- `PluginInstaller` downloads with a byte limit, inspects the script, requires search and playback capabilities, computes SHA-256 and installs through `PluginRegistry`.
- `DirectoryPluginStorage` persists scripts and metadata with hash-derived filenames. `PluginRegistry` deduplicates identical script hashes and atomically replaces a matching provider id.
- `RhinoMusicFreeRuntime` is the initial JavaScript engine. Installation syntax-checks a lowered copy without running top-level code (important for bundles that call `require` during initialization). Execution uses interpreter mode, instruction-based deadlines, a deny-all `ClassShutter`, and safe standard objects. Because the Android bridge is synchronous, `async`/`await` syntax is lowered before Rhino parsing. It does not expose Android `Context`, Java classes, files, application cookies, Last.fm/ListenBrainz tokens or any other app credential.
- `MusicFreeAdapter` and `JsonCodec` keep all Rhino values behind a JSON boundary before data reaches player/UI code.

The Android `AndroidMusicFreeManager` composes these pieces. It uses a dedicated no-cookie HTTP client, disables automatic redirects, validates every redirect URL and bounds subscription, script and response sizes. Installed plug-ins live in the app-private `files/musicfree/plugins` directory.

## `music1.json` compatibility

The compatible manifest shape is:

```json
{
  "plugins": [
    { "url": "https://example.invalid/provider.js", "name": "hint", "version": "hint", "desc": "hint" }
  ]
}
```

The original app inferred a subscription from `.json`, `.jsons` or `.listensoul.json`, downloaded every `plugins[].url`, and did not rely on the manifest's name/version/description. This fork follows those semantics but intentionally differs in these safety-sensitive areas:

1. Subscription URLs must be HTTPS.
2. Plug-in URLs must be HTTPS by default. HTTP entries are rejected and reported instead of enabling application-wide cleartext. `SourceUrlPolicy` supports an explicit per-host allowlist for a future informed-consent UI, but this build does not silently allow the five known HTTP entries in the sample subscription.
3. URL credentials, fragments, localhost and literal private/link-local addresses are rejected. Redirects are revalidated.
4. Subscriptions are limited to 256 KiB/100 entries, scripts to 512 KiB, HTTP responses to 2 MiB, redirects to four, and each script execution or HTTP call to 15 seconds.
5. Plug-ins cannot access host Java/Android APIs or app credentials. A narrow permission-aware HTTP bridge must be added before network-dependent plug-in functions are enabled.

## Current compatibility boundary

This change establishes the buildable native source layer, secure subscription installer, persistent registry and product routing. Synchronous CommonJS MusicFree plug-ins can be inspected and invoked through the adapter. The source-management screen can import and remove HTTPS plug-ins.

The Android HTTP bridge is now attached. It accepts GET and POST, revalidates every redirect, strips cookies and credential headers, and returns an axios-shaped `{status,data,headers}` object. `async`/`await` is lowered because the bridge is synchronous. The runtime also provides the small MusicFree module set that signing and parsing plug-ins need: `axios`, `crypto-js` (MD5, SHA-1 and SHA-256), `qs`, `he` and `dayjs`. File, cookie, WebDAV and Java access stay closed, and `env.getUserVariables()` returns an empty object so app tokens are not readable.

Search results preserve non-reserved plug-in fields. Those fields travel with the Media3 item and are sent back to `getMediaSource(musicItem, "standard")` and `getLyric(musicItem)`. Duration is converted from the protocol's seconds into player milliseconds. If the in-memory playback URL is gone after a process restart, `MusicService` asks the installed plug-in again instead of falling through to the YouTube resolver. The source screen can import an HTTPS `music1.json` subscription, search installed plug-ins and play a result through the existing Media3 queue.

The upstream YouTube implementation and related screens still exist as unreachable compatibility code because deleting the `core` Innertube graph in the same change would destabilize database entities, queue restoration and retained UI. Product navigation, online search selection, manifest URL handlers and YouTube package queries are disabled. Removal should proceed after queue/database migrations prove that existing local libraries and downloads remain recoverable.

## Verification

Run:

```shell
./gradlew :core:test :app:compileGmsMobileUniversalDebugKotlin
```

Tests cover manifest parsing, URL policy, hash/id registry semantics, disk storage path safety, Rhino capability detection/deadline termination and the runtime/JSON adapter boundary.
