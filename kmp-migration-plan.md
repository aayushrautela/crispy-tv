# Plan: Crispy TV — Kotlin Multiplatform for Android, Android TV, iOS, and Desktop

**Revised:** 2026-09-30 (rev 3). Supersedes all prior revisions of this file.
**Verified against:** working tree at `4b180fd4`.

**How to read this file.** §0 (the goal and the non-negotiables), §4 (the toolchain facts
that will bite you), §5 (the source-set strategy) and the *standing* parts of §6 are
decisions — do not re-derive them, they are true until something measured contradicts
them. **§1's counts are measurements and they go stale by design**; re-measure rather than
trust them, using the pair printed there. A landing that moves files should refresh them
in the same commit, because the alternative is a plan whose only verified section is wrong.

---

## 0. Goal — read this before anything else

**One Kotlin codebase. One product. Five runnable targets.**

| Target | UI toolkit | Status |
|---|---|---|
| **Android** (phone/tablet) | Compose Multiplatform | in production today |
| **Android TV** (10-foot) | Compose, TV-specific layout | shipping today |
| **iOS / iPadOS** | **SwiftUI shell + Compose content, Liquid Glass** | shell exists, not wired to shared code |
| **Desktop — Windows, macOS, Linux** | Compose Multiplatform (Desktop JVM) | seam proven; renders `:app` code; not an app |

Windows, macOS and Linux are **one** target, not three: they all run on the JVM, so a single `desktopApp` module produces all three — `.msi`/`.exe`, `.dmg`/`.pkg`, `.deb`/`.rpm`.

**Non-negotiables, settled:**

- **Nothing is deferred to "later".** Every phase below is required. There is no phase 8+, no "if we get to it", no row marked optional.
- **Production grade.** No stubs, no null-returning placeholders, no TODO-as-design.
- **GPL.** mpv / libVLC / VLCJ / MPVKit all acceptable.
- **Players stay Android-only** — Media3 + libmpv in `:native-engine`. This is a *deliberate architecture*, not an omission: desktop uses VLCJ/mpv, iOS uses AVPlayer. `:player` keeps portable interfaces, so the per-platform choice is an implementation swap, never a rewrite.
- **Two permanent flavors:** `store` (Play / App Store) and `sideload` (APK / IPA). Torrent engine, QuickJS plugin runtime and YouTube extractor are **structurally excluded** from `store` — separate modules only `sideload` depends on. Never a runtime `if`.

---

## 1. Status — what is actually done

Verified against the working tree, not remembered. **Last reconciled 2026-09-30.**

**Read the counts below as of the reconciliation date and re-measure before
planning.** They are the one part of this file that is *meant* to go stale —
everything else is a standing decision. The measuring pair, whose two `commonMain`
halves must agree:

```sh
find android/app/src/commonMain -name '*.kt' | wc -l
git ls-files android/app/src/commonMain | grep -c '\.kt$'
git ls-files android/app/src/androidMain | grep -c '\.kt$'
```

| Phase | State |
|---|---|
| 0 — Foundations | **done** — toolchain, wrapper, version catalog, dead deps purged, 4 manual CI workflows, golden-screenshot gate, distribution guards |
| 1 — Shells + prove the seam | **done** — `:androidApp` split off, contract suite in `core-domain/commonTest`, desktop seam proof |
| 2 — Data layer | **done** — all six modules KMP; no `java.time`, no inline clocks, 9 `commonMain` source sets clean |
| 3 — Flavors + config | **done** — one source of truth for the version across `:androidApp` and generated `AppConfig` |
| 4 — Shared UI | **in progress** — resources done, `commonMain` path proven; **110 of 191 `:app` files moved** |
| 5 — Desktop, full | **started** — `:desktopApp` depends on `:app` and renders **two** of its `commonMain` screens, `ContinueWatchingRail` and `ImageSettingsScreen`, and constructs **all six** `platform-core` ports through `:android:platform-desktop`, so the seam is no longer a claim about compilation. It is still 3 screens' worth of code, not an app: no navigation seam, and the settings screen is reachable only from a placeholder affordance in the window |
| 6 — iOS + Liquid Glass | SwiftUI shell exists; never built against shared code. `CrispyUI` is built and exported and **nothing imports it** — `grep -rn "import CrispyUI" ios/` returns nothing |
| 7 — Harden | Apple CI done; the rest not |

**Where `:app` stands, and the honest shape of the remainder.** The 80 files still in
its `androidMain` are **not** 80 independent jobs. A reverse audit — forbidden imports
first, then subtracting every type declared in every module's `commonMain` — leaves
**10 candidates and resolves 0 of them**. Every one is pinned by a measured wall:
`androidx.navigation` (six nav-graph files, which §Phase 4 already says should stay put),
`paging-compose` (`DiscoverScreen`, `library/LibraryRoute`), the composition root,
:android:native-engine` (the four `playerui` files), and `org.json` — which was recorded
here as permanent and **is not**: the node type is **decided**, `kotlinx.serialization.json.JsonElement`,
and `WatchProgressStore.kt` was the first file it moved. That file was 405 lines of `androidMain`
with **not one `android.*` import** — it already took the four `:platform-core` ports, so only the
JSON parsing pinned it. It is now `commonMain`, and its 44 cases run on **both** `desktopTest` and
`testAndroidHostTest` from `commonTest` with no Robolectric and no `org.json` on either classpath.
**The JSON-only remainder is five files**, re-measured rather than decremented.
So Phase 4's remaining file count is a misleading measure of the work left, and Phase 5
is not waiting on Phase 4.

**Where the files are, measured per module.** The `commonTest` column is the one that
was missing and is the reason four modules held untested `commonMain`; `git ls-files
<module>/src/commonTest | grep -c '\.kt$'` settles it per module in one command.

| Module | `commonMain` | `androidMain` | `commonTest` |
|---|---|---|---|
| `core-domain` | 29 | 0 | 29 |
| `platform-core` | 7 | 0 | 1 |
| `sharedUI` | 5 | 0 | 1 |
| `player` | 6 | 0 | 2 |
| `addons` | 10 | 5 | 1 |
| `backend` | 17 | 1 | 5 |
| `home` | 13 | 5 | 6 |
| `network` | 3 | 4 | 1 |
| `watchhistory` | 5 | 1 | 3 |
| **`:app`** | **110** | **83** | **41** |

**Seven of these ten rows were wrong when this table was last refreshed, and
the total was wrong in both directions.** `backend` was three landings stale, `home`
was short one `androidMain` file, `core-domain`'s `commonTest` count was short by
one, and `:app` was short two — so the `:app` row alone had drifted far enough to
move the total by 13 files while every individual row still looked plausible.
**A per-module table is only refreshed by re-running the measurement over every
row; patching the rows the current landing touched leaves the others wrong, and a
row that is one file out is indistinguishable from a row that is right.**

`:player` had this shape until its landing and was the sharpest of the findings,
because unlike `:addons` **its `androidMain` is empty** — nothing about it looked
untestable. `:network` was the second, and it is the mirror image: **all four of its
`androidMain` files are OkHttp/`Context` adapters that no `commonMain` can ever
reach**, which makes the module read as Android-only, while its `commonMain` is 27
lines of pure string handling whose regexes a hand-rolled implementation gets wrong
quietly. It now has 27 cases.

**`:watchhistory` was recorded as a measured non-finding, and the measurement was
correct about `commonMain` and wrong about the module.** Its two `commonMain` files
were `WatchHistoryConfig.kt` — a single field defaulting to `"dev"` — and
`sync/WatchSyncSource.kt`, a three-method interface with empty bodies, so a suite
there would have re-asserted the compiler. **A module with no test source set is a
question, not a defect** — and the answer here was wrong, because the question was
asked of `commonMain` when the module was the subject. The module had no test source
set of any kind, and the unmeasured content was 405 lines of `androidMain`.

**It now has 5 `commonMain` files, 3 `commonTest` files and exactly 1 `androidMain`
file, and its 44 cases run on two targets** — `desktopTest` and `testAndroidHostTest`
both report 44, from the one `commonTest` source set, which is the shape this whole
plan argues for. **All three of its former `androidMain` files are accounted for, and
only one of them was ever blocked by its subject matter:** `WatchProgressStore` moved
when the JSON node type changed; `BackendWatchHistoryService` moved with **no
substance change at all** — zero `android.*` imports, zero OkHttp, zero
`System.currentTimeMillis`, and all 27 of its imports resolving in a `commonMain`
source set — because its receiver, `CrispyBackendClient`, had only just become
`commonMain`; and `OkHttpWatchSyncSource.kt` **is still `androidMain` and is blocked
by OkHttp**, implementing a `commonMain` interface. **The middle one is the finding:
a 681-line file sat two landings behind a receiver, and nothing about it looked
pinned until the receiver moved.**

| Module | `commonMain` | `androidMain` |
|---|---|---|
| `core-domain` | 29 | 0 |
| `platform-core` | 7 | 0 |
| `sharedUI` | 5 | 0 |
| `player` | 6 | 0 |
| `addons` | 10 | 5 |
| `backend` | 17 | 1 |
| `home` | 13 | 5 |
| `network` | 3 | 4 |
| `watchhistory` | 5 | 1 |
| **`:app`** | **110** | **83** |
| **total** | **205** | **99** |

**`:app` is no longer the only module that matters, and every other module is now
*finished* rather than "near its resting point".** `addons`, `backend`, `home`,
`network` and `watchhistory` have all crossed over — `home` and `backend` have more
files in `commonMain` than in `androidMain`, and `backend` and `watchhistory` are
down to **one** `androidMain` file each, both of them pinned by a transport rather
than by anything structural. `:app` is 110 of 193, i.e. **57%**, and
the 83 that remain are behind the walls listed in the table above.

**The 57% is the one figure here that survived unchanged, and it survived by
coincidence.** It was `106 of 186` and it is now `110 of 193` — both operands
moved and both round to the same integer, so the percentage is right for a reason
that has nothing to do with being right. *A ratio is the worst kind of count to
quote, because it can stay constant while every number in it is being corrected.*

What moved `:app` was mostly **not** UI work. It was removing the things that were
*pinning* UI to `androidMain`:

- `6a3d4024` — the 52 backend response types out of `CrispyBackendClient` and into
  `:android:backend`'s `commonMain`. Pure data was being held hostage by a transport
  concern: 38 files across 8 modules referenced `CrispyBackendClient.<Type>` and were
  therefore all `androidMain` because the client speaks OkHttp and `org.json`.
- `8658b778` — one `ResponsiveImageSet` instead of two identically shaped ones, now in
  `:android:core-domain`.
- `2560ec2a` — Phase 4 Step 1, all 111 design assets into `:sharedUI` as
  `composeResources`, 204 references repointed, 21 `@DrawableRes Int` declarations
  became `DrawableResource`. This is what removed the last *systematic* blocker: a file
  could not be in `commonMain` while it reached its icons through an Android `R`.
- `bd0e1dab` — Phase 4 Step 2, the proof slice. `commonMain` in `:app` was empty and is
  now 5 files. Goldens byte-identical.

**The dependency facts that decide the remaining order, all resolved rather than assumed:**

- `coil3` 3.5.0 is fully multiplatform — 23 KMP variants including `jvm`, `iosArm64`,
  `linuxX64`. The 24 `:app` files blocked on it are blocked by nothing.
- `androidx.lifecycle` publishes for desktop at 2.9.4, and `ViewModel`,
  `viewModelScope` and `ViewModelProvider` are all present under the **same**
  `androidx.lifecycle` package. Imports do not change. 30 files unblocked.
- **`androidx.navigation-compose` is Android-only.** Its only non-Android variant is
  `jvmStubs` — stubs, not an implementation. This is the real wall, and it is why the
  nav-graph files (`AppNavHost`, the six `*NavGraph.kt`) should stay in `androidMain`
  for now rather than being fought onto desktop.
- `AppRoutes` split cleanly: its 49 route constants and 3 route patterns are portable and
  are in `commonMain`; the 4 builders percent-encode with `android.net.Uri.encode` and are
  `androidMain` extensions on `AppRoutes`, so all 19 call sites are unchanged. See
  `AppRoutesBuilders.kt` for why substituting `core-domain`'s encoder there would have been
  a silent deep-link behaviour change.

**What the plan above still gets wrong, corrected against the tree:**

- *"Split `PlayerSessionViewModel` (1,410 lines)"* — **done 2026-10-01: four landings, 1,411 → 1,202 lines, three clusters moved into `commonMain` with 55 tests. The plan's "moves no files into `commonMain`" was wrong.**
  Four pure decisions were extracted into `PlayerSessionDecisions.kt` and pinned by 16
  `androidHostTest` cases *before* any use case moved, because the class cannot be
  constructed without a real player and "characterise before refactoring" is therefore
  impossible until the decisions are separately nameable. Re-measured 2026-09-30: 1,411
  lines and **54 functions**, of which 15 of 45 `com.crispy.tv` imports are
  `:android:nativeengine.playback.*` — so the split moves **zero** files into `commonMain`
  and is an `androidMain`-to-`androidMain` restructure that creates the seam Phase 5/6
  needs. It is filed in the wrong phase and is Phase 5's item wearing a Phase 4 label.
  and still the highest-likelihood regression in the migration.
- *"Verify the 28 shared-transition call sites"* — re-measured 2026-09-30: **27 files**, not
  28 and not 10, and the split that matters is **14 in `:app` `commonMain` against 13 in
  `androidMain`**. The participants, the composition local and the transition mechanism were
  already shared; the single provider was not, so the 14 shared ones silently found `null`
  on any target that is not the Android one. That provider is now `CrispySharedTransitionLayout`
  in `commonMain` — see the Phase 4 bullet below.
- *"Delete the `:app`↔`:tv` duplicate `Theme.kt`"* — already corrected in place above. Both
  files still exist and neither is deletable; `:tv` now depends on `:sharedUI` and reads
  its tokens.
- *"Move screens from `:app` into `:sharedUI` `commonMain`"* — in practice the first
  destination is **`:app`'s own `commonMain`**, not `:sharedUI`. `:sharedUI` holds the
  design system and its assets; a screen that `:tv` also needs may go either way, but the
  proof slice went to `:app/commonMain` because those files are phone/tablet UI that `:tv`
  does not use.

**A Phase 7 gate that is stated wrong and would fail on correct code:** *"zero
`org.jetbrains.compose` imports anywhere"*. There are 93, and every one is
`org.jetbrains.compose.resources` — which is a **real package**, 124 classes in the
desktop jar and zero `androidx/compose` classes in the same jar. It is the
compose-resources API, not a thin alias. The rule this gate is actually reaching for is
§4.1's: no `org.jetbrains.compose.{runtime,ui,foundation,material3}` alias imports. As
written it would be switched off the first time it fired on correct code, which is the
same failure mode as the stale-class gate had.

**The six-interface hole that blocked Phase 5 is closed** (2026-09-30). All six
`platform-core` ports — `SecretStore`, `KeyValueStore`, `AppLogger`, `TimeSource`,
`DistributionCapabilities`, `MonotonicClock` — now have desktop implementations in
`:android:platform-desktop`, a new plain `kotlin.jvm` module, constructed by
`desktopApp`'s `DesktopEnvironment` and covered by 40 tests.

**`:platform-android` was measured and deliberately left as a plain
`com.android.library`**, which answers the question this note used to call the next
landing's first question. Five modules already depend on it — `:android:app`,
`:android:tv`, and `:android:watchhistory`, `:android:backend`, `:android:home` by
`api` — and all four KMP consumers already take it from an `androidMain` source set.
Converting it would have churned four modules to move code with no reason to move, so
the desktop implementations went into a **symmetric sibling** instead. `:platform-android`'s
own KDoc had already specified that shape: *"The desktop equivalents arrive with the
desktop app in Phase 5 and the Apple equivalents in Phase 6, each against the same
`platform-core` interfaces. That is the whole point of the interfaces existing."*

**One thing that had to move for the two implementations to agree, and is worth
remembering:** `SecureTokenStore`'s `PREFIX`/`IV_SEPARATOR`/`GCM_TAG_LENGTH_BITS` were
`private` constants, so a second implementation had to reproduce the format by reading
that file's body — a coupling with no compiler in it, where a change on one side produces
values the other silently cannot read (`decrypt` returns `null`, which looks like a
corrupt read rather than a format mismatch). They are now `SecretFormat` in
:platform-core`'s `commonMain`, beside the interface, and both sides reference it.

