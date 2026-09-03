# Stream Plugins (JS) — Implementation Plan

Status: planned. Feature name: **Plugins**. Package: `com.crispy.tv.plugins`.
Scope: stream extraction plugins written in JavaScript, installed from user-added repository manifests, synced via the existing account addon sync. FOSS Android flavor only (phone + TV) in v1.

## Goals / non-goals

In scope:
- JS stream plugins loaded from user-added repo manifests
- Plugin lifecycle: add repo, browse providers, enable/disable, refresh, sync across devices
- Results merged into the existing stream pipeline (SelectorCoordinator)

Out of scope (v1):
- iOS/tvOS runtime (sideload-only builds are v2)
- Cloudflare solving beyond external solvers (FlareSolverr-style HTTP services)
- WASM bridge
- DRM links (`newDrmExtractorLink` equivalent)
- Plugin-authored catalogs / subtitles (streams only)
- TV settings UI (TV uses plugins; management happens on phone)

License guardrails:
- Nuvio is GPL-3.0: reference API shape only, never lift code (clean-room).
- `quickjs-kt` (com.dokar.quickjs) is MIT and is the intended engine.

## Locked architecture decisions

| Decision | Choice | Rationale |
|---|---|---|
| Engine | `quickjs-kt` | MIT, KMP, small native lib, has interrupt handler for timeouts |
| Flavor gate | New `:android:plugins` module; play source-set ships stub (`PluginsRuntimeSupport = false`) | Mirrors `TrailerFlavorCapabilities` + `NewPipeExtractor` pattern |
| TV module | `missingDimensionStrategy("distribution", "foss")` on the new module | `:android:tv` is always FOSS; no stub variant needed there |
| Plugin contract | Nuvio-compatible `getStreams(tmdbId, mediaType, season, episode)` | Existing Nuvio community plugins run unmodified |
| Storage | Plugin code + repos cached on-device; sync stores enabled-provider records, not full repos | Per-device install, per-account enablement |
| Sync safety | Unknown addon types ignored, never deleted (Android play flavor and iOS) | Prevents the Nuvio #1190 clobber bug class |
| Stream integration | Plugins join `SelectorCoordinator` as another provider source | Zero player/UI rework; headers via existing `PlatformPlaybackDataSourceFactory` |

## Plugin contract v1

A plugin is a single JS file. It may export via `module.exports.getStreams` or a
global `getStreams` (both resolve; `module.exports` wins). Required export:

```js
async function getStreams(tmdbId, mediaType, season, episode) {
  // tmdbId: string; mediaType: "movie"|"tv"; season/episode: number|undefined
  return [{
    title?, name?, url?,           // url may be a string or { url }; infoHash allowed instead of url
    quality?, size?,               // size: human string ("1.2 GB") or bytes number
    language?, provider?, type?, seeders?, peers?, infoHash?,
    headers?, referer?,
    subtitles?: [{ url, language?, lang?, name?, headers? }],
    audio?, filename?
  }];
}

// optional
async function onSettings() { /* returns settings layout JSON */ }
```

## Host bridge surface (v1)

| Bridge | Signature / shape | Host impl |
|---|---|---|
| `fetch(url, opts)` | `{status, headers, body, bodyBase64?}` | CrispyHttpClient / OkHttp |
| `dom.select(html, selector)`, `dom.selectFirst` | `[{ text, attrs: {} }]` | Jsoup |
| `crypto` | md5/sha1/sha256 (hex/base64), AES-CBC/ECB enc+dec | javax.crypto |
| `storage.get/set/delete` | per-plugin key/value | DataStore, scoped prefix |
| `url` | parse / encode / resolve | java.net.URI |
| `log(msg)` | Logcat `[plugin:id]` tag | — |
| settings | `onSettings()` layout schema (text/toggle/select) | persisted per plugin |

Hard limits:
- 60s per execution
- 2 MB string cap in/out of any bridge
- Fresh isolate per execution; QuickJS interrupt handler for runaway loops
- Per-provider failure isolation: a throwing plugin yields zero streams, never propagates
- SSRF guard: `fetch` rejects loopback / private-IP / `.local` targets

## Repo / manifest format

```json
{
  "name": "...",
  "description": "...",
  "scrapers": [{
    "id", "name", "version", "filename",
    "supportedTypes",
    "logo"?, "contentLanguage"?, "hasSettings"?, "disabledPlatforms"?
  }]
}
```

- Scraper JS resolved relative to manifest URL
- Refresh interval: 6h, plus manual refresh in settings
- Code cache: `filesDir/plugins/<repoHash>/<scraperId>.js`, overwrite-on-update with atomic rename

## Server changes (crispy-server, ~1.5 days)

