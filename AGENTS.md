# Agent Guide (Goal, Build, Test, Style)

## Goal

**This project is a Kotlin Multiplatform app: one codebase runs on Android, Android TV, iOS/tvOS and desktop (Windows, macOS, Linux).**

Android and TV ship today. **The desktop app is the work in front of you** — `:android:desktopApp`
builds its own `AppServices` and `AppGraph` and boots the shared shell's **real** intro/auth/
profile gate, so it is an app that authenticates rather than a hardcoded pair of screens. **`AppRoot`
and its `MainAppShell` are already `commonMain` and already callable off Android** — `MainAppShell` is
a `private fun` at `AppRoot.kt:96`, private to a file that sits in `commonMain`, so nothing about
them is an Android pin. The one thing between `:desktopApp` and the shared shell is that it cannot
build an `AppNavHostDependencies`: 40 members, of which the four `@Composable` Android-only slots
(the Media3 trailer layer, the two `:ui-assets` badge composables, the YouTube dialog), the player
destination, and the factories behind `StreamResolverProvider`/`PlayerStreamHandoff` have no desktop
answer yet.

**The route-argument seam is not a blocker and never was.** `NavBackStackEntry.arguments` is a
common `SavedState?`, not an `android.os.Bundle`, and `:app` reads route arguments off
`savedStateHandle` — `HomeRouteArguments.kt`, three `internal` readers, and **zero** `.arguments`
reads left anywhere under `android/app/src`. What *is* still true is the `when`: `desktopApp/Main.kt`
keeps a `private enum class DesktopScreen` that exists only because the shell could not be reached,
and its own KDoc says it was written to be deleted rather than extended. The next landings are a
desktop `AppNavHostDependencies`, the deletion of that `when`, and then the desktop chrome — which is
a `:app` `commonMain` decision, because `MainAppShell` already owns the chrome and puts a
`FloatingBottomBar` at the bottom where Crispy-web puts a top topbar. Desktop playback (a real
player behind `:player`'s interfaces, from Nuvio) and the Apple wiring come after that.

This file describes the project as it is. It is not a phase map and holds no migration history —
the multiplatform port is finished.

Also orthogonal to the goal but binding on every change: Android and Swift must stay aligned with
`contracts/SPEC.md` (`android/core-domain` and `ios/ContractRunner`).

Repo agent rules:
- No `.cursor/rules/` or `.cursorrules` found.
- No `.github/copilot-instructions.md` found.

## Toolchain (match CI)

- JDK 21 **everywhere**, including `jvmToolchain` in `:android:core-domain` and `:android:app`.
- Android SDK `platforms;android-37.0` + `build-tools;36.0.0`
- Gradle 9.7.1 via the committed wrapper: always use `./gradlew`, never a bare `gradle` (it is not
  installed, and a different Gradle version starts a second daemon that nothing reclaims)
- Python 3.12 + `jsonschema==4.23.0`
- Xcode + `xcodegen`; Swift tools 5.9

## Project Layout

| Module | Kind | Notes |
|---|---|---|
| `:android:androidApp` | `com.android.application` | manifest, app-only `res/`, signing, ProGuard, ABI splits, the `store`/`sideload` flavours, the golden screenshots |
| `:android:app` | KMP + Compose | the shared UI and presentation. **173 `commonMain` / 39 `androidMain` / 9 `jvmMain` / 9 `desktopMain`** — counted by `verify_kmp_structure.py --json`, not by eye. `jvmMain` is the JVM layer **both** JVM targets compile, and holds what a JVM API (not Android) pins; a factory split across it puts only its wiring there, because `create(Class<T>)` has no common spelling. |
| `:android:sharedUI` | KMP + Compose | the design system **and the design assets**; produces the `CrispyUI` iOS framework |
| `:android:ui-assets` | `com.android.library` | only what CMP cannot carry — launcher mipmaps, splash colour + 2 drawables, 9 provider-logo SVGs |
| `:android:core-domain` | pure KMP | domain rules, no Android types/IO, **and the contract suite in `commonTest`** |
| `:android:player`, `:network`, `:addons`, `:home`, `:backend`, `:watchhistory`, `:platform-core` | KMP | the feature and port modules |
| `:android:platform-desktop` | `kotlin.jvm` | the desktop side of all seven ports. Exists because `desktopApp` is a real caller |
| `:android:desktopApp` | `kotlin.jvm` | the desktop entry point. **Boots the shared bootstrap gate off one `DesktopAppServices` + `AppGraph`** |
| `:android:tv` | `com.android.application` | Android TV app; stays on the Android source set |
| `:android:youtube-extractor`, `:android:torrent-engine` | sideload-only | optional engines, excluded **structurally** from the store build |
| `:android:native-engine` | `com.android.library` | the MPV/Media3 player and nothing else optional |
| `:android:plugins` | plain Android lib | QuickJS bridge, `store` source set unread on purpose |
| `android/torrent-engine`, `android/plugins`, `android/tv` | plain Android | **a plain `com.android.library` cannot be consumed from a KMP `commonMain` at all** |

Six facts about this layout that cost time to learn:

- **A plain `com.android.library` cannot be consumed from a KMP `commonMain`.** Only a
  `com.android.kotlin.multiplatform.library` publishes a JVM variant. `:native-engine` and
  `:ui-assets` are both plain libraries. Check `plugins { }` before adding a module to a common
  source set; the error is an ambiguous-variant listing that never hints the cause is the module
  *type*.
- **A `commonMain` file is worth nothing until a non-Android target consumes it**, so a file count
  is not progress: ask what runs it. `:android:app` now has a **`desktopMain`** source set of its own
  (4 files: `DesktopAppServices`, `DesktopAppRoot`, and the two desktop factory files), and
  `:desktopApp` is its only caller.
- **A `ViewModelProvider.Factory` cannot be written once for both platforms.** `javap` on
  lifecycle-viewmodel 2.11.0's Android artifact shows **three** `default` members (`create(Class<T>)`
  throws, `create(Class<T>, extras)` and `create(KClass<T>, extras)` funnel into it), while the
  **common metadata declares exactly one overridable signature**, `create(KClass<T>, extras)`. And
  `KClass.isAssignableFrom` **does not exist** in Kotlin 2.4.10's common `KClass` — so the Android
  factories' `isAssignableFrom` guard has no portable spelling. That is why the ViewModel *wiring*
  is `commonMain` (`AccountViewModelBuilders.kt`) and the *factory objects* are per platform: two
  androidMain twins plus two desktopMain twins of the same names, which is legal because sibling
  source sets are separate compilations. `compileCommonMainKotlinMetadata` is what catches the wrong
  attempt, with `'create' overrides nothing`.
- **`:android:home` shares the package `com.crispy.tv.home` with `:app`**, so a type declared in its
  `androidMain` is reachable from `:app` **with no import at all** — the purest form of the trap
  that makes an import-only audit report confidently-empty answers.