**Sharing the constants turned out not to be the same as sharing the format, and the
difference was the part nobody writes:** both stores referenced the three constants and
still joined and split the value themselves, identically and by eye, so two stores agreed
on the shape by promise rather than by call. `SecretFormat.encode(ivBase64,
ciphertextBase64)` and `SecretFormat.decode(stored)` now live there too — taking **`String`s**
rather than byte arrays, because base64 is a platform concern (`android.util.Base64`,
`java.util.Base64`, `NSData`) and neither function touches it. Four rules are pinned by 8
cases that run on desktop JVM, Android host and both iOS targets: an **unprefixed** value is
still read (a store that shipped before the prefix existed holds values without it); the
**wrong number of fields is `null`**, not a guess; `isEncrypted` is a **prefix test**, so a
plaintext token that happens to start with `enc_v1:` is reported as encrypted and the prefix
is consumed before the halves are read — the format's one sharp edge; and the format **does
not validate its own fields**, because only the caller knows what a valid IV looks like.
**No suite on either side could have caught the divergence:** `SecureTokenStore` reaches
`AndroidKeyStore` in its constructor and cannot be constructed on a JVM, and
`:platform-desktop`'s suite exercises the store rather than the format. `:platform-core` had
**no test source set at all** across all five of its targets and now has `withHostTest {}`
and a `commonTest` — found by the sweep recorded in `AGENTS.md` §1, which found four modules
with the same shape.

**One measurement that is easy to get backwards, recorded because it was nearly got
wrong:** `:desktopApp` being a plain `kotlin.jvm` module consuming a KMP library's `jvm`
variant *does* work, and that is worth stating as a measurement rather than assuming —
but the thing that actually made it useful was separate. `ContinueWatchingRail` had to be
`public` for it to be callable at all, because `:app`'s screen composables are `internal`
and widening all of them would be API surface nobody asked for. So the seam was already
built and unused; what was missing was a single `public` entry point.

