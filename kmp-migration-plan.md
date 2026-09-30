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
| 4 — Shared UI | **in progress** — resources done, `commonMain` path proven; **106 of 186 `:app` files moved** |
| 5 — Desktop, full | **started** — `:desktopApp` depends on `:app` and renders **two** of its `commonMain` screens, `ContinueWatchingRail` and `ImageSettingsScreen`, and constructs **all six** `platform-core` ports through `:android:platform-desktop`, so the seam is no longer a claim about compilation. It is still 3 screens' worth of code, not an app: no navigation seam, and the settings screen is reachable only from a placeholder affordance in the window |
| 6 — iOS + Liquid Glass | SwiftUI shell exists; never built against shared code. `CrispyUI` is built and exported and **nothing imports it** — `grep -rn "import CrispyUI" ios/` returns nothing |
| 7 — Harden | Apple CI done; the rest not |

**Where `:app` stands, and the honest shape of the remainder.** The 80 files still in
its `androidMain` are **not** 80 independent jobs. A reverse audit — forbidden imports
first, then subtracting every type declared in every module's `commonMain` — leaves
**10 candidates and resolves 0 of them**. Every one is pinned by a measured wall:
`androidx.navigation` (six nav-graph files, which §Phase 4 already says should stay put),
`paging-compose` (`DiscoverScreen`, `library/LibraryRoute`), the composition root,
`:android:native-engine` (the four `playerui` files), and `org.json`, which is permanent.
So Phase 4's remaining file count is a misleading measure of the work left, and Phase 5
is not waiting on Phase 4.

**Where the files are, measured per module:**

| Module | `commonMain` | `androidMain` |
|---|---|---|
| `core-domain` | 29 | 0 |
| `platform-core` | 7 | 0 |
| `sharedUI` | 4 | 0 |
| `player` | 6 | 0 |
| `addons` | 10 | 5 |
| `backend` | 9 | 8 |
| `home` | 13 | 4 |
| `network` | 2 | 4 |
| `watchhistory` | 2 | 3 |
| **`:app`** | **106** | **80** |
| **total** | **187** | **104** |

**`:app` is no longer the only module that matters, and every other module is now
*finished* rather than "near its resting point".** `addons`, `backend`, `home`,
`network` and `watchhistory` have all crossed over — `home` and `backend` have more
files in `commonMain` than in `androidMain`. `:app` is 106 of 186, i.e. **57%**, and
the 80 that remain are behind the walls listed in the table above.

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

- *"Split `PlayerSessionViewModel` (1,410 lines)"* — still 1,409 and untouched. Unchanged
  and still the highest-likelihood regression in the migration.
- *"Verify the 28 shared-transition call sites"* — there are **10**, not 28, and they span
  6 files. Cheaper than planned.
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
`:platform-core`'s `commonMain`, beside the interface, and both sides reference it.

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
- Move screens from `:app` into shared code, **in vertical slices, one screen at a time**, each with a golden-screenshot diff.
  - **Corrected 2026-09-29:** the first destination is **`:app`'s own `commonMain`**, not `:sharedUI`'s. `:sharedUI` owns the design system and its assets; phone/tablet UI that `:tv` does not use goes to `:app/commonMain`, and `:app`'s empty `commonMain` is the thing that needed proving. Anything genuinely shared by both surfaces goes to `:sharedUI`.
- The import rule in §4.1 applies to every file moved: coordinates change, imports do not.
- `R.font.archivo_top10` → `composeResources`. Superseded by **Step 1: resources** below, which is the real prerequisite for moving any composable and splits it into two parts that must not be bundled.
- Split `PlayerSessionViewModel` along use-case lines. **Highest-likelihood behavioural regression in the migration** — state, coroutine scoping and recomposition are all in play. Diff behaviour, not just pixels. **Still 1,409 lines and entirely untouched as of 2026-09-29.** Do it while the golden gate is fresh rather than at the end.
- Verify the shared-transition call sites against CMP's documented limitations (no `AndroidView` interop, `ContentScale` snapping, no shape-clip animation). **Corrected 2026-09-29: there are 10, not 28, across 6 files** — `DetailsHero`, `HomeCalendarComponents` (×2), `HomeHeroCarousel`, `PersonDetailsRoute`, `PersonCircleCard`, `SharedCardBackdrop`, plus the scope itself in `LocalSharedTransitionScopes.kt` and the host in `AppNavHost.kt`.
- Tokenise the design system so a 10-foot TV surface and a resizable desktop window are both servable **without changing today's appearance**.
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
| Hard wall | **84** | `Context` (59), `java.*` (31), `androidx.navigation` (9), `androidx.media3` (6), `native-engine` (10), `org.json` (8), `okhttp3` (3), `materialkolor` (1) |
| `coil3` only | 13 | real work, not a blocker: coil3 is multiplatform, these need the same `Int` → resource porting the drawables already got |
| No wall | 23 | but 0 of them are closable without upstream moves; the largest gate is **4 files** |

The per-file counts above are **historical** (measured with 120 files left, and several
of those blockers have since been removed by the service-locator conversion). The buckets
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
2. **`CrispyBackendClient` (17) and `SupabaseAccountClient` (10)** are `androidMain`
   because they speak OkHttp and `org.json` — the same reason `BackendTypes.kt` had to
   leave the client. Their *vocabulary* is already portable; what is missing is a
   transport port (`HttpClientPort`, still deferred) so the interface can be commonMain
   while the implementation stays Android.
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

### Phase 6 — iOS + Liquid Glass *(2–3 wks)*

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