| File | Change |
|---|---|
| `migrations/0062_addons_typed.sql` | `addon_type text NOT NULL DEFAULT 'stremio'` with `CHECK (addon_type IN ('stremio','jsplugin'))`; `payload jsonb NOT NULL DEFAULT '{}'` for per-type fields (jsplugin: `repoUrl`, `scraperId`, `version`, `name`) |
| `src/http/contracts/addon.ts` | `addonItemSchema` / `addonCreateBodySchema` gain `type` enum (follow `profiles.ts` enum pattern) |
| `src/modules/users/addon.service.ts`, `addon.repo.ts` | Accept/return type + payload; server remains a dumb bookmark store (never fetches manifests or plugin code) |
| `src/http/routes/addons.test.ts`, `openapi/public-app.v1.yaml` | Tests + regenerated spec |

`manifest_url` semantics for jsplugin rows: stores the repo manifest URL; uniqueness stays per (account, url).

## Android client work

### New module `:android:plugins`

```
com.crispy.tv.plugins
 ├─ runtime/   PluginRuntime (isolate lifecycle, timeout, interrupt), JsonBridge marshalling
 ├─ bridges/   HttpBridge, DomBridge, CryptoBridge, StorageBridge, UrlBridge
 ├─ repo/      PluginManifestClient, PluginCodeStore, PluginRepositoryStore (Room)
 ├─ streams/   PluginStreamsService (runs enabled plugins per lookup, maps results)
 └─ PluginFlavorCapabilities.kt  (foss=true / play=false)
```

### Stream pipeline integration

- `PluginStreamsService.load(tmdbId, imdbId, mediaType, season, episode, title, year)` runs enabled plugins concurrently (cap 4, same semaphore discipline as `AddonStreamsService`), maps each result into `AddonStream` with `providerId = "plugin:<scraperId>"`, merged `headers+referer`, `stableKey = hash(url+headers)`
- Registered as an additional provider source inside `SelectorCoordinator.resolve()` alongside `StreamResolver`; results flow through existing `onProviderResult` and stream list UI
- Cancellation: `SelectorCoordinator.dismiss()` cancels coroutine scope → interrupts isolates

### Settings UI (foss-only)

- New `PluginsSettingsScreen` under Settings → INTEGRATIONS, next to Addons
- Add repo by pasted URL → manifest fetch → provider list with per-provider toggles; enabling downloads code and pushes sync record
- Remove provider → delete local code + push removal
- play flavor: row hidden entirely via capability flag

### Sync

- `AddonDto` gains `type`, `payload`; `HouseholdAddonsCloudSync` push/pull branches on type
- Unknown-type guard: rows with unrecognized `type` round-trip untouched; server wins on conflict
- Fresh device pull: re-fetch manifest from `repoUrl`, resolve `scraperId`, download code, enable

## iOS this cycle

- `ios/CrispyKit` backend client: tolerate unknown addon `type` values (decode as opaque/ignored, never fail)
- No runtime, no UI. Sideload build with the same QuickJS core = v2

## Testing

- `python3 scripts/validate_contracts.py`
- `gradle :android:contract-tests:test`
- `swift test --package-path ios/ContractRunner`
- JVM tests: bridge JSON marshalling, manifest parsing, code-store round-trip, unknown-type sync guard, ADDONSTREAM mapping
- Integration: one hand-written reference plugin bundled in dev assets; end-to-end `getStreams` → playable stream
- TV + iOS placeholder build gates stay green

## Phases

| # | Phase | Estimate | Unlock check |
|---|---|---|---|
| 1 | Server schema + client DTO + unknown-type guard | 1.5 d | — |
| 2 | Runtime core (quickjs-kt, isolate, timeout, interrupt, marshalling) | 3 d | `eval("1+1")` through bridge |
| 3 | Bridges (http/dom/crypto/storage/url) + SSRF guard | 2–3 d | fetch from JS in a test |
| 4 | Repo client + code store + refresh | 2 d | toggle → file on disk |
| 5 | Stream bridge into SelectorCoordinator | 2–3 d | reference plugin returns playable stream in-app |
| 6 | Settings UI (foss-only) | 2 d | add repo → enable → second device sees it |
| 7 | Sync wiring + cross-device round trip | 1–2 d | TV pull shows phone providers |
| 8 | (stretch) Community-format compat alias layer | 2–3 d | a public community repo provider works unmodified |

Core total: ~13–15 days solo. Phase 8 is deliberately last and optional; phases 1–7 are fully self-contained.

## Risk register (ranked)

1. quickjs-kt ABI coverage across arm/arm64/x86/x86_64 devices — validated in Phase 2; fallout: restrict to arm64
2. Bridge API drift vs community expectations — Phase 8 mitigates; bundled reference plugin keeps the contract honest
3. Play review safety — runtime is absent from play variant by construction; add a CI grep step proving `quickjs` native libs never enter play APKs
4. Plugin failure storms — per-provider isolation + zero-stream fallback keeps details pages alive
5. Plugin-setting layout churn (hasSettings) — keep schema minimal (text/toggle/select) and version it