**The second screen repeated the shape, which is the useful part.** `ImageSettingsScreen`
was already `public` with no walls at all — no `NativeTrack`, no `:native-engine`, no
`paging-compose`, no `androidx.navigation` — and the only thing standing between it and the
desktop was the *same* one-word wall: `KeyValueStoreImageSettingsRepository` was `internal`.
One production caller (`:androidMain`'s `ImageSettingsRepositoryProvider`), so widening it
cost nothing else. **The lesson worth keeping is not "widen `internal`" but that a
`commonMain` screen's reachability is decided by its repository's visibility, not its
own.** A public composable over an internal repository is as unreachable as an internal
composable, and it reads as reachable.

**What this landing did *not* do, deliberately: build a navigation shell.** The window
switches between the two screens with a `remember`ed enum and a `BasicText` affordance,
which is four lines of wiring and looks like a placeholder because it is one. The
alternative was inventing a portable `NavHost` for two screens, in a landing whose
point is something else — and `:app` has no navigation seam to grow one into, because
`androidx.navigation` is not on the `commonMain` classpath at all. **A screen count is
not a product:** what makes desktop a runnable target is a *stateful* surface reached
through a real port round trip, and the image-quality screen is the first one that
actually is (see below). The shell is the next thing, and it belongs in `:app`'s
`appUi` source set rather than in the entry point.

**The one test that matters here, and why no other test covers it.** Every test of
`KeyValueStoreImageSettingsRepository` runs in `:app`'s `commonTest` against a fake;
every test of `FileKeyValueStore` runs in `:platform-desktop` against a hand-written
caller. Neither ever puts the two together — so a `KeyValueStore` satisfying its own
contract and a repository satisfying its own could still fail to interoperate, and that
is the entire claim of a port. `DesktopImageSettingsTest` (8 cases) drives a real
`:app` repository over a real desktop store and asserts the value is on disk, survives a
second environment, and lives in a **separate file** from the window settings, which is
the one-file-per-store-name rule checked rather than assumed.

**RETRACTED — the claim this section used to make.** It said:

> *`:app` is written against Material3 Expressive (`androidx.compose.material3`
> 1.5.0-alpha26), and **that version publishes no `material3-desktop` artifact** … Before
> any screen can move into `appUi`, Material3 Expressive has to be dropped or replaced.*

**That is false and acting on it would have deleted a shipping design feature.** It was
inferred from a version-coordinate mismatch, not from a platform limit; Expressive ships
for desktop and iOS. Resolving the dependency graph is what disproved it, and
`17e21ea5` settled it in the opposite direction: Material3 Expressive is used **on every
target**, via one coordinate, `org.jetbrains.compose.material3:material3`, which
delegates to genuine AndroidX on Android and to the real fork on desktop and iOS. The
goldens were re-run afterwards and `PlayerLoadingCurtainScreenshotTest` came out
byte-identical across `1.5.0-alpha26 → 1.5.0-alpha27`. See §Phase 4 for the full record
and the version trade.

**A second false claim, in the same section:** *`:app` is still `com.android.application`
and still owns `AndroidManifest.xml`, `res/`, `proguard-rules.pro`, signing config and
`productFlavors`.* No: that split is done. `:app` is a flavour-less KMP library and
`:androidApp` is the `com.android.application`.

**`BuildConfig` is gone**, replaced by the generated `AppConfig` in `:platform-core`,
and the version now has a single source (`crispyVersionName` in `gradle.properties`)
rather than a convention to assert by hand.

**The gate that was missing and is now not:** `BUILD SUCCESSFUL` was not evidence that
`build/classes/kotlin` matched the sources, which made every other gate unreliable.
`scripts/verify_kmp_outputs.py` asserts no compiled class has no source declaration and
runs in `check-local.sh`. Its *cause* is still unknown — see AGENTS.md, which records
the two obvious explanations as tested and false rather than asserting a mechanism.

---

## 2. Architecture

```
   ┌──────────────────────────────────────────────┐
   │            sharedLogic  (no Compose)         │
   │  core-domain  platform-core  contracts       │
   │  network  backend  addons  home              │
   │  watchhistory  player(ifaces)                │
   │  + presentation state (StateFlow)            │
   └──────────────────┬───────────────────────────┘
                      │
        ┌─────────────┼─────────────┬──────────────┐
        │             │             │              │
   ┌────▼─────┐  ┌────▼─────┐  ┌────▼────┐   ┌─────▼──────┐
   │ sharedUI │  │androidApp│  │iosApp   │   │desktopApp  │
   │ (KMP+CMP)│  │(Android  │  │(SwiftUI │   │(Windows /  │
   │ ui +     │  │ flavors, │  │ shell + │   │ macOS /    │
   │ screens  │  │ manifest)│  │ Compose)│   │ Linux JVM) │
   └────┬─────┘  └────┬─────┘  └────┬────┘   └─────┬──────┘
        │             │             │              │
   ┌────▼─────────────┴──┐          │              │
   │  tv (Android, 10-ft)│          │              │
   │  own layout,        │          │              │
   │  consumes sharedUI  │          │              │
   └────┬────────────────┘          │              │
        │                           │              │
   ┌────▼───────────────────────────▼──────────────▼────┐
   │  Android-only: native-engine (Media3/mpv)         │
   │                plugins (QuickJS)   torrent-engine │
   └────────────────────────────────────────────────────┘
```

**This is JetBrains' documented default and it is staying.** From the [new KMP structure](https://blog.jetbrains.com/kotlin/2026/05/new-kmp-default-structure/):

> "If all your platforms will use it, including those with native UI implementations, it should go in `sharedLogic`. If only platforms using Compose Multiplatform need that code, it should go in `sharedUI`."

**The decision rule, applied:**

| Code | Home | Why |
|---|---|---|
| Domain rules, planners, reducers, sync logic, contracts | `sharedLogic` | every target uses it, including the SwiftUI shell |
| Presentation state (`StateFlow` viewmodels) | `sharedLogic` | **no Compose dependency** — SwiftUI needs it too |
| Design system, tokens, Compose screens | `sharedUI` | Android + desktop + iOS content |
| Anything the SwiftUI shell touches | `sharedLogic` | Swift must never link Compose |

**One module for shared UI.** `:sharedUI` holds the design system *and* the screens. There is no separate `:ui` module — `:tv` consumes `:sharedUI` directly. A prior revision proposed `:ui` + `:compose-ui` + `:sharedUI`; that was redundant overlap and is collapsed.

**Why the iOS split is load-bearing.** The SwiftUI shell (navigation, tabs, sheets, Liquid Glass chrome) is native Swift. It talks to `sharedLogic` only. The *content* inside is Compose, embedded via `ComposeUIViewController`. So:

- `sharedLogic` must never gain a Compose import. The moment it does, iOS build time and binary size degrade and the shell loses its clean seam.
- `sharedUI` is consumed by iOS, so it must be embeddable: **no `AndroidView` interop, no `LocalConfiguration`** (see §4).

**Do not collapse the 13-module DAG into one.** The two-module split is the default for *new* projects; JetBrains is explicit that "existing projects aren't required to adopt the same exact structure." The DAG is clean, acyclic, and has no cycles to untangle. Take the *dependency direction* from the guidance, not the module count.

---

## 3. Sequencing principle — why Phase 1 ends with a working app on a new platform

**The plan proves the seam as early as possible, and in bulk only after it is proven.**

A previous ordering moved ~31,000 lines of Compose first and deferred the first desktop and iOS runs to phases 6–7. That inverts the cost of failure: if the seam turned out to be structurally wrong, you discover it after the expensive work. The risk table even recorded that `iosSimulatorArm64Test` had never run green — the sequencing *guaranteed* that unknown stayed open for the whole migration.

So Phase 1 ends with **a thin but real `desktopApp`** that renders an actual screen from `:sharedUI` against real `:sharedLogic`, and with the contract suite running on the desktop target. It is not a mock harness and it is not a throwaway — it is the shipping app's skeleton, and every later phase fills it in.

Cost: roughly one day. What it buys: every subsequent phase is "watch it work" rather than "hope it works", and it fails on your Linux box, not on a CI runner 20 minutes later.

---

## 4. Toolchain — the facts that will bite you

Each of these was established by failure. Do not "clean up" without reading.

### 4.1 Compose Multiplatform ships `androidx.compose.*`, not `org.jetbrains.compose.*`

**The most expensive thing learned in this migration.** JetBrains converged the namespaces. Verified by unzipping the resolved AARs:

| Artifact | `androidx/compose` classes | `org/jetbrains/compose` classes |
|---|---|---|
| `runtime-android` | 790 | **0** |
| `ui-android` | 1336 | **0** |
| `foundation-android` | 2018 | **0** |
| `material3-android` | 1374 | **0** |

The `org.jetbrains.compose.*` artifact coordinates are thin aliases that redirect to `androidx`. Therefore:

- `import androidx.compose.ui.unit.Dp` in `commonMain` is **correct**. Do not "port" it to `org.jetbrains.compose`.
- `import org.jetbrains.compose.runtime.Composable` does not compile **anywhere**.
- Moving a Compose file from `:app` to `:sharedUI` changes the **artifact coordinates in `build.gradle.kts`**, never the imports in the file.

**Pinned pairing:** Kotlin `2.4.10` ↔ Compose Multiplatform `1.11.1`. Bump together or not at all; a mismatch surfaces as an opaque compose-compiler error.

### 4.2 `android { }` — `androidLibrary { }` is deprecated *(corrected in Phase 1)*

A KMP module with an Android target uses `android { }`:

```kotlin
kotlin {
    android {
        namespace = "..."
        compileSdk = 37
        minSdk = 26
        androidResources { enable = true }
    }
}
```

**This section was wrong before Phase 1 and said the exact opposite.** It previously claimed `androidLibrary { }` was mandatory for Compose modules and that `android { }` failed with `Unresolved reference 'org.jetbrains.compose'`, on the authority of the JetBrains migration guide. Kotlin `2.4.10` disagrees in as many words: *"'androidLibrary' block is deprecated. Please use 'android' instead."* JetBrains converged the two blocks, so `android { }` is correct everywhere now. `:app` uses it and compiles; `:sharedUI` still uses the deprecated spelling and works with a warning.

Two adjacent facts the split established, both of which cost real time to find:

- **`platform(libs.androidx.compose.bom)` does not exist on a KMP source set.** `KotlinDependencyHandler` has no `platform()`, so a KMP library cannot apply a BOM. `Unresolved reference 'platform'`, at configuration time. Either pin every Compose artifact explicitly, or do not apply Compose Multiplatform to that module.
- **A KMP library cannot consume a *flavoured* Android library.** The KMP library plugin is single-variant, so it states no preference between a dependency's `store*` and `sideload*` variants and Gradle fails with an ambiguous-variant error naming every candidate. This is why `:android:network` had to lose its flavour axis rather than `:app` working around it with `matchingFallbacks` — a fallback would have compiled `:app` against the store variant while the sideload APK shipped the sideload one, which is the same class of lie as the unwired torrent resolver fixed in `911f8d75`.

### 4.3 No `linuxX64` on Compose modules

Compose Multiplatform publishes **no `linuxX64` artifacts** — its targets are Android, iOS and Desktop (JVM). Declaring `linuxX64()` on a Compose module makes every `compose.*` dependency fail to resolve.

- Pure-Kotlin KMP modules (`core-domain`, `platform-core`, contracts) **keep** `linuxX64()`: a free, ~5-second local proof of "no JVM API in `commonMain`".
- Compose modules (`sharedUI`, `desktopApp`) use **`jvm("desktop")`** as their local purity gate.

This is a per-module rule, not a general one. `check-local.sh` encodes both.

### 4.4 `LocalConfiguration` is Android-only, even under CMP

`LocalConfiguration` lives in `AndroidCompositionLocals_androidKt` and cannot be referenced from `commonMain`. The portable equivalent:

```kotlin
LocalWindowInfo.current.containerDpSize.width   // DpSize
```

`containerSize` is `IntSize` (px); `containerDpSize` is `DpSize`. Use the latter — no manual density conversion.

`responsivePageHorizontalPadding()` is converted. **It affects 19 call sites** and substitutes one width source for another, so it needs a golden-screenshot check like any other visual change.

### 4.5 Purity gate

`scripts/check_common_purity.py` forbids platform imports in `commonMain`, with `androidx.compose.*` **allowed** (CMP republishes it, §4.1) and other `androidx.*` subpackages enumerated individually (`androidx.core`, `androidx.lifecycle`, `androidx.activity`, `androidx.media3`, …) so the gate stays strict without an allowlist.

Accepted consequence: an *Android-only* Compose API in a `commonMain` passes the grep and is caught by the compiler instead, because `sharedUI` declares desktop and iOS targets that lack it. `check-local.sh` compiles desktop, so the inner loop still fails on a Linux host.

Both directions are covered by negative controls. Do not weaken the rule without re-running them.

### 4.6 Linux host, Apple targets

`iosArm64` / `iosSimulatorArm64` are **declared** — Gradle config, IDE and CI all correct — but Kotlin/Native cannot compile them on Linux. Configuration succeeds; the compile task fails.

Never run aggregate tasks locally: `build`, `check`, `allTests`. Use `./check-local.sh` or targeted tasks. Apple compile verification lives in `.github/workflows/apple.yml` and nowhere else.

---

## 5. Source-set strategy

### 5.1 Default hierarchy template — never hand-roll

KGP's default template builds `commonMain` → `nativeMain` → `appleMain` → `iosMain` automatically. Writing `dependsOn` by hand cancels it and emits a warning.

```kotlin
kotlin {
    android { /* … */ }
    jvm("desktop")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { t ->
        t.binaries.framework {
            baseName = "CrispyUI"
            isStatic = true
        }
    }
    applyDefaultHierarchyTemplate()
}
```

### 5.2 One custom source set, deliberately

```kotlin
sourceSets {
    val appUi by creating { dependsOn(commonMain.get()) }
    androidMain.get().dependsOn(appUi)
    jvmMain.get().dependsOn(appUi)
    iosMain.get().dependsOn(appUi)
    // no tvMain — the TV module keeps its own layout
}
```

TV is a separate `com.android.application` consuming `sharedUI`, not a source set. The 10-foot UI is genuinely different code — focus rings, D-pad navigation, no touch targets — not a variant of the phone layout.

### 5.3 Hard constraint to design around

> "Kotlin doesn't currently support sharing a source set for these combinations: several JVM targets; **JVM + Android targets**; several JS targets."

Android and desktop-JVM cannot share an intermediate source set. Anything common to both but not iOS must live in `commonMain`; iOS-specific code goes in `iosMain`. Do not plan an `androidAndDesktopMain`.

### 5.4 The iOS framework

`sharedUI` produces `CrispyUI`. `sharedLogic` types appear in its signatures, so **export** it:

```kotlin
export(projects.sharedLogic)
```

Without this, APIs arrive prefixed (`sharedLogic_viewModelViewModel`). The Swift import name follows `baseName`, not the Gradle module name.

---

## 6. Phases

Each gate is a real check.

### Phase 1 — Shells, and prove the seam *(1.5–2 wks)*

**Mandatory for AGP 9, and the critical path.**

- ~~Create `:androidApp` (`com.android.application`). Move from `:app`: `AndroidManifest.xml`, `res/`, the 2 XML layouts, `proguard-rules.pro`, signing config, `productFlavors { store; sideload }`, ABI splits, packaging block, `buildConfigField`s.~~ **DONE.** `buildConfigField`s did not move — nothing referenced `BuildConfig` after `AppConfig` landed, so they were deleted rather than relocated. The two XML layouts split: `hero_trailer_player_view.xml` went to `:app` with the code that inflates it, while `exo_player_view.xml` stayed in `:androidApp` because it overrides a Media3 library layout at resource-merge time and is not referenced by name.
- ~~Convert `:app` → KMP library using the **`androidLibrary { }`** block (§4.2).~~ **DONE — with the `android { }` block instead.** `androidLibrary` is deprecated in Kotlin `2.4.10`; see §4.2 for the corrected rule. `:androidApp` depends on `:app`, and **no source file moved** — all 158 files are still in `androidMain`.
- ~~`:contract-tests` → KMP `commonTest` over `contracts/fixtures`, so the 96 fixtures run on Android and desktop immediately and on Apple as those targets come online.~~ **DONE.** The suite is `:android:core-domain`'s `commonTest`; `:android:contract-tests` is deleted rather than converted, because a KMP module that contains nothing but tests is a module with no reason to exist and the tests only ever imported `com.crispy.tv.domain.*`. On the fixture-loading blocker the plan suggested okio; okio is the wrong answer. The problem is not the API, it is that an Android unit test starts in the module directory, a desktop test in the root project, and an iOS simulator test in a container path that is neither — and the old code hid that by walking up from the working directory looking for `settings.gradle.kts`. The fixtures are **generated into Kotlin source** instead (83 KB, 96 fixtures, sorted for byte-identical output), which removes the path question entirely and makes the suite hermetic.
- Enable the `appUi` intermediate source set (§5.2).
- ~~**Create `desktopApp` and make it run**~~ **DONE.** It renders `:android:sharedUI`'s design system over real `:android:core-domain` planner output, seeded from a real contract fixture, and the test drives it through all five `continue_watching` fixtures. It asserts on the semantic tree rather than a golden image, because a Skia raster varies by Skia version and font availability and `:android:androidApp`'s Roborazzi gate is already the rendering gate. It is the shipping app's skeleton, not a throwaway — but note the honest limit recorded above: it cannot yet render an `:app` screen.

**Gate: MET.** Both flavors assemble in debug and through R8; the goldens verify unchanged (not re-recorded); the contract suite passes 59/59 on both Android host tests and desktop and compiles for `linuxX64`; `desktopApp` renders. `iosSimulatorArm64Test` remains unproven — it needs macOS, and the plan's own risk table already refuses to let that stay unknown past Phase 6.

### Phase 2 — Data layer *(1.5–2 wks)*

- `:network` — `HttpClientPort` around OkHttp. ~~`YouTubeTrailerExtractor` behind a `commonMain` interface; its flavour split disappears and becomes a `DistributionCapabilities` check.~~ **DONE early, in Phase 1**, because the split made the flavour axis unbuildable: a KMP library is single-variant, so `:app` could not choose between `:android:network`'s `store` and `sideload` variants and Gradle failed with an ambiguous-variant error. `:android:network` is now flavour-free, `TrailerExtractor` is an interface in its `main` source set, and the NewPipeExtractor implementation is the sideload-only `:android:youtube-extractor`. **Also done in Phase 2:** that interface is now in `commonMain` and the module is multiplatform. **`HttpClientPort` is deferred to the `:android:watchhistory` step**, where a portable caller exists -- designing it here would be guesswork, and migrating its 36 call sites across six not-yet-portable modules is the mistake §3 warns about. **`DistributionComponents.trailerExtractor` is kept, not retired**: it is how `:app` reaches the variant's extractor, so deleting it would make `:android:youtube-extractor` unreachable. **Coil is not in `:network`** -- `coil3` is used only by `:app`, `:tv` and `:androidApp`, so its portable engine is Phase 4 work on `:app`, not here.
- `:backend` — `SecureTokenStore`, `ActiveProfileStore` onto `platform-core`.
- `:addons` — 5 leaking services onto interfaces. Assess `AddonStreamsService` (1,068 lines) for statefulness.
- `:home` — 10 imports total (`@Immutable` ×5, `Log` ×2, `Context` ×2, `SharedPreferences` ×1). Trivial; `@Immutable` is portable.
- `:watchhistory` — `KeystoreSecretStore` → `SecretStore`; `WatchProgressStore` → `KeyValueStore`; inject `TimeSource`.
- `:player` — interfaces stay pure; Media3/mpv stay in `native-engine`.

**Gate:** all targets compile; zero platform imports in `commonMain`; `CrispyKit` no longer owns a backend client.

### Phase 3 — Flavors + configuration *(1 wk)* — **DONE**, see §Phase 3 outcome

- `DistributionCapabilities` in `platform-core`, fed by flavour config from `:androidApp`.
- Replace `BuildConfig` (absent in KMP libraries — AGP 9 does not generate it) with a portable config object. **Verify `VERSION_NAME` explicitly**; it is Android-special and may not replicate.
- Collapse `PluginsFlavorCapabilities`, `TrailerFlavorCapabilities`, `PluginFlavorCapabilities` into the one interface.
- Merge the two `PluginsSettingsScreen` versions (51 store + 365 sideload lines) into one gated composable.
- ~~`:plugins` keeps its flavours and stays `com.android.library`, consumed from `androidMain`.~~ **Superseded in Phase 1.** `:plugins` no longer has a flavour axis: its whole `store` source set was one unread `internal val` that was never compiled, since only `sideloadImplementation` consumes the module. Its `sideload` source set — the real QuickJS implementation — is now `main`, matching `:android:torrent-engine` and `:android:youtube-extractor`.
- Re-run the dex-level distribution assertion.

**Gate:** both flavours build and behave correctly; no KMP library declares a flavour; `store` dex contains no QuickJS, torrent or YouTube-extractor classes.

#### Phase 3 outcome

**Four of the five items were already satisfied** by the end of Phase 1, so the phase
was much smaller than planned. Measured rather than assumed:

| item | state on arrival |
|---|---|
| `DistributionCapabilities` in `platform-core`, fed from `:androidApp` | already done — `BuildDistributionComponents` exists in both `store/` and `sideload/` |
| replace `BuildConfig` with a portable config object | already done — generated `AppConfig` |
| collapse the three `*FlavorCapabilities` interfaces | already done — 0 references remain |
| merge the two `PluginsSettingsScreen` versions | already done, differently — the 51-line `store` copy was **deleted** in Phase 1 rather than merged into a gated composable |
| re-run the dex-level distribution assertion | already passing; re-verified on CI in Phase 2 |

The **no-KMP-library-declares-a-flavour** gate holds: 10 KMP modules, none with a
`productFlavors` block.

#### "Verify `VERSION_NAME` explicitly" found a real defect

This was the one open item, and it was worth the Phase 3 label on its own. The
version was hardcoded as the literal `"0.1.0"` in **two** build files, and
`:android:platform-core`'s comment claimed the duplication was guarded:

> Kept in step with versionName in :android:app/build.gradle.kts. AGP 9 gives neither
> module a shared source of truth, so this is asserted rather than assumed — see the
> check in `:android:app`.

**There is no such check anywhere in the repository.** The comment also named
`:android:app`, which stopped having a `versionName` when Phase 1 moved the application
module to `:androidApp`. So the guard was described, believed, and absent, and the two
literals were free to drift — the exact failure the comment claimed to prevent.

Fixed by removal rather than by writing the missing check: `crispyVersionName` now
lives in `gradle.properties`, and both `:androidApp` (`defaultConfig.versionName`) and
`:android:platform-core` (`AppConfig.VERSION_NAME`) read it. Drift is impossible by
construction, so there is nothing left to assert. A check would only have re-verified
what one source already guarantees.

One behaviour deliberately left alone: the `sideload` flavour keeps its
`versionNameSuffix = "-sideload"` so the store and sideload APKs can be installed side
by side, but `AppConfig.VERSION_NAME` reports the **base** version to the backend. That
suffix is packaging, not application version; changing what telemetry reports is a
product decision, not a plumbing one. It is now documented at both sites rather than
left to be discovered.

### Phase 4 — Shared UI *(3–4 wks)* — the big one

- Point `:tv` at `:sharedUI` and delete the `:app`↔`:tv` duplicate `Theme.kt`.
  **This bullet is wrong and was corrected on 2026-09-28.** `:tv` uses
  `androidx.tv.material3`; `:sharedUI` uses `androidx.compose.material3`. Those are
  two different libraries, not two copies of one, so neither `Theme.kt` is
  deletable and `:tv` cannot simply be pointed at `:sharedUI`'s. What *is*
  shareable is the token layer — the colour and shape values — and only that.
  **DONE, as a token layer only.** `:sharedUI` now holds `CrispyPalette`, one object
  of 38 constants that both `darkColorScheme` calls build from; `:tv` keeps its own
  `Theme.kt` and maps `border = CrispyPalette.outline`, `borderVariant =
  CrispyPalette.outlineVariant`. Three things the measurement overturned, all of
  which had been assumed:
  - **The two schemes were already value-identical.** All 27 shared roles matched
    exactly, and the two border roles held the *same* hex values under different
    names (`0xFF333333` and `0xFF262626`). So the only divergence was the role
    *name* between two libraries, which is a **mapping, not a divergence** — there
    was no deliberate TV colour to preserve, and the extraction changed zero pixels.
    `:tv`'s own `DetailPalette.kt` already mapped the roles by hand at its last four
    lines, which is independent proof they are the same role.
  - **A duplicated `public` constant can be dead.** `:tv` re-declared
    `CrispySpinner = Color(0xFFF56E3C)` under the *same name* as `:sharedUI`'s, both
    `public`, different packages — so nothing flagged it. `git grep` showed all
    **11** call sites importing `:sharedUI`'s, and `:tv`'s had zero. Deleted.
  - **`:tv` already depended on `:sharedUI`** (`android/tv/build.gradle.kts:150`), so
    the token layer needed no new dependency and no new module.
  The 7 roles `:tv` cannot express (Material3 Expressive's surface-container family)
  are in the palette and `:tv` simply does not use them. `:tv`'s `DetailPalette.kt`
  is untouched and out of scope: it is the dynamic seed-colour feature and is
  Android-only by nature (`Bitmap`, `Context`, `LruCache`, coil3, `com.materialkolor`).
  `CrispyPaletteTest` (`:sharedUI`'s first test file) pins every value in `commonTest`,
  which runs on Android, desktop and both iOS targets — necessary because **the golden
  suite does not render `:tv` at all**, so a mistyped hex on that surface would
  otherwise ship green.
- Move screens from `:app` into shared code, **in vertical slices, one screen at a time**, each with a golden-screenshot diff.
  - **Corrected 2026-09-29:** the first destination is **`:app`'s own `commonMain`**, not `:sharedUI`'s. `:sharedUI` owns the design system and its assets; phone/tablet UI that `:tv` does not use goes to `:app/commonMain`, and `:app`'s empty `commonMain` is the thing that needed proving. Anything genuinely shared by both surfaces goes to `:sharedUI`.
- The import rule in §4.1 applies to every file moved: coordinates change, imports do not.
- `R.font.archivo_top10` → `composeResources`. Superseded by **Step 1: resources** below, which is the real prerequisite for moving any composable and splits it into two parts that must not be bundled.
- **Split `PlayerSessionViewModel` along use-case lines — DONE 2026-10-01, and the plan filed it wrong twice.** It was called "the highest-likelihood behavioural regression in the migration" *and* an `androidMain`-to-`androidMain` restructure that "moves no files into `commonMain`", on the grounds that 15 of its 45 `com.crispy.tv` imports are `:android:nativeengine.playback.*`. **Both were wrong, and four landings prove it.** The file went **1,411 lines / 54 functions → 1,202**, and **three whole clusters moved into `commonMain` with 55 tests between them**: `DetailsMetadataLoader` (`51836533`), `PlaybackProgressReporter` (`f16f6867`) and `SeasonEpisodesLoader` (`b989025b`), plus the four pure decisions extracted first (`0207161b`) because a class that cannot be constructed has nothing to characterise. `:app` went **106 → 110 `commonMain`** while `androidMain` stayed 81 — **every one of the four clusters turned out to be fully portable the moment its types were measured, because `:addons`, `:backend`, `:home`, `:player` and `:platform-core` are all already on `:app`'s `commonMain` classpath.** What genuinely stayed `androidMain`, each measured: the episode-metadata cluster (`PlaybackSource` is declared in `:android:native-engine`, a plain `com.android.library`), the subtitle cluster (`languageFromTrack` reads `NativeTrack`; `fetchAddonSubtitles` is 11 lines of `android.util.Log`), `pollPlaybackState` (holds `PlaybackController` and `AudioFocusManager`), and `handleChosenStream` (calls `resolvePlaybackSource`). **The rule that replaced the file's guess: the `:native-engine` wall pins the clusters that *return* one of its types, not every cluster in the file** — measure the members, not the member count.

  Eight things this phase found that are not about the view model. **A module with no test source set can hold a large untested `commonMain`**: `:android:addons` compiled for five targets with **no `commonTest` at all**, because five of its files are `Context`/OkHttp/`org.json` Android adapters and creating a test directory for one file in an Android-shaped module reads as wrong. That left **171 lines of nine pure functions** (`StreamLookupSupport.kt`) with zero tests anywhere; `:addons` now has `withHostTest {}` and 28 cases. The `:addons` explanation does not generalise, though — `:android:player` had the same gap with an **empty `androidMain`**, so nothing about it looked untestable, and the real cause is that its build file calls itself "the one that establishes the recipe the other five follow" and **the recipe omitted a test source set**. `git ls-files <module>/src/commonTest` across every module is the one-command sweep that finds all of them. And **one guard matches a segment where a reader sees a prefix**: `bridgeCandidateIds` in `:core-domain` bridges a tmdb id to the imdb id on its own record only when `contentId.contains(":tmdb:")` — a colon on *both* sides — so a **bare `"tmdb:1234"` id never bridges**, even with a perfectly good imdb id sitting in the record. Only a provider-scoped id such as `"provider:tmdb:1234"` matches. Nothing in the repository said so until `:player`'s new suite asserted it, and whether the bare form is *meant* to bridge is a question about the providers rather than about the code. **Unresolved: recorded in `MetadataLabResolverTest.aBareTmdbIdIsNeverBridgedBecauseTheMarkerIsASegmentNotAPrefix`, deliberately not changed.** And **two copies of the same episode load disagree on what a failed request means** — the player says "Failed to load episodes.", `DetailsUseCases.loadSeasonEpisodes` (which `DetailsViewModel` already delegates to) says "No episodes found for this season.", so a failed request there claims there were none. **Unresolved: that is a product decision, not a refactor, and the two copies were deliberately left alone rather than unified.** And **two functions that decide the same thing disagree about the same URL, in both directions**: `classifyTrailerSource` matches exactly two literals (`youtube.com/watch`, `youtu.be/`) and lowercases first, so it is case-correct, while `extractYouTubeVideoId` matches **four** regex shapes — adding `/shorts/` and `/embed/` — and does *not* lowercase. So a `/shorts/` or `/embed/` link classifies as `DIRECT` while the extractor reads a video id out of it, and an uppercase `YOUTU.BE/…` link classifies as `YOUTUBE` while the extractor returns the whole URL as its "id". Compounding it, `extractYouTubeVideoId` returns `null` for **exactly one** input shape — a blank value — so `YouTubeTrailerExtractor`'s `?: return null` gives up only on a blank id and otherwise asks NewPipe to resolve whatever string it was handed. **Unresolved: which of the two shapes is right is a question about how providers hand over trailer links, so it is recorded in `TrailerSourceTest` and deliberately not changed.** And **a guard can be masked by a neighbouring condition and the masking is indistinguishable from absence**: `extractYouTubeVideoId`'s `contains("youtu", ignoreCase = true)` gate appears redundant, because an uppercase `YOUTU.BE/` link passes the gate, then fails the lowercase-only `youtu\.be/` alternative, then falls through to the `?: trimmed` fallback — which is the same string a case-sensitive gate would return, so the mutation survives. The fixture that separates them is an uppercase host carrying a **lowercase** `v=` or `/embed/`, where the two answers part company. And **a module's type was standing in for a fact about its code, which is the second time that shape has cost this repository work the first time had already caught it**: `architecture.md` and `AGENTS.md` both recorded `:tv`'s two-line `border`/`borderVariant` mapping as untested *because `:tv` is a plain `com.android.application`*. Measurement says that reason is false. `CrispyTvDarkColors` is a top-level `val` calling `darkColorScheme(...)`, which builds a `ColorScheme` data class out of `androidx.compose.ui.graphics.Color` — an inline value class over `ULong`, pure Kotlin, no `Context` and no resource — so a **plain JVM unit test in `:android:tv/src/test` reaches it**. The actual obstacle was that the `val` was `private`, which the landing widened to `internal`. `:tv` also had **no `src/test` at all** while being the one surface whose tokens are read on television, and the KDoc's own accounting was off: `:tv` maps **29 of `CrispyPalette`'s 37 roles**, and the eight unmapped are the seven `surfaceContainer*`/`surfaceDim`/`surfaceBright` roles it named **plus `spinner`**, which is not a Material3 role at all. Ten cases now pin it, and the suite's own non-vacuity gate found that **`scrim` is the one role no value assertion can verify** — `CrispyPalette.scrim` is `0xFF000000` and `androidx.tv.material3` defaults to opaque black, so a mapped `scrim` and a dropped one are indistinguishable. That limit is asserted as the exact collision set rather than left in a comment. And **a document's own headings and type names are not evidence about the code, and a second numbered plan for one repository is a defect even when every sentence in it is correct**: the `architecture.md` pass found that **seven of the type names that document used had never existed** — `WatchProvider`, `Resource`, `Freshness`, `ScreenState`, `PendingMutation`, `LibraryRepository`, `ProviderConnectionRepository`, `PlaybackRepository`, `SettingsRepository` — and that `DataSource`'s only six hits in this repository are every one of them `androidx.media3.datasource.DataSource` inside `:native-engine`, so **the name a document proposes was already taken by a Media3 type**. `## Target Use Cases` says outright that its names are not the point, which is what makes its six invented interfaces obviously illustrative; **`## Fetch And Cache Policy` is the same kind of section and carried no such disclaimer**, so a reader took its `Resource<T>`/`DataSource`/`Freshness`/`ScreenState` as descriptive — so the register of the surrounding prose is part of the audit. The structural half is worse: `architecture.md` also carried a **seven-phase `## Migration Plan` whose phase numbers meant something entirely different from this plan's**, so a reader who had read one misread every phase number in the other. The fix was a **scope split, not a deletion** — it is now *Migration Plan (product and backend)* and points here for the port.
  - `PlayerSessionDecisions.kt` holds `resolveInitialEngine`, `statusMessage`,
    `initialSeekDecision` (a `sealed interface` of `Wait`/`Clear`/`Seek`) and
    `shouldFallBackToMpv`, all `internal` in the same package. Two of the two literal
    body moves needed **no call-site edit at all** — the private members were shadowing
    same-named top-level functions, so deleting the member *was* the change.
  - **Three decisions a reader would get backwards, now pinned:** `Auto` starting on
    ExoPlayer is a product decision (it means *try Exo, switch on a codec error*), not
    "pick the best engine"; the readiness check is evaluated *before* the already-at-target
    check, so a `PREPARING` snapshot that has reached the target waits rather than clears;
    and the fallback's third conjunct is `preference != ExoPlayer`, **not** `== Libmpv` —
    reversing it compiles, reads sensibly, and quietly reduces `Auto` to `ExoPlayer`.
  - All three of those mutations are **caught by name** (`scripts/mutate_player_session_decisions.py`,
    3 entries, 0 survived).
- Verify the shared-transition call sites against CMP's documented limitations (no `AndroidView` interop, `ContentScale` snapping, no shape-clip animation). **The host is DONE and the count is corrected. 2026-09-30.**
  - **The count was wrong twice, and the second error was the informative one.** The plan said 28, then 10 across 6 files; measured over tracked sources it is **27 files — 14 in `:app` `commonMain`, 13 in `androidMain`**. The per-file list is in `AGENTS.md`.
  - **"Bound to navigation" is a statement about the file, not about the mechanism.** Across the whole repository there are exactly three distinct imports of shared-transition machinery: six sites of `com.crispy.tv.ui.navigation.LocalSharedTransitionScope`, **one** of `androidx.compose.animation.SharedTransitionScope` and **one** of `androidx.compose.animation.SharedTransitionLayout`. **Zero** of the 27 participants import `androidx.navigation` for the transition itself — `androidx.compose.animation` is Compose Multiplatform and present on every target. Navigation is what *navigates*; the thing that *transitions* is Compose.
  - **The bug this hid, and it was a real one on every non-Android target:** the scope was provided from two lines inside `AppNavHost.kt` (`androidMain`), so all **14 `commonMain` participants read `null` off Android and rendered with no transition — silently, with no error anywhere.** The participants were correct all along; only the host was missing.
  - **Fixed by a new `CrispySharedTransitionLayout` in `:app` `commonMain`**, public, with a no-default `content` slot so a caller cannot obtain a provider that provides nothing. `AppNavHost.kt` shrank by four lines and stays `androidMain` (it is genuinely `NavHost`-bound), and `desktopApp` now wraps its screens in it — so a shared transition is exercised on a non-Android target for the first time. `DesktopSharedTransitionTest` asserts the scope is non-null under the host **and** null without it, which is what makes the first assertion mean "the host supplied it" rather than "the local defaults to it".
  - **Still open, and genuinely Phase 5's:** the five `*NavGraph.kt` files stay `androidMain`. They declare `NavGraphBuilder` graphs and `androidx.navigation` has no KMP artifact at all, so this is a navigation problem and not a transition one. Do not read the remaining `androidMain` participants as transition work.
- Tokenise the design system so a 10-foot TV surface and a resizable desktop window are both servable **without changing today's appearance**.
- **`architecture.md` updated for the ported codebase. This was the last named item in Phase 4, and it is documentation rather than code. Done.** Measured staleness, all four points verified against the tree: its `## Status` names "the Android and iOS clients", and desktop has shipped; `## Recommended Package Direction` points at `android/app/src/main/java/com/crispy/tv/` with a `feature/` and an `infra/` that do not exist, and the `AppGraph.kt` it names is at `android/app/src/androidMain/kotlin/com/crispy/tv/app/AppGraph.kt`; and `## What To Refactor First` item 2 tells the reader to replace `WatchProvider?`, a type that no longer exists anywhere (`grep -rl WatchProvider android --include=*.kt` returns nothing). Its **backend-first ownership model is still correct and was kept** — so this was a KMP pass over a document whose reasoning survives, not a rewrite. **Reading it in full first was the whole lesson**: patching those four anchors in isolation would have missed the seven non-existent type names, the four already-finished refactor items, and the competing seven-phase plan. What changed: the client set is now all four targets; `## Recommended Package Direction` became `## Recommended Module Direction` and is now the **real module graph** rather than a package tree that was never built, with the two directories that answer the old question named (`domain/repository/` in `:app` `commonMain`, `AppGraph.kt` in `androidMain`); `## What To Refactor First In This Repo` is split into *Still true* and *Done since this list was written* with the numbering kept; `## Migration Plan` is retitled for scope; `## Fetch And Cache Policy` is explicitly labelled a proposal with the `DataSource` collision named; and the mutation lifecycle in `## Offline And Retry Behavior` was replaced with the **real four-state sealed `MutationStatus`** — which has no `CONFIRMED`, because a synced mutation is **deleted**, and no `RETRY_SCHEDULED`, because retry is a `Pending` carrying `nextAttemptAtMs`, and which has a `Conflict(serverValue)` the old list had no word for, so the document's own `## Conflict Resolution` section could not be implemented as written. 594 → 666 lines; `:1-200`, which is the spine, is untouched.
- **Material3 Expressive: use it on every target.** This supersedes an earlier revision
  of this plan that said Phase 4 "has to drop or replace Material3 Expressive". That
  claim was inferred from a version mismatch, not a platform limit, and acting on it
  would have deleted a shipping feature to work around a pin.

  #### The groundwork, and it is Phase 4 step 0

  Expressive is supported on desktop and iOS, and this was verified rather than
  assumed — twice, because the first reading was wrong in the opposite direction.

  - JetBrains publishes official multiplatform API docs for `LoadingIndicator`,
    `LoadingIndicatorDefaults` and `ContainedLoadingIndicator` under
    `compose-multiplatform/material3`, and CMP **1.9.3's whats-new carries a
    "Material 3 Expressive theme" section** describing `MaterialExpressiveTheme`.
  - CMP **1.9.0 decoupled Material3 versioning** from the plugin: *"The versions and
    stability levels of the Material3 library and Compose Multiplatform Gradle plugin
    no longer have to be aligned."* The `compose.material3` alias deliberately points
    at the latest **stable** Material3, which is why it lands on 1.4.0 and why
    Expressive is absent. That is a policy choice, not a missing feature.

  **The enabling move is one coordinate, not a per-target seam.** Verified by
  resolving the actual dependency graph:

  ```
  org.jetbrains.compose.material3:material3:1.9.0
    ├── android  -> androidx.compose.material3:material3-android:<ver>   (real AndroidX)
    └── desktop  -> org.jetbrains.compose.material3:material3-desktop:<ver> (real fork)
  ```

  The JetBrains coordinate is a **thin alias that delegates to AndroidX on Android** at
  every version, and ships the real implementation on desktop and iOS. So there is
  never more than one material3 on the classpath, and the same
  `androidx.compose.material3` package and imports work on every target. Pinning the
  JetBrains coordinate explicitly — instead of the alias — is the entire change. No
  `expect`/`actual` seam is needed, and the 12 Expressive call sites are untouched.

  **What it actually costs, stated honestly.** Expressive requires a material3 *alpha*,
  and on Android each JetBrains material3 alpha also raises the AndroidX Compose
  version it requires. Measured:

  | JetBrains material3 | AndroidX material3 | AndroidX Compose required | Expressive on desktop |
  |---|---|---|---|
  | 1.9.0 (the alias) | 1.4.0 stable | 1.7.x | **no** |
  | 1.12.0-alpha03 | 1.5.0-alpha22 | 1.12.0-beta01 | yes |
  | 1.13.0-alpha01 | 1.5.0-alpha27 | 1.13.0-alpha02 | yes |

  So the price is a Compose stack move off stable (beta at best), not just a material3
  bump. This is the real trade, and it is the opposite of the one the plan previously
  asserted. It is also temporary: AndroidX 1.5.0 is graduating large parts of
  Expressive to non-experimental, so the alpha surface should shrink.

  Step 0 is: pin `org.jetbrains.compose.material3:material3` in `:sharedUI`,
  `:app`, `:androidApp` and `:tv`; drop the `compose.material3` alias and every
  direct `androidx.compose.material3` declaration so exactly one path resolves
  material3; then let the golden screenshots judge the `1.4.0`/`1.5.0-alpha22` visual
  delta rather than assuming it is nil. Two things to verify rather than assume, both
  of which step 0 must actually test: that `LoadingIndicator`'s shape-morph renders
  identically on Android across the material3 version step, and that a material3 alpha
  has no R8/ProGuard consequence in the release build.

**Gate:** Android visually unchanged (goldens clean); all four targets compile; both flavours pass the manual checklist; release build with R8 succeeds.

### Phase 4, Step 1 — Resources *(the real prerequisite)*

**DONE — Material3 Expressive** (commit `17e21ea5`). The section above is the record of
how it was settled; it is no longer a step to take.

**DONE — the two prerequisites that were blocking every UI move.** Both were pure
data trapped behind an `androidMain` declaration, and both are now `commonMain`:

- The **52 backend response types** moved out of `CrispyBackendClient` into
  `:android:backend`'s `commonMain` as `BackendTypes.kt` (commit `6a3d4024`). 38 files
  across 8 modules referenced them, and because the client speaks OkHttp and
  `org.json`, every one of those files was pinned to `androidMain` by where a data
  class happened to live.
- **`ResponsiveImageSet` is one type, not two** (commit `8658b778`). The backend DTO
  (`small`/`medium`/`large`) and the UI model (`low`/`medium`/`high`) had identical
  shapes and identical `isEmpty`. They are now one type in `:android:core-domain`,
  with `ImageQuality` moved alongside it under its original package so no import
  changed. Field names follow `ImageQuality`; the persistence keys stay
  `small`/`medium`/`large` because that is an on-disk format, not a field name.

**Now the resource migration, which is the last thing standing between `:app` and
`commonMain`.** Measured, not estimated: 49 files hold **225** references —
**206 `R.drawable`, 18 `R.raw`, 1 `R.font`** — across 102 XML drawables, 9 raw SVGs
and 40 launcher mipmaps in `:android:ui-assets`.

**Part 1 — the 102 drawables and the font. Mechanical, and it unblocks the phase.**

- `:sharedUI` already has `compose.components.resources` wired and an empty
  `composeResources` directory, so no new dependency is needed.
- The generated accessors are **`internal` by design**, which is fine *within* the
  owning module. For `:app` and `:tv` to consume them, the documented switch is
  `compose.resources { publicResClass = true }`, with `packageOfResClass` set to
  something deliberate rather than the auto-derived
  `crispy_rewrite.android.sharedui.generated.resources`. Do **not** use the
  `doLast` block that rewrites `internal object Res {` in the generated file; upstream
  labels it a temporary workaround and it patches generated output.
- **The accessors are top-level extension properties on `Res.drawable`, so each one
  needs its own import.** `import ...generated.resources.Res` alone brings in the
  nested `object drawable` and nothing else, so every accessor then reads
  `Unresolved reference` while `Res` and `Res.drawable` resolve. That exact symptom
  was misdiagnosed here as a Compose Multiplatform limitation and cost an hour and a
  revert before the documentation was read. Look it up first.
- `CrispyBrand`/`CrispyIntroSplash` move with the drawables; neither has a drawable in
  its public signature, so their callers do not change.
- Do the 206 repoints with a parser, not a regex, and let the compiler judge. See
  AGENTS.md on why: a regex cannot see inside a comment or a string, and one such
  pass already deleted 140 live imports.

**Part 2 — the 9 SVGs are a design problem, not a rename. Do not bundle them.**

They are `@RawRes Int?` passed to Coil as `model =`, and **Compose Multiplatform
documents SVG as "supported on all platforms except Android"** — so
`painterResource(Res.drawable.trakt)` would silently fail on the platform that
matters most. The supported route for arbitrary files is
`composeResources/files` + `Res.readBytes(path)`, which is cross-platform, but then
Coil needs an SVG decoder on Android. The options are: keep these nine as Android
resources in an Android-only module, add an SVG decoder for the other three targets,
or replace the logos with vector drawables. That has product consequences and needs
to be decided deliberately, in its own commit.

**The launcher mipmaps are app assets, not design assets.** They are referenced from
two `AndroidManifest.xml` files and one notification icon, they cannot be
`composeResources`, and duplicating them into a shared module would be wrong. Each app
owns its own icon: `:androidApp` and `:tv`.

**The flavour axis constrains `:ui-assets` and must be settled by testing, not by
reading the plugin API.** It currently carries
`missingDimensionStrategy("distribution", "sideload")` because `:app` — a KMP library
with no flavours — consumes it. A KMP library cannot declare that, and AGP's
`com.android.kotlin.multiplatform.library` has no `productFlavors` at all. Converting
`:ui-assets` interacts with the rule that flavours live only in `:androidApp`, so
resolve it empirically before committing to a shape.

**Gate for Part 1:** `:sharedUI` compiles for desktop and Android with the resources in
place, `desktop` being the portability proof; the 206 call sites compile; goldens
clean.

#### Step 6 — DONE. The file-move phase of Phase 4 is complete, and the remainder is not a file move

`9bf0b769` moved the last 13 `:app` files. `:app` now holds **40 files in
`commonMain against 120 in `androidMain`**, up from 0/156 when this phase started.

Then the two questions that were being answered by guesswork were measured, and the
answer changes what the rest of Phase 4 is:

**1. Can any further `:app` file move? No.** Two independently written analyzers agree,
and a third that peels transitively agrees: **0 of the files remaining in `:app`'s
`androidMain` are movable as they stand.** (The bucket table below was measured when 120
remained; it is now 80, and the *conclusion* is unchanged while the per-file counts are
stale — treat the table as the shape of the problem, not its current size.) Every one is
either behind a hard wall or waiting on a declaration that is itself in some other
module's `androidMain`. A naive "this declaration blocks N files" ranking badly overstates
the payoff, because a file with three blockers is not unblocked by removing one — so the
ranking used below is a transitive peel, not a count.

**1a. Re-measured 2026-09-30, the other direction.** A *reverse* audit — forbidden
imports and tokens first, then subtracting every type declared in every module's
`commonMain` (642 types) — over all 80 remaining files leaves **10 candidates and
resolves 0 of them**. The two that looked most promising were checked and are dead ends:
`CatalogPagingSource` (44 lines) is unblocked on dependency grounds, since
`androidx.paging:paging-common` **is** already a `commonMain` dependency and `PagingSource`
lives in it, but it needs one port and frees no other file; and `ContinueWatchingPlanItem`
cannot be mapped to a `CatalogItem` at all, because the planner emits a ranking with no
title or artwork. Moving files has hit its floor. **Phase 5 is not waiting on Phase 4.**

**2. What the remainder actually breaks down into**, and this is the shape that matters:

| Bucket | Files (at 120 remaining) | What it takes |
|---|---|---|
| Hard wall | **84** | `Context` (59), `java.*` (31), `androidx.navigation` (9), `androidx.media3` (6), | `native-engine` (10), `org.json` (was 5, **now 2 — `:backend`'s half landed**), `okhttp3` (3), `materialkolor` (1) |
| `coil3` only | 13 | real work, not a blocker: coil3 is multiplatform, these need the same `Int` → resource porting the drawables already got |
| No wall | 23 | but 0 of them are closable without upstream moves; the largest gate is **4 files** |

### Measured 2026-10-01 (later), corrected 2026-10-02 — one of the two halves landed, so "permanent" was half wrong

`WatchProgressStore.kt` was the first file moved onto `JsonElement` (405 lines, out
of `androidMain` and into `commonMain`, its 44 cases now on both targets). That
made the remaining four look like the same work. **They are not, and the reason is
that the node type was never their pin.** Together they were **1,226 lines of
behaviour-preserving churn that would buy zero non-Android consumers**, which is
the same defect as padding a mutation driver with `expect_survive` entries. Both
chains were measured; neither is an estimate.

**Correction, 2026-10-02. `:backend`'s half of that prediction shipped, and it
was the wrong half of it.** The 811 lines were not churn: the wall below them was
ported first, exactly as this section's own order said, and then they moved.
`:backend` is now **17 `commonMain` files / 3,989 lines with exactly 1 `androidMain`
file**, all 33 of its cases in `commonTest` with no Robolectric, and the single
survivor is `SecureTokenStore` (Android Keystore).

**`SupabaseAccountClient` is the other half of this correction, and it is the half that
was recorded as untried.** It was written up as "`org.json` alone, so not yet, not
permanent" — and **`org.json` was neither its largest pin nor the one that had to go
first.** Three things held it, and only one of them was the node type:

| pin | measured | freed by |
|---|---|---|
| `appContext: Context` | **1 occurrence — its own declaration, never read** | deleted the
  parameter. *A parameter whose only occurrence is its declaration is not a dependency on
  its type; the type is what made the file look pinned.* |
| `tokenStore: SecureTokenStore` | a constructor parameter | **`AccountSessionStore`** —
  3 members, `suspend` on two of them because that is what the implementation has |
| `System.currentTimeMillis()` | **2 sites** (:168, :245) | **`nowMs: () -> Long`, required
  and with no default** — because a default would have been
  `System.currentTimeMillis()`, and a default would have compiled on every target this
  repo builds while silently keeping the pin it was added to remove |

**The clock is the part worth keeping.** `:platform-core`'s `MonotonicClock` is right
there, already used by `:app` and `:watchhistory`, and it is **the wrong clock rather
than a competing one**: `shouldRefresh` compares against the server's epoch-second
`expires_at`, so a monotonic source produces a correct *duration* and a nonsense
*instant*. **"Reuse the existing utility" and "the existing utility answers a different
question" are different findings, and the second is why the first does not apply** — which
is why there is no second clock interface in `:backend`.

**So this block's own sentence is now earned twice.** A pin measured on one axis ("a file
still imports `org.json`") is a claim about an *import*. Across this migration, three
files named as JSON-pinned were each pinned by something that census cannot see: **a
receiver** (`CrispyBackendClient`, 39 parsers), **a constructor parameter**
(`SupabaseAccountClient`'s token store), and **a clock that needs no import**.
**What the prediction got right was the order and the mechanism, and what it got
wrong was the adjective**: "the node type was never their pin" was correct, and
"permanent" assumed that because a pin is not the node type it is also not
removable. **A negative result is a statement about what was measured *so far*, and
the honest verb for one that has not been re-measured is "not yet", not
"permanent".** `:app`'s 415 lines still stand as recorded below.

**1. `:backend`'s JSON layer — 811 lines (165 accessors + 646 parsers) — was
`androidMain` because of the transport, not the parser. LANDED 2026-10-02.**
39 of the 45 top-level functions in `CrispyBackendParsers.kt` are
`internal fun CrispyBackendClient.parseX(json: JSONObject)`, so the *receiver*
carries the pin into every signature. `CrispyBackendClient` (497 lines) had a
small OkHttp surface of its own — `authHeaders` and `JSON_MEDIA_TYPE`, three
places, and **it never touched `OkHttpClient`**; it talked through
`CrispyHttpClient`. The wall was one layer further down: `CrispyHttpResponse` was
declared `data class CrispyHttpResponse(val url: HttpUrl, val code: Int, val
headers: Headers, val body: String)`, so **the response type named OkHttp in its
constructor.** Closing this meant a different response type (`url: String`,
`headers: Map<String, String>`) — and **the figure that used to stand here was a
guess with no command behind it, wrong in its reasoning as well as its
arithmetic.** It read *"rippling through ~100 call sites that read
`response.url`, `response.header(…)` and `response.code`"*. Measured: **53**, and
**two of those three fields had zero readers.** `CrispyHttpResponse.header(name)`
had **0** callers — all ten unqualified `.header(` hits were OkHttp builder and
accessor calls (`request.header(…)`, `Headers.Builder.header`, `SsrfGuard`), none
on that response type. `url` had **0** readers — the only hit was the assignment
that populates it. Only `code` was actually read. So the port dropped `url` and
`headers` and `header(name)` outright rather than migrating them. **The 53 was
`postJson` 15 + `get` 27 + `delete` 6 + `execute` 5**, all receiver-qualified;
and it was corroborated by a count that started from a different direction —
**58** repo-wide `.toHttpUrl()` lines, less 4 inside the new implementation and 1
in `:watchhistory`'s own direct-OkHttp file — which is the completion check.

**The second wall was `org.json` in the client's own two envelope methods — eight
sites, and nothing else in 501 lines** — and it was the one that was never on a
list, because the list counted the parsers' 811 lines and not the 8 behind them.
`requireSuccess` returned `JSONObject`, so **its 45 call sites across the three API
files were switched underneath** — 22 `optJSONObject`, 19 `optJSONArray`, 10
`optString`, 1 `opt`, 3 `length()`, plus 16 `JSONObject().put(…)` write sites.
That is where the 811 lines' real cost was, and it was invisible from the parser
side. **The order was forced rather than chosen**: the parsers compiled in
`commonMain` first and produced 35 identical `Unresolved reference
'CrispyBackendClient'` errors, because the receiver had not moved yet. So the
chain ran *client -> API files -> parsers*, which is the same "port the wall,
then the pin" rule as below with the receiver substituted for the transport.

**Three behaviour changes shipped, all fixes, all found by the suite that was
already there:** a JSON null under any of the parsers' **51 `optString` sites**
used to read the four characters `"null"` — not blank, so the
`.trim().ifBlank { null }` after most of them never fired — and now reads `null`;
**`optIntOrNull` over `{"fraction":42.9}` went `42` to `null`**, because
`org.json` truncated and `JsonPrimitive.intOrNull` parses, so the numeric widths
are now reproduced rather than inherited; and **`toStringMap` drops a container**
where `org.json` stringified it. **None of the three was a compile error**, which
is exactly why they needed a suite rather than a compiler.

**2. `:app`'s two JSON stores — 415 lines (118 accessors + 297 stores) — are
`androidMain` because of Android storage.** `ProfileDataShadowStore` is pinned by
`Context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)`.
`LibraryDiskCacheStore` is not a light adapter at all: **60+ `org.json`
touchpoints**, including a `JSONObject.toCatalogItem()` that calls
`optNullableString` 14 times and a `toCacheJson()` with 19 chained `.put(…)`,
alongside `java.io.File`, `StandardCharsets`, `MessageDigest` and
`Dispatchers.IO`. Porting the 118-line accessors alone would move **zero files**,
and a `commonMain` `JsonElement` accessor cannot be called from an `androidMain`
store holding an `org.json.JSONObject` at all — the type system rejects mixing
them, since `json.optJSONArray("items")` needs a `JSONObject`.

**The generalisable form, which is the part worth keeping:** *the node type was
the visible pin, and the real pin was one layer down — a transport's response type
in one case and a storage API in the other.* So the honest order is **port the
wall, then the pin**: `HttpClientPort` first, and only then `:backend`'s parsers.
A file whose *receiver* is pinned cannot be freed by changing its arguments, and a
consumer that cannot move makes its helpers worth nothing to move either.

**The rule below predicted the right answer and was still wrong about
timing.** "Port the wall, then the pin" was correct, and the wall was not the
transport alone: there was a second one behind it that no single survey had
found, because the survey counted the pinned files and not what pinned *their
receiver*. **The prediction's failure mode was a word, not a mechanism — it said
"permanent" where it had measured "not yet", and the two are indistinguishable
until the next landing tries.**

The per-file counts above are **historical** (measured with 120 files left, and several
of those blockers have since been removed by the service-locator conversion, and
`org.json` has since fallen from 5 files to 2). The buckets
still describe the shape: most of what is left is a hard wall, and the two genuinely
unblocked buckets are both bounded by upstream decisions.

**So the honest statement is that Phase 4's remaining work is a refactor, not a
migration.** Moving files has hit its floor. What is left, ranked by how many files it
actually unblocks once the transitive blockers are counted:

1. **The service locators — one pattern, six of the top blockers.** `SupabaseServicesProvider`
   (20), `BackendServicesProvider` (16), `PlaybackDependencies` (8),
   `BackendContextResolverProvider` (6), `PlaybackSettingsRepositoryProvider` (4),
   `AppDistribution` (8) are the same shape: a locator that holds a `Context` **only in
   order to construct an Android dependency**, then hands back a portable interface. The
   fix is already proven in this repository — `AppDistribution`/`DistributionComponents`
   does exactly this for the flavour axis. Convert each to "installed components", with
   the Android factory installed from `CrispyApplication.onCreate`, and the locator
   itself becomes portable. **This is the single highest-leverage remaining move**, and
   it is the thing `Context` in 59 files is really about.
2. **`CrispyBackendClient` and `SupabaseAccountClient` were `androidMain`
   because the transport was — and this shipped.** `CrispyHttpResponse` carried
   `HttpUrl` and `Headers` in its own constructor, so the pin was one layer below
   the client rather than in the JSON; `HttpClientPort`/`CrispyHttpClient` is now a
   `commonMain` interface in `:network` with `OkHttpCrispyHttpClient` as its
   `androidMain` implementation. **`CrispyBackendClient` is now `commonMain`**, and
   the ranked figure of 17 files it unlocked is the real one.
   **`SupabaseAccountClient` shipped too, and not on the `org.json` axis that was
   recorded for it.** Its pins were a dead `appContext: Context` parameter (count 1), a
   `SecureTokenStore` constructor parameter, and two `System.currentTimeMillis()` calls
   that need no import and so are invisible to the forbidden-token scan. Freed by
   `AccountSessionStore` and a required `nowMs: () -> Long`. **`:backend` now has one
   `androidMain` file**, and it is the one that cannot move.
3. **The `coil3` 13** — port to the multiplatform API.
4. **`androidx.navigation-compose` is externally Android-only** (its only non-Android
   variant is `jvmStubs`). The nav graph cannot be shared without replacing it; that is
   a decision, not a task.
5. **media3 and `:native-engine` are Android-only by product decision.** Ten files that
   touch them are correctly parked and should stay parked.

**Consequence for sequencing:** Phase 5 (desktop, complete) does not wait on Phase 4
finishing, because the desktop app's own navigation and the Android-only player are
different problems. The desktop app can be driven against `:sharedUI` and
`:core-domain` — which is what `:desktopApp` already proves — while the locator refactor
proceeds independently.

### Phase 5 — Desktop, complete *(1.5–2 wks)*

Grow the Phase 1 skeleton into the shipping app.

- Full navigation, window-state persistence, multi-monitor, keyboard shortcuts, desktop-vs-10-foot layout switch.
- Desktop player: VLCJ or mpv, behind `:player`'s existing interfaces.
- `nativeDistributions { targetFormats(Dmg, Msi, Deb) }`; ship `.deb` and add `.rpm`.
- Auto-update channel or a documented manual update path.

**Gate:** launches and is usable on Windows, macOS and Linux from one codebase; desktop holds a full watch session.

### Measured 2026-10-01 — `:app`'s `commonMain` is now clean for a Native target

`apple.yml` compiles all ten Apple-target modules (see the `verify_apple_targets.py` note above),
and the first such run failed at `:android:app:compileKotlinIosArm64` with **18 errors across nine
files and five classes of JVM API in `commonMain`**. All five are now fixed and the fixes are
recorded in `AGENTS.md`; the transferable finding is the one about a gate that cannot reach a
module. In summary: five view models and `LibraryViewModel` had a **defaulted** `ioDispatcher`
(defaulting to `Dispatchers.IO`, which does not exist in `commonMain`), `LibraryPagingSource` and
`LibraryScreen` used it inline, `SubtitleRepository` defaulted its whole `CoroutineScope` to a
`SupervisorJob` it never cancelled, `UserMediaRepository.getCanonicalContinueWatching` defaulted
`nowMs` to `System.currentTimeMillis()` (the same defect `WatchHistoryService` had already fixed
in the same-named method), `HomeCalendarComponents` used a fully-qualified `java.time.LocalDate`
that needed no import and therefore no purity-gate visibility, `SubtitleRepository` used
`synchronized`, and `ItemActionSheet` carried a stale `androidx.compose.ui.res.painterResource`
import alongside the Compose Multiplatform one.

**The new shared function is `formatIso8601MonthDay` in `:core-domain`'s `domain/watch`**, beside
`formatIso8601LongDate` and over the same `MONTH_LABELS` table, with 11 cases in
`FormatIso8601MonthDayTest`. Its output was **measured against the `java.time` expression it replaced
on a real JDK** — all twelve months, the leap day, three invalid dates, and `0000-03-07` — and that
measurement is in the KDoc rather than only in this file.

Two things that measurement settled, neither of which was obvious before it ran:

- **The three invalid dates threw** `DateTimeParseException`, a `RuntimeException`, so the caller's
  `catch (_: Exception) { null }` had been turning them into `null` all along. That is why the
  replacement's `?: return null` gives the same answer, for a reason no reader of either file could
  have derived.
- **`0000-03-07` renders `Mar 7` in both implementations — not because the year is right, but because
  no year is printed.** `formatIso8601LongDate` prints one and must shift it to `0001`. So the
  year-of-era trap this file's own KDoc warns about is *invisible* in the month-day form, and that
  invisibility is the only reason printing no year is safe. Now an assertion rather than a comment.

A first draft of the new KDoc claimed the sibling `iso8601MonthLabel` "never looks at the day", read
off a comment about one caller passing an instant. **The test failed on its first run** — the claim
was about truncation, and `parseIso8601MonthNumber` calls `isValidDate` seven lines further down. The
two functions are strict about an impossible day *identically*; only truncation differs. Recorded
because the mistake is the general one: **a KDoc sentence about one caller is not a statement about
the function**, and the failing assertion is what caught it.

`scripts/mutate_ios_fixes.py` has **4 entries, all caught**, and it deliberately does *not* cover the
other four defect classes. Those were defects *because* they do not compile for Kotlin/Native, and
every mutation of them compiles on the JVM and changes no answer, because callers already pass the
argument explicitly. **Sixteen `expect_survive` entries would have looked like coverage while
observing nothing**; the driver instead states in its docstring that `apple.yml`'s
`:android:app:compileKotlinIosArm64` is their gate, and that run is the one deciding whether the other
four classes are fixed.

### Phase 6 — iOS + Liquid Glass *(2–3 wks)*

**Measured 2026-10-01, and one thing this phase was assumed to be is not.** The queue read
"Apple implementations of the six `:platform-core` ports need their own module, symmetric
with `platform-android` and `platform-desktop`", on the reasoning that `SecretStore` has no
Apple implementation. That reasoning came from `:platform-android`'s KDoc and from nothing
else, and measurement kills it:

- `grep -rl <port> ios --include=*.swift` returns **zero hits for all six ports**.
- `:sharedUI` has **no** `platform-core` dependency at all.
- `ios/CrispyKit` is a **19-file Swift reimplementation of the entire data layer** — its own
  HTTP client, Supabase auth, session store, JSON and seven view models.
- `ios/project.yml` links only `CrispyKit` and `ContractRunner`. **`CrispyUI` is built by
  nothing and imported by nothing.**

So **zero lines of Kotlin execute in the shipping iOS/tvOS app.** A `platform-apple` module
would have no consumer, and could only be "verified" by adding it to the `apple.yml` list
being edited in order to verify it — circular, and the speculative-dependency shape §4.1's
rules forbid. `platform-desktop` landed because `desktopApp` is a real caller; the Apple gap
is a **product decision, not a technical block**. The bullets below are unaffected: wiring
`CrispyKit` to `CrispyUI` is what would *create* the consumer, and it is unchanged.

**What did change is the gate underneath this phase.** Ten modules declared
`iosArm64`/`iosSimulatorArm64` and `apple.yml` compiled **two**, so eight Apple targets had
never been built by anything — and a target nothing builds cannot fail, so it asserted
nothing about platform freedom. `apple.yml` now compiles all ten and
`scripts/verify_apple_targets.py` keeps the list honest. **That is a real prerequisite for
this phase rather than a tidy-up**: the first attempt at wiring `:app` into an Apple app will
be much better informed by a runner that has already compiled all ten.

- Wire `CrispyKit` to the `CrispyUI` framework. Keep the `ContractRunner` import — it is load-bearing, not dead code.
- **Reconcile the existing Swift presentation layer.** `CrispyKit` already contains six files that reimplement what `sharedUI`/`sharedLogic` will own: `DiscoverViewModel`, `DetailsViewModel`, `HomeViewModel`, `CatalogListViewModel`, `HomeSnapshotMapper`, `MediaCard`. Each must either be repointed at the shared state or deleted. This is the bulk of the phase and it gets its own sub-gate.
- SwiftUI shell: navigation, tabs, sheets, **Liquid Glass** on iOS 26, with a real fallback for earlier versions.
- Embed Compose content via `ComposeUIViewController`.
- `CADisableMinimumFrameDurationOnPhone` in `Info.plist` **before** any Compose content is embedded — without it the app crashes at runtime.
- `iosSimulatorArm64Test` green — the gate that has never run.
- Confirm the iOS deployment target (currently 26.0 vs `CrispyKit`'s `.iOS(.v17)`).

**Gate:** runs on simulator and device; contract suite green on `iosSimulatorArm64`; the six Swift files are reconciled; Liquid Glass on iOS 26 with correct fallback below.

### Phase 7 — Harden *(1 wk)*

- Run the full contract suite on **all four** targets: Android, desktop JVM, `iosArm64`, `iosSimulatorArm64`.
- ~~Force every `platform-core` interface implementation onto the desktop target.~~ **Done 2026-09-30 in `:android:platform-desktop`** — a new plain `kotlin.jvm` module holding all six: `DesktopTimeSource`, `DesktopMonotonicClock`, `DesktopAppLogger`, `FileKeyValueStore`, `DesktopSecretStore`, `DesktopDistributionCapabilities`, with 40 tests. `desktopApp`'s `DesktopEnvironment` constructs all six and the window's size is persisted through the injected `KeyValueStore`, so the seam is exercised rather than declared. What the *rest* of this phase still holds: an impl that only works on Android is invisible until the full suite runs on all four targets, which is the first bullet.
- CI gates: zero platform imports in `commonMain`; zero Compose in `sharedLogic`; no KMP library declares a flavour.
  - **This list previously read "zero `org.jetbrains.compose` imports anywhere", and that is wrong.** There are 93, all `org.jetbrains.compose.resources`, which is a real package — 124 classes in the desktop jar, zero `androidx/compose` classes in the same jar. The rule being reached for is §4.1's: no `org.jetbrains.compose.{runtime,ui,foundation,material3}` alias imports. A gate that fires on correct code gets switched off.
- Publish the platform-support matrix: per module, shared vs platform-specific, with file paths.
- Write the add-a-target runbook.

**Gate:** all four targets green; all gates pass; matrix and runbook published.

---

## 7. Risks

| # | Risk | Severity | Mitigation |
|---|---|---|---|
| 1 | ~~`iosSimulatorArm64Test` has never run green — unverifiable on a Linux host~~ **CLOSED.** `apple.yml` ran green on `392cacd5` (2026-09-28): the contract suite compiled for `iosSimulatorArm64` and `iosSimulatorArm64Test` passed on a real simulator, for the first time. `android.yml` is green on the same tree. This is why the seam proof was built in Phase 1 rather than deferred — the whole point was to make this unknown resolvable early. Note the caveat: the run used Gradle's default log level, so the passing step confirms the task succeeded but the per-test names are not in the archived log. |
| 2 | `PlayerSessionViewModel` split changes behaviour (1,410 lines) | **High** | Phase 4, own gate: screenshot diff **and** full player pass |
| 3 | Effective coverage is 96 fixtures + 4 goldens | **High** | Goldens re-verified on every UI move; manual checklist per flavour |
| 4 | Adding a Compose import to `sharedLogic` degrades the iOS shell | **High** | CI gate, cheap, immediate |
| 5 | Six Swift ViewModels duplicate what shared code will own | **High** | Phase 6 sub-gate; reconcile or delete, never leave both |
| 6 | `BuildConfig` absent in KMP libraries; `VERSION_NAME` may not replicate | Medium | Phase 3; verify explicitly |
| 7 | Android + desktop-JVM cannot share a source set | Medium | Design `commonMain` boundaries around it (§5.3) |
| 8 | Shared-transition CMP limits bite the 28 call sites | Medium | Verify explicitly in Phase 4 |
| 9 | `R.font` references break in `commonMain` | Low | `composeResources`, Phase 4 |
| 10 | Player surface binding breaks when XML layouts move module | Low | Phase 1; playback smoke test |
| 11 | `responsivePageHorizontalPadding` width source substituted (19 sites) | Low | Golden-screenshot diff |
| 12 | iOS deployment target mismatch (26.0 vs `.v17`) | Low | Confirm in Phase 6 (CMP floor is iOS 14) |

**Settled, not open:** CMP ↔ Kotlin 2.4.10 compatibility · shared transitions (shipped since CMP 1.7.3) · GPL and all player licensing · flavour handling · desktop platform strategy (one JVM module for all three OSes) · iOS UI strategy (SwiftUI shell + Compose content) · module count (one `:sharedUI`, no `:ui`).

---

## 8. Effort

| Phase | Est. |
|---|---|
| 1 Shells + prove the seam | 1.5–2 wks |
| 2 Data layer | 1.5–2 wks |
| 3 Flavors + configuration | 1 wk |
| 4 Shared UI | 3–4 wks |
| 5 Desktop, complete | 1.5–2 wks |
| 6 iOS + Liquid Glass | 2–3 wks |
| 7 Harden | 1 wk |
| **Total** | **12–16 wks** |

Phase 1 is the insurance premium. Roughly one day of it is the desktop seam proof, and that day is what keeps Phases 2–6 from being open-ended debugging on a platform you cannot test locally.

---

## 9. Sources

- [A New Default Project Structure for Kotlin Multiplatform](https://blog.jetbrains.com/kotlin/2026/05/new-kmp-default-structure/) — JetBrains, May 2026
- [Recommended KMP project structure](https://kotlinlang.org/docs/multiplatform/multiplatform-project-recommended-structure.html) — the `sharedLogic`/`sharedUI` split, entry-point extraction, `androidLibrary` DSL
- [Hierarchical project structure](https://kotlinlang.org/docs/multiplatform/multiplatform-hierarchy.html) — default hierarchy template, JVM+Android restriction
- [Set up the Android Gradle library plugin for KMP](https://developer.android.com/kotlin/multiplatform/plugin) — single-variant constraints, flavour-bearing-library workaround, no `BuildConfig`
- [Updating multiplatform projects to AGP 9](https://kotlinlang.org/docs/multiplatform/multiplatform-project-agp-9-migration.html) — why entry points must be separate modules
- [Multiplatform ViewModel](https://kotlinlang.org/docs/multiplatform/compose-viewmodel.html) — shared ViewModel, SwiftUI consumption
- [Liquid Glass in a Compose Multiplatform app](https://kotlinlang.org/docs/multiplatform/ios-liquid-glass.html) — native SwiftUI shell + Compose content
- [Compose Multiplatform releases](https://github.com/JetBrains/compose-multiplatform/releases) — 1.11.1 is the current pairing for Kotlin 2.4.x
- [Kotlin/kmp-production-sample](https://github.com/Kotlin/kmp-production-sample) — shared logic + fully native UI per platform
