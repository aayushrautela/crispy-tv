# Plan: Extract shared media-source layer (`:android:addons`) — full repackage

## Status: implemented (pending compile verification) — TV wiring next

## Decision record
Extraction with **full repackaging** into the new module's own namespace. The earlier
idea of preserving old packages across modules ("split packages") was rejected after
review: AndroidX api guidelines require unique namespaces that match the module root
package; Kotlin `internal` visibility silently breaks across same-named packages in
different modules; and split packages permanently blur where code lives. One-time
mechanical import churn beats permanent ambiguity. No split packages anywhere.

## Current dependency picture (measured)

```
core-domain ──► domain.metadata.* (MetadataRecord, MetadataMediaType, candidates)
:android:player ──► interfaces: EpisodeListProvider, WatchHistoryService,
                    MetadataLabResolver, SupabaseSyncLabService + PlaybackIdentity,
                    MetadataLabMediaType          [shared lib, TV depends already]
:android:backend ──► CrispyBackendClient (+ MetadataView models)   [shared lib]
:android:app ──► EVERYTHING else today:
    streams/AddonStreamsService.kt        1086 ln   addon protocol (manifest+streams+subs)
    metadata/MetadataAddonRegistry.kt      559 ln   registry/enabled/ordering (pure!)
    streams/StreamResolver.kt              131 ln   cache facade (+ per-app Provider object)
    playback/StreamLookupSupport.kt        175 ln   id matching/finalize helpers
    metadata/RemoteMetadataLabDataSource   521 ln   implements player-lib interface w/ backend
    metadata/BackendEpisodeListProvider     52 ln   implements player-lib interface
    metadata/RemoteSupabaseSyncLabService   82 ln   implements player-lib interface
    metadata/MetadataViewMappings.kt       200 ln   backend MetadataView → MediaDetails/MediaVideo
    MediaDetails / MediaVideo models                 defined INSIDE home/HomeCatalogService.kt:69,96
                                                     imported by 25 files across details/playerui/streams
    streams/SelectorCoordinator.kt         153 ln   selection state machine (flow-only logic)
    streams/StreamSelectorState.kt          29 ln   ui-state models
    streams/StreamSelectorContent.kt       521 ln   phone Compose UI            ← STAYS (app)
    streams/SelectorChrome.kt               18 ln   phone Compose UI            ← STAYS (app)
```

## Target module & package layout

Module **`:android:addons`** — namespace `com.crispy.tv.addons`.
Deps: `network`, `player`, `backend`. No Compose deps (`@Immutable` dropped,
strong-skipping infers stability — same precedent as AiInsightsModels).

```
android/addons/src/main/kotlin/com/crispy/tv/addons/
├── registry/   MetadataAddonRegistry, AddonManifestSeed        (ex com.crispy.tv.metadata)
├── streams/    AddonStreamsService, AddonStream, AddonSubtitle,
│               ProviderStreamsResult, StreamProviderDescriptor,
│               StreamResolver                                   (ex com.crispy.tv.streams)
├── lookup/     StreamLookupTarget, StreamLookupSupport          (ex com.crispy.tv.playback)
│               + toAddonLookupId/buildAddonEpisodeLookupId/
│                 toMetadataLabMediaTypeOrNull                   (ex com.crispy.tv.metadata)
├── sources/    RemoteMetadataLabDataSource, BackendEpisodeListProvider,
│               RemoteSupabaseSyncLabService                     (ex com.crispy.tv.metadata;
│                                                                implement :android:player ifs)
├── model/      MediaDetails, MediaVideo                         (ex home/HomeCatalogService.kt:69-96)
└── mapping/    MetadataViewMappings (toMediaDetails …)          (ex com.crispy.tv.metadata)
```