- **The 39 remaining `:app` `androidMain` files are not a backlog.** Each carries a real platform
  pin. Measured by import, and **these sets overlap** — a pin is per file, so a file holding two pins
  appears twice and a first-match partition would report only the first (see Rules §1):

  | token | files |
  |---|---|
  | `import android.content.Context` | 23 |
  | `androidx.media3.*` | 6 |
  | `androidx.compose.ui.platform.LocalContext` | 7 |
  | `import java.util.Locale` | 2 |
  | a `R.<type>` reference | 3 |
  | `androidx.navigation.*` | 1 |

  **A wiring `Context` is not the same pin as a calling one** — a factory's `Context` belongs to the
  factory, so a bucket that lumps them reads as 23 blocked files when many are correct. **Expect to add
  files here, not to drain them.**

  **But "pinned by `Context`" no longer means "stays in `androidMain`".** `jvmMain` exists, both JVM
  targets compile it, and a `Context` used only to reach `AppGraph` or an `AppServices` member is
  discharged by taking that object instead — which is the difference between a file that is written
  once and a file written twice as an androidMain/desktopMain pair. What genuinely cannot leave is
  what needs `Class<T>` (the factory's `create`, which has no common spelling) or a `java.awt`/media3
  type. `CatalogViewModelBuild.kt` was the proof of the shape — the wiring moved, the `create(Class<T>)`
  shim stayed — and it now has siblings: `DiscoverViewModelBuild.kt`, `RandomWheelViewModelBuild.kt`,
  `PersonDetailsViewModelBuild.kt`, `CalendarViewModelBuild.kt`, `SearchViewModelBuild.kt` each took
  their factory's wiring into `jvmMain` while the same-named `androidMain` file shrank to the
  `create(Class<T>)` shim, and three functions moved whole with no shim at all
  (`BirthdayDateFormat.kt`, `AndroidLanguageLabels.kt`, `DeviceUtcOffsetMillis.kt`).
  `AndroidAppLogger(appContext)` became `graph.services.logger` and
  `SharedPreferencesKeyValueStore(context, name)` became `graph.services.keyValueStores.store(name)`:
  `AppGraph.services` is an `internal` member of `:app`, so `jvmMain` can read it.
  **The reverse also holds — a move is blocked when the collaborator itself is platform-bound**:
  `AddonsSettingsViewModelFactory` could not move because `metadataAddonRegistry(context)` lives in
  `:addons` `androidMain`, and a plain `com.android.library` publishes no `jvmMain` variant.
- **`:android:sharedUI`** — CMP `1.11.1` pinned to Kotlin `2.4.10`; bump together or not at all.
  **Do not re-litigate Material3 Expressive, and do not "fix" it by dropping it.** `android { }` is
  current, `androidLibrary { }` is deprecated; **CMP 1.11.x ships `androidx.compose.*`, not
  `org.jetbrains.compose.*`**, so moving a file changes *coordinates*, never imports;
  **`platform(...)` does not exist on a KMP source set**, so a KMP library pins versions itself; and
  **no `linuxX64` on Compose modules** (its purity gate is `jvm("desktop")`). **A missing
  `CrispyPalette` role mapping in `:tv` is a silent colour change, not a compile error.**

`android/app/build.gradle.kts` carries a per-file table of what holds what. Read it before planning
any change to `:app`.

### The two reference projects for the desktop work

Two reference projects, and **the division between them is the whole point — do not blur it.**

| | comes from | because |
|---|---|---|
| **Player and desktop mechanics** | **Nuvio** | It has solved the hard half: libmpv on three OSes, a native AWT surface peered into Compose, fullscreen, PiP, window chrome. **This is the most important thing we take from anywhere.** |
| **Layout, information architecture, feel** | **Crispy-web, roughly** | It is this project's own predecessor, so it already knows the product. **Its implementation is the part we are replacing.** |
| **Composition and wiring** | **neither — `:app`** | The desktop plugs into the seams `:app` already exposes, and **all of them are already `commonMain`**: `AppBootstrapGate(…, ready)` (intro → auth → profile selection, and `ready` gets `onSignedOut`), and `AppRoot`, which calls the gate with `MainAppShell` and takes the `@Composable () -> AppNavHostDependencies` producer. `AndroidAppRoot` is the *Android* wrapper around it — the one that reads the graph off the `Application` — and `DesktopAppRoot` is its desktop twin; neither is what the desktop is waiting for. |
| **Widget vocabulary** | **neither — Compose + `:sharedUI`** | See below; this is the line that is easiest to cross by accident. |

**Neither is a code source.** Do not port files across, and do not build a transliteration layer.
**The vocabulary stays Compose + Material3 + `CrispyPalette`**: read the web app for *what is on
screen and roughly where*, then build it from `Scaffold`, `TopAppBar`, `NavigationRail`,
`ModalBottomSheet`, `Dialog`, `LazyRow`, `LazyVerticalGrid`, `WindowInsets`, `onKeyEvent` +
`FocusRequester`, `detectTransformGestures`. **Its shadcn wrappers, Tailwind class soup and CSS
custom properties are the old implementation and do not come across** — and if this work ends up
producing Compose wrappers whose names mirror shadcn components, it has become a port of the web
app rather than a desktop app. `:app`'s `commonMain` already carries `TopLevelDestination`,
`SearchTopBar`, `FloatingBottomBar` and `CrispySharedTransitionLayout`; assemble the desktop
chrome from the shared shell's own components instead of modelling it on `Topbar.tsx`.

Nuvio keeps desktop as a `jvm("desktop")` target *inside* its shared module (132 `desktopMain`
files against 127 `androidMain`), so it is a reference for **how much** desktop surface a real app
carries, not for where to put ours.

**From Nuvio — the mechanics, and this is the priority.** Measured in
`desktopMain/kotlin/com/nuvio/app/Main.kt`, 285 lines:

- **`SwingWindow`, not `Window`** — required because the native player surface is an AWT
  `JComponent` peer. Ours uses `Window` today and will have to change when a player lands.
- **An ordered `main()` preamble**, each step a named one-line initialiser, in a fixed order that
  is not interchangeable: `initGtkEarly()` on Linux **before** AWT/Compose/Skia (Skiko otherwise
  half-loads GDK and you get a `GdkDisplayManager` type-registration conflict), then Swing/HiDPI
  globals **before** anything touches AWT (installing the open-URI handler first left HiDPI Linux
  at 1×), then cached profiles **before the first Compose frame** so the first frame already has
  the profile colour.
- **`jvmArgs`**: `-Djdk.gtk.version=0` plus five `--add-opens` (`java.awt`, `sun.lwawt`,
  `sun.lwawt.macosx`, `sun.awt.windows`, `sun.awt.X11`).
- **`nativeDistributions`** formats `Dmg, Msi, Deb, Rpm, AppImage`, with per-OS icon files
  (`.icns`/`.ico`/`.png`), `CFBundleURLTypes` for custom schemes, and nullable credential-driven
  signing + notarization.
- **Window state as a global, non-profile-scoped preference** persisted from `snapshotFlow` on
  every change — including position, maximized and fullscreen — with the rule **persist geometry
  only while windowed**, because fullscreen coordinates are not a meaningful position to restore.
  Ours persists width/height only, on close.
- **A `smokePlayerUrl` harness branch** driven by a gradle property / env var that renders just the
  player surface instead of the app — a CLI-drivable "does this actually decode" gate.
- **Its player is a native-bridge problem, not a dependency problem**: hand-written libmpv bridges
  per OS plus **bundled runtimes** (macOS 115 MB, Windows 111 MB checked in) and explicit task
  ordering ahead of every desktop run task. `features/player/desktop/` is **3,283 lines** across 13
  files, `NativePlayerController.kt` alone being 1,872. **Budget for that order of magnitude —
  `:player`'s existing interfaces are the seam, and this is the work behind them.**

**From Crispy-web — the layout, roughly, and its implementation not at all.** It is the
React/Vite **predecessor of this very project**, so its information architecture is the one worth
reading. **Its layout already *is* the desktop layout** — only three files carry a `*Desktop`
suffix (`HeroDesktop`, `MetaDetailsDesktop`, `PlayerDesktop`), one of them is a 12-line shim, and
there is **no `isDesktop` / media-query branch anywhere**, so there is no responsive switch to
reproduce and no mobile variant to design around. Read it for what the topbar carries, what the
sidebar carries, what the hero shows, that details open over the content, and which panels the
player has — then build those in Compose.

**Two of its mechanics are worth knowing about even though the code is not coming across.** Its
`index.css` hides the header on immersive routes
(`.app-layout-root[data-route-type="meta"|"player"|"auth"]`), and its "Dual Layer Architecture
(MPV Mode)" makes `html, body, #root` transparent with `!important` "to let native video show
through". **That is the web's version of the transparent-window fullscreen problem, and Nuvio's
answer to the same problem is the opposite one** — an opaque AWT window/`rootPane`/`contentPane`
at `0x0D0D0D` with `isOpaque = true`, and fullscreen handled by `WindowPlacement`. Ours is the
Nuvio kind.

**The palette is a nudge, not a merge.** Crispy-web's tokens are a shadcn set (`--background:
0 0% 2%`, `--card: 4%`, `--accent: 12%`, `--muted-foreground: 60%`, `--radius: 1rem`,
`--font-display: 'Archivo Variable'`). Ours, `CrispyPalette`, is a **Material3 role set** and
reads two-to-three steps lighter: `background 0xFF141414` against ≈`#050505`, `surface 0xFF1F1F1F`
against a 4% card, `surfaceVariant 0xFF2A2A2A` and `outline 0xFF333333` **above anything in the
web's token set**. Exactly one role coincides —
`surfaceContainerLowest`/`surfaceDim` `0xFF0A0A0A` is the web's *card*, not its background. **So
the two are not transcriptions of each other and must not be reconciled into one vocabulary:
`CrispyPalette` stays a Material3 role set and a few of its roles move a step darker where the
desktop should read like the web app.** That touches the shipping Android app and `:tv`, and a
missing `:tv` role mapping is a silent colour change rather than a compile error — so it is a
product decision about shipped surfaces, and it gets **its own landing**.

## Commands

Contracts (fast):
```sh
python3 -m pip install jsonschema==4.23.0
python3 scripts/validate_contracts.py
./gradlew :android:core-domain:desktopTest :android:core-domain:testAndroidHostTest
swift test --package-path ios/ContractRunner
```

Frequently used tasks:
```sh
./gradlew :android:app:testAndroidHostTest --tests 'com.crispy.tv.distribution.AppDistributionTest'
./gradlew :android:app:desktopTest --tests 'com.crispy.tv.settings.KeyValueStorePlaybackSettingsRepositoryTest'
./gradlew :android:desktopApp:compileKotlin   # proves an :app commonMain change still renders off-Android
./gradlew :android:desktopApp:test
./gradlew :android:backend:desktopTest        # gate before changing BackendApi or AccountApi
./gradlew :android:home:desktopTest :android:home:testAndroidHostTest
```

Android builds and lint: `./gradlew :android:androidApp:assemble{Sideload,Store}{Debug,Release}`,
`:android:tv:assemble{Debug,Release}`, `:android:androidApp:lint{Store,Sideload}Debug`,
`:android:tv:lintDebug`.

Apple placeholder compile gate: `xcodegen generate --spec ios/project.yml`, then
`xcodebuild -scheme CrispyRewrite{iOS,tvOS} -destination 'platform=iOS Simulator,name=iPhone 16'
build` (or `platform=tvOS Simulator,name=Apple TV` for tvOS).

Local gates (`./check-local.sh` runs all of them):
```sh
python3 scripts/validate_workflows.py      # workflow YAML, no duplicate keys
python3 scripts/check_common_purity.py     # commonMain may not import JVM/Android
python3 scripts/verify_kmp_structure.py    # commonMain may only depend on commonMain
python3 scripts/verify_kmp_outputs.py      # no compiled class without a source declaration
python3 scripts/verify_apple_targets.py    # a declared Apple target must be built by CI
python3 scripts/validate_contracts.py
```

## Rules

Distilled from every gate that fired and every compile that failed. Each one is here because it
changed a result.

### 1. Measure before you claim

- **A count is a claim about the code, and re-measuring it is one command.** A count two documents
  share is twice as likely to be believed and no more likely to be right. **An omission nobody
  mentioned reads as a decision nobody made.**
- **Re-test a premise this file or a build file states as settled.** A KDoc sentence about one caller
  is a comment about that caller, not a statement about the function; a per-file table was once wrong
  about both of the entries it listed for one file. **Correct every copy in the same commit — a
  corrected premise with two surviving copies is worse than the original, because the next reader
  finds both. And one of those copies can be the GATE itself**, so after any rename **grep the
  scripts, not just the docs.**
- **"No equivalent exists" is the version that rots.** Measure the artifact you are about to declare;
  never declare the artifact, declare the measurement.
- **A document's heading, prose class, or type names are not evidence about the code.** `architecture.md`
  carried seven type names that never existed and a plan section whose numbers meant something
  entirely different from the tracked plan's. A section carrying an explicit disclaimer that its
  names are illustrative is obviously illustrative; a sibling section with no disclaimer is not.
- **A pin is per file, not per token — the token you hunted is rarely the one that decides.** Read the
  whole import list of a file you claim to understand, and **treat every second pin as the one that
  decides.** A first-match partition reports a file's *first* pin, so **a bucket is a floor, not a
  description.**
- **A dead import reports itself as a pin.** An import whose simple name occurs once in the file is
  dead; an import occurring twice is the control that shows the rule is not "delete anything seen
  once". **A token scan counts an import whether or not anything uses it.**
- **A pin can arrive through a *member function on a value*, and no import scan can see it.**
  `String.format` and `MutableMap.putIfAbsent` are JVM-only and need no import to write;
  `Dispatchers.IO` is `public` on the JVM and **`internal` on Kotlin/Native**, so the compiler reports
  "cannot access", not "unresolved". **The only instrument that finds this family is a metadata
  compilation** — `:android:app:compileCommonMainKotlinMetadata` compiles `commonMain` against the
  **metadata** variant of every dependency, so it is the only task that catches both directions.
  `compileKotlinDesktop` and `compileAndroidMain` each miss one. **"The import list is clean" is not a
  measurement.**
- **Run the audit in both directions.** A forbidden-token scan answers *pinned by an import*.
  Subtracting every type declared in every module's `commonMain` from the capitalised identifiers a
  file uses answers *pinned by a sibling* — overlapping `com.crispy.tv.*` packages make another
  module's `androidMain` reachable with **no import at all**. And **zero forbidden imports does not
  mean movable, because the blocker can be a _type_**: grep its distinctive types and read the owning
  module's `plugins { }`.
- **A gate that does not compile the source set you changed certifies nothing and still returns
  `BUILD SUCCESSFUL`.** Check which source sets a task names before trusting it.
- **A guard scoped to a _form_ is a claim about that form.** Scope a "must be gone" guard to the
  *member name*, not the expression calling it — the expression is the part a file may rewrite and
  the name is not. **And a guard on a bare token is satisfied by the legal replacement that
  discharges it** (`ManifestUri.parse(` *contains* `Uri.parse(`), so scope it with `\b`.
  **Write "must be gone" as "is exactly the thing that should remain"** — a guard that cannot
  distinguish a correct edit from a broken one gets switched off.
- **A safeguard that cannot fire is not a safeguard, and it is invisible in review precisely because
  it looks like one.** A `defaultdict` makes an empty bucket invisible; seed the table with every
  *declared* bucket name first — that pre-seeding turned two silently-never-matched rules into visible
  zeros. A gate that parses a sentence it does not own must **fail when it cannot find it**: a literal
  space in that regex once made it print a clean summary **while checking nothing at all**. A target
  declaration no CI job builds **cannot fail, so it asserts nothing**. A checksum that prints `True`
  is a claim, not a check. **Re-prove a gate you changed by violating its premise — removing a token is
  not evidence that the gate still works.**
- **A stale class file can outlive the declaration that produced it, and the cause is not
  established.** A stale class binds a reference that should have failed to compile, so a later green
  build certifies nothing and **every other gate becomes unreliable**. `verify_kmp_outputs.py` is a
  *detector*, not a fix, and reads `build/classes/kotlin` so it must run **after** the compile tasks.
  **If it fires on an artefact you did not inject, that is the observation which explains it** —
  capture it rather than reaching for a mechanism.
- **A `commonMain` file holding a small pure thing no test can reach is an *extraction*, not a
  move.** Push the pure half toward `commonMain` even when the platform half is smaller, or the pure
  half stays untestable. Deciding which half is the measurement.
- **`org.json` is not a dependency of this project at all** — it is an `android.jar` platform class
  appearing in exactly one build file. A file that parses or writes JSON stays in `androidMain`.
  Neutralising the accessors moves nothing, because consumers name an `org.json` node **in the
  signature**, and an untyped tree cannot carry it since `JSONObject.NULL` and an absent key are
  different events. **The decision is `kotlinx.serialization.json.JsonElement`** — the only candidate
  that keeps a JSON null distinguishable from an absent key.

### 2. Ports, seams and slots

- **`Context` used for *wiring* belongs in the factory. A `Context` used for a *call* is a capability,
  and the slot carries the data** — `shareText: (String) -> Unit`, `openUrl`, `stashHandoff`. A slot
  over `(Context) -> Unit` keeps the platform type on the wrong side of the line.
- **A `Context` *holder* in a constructor parameter is the pin that keeps an otherwise-portable class
  out of `commonMain`** — a file whose parameter names a concrete platform holder cannot be read from
  `commonMain` however portable its body is. **A class whose constructor takes only a `Context` is a
  composition root wearing a class's clothes.** Grep the property initialisers, not the parameter
  list; move the wiring to the factory and leave the class behind it.
- **A class that builds itself from a `Context` in a companion is that composition root, and it is
  one landing, not two** — the class moves, `companion object { fun create(context) }` becomes a
  top-level `androidMain` function, and an interface goes between them. Correct a composition root's
  own comment in the same commit when the landing changes the placement it asserts, and widen a
  `private` class to `internal` — a `private` member cannot be named by the factory meant to construct
  it.
- **The cheapest pins to discharge are the ones that die with their sole consumer** — a `Context` read
  only to reach the factory disappears without a slot of its own, and a `remember` that existed only
  for it goes too. **A pin that vanishes when the thing that read it moves is not visible as a pin at
  all while both halves sit in the same file.**
- **A slot must cross EVERY hop between where it is decided and where it is used, and the compiler
  only names the deepest one.** `"Unresolved reference"` at a call site means the parameter is missing
  from a signature *above* it. Expect one error per un-threaded level, not one per missing hop.
- **The payload's unit is part of the slot's contract, so pick the one the file's other slots already
  use** — three `(Long) -> String` parameters differing in unit is a trap nobody sees at the call
  site. **A `java.time` type in a slot signature is the pin the slot was opened to discharge.**
- **The slot should carry the whole platform step, including the step that looks portable.**
  `titlecase(Locale.ENGLISH)` is locale-dependent while `replaceFirstChar { it.uppercase() }` is not,
  so splitting them puts the Turkish dotless-i bug in shared code. *A step is platform work if its
  answer depends on the platform; "it is just a `String` call" is not the test.*
- **Read what the consumer DOES with the value before typing the slot.** A value it *stores* must stay
  a lambda; a value it merely passes on may be the product. **The counterexample: a `produceState`
  consumer keys on the lambda's identity**, so a fresh one each recomposition restarts the load and it
  must stay a suspend lambda. *The question is not whether a slot is a lambda or a product; it is
  whether the consumer keys on its identity.*
- **No-default slots for anything a call site must not forget.** A defaulted capability lets a call
  site silently hide a row the build ships; a defaulted `CoroutineScope` hides the lifetime decision
  from every call site. **A class that builds its own `CoroutineScope(SupervisorJob() + …)` owns a job
  nothing cancels** — pass the caller's scope instead.
- **When a platform composition local is unreachable, the answer is usually a value the caller already
  has** (`isWideScreen`, `isCompact`, `pluginsUiSupported` all crossed as data from something in scope).
- **A registration function crossing as a function needs *both* the receiver and the controller, and
  `::name` is not a shortcut for either.** A first-class reference to an *extension* needs its
  receiver, and `NavGraphBuilder.() -> Unit` compiles as neither — it fails at the call site with
  **`No value passed for parameter 'p1'`**, and `p1` is a synthesised name, so its appearance means the
  compiler read a plain `Function1` where an extension type was declared. The working shape is
  `(builder: NavGraphBuilder, navController: NavHostController) -> Unit`, where one argument is a scope
  that only exists inside the file asking for it.
- **When the object cannot be built by the caller, the slot is `@Composable () -> T` and not `T` —
  a fourth slot shape, not another instance of an existing one.** `AppNavHost` needs ~40 platform
  products and two inputs to building them are composition locals, so the bundle cannot be constructed
  by a `commonMain` caller with no context. The seam is therefore a *producer*,
  `dependencies: @Composable () -> AppNavHostDependencies`, and `AppRoot` passes the producer down as
  the same slot type rather than calling it, so exactly one composable scope in the chain owns the
  `remember`s. **`@Composable (Args) -> Unit` is a rendering seam, `(text: String) -> Unit` is a
  capability, `@Composable () -> T` is a wiring seam.** What separates them is not "is this a lambda"
  but **who has to be a composable for it to work.**
- **A port's members are the union of every caller's** — measuring one caller once gave four of five
  members, and the fifth was called from another file. **Building the interface from the file you
  happened to move is a behaviour change.** **An interface grows when a second caller appears, and
  every comment that justified a member's *absence* is then false.**
- **Name the port after the class it replaces** so consumers need no import edit, and **reproduce the
  implementation's signature verbatim, return type included** — narrowing a return type to the
  tidier-looking one is a silent behaviour change. **A nested type is as pinned as the file declaring
  it**, so lift it to top level first.
- **Widening a proven port beats writing a private copy of it, because a port with measured edge cases
  is a specification.** A URL parser's interesting behaviour is in the mismatches against the platform
  type, and none of it survives being copied. **A port's width should follow its callers; a second
  caller is the signal to widen, not to fork. A new parser is new surface with no golden, and a port
  that exists has one. Look for the port before writing the replacement.**
- **A caller that already collapses every failure into one answer does not need a port to distinguish
  them; a caller that answers two different ways does.** Read each caller's body rather than
  inferring from sites sharing a name. **`null` means "this was never a request"; a throw still means
  "the request failed".**
- **A `null` and a `throw` are different answers, and `runCatching { }.getOrNull()` collapses them
  silently** — the transcription that came first would have told a signed-in user with a flaky network
  to sign in again. Keep `isFailure` and `getOrNull()` as **two** questions wherever the original
  answered them separately.
- **A wrapper's `if` decides whether the shared body runs at all**, so collapsing it into `?:`
  silently adds a fallback the original never had. **A null *from the body* is not the same event as a
  null *input*.**
- **A lock around two fields does not make the pair atomic** — a reader can take the new key and the
  old list, because its two reads sit either side of where the writer is mid-update. Hold the pair as
  one immutable value inside the critical section. **When a lock protects more than one field, ask
  what a reader sees halfway through a write; if the answer is "a mix of two states", the invariant is
  in the fields' relationship, not the lock.**
- **A cache with other readers must be shared, not moved in.** Count the readers before a KDoc reasons
  about ownership. **A cache is a thing you share.**
- **Shared constants are not the shared format, and the difference is the part nobody writes.**
  `SecretFormat` carried its constants in `commonMain` and both stores still *joined and split* the
  value themselves, identically and by eye. The fix is `SecretFormat.encode(ivBase64,
  ciphertextBase64)` / `decode(stored)`, taking **`String`s rather than byte arrays** because base64 is
  a platform concern — what is shared is the *shape*, and neither function touches base64. **A rule
  both implementations depend on and neither tests needs its own suite; it does not come free with the
  constant.**
- **A composite key built by string concatenation is a parser, and it is wrong on exactly the inputs
  the format cannot represent.** **A separator is a claim that the field cannot contain it, and
  nothing states that claim anywhere.** `("a", "b:c")` and `("a:b", "c")` collide, so the separator has
  to be the field boundary. **A key format is not visible in a signature** — expectations written from
  the parameter names rather than the format fail.

### 3. Tests

- **A decision no test can call is a decision no test can cover — name it in production.** A dozen
  `private` decisions became `internal`. **A test's own private re-implementation of a decision is
  worse than no test**: everything about it is correct and it proves the suite, not the code. **A
  private member is worse than a private function**, because `private` is a property of the class, not
  the file. Prefer lifting to a top-level `internal` in the same package — every existing call site
  then resolves to it with no edit, but **watch for a companion member shadowing the new top-level
  declaration**, since two strings that must agree then have nothing making them agree.
- **Which source set a test belongs in follows the source set of the code under test, and that is a
  fact to read rather than a preference to express.** An `androidMain` class cannot be tested from
  `commonTest` at all. **A suite that runs and a suite that could be written look identical in a build
  log, and the second is the one a reader counts as progress.**
- **A concrete class in a constructor blocks testing; an interface does not — but ask *whose*
  `commonTest` it blocks.** That wall is scoped to the module that owns the type, and a consumer
  outside it was never behind it. One parameter type changed from `CrispyBackendClient` to
  `BackendApi` turned an untestable service into a 12-case suite, with no behaviour change on the one
  wiring call site.
- **An exhaustive double's shape must be READ, never recalled, because completeness is the only
  property it has.** Port members were written from memory four times and rejected four times. **A
  narrow double gets away with being approximately right because nobody claims it is complete; an
  exhaustive one exists precisely because it is.** `overrides nothing` is the interface telling you the
  one thing the double exists to mirror — **open the file, don't adjust the declaration until it
  type-checks. A nullability difference in a `Map` value type is the detail that survives recall most
  often.** Make the double `open` and subclass it in the consumer rather than writing a second
  exhaustive one, and **never narrow a member's return type to `Nothing`** — no override can widen it.
- **A fixture that builds its own copy of the double the cases assert on passes for the wrong reason,
  and the symptom looks like a green suite** — the ViewModel read a map nobody wrote and every
  `assertEquals(emptyList(), …)` passed **because nothing was wired, not because a guard ran**. The fix
  is structural: make the double a constructor parameter so the disagreement is impossible to express.
  **A recording double passed by anything other than the object under test is a double nobody is
  watching.**
- **A fixture that asserts its own transformation, or defaults its expected value, is a vacuous
  assertion** — and a defaulted expected value agrees with every case it was *not* given to, which
  fails en masse rather than one at a time. **A value the author fills in from memory is a value about
  the code rather than about the case; in every such case the fixture was the defect and production was
  right.** Three variants worth naming: asserting a normaliser on an input it maps to itself proves
  the standard library; a value seeded into a store and asserted back may be read from a *different*
  source than the code reads (**which side a value came from decides what the assertion proves**, and
  a default is a value nobody measured); and a counter asserted against a pre-collected list proves
  nothing about ordering.
- **A near-miss fixture written from the SHAPE of its neighbours is a guess** — run the function and
  read its answer. **Two tables of expectations for one function must not be written from the same
  reading of the source**, and a case that contradicts another case in the same file is a defect in the
  fixture, not the code.
- **A test asserting a guard's *reason* needs the two answers to differ in exactly one respect.**
  Write down what each world would answer; if the strings are equal, the case is decoration.
- **A name is a claim about the body.** A name that excludes N while the body does not is the same
  defect twice — declare the excluded set once and have both tests read it, or the failure mode is a
  test that **skips** a case rather than one that fails. A name *wider* than its body is the defect the
  code is right about ("answers null" and "does not keep the entry" are two facts a contract-only suite
  cannot tell apart). **"Is every member of this group X?" is only worth asserting as a *set*
  comparison** — a hand-picked list is a sample, and a new member that quietly changed is not in it.
  Always **name the member in the failure message**, and remember a `Color(0xFF…)` literal is ARGB.
- **An assertion can describe a rule the code does not have, and the failure reads as a production
  bug.** Establish what the code's rule actually is by reading the body rather than inferring it from
  the method's name. **Read the early return**, because a path that returns early does not run the
  shared tail and a suite cannot tell an unexecuted tail from a wrong message.
- **The library that ships is not the library a JVM test can stand in for.** For `org.json`,
  `android-all` carries AOSP's `libcore/json` and `org.json:json` is a different implementation:
  `optString` of a `JSONObject.NULL` is `"null"` on AOSP and `""` on the reference, a fractional number
  is `Double` on AOSP and `BigDecimal` on the reference, and both agree on every whole number. **On a
  parsing boundary the substitute's answer is the one a JVM test reports.**
- **A mechanical port changes behaviour in *both* directions, so port a type by asking what its old
  members permitted, not what its new ones accept.** A lenient accessor feeding a strict parser is a
  **truncation, not a parse**; mutability is not preserved; and **`else -> this` is the arm that
  survives a port least often**, because it was right about the old type and wrong about the new one.
  Reproducing AOSP's `Number` widths is the port *plus* the behaviour, and only the last width is
  behaviour-preserving.
- **`org.junit.Assert.assertEquals` has no four-argument overload, and swapping expected for actual
  still compiles.** A three-argument call whose first argument was the *expected value* compiles, runs,
  and reports a failure that reads as a production bug. `:androidApp` tests use the JUnit order
  (message first), `commonTest` suites use `kotlin.test` (message last). **When a failure message quotes
  your own label text as the *actual*, the assertion is misordered.**
- **Read test XML with `ElementTree`, iterating `testcase` → `failure` as nested elements.** A greedy
  regex pairs a *passing* test's name with the next *failing* one's message.
- **Clear every module you will read, and read no wider than the tasks you ran** — a shorter `rm -rf`
  list turns a green suite into phantom failures, and `build/test-results/` is not cleared when the
  compile fails. **A failure list that does not move when the code under it changed means you are
  reading the previous run.**
- **A `commonTest` backtick name cannot contain a comma** — only a Native test compilation catches it,
  and `:app`'s Apple test compilation is not built on CI at all. A test filtered by name the task does
  not own fails as `BUILD FAILED` with no `e:` line, indistinguishable from a compile failure.

### 4. Coroutines in tests

- **A manual clock with a per-read `stepMs` is the only way to make a method's two readings of the
  same poll disagree.** A fixed clock makes them identical, so any assertion about the gap is vacuous.
- **`advanceUntilIdle()` drives the scope the test body runs in**, and `backgroundScope` is neither
  that scope nor a durable one. For a class that builds its own scope the working combination is
  `CoroutineScope(UnconfinedTestDispatcher())` with no `advanceUntilIdle`, and
  `UnconfinedTestDispatcher()` **must** be given `testScheduler` explicitly or `Main` delays never
  advance. **Give an injected scope a default of the old expression so production call sites are
  untouched** — that is the difference between a testability change and a refactor.
- **`viewModelScope` captures `Dispatchers.Main` at construction**, so `setMain` must be installed
  **before** the object under test exists, or the launch never runs and every assertion sees empty state.
- **`runCurrent()` runs what is already queued; it does not wake a coroutine parked in a `delay`.** And
  a test that fails before reaching a trailing `cancel()` never reports its failure — it hangs, with no
  XML. **A `SharedFlow` with no replay must be subscribed before the emission is triggered.**
- **A gate must hold exactly one call, or all of them,** to prove anything about concurrency.

### 5. Mutation drivers

There are fifteen `scripts/mutate_*.py` drivers. These rules are stated in each driver's docstring too,
because a driver runs unattended.

- **A survivor is the absence of a failure _after positive evidence the task ran_.**
  `subprocess.run(env=…)` **replaces** the environment, so `./gradlew` never starts — use
  `env = {**os.environ, …}` and require `BUILD SUCCESSFUL`/`BUILD FAILED` in the output. **When a
  driver reports every entry surviving at once, suspect the driver before the code.**
- **Read an entry's `old`/`new` as code, not its `name`/`why`** — prose can describe a mutation the patch
  does not implement, and the tell is a comment reasoning carefully about an outcome its code cannot
  produce. **Read every replacement as code: does it change an answer, and does it compile?** Nine
  shapes all look competent — a default *parameter*; a default on a **data-class constructor
  property** (the most deceptive, since the diff reads as a behaviour change); `?: return null` in an
  expression body; a `?: ""` arm on an `if/else` body; a repeated declaration prefix; `x?.y?.z().w()`
  (a `?.` chain covers only the next call); a rewrite *identical* to the original; renaming
  `runCatching` to `run`; appending a comment. **`COMPILE FAILED` is not evidence.**
- **Print the task's own failure lines once before trusting the regex that reads them**, and **never
  pass `-q`** — it suppresses those lines, so the verdict survives and the evidence does not. Truncate
  the log **per entry**; a superset of the `expect` set means the log is shared. **Never pass a method
  name to `--tests` — it names a class**, and a filter matching nothing fails as `BUILD FAILED` with no
  `e:` line, which a name-reading driver scores SURVIVED. **A test-filtered task only observes the
  source sets it compiles** — `:app:desktopTest` cannot see an `androidMain` mutation however extreme.
- **Pass `--no-build-cache` and guard on `> Task … (FROM-CACHE|UP-TO-DATE)` as text.** A `FROM-CACHE`
  task never compiles the mutated source and reports success in 1s — **a one-second green from a task
  you just perturbed is not a result.**
- **A caught mutation is evidence that _some_ test caught it, not that the one you would have pointed
  at did.** Read the driver's `(failures seen: …)` line before writing any code. **A guard can be masked
  by a neighbouring condition, and masking is indistinguishable from absence** — the discriminator is
  the input that reaches the guard you meant and fails only there.
- **A surviving mutation is a claim about the code, so check it by hand.** A survivor is either
  unreachable (keep it and write the measurement at the guard) or reachable and merely *unobservable* —
  and the answer to the second is **to widen the observation rather than delete the behaviour, then
  re-run the same mutation**. Padding the driver with `expect_survive` there would record a real leak as
  an accepted risk.
- **A set of names is derived by subtracting names, never values** — ten of `CrispyPalette`'s 37 roles
  share one value, so a value-based difference deleted the very roles under assertion and passed for
  the wrong reason. **A test that cannot cover one case asserts the set of uncovered cases. A port's
  implementation is the file most worth covering**, since it is what a `commonTest` cannot reach.
- **Mechanics.** Run a `check_anchors()` pre-flight and print `SKIP … anchor occurs Nx` — never count
  it as a pass. Restore in a `finally` with a printed `restored:` line, and print `of len(selected)` so
  a narrowed run cannot be mistaken for a full one. `Pattern.finditer(s, re.M)` does not set a flag —
  on a *compiled* pattern the second argument is `pos`, so `^` can never match again.

### 6. Editing safely

- **Never rewrite source with a regex.** It cannot see inside comments or string literals, and a
  "sound" cleanup rule once self-matched on its own package and deleted 140 live imports. For a
  mechanical change, let the compiler find the sites, or parse properly. **The same self-reference is
  reachable from a KDoc** — a `git grep -- '*/src/commonMain'` pathspec written inside a block comment
  closes it, because the `*/` **is** a terminator and everything after it parses as Kotlin. **A command
  quoted into a comment is code, and `*/` is in every glob.**
- **Any script that rewrites source must compute every new file's content before it opens any of them
  for writing, assert each anchor, and read the region back afterwards.**
  - **`open(path, "w")` truncates before its argument is evaluated**, so computing content *inside* the
    write call destroys the file before the assertion fires. Compute into a dict and write at the end.
  - **Replacements must be applied cumulatively** — computing two from the same original and writing
    both loses the first, and every uniqueness assertion still passes.
  - **Count assertions in the ORIGINAL text.** An anchor a *previous* edit introduces is 0x by
    construction; an anchor a *later* edit introduces does not exist yet — phase 1 will abort on a
    perfectly correct patch. Fold such edits into one. **A no-op edit is a landmine**, because it
    consumes the anchor's uniqueness for every other edit that wants it.
  - **A count assertion checks a word, not a declaration** — scope it to the declaration form
    (`count("interface X {") == 1`), because a supertype reference is a second legitimate occurrence.
  - **A structural slice must preserve comment delimiters**, and only `diff` sees a four-space shift.
    **A syntax-error cascade is one missing delimiter**: resolve the distinct *names* in it before
    reading any of it, and `grep -c '^/\*\*'` against `grep -c '^ \*/'` answers the question without
    reading an error.
  - **The read-back condition is derived, not remembered: `old not in new`**, asserted only when the
    replacement genuinely removed it. A new string that contains its own old text is legitimate, and
    **`count == 1` on an empty string always fails.** A read-back whose sign is inverted reports a
    correct write as broken.
  - **A "must be gone" guard has three ways to be wrong**: it fires on prose the new comment wrote on
    purpose (strip `/** */` and `//` before asserting), on a *substring* of the legal replacement (use
    `\b`), and on a declaration that was supposed to survive.
  - **A guard that quotes only the FIRST matching line of its finding will hide a real leftover** —
    print every hit with its line number before deciding what kind of occurrence it is.
- **Deleting a construct does not make an adjacent import dead, and the errors name none of the real
  cause.** Moving three `remember(appContext)` calls out deleted `getValue` alongside `remember`, and
  `getValue` is the `by`-delegate operator — it dies with the `by`, not with the `remember`. Three
  errors, one cause, on an import filed under an unrelated construct. **Ask "what syntax needs this
  name", not "what block did I delete next to it".**
- **A hand-written "superset" import list is not a superset, so derive a split file's imports from the
  original's mechanically** — for each import, ask whether the *moved body* names its simple name. This
  converges in one compile instead of five.
- **Turning a POSITIONAL call into named arguments is a claim about the signature, and a wrong claim
  type-checks.** **The dangerous shape is the one that renames cleanly, because the edit and the
  behaviour change are the same edit.** Read the declaration's parameter names before naming anything,
  and note that **a name-only grep picks the wrong declaration** when two exist in different modules.
- **A `public` function cannot expose an `internal` type, and the fix is to narrow the function, not
  widen the type.** **A visibility error is a report about the module's convention** — read the
  siblings, not the single declaration the compiler named.
- **Inserting a declaration between an annotation and its target silently re-targets the annotation**,
  and the main compile will not catch it. It surfaces in the *test* compilation as "must be marked
  @Composable" plus an error at every call site — **when every error is that, the annotation is
  misplaced, not the calls.** **Extracting a decision out of a `val` can also destroy a smart cast the
  branch depended on.** **Structural line-range edits must precede string replacements earlier in the
  same file.** **A file's first line is not its `package` line** — a `@file:OptIn` may come first.
- **`git mv` preserves mtime**, so incremental Kotlin compile can skip a moved file and report success
  with no class produced. Compile a moved file once with `--rerun-tasks`.
- **A revert that only compiles the source set you moved *from* proves nothing**, and a partial revert
  looks exactly like a completed one. **After any revert, run `git status --short` and look for `D ` in
  the index, and compile *both* source sets.** `git restore --staged --worktree <path>` is the reliable
  restore. **The index is not a backup of your work — `git checkout --` is the wrong tool for undoing a
  mutation**, because it restores your last commit rather than your last edit.
- **One gate per tree, and a unique log path per run.** Two runs redirecting to one file interleave,
  ending in `BUILD FAILED` with **zero `e:` lines, no `What went wrong`, and mangled task lines**.
  **A `BUILD FAILED` with no `e:` line and no `What went wrong` is task selection or harness noise, not
  compilation — re-run before diagnosing.**

## Compose resources

The 100 design drawables, 12 genre rasters, the Archivo font, and `CrispyBrand`/`CrispyIntroSplash`
live in `:android:sharedUI` as `composeResources`. `:ui-assets` keeps only what CMP cannot carry.

- **Every accessor needs its own import.** `Res.drawable.ic_search` is a *top-level extension
  property*, so importing `Res` alone resolves `Res` and `Res.drawable` while every accessor reads
  `Unresolved reference`.
- **`publicResClass = true` is the supported switch**, set alongside
  `packageOfResClass = "com.crispy.tv.ui.resources"` — the default leaks the Gradle coordinates into
  every import. Do **not** `doLast`-rewrite the generated `internal object Res`.
- **`org.jetbrains.compose.resources.painterResource` replaces `androidx.compose.ui.res.painterResource`
  wherever a drawable is involved** — the Android one takes an `Int`, the CMP one a `DrawableResource`.
  Same for `Font`, and **`Font(FontResource)` is `@Composable`**, so a top-level `FontFamily` needs a
  composable property getter. **Deciding which import a file needs is not a textual problem, so let the
  compiler decide** — a file can receive a `DrawableResource` as a parameter and never name `Res.`.

**What stayed in `:ui-assets`, and why — do not "finish" the move by dragging these across:**

| Asset | Why it cannot be `composeResources` |
|---|---|
| 25 launcher mipmaps | named by two `AndroidManifest.xml` files and a notification icon; a manifest names an Android resource, never a composeResources one. Each app owns its own icon. |
| `splash_mark`, `splash_mark_animated` | named by `windowSplashScreenAnimatedIcon` in the `:androidApp` and `:tv` themes. An XML theme can only name an Android drawable. |
| `splash_background` colour | `windowSplashScreenBackground` in the same themes. |
| 9 provider-logo SVGs, under `res/raw/` | **CMP documents SVG as "supported on all platforms except Android"**, so `painterResource` would fail silently on the platform that matters most. These are `@RawRes Int?` passed to Coil. The portable route is `composeResources/files` + `Res.readBytes(path)`, but Coil then needs an SVG decoder. **An open design decision with product consequences, not a rename.** |

**A Kotlin-only reference count is a count**: `ic_star` was deleted for zero Kotlin references, but
`splash_mark_animated` is referenced from XML, which that count cannot see.

## GitHub Actions (`.github/workflows/`), all `workflow_dispatch`-only by deliberate choice:

- `android.yml` — verification for the app (both flavors) and the TV app.
- `apple.yml` — the only place Apple can be verified: compiles the KMP Apple targets, runs their
  simulator tests, the Swift contract suite, and the iOS/tvOS Xcode gate.
- `android-release.yml` — signed release artefacts; separate because it alone needs the signing secrets.
- `apple-release.yml` — unsigned sideloading IPA.
- **`kotlin.srcDir(...)` must be given a TaskProvider, never a directory.** `layout.buildDirectory.dir(…)`
  is a `Provider<Directory>` carrying no task information, so nothing orders the generating task before
  compilation: the task silently never runs and the build only succeeds if a *previous* run left the
  files behind. Use `kotlin.srcDir(generateAppConfig)` so the task's outputs are the source dir. This
  bit `generateAppConfig`, so `AppConfig` was unresolved on every clean machine and fine locally for
  weeks.
- **Workflow YAML must parse with no duplicate keys** (`validate_workflows.py`, run first in
  `check-local.sh` and as the first step of every workflow). A duplicate key makes a workflow fail to
  *load*, producing a run that fails in under a second with an empty log — the symptom reads as "the
  tests failed" when no test ever ran, and nothing inside the workflow can report it. `yaml.safe_load`
  takes the last value for a repeated key and reports nothing, so the validator loads through a
  constructor that raises on the second occurrence, at any depth.
- **`verify_kmp_outputs.py` has one naming subtlety of its own** (the rest is Rules §1): it must know how
  Kotlin names a file facade when the file name contains a dot — the compose-resources plugin names its
  accessors `Drawable0.commonMain.kt`, whose facade is `Drawable0_commonMainKt`, and using the stem
  verbatim reports every migrated resource as an orphan. **An `actual typealias` is the shape to check
  whenever an expect/actual pair lands**, being the one declaration whose facade exists on one target
  and not on another.
- **`verify_apk_distribution.py` reads the dex** and is only valid on unminified builds, so it runs in
  `android.yml` (debug) and not in `android-release.yml`. Release asserts via
  `verifyDistributionExclusions`, which reads the dependency graph and is minification-proof.
- **Flavors live only in `:androidApp`, and that is not negotiable.** AGP's
  `com.android.kotlin.multiplatform.library` has **no** `productFlavors` at all, and a KMP library
  cannot *consume* a flavored `com.android.library` — the library plugin is single-variant, states no
  preference between `store*` and `sideload*`, and Gradle fails with an ambiguous-variant error naming
  every candidate. Do not reintroduce `matchingFallbacks` to paper over this: it would compile `:app`
  against the store variant while a sideload APK shipped the sideload one.
- Distribution is a permanent two-flavor axis: `store` and `sideload`. Optional engines are excluded
  **structurally**. Never reintroduce a null-returning stub for something the store build should simply
  not contain.
- Apple targets cannot compile on Linux. **Never run aggregate tasks** (`build`, `check`, `allTests`) —
  they reach the Kotlin/Native targets and fail. **A dispatched run has to be *read*: 204 means
  accepted, not green.**

## Configuration / Secrets

- Android app reads Gradle properties and injects them into `BuildConfig`; in CI these come from `ORG_GRADLE_PROJECT_*`.
- Do not commit secrets; use `~/.gradle/gradle.properties` for Trakt/Simkl ids+redirect URIs, `SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY`, and `CRISPY_BACKEND_URL`.
- Set repository secret `CRISPY_BACKEND_URL` so CI builds embed the backend base URL in `BuildConfig.CRISPY_BACKEND_URL`.
- Signing: release uses `RELEASE_KEYSTORE_*` if present; otherwise debug signing. Debug can be overridden via `DEBUG_KEYSTORE_*`.
- Daemon memory/lifetime is tuned in `~/.gradle/gradle.properties`, which overrides this repo's `org.gradle.jvmargs`; that is deliberate, so leave the committed `-Xmx4g` alone.

## Code Style

- Contracts drive behavior. If behavior changes, update `contracts/SPEC.md`, fixtures/schemas, Kotlin
  contract tests, and Swift ContractRunner — bumping `contract_version` per SPEC and keeping Kotlin and
  Swift in lockstep. Suites: `player_machine`, `continue_watching`, `sync_planner`, `storage_v1`,
  `player_progress`.
- Determinism: pass `nowMs`/clock; inject seeded RNG; keep output ordering canonical. No
  `System.currentTimeMillis()` in domain; no iteration over unordered maps where order matters. When
  enriching or merging metadata, keep precedence stable (server-first, fill missing only).
- Gradle repos: `settings.gradle.kts` enforces `repositoriesMode = FAIL_ON_PROJECT_REPOS`; do not add
  repos in a module `build.gradle.kts`.
- KISS, DRY, YAGNI. Refactor existing logic instead of layering on top; remove obsolete or commented-out
  code; keep functions to one responsibility; avoid premature abstractions; prefer self-documenting names
  over comments; reuse existing utilities before creating new ones.
- Kotlin: official style, 4-space indent, **no wildcard imports**, grouped stdlib → Android/AndroidX →
  third-party → internal. Use nullability for optional values; normalize early (trim, treat blank as
  missing). `UpperCamelCase` types, `lowerCamelCase` functions, `SCREAMING_SNAKE_CASE` consts. Model
  actions/events as `sealed interface` + `data class`/`data object`, with explicit mapping helpers for
  contract string values. Keep pure rules in `:core-domain` (no Android types/IO); immutable state +
  reducers where they fit. **No exceptions for normal control flow in domain** — return explicit
  results, and preserve coroutine cancellation (don't swallow `CancellationException`).
- Swift: small explicit APIs, prefer `struct`; imports minimal, `Foundation` first; `guard` +
  descriptive thrown errors, no force unwraps; never read system time directly.
- Python tooling: hermetic and deterministic; non-zero exit on failure; errors include the fixture path
  and JSON location.

## Single-change checklist

- `python3 scripts/validate_contracts.py`
- `python3 scripts/validate_workflows.py`
- `./gradlew :android:core-domain:desktopTest :android:core-domain:testAndroidHostTest`
- `./gradlew :android:app:testAndroidHostTest` (the composition root; the gate before touching `PlaybackDependencies`, `AppDistribution`, `AppServices` or `AppGraph`)
- `./gradlew :android:app:compileCommonMainKotlinMetadata` (`:app`'s `commonMain` against the **metadata** variant of every dependency, so it is the only task that catches a *JVM-only* symbol in `commonMain`; `compileKotlinDesktop` and `compileAndroidMain` each miss one.)
- `./gradlew :android:app:desktopTest` (`:app`'s `commonTest`)
- `./gradlew :android:desktopApp:compileKotlin` (after any `:app` `commonMain` change — the task that proves it still renders off-Android)
- `./gradlew :android:backend:desktopTest` (the gate before changing `BackendApi` or `AccountApi`)
- `python3 scripts/verify_kmp_outputs.py` (after any compile; catches a stale class a green build cannot)
- `swift test --package-path ios/ContractRunner` (if Swift logic touched)
- Ensure `:android:tv` and tvOS placeholder builds still compile