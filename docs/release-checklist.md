# Release checklist

Manual verification that automation cannot do. Run this before shipping any
build to a store or handing an APK/IPA to a user.

Automated gates already cover: contract fixtures, domain unit tests, golden
screenshots, distribution exclusions (both dependency graph and built APK/AAB),
purity of `commonMain`, and the Apple compile/test job. See
`./check-local.sh` and `AGENTS.md`. This file covers what they cannot see.

## Which build am I testing?

The two flavors are **not** feature-equivalent. Decide first, because several
sections only apply to one.

| Capability | `store` | `sideload` |
|---|---|---|
| Torrent / magnet playback | **absent** | present |
| QuickJS stream plugins | **absent** | present |
| Plugin settings screen | hidden | present |
| YouTube inline trailer in hero | **absent** | present |

If you are testing `store`, the sections marked *(sideload only)* do not apply.
If you are testing `sideload`, all of them do.

## 1. Install and first run

- [ ] Fresh install over a previous version of the same flavor (upgrade path).
- [ ] Fresh install on a clean device / cleared app data.
- [ ] Uninstall and reinstall; no crash on first launch.
- [ ] App icon, label and splash/intro render correctly.
- [ ] No crash in logcat on startup: `adb logcat -c` then launch, then
      `adb logcat -d *:E`.

## 2. Auth

- [ ] Sign up with a new account.
- [ ] Sign in with an existing account.
- [ ] Wrong password shows an error, does not crash, does not hang.
- [ ] Sign out, then confirm protected screens require sign-in again.
- [ ] Token survives an app restart.
- [ ] *(sideload only)* Profile name validation rejects an empty name.

## 3. Home

- [ ] Home loads and shows rows.
- [ ] Pull to refresh.
- [ ] Scroll a long list; no dropped frames severe enough to notice.
- [ ] Hero row renders, and tapping a hero opens details.
- [ ] Back from details returns to the same scroll position.
- [ ] Empty state renders when there is no data (test with a new account).

## 4. Catalog and search

- [ ] Open a catalog; pagination loads more on scroll.
- [ ] Filters apply and can be cleared.
- [ ] Search returns results and handles a query with no matches.
- [ ] Search with a title containing a space and a non-ASCII character
      (e.g. `dune part two`, `千と千尋`) — the URL encoder must escape both.
- [ ] Genre glyphs render for several genres; none shows a blank or wrong icon.

## 5. Details

- [ ] Details loads for a movie and for a series.
- [ ] Cast, metadata and artwork render.
- [ ] Episode list for a series; selecting an episode starts playback.
- [ ] *(sideload only)* YouTube trailer plays inline in the hero.
- [ ] *(store)* The inline YouTube trailer is absent rather than broken.

## 6. Playback

- [ ] Play a stream; video and audio both start.
- [ ] Seek forwards and backwards.
- [ ] Pause and resume.
- [ ] Audio track and subtitle track selection works.
- [ ] Subtitle styling toggle works.
- [ ] Rotate/resize behaviour is correct for the device.
- [ ] Stopping playback returns to the previous screen.
- [ ] Playback survives a brief network drop and recovers.
- [ ] *(sideload only)* Play a magnet link; the torrent service starts and the
      stream resolves.
- [ ] *(store)* Attempting a magnet link reports that torrenting is not
      available in this build. It must **fail clearly**, not silently hang or
      show a generic stream error.

## 7. Watch progress and continue watching

- [ ] Progress is written during playback and appears in continue watching.
- [ ] Resume continues from the saved position, not from zero.
- [ ] Marking an episode watched removes it from continue watching.
- [ ] Deleting a title updates the row.

## 8. Sync *(signed in)*

- [ ] Watch progress syncs to another signed-in session.
- [ ] Addons/lists sync.
- [ ] Pull-to-sync does not duplicate entries.
- [ ] Offline: queue an action, go offline, come back, confirm it flushes once.

## 9. Plugins *(sideload only)*

- [ ] Plugin settings screen is reachable.
- [ ] Install a plugin; it appears in the list.
- [ ] Enable/disable takes effect.
- [ ] Remove a plugin; it disappears and no longer contributes streams.
- [ ] A plugin returning a bad script surfaces an error rather than crashing.
- [ ] *(store)* The plugin settings entry is not present anywhere in the UI.

## 10. Settings and distribution behaviour

- [ ] Theme and playback settings persist across restart.
- [ ] *(store)* No UI anywhere offers torrent, plugin, or YouTube-trailer
      features.
- [ ] *(store)* Uninstalling and reinstalling does not reveal a hidden entry
      point.

## 11. Build and packaging

- [ ] `./check-local.sh` is green.
- [ ] `./gradlew :android:androidApp:assembleStoreRelease :android:androidApp:assembleSideloadRelease :android:tv:assembleRelease` green.
- [ ] Lint clean: `:android:androidApp:lintStoreDebug :android:androidApp:lintSideloadDebug :android:tv:lintDebug`.
- [ ] Store AAB uploads to Play Console and passes pre-launch report.
- [ ] Sideload APK installs over the previous version without a signature error.
- [ ] Release signing uses the release keystore, not the debug one.
- [ ] `scripts/verify_apk_distribution.py` passes against the release artefacts.

## 12. Apple *(on a Mac)*

- [ ] `swift test --package-path ios/ContractRunner` green.
- [ ] `xcodegen generate --spec ios/project.yml` then both schemes build.
- [ ] KMP Apple targets compile and their tests pass:
      `./gradlew :android:core-domain:iosSimulatorArm64Test`.
- [ ] iOS app runs on a simulator; tvOS app runs on a tvOS simulator.

## 13. Accessibility and locales

- [ ] TalkBack/VoiceOver reaches interactive elements and announces them.
- [ ] Focus order is sensible with a D-pad (TV) and with a touchscreen.
- [ ] Text remains legible at the largest font scale.
- [ ] Layout does not clip with long titles in every supported language.