Stays in app (phone-owned UI/wiring):
`StreamSelectorContent.kt`, `SelectorChrome.kt`, bottom-sheet wiring, and all
provider singletons reading `BuildConfig.METADATA_ADDON_URLS`
(`StreamResolverProvider` body becomes app-side construction of addons-module classes).
`home/HomeCatalogService.kt` keeps the catalog pipeline minus the two extracted models.
Result: package `com.crispy.tv.streams` continues to exist ONLY in the app (UI), so no
split packages remain anywhere.

## Import rewrite map (mechanical, scripted)

| Old | New |
|---|---|
| `com.crispy.tv.metadata.AddonManifestSeed` / `.MetadataAddonRegistry` | `com.crispy.tv.addons.registry.*` |
| `com.crispy.tv.metadata.toAddonLookupId` / `.buildAddonEpisodeLookupId` / `.toMetadataLabMediaTypeOrNull` | `com.crispy.tv.addons.lookup.*` |
| `com.crispy.tv.metadata.toMediaDetails` (MetadataViewMappings fns) | `com.crispy.tv.addons.mapping.*` |
| `com.crispy.tv.metadata.RemoteMetadataLabDataSource` / `.BackendEpisodeListProvider` / `.RemoteSupabaseSyncLabService` | `com.crispy.tv.addons.sources.*` |
| `com.crispy.tv.streams.AddonStreamsService` / `.AddonStream` / `.AddonSubtitle` / `.ProviderStreamsResult` / `.StreamProviderDescriptor` / `.StreamResolver` | `com.crispy.tv.addons.streams.*` |
| `com.crispy.tv.playback.*` | `com.crispy.tv.addons.lookup.*` |
| `com.crispy.tv.home.MediaDetails` / `.MediaVideo` | `com.crispy.tv.addons.model.*` |

Files needing import updates (pre-measured): 25× MediaDetails/MediaVideo importers,
plus `AppGraph.kt`, `PlaybackDependencies.kt`, `HomeSelectorViewModel.kt`,
`CalendarMetaEpisodeService.kt`, `AddonsSettingsScreen.kt`, `SelectorCoordinator.kt`,
`DetailsUseCases.kt`, `PlayerSessionViewModel.kt`. Wildcard-free explicit imports make
the rewrite exact-match safe.

## Steps

1. `settings.gradle.kts`: include `:android:addons`; create module build file
   (android library, namespace `com.crispy.tv.addons`, minSdk 26, compileSdk 37,
   Java 21; deps network/player/backend).
2. `git mv` the ten source files into the new tree; apply package renames inside files;
   cut `MediaDetails`/`MediaVideo` out of `HomeCatalogService.kt` into `model/MediaModels.kt`;
   strip `@Immutable`; remove `StreamResolverProvider` from `StreamResolver.kt`.
3. Scripted import rewrite across both apps (map above), then hand-fix any leftovers
   surfaced by compilation.
4. Re-point phone provider wiring (`PlaybackDependencies.kt`, `AppGraph.kt`,
   `HomeSelectorViewModel.kt`) to construct addons classes directly.
5. `android/app/build.gradle.kts`: add `implementation(project(":android:addons"))`.
6. Verify: `gradle :android:app:assembleDebug :android:addons:assembleDebug
   :android:contract-tests:test` + lint both apps.

## TV payoff (immediately after extraction)

1. `TvServices`: build `MetadataAddonRegistry` + `AddonStreamsService` + `StreamResolver`
   from `BuildConfig.METADATA_ADDON_URLS` (TV has this BuildConfig field already).
2. Detail Play flow: build `StreamLookupTarget`, fetch provider results, render D-pad
   side panel of sources.
3. Selected `AddonStream.url` feeds existing `play/{itemId}?streamUrl=` route;
   torrent/debrid URLs route through `native-engine`.

## Risks / notes

- Big-but-mechanical diff (~40 files); every change is import-line or package-line
  except the HomeCatalogService model cut-out and provider re-pointing.
- `RemoteMetadataLabDataSource` Context usage — verify prefs/cache at move time.
- core-domain untouched → contract fixtures unaffected.
- No split packages introduced or left behind.
