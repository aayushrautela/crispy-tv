# Android TV app

`:android:tv` is the television build of Crispy: a plain `com.android.application`
that ships one APK to a TV box or a Leanback television. It is not a Kotlin
Multiplatform module, so it has no `commonMain` and no `commonTest`; its tests are
plain JUnit on the JVM, and they can only assert decisions that are pure.

Everything the app shows comes from the shared KMP modules -- `:home`, `:addons`,
`:player`, `:backend`, `:core-domain`, `:native-engine`. What lives here is the
television surface: the screens, the view models, and the rules that only make sense
with a D-pad.

---

## The focus contract

A television has no pointer. **Every action is a key**, and the screen is responsible
for saying which key does what -- visibly, on screen, while the chrome is up.

| Key | Home | Source picker | Player |
| --- | --- | --- | --- |
| Centre / OK | **play** the focused card | pick the focused source | play/pause, or retry when there is an error |
| Long-press | the card's menu (play / open details / remove) | -- | -- |
| Left / Right | move along a rail | -- | seek ∓10 s |
| Up / Down | move between rails | move between rows | wake the chrome / open the options sheet |
| Back | open the sidebar | back | close the sheet, then leave the player |

The rules that follow from that:

1. **Every row is a focus target.** A row that cannot be landed on is a row the
   viewer cannot pick. Focused rows say so: they grow a little and draw a border
   (`TvSourcesScreen`).
2. **Nothing is a dead end.** "Play" from home used to open a player with no stream;
   it now opens the source picker.
3. **OK plays, and the details are one long-press away** rather than one screen away.
   The hero keeps both buttons because both are already on screen.
4. **A sheet is part of the state.** The open sheet lives in the view model, because
   the keys it handles depend on it (`TvPlayerOverlay`).
5. **The chrome hides itself, but never while a sheet is open.** A sheet the viewer
   cannot see is a sheet they cannot use.

## What "play" means for a home card

A home card knows more than an item id, and the difference decides **which episode
is played** (`TvHomePlayTarget`):

- a **Continue Watching** row names the episode the viewer was watching: its imdb id
  turns into the episode lookup id (`tt1234567:2:5`), and the season and the episode
  travel with it;
- a **calendar** row names the episode that aired but carries no imdb id, so the
  season and the episode travel alone and the picker resolves the episode's id from
  the series' episode list (`TvSourcesViewModel.resolveEpisodeLookupId`);
- any other card names its title and leaves the rest to the picker.

Without this, the picker would ask the providers for the series' *next* episode --
its rule when it knows nothing else -- and the episode the viewer meant would be
missed.

## The identity rule

Where a viewer's position is filed decides whether the phone and the TV can find each
other's progress. **The rule is the phone's rule**, because the phone writes the
progress the TV reads:

- a movie is filed under the item id;
- an episode is filed under the **title/series id** plus the season and episode --
  `<series id>:<season>:<episode>`;
- the season and the episode come out of the **episode lookup id**
  (`tt1234567:2:5`), which is the only place they survive between the detail
  screen, the picker and the player -- and out of the pick itself, because a
  lookup id cannot always be built (`TvPlaybackSelection.season/episode` is read
  when the lookup id names no episode).

So `parseAddonEpisodeLookupId` lives in `:addons` `commonMain` and is used by both
apps, and `TvPlayerViewModel` builds its `PlaybackIdentity` with the title id -- not
with the episode's own id.

Progress is written through `:player`'s `WatchHistoryService` (the same local store
the phone reads, under the same `watch_progress` key), not through the backend
directly. The TV used to send its own events under its own key, which is why the two
apps disagreed about where a viewer had got to.

## The state inventory

Each surface has one view model and one state object. The state is a **reading** of
the source of truth, never a second copy of it.

| Surface | View model | State | Source of truth |
| --- | --- | --- | --- |
| Home | `TvHomeViewModel` | `HomeUiState` | `:home`'s `HomeRefreshCoordinator` and `buildHomeLayoutState` -- the phone's own home model |
| Source picker | `TvSourcesViewModel` | `SourcesUiState` | `:addons`' `StreamResolver`, ordered by `TvSourceOrdering` |
| Player | `TvPlayerViewModel` | `TvPlayerUiState` | `:native-engine`'s `NativePlaybackController`, read through `PlaybackSessionController.snapshot()` |

## What is shared with the phone, and through which port

`:tv` must not depend on `:app`: `:app` is the phone's UI and its graph, and the two
have nothing to say to each other. What they share are **values and ports**:

| Shared thing | Port | Owner |
| --- | --- | --- |
| Home model and layout | `HomeUiState`, `buildHomeLayoutState` | `:home` |
| Stream resolution | `StreamResolver` | `:addons` |
| A stream becomes a playable source | `AddonStream.resolvePlaybackSource(torrentResolver, sessionId)` | `:addons` `commonMain` |
| Torrent engine | `TorrentResolver` | `:player` `commonMain`, implemented by `:android:torrent-engine` |
| Playback engine | `PlaybackSessionController` | `:core-domain` `commonMain`, implemented by `:native-engine` |
| Watch progress | `WatchHistoryService`, `PlaybackIdentity` | `:player` `commonMain` |

When a *decision* has to be shared -- rather than a value -- it moves into a KMP
module's `commonMain`, where `commonTest` can pin it and where the phone, the TV and
the desktop all get it. That is how `resolvePlaybackSource` and
`parseAddonEpisodeLookupId` came to exist.

## How the pick reaches the player

A pick used to travel as a route argument (`play/{itemId}?streamUrl={streamUrl}`).
That cannot carry a provider's request headers and cannot carry the stream's sidecar
subtitles -- which is why a stream that needed a `Referer` played on the phone and
not on the TV.

Now the picker hands the player a **`TvPlaybackSelection`**: the **stream itself** --
not a URL -- plus the lookup it came from. It is held by
`TvPlaybackSelectionStore`, named by an opaque id, and the route carries the id
(`play/{itemId}?sel={id}`). The pick is *taken*, not read, so a stale pick cannot be
replayed by a later visit to the player.

The stream is carried whole on purpose. A URL cannot carry a provider's request
headers, and it cannot carry the stream's sidecar subtitles -- which is why a stream
that needed a `Referer` played on the phone and not on the TV. And a magnet link is
not playable at all until the torrent engine has resolved it, so the TV resolves it
**at play time**, where it can show "preparing" and report a failure the way every
other failure is reported, instead of refusing the row in the picker.

## Torrents on the TV

The TV app **ships the torrent engine**. `:android:torrent-engine` is a service and
a resolver, not a UI, and the TV already takes the YouTube extractor the same way --
the TV has been a sideload-only surface all along. So a magnet row in the picker is
offered, and picking it resolves the magnet through `NativeTorrentResolver` and plays
the local stream.

The rule that decides it is shared, not re-implemented: `resolvePlaybackSource` in
`:addons` `commonMain` plays a stream directly when it has a plain URL and asks the
engine only when it does not. The phone goes through the same function, so the two
apps cannot disagree about which row needs the engine.

Two things a local build needs:

- the TorrServer native binary (`libtorrserver.so`) is **not in the repository**. It
  is fetched by `.github/scripts/fetch-torrserver-binaries.sh` into the gitignored
  `android/native-engine/src/main/jniLibs/`, and CI runs that script before it
  assembles. Without it the engine has no binary to run;
- the engine's foreground `TorrentService` merges into the app from the library's own
  manifest, so nothing has to be declared here.

**Distribution:** a TV build for Google Play would need the phone's `distribution`
flavour axis, because Play does not allow the torrent engine in a store build. The
phone already has that axis; the TV does not, and adding it is release work, not
playback work.

## Building and testing

```bash
./gradlew :android:tv:assembleDebug     # the APK
./gradlew :android:tv:test              # the module's JVM tests
./gradlew :android:tv:lintDebug         # lint
```

A local build that is meant to play a torrent also needs the TorrServer binary,
which is fetched rather than committed:

```bash
.github/scripts/fetch-torrserver-binaries.sh
```

Both `check-local.sh` and `.github/workflows/android.yml` run `:android:tv:test`:
the module had a test suite that nothing ran, so a broken test was
indistinguishable from a passing one.

The module's `applicationId` is **`com.crispy.tv.tv`**, not `com.crispy.tv`: the
phone's store build ships under the latter, and two apps cannot share an id.

## Known gaps

- **There is no `MediaSession`.** The system media controls do not see the TV
  player yet; a `MediaSessionService` has to be registered in the manifest first.
- **The TV build has no `distribution` flavour axis.** It takes the torrent engine
  and the YouTube extractor unconditionally, which is right for a sideload build and
  wrong for a Play build.
- **Search, Library and Settings are placeholders** ("Phase 5").
- **The player has no next-episode button.** The engine and the picker are in
  place; the navigation between episodes is not.
- **Nothing here has been compiled or run.** The changes landed without a build,
  so the first thing to do on a machine with Gradle is
  `./gradlew :android:tv:assembleDebug` and a manual D-pad pass.
