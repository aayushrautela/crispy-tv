# Player Fix Plan — port reference-player behavior at ground level

Goal: fix the player by mirroring the reference implementation (Nuvio) as closely as
possible, at ground level (no new abstraction layers on top), removing dead code,
and without using the word "nuvio" in any identifier. We will not compile; logic
must be correct but minor compile friction is acceptable.

## Current gaps (verified)
- Loading: `PlayerOverlay.kt:199` play button is always shown; `PlayerLoadingCurtain`
  (`PlayerLoadingCurtain.kt:46-52`) shows spinner + Text whenever `isBuffering`.
  No mutual exclusion -> play icon and loading spinner appear together, with text.
- Seek feedback: `PlayerGestureFeedback.kt:73` centers the feedback pill
  (`Alignment.Center`).
- Zoom: only hard-coded `RESIZE_MODE_FIT` at `PlayerRoute.kt:154`. No enum, no
  cycle, no persistence, no button.
- Subtitles: engine already supports external subs
  (`PlaybackSource.externalSubtitles`, `NativePlaybackController.setExternalSubtitle`,
  MPV `sub-add`). But `AddonStream` (`AddonStreamsService.kt:28`) has no `subtitles`
  field and there is no addon-subtitle fetch, so `externalSubtitles` is always empty
  -> "addons not fetching subtitles" and broken subtitle selection.

---

## 1. Loading: never show play + spinner together, drop the text
Modify `PlayerOverlay.kt`
- Gate the center `FilledIconButton` (`:199-214`) on `!uiState.isBuffering`
  (when buffering, render nothing in that slot).
- Keep the buffering `LaunchedEffect` (`:106-118`) showing `PlayerLoadingCurtain`.

Modify `PlayerLoadingCurtain.kt:46-52`
- Remove the `Text` composable; keep only the spinner/`LoadingIndicator`.
  This kills "text with loading icon". Reuse existing indicator, do not add a new one.

## 2. Double-tap seek feedback -> top-center
Modify `PlayerGestureFeedback.kt:73`
- Change `Box(contentAlignment = Alignment.Center)` into a full-size `Box` wrapping
  a child `Box(Modifier.align(Alignment.TopCenter).padding(top = 40.dp, horizontal = 16.dp),
  contentAlignment = Alignment.Center)`. Mirror reference `PlayerPlaybackOverlays.kt:119-123`.
- Keep `GestureFeedbackMessage(text, icon)` as-is (Forward10 / Replay10 already used).

## 3. Zoom / resize mode (new enum + engine + persistence + button)
New file `playerui/PlayerResizeMode.kt`
- `enum class PlayerResizeMode { Fit, Fill, Zoom }`
- `fun next()` (Fit -> Fill -> Zoom -> Fit)
- `val label: String` (Fit/Fill/Zoom)

Persistence — extend `PlaybackSettingsRepository.kt` (do NOT create a new prefs layer)
- Add `PLAYBACK_SETTINGS_KEY_RESIZE_MODE = "resize_mode"`, default `Fit`.
- Add `resizeMode: PlayerResizeMode` to `PlaybackSettings` + `readSettings` + `OBSERVED_KEYS`.
- Add `setResizeMode(mode)` mirroring existing setters.

Engine — `PlaybackController.kt` + `NativePlaybackController.kt` (ground level)
- Add `applyResizeMode(mode: PlayerResizeMode)` to `PlaybackSessionController`.
- MPV impl: `panscan` 0.0 / 1.0 / 0.5 and `video-aspect-override = "no"`.
- EXO impl: no-op at controller (applied on the view).

`PlayerRoute.kt`
- Read initial `resizeMode` from `PlaybackSettingsRepositoryProvider`, pass into
  session / `PlayerUiState`.
- EXO `AndroidView`: in the `update` lambda set
  `playerView.resizeMode = uiState.resizeMode.toExoResizeMode()`.
- MPV: `LaunchedEffect(uiState.resizeMode) { playbackController.applyResizeMode(...) }`.
- Add `PlayerResizeMode.toExoResizeMode()` -> `RESIZE_MODE_FIT / FILL / ZOOM`.

UI — `PlayerOverlayControls.kt` (`PlayerBottomControls`)
- Add params `onCycleResizeMode: () -> Unit`, `resizeModeLabel: String`.
- Add a `PlayerActionButton` (icon `ic_player_aspect_ratio.xml`, copied from reference)
  next to the subtitle button (`:169-173`).
- `PlayerOverlay.kt` wires `onCycleResizeMode = session::cycleResizeMode` and passes label.
- `PlayerSessionViewModel`: add `resizeMode` to `PlayerUiState`, `fun cycleResizeMode()`
  (updates state + `PlaybackSettingsRepository.setResizeMode` + gesture feedback label).

Resources: add `ic_player_aspect_ratio.xml` (copied) + strings
`player_resize_fit` / `player_resize_fill` / `player_resize_zoom`.

## 4. Subtitles — two ground-level sources

### 4A. Stream-level subtitles (primary, Stremio convention)
- `AddonStreamsService.kt:28` `AddonStream`: add
  `val subtitles: List<StreamSubtitle> = emptyList()`, where
  `data class StreamSubtitle(val url: String, val lang: String?, val name: String?)`.
- In stream JSON parsing, read the `subtitles` array (each: `url`, `lang`/`language`,
  `name`/`title`) and map to `StreamSubtitle`.
- Where `AddonStream` -> `PlaybackSource` is built (PlayerActivity / StreamLookupSupport),
  map `stream.subtitles` -> `PlaybackSource.externalSubtitles` (`PlaybackExternalSubtitle`).
- Result: after load, engine already lists external subs in `subtitleTracks`
  (shown in `PlayerTrackSheet`) and MPV auto-selects the first. No new UI needed.

### 4B. Addon subtitle-resource fetch (OpenSubtitles-style) — faithful port
New file `playerui/SubtitleRepository.kt`
- `data class AddonSubtitle(val id: String, val url: String, val language: String,
  val display: String, val addonName: String? = null, val isSelected: Boolean = false)`
- `object SubtitleRepository` with `StateFlow`s `addonSubtitles`, `isLoading`, `error`
  and `fun fetchAddonSubtitles(type: String, videoId: String)`.
- Iterate enabled addons that expose a `subtitles` resource (built on Crispy's
  `MetadataAddonRegistry` + `CrispyHttpClient`, already used by `AddonStreamsService`)
  -> build `{base}/subtitles/{type}/{id}.json` -> fetch -> parse `subtitles` array
  (id / url / lang) -> emit `AddonSubtitle`. Equivalent of the reference
  `SubtitleRepository` + addon helpers, adapted to Crispy's addon infra (Crispy has
  no generic `AddonRepository`; reuse `MetadataAddonRegistry` to enumerate
  manifests/resources).

Wire into player
- `PlayerUiState`: add `addonSubtitles`, `addonSubtitlesLoading`, `addonSubtitlesError`,
  `selectedAddonSubtitleId`.
- `PlayerTrackSheet.kt`: in the Subtitle tab add a "Search / refresh subtitles" action
  (calls `onFetchAddonSubtitles`) and a list of `addonSubtitles`; selecting one calls
  `onSelectAddonSubtitle(AddonSubtitle)` -> `session.setExternalSubtitle(url, lang, name)`
  (already exists at `PlayerSessionViewModel.kt:271`) and records the selection.
- `PlayerOverlay.kt` / `PlayerRoute.kt`: pass new callbacks + state.

---

## Files
Create:
- `playerui/PlayerResizeMode.kt`
- `playerui/SubtitleRepository.kt`
- `res/drawable/ic_player_aspect_ratio.xml`

Modify:
- `PlayerOverlay.kt`
- `PlayerOverlayControls.kt`
- `PlayerGestureFeedback.kt`
- `PlayerLoadingCurtain.kt`
- `PlayerRoute.kt`
- `PlayerSessionViewModel.kt`
- `PlayerTrackSheet.kt`
- `PlaybackController.kt`
- `NativePlaybackController.kt`
- `MpvPlaybackRuntime.kt`
- `AddonStreamsService.kt`
- `PlaybackSettingsRepository.kt`
- `AddonStream` model
- `strings.xml`

Remove (dead / useless):
- The loading `Text` in `PlayerLoadingCurtain`.
- The always-visible play button during buffering.
- Any now-redundant `statusMessage` overlay logic tied to buffering text.
- Keep `PlayerLoadingCurtain` as spinner-only.

## Open assumption
Crispy's addon model differs from the reference (no generic `AddonRepository` with
`subtitles` resources). 4B reuses `MetadataAddonRegistry` + `CrispyHttpClient` to
enumerate subtitle-capable addons. If only the immediate stream-subtitle fix is
wanted, 4A alone resolves "addons not fetching subtitles" for the common Stremio
case and is much smaller.
