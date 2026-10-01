# Agent Guide (Goal, Build, Test, Style)

## Goal

**This project converts the Crispy TV Android app to Kotlin Multiplatform, so that one
codebase runs on Android, Android TV, iOS and desktop (Windows, macOS, Linux).** Android
and TV ship today; the other two targets are the point of the work.

**Read `kmp-migration-plan.md` §0 before choosing a landing.** It is the source of truth
for the goal, the non-negotiables (production grade, nothing deferred, no compatibility
scaffolding — refactor rather than layer) and the phase map. This file carries only what
an agent needs *while working*, and deliberately does not restate the plan: a second copy
of a source of truth drifts from it, and that has happened once already.

The other three root planning documents — `TAKEOUT.md`, `phase1-split-plan.md`,
`phase2-data-layer-plan.md` — are **deliberately untracked** and are not part of the
project record. Do not commit them, and do not point anything tracked at them.

Two things worth knowing that a reader would otherwise have to rediscover:

- **The plan is not measured status.** Its per-module counts go stale between landings.
  Re-measure rather than trust them, and check a count against git, not against prose:
  ```sh
  find android/app/src/commonMain -name '*.kt' | wc -l
  git ls-files android/app/src/commonMain | grep -c '\.kt$'
  git ls-files android/app/src/androidMain | grep -c '\.kt$'
  ```
  The two `commonMain` commands must agree. A count that came from one command is a
  claim, not a measurement. **Refresh §1's counts in the same commit as any landing that
  moves files** — the plan is now tracked, so its only verified section being wrong is
  visible to every reader.
- **A `commonMain` file is worth nothing until a non-Android target consumes it.** A
  file count is therefore not progress on its own; ask what runs it. The `apple.yml` and
  `:android:desktopApp` entries under *Project Layout* are where that gets answered.

Also orthogonal to the goal but binding on every change: Android and Swift must stay
aligned with `contracts/SPEC.md` (`android/core-domain` and `ios/ContractRunner`).

Repo agent rules:
- No `.cursor/rules/` or `.cursorrules` found.
- No `.github/copilot-instructions.md` found.

## Toolchain (match CI)

- JDK 21 **everywhere**, including `jvmToolchain` in `:android:core-domain` and `:android:app`. Do not reintroduce a 17 toolchain: neither module is published and every consumer is already compiling at 21, so 17 bytecode bought nothing while making the build depend on a JDK CI does not install.
- Android SDK `platforms;android-37.0` + `build-tools;36.0.0`
- Gradle 9.7.1 via the committed wrapper: always use `./gradlew`, never a bare `gradle` (it is not installed, and a different Gradle version starts a second daemon that nothing reclaims)
- Python 3.12 + `jsonschema==4.23.0`
- Xcode + `xcodegen`; Swift tools 5.9

## Commands

Contracts (fast):
```sh
python3 -m pip install jsonschema==4.23.0
python3 scripts/validate_contracts.py
./gradlew :android:core-domain:desktopTest :android:core-domain:testAndroidHostTest
swift test --package-path ios/ContractRunner
```

Other useful tasks:
```sh
# JVM unit tests (if present)
./gradlew :android:core-domain:test
./gradlew :android:app:testAndroidHostTest
./gradlew :android:home:desktopTest
./gradlew :android:androidApp:testStoreDebugUnitTest

# Clean
./gradlew clean
```

Single test (important):
```sh
# Contract suite (all 96 fixtures). It is one suite compiled for every target, so
# a single test filter is applied to the target you want:
./gradlew :android:core-domain:desktopTest --tests 'com.crispy.tv.contracts.PlayerMachineContractTest'
./gradlew :android:core-domain:desktopTest --tests 'com.crispy.tv.contracts.PlayerMachineContractTest.playerMachineFixtures'
./gradlew :android:core-domain:testAndroidHostTest --tests 'com.crispy.tv.contracts.PlayerMachineContractTest'

# The composition root, in :app's androidHostTest compilation:
./gradlew :android:app:testAndroidHostTest --tests 'com.crispy.tv.distribution.AppDistributionTest'
./gradlew :android:app:testAndroidHostTest --tests 'com.crispy.tv.PlaybackDependenciesTest'

# The settings repositories, in :app's commonTest compilation:
./gradlew :android:app:desktopTest --tests 'com.crispy.tv.settings.KeyValueStorePlaybackSettingsRepositoryTest'
./gradlew :android:app:desktopTest --tests 'com.crispy.tv.settings.KeyValueStoreImageSettingsRepositoryTest'

# The portable date formatting -- :app's fallback policy, :core-domain's formatter:
./gradlew :android:app:desktopTest --tests 'com.crispy.tv.details.FormatLongDateTest'
./gradlew :android:app:desktopTest --tests 'com.crispy.tv.details.DetailsHeaderSubtextTest'
./gradlew :android:app:desktopTest --tests 'com.crispy.tv.library.LibraryMonthKeyTest'
./gradlew :android:app:desktopTest --tests 'com.crispy.tv.playerui.EpisodeMetaTest'
./gradlew :android:app:desktopTest --tests 'com.crispy.tv.playerui.PlayerInfoSheetTest'
./gradlew :android:app:desktopTest --tests 'com.crispy.tv.playerui.PlayerGesturesTest'
./gradlew :android:app:desktopTest --tests 'com.crispy.tv.playerui.PlayerEpisodesSheetTest'
# The AndroidPlayerGestureController implementation -- :app's only test task that compiles androidMain:
./gradlew :android:app:testAndroidHostTest --tests 'com.crispy.tv.playerui.AndroidPlayerGestureControllerTest'
./gradlew :android:app:desktopTest --tests 'com.crispy.tv.playerui.LanguageLabelsTest'
./gradlew :android:core-domain:desktopTest --tests 'com.crispy.tv.domain.watch.CivilMonthKeyTest'
./gradlew :android:core-domain:desktopTest --tests 'com.crispy.tv.domain.watch.FormatIso8601LongDateTest'
./gradlew :android:app:desktopTest --tests 'com.crispy.tv.details.ReviewProviderMatchingTest'
./gradlew :android:app:desktopTest --tests 'com.crispy.tv.playerui.SubtitleRepositoryTest'
./gradlew :android:app:desktopTest --tests 'com.crispy.tv.discover.BackendBrowseRepositoryTest'
./gradlew :android:app:desktopTest --tests 'com.crispy.tv.library.LibraryPagingSourceTest'

# :backend's common-domain tests (BackendContextResolver)
./gradlew :android:backend:desktopTest --tests 'com.crispy.tv.backend.BackendContextResolverTest'
./gradlew :android:app:desktopTest --tests 'com.crispy.tv.accounts.SyncProviderRepositoryTest'
./gradlew :android:app:desktopTest --tests 'com.crispy.tv.accounts.AppBootstrapViewModelTest'
./gradlew :android:app:desktopTest --tests 'com.crispy.tv.search.SearchViewModelTest'
./gradlew :android:app:desktopTest --tests 'com.crispy.tv.person.PersonDetailsViewModelTest'
./gradlew :android:app:desktopTest --tests 'com.crispy.tv.home.HomeSelectorViewModelTest'
./gradlew :android:app:desktopTest --tests 'com.crispy.tv.optimistic.UserMutationOutboxTest'
./gradlew :android:app:desktopTest --tests 'com.crispy.tv.details.DetailsUseCasesTest'
./gradlew :android:app:desktopTest --tests 'com.crispy.tv.details.DetailsViewModelTest'
./gradlew :android:app:desktopTest --tests 'com.crispy.tv.details.DetailsSeedColorCacheTest'
./gradlew :android:app:desktopTest --tests 'com.crispy.tv.accounts.AccountViewModelsTest'
./gradlew :android:app:desktopTest --tests 'com.crispy.tv.accounts.ProfileMenuRouteTest'
./gradlew :android:app:desktopTest --tests 'com.crispy.tv.search.SearchScreenTest'
./gradlew :android:app:desktopTest --tests 'com.crispy.tv.details.DetailsRatingsSectionTest'
./gradlew :android:app:desktopTest --tests 'com.crispy.tv.home.HomeViewModelTest'
./gradlew :android:app:desktopTest --tests 'com.crispy.tv.accounts.PendingProviderAuthStoreTest'

# :home's moved services. Same filter works on either target.
./gradlew :android:home:desktopTest --tests 'com.crispy.tv.home.CalendarServiceTest'
./gradlew :android:home:testAndroidHostTest --tests 'com.crispy.tv.home.UpNextServiceTest'

# A single golden (verify by default, add -Proborazzi.record=true to re-record):
./gradlew :android:androidApp:testStoreDebugUnitTest --tests 'com.crispy.tv.screenshot.LandscapeCardScreenshotTest'

# SwiftPM
swift test --package-path ios/ContractRunner --filter ContinueWatchingContractTests
swift test --package-path ios/ContractRunner --filter ContinueWatchingContractTests.testSomeCaseName
```

Android builds/lint:
```sh
./gradlew :android:androidApp:assembleStoreDebug :android:androidApp:assembleSideloadDebug :android:tv:assembleDebug
./gradlew :android:androidApp:assembleStoreRelease :android:androidApp:assembleSideloadRelease :android:tv:assembleRelease
./gradlew :android:androidApp:lintStoreDebug :android:androidApp:lintSideloadDebug
./gradlew :android:tv:lintDebug
```

Apple placeholder compile gate:
```sh
xcodegen generate --spec ios/project.yml
xcodebuild -scheme CrispyRewriteiOS -destination 'platform=iOS Simulator,name=iPhone 16' build
xcodebuild -scheme CrispyRewritetvOS -destination 'platform=tvOS Simulator,name=Apple TV' build
```

Optional native deps (CI parity):
```sh
bash .github/scripts/fetch-torrserver-binaries.sh
```

## Project Layout

Gradle modules (common targets):
- `:android:androidApp`: the Android entry point (`com.android.application`) — manifest, `res/` that only the application needs, signing, ProGuard, ABI splits, the `store`/`sideload` flavours, the golden-screenshot tests
- `:android:app`: the shared UI and presentation layer, a Kotlin Multiplatform library. Its `commonMain` now holds the design-agnostic UI (routes, `ui/components`, `ui/navigation`, `ui/edge_to_edge`, `ui/utils`, the seek/gesture feedback surfaces) and `androidMain` holds the rest; `commonMain` is a strict subset of the Android build, so the phone app and the desktop app compile the same files. Its `androidHostTest` source set (see *Composition-root tests* below) is where the composition root is pinned.
  - **`CrispySharedTransitionLayout` is the shared host, and it is in `commonMain` because the
    mechanism is not navigation.** 14 `commonMain` files participate in a shared-element
    transition by reading `LocalSharedTransitionScope` (a `staticCompositionLocalOf<SharedTransitionScope?> { null }`
    in `ui/navigation/LocalSharedTransitionScopes.kt`, also in `commonMain`). Until recently the
    only provider was two lines inside `AppNavHost.kt`, so on any non-Android target all 14 read
    `null` and rendered with **no transition and no error**. `CrispySharedTransitionLayout`
    supplies the scope from `androidx.compose.animation.SharedTransitionLayout` -- Compose
    Multiplatform, on every target -- and is called by `AppNavHost` and by `desktopApp`. Its
    `content` slot has **no default**, so a caller cannot obtain a provider that provides
    nothing. `AppNavHost.kt` is still `androidMain` and still four lines shorter: it is genuinely
    `NavHost`-bound, and **the five `*NavGraph.kt` files remain Phase 5's problem** because they
    declare `NavGraphBuilder` graphs and `androidx.navigation` has no KMP artifact at all.
- `:android:sharedUI`: the design system **and the design assets** (Phase 4 Step 1) — `composeResources`, the theme tokens, the brand composables
- `:android:ui-assets`: Android-only assets that cannot be `composeResources` — launcher mipmaps, the splash colour and its two drawables, the nine provider-logo SVGs
- `:android:youtube-extractor`: sideload-only YouTube stream extraction (NewPipeExtractor)
- `:android:tv`: Android TV placeholder app (must compile)
- `:android:core-domain`: pure domain rules (no Android types/IO), **and** the contract suite in `commonTest` (see below)
- `:android:player`, `:android:network`, `:android:watchhistory`, `:android:native-engine`: Android libraries
- `:android:torrent-engine`: sideload-only torrent engine. `:android:native-engine` holds the MPV/Media3 player and nothing else optional.

Golden screenshots (`:android:androidApp:testStoreDebugUnitTest`):
- Robolectric + Roborazzi, running on a plain JVM. No emulator, no KVM, no `androidTest` device. This is the only rendering coverage in the repository.
- **Verify is the default**; re-record with `./gradlew :android:androidApp:testStoreDebugUnitTest -Proborazzi.record=true`. Goldens are committed under `android/androidApp/src/test/screenshots/`, so a missing golden fails the build.
- The tests live in `:androidApp`, not `:app`, and that is not arbitrary: they need `isIncludeAndroidResources = true` to inflate the real app theme, which only an application module has. They still render composables that live in `:app`.
- The Roborazzi Gradle plugin is deliberately NOT applied: 1.43.1 fails against AGP 9.3 with `Extension of type 'TestedExtension' does not exist`. Record/verify is driven by the `-Proborazzi.*` properties in `android/androidApp/build.gradle.kts` instead.
- Screenshot tests must set `application = ScreenshotTestApplication::class`. Robolectric otherwise boots `CrispyApplication`, whose `onCreate` reaches an `AndroidKeyStore` that cannot exist on a JVM.
- Freeze the Compose clock (`mainClock.autoAdvance = false`) or animated content never matches.
- **Host prerequisite:** a *failing* screenshot needs a host font, because Roborazzi labels the diff with Java2D. On a host with no font stack the failure reports `Fontconfig head is null` instead of the real difference. It still fails the build, but the diff is unreadable. `test-fonts/` at the repository root bundles Roboto and a generated fontconfig, which covers hosts that have libfontconfig but no fonts; a host with **no** `libfontconfig.so.1` at all needs the library supplied some other way, and root is not required: `dnf download --resolve --alldeps mesa-libGL libX11 fontconfig` then extract each rpm with `rpm2cpio | cpio -idm` into a scratch directory and point `LD_LIBRARY_PATH` at it. That is how the bare Fedora container this was last verified on runs both rendering suites. Three traps in that sequence, all of which produce a failure that looks like a code regression and is not:
  - `dnf download` fetches **both** `i686` and `x86_64` packages, and merging them puts a 32-bit `libfontconfig.so.1` on the path. Skiko then dies with `UnsatisfiedLinkError: libfontconfig.so.1: wrong ELF class: ELFCLASS32`, surfacing as `ExceptionInInitializerError` from `SkiaGraphicsContext` and then as four `NoClassDefFoundError: RenderNodeContext` — which reads as "the desktop seam proof is broken" and is not. Extract only `*x86_64*.rpm` and check the ELF class before trusting the result.
  - `cpio -idm` applies the payload's directory modes, so a single shared target directory makes the *second* rpm fail on a read-only directory — stage each one separately and merge. A `cp -a` that fails this way is silent under `2>/dev/null`, so verify the three critical libraries are actually present afterwards rather than assuming the loop worked.
  - `rm -rf` on the previous merge fails on the same read-only directories, leaving stale libraries behind. Build a fresh directory and check the merged output for 32-bit artefacts.

Composition-root tests (`:android:app:testAndroidHostTest`):
- `:app` is a KMP library, so this is its **only** test compilation. It exists because of `withHostTest {}` in `android { }`; without that block `:app` has no way to be tested at all. `desktopTest` sees only `commonMain` + `appUi`, and everything this covers — `AppDistribution`, `PlaybackDependencies`, `BackendServicesProvider`, `SupabaseServicesProvider` — is in `androidMain`. Do not reach for the aggregate `check` task to find the name; `testAndroidHostTest` is it.
- **The source set is not on the `sourceSets` container.** `androidHostTest.dependencies { }` does not resolve; only `getByName("androidHostTest").dependencies { }` does. The task names (`testAndroidHostTest`, `compileAndroidHostTest`, `assembleAndroidHostTest`) exist regardless.
- Robolectric is here **for a `Context` and nothing else**. No view is inflated and no resource is read, so `@Config(manifest = Config.NONE)` is enough and `isIncludeAndroidResources` is unnecessary — which is exactly why these can live in `:app` while the goldens must stay in `:androidApp`. Robolectric is the repo's existing dependency, not a new one.
- **Pin `sdk = [35]` on every one of these classes.** With `Config.NONE` there is no manifest, so Robolectric cannot read `targetSdk` and silently falls back to **SDK 21**. That is below the app's `minSdk` of 26, so a class added after API 21 (`AudioFocusRequest`, API 26) is absent from `android-all`, the classloader falls through to AGP's mockable `android.jar`, and the failure reads `Method setAudioAttributes in android.media.AudioFocusRequest$Builder not mocked` — a message that names a real app class and blames nothing about Robolectric.
- **The Robolectric sandbox classloader is shared across every test class with the same `@Config` in one worker JVM.** It is not per class, and this is the opposite of what the isolation argument usually claims. Any test about a process-wide singleton therefore sees every other class's mutations: a test instance is rebuilt per method, so an object a test installs has to live in a `companion object` or a second method is comparing against a different object than the one installed; and a lazily cached instance populated by another class makes an assertion pass without the code under test having run. `:app`'s `AppDistributionTest` reads `torrentResolverFactory` rather than `getTorrentResolver` for that reason, and the comment says so.
- JUnit's `@FixMethodOrder(MethodSorters.NAME_ASCENDING)` is what makes an ordered singleton lifecycle readable; the alternative is a reset hook on production code that exists only for tests.
- **`SecureTokenStore` is untestable on a JVM**, so everything that reaches it is untestable: `SupabaseServicesProvider.secureTokenStore` and `accountClient` directly, and `homeCatalogService` indirectly through `BackendContextResolverProvider.get`. The failure is `KeyStoreException: AndroidKeyStore not found` raised in a constructor, before any assertion. A fake keystore would prove nothing about the real one, so leave them out rather than mocking around it.

Common-domain tests (`commonTest`; `:android:backend:desktopTest`, `:android:app:desktopTest`):
- A `commonTest` in a module that had **no test source set at all** is the cheapest possible guard for a file that just moved to `commonMain`, and it is deliberately *not* an `androidHostTest`: a test that only ran on Android would not notice the file reaching for a JVM API again. Same reasoning as the `linuxX64` gate, applied to tests.
- **`commonTest` and `androidHostTest` are complementary halves of one module, and one step of CI is not enough to cover both.** `commonTest` cannot see androidMain; `androidHostTest` cannot see what commonMain alone compiles. `:app` runs both (`desktopTest` and `testAndroidHostTest`) and they cover disjoint files — the settings repositories and the composition root respectively.
- **`commonTest` without `withHostTest {}` runs on no Android target, and AGP only *warns*.** The message is `The 'commonTest' source directory exists, but android host tests are not enabled`, which reads like a build-file nit and is actually the tests not running where the module ships. `:backend` had exactly this. `:backend`, `:app` and `:core-domain` now all declare both blocks; a fourth `commonTest` must add `withHostTest {}` in the same change.
- `BackendContextResolverTest` drives `CachingBackendContextResolver` through `AccountApi` / `BackendApi` fakes rather than through its provider, precisely because the provider reaches the keystore.
- `SyncProviderRepositoryTest` and `PendingProviderAuthStoreTest` (`:app`'s `commonTest`) cover the two moved account declarations that have behaviour of their own. `ProfileRepository` and `AccountSettingsRepository` are deliberately untested: they are delegation, and a test of delegation re-asserts the compiler.
- `UnusedBackendApi` implements all 52 members and throws. **It exists to be broken by the compiler**: adding a `BackendApi` member fails to compile here until a fake decides what that member means. Prefer this to a mock framework that would silently absorb a new member as a no-op.
- **Prove a new suite is not vacuous before trusting it, and treat "no test failed" as a finding rather than a pass.** Six deliberate breakages caught five. The sixth is the interesting one: deleting **only the fast-path cache read outside the `Mutex`** changed nothing observable, because the in-mutex check re-reads the same cache and the store is untouched either way. Both checks have to go before the caching assertions fail. A single-check test cannot see a redundant check being removed.
- **A test that samples the cases instead of enumerating them tests the sample.** The settings suite's first "a setter that changes nothing writes nothing" case covered five of twelve identical setters, and deleting the guard from the other seven failed nothing. All twelve are called now. Ask whether a test proves the *rule* or an *instance* of it, and prefer the loop over the hand-picked list.
- **A class that builds its own `CoroutineScope` on a real dispatcher is untestable until the scope is injected, and `Dispatchers.setMain` is not the answer.** `SubtitleRepository` built `CoroutineScope(SupervisorJob() + Dispatchers.IO)` internally; `setMain` replaces `Dispatchers.Main` and the class never used it. Four strategies were measured before one worked: `setMain` + `advanceUntilIdle` (8 of 9 tests failed, the launch never ran), `runTest`'s `backgroundScope` (identical 8 failures — it is not on the scheduler `advanceUntilIdle` drives), an explicit `TestScope(StandardTestDispatcher(scheduler))` (identical 8 failures — `TestScope` appears to build its own scheduler rather than adopt the one passed to the dispatcher), and finally **`CoroutineScope(UnconfinedTestDispatcher())` with no `advanceUntilIdle` at all**, which works because unconfined runs the body eagerly on the calling thread. Give the injected scope a **default** of the old expression so production call sites are untouched — that is the difference between a testability change and a refactor.
- **A patch whose anchor assertion failed did not apply, and the test run after it measured the previous file.** The third dispatcher attempt was not a fourth strategy failing: the python patch had failed its own exactly-once assertion because `scope.advanceUntilIdle()` occurred 14 times, 9 call sites plus 5 inside the KDoc. **When a patch fails, do not re-run the tests as if it had applied**, and when N different strategies produce byte-identical output, suspect the file did not change before suspecting the strategies.
- **A renamed type reaches plain-Android modules that will never migrate.** `android/tv` is a `com.android.application` that stays on the Android source set forever, but it constructs `StreamResolver` itself, so renaming the class to `CachingStreamResolver` needed an import edit there too. Grep for the *type name* across every module before renaming anything a shared module constructs.
- **A test failure and a compilation failure look identical if you only read the exit code** — and the test results on disk are the *previous* run's, so the wrong evidence gets reported. Always run `compileTestKotlinDesktop` first and read the XML only if that succeeded. This bit me once mid-session and produced a confidently wrong mutation result.
- **A `commonMain` class that three repositories depend on must be an interface, or those repositories cannot be tested at all.** `BackendContextResolver` was a final class in `commonMain`; a KMP class is final by default, so `SyncProviderRepository` and its two siblings were *unconstructible* in a test — not hard to test, impossible. The fix is `interface BackendContextResolver` plus `CachingBackendContextResolver`, which is the same `BackendApi`/`AccountApi` discipline applied to the project's own seam instead of to a transport. Ask of any `commonMain` class a caller takes as a constructor parameter: *can a test hand this caller something?*
- **An exhaustive test double is a property of the module that owns the interface, not of every module that needs one.** `UnusedBackendApi` (52 members, throws) lives in `:backend`'s `commonTest` and is wired into `check-local.sh`, so a new `BackendApi` member is caught there. `:app`'s `RecordingBackendApi` is 52 members too, and it has to be: a *partial* double in a different module would let a new member arrive unexamined there. A narrow double is only acceptable when the exhaustive one already exists and is gated — the cost is a second 300-line file that must be regenerated when the interface changes, and a narrow one silently rots.
- **`kotlin.test` takes the assertion message *last*; `org.junit.Assert` takes it first.** The repo's `:androidApp` tests use the JUnit order and the `commonTest` suites use the `kotlin.test` order, and both are correct. Getting it wrong does not compile, so this is a compiler error rather than a trap.
- **Before concluding an AndroidX dependency is a blocker, read its `.module` on Google Maven — the answer is split down the middle far more often than not.** `androidx.paging:paging-common:3.5.1` publishes a genuine KMP artifact (android, desktop, iosArm64, iosSimulatorArm64, macosArm64, linuxX64, linuxArm64, js, wasmJs, mingwX64, tvOS, watchOS), so `PagingSource`, `PagingState`, `LoadParams` and `LoadResult` all resolve in `commonMain` — while `paging-runtime` and `paging-compose` (`Pager`, `PagingConfig`, `cachedIn`, `LazyPagingItems`) are the Android-only half and stay in `androidMain`. `androidx.navigation:navigation-compose:2.9.8` is the opposite: its only `available-at` files are `-android-`, `-jvmstubs-` and `-linuxx64stubs-`, and **the two `*Stubs*` variants are dokkadoc stub artifacts, not compilable KMP targets** — reading them as a yes is a false positive in the opposite direction from the usual one. **Maven Central 404s for every AndroidX coordinate**, so fetch `https://dl.google.com/dl/android/maven2/…/<artifact>.module`. When half a library is KMP, declare the KMP half in `commonMain.dependencies` **after** its consumer exists: a `commonMain` dependency with no `commonMain` consumer is speculative, and the repo's principles say not to ship that.
- **The number of roots tells you the size of the next batch before you start it.** Of the three `PagingSource`s, `BrowsePagingSource` had a single root (`BackendBrowseRepository`) and moved once the repository stopped constructing its own ports. `CatalogPagingSource` has a single root too — `HomeCatalogService`, in `:home`'s `androidMain`, pinned by `org.json` — and `org.json` in `commonMain` is settled and permanent, so neither it nor its consumer can move; that is a measured dead end, not an unstarted batch. `LibraryPagingSource` has five roots (`CrispyBackendClient`, `LibraryDiskCacheStore`, two section constants, and the cache's `read`/`write`) and is a whole batch of its own.
- `CalendarServiceTest` (34 cases) and `UpNextServiceTest` (16) cover `:home`'s two moved services. **Write a bucket-boundary case as two cases either side of the exact instant, and write every date as its ISO form with the epoch in a comment** — the boundary *is* the assertion, and a `Clock` fixture makes it readable where a raw `1_767_830_400_000L` does not.
- **A guard that is redundant is not "uncovered behaviour", it is no behaviour at all, and no test can ever see it removed.** This has now happened twice, and the second time it was a real finding about the code rather than a gap in the suite. `CalendarService` caches a fresh snapshot under `if (!freshSnapshot.isError)`, but the only path that returns `isError = true` is the no-context case — and there the `backendContext?.let { … }` performing the write is already a no-op. Replacing the guard with `if (true)` failed every test. The earlier instance was the fast-path cache read outside the `Mutex` in `CachingBackendContextResolver`. When a mutation survives, ask *what the guard is protecting* before writing a test for it. Here the answer is "nothing today" — the guard is a cheap assertion of the invariant that an error snapshot is never cached, and it is kept on that basis rather than deleted; a future error path that *is* reachable with a context would make it load-bearing again, and the suite would then have a gap. Record the reasoning where the guard is, so the next person does not read the mutation result as a bug. **A third instance, and the clearest:** `formatLongDate` trims first and then asks `if (raw.isBlank())`, so replacing that with `isEmpty()` is unobservable — after a `trim()` the two predicates cannot differ. The `isBlank()` is kept because it states the rule the KDoc claims ("a blank value is null") and stays correct if the trim is ever removed, but the mutation result is a fact about `String.trim`, not about the code. **A fourth instance, and the only one found by a test that was wrong before it found the guard.** `SubtitleRepository.serveFromCache` reassigns `_addonSubtitles` from the cache on a hit. The cache is a **single slot** and only `onSuccess` writes it, on the line before the publish, with the same list — and a failure writes neither — so a hit's cached list is by construction the list already on screen, and **no test can see the assignment removed.** I spent two attempts trying to construct one: the first asserted a fetch count that was off by one because a third title *displaces* the single slot, and the second published different subtitles, which by the same argument is impossible. The guard is kept, with the reasoning written at the guard. When a mutation survives twice, the cheap test is the one asserting the *architecture* (one writer, one slot) rather than a third arrangement of the inputs.
- **A mutation that a suite does not catch is a claim about the code, and it should be checked by hand.** Two of the fifteen `:home` mutations reported `COMPILE FAILED -- not evidence` when the patch itself was malformed (a stray `sections.` prefix, then an inverted replace), and one failed to compile because `view.nextEpisode` is not smart-cast. A broken patch and a real compile error look identical from outside the driver.

Kotlin Multiplatform modules (in progress; see `check-local.sh`):
- `:android:platform-core`: platform-portability interfaces (`SecretStore`, `KeyValueStore`,
  `AppLogger`, `TimeSource`, `DistributionCapabilities`, `MonotonicClock`) plus **`SecretFormat`**.
  Its `commonMain` stays platform-free — all seven files have zero `android.`/`java.` imports.
- **`:android:platform-desktop`: the desktop side of all six interfaces, and `:platform-android`
  is deliberately *not* being converted.** Measured: five modules already depend on
  `:platform-android` (`:android:app`, `:android:tv`, and `:android:watchhistory`,
  `:android:backend`, `:android:home` by `api`), and all four KMP consumers already take it from
  an `androidMain` source set, so converting it would churn four modules to move code with no
  reason to move. `:platform-desktop` is a plain `kotlin.jvm` module instead — every implementation
  is a JVM call, so there is nothing portable to put in a `commonMain` — and it holds
  `DesktopTimeSource`, `DesktopMonotonicClock`, `DesktopAppLogger`, `FileKeyValueStore`,
  `DesktopSecretStore`, `DesktopDistributionCapabilities` and `DesktopPaths`, with 40 tests. Four
  things in it are worth not re-deriving:
  - **`FileKeyValueStore` is one file per store name, never a key prefix inside one file.** That is
    the `SharedPreferencesKeyValueStore` rule, and it exists because a prefix has to be re-applied
    on every read and write, and one missed prefix is a silent leak between stores. It also makes
    the store hold no in-memory cache, which is why a write through one instance is visible to the
    next read through another — the property `FileKeyValueStoreTest` pins and a caching store
    would break.
  - **`sanitize` must reject a name that is only dots.** `.` is legal *within* a name
    (`settings.v2`), but a name of `..` sanitises to `..`, and `File(root, "..")` resolves to the
    root's **parent** — a traversal out of the store directory. This was found by a test asserting
    the file lands directly inside the root, after a first version asserted the sanitised *name*
    contained no `..` and passed on the buggy code (`auth_.._tokens` is a perfectly safe filename;
    it is a whole path component of `..` that is not).
  - **`Properties.load` does not throw on a line that merely looks wrong.** `=not a key` parses
    fine as an empty key. The only documented rejection is a malformed `\u` escape, so a fixture
    meant to prove "unreadable file reads as empty" has to use one of those or it passes for the
    wrong reason.
  - **`DesktopSecretStore` is not a keystore, and says so in its KDoc.** The key is a file next to
    the data. It buys "not readable as plaintext by whatever opens the settings file" and
    format compatibility with the Android store; it does **not** buy protection from an attacker
    with filesystem access, which is what `AndroidKeyStore` buys. `keyFile` is a constructor
    parameter precisely so an OS keychain is a wiring change rather than a contract change.
- **`org.json` in a `commonMain` file is settled, and not for the reason the old note gave.** It
  is not a dependency of this project at all — it is a class of the Android platform, supplied by
  `android.jar`, and it appears in exactly one build file (`testImplementation` in `:android:plugins`).
  There is no version to bump and no artifact to swap, so a KMP module simply does not have it.
  Replacing it means adding `kotlinx.serialization`, which is a behaviour change on a parsing
  boundary rather than plumbing. **Consequence: a file that parses or writes JSON stays in
  `androidMain`, permanently.** In `:app` that is `SearchHistoryStore`, `AiInsightsCacheStore`,
  `ProfileDataShadowStore` and `PendingMutationStore`; in `:watchhistory` it is the single thing
  keeping `WatchProgressStore` out of `commonMain`.
- **`:app`'s `androidMain` is a knot, not a list of independent files, and the table of what holds
  what lives in `android/app/build.gradle.kts`** — read it before planning any move. The
  `:app` + `:home` + `:addons` package overlap is why an import audit can call a file clean and be
  wrong; the *Rules* section below is the distilled method for getting past it.
  - **A replacement for a JVM library call goes in the file that already replaces that library, not next to the caller.** `formatLongDate` was a `LocalDate` + `DateTimeFormatter` call sitting at the bottom of an `androidMain` details screen, blocking a 264-line file. `Iso8601.kt` in `:core-domain` had *already* replaced `java.time` — it carries the month labels, the civil-date arithmetic and the parse functions. The new `formatIso8601LongDate` went there, and only the fallback policy (blank is null, unparseable is shown untrimmed) stayed in `:app`, because what to do with an unparseable value is a presentation decision rather than a property of a date. Look for the existing replacement before writing a second one in the same repo.
  - **Measure the JVM's real output before replacing a JVM call, and copy the measurement into the test.** `DateTimeFormatter`'s `yyyy` is a *year of era*, so `0000-01-01` formats as `Jan 1, 0001`, not `Jan 1, 0000`; a year past four digits gains a `+` sign. The first is reachable and is pinned by `FormatIso8601LongDateTest`. Six minutes running the real expression would have caught it; reasoning about what a year field "should" do would not have.
  - **Two functions can read the same ten digits, and a test of one says nothing about the other.** `parseIso8601DateToEpochDay` and `parseIso8601MonthNumber` both validate a `yyyy-mm-dd` prefix, and both needed the same separator fix. A mutation of the second passed every test of the first, because the first is not on the formatter's path. When a mutation survives, check *which function the test reaches* before concluding the guard is untestable — and widen the XML scan, since a filter naming only the new test class reported "no test failed" while the new assertion sat in a different class and had already failed.
- **An `R` reference blocks a file completely but usually blocks only a few lines of it, and the two halves belong in opposite source sets.** `:app` has **zero** `expect`/`actual` (`grep -rln 'expect fun\|actual fun' android/app/src` returns nothing), and the Material3 note above celebrates that a seam was never needed there — so introducing one for a single private `when` on a resource id was against the grain. `DetailsCastSection.kt` (241 lines) imported `com.crispy.tv.ui.assets.R` for exactly two `R.raw` ids. The split: 216 lines stayed in `commonMain`, 25 went to a new androidMain `ReviewProviderBadge.kt`, and the badge reaches the card through a composable-slot parameter `reviewProviderBadge: @Composable (String) -> Unit` with **no default**, so a call site cannot forget it — the same choice `ImageSettingsRepository(onQualityChanged:)` made, and now the second instance of that rule.
- **Push the pure half of a split toward `commonMain` and the platform half toward `androidMain`, even when the platform half is the smaller one — because otherwise the pure half is untestable.** `reviewProviderLogoRes` originally matched the *name* and returned the *id* in one function. A Robolectric test for it failed 4 of 6 cases with `NoClassDefFoundError: com/crispy/tv/ui/assets/R$raw`, because that generated class is not on a JVM test classpath — the test could never reach the name matching, which is the part that has behaviour. Splitting the matching into commonMain `ReviewProvider` + `reviewProviderOrNull()`, and leaving the androidMain function to translate the enum to an id, moved a real suite into `commonTest` where it runs on every target. **A function that both decides and looks up a platform resource is untestable for the decision**, and the error is a `NoClassDefFoundError` that names a generated class and says nothing about the logic.
- `linuxX64` on the pure-Kotlin KMP modules is a **compile-only verification target**, never shipped and never run. It is the one Kotlin/Native target that builds on a Linux host, so `compileKotlinLinuxX64` in `check-local.sh` enforces the same "no JVM API" rule as the Apple targets in seconds instead of waiting for macOS CI. The import-based purity gate cannot see this class of bug: `"".format(y)` is `kotlin.*`, so it passes the import scan and then fails only when an Apple target compiles. Prefer the native compile gate for anything touching `commonMain`.
- **The golden screenshots now cover the image layer, and the two cases are not redundant because `crispyImageRequest` returns early *before* it reads `LocalPlatformContext` when the URL is blank.** Until `LandscapeCardScreenshotTest` was added, the only goldens were `BrandAndGenreIconsScreenshotTest` and `PlayerLoadingCurtainScreenshotTest` — brand marks, genre icons and a player curtain. None of them rendered a card, a hero or an artwork frame, so the ten files that moved through `CrispyImage` passed the golden gate because the gate never looked at them. A card with no artwork still would not, because the blank-URL early return is above the line the move changed; only `withLoadedArtwork` exercises it. **The goldens prove the pixels arrived, not just that the code compiled:** the recorded `landscape_card_loaded.png` contains 10,500 sampled pixels of the committed fixture's quadrants and `landscape_card_fallback.png` contains none, which is a stronger claim than a green run.
- **Robolectric's `ImageDecoder` shadow serves a `data:` image model and fails a `file://` one, and the failure names a real framework class without saying what is wrong.** The card golden originally pointed `artworkUrl` at the committed fixture as a `file://` URI, which Coil fetches fine on a device. Under Robolectric the request failed with `android.graphics.ImageDecoder$DecodeException: Only supported on Android`, the card never fired `onSuccess`, and the golden hung on its `waitUntil`. Four measurements isolated it: Coil's `commonMain` does register `FileUriFetcher` (confirmed by `javap` on the cached `coil-core-jvm-3.5.0.jar`, which lists exactly `BitmapFetcher`, `ByteArrayFetcher`, `DataUriFetcher`, `FileUriFetcher`, and there is no `META-INF/services`); the *same bytes* as a `data:` URI decode to 240x135; `BitmapFactory.decodeFile` reads the fixture; and `ImageDecoder.createSource(ByteBuffer.wrap(bytes))` reads it too. So the tempting conclusion — "Robolectric cannot decode images" — is false, and the real distinction is the source shape Coil hands the decoder. `CoilDataUriScreenshotTest` pins the contrast in both directions, including the message to read if the `file://` arm ever starts succeeding. Three dead ends not worth repeating: `ImageDecoder.createSource(Uri.fromFile(f))` does not compile (use the `ByteBuffer` overload), and `coil3.compose.SingletonAsyncImage` does not exist in 3.5.0 despite appearing in Coil's own `commonMain` sources.
- **A golden that waits on a signal the component itself emits is not a flaky golden; a golden that waits on a duration is.** `withLoadedArtwork` waits on `SharedImageMemoryKeys.getCardKey(...) != null`, which `LandscapeCard` writes from `AsyncImage`'s `onSuccess`, so a slow host cannot make it time out spuriously. `SharedImageMemoryKeys` has `put` and `get` and **no removal API**, which is why the test has no cleanup: the first version had a `finally` that re-set the key under a `?.let`, which was a no-op dressed as tidiness. A test needing a *clean* map would have to add an API rather than work around its absence.
- `:android:home`: the home feature module — a KMP library declaring `iosArm64`/`iosSimulatorArm64`, so on Linux **`compileKotlinLinuxX64` is the only check its Apple targets get** and it is in `check-local.sh` for that reason. It is where the composition root's home data lives, and two facts about it are easy to get wrong:
  - **It shares the package `com.crispy.tv.home` with `:app`, so a type declared in its `androidMain` is reachable from `:app` with no import at all.** This is the purest form of the trap that makes an import-only migration audit report confidently-empty answers. `CalendarEpisodeItem` is declared in `:home/src/androidMain` and was used from `:app/src/androidMain` with nothing in the import list to show for it.
  - **`CalendarService`'s cache is per-profile and written only on a non-error fetch.** `cachedCalendarSnapshot` carries the `profileId` it was written for, `loadCalendar` reads it only on a `takeIf { it.profileId == context.profileId }` match, and a fetch that throws returns the cached snapshot rather than overwriting it. The two non-obvious consequences: a cache written for one profile is never handed to another, and a failed read never replaces a good snapshot. Both are pinned by `CalendarServiceTest`; the `isError` guard on the write is deliberately redundant (see the redundant-guard finding above) and says so where it is.
- `:android:desktopApp`: the desktop entry point and the **seam proof** (plan §3) — one JVM module for Windows, macOS and Linux. Renders `:android:sharedUI`'s design system over `:android:core-domain`'s real `planContinueWatching`, seeded from a real contract fixture. **It now depends on `:android:app` and renders `ContinueWatchingRail` from `:app`'s `commonMain`**, which makes it the first non-Android target to consume `:app` code, and `ContinueWatchingRailTest` the first test in the repository that compiles `:app` for a non-Android target. Three things that landing established, each measured rather than assumed. **A plain `kotlin.jvm` module consuming a KMP library's `jvm` variant is a compile, not a documented guarantee** — it works, and the dependency is worth writing down as a measurement. **A `commonMain` composable that gains a caller outside its module has to be `public`, and the rest stay `internal`:** `:app`'s screen composables are internal because their callers are in-module, and widening all of them is API surface nobody asked for, so widen only the one a second target actually calls. **A module can carry a premise that a dependency invalidates:** the desktop rail's `CARD_CORNER_RADIUS` duplicated `CardStyle.CardCornerRadiusDp` with a KDoc saying it could not be imported because that constant lives in `:app`'s `androidMain` "which the desktop target cannot see" — the constant was in `commonMain` all along, and the duplicate was dead the moment the dependency existed. **It now also constructs all six `:platform-core` ports** via `DesktopEnvironment`, and the window's size is read from and written to the injected `KeyValueStore` — so the seam is exercised on a real round trip rather than merely declared. **It renders a second `:app` screen, `ImageSettingsScreen`, over a real user setting**, which is the first *stateful* surface on the desktop: a quality chosen in the window is in the file on disk and still chosen on the next launch. Three things that second screen settled. **A `commonMain` composable's reachability is decided by its repository's visibility, not its own:** `ImageSettingsScreen` was already `public` and wall-free, and was still unreachable because `KeyValueStoreImageSettingsRepository` was `internal` — a public composable over an internal repository is as unreachable as an internal composable, and it *reads* as reachable, which is what makes it a trap. Widen the repository when the screen needs it; it had one production caller, so the change was one word. **The two implementations of a port meeting is a distinct thing from either one being correct,** and nothing tested it: every test of the `:app` repository runs in `commonTest` against a fake, and every test of `FileKeyValueStore` runs in `:platform-desktop` against a hand-written caller, so a store that satisfied its contract and a repository that satisfied its could still fail to interoperate — which is the whole claim of a port. `DesktopImageSettingsTest` (8 cases) is the suite that closes that, and it asserts the setting is on **disk** rather than that the code handed the value back. **A screen count is not a product, and a placeholder affordance is a placeholder:** the window switches between its two screens with a `remember`ed enum and a `BasicText` label, because `:app` has no navigation seam to grow a shell into — `androidx.navigation` is not on the `commonMain` classpath at all. The shell is the next thing and belongs in `:app`'s `appUi`, not in the entry point. Two rules that came with it. **Construct the `MonotonicClock` once and share it:** it is an origin plus a reading, so a caller that made its own would compare an elapsed time against a different origin and get a large or negative interval. **Persist a window size on close, not from a `SideEffect` on the size:** a `SideEffect` keyed on the width rewrites the settings file on every frame of a drag, and each write is a full read-modify-write of the file. It is a semantic-assertion test, not a golden: a Skia raster varies by Skia version and font availability, and the Android Roborazzi gate is already the rendering gate. **Host prerequisites:** Skia needs `libGL.so.1`, `libX11.so.6` and `libfontconfig.so.1`, and needs at least one font. The font is bundled in `test-fonts/` (repository root, shared with `:android:androidApp`) and reached through a generated `fonts.conf`. Without those the test reports a Skiko native-load error or `IllegalStateException: Could not load font` — neither says anything about the seam. CI's `ubuntu-latest` has all of them.
- `:android:sharedUI`: KMP + Compose Multiplatform `1.11.1`, targeting Android + `desktop` JVM + `iosArm64` + `iosSimulatorArm64`, and producing the `CrispyUI` iOS framework. Holds the design system in `commonMain`. Three rules that are expensive to relearn:
  - **`android { }` is current; `androidLibrary { }` is deprecated** as of Kotlin `2.4.10`, which says so outright: *"'androidLibrary' block is deprecated. Please use 'android' instead."* Earlier guidance — the JetBrains migration guide, and earlier revisions of this file — said the opposite and described a real failure with `android { }`. That failure is gone; JetBrains converged the two blocks. Write `android { }` on new code. `:sharedUI` still uses `androidLibrary { }` and compiles, with a deprecation warning.
  - **CMP 1.11.x ships `androidx.compose.*`, not `org.jetbrains.compose.*`.** Verified by unzipping the resolved AARs: 790 `androidx/compose` classes in `runtime-android`, 1336 in `ui-android`, and **zero** `org/jetbrains/compose` in any of them. The `org.jetbrains.compose.*` coordinates are thin aliases. So moving a Compose file from `:app` to `:sharedUI` changes the **artifact coordinates in `build.gradle.kts`**, never the imports in the file, and `import org.jetbrains.compose.*` does not compile anywhere.
  - **No `linuxX64` on Compose modules.** CMP publishes no linuxX64 artifacts — its targets are Android, iOS and Desktop (JVM) only — so declaring it makes every `compose.*` dependency fail to resolve. `jvm("desktop")` is the local purity gate for Compose modules instead. This is why the `linuxX64` rule above is scoped to pure-Kotlin modules.
  - **`CrispyPalette` is the token layer both surfaces read, and `:tv` maps two roles onto it.**
    `:sharedUI` and `:tv` both keep their own `Theme.kt` — different Material3 libraries, so
    neither is deletable — but both build their `darkColorScheme` from the one `object
    CrispyPalette` in `:sharedUI`'s `commonMain`. `:tv` needs **no new dependency**: it already
    had `implementation(project(":android:sharedUI"))` at `android/tv/build.gradle.kts:150`. Its
    only special case is `border = CrispyPalette.outline, borderVariant =
    CrispyPalette.outlineVariant`, because `androidx.tv.material3` names those roles `border`
    where `androidx.compose.material3` names them `outline`. **Those two mapping lines are
    written out rather than derived, and a missing one is a silent colour change, not a compile
    error** — which is why the values behind them are pinned in `commonTest`. `:sharedUI` also
    gained `withHostTest {}` and its **first test file** in this landing; without the block a
    `commonTest` source directory exists but nothing compiles or runs it on Android and AGP only
    warns.
  - **`:tv`'s mapping is now pinned by `:android:tv`'s first test, and the reason it was recorded
    as untestable was false.** This bullet used to end at "the values behind them are pinned in
    `commonTest`", which pinned `CrispyPalette` and left `:tv`'s half of the mapping — the part
    that can actually be wrong — asserting nothing, for a stated reason that was never checked.
    **`:tv` is a plain `com.android.application`, and that is not the obstacle it was taken to
    be**: `CrispyTvDarkColors` is a top-level `val` calling `darkColorScheme(...)`, which builds
    a `ColorScheme` data class out of `androidx.compose.ui.graphics.Color` — an inline value
    class over `ULong`, pure Kotlin, no `Context`, no resource, no view. **A plain JVM unit test
    in `:android:tv/src/test` reaches it, and `:tv` has had no `src/test` at all**; the real
    obstacle was the `private` modifier, which is why the landing widened it to `internal`
    (the repo's own rule: *a decision no test can call is a decision no test can cover*). Ten
    cases, `testDebugUnitTest`, JUnit only and deliberately **not** Robolectric. **So "`:tv` is a
    plain application module" was a claim about the module type standing in for a fact about the
    code, and the second time that shape has cost this repository work the first time had already
    caught it** (the `LocalWindowInfo` rule in §1). Before recording a surface as untestable,
    find the declaration and look at what it actually *is*.
  - **`:tv` maps 29 of the palette's 37 roles, and its own KDoc under-counted the gap.** The
    unmapped eight are the seven `surfaceContainer*`/`surfaceDim`/`surfaceBright` roles the KDoc
    named **plus `spinner`**, which is not a Material3 role at all. The old wording "under-counted
    the gap by one and left the `spinner` omission looking deliberate when it was only never
    considered" — which is the general hazard: **an omission nobody mentioned reads as a decision
    nobody made.** The other number in that KDoc, "all twenty-seven roles", is the count of
    *mapped* roles and reads as the palette's size; it is 37. Both figures are now asserted, and
    `theMappedAndUnmappedRolesPartitionTheWholePalette` fails if the two sides stop summing to
    `paletteRoles.size`.
  - `LocalConfiguration` is Android-only even under CMP — it lives in `AndroidCompositionLocals_androidKt` inside the `ui-android` AAR, so a grep of the class lists cannot see it either. **This line once recommended `LocalWindowInfo.current.containerDpSize` as the replacement, and `LocalWindowInfo` does not exist in any resolved Compose artifact at any version.** There is no portable composition local for the window size here: the answer is that the caller already holds the value. A screen that needs to know whether it is wide takes a no-default `isWideScreen: Boolean` and the caller passes it — see *Rules* §1.
  - **Material3 Expressive is available on every target, and is used on every target. Settled — do not re-litigate, and do not "fix" it by dropping it.** The whole blocker was one line in a build file. This repo recorded, in three places, that Phase 4 "has to drop or replace Material3 Expressive" because `androidx.compose.material3:1.5.0-alpha26` publishes no `material3-desktop` artifact. That was inferred from a coordinate mismatch and was **wrong**, and acting on it would have deleted a shipping design feature to work around a version pin.
    The fix is to declare **`org.jetbrains.compose.material3:material3`** (`libs.compose.material3`) instead of either the androidx coordinate or the `compose.material3` alias. That coordinate is a thin alias that delegates per target, verified by resolving the graph: on Android it becomes `androidx.compose.material3:material3-android:1.5.0-alpha27` — genuine AndroidX, one alpha *forward* of what the app shipped — and on desktop/iOS it becomes `org.jetbrains.compose.material3:material3-desktop:1.13.0-alpha01`, the real fork carrying `LoadingIndicator`, `MaterialShapes` and `WavyProgressIndicator`. So there is exactly one material3 on any classpath, the `androidx.compose.material3` package and imports are identical everywhere, and **no `expect`/`actual` seam is needed**. The 12 Expressive call sites are untouched.
    **The `compose.material3` alias cannot be used, and this is the part that is easy to get wrong.** It deliberately tracks the latest *stable* Material3, which is 1.4.0 and has no Expressive at all — CMP decoupled Material3 versioning in 1.9.0 precisely so a project can opt forward. Gradle now deprecates it too. Always take material3 from `libs.compose.material3`.
    **Version choice: forward, never backward.** `composeMaterial3 = 1.13.0-alpha01` is the only published JetBrains material3 at or ahead of the AndroidX alpha the app was on. The nearby `1.12.0-alpha03` would have been a **downgrade on two axes** — AndroidX material3 to `1.5.0-alpha22` *and* the whole Compose stack to `1.12.0-beta01`, which is *older* than the `1.12.0` stable it replaces (publication order is alpha01..alpha03, beta01, beta02, rc01, 1.12.0). Do not take it. The accepted cost is that the Compose stack leaves stable for `1.13.0-alpha02`; `composeAndroidx` is pinned to match it and must be bumped together with `composeMaterial3`, never alone.
    Verified rather than assumed: the goldens were re-run after the bump, and `PlayerLoadingCurtainScreenshotTest` — which renders an Expressive `LoadingIndicator` — is **byte-identical** across `1.5.0-alpha26 → 1.5.0-alpha27`, with no golden re-recorded.
    Two related traps. **The number of files carrying `@OptIn(ExperimentalMaterial3ExpressiveApi::class)` is not the number of files using Expressive, and the gap closes as files move.** This line once read "14 files annotate but only 6 touch an Expressive symbol — the other 8 annotate while using only ordinary Material3 such as `MaterialTheme`, so their annotation is vestigial and can be deleted as those files move." That was true when written and is now history: the 8 vestigial annotations were deleted as those files moved, and the current measurement is **6 files annotating and 6 using** (`LoadingIndicator` in `AuthScreens`, `DiscoverScreen`, `LibraryRoute`, `LibraryScreen`, `PlayerLoadingCurtain`; `MaterialShapes` in `AiInsightsStoryOverlay`). Keep doing the check rather than trusting either number: `grep -rl 'ExperimentalMaterial3ExpressiveApi'` finds annotators, not usages, and an annotation with no Expressive symbol behind it is dead weight that a green build will not flag. And **never read an AAR's size to decide whether a version has a feature**: `androidx.compose.material3:material3` is a ~4.7 KB stub holding only a manifest and a licence at every version, with the real code in the `material3-android` sub-module. Read the per-target artifact, or the `*.module` metadata, or just resolve the dependency graph.
  - **`platform(libs.androidx.compose.bom)` does not exist on a KMP source set.** `KotlinDependencyHandler` has no `platform()`, so a KMP library cannot apply a BOM and must pin versions itself. A plain Android module like `:androidApp` can, and does.
  - Pin CMP `1.11.1` to Kotlin `2.4.10`; bump them together or not at all.

## Rules

Distilled from every gate that fired, every compile that failed and every mutation that survived
this session. Each one is here because it changed a result; none of it is a story about a file.
The per-landing narrative this replaced is in the git history, where it belongs.

### 1. Audit before you plan

- **A module's untested `commonMain` is usually untested because its `androidMain` files meant
  nobody ever added a test source set — so the absence of tests is a fact about the module's
  build file, not about any individual file.** `:android:addons` compiles for Android, desktop
  JVM, `linuxX64` and both iOS targets, and had **no test source set at all** across all five.
  Five of its files are `Context`/OkHttp/`org.json` Android adapters, and that is exactly why
  nobody created `commonTest`: it would have been a directory with one test in it, in a module
  that reads as Android-shaped. The consequence was that **`StreamLookupSupport.kt` carried 171
  lines of nine pure functions** — lookup-id parsing, subtitle building, episode matching,
  provider merging — with **zero tests anywhere in the repository**, while `:app`'s
  `androidMain` files had 445. Nothing about those 171 lines says they were undertested; the
  only place the answer was written down was the module's *plugin block*. So when you conclude
  "this pure file is covered" or "this module is thin", check `git ls-files <module>/src/commonTest`
  **first** — it is one command, and it finds an entire untested surface that no per-file audit
  will surface. `:addons` now has `withHostTest {}` and a `commonTest`, and its 28 cases run on
  desktop JVM *and* Android host.
  **Sweeping that one command across every module finds four more of the same shape:**
  `:platform-core` (7 files), `:player` (6), `:network` (2) and `:watchhistory` (2) —
  **17 files of `commonMain` with no test source set at all.** `:platform-core` is the
  sharp one, because it holds the six port interfaces *and* `SecretFormat`, the
  encrypted-secret contract both platform stores implement. So this is a whole-repo
  sweep rather than a per-file audit, and it costs one loop over the module list.
- **The `:addons` explanation does not generalise, and assuming it does hides the more useful
  cause.** The bullet above says a module went untested because its `androidMain` files made a
  `commonTest` look silly — and `:android:player` is the counter-example that identifies the
  real mechanism. All six of its files are `commonMain` and its `androidMain` is **empty**, so
  there is no Android-shaped sibling and nothing about the module looks untestable. Its own
  build-file KDoc says it is "the first Phase 2 module to become multiplatform, and the one
  that establishes the recipe the other five follow" — **and the recipe it established did not
  include a test source set**, which is how five later modules inherited the gap. So when a
  module has no tests, read its KDoc before theorising: a module that describes itself as the
  template has told you the other five are copies. 389 lines of `:player` ran on four targets
  asserted by none of them, and it is now 51 cases on desktop JVM *and* Android host.
- **The two ends of that sweep are opposites, and the opposite end is the one that hides a
  decision.** `:android:player` had an empty `androidMain` and nothing looked untestable;
  `:android:network` is the mirror — **all four of its `androidMain` files are OkHttp/`Context`
  adapters that no `commonMain` can ever reach** (OkHttp publishes no Kotlin/Native artifact),
  so the module reads as Android-only, and its `commonMain` is 27 lines of pure string
  handling whose four-alternative hand-rolled regex is exactly the kind of thing that rots
  silently. `:network` is now 27 cases. **A module that looks untestable is not a module
  with nothing to test** — read the `commonMain` file list and its line counts before
  concluding anything from the `androidMain` shape.
- **A module with no test source set is a question, not a defect, and one of the four is a
  measured non-finding.** `:android:watchhistory`'s `commonMain` is `WatchHistoryConfig.kt`
  — one field defaulting to `"dev"` — and `sync/WatchSyncSource.kt`, a three-method interface
  with empty bodies. A suite there re-asserts the compiler, the same reasoning recorded below
  for `ProfileRepository` and `AccountSettingsRepository`. **This is why the sweep is written
  down with its answer**: the four-module finding is closed, and closing it means recording
  that one module was asked and deliberately left alone. Its three `androidMain` files are
  blocked by `org.json`, by reaching `:backend`/`:player`, and by OkHttp respectively, as its
  own build-file KDoc records.
- **A document's own heading, prose class, or type names are not evidence about the code — and a
  second numbered plan for one repository is a defect even when every sentence in it is correct.**
  `architecture.md` was rewritten for the ported codebase and both halves of that bullet came from
  reading the whole 594-line file rather than its four known-stale lines.
  **Seven of the type names it used did not exist and never had**: `WatchProvider`, `Resource`,
  `Freshness`, `ScreenState`, `PendingMutation`, `LibraryRepository`, `ProviderConnectionRepository`,
  `PlaybackRepository`, `SettingsRepository`. One command settled it —
  `git ls-files '*.kt' | xargs grep -l "\b$t\b"` per name — and **`DataSource` was the sharp one:
  its six hits are every one of them `androidx.media3.datasource.DataSource` inside `:native-engine`,
  so the name a document proposes is already taken in this repository by a Media3 type.** A reader
  who had implemented the proposal would have hit a collision rather than a fresh type.
  **The prose class is the part nobody would have guessed.** `## Target Use Cases` carries an
  explicit disclaimer — *"The important part is not the exact names"* — so its six invented
  interface names are obviously illustrative. **`## Fetch And Cache Policy` is the same *kind* of
  section and carries no such disclaimer**, so a reader takes its `Resource<T>`/`DataSource`/
  `Freshness`/`ScreenState` as descriptive. So the two things a stale document does are: name types
  that do not exist, and let a reader's own inference about register decide whether that matters.
  When auditing prose, read the surrounding sentences, not the identifiers.
  **The competing plan is the structural half.** `architecture.md` carried a seven-phase
  `## Migration Plan` whose phase numbers meant something completely different from the tracked
  `kmp-migration-plan.md`'s — same repository, same word "Phase", disjoint meaning, so a reader who
  had read one misread every phase number in the other. The fix was a **scope split, not a
  deletion**: retitle it *Migration Plan (product and backend)* and point at the tracked plan for the
  port. **Two numbered plans for one repository is worse than one plan even when both are correct**,
  because the collision is only visible to a reader holding both, which is the reader most likely to
  be misled.
- **A refactor list is a set of claims about the code, so re-measure it; a list where most entries
  are finished is worse than no list.** `## What To Refactor First In This Repo` held nine numbered
  items, and the heading says *In This Repo*, so each one is checkable — and three had already been
  done: the provider enum is gone, the durable profile-scoped mutation queue exists
  (`UserMutationOutbox` in `:app` `commonMain` with a sealed `MutationStatus` in `core-domain` and a
  `commonTest` suite), and `SessionRepository`/`CatalogRepository`/`UserMediaRepository` exist
  together in `android/app/src/commonMain/kotlin/com/crispy/tv/domain/repository/`. A list that
  reads as outstanding work it is not. **The fix is to split it into what is still true and what is
  done, keeping the numbering**, so a reader can see item 6 is half-done rather than guessing from
  the fact that its first half has an implementation.
- **Run the import audit in both directions.** A forbidden-token scan answers *pinned by an
  import*. Subtracting every type declared in every module's `commonMain` from the capitalised
  identifiers a file uses answers *pinned by a sibling* — `:app`, `:home` and `:addons` all declare
  overlapping `com.crispy.tv.*` packages, so a declaration in another module's `androidMain` is
  reachable from here with no import at all, which the forward scan cannot see. Seven instances;
  the reverse scan is what found six movable files / 1,199 lines out of 25 candidates.
- **A file with zero forbidden imports can still be unpinnable, because the blocker can be a
  *type*.** `grep -rn "class X"` its distinctive types and read the owning module's `plugins { }`
  block. `:android:native-engine` and `:android:ui-assets` are plain `com.android.library`, so no
  KMP `commonMain` can name their types whatever the code looks like. One command settles it.
- **Resolve a large error cascade by distinct unresolved *names*,** mapping each back to its
  declaring file — not by line. 17 errors reduced to two roots; 200 reduce to a handful.
- **Re-test a premise this file or a build file states as settled.** Nine have been wrong so far,
  and the pattern is always the same: a name that sounds plausible was never tried. `LocalWindowInfo`
  **does not exist** in any resolved Compose artifact; `LocalConfiguration` is Android-only and
  lives in `ui-android`'s `AndroidCompositionLocals_androidKt` so a jar-grep cannot see it; `coil3`'s
  own `commonMain` already declares `LocalPlatformContext`; `lifecycle-viewmodel`,
  `lifecycle-viewmodel-compose` and MaterialKolor are genuine KMP artifacts. **Measure the artifact
  you are about to declare, never the family** — `paging-common` is KMP while `paging-compose` and
  `paging-runtime` are not, in the same family, in the same module.
- **A target declaration is a claim, and a target no CI job builds cannot fail -- so it asserts
  nothing about whether the code is platform-free.** Ten modules declared
  `iosArm64`/`iosSimulatorArm64`. `apple.yml` compiled **two** of them (`:core-domain`,
  `:platform-core`) through an explicit task list, and **eight carried a target that no workflow
  on any runner ever compiled.** It turned up while asking a different question -- whether a
  `platform-apple` module would have a consumer -- which is the third time in this repository that
  the useful answer came from measuring a *neighbouring* claim rather than the one in front of you.
  `apple.yml` now compiles **all ten**, and `scripts/verify_apple_targets.py` (run by
  `check-local.sh` and by `apple.yml`) fails when a module declares a target the workflow does not
  build. The converse is deliberately unchecked, because **removing a target is a product decision
  and a script has no business making one.** `scripts/verify_kmp_outputs.py` is the same idea pointed
  at class files; the general form is that **an assertion nothing executes is not a weak assertion,
  it is no assertion.** And the gate needed proving before it could be trusted: its first version
  built `compileKotliniosArm64` where the task is `compileKotlinIosArm64`, so it reported all twenty
  tasks missing on a workflow that invoked every one -- the repo's own "a gate that fires on correct
  code gets switched off" trap, reached by the most direct route available.
- **The Apple client is not this codebase, and `kmp-migration-plan.md`'s Phase 6 is correct while a
  build file's KDoc is not.** `:platform-android`'s KDoc promised the Apple port implementations
  "in Phase 6, each against the same `platform-core` interfaces". Measured: `grep -rl <port> ios
  --include=*.swift` returns **zero hits for all six ports**; `:sharedUI` has **no** `platform-core`
  dependency; `ios/CrispyKit` is a **19-file Swift reimplementation of the whole data layer** (its
  own HTTP client, its own Supabase auth, its own session store, its own JSON, its own seven view
  models); and `ios/project.yml` links only `CrispyKit` and `ContractRunner` -- **`CrispyUI` is built
  by nothing and imported by nothing.** So **zero lines of Kotlin execute in the shipping iOS/tvOS
  app**, and a `platform-apple` module would have no consumer: it could only be "verified" by adding
  it to the `apple.yml` list being edited in order to verify it, which is circular, and it is the
  speculative-dependency shape the repo's own rules forbid. The desktop equivalent landed
  (`platform-desktop`) **because `desktopApp` is a real caller**; the Apple gap is a product
  decision, not a technical block. Note what went wrong in my own reasoning, because it is the
  generalisable part: **I read that KDoc promise as the plan's next phase, and neither the KDoc nor
  the plan said what I assumed.** The plan's actual Phase 6 is "Wire `CrispyKit` to the `CrispyUI`
  framework" and reconcile the six Swift files that duplicate shared code -- a different and
  already-written answer. **A promise in one module's KDoc is not a plan, and a plan is not a
  promise; read the plan.**
- **Measure the JVM's real output before replacing a JVM call, and copy the measurement into the
  test.** A `DateTimeFormatter`'s `yyyy` is a year of *era*; `Math.round(0.999f * 100f)` is 100;
  `mi` renders `Māori` with a macron; `fre` is French and `ger` is German. Six minutes running the
  real expression beats reasoning about what a field "should" do.
- **`dependencyInsight` on a configuration a dependency is not declared in** returns "no
  dependencies matching" in seconds, before a compile that would produce the same answer as an
  error inside an unrelated file.
- **Diff every moved file against its original in `HEAD`.** `diff <(git show HEAD:<src>) <dest>` is
  the cheapest check in the workflow and the only one that proves a *non-executed* line survived.
  Four of the six moves in one batch were byte-identical, which is the strongest form of the answer.
- **Two libraries naming one role differently is a mapping, not a divergence — and it is
  invisible until you put the two files side by side.** `:tv`'s `androidx.tv.material3`
  calls the border roles `border`/`borderVariant`; `:sharedUI`'s `androidx.compose.material3`
  calls the same roles `outline`/`outlineVariant`. Both sides held the *same two hex
  values* (`0xFF333333`, `0xFF262626`) written out twice. Read as two schemes side by side
  this looks exactly like a deliberate TV palette divergence that must be preserved, and it
  is in fact a pure de-duplication worth zero pixels. `:tv`'s own `DetailPalette.kt` already
  mapped the roles by hand at its last four lines — read it *before* deciding a divergence
  is a product decision, because it is independent proof when it is not.
- **A duplicated `public` constant can be dead, and the same name in a different package is
  what hides it.** `:tv` re-declared `CrispySpinner = Color(0xFFF56E3C)` under the *same
  name* as `:sharedUI`'s, both `public`, in different packages — so no compiler error, no
  lint, and no import audit flagged it. `git grep` showed all **11** call sites importing
  `:sharedUI`'s and **zero** referencing `:tv`'s. This is the §1.1 same-package trap from
  the other direction: there, a type is reachable with no import; here, an identical
  declaration is reachable with no diff.

- **"This is bound to navigation" is a statement about the file, not about the mechanism, and
  reading the provider settles it in one grep.** `kmp-migration-plan.md` filed the shared-element
  transitions under Phase 4 with the reason "`androidx.navigation` is not on the `commonMain`
  classpath", and that reason was **false as stated**: across the whole repository there are
  exactly **three** distinct imports of shared-transition machinery -- six sites of
  `com.crispy.tv.ui.navigation.LocalSharedTransitionScope`, **one** of
  `androidx.compose.animation.SharedTransitionScope` and **one** of
  `androidx.compose.animation.SharedTransitionLayout`. **Zero** of the 27 participating files
  import `androidx.navigation` for the transition itself. `androidx.compose.animation` is Compose
  Multiplatform and present on Android, desktop JVM and both iOS targets. *Navigation is what
  navigates; the thing that transitions is Compose.* The single provider was two lines inside
  `AppNavHost.kt` -- in a package called `ui/navigation`, which is exactly why it read as
  navigation-bound -- and **neither of those two lines named navigation.** The dependency is in the
  file, not in the lines that matter.
- **The count was wrong twice, and the second error was the informative one.** The plan said 28,
  then "10, not 28, across 6 files". Measured over tracked sources: **27 files, 14 in `:app`
  `commonMain` and 13 in `androidMain`** -- `commonMain`: `details/DetailsBody.kt`,
  `details/DetailsCastSection.kt`, `home/{HomeCalendarComponents,HomeCatalogComponents,
  HomeHeroCarousel,HomeTop10Components,HomeWideRailComponents}.kt`, `library/LibraryScreen.kt`,
  `search/SearchScreen.kt`, `ui/components/{LandscapeCard,PersonCircleCard,SharedCardBackdrop}.kt`,
  `ui/navigation/{AppRoutes,LocalSharedTransitionScopes}.kt`; `androidMain`:
  `catalog/CatalogScreen.kt`, `details/{DetailsHero,DetailsRoute,DetailsScreen}.kt`,
  `discover/DiscoverScreen.kt`, `home/CalendarScreen.kt`, `person/PersonDetailsRoute.kt`,
  `ui/navigation/{AppNavHost,AppRoutesBuilders,DiscoverNavGraph,HomeNavGraph,LibraryNavGraph,
  SearchNavGraph}.kt`. **Each correction made the job look cheaper and the truth was the opposite**,
  because what grew was the count of *shared* participants, and the framing that paid off was
  *the participants, the scope and the mechanism are all already shared; only the host was Android*.
- **A missing provider is a silent null, so "the code is already shared" is not evidence that
  anything runs.** All 14 `commonMain` participants read `LocalSharedTransitionScope`, whose default
  is `null` -- so off Android every one of them rendered with **no transition and no error
  anywhere**, and the goldens did not see it because the golden suite does not render those
  screens. A participant written against a `null`-defaulting local is correct and *inert*. When a
  landing supplies a host, assert the value is non-null **under the host and null without it** --
  the second assertion is what makes the first mean "the host supplied it" rather than "the local
  defaults to it", and it pins the fallback the participants rely on.

### 2. Ports, seams and slots

- **Shared constants are not the shared format, and the difference is the part nobody
  writes.** `SecretFormat` carried `PREFIX`, `IV_SEPARATOR` and `GCM_TAG_LENGTH_BITS` in
  `:platform-core`'s `commonMain` and both stores referenced them — and both stores still
  *joined and split* the value themselves, identically and by eye. Two stores agreeing on
  the shape was therefore a promise in prose rather than a call to one function. The fix is
  `SecretFormat.encode(ivBase64, ciphertextBase64)` and `SecretFormat.decode(stored)`, and the
  reason they take **`String`s rather than byte arrays** is the part worth keeping: base64 is
  a platform concern (`android.util.Base64`, `java.util.Base64`, `NSData`), so what is shared
  is the *shape*, and neither function touches base64 at all — which is what lets them live in
  `commonMain`.
  **And nothing could have caught it**, which is the sharper half. `SecureTokenStore` reaches
  `AndroidKeyStore` in its constructor and cannot be constructed on a JVM at all, and
  `:platform-desktop`'s suite exercises the store rather than the format — so *neither side*
  had a test that ran the shared code directly. A rule both implementations depend on and
  neither tests needs its own suite; it does not come free with the constant.
- **`Dispatchers.IO` does not exist in `commonMain`.** It is declared in the JVM and Native
  source sets, so a `commonMain` file sees only `Dispatchers.Default` and `Dispatchers.Main`.
  A `commonMain` class doing blocking work therefore needs a **no-default** `ioDispatcher`
  slot rather than a defaulted one: defaulting to `Dispatchers.Default` compiles on every
  target and silently puts blocking I/O on a CPU-sized pool. Measured when
  `DetailsMetadataLoader` moved to `commonMain`; the view model passes `Dispatchers.IO`.
- **A double whose member returns `Nothing` cannot be subclassed, and `Nothing` is a lie the
  interface never declared.** `RecordingBackendApi` answered every unstubbed `BackendApi`
  member with `): Nothing = unused("name")`, which is subtype-narrowing -- and no override can
  widen `Nothing`, so making the class `open` was not enough. Declare the **interface's**
  return type and keep the throw (`): MetadataTitleExtrasResponse = unused("...")`). The throw
  is what the double is for; the wrong return type is what blocked reuse.
- **Make the exhaustive double `open` rather than writing a second exhaustive one.** A `:app`
  consumer that needs one of the 52 `BackendApi` members answered would otherwise either
  duplicate 52 members (a copy that rots) or add a narrow port (worse: it never learns a new
  member arrived). `open` + a one-member subclass in the consumer's own test file is the third
  option, and it costs the base class one word. This is the narrow-double rule turned inside
  out: the exhaustive double stays exhaustive, and the *specialisation* is a subclass.

- **A port's members are the union of every caller's.** Measuring one caller gave four of
  `PlayerGestureController`'s five; the fifth, `restoreBrightness()`, is called from another file and
  is a one-shot latch the screen depends on. Building the interface from the file you happened to
  move is a behaviour change.
- **Name the port after the class it replaces** so consumers need no import edit
  (`StreamResolver` → `CachingStreamResolver`; `PlayerGestureController` kept its name).
- **Reproduce the implementation's signature verbatim**, return type included. Narrowing a port's
  return type to the tidier-looking one is a silent behaviour change on a caller's hot path.
- **A nested type is as pinned as the file declaring it.** A caller naming `Outer.Inner` as a
  parameter type pins the whole file, so lift it to top level first.
- **An interface grows when a second caller appears — and every comment that justified a member's
  *absence* is then false.** `LibraryDiskCache` had two members because the paging source used two;
  the screen made it three. Grep the suite for the reasoning, not just for the type.
- **A `Context` used for *wiring* belongs in the factory. A `Context` used for a *call* is a
  capability, and the slot carries the data** — `shareText: (String) -> Unit`, `openUrl`,
  `loadProfile`, `stashHandoff`. A slot over `(Context) -> Unit` would keep the platform type on the
  wrong side of the line.
- **No-default slots for anything a call site must not forget.** A defaulted capability lets a call
  site silently hide a row the build ships.
- **When a platform composition local is unreachable, the answer is usually a value the caller
  already has.** `isWideScreen`, `isCompact` and `pluginsUiSupported` all crossed as data from a
  value already in scope; the alternative is inventing a lookup that may not exist.
- **The slot should carry the whole platform step, including the step that looks portable.**
  `titlecase(Locale.ENGLISH)` is locale-dependent while `replaceFirstChar { it.uppercase() }` is
  not, so splitting them puts the Turkish dotless-i bug in shared code. *A step is platform work if
  its answer depends on the platform; "it is just a `String` call" is not the test.*
- **A duplicate body is a signal one copy needs a caller.** `episodeHeaderMetadata` and
  `episodeRowMeta` assembled the same string byte-identically in two files with no dependency
  between them; letting one delegate **reversed the package dependency** in the better direction.
- **A wrapper's `if` decides whether the shared body runs at all**, so collapsing it into `?:`
  silently adds a fallback the original never had. A null *from the body* is not the same event as
  a null *input*.
- **A `create`-style factory is not a reason a class cannot be portable; it is a reason the factory
  has to live somewhere else.** Make it a top-level function, never a cached object — caching
  would be a behaviour change dressed as a refactor.
- **A large file holding a small pure thing no test can reach is an *extraction*, not a move.**
  Push the portable decision out and leave the composition; the tell is a big file with a small
  pure thing at the bottom, inside something platform-shaped. Deciding which is the measurement.
- **Push the pure half of a split toward `commonMain` even when the platform half is smaller** —
  otherwise the pure half is untestable. An `R` reference blocks a file completely but usually
  blocks only a few lines, so split at the platform boundary rather than avoiding the file.
- **Check a companion object for non-factory members before deleting it.** A `private const val`
  beside a factory is a string three separate systems have to agree on.
- **A class whose constructor takes only a `Context` is a composition root wearing a class's
  clothes.** Grep the property initialisers, not the parameter list; move the wiring to the
  factory and leave the class behind it.

- **Deleting a private member whose name a same-package `internal` top-level function now carries
  needs no call-site edit.** Extracting a private member to a top-level `internal` declaration of
  the same name in the same package leaves every existing call site resolving to the top-level
  function the moment the member is deleted — the member was shadowing it, so "delete the member"
  *is* the whole change. This is the §1.1 same-package trap taken the other way, and it is worth
  looking for: it means a literal body move can be two files with no call-site churn at all. The
  corollary is that you get no compiler signal for it, so check it with
  `diff <(git show HEAD:<file>) <current>` rather than by the number of call sites you expected
  to edit.
- **Generalised: it was not one member. 36 of the double's 51 members returned `Nothing`, so
  `open` bought almost nothing.** Every one was rewritten to the interface's declared return
  type, which is scriptable off `BackendApi.kt` but has a consequence worth stating: **`Nothing`
  needed no imports, and the real types do.** The rewrite surfaced 18 unresolved names, and
  finding each one's package by `grep -rl … | head -1` picked the **wrong** `ProfileSettings` --
  there are two, `com.crispy.tv.backend` and `com.crispy.tv.domain.account`, and the interface has
  no import for it, which is how you tell that its type is same-package. **Two declarations share
  a simple name; the package that has to win is the one the *interface* imports, not the one the
  first grep hit.**
- **A `null` and a `throw` are different answers, and `runCatching { }.getOrNull()` collapses
  them silently.** `SeasonEpisodesLoader` asks for a session; the original reported "Failed to
  load episodes." when that lookup *threw* and "Sign in to load episodes." when it returned null.
  The transcription that came first used `getOrNull()` and would have told a signed-in user with
  a flaky network to sign in again — no compile error, no test failure, just a worse product.
  Keep `isFailure` and `getOrNull()` as **two** questions wherever the original answered them
  separately.
- **A cache with other readers must be shared, not moved in.** `seasonEpisodesCache` is read by
  five places in the view model other than the fetch it was extracted from; a map owned by the
  loader would have been a second cache, and the screen would have consulted the first. The first
  draft's KDoc asserted the opposite — "it belongs to this fetch, not the screen, so it moved
  with it" — and five measured readers disproved it. **A cache is a thing you share**, and the
  readers are worth counting before a KDoc reasons about ownership.

### 3. Tests

- **`build/test-results/<task>/` is not cleared when the compile fails, so it reports the
  previous run.** A `desktopTest` invocation reported `EXIT=1` with 444 tests and the
  *identical* two failures while the log's real content was
  `> Task :android:app:compileTestKotlinDesktop FAILED`. The cause was a rename of a shared
  fixture constant that missed **one** call site — the survivor lacked the trailing comma the
  other 21 had, so `grep` output showed two identical lines. **Always `rm -rf` the results
  directory before reading it**, and run `compileTestKotlinDesktop` first and read the XML only
  if that succeeded. **The tell is a failure list that does not move when the code under it
  changed** — byte-identical messages across an edit you know altered behaviour mean you are
  reading the previous run.

- **A decision no test can call is a decision no test can cover — name it in production.**
  `searchItemKey`, `watchCtaSubtext`, `selectedSeasonOrFirst`, `visibleEpisodes` and about a dozen
  others were `private` and are now `internal`. A test's own private re-implementation of a decision
  is *worse* than no test: everything about it is correct and it proves the suite, not the code.
- **A decision is not "in the composable" because it renders; it is in the composable only if it
  needs the composition.** A `when` inside a `@Composable` body is uncallable, so its arms cannot
  be covered. A condition over several values written inline is *five decisions wearing one coat* —
  as one named predicate each clause is separately testable.
- **A fixture that equals its own transformation is a vacuous assertion.** Asserting
  `"8.4"` for a rating proved the standard library, because `formatOneDecimal(8.4)` is also `"8.4"`;
  use `"10"` → `"10.0"`. Same shape: a cap case whose fixture was already sorted could not see a
  cap applied *before* the sort. **When a normaliser has an identity input, that is the input to
  avoid in the fixture meant to prove the normaliser ran.**
- **A test asserting a guard's *reason* needs the two answers to differ in exactly one respect.**
  Write down what each world would answer; if the strings are equal, the case is decoration.
- **A stub cannot be evidence about the value it replaces.** Assert on what production computed
  (`askedFor`), never on a string the stub invented.
- **Read a `data class` end to end before writing a fixture,** closing paren included. And check for
  duplicate test names with `grep -oE "fun [a-zA-Z]+\(" <file> | sort | uniq -d` — two same-named
  no-arg functions are a `Conflicting overloads` error, and the grep is one command cheaper than the
  compile.
- **`commonTest` must be JVM-free, and the purity script scans only `commonMain`,** so nothing in
  this repo would notice a `java.util.Locale` in a test. A `commonTest` backtick name **cannot
  contain a comma** — only `compileTestKotlinLinuxX64` catches it, and `:app`'s Apple test
  compilation is not built on CI at all.
- **Read test XML with `ElementTree` and iterate `testcase` → `failure` as nested elements.** A
  greedy regex pairs a *passing* test's name with the next *failing* one's message, and the result
  reads like a set of real failures that did not happen.
- **When a `getOrElse` sits on a path in production, an empty result proves nothing** until you have
  established the double answered, and for the right call number.
- **A name that excludes N and a body that does not is the same defect twice, and the fix is
  one shared constant both use.** A test called `everyRoleExceptTheAccentAndTheErrorIsAGrey`
  listed 35 roles *including* `errorContainer`, `onErrorContainer` and `inverseSurface` — and
  the rewritten version had the identical bug, iterating all 37 unfiltered. Two tests each
  spelling out the excluded set is two places to forget to update, and the failure mode is a
  test that **skips** a role rather than a test that fails. Declare the set once
  (`private val colouredRoles = setOf(...)`, `private fun isGrey(colour: Color)`) and have both
  tests read it.
- **"Is every role in this group neutral?" is only worth asserting as a *set* comparison.**
  A hand-picked list is a sample: a newly added role that quietly picked up a tint is not in
  the list and the suite passes silently. `assertEquals(colouredRoles, roles.filterValues { !isGrey(it) }.keys)`
  inverts that — it turns "a role I did not think of changed" into a failure. And **assert the
  key set before the values**: comparing keys first reports a new role as "the list changed"
  rather than as a confusing value diff. Always **name the role in the failure message** — 32
  roles in a loop with a shared message identifies nothing.
- **A `Color(0xFF141414)` literal is ARGB, not RGB.** A first pass parsed byte 0 as red and
  reported 27 of 37 palette roles non-neutral, which is why the "obvious" answer is worth
  checking against the type. Once measured correctly: 37 roles, **5** non-neutral
  (`spinner`, `error`, `errorContainer`, `onErrorContainer`, `inverseSurface`), 32 neutral —
  and **two of the five are not R > G > B**: `error` (`0xFFE8455C`) and `errorContainer`
  (`0xFFB03040`) are pink-red with blue above green, so a blanket "is it warm?" assertion
  fails on exactly the two roles it most needed to check. Measure each direction, then pin it.

- **A class too large to construct has to have its decisions extracted *before* the split, not
  after.** "Characterise before refactoring" is unfalsifiable when the class is 1,411 lines and
  needs a real player to exist: there is nothing to characterise. `PlayerSessionViewModel` had
  four pure decisions buried in it — engine selection, the status line, the pending-initial-seek
  rule, and the codec-fallback condition — and they were only nameable *after* being lifted into
  `PlayerSessionDecisions.kt`. So the extraction is not a preliminary to the split, it is the
  precondition for pinning it. Ask what a class cannot do to a test, and extract that first.
- **`org.junit.Assert.assertEquals` has no four-argument overload, and swapping expected for actual
  still compiles.** Three separate slips in one test file, in two shapes: a four-argument call
  (`message, expected, actual, "extra"`) and a three-argument call whose first argument was the
  *expected value* and second the message. The second one compiled, ran, and reported
  `expected:<[Preparing playback...]> but was:<[wrong message for IDLE]>` — a failure that reads
  as a production bug and is entirely the assertion's argument order. `:androidApp` tests use the
  JUnit order (message first), `commonTest` suites use `kotlin.test` (message last), both correct.
  When a failure message quotes your own label text as the *actual*, the assertion is misordered.
- **`assertEquals` cannot infer its type parameter when one side is `List<Subtype>` and the other
  `MutableList<Supertype>`,** and the error is `Type inference failed` — which names neither the
  assertion nor the two lists. A one-line `private fun outcomes(vararg v: SeasonEpisodesOutcome):
  List<SeasonEpisodesOutcome> = v.toList()` makes the expected side explicit and reads better than
  spelling the type argument at four call sites.
- **An assertion can describe a rule the code does not have, and the failure reads as a
  production bug.** `theCachedListIsOnlyConsultedWhenTheCurrentOneHasNoAnswer` asserted that a
  cached episode is *shadowed* by the current season's list, so it expected the current
  list's answer when both held a match. The code does the opposite: it concatenates current
  then cached and takes the **first** match, so "shadowing" would mean *dropping* the cached
  answer — and the failure was `expected:<only> but was:<other>`, a pair of plausible episode
  ids that reads as "the cache returned the wrong episode". It was entirely the assertion.
  This is the `getOrElse` rule's other face: an empty or wrong-looking result proves nothing
  until you have established **what the code's rule actually is**, and the cheapest way to
  establish it is to read the body rather than infer the rule from the method's name — the
  name here described an *intention* ("consulted only when…") while the code described an
  *order*.

### 4. Coroutines in tests

- **A manual clock with a per-read `stepMs` is the only way to make a method's two readings of
  the same poll disagree — and that is what makes the ordering testable at all.**
  `PlaybackProgressReporter.syncWatchHistory` reads `MonotonicClock` twice, once for the
  seek-settle guard and once for the persist-interval stamp, and its KDoc claims the second is
  strictly later. A fixed clock makes the two readings identical, so *any* assertion about the
  gap is vacuous. The test drives `TestClock(nowMs, stepMs = 9)` and asserts no progress at
  60 000 and progress at 60 009, which a collapse-to-one-reading fails. Whenever a KDoc claims
  two calls are not one call, ask what fixture makes them differ.

- **`advanceUntilIdle()` drives the scope the test body runs in, and `backgroundScope` is
  neither that scope nor a durable one.** A `commonTest` loader taking an **explicit**
  `CoroutineScope` got 14 of 19 tests failing with no error -- the synchronous half ran, the
  coroutine body never did, `ensureValidSessionCalls` was 0. The scope had been handed
  `backgroundScope`, which `advanceUntilIdle()` does not drive the way it drives the test
  body, and which is torn down when the test finishes -- correct for a long-lived watcher,
  wrong for a unit under test. Pass the `TestScope` itself. Note this is a *different* failure
  from the `Dispatchers.setMain` one above: there the coroutine is parked on a real
  dispatcher, here it is queued on a scope nobody drains.

- A class that builds its own `CoroutineScope` on a real dispatcher is untestable until the scope is
  injected, and `Dispatchers.setMain` is not the answer — the working combination is
  `CoroutineScope(UnconfinedTestDispatcher())` with no `advanceUntilIdle`. Give the injected scope a
  **default** of the old expression so production call sites are untouched: that is the difference
  between a testability change and a refactor.
- **`viewModelScope` captures `Dispatchers.Main` at construction**, so `setMain` must be installed
  *before* the object under test exists, or the launch simply never runs and every assertion sees an
  empty state.
- `UnconfinedTestDispatcher()` without the test scheduler owns a private scheduler, so
  `advanceUntilIdle` never drives a `Main` delay. Pass `testScheduler` explicitly.
- **`runCurrent()` runs what is already queued; it does not wake a coroutine parked in a `delay`.**
  A flush assertion that sees nothing happen is almost always this. And a test that fails before
  reaching a trailing `stop()`/`cancel()` does not report its failure — it hangs, with no XML.
- **A `SharedFlow` with no replay must be subscribed before the emission is triggered.**
- **A gate must hold exactly one call, or all of them,** to prove anything about concurrency.
- When N strategies produce byte-identical output, suspect the file did not change before suspecting
  the strategies — and when a patch's anchor assertion fails, do not re-run the tests as if it had
  applied.

### 5. Mutation drivers

Every rule in this section is stated in each driver's docstring, because a driver is run unattended.

- **Read the replacement, not the entry's `name` and `why`.** A mutation entry's prose can
  describe a mutation its `old`/`new` does not implement, and the result is a **SURVIVED**
  verdict that reads identically to a genuine suite gap. One entry was named and documented as
  removing *both* the start branch's timestamp assignment *and* its `return`, and its patch
  removed only the assignment — the anchor existed, `check_anchors()` printed `occurs 1x`,
  and it survived. The rule below says to read every replacement as code; this is the second
  instance of the same thing inside my own driver. **The tell is a comment reasoning
  carefully about an outcome its code cannot produce** — read `old`/`new` side by side with
  `name` before believing either.
- **An empty failure list is not a survivor — a survivor is the absence of a failure *after
  positive evidence that the task ran*.** A driver's first run reported `0 caught` with
  `DRIVER_EXIT=0` for all four entries because `subprocess.run(env={"JAVA_TOOL_OPTIONS": ...})`
  **replaces** the environment rather than merging it, stripping `PATH` and `JAVA_HOME`, so
  `./gradlew` never started; the log was 24 lines of `ERROR: JAVA_HOME is not set`. Use
  `env = {**os.environ, ...}` and give the driver a `NO EVIDENCE` verdict requiring
  `"BUILD SUCCESSFUL"` or `"BUILD FAILED"` in the output. **When a driver reports every entry
  surviving at once, suspect the driver before the code** — the same family as the `-q` rule
  below and the `finditer(s, re.M)` rule: the verdict survives and the evidence is lost.

- **A narrowing mutation over a key two fields populate identically is not a mutation.** The
  first entry for `DetailsMetadataLoader`'s recommendation filter was
  `distinctBy { "${it.type}:${it.id}" }` -> `distinctBy { it.id }`, on the theory that dropping
  `type` widens the key. Reading `CatalogMappings.toCatalogItem` first showed it sets
  `id = normalizedItemId` **and** `itemId = normalizedItemId` -- the same value -- so the
  narrowed key is provably identical and the entry could only ever report a **false positive
  about the suite**. The rule that generalises the existing "does it change an answer, and
  does it compile?": *read the value, not the name*. A key's name says which fields it
  mentions; only the code says which fields it reads, and two of them can be the same value.

- **`Pattern.finditer(s, re.M)` does not set a flag, and it does not raise.** On a *compiled*
  pattern the second argument is `pos`, so the integer `8` is read as a start offset; `^` can then
  never match again and the scan returns an empty list. A mutation driver that reads failure names
  this way reports **"no test failed" for a suite that failed by name** — the verdict looks
  trustworthy and the evidence is silently empty, which is the worst of the two failure modes.
  Compile with the flag (`re.compile(pattern, re.M)`) and scan with one argument. The tell is a
  driver that returns *no* evidence for mutations a hand-run catches immediately: run one by hand
  and print the task's own output before believing any regex over it.
- **Take the capture-group index from the pattern in front of you, not from another driver.**
  `AGENTS.md` records the correct index as 3 for a `FAILED`-line pattern; this driver's pattern has
  two groups (both bracket groups are non-capturing), so index 3 raised `IndexError: no such
  group` on the first run. A rule quoted from a sibling file is a claim about that file.
- **Never pass `-q`.** It suppresses Gradle's per-test `FAILED` lines, so every entry reports "build
  failed" instead of a name: the *verdict* survives and the *evidence* does not, and a run reporting
  no test names is indistinguishable from one whose mutations did not compile.
- **A test-filtered task only observes the source sets it compiles.** `:android:app:desktopTest`
  builds the desktop target, so a mutation in `androidMain` cannot fail under it *no matter what it
  does* — including `abstract` on a class that then fails to compile at its only construction site.
  `:app`'s other compilation, `:android:app:testAndroidHostTest`, compiles `androidMain` as well.
  **"This cannot be observed" is only ever a claim about the task you ran, so the fix is a different
  task, not a deleted entry.**
- **A branch inside a `@Composable` body is the one thing that really cannot be observed**, and it is
  a different answer from "wrong task": it needs a rendering harness, and the goldens in
  `:android:androidApp` do not render every screen. Ask whether the branch needs the composition,
  not whether a test exists. The decision worth testing there is the *caller's*.
- **A port's implementation is the file most worth covering**, because it is the one a `commonTest`
  suite cannot reach at all — and an implementation behind a port created in the *same* landing is
  exactly where the gap bites.
- **Read every replacement as code before writing the entry: does it change an answer, and does it
  compile?** Nine bad-patch shapes have been caught, and all nine look like competent edits — a
  default *parameter*; a default value on a **data-class constructor property** (the most
  deceptive, because the diff looks like a behaviour change); `?: return null` in an expression body;
  a `?: ""` arm on an `if/else` expression body; a repeated declaration prefix; `x?.y?.z().w()`
  (a `?.` chain covers only the *immediately following* call); a rewrite that is *identical* to the
  original because the value already was (`raw.length` → `code.trim().length` when `raw` **is**
  `code.trim()`); renaming `runCatching` to `run`, which is not a Kotlin function; and appending a
  comment or `+ 0L`. `COMPILE FAILED` is not evidence.
- **Run a `check_anchors()` pre-flight before the first write.** Four entries once reported
  `anchor occurs 0x` because three functions wrapped a `?:` onto a second line and the anchors were
  written single-line. Cost one run, instead of four entries reported as "no test failed" against
  code that was never altered. Print `SKIP … anchor occurs Nx` and never count it as a pass; on a 0×,
  grep the file — **zero occurrences means the code is gone, some occurrences means your anchor is
  wrong.**
- **Restore in a `finally` with a printed `restored:` line**, end with
  `if __name__ == "__main__": main()` (a driver executes on import otherwise), and print the tally
  `of len(selected)` so a narrowed `RECHECK` run cannot be mistaken for a full one.
- **Derive a set of *names* by subtracting *names*, and a suite that pins values must say which
  values it cannot tell apart.** `CrispyTvDarkColorsMappingTest` found both halves of this on its
  first run. It computed the unmapped palette roles as `allPaletteRoles - mappedRoles.values.toSet()`
  — a subtraction by **value**, because the map happened to be keyed by palette role and valued by
  colour. **Ten of `CrispyPalette`'s 37 roles carry the identical value `0xFFFFFFFF`**, and
  `surfaceContainer` shares `0xFF1F1F1F` with the mapped `surface`, so a value-based difference
  **deleted the very roles the assertion was about and passed for the wrong reason.** *A value
  hiding a name is the same shape as `:tv`'s dead `CrispySpinner` duplicate — an identical
  declaration reachable with no diff.* Subtract by the key, never the value, whenever the key is
  the thing you mean. And the second half: because so many roles share a value, **no value
  assertion can tell them apart**, so the suite names the interchangeable set explicitly
  (`theTenRolesThatShareWhiteAreNamedRatherThanAssumed`) instead of letting a green run imply each
  role is wired to the right palette entry.
- **A test's own non-vacuity gate is where a suite learns its limit, and the limit belongs in the
  assertion, not in a comment.** `noMappedRoleCoincidesWithTheTvLibrarysOwnDefault` asserts that no
  mapped role carries the value `androidx.tv.material3` would have defaulted to — without it, a
  suite that forgot to pass a role at all would be green, because the constructed object would hold
  the default. It failed on the first run and found its own limit: **`scrim` is the one mapped role
  whose value equals the library's own default** (`0xFF000000`, and the TV library defaults to
  opaque black), so **no assertion can tell a mapped `scrim` from a dropped one.** It was rewritten
  to assert the collision set is *exactly* `setOf("scrim")`, so a second collision fails instead of
  being absorbed into the list — **the difference between documenting a limit and ignoring one.** The
  suite pins 28 of 29 roles' wiring and its own gate says which one it cannot, which is a better
  claim than a green suite that implies 29. **When a test cannot cover one case, assert the set of
  uncovered cases**, so the count is checked and a reader is not left to assume.
- **A surviving mutation is a claim about the code, so check it by hand.** Read the callee. If the
  guard is genuinely unreachable, keep it and write the measurement **at the guard** — fourteen so
  far, in three files, the last two of them *pairs in one file*, which is the tell. If it is
  reachable, the test was missing. **Fifteen now**, in four files. **Sixteen**, in five.
  The sixteenth is `PlaybackProgressReporter.syncWatchHistory`'s trailing `return` in the
  start branch, and it is the first one where **the guard and the assignment above it both
  answer the same question and neither is visible in the other's KDoc** — deleting the
  `return` alone changes no answer, because the fall-through below is ended twice over, once
  by `hasReportedPlaybackStart` being set and once by the delta being 0. Deleting *both* is
  caught, and the test that catches it had to be written first: a poll with the clock already
  past `PROGRESS_SYNC_INTERVAL_MS`, so the interval guard is not what ends the fall-through.
  **Two lines that look redundant and together cover one rule is the shape to watch for
  here** — ask which of them a reader would delete if the other were gone.
- **The `[desktop]` suffix on a Gradle `FAILED` line is not always there, so a regex that requires
  it silently extracts no evidence.** `desktopTest` prints `Class[desktop] > method FAILED`; the
  host task prints `Class > method FAILED` with no bracket. Six androidMain entries were caught
  correctly and credited to nothing — every one read as a bare `build failed` — until a single
  hand-run showed the format. The `-q` rule is the same failure one level up: the *verdict* stays
  trustworthy and the *evidence* is what you lose, so **print the task's own failure lines once
  before trusting the regex that reads them.**

- **Truncate the driver's log per entry, not once per run.** Appending across entries makes
  every entry report *all* earlier failures as its evidence, so a name this entry expects that
  happened to fail under an earlier mutation is scored as a catch. The five accumulated lists
  looked like stronger evidence than the honest five minimal ones — a longer failure list is not
  a more specific one. **Check that a caught entry's failure list is close to its `expect` set**;
  a superset means the log is shared.
- **`--tests` names a *class*, and a filter that matches nothing fails the task in a shape a
  mutation driver reads as a survivor.** `scripts/mutate_player.py` passed
  `--tests com.crispy.tv.player.<methodName>` to narrow each entry. Gradle resolves the filter
  as a class pattern, matches no class, and fails with "No tests found for given includes" — a
  `BUILD FAILED` carrying **no `e:` line and no per-test `FAILED` line**. A driver deciding
  `CAUGHT`/`SURVIVED` by reading failure names then scored **19 of 23 entries SURVIVED** with
  `no_evidence=0` and `compile_failed=0`, which reads as a suite in trouble. **The tell was that
  all four caught entries were the only four with *two* expected failures** — the four the
  unfiltered path happened to cover; when N of your entries share a code path and only the
  others report, the difference in the path is the bug. The suite was correct: a hand-run of
  the first entry failed exactly as the suite claims, in the documented
  `Class[desktop] > method FAILED` shape. **Never pass a method name to `--tests`, and treat
  "many survivors, no compile errors" as a driver verdict before it is a suite verdict** — this
  is the `NO EVIDENCE` rule's blind spot, because a filter that matches nothing *does* produce
  evidence, it just produces the wrong kind. **The next bullet is the same trap with one entry
  instead of twenty-three, and it survived this fix because a tally of 22 caught and 1 survived
  looks reasonable rather than alarming** — which is the real lesson: a driver verdict is not
  evidence of anything until you have read a failure name.
- **Two more ways a driver invents a survivor, and one of them is a *stale `expect` list* — which
  looks exactly like a genuine survivor and is invisible in the tally.** Entry 21 of
  `scripts/mutate_player.py` (`CoreDomainMetadataLabResolver`'s `addonLookupId`) survived four
  consecutive runs, and the causes were **three different things, one per run**:
  1. **A real suite gap** — nothing asserted that line at all, because the one nearby test drove
     `DefaultMetadataLabResolver` instead. Fixed by adding the case.
  2. **A `FROM-CACHE` test task.** Without `--no-build-cache`, Gradle served
     `:android:player:desktopTest` **`FROM-CACHE`** and left `compileKotlinDesktop`
     `UP-TO-DATE`, so the mutated source was **never compiled**; the task reported
     `BUILD SUCCESSFUL in 1s` and the driver wrote a confident verdict about code it had not
     built. The `NO EVIDENCE` rule cannot catch this — the task *did* report a verdict — so the
     guard has to be a text match on `> Task :…:desktopTest (FROM-CACHE|UP-TO-DATE)`. **A
     one-second green from a task you just perturbed is not a result.**
  3. **A stale `expect` list, after the suite was already correct.** The fix in (1) added a
     better test, but the entry still named the *old* one, so the driver's substring test found
     no hit and reported `SURVIVED` — while printing the failing test's name in plain sight on
     the `(failures seen: […])` line. **When an entry names the case you just replaced, or a
     hand-run shows the suite catching something the driver calls a survivor, the driver's
     expectation is what is stale.** Read the `(failures seen: …)` line before writing any
     code: it is the cheapest evidence in the whole workflow, and a `SURVIVED` verdict printed
     directly above a correct failure name is a self-evident bug. Generalising all three: *the
     verdict was sound every time and the evidence was about a different run* — which is the
     same shape as the log-truncation rule above, and the reason "no test failed" is a finding
     to investigate rather than a result to record.
- **A surviving mutation is not the only way a case can fail to be the one doing the work — a
  *caught* one can too, and the tell is that the failure list does not contain the case you
  would have named.** `scripts/mutate_network.py` entry 13 drops `?` from the pattern's
  `[?&]v=`, leaving `&v=`, and the driver reported `SURVIVED` for
  `theQueryParameterFormIsReadWithOrWithoutTheAmpersand`. The `(failures seen: […])` line
  printed **three other cases** and not that one — and reading them showed the named case's
  body asserted **only the `&` form**, while its *name* claimed both. The mutation was caught;
  the case that names the rule simply did not write the rule down. This is the mirror of the
  stale-`expect` bullet above and the same lesson from the other direction: **a caught
  mutation is evidence that *some* test caught it, not that the test you would have pointed at
  is the one that did.** So the `expect` set and the `(failures seen: […])` list are two
  different claims, and when they disagree the right move is to open the test the mutation
  actually broke and ask whether its name overstates its body. Overstated names are a defect
  in their own right — a reader trusts the name and skips the body.
- **A guard can be *masked* by a neighbouring condition, and masking is indistinguishable from
  absence until you write the input that reaches the guard and fails only there.**
  `extractYouTubeVideoId`'s `contains("youtu", ignoreCase = true)` gate looks redundant. It is
  not: drop `ignoreCase = true` and the mutation survives — because the *obvious* fixture, an
  uppercase `YOUTU.BE/dQw4w9WgXcQ`, passes the gate, then fails the lowercase-only
  `youtu\.be/` alternative, then falls through to the `?: trimmed` fallback, and **that is the
  same string a case-sensitive gate would have returned anyway**. Both worlds answer alike, so
  the case is decoration. The fixture that separates them is an uppercase host carrying a
  **lowercase** `v=` or `/embed/` — `https://YOUTU.com/watch?v=abcdefghijk` returns
  `"abcdefghijk"` with the guard and the whole URL without it. **The sixteen "redundant guard"
  findings in this file are all claims that a guard has no effect; this is the other side of
  that, and it is worse to miss, because the mutation result looks like agreement.** When a
  guard's own fixture returns a value that a *fallback* could also produce, the guard is
  untested rather than redundant — write down what each world would answer, and if the strings
  are equal the case is decoration. The `parseLookupId` arity/numeric pair below is the same
  shape, and the discriminator there was a purely numeric `"5:7"`; here it is a lower-case
  alternative reached through an upper-case gate.
- **A test whose name states a rule its body does not check is a hole shaped like coverage.**
  `theQueryParameterFormIsReadWithOrWithoutTheAmpersand` covered only the `&` form, so the
  `?` half of the character class was unasserted *by the case that names it* — the other
  cases caught the mutation, so nothing was red, and the name was the only thing wrong. This
  is the generalisation of the "a test that samples the cases instead of enumerating them
  tests the sample" rule from the other end: there the body was narrower than the intent,
  here the *name* is wider than the body. **When a name says "with and without", "either",
  or "every", the body has to enumerate both halves** — and a mutation caught by some *other*
  case is not evidence that this one does its job.
- **A survivor can mean the *case* was caught by the wrong one of two conditions defending the
  same rule — and then the guard was fine and the test was not.** `parseLookupId`'s arity
  check (`parts.size >= 3`) and its numeric check (`toIntOrNull()` and `> 0`) both defend
  "a season/episode pair is only read from a three-part id". Loosening the arity to `>= 2`
  left the case **unobservable**: my input `"tt1234:5"` makes `parts[lastIndex-1]` = `"tt1234"`,
  whose `toIntOrNull()` is null, so the *numeric* guard caught it and the answer did not move.
  The discriminator is the input that **reaches the guard you meant and fails only there** —
  a purely numeric pair, `"5:7"`, which with the arity loosened claims season 5, episode 7 and
  an **empty** base id. This is the redundant-guard finding seen from the other side: those
  sixteen say a guard has no effect, and this says **a guard can have an effect that a
  neighbouring condition is masking**, which is indistinguishable from "no effect" from the
  outside. When a survivor turns out to be defended by a sibling condition, write the second
  case before deciding the guard is or is not real — and say in the test's comment *why the
  case exists*, because nothing about it looks load-bearing.

### 6. Editing safely

- **Never rewrite source with a regex.** It cannot see inside comments or string literals; a
  "sound" cleanup rule once self-matched on its own package and deleted 140 live imports. For a
  mechanical change, let the compiler find the sites, or parse properly — the character-scanner lexer
  in `scripts/verify_kmp_outputs.py` is the model.
- **A python patch must assert each anchor exactly once,** and **read the region back** after a
  positional or structural patch: a count assertion does not check the brackets around what it
  replaced, and only `diff` sees a four-space shift.
- **Inserting a declaration between an annotation and its target silently re-targets the
  annotation**, and the main compile will not catch it — a `@Composable` function returning `String?`
  is legal. It surfaces in the *test* compilation as "must be marked @Composable" on the test method
  plus an error at every call site. **When every error is that, the annotation is misplaced, not the
  calls.** Verify by counting pairs against the original.
- **Extracting a decision out of a `val` can destroy a smart cast the branch depended on.** The fix
  is a safe call that is behaviourally identical, and the compiler is right to complain.
- **A "no forbidden token remains" guard must be scoped to code, not prose** — you will write about
  the token in the sentence that documents its removal.
- **`git mv` preserves mtime**, so Gradle's incremental Kotlin compile can skip a moved file and
  report success with no class produced. Compile a moved file once with `--rerun-tasks`.
- **After any revert, check `git status --short` for ` D` in the index** and compile *both* source
  sets; a revert that only compiles the source set you moved *from* proves nothing, and the index
  half is the part people forget.
- **A file's first line is not its `package` line.** A `@file:OptIn(...)` or `@file:JvmName`
  annotation may come first, so a patch that rewrites the region above the package declaration
  eats the package statement and leaves the import pasted onto it — `import com.crispy.tv.ui.theme.CrispyPalettepackage com.crispy.tv.playerui`.
  **The signature is one distinct unresolved name, `CrispyPalettepackage`, inside a 328-error
  cascade**; find that mangled name before reading the cascade, and locate the package line with
  `len(re.findall(r"^package com\.crispy\.tv\.\S+$", s, re.M)) == 1` rather than indexing line 0.
- **`open(path, "w")` truncates before the argument is evaluated.** A single-expression rewrite
  that computes its new content *inside* the `write()` call can raise — on a bad anchor, a
  `ValueError: substring not found` — **after** the file has already been emptied. One such
  patch destroyed a 368-line file and the exception named the patch, not the file. Build a
  `results` dict of every new content **first**, and only then open any file for writing; that
  ordering also means an assertion failure on the seventh file leaves the first six untouched.

- **A count you assert while patching is a claim about the code, and the code is the only
  thing that settles it.** A patch asserting the episode guard occurs "4 times" aborted with
  `AssertionError: 5` and wrote nothing — the guard was five-armed, and the "four times, three
  with the same message" figure in the KDoc and in the plan was wrong for the same reason. The
  assertion did its job; the number was the error.

## Compose resources (Phase 4 Step 1)

The 99 design drawables, the 12 genre rasters, the Archivo font, and `CrispyBrand`/`CrispyIntroSplash` now live in `:android:sharedUI` as `composeResources`. `:ui-assets` keeps only what Compose Multiplatform cannot carry. **These four facts are the expensive part; each was learned the hard way and the cheap-looking alternative is wrong.**

- **Every accessor needs its own import.** `Res.drawable.ic_search` is a *top-level extension property* on `Res.drawable`, not a member. `import com.crispy.tv.ui.resources.Res` alone brings in the nested `object drawable` and nothing else, so `Res` and `Res.drawable` resolve while every accessor reads `Unresolved reference`. That exact symptom was misdiagnosed here as a CMP limitation, and cost an hour and a revert. Look it up before concluding anything.
- **`publicResClass = true` is the supported switch, and the accessors come out `public`.** `Res` is `internal` by default. Set it in `:sharedUI/build.gradle.kts` alongside `packageOfResClass = "com.crispy.tv.ui.resources"` — the default would be `crispy_rewrite.android.sharedui.generated.resources`, which leaks the Gradle coordinates into every import. Do **not** use the `doLast` block that rewrites `internal object Res` in the generated file; upstream labels it a temporary workaround and it patches generated output. In CMP 1.11.1 the generated accessors are `public val Res.drawable.x`, so cross-module use needs nothing but the import.
- **`org.jetbrains.compose.resources.painterResource` replaces `androidx.compose.ui.res.painterResource` wherever a drawable is involved.** They are different overloads: the Android one takes an `Int`, the CMP one a `DrawableResource`. The same applies to `Font` (`org.jetbrains.compose.resources.Font` takes a `FontResource`). `Font(FontResource)` is **`@Composable`** — it reads the resource bytes during composition — so a top-level `val FontFamily(Font(Res.font.x))` no longer compiles and needs a composable property getter. `PlayerOverlayControls.kt` keeps the *Android* import too: it is the one place still passing an Android drawable, `com.crispy.tv.app.R.drawable.ic_player_aspect_ratio`, which stays in `:app`'s own `res/`.
- **Deciding which import a file needs is not a textual problem, so let the compiler decide.** A file can pass a `DrawableResource` to `painterResource` while never naming `Res.` — it receives the value as a parameter, exactly as `ItemActionSheet` and `SidebarNavigation` do. No source-level rule distinguishes that from a genuine `Int`, and a rule that guesses wrong leaves a file that either does not compile or silently keeps a dead import. Loop on the compiler's `actual type is 'DrawableResource', but 'Int' was expected` and add the import to exactly the files it blames.

**What stayed in `:ui-assets`, and why — do not "finish" the move by dragging these across:**

| Asset | Why it cannot be `composeResources` |
|---|---|
| 40 launcher mipmaps | referenced from two `AndroidManifest.xml` files and one notification icon; a manifest names an Android resource, never a composeResources one. Each app should own its own icon. |
| `splash_mark`, `splash_mark_animated` | named by `windowSplashScreenAnimatedIcon` in the `:androidApp` and `:tv` themes. An XML theme can only name an Android drawable. |
| `splash_background` colour | `windowSplashScreenBackground` in the same themes. |
| 9 provider-logo SVGs | **CMP documents SVG as "supported on all platforms except Android"**, so `painterResource(Res.drawable.trakt)` would fail silently on the platform that matters most. These are `@RawRes Int?` passed to Coil. The portable route is `composeResources/files` + `Res.readBytes(path)`, but Coil then needs an SVG decoder. This is an open design decision with product consequences, not a rename. |

`ic_star` was deleted: it had zero references (`ic_star_filled` is the one in use) and the Kotlin-only reference count is what proved it. Note that a Kotlin-only count is also what *nearly* proved `splash_mark_animated` dead — it is referenced from XML, which that count cannot see.

`:ui-assets` no longer needs `kotlin.compose`, the Compose dependencies, or `missingDimensionStrategy("distribution", "sideload")`. All three were removed and the whole graph still resolves; the flavour axis was never the constraint it appeared to be.

GitHub Actions (`.github/workflows/`), all `workflow_dispatch`-only by deliberate choice:
- `android.yml` — verification for the app (both flavors) and the TV app: purity gate, fixtures, contract + domain + plugins tests, golden screenshots, lint, assemble, and the dex-level distribution assertion.
- `apple.yml` — the only place Apple can be verified: compiles the KMP Apple targets, runs their simulator tests, the Swift contract suite, and the iOS/tvOS Xcode gate.
- `android-release.yml` — signed release artefacts. Separate from `android.yml` because it alone needs the release signing secrets.
- `apple-release.yml` — unsigned sideloading IPA.
- **`kotlin.srcDir(...)` must be given a TaskProvider, never a directory.** `layout.buildDirectory.dir("...")` is a `Provider<Directory>` and carries no task information, so nothing orders the generating task before compilation: the task silently never runs, and the build only succeeds if a *previous* run left the generated files behind. This bit `:android:platform-core`'s `generateAppConfig`, so `AppConfig` was unresolved on every clean machine and fine locally for weeks. `kotlin.srcDir(generateAppConfig)` registers the task's outputs as the source dir and infers the dependency. All four generator tasks are now wired this way or with an explicit `dependsOn`; when adding a fifth, use the TaskProvider.
- **Workflow YAML must parse with no duplicate keys** (`scripts/validate_workflows.py`, run first in `check-local.sh` and as the first step of every workflow). A duplicate key makes a workflow fail to *load*, which produces a run that fails in under a second with an empty log. The symptom reads as "the tests failed" when no test ever ran, and nothing inside the workflow can report it, because it never started. This happened: two `run:` lines under one step in `android.yml` and `android-release.yml` produced two instant-failure runs and no test output. `yaml.safe_load` takes the last value for a repeated key and reports nothing, so the validator loads through a constructor that raises on the second occurrence, at any depth.
- **A class file in the output can outlive the declaration that produced it, and the cause is not established.** During the extraction of the 52 backend response types, `CrispyBackendClient$ResponsiveImageSet.class` sat in `build/classes/kotlin` with a timestamp seven hours older than the change that removed the nested type, and a `clean` followed by a normal build left it visible for a while. A stale class binds a reference that should have failed to compile, so a later green build certifies nothing and **every other gate in this repository becomes unreliable**. That risk is real even though the mechanism is unexplained. **The two obvious explanations were tested and are both false**, so do not repeat them as if they were established: (1) removing a nested declaration from a file and recompiling incrementally deletes its class correctly, and (2) `git mv`-ing a file between source sets with mtime preserved did not cause Gradle to skip it — the task reran and produced the class. `scripts/verify_kmp_outputs.py` therefore exists as a *detector* rather than a fix: it asserts no compiled class has no source declaration, and it is proven by injecting both a top-level orphan and a nested one. It runs at the end of `check-local.sh` and in `android.yml`; it reads `build/classes/kotlin`, so it must run *after* the compile tasks. A CI runner is always clean, which is why the check belongs to the local gate. If you ever see this fire on an artefact you did not inject, that is the observation that explains it — capture it rather than reaching for a mechanism.
- **The detector has to know how Kotlin names a file facade when the file name contains a dot.** It infers `<FileName>Kt` from the source stem, and the compose-resources plugin names its generated accessors `Drawable0.commonMain.kt`, whose facade is `Drawable0_commonMainKt`. Using the stem verbatim reported all 111 migrated resources as orphans — indistinguishable from the stale-output breakage the gate exists to catch, on a tree that was entirely clean. **A safety gate that fires on correct code gets switched off, so keep its notion of "correct" in step with the code generators it reads.** Re-prove it after any change by injecting all three orphan shapes: a top-level class, a nested class whose owner does not name it, and a facade whose name contains a dot.
- **Never rewrite source with a regex.** A regex applied to source files cannot see inside comments or string literals, so it silently corrupts or deletes code. This cost a full revert during the backend extraction: a "sound" cleanup rule matched any `com.crispy.tv.backend.X` import, self-matched on the import's own package, and deleted 140 live imports; a separate heuristic matched `User` inside the string `"User-Agent"` and added eight phantom imports. Neither failed loudly — the compiler did, which is the only reason the damage was caught. For a mechanical change, either let the compiler find the affected sites and fix them by hand, or parse properly (see the lexer in `scripts/verify_kmp_outputs.py` for what "properly" means: a character scanner, not a substitution chain). A regex is fine for *matching* a single line you then read; it is not fine for *rewriting*.
- Names are platform + intent, not Gradle build type. Do not reintroduce `debug`/`release` into workflow names; "debug CI" and "debug build" are different things.
- `verify_apk_distribution.py` reads the **dex** and is only valid on unminified builds, so it runs in `android.yml` (debug) and not in `android-release.yml`. Release asserts via `verifyDistributionExclusions`, which reads the dependency graph and is minification-proof.
- **Flavors live only in `:androidApp`, and that is not negotiable.** AGP's `com.android.kotlin.multiplatform.library` has **no** `productFlavors` at all (unlike `com.android.library`/`com.android.application`), and a KMP library cannot even *consume* a flavored `com.android.library`: the library plugin is single-variant, so it states no preference between a dependency's `store*` and `sideload*` variants and Gradle fails with an ambiguous-variant error naming every candidate. Two modules were forced off that axis by this and both losses turned out to be dead weight: `:android:network` (the YouTube extractor, now the sideload-only module `:android:youtube-extractor` reached through the `TrailerExtractor` interface) and `:android:plugins`, whose entire `store` source set was one unread `internal val PluginsRuntimeSupported = false` that was never even compiled. Do not reintroduce `matchingFallbacks` to paper over this — it would compile `:app` against the store variant while a sideload APK shipped the sideload one, the same class of lie as the unwired torrent resolver fixed in `911f8d75`.
- Distribution is a permanent two-flavor axis: `store` (Play/App Store) and `sideload` (APK/IPA). Optional engines are excluded **structurally** — the torrent engine, the QuickJS plugin runtime and the YouTube extractor are separate modules that only `sideload` depends on. Never reintroduce a null-returning stub for something the store build should simply not contain. Two guards enforce this: `./gradlew :android:androidApp:verifyDistributionExclusions` reads the resolved dependency graph, and `scripts/verify_apk_distribution.py` reads the built dex and asserts in both directions.
- The flavour axis does not stop the migration; it moves with the entry point. `:app` *is* now a flavor-less KMP library, because the KMP plugin cannot carry `productFlavors` while the flavour axis is a product requirement. `:androidApp` is the `com.android.application` that declares them, and `:app` reaches the variant through the `DistributionComponents` seam rather than through source sets.
- Apple targets are declared but cannot compile on Linux. Never run aggregate tasks (`build`, `check`, `allTests`); they reach the Kotlin/Native targets and fail. Use `./check-local.sh` or targeted tasks.
- **A plain `com.android.library` cannot be consumed from a KMP `commonMain` at all.** Only a `com.android.kotlin.multiplatform.library` publishes a JVM variant. `:android:native-engine` and `:ui-assets` are both plain libraries, so neither can be a `commonMain` dependency — the same wall that stopped `:ui-assets` being used from a shared module. Check `plugins { }` in the module's `build file` before adding it to a common source set's `dependencies`; the compiler error is an ambiguous-variant listing with no hint that the cause is the module *type*.
- **`git mv` preserves mtime, so Gradle's incremental Kotlin compile can skip a moved file and report `BUILD SUCCESSFUL` with no class produced.** This bit the move of `PlayerStreamHandoff` back from `commonMain` to `androidMain`: the class file was already in `build/classes/kotlin/android/main`, so the compile reported failure while the output was correct-looking, and the errors (`Unresolved reference` in three files, plus `Destructuring of type 'Any'` cascades) read as a missing declaration. **Always run a moved file's compilation with `--rerun-tasks` once, and treat any `Unresolved reference` for a class you can see in `build/classes` as this.** The `verify_kmp_outputs.py` gate is the detector, not a fix.
- **A revert that only compiles the source set you moved *from* proves nothing about the source set you moved *to*, and a partial revert looks exactly like a completed one.** Moving two `PagingSource`s out of `androidMain` failed, so they were reverted; `git status` showed them as staged deletions afterwards, because `git reset -q HEAD <dest>` resets the *destination* path and the file never came back. `compileKotlinDesktop` was green the whole time, because a file only referenced from `androidMain` is not on the desktop compile path — the breakage was invisible to the one command I ran. `:app:compileAndroidMain` then failed with 20 errors, the largest being `Unresolved reference 'LibrarySectionPageUi'` for a type declared in a file I had deleted. **After any revert, run `git status --short` and look for `D ` in the index, and compile *both* source sets.** `git restore --staged --worktree <path>` is the reliable restore; the index is the part people forget.

Apple:
- `ios/ContractRunner`: SwiftPM contract runner (mirrors `android/core-domain` behavior)
- `ios/project.yml`: XcodeGen spec for placeholder iOS/tvOS apps (compile gate)

Contracts:
- `contracts/SPEC.md`: source of truth for heuristics + deterministic rules
- Fixtures are **compiled in, not read from disk**. `generateContractFixtures` in
  `android/core-domain/build.gradle.kts` turns `contracts/fixtures/**/*.json`
  into a Kotlin source file under `build/generated/`, wired as a source directory
  of `commonTest`. Do not "simplify" this back to `java.nio.file` or okio: the
  suite is in `commonTest` so it runs on Android, desktop and the Apple targets,
  and there is no single path to the fixtures that is correct on all of them. The
  old `:android:contract-tests` module worked around that by walking up from the
  working directory looking for `settings.gradle.kts`.
- The generator sorts by relative path, so identical inputs give a byte-identical
  file and there is no spurious diff between machines.
- `scripts/validate_contracts.py` stays the authority on fixture *validity* (JSON
  Schemas). The generated file is only about getting the bytes readable on any
  target. `ContractFixturesSanityTest` is the guard that the fixtures actually
  reached the compilation, which Python cannot check.
- `compileTestKotlinLinuxX64` is the gate that proves `commonTest` holds no JVM
  API. The import-based purity gate only scans `commonMain`, so it cannot see a
  `java.nio.file` in test code, and the Apple targets would fail on macOS instead.
- `contracts/fixtures/` + `contracts/schemas/`: versioned JSON fixtures + schemas

## Configuration / Secrets

- Android app reads Gradle properties and injects them into `BuildConfig`; in CI these come from `ORG_GRADLE_PROJECT_*`.
- Do not commit secrets; use `~/.gradle/gradle.properties` for Trakt/Simkl ids+redirect URIs, `SUPABASE_URL`, `SUPABASE_PUBLISHABLE_KEY`, and `CRISPY_BACKEND_URL`.
- GitHub Android workflows pass the same values through `ORG_GRADLE_PROJECT_*`; set repository secret `CRISPY_BACKEND_URL` so CI builds embed the backend base URL in `BuildConfig.CRISPY_BACKEND_URL`.
- Signing: release uses `RELEASE_KEYSTORE_*` if present; otherwise debug signing. Debug can be overridden via `DEBUG_KEYSTORE_*`.
- Daemon memory/lifetime (heap, metaspace, idle timeouts) is tuned in `~/.gradle/gradle.properties`, which overrides this repo's `org.gradle.jvmargs`; that is deliberate, so leave the committed `-Xmx4g` alone.

## Code Style

General:
- Contracts drive behavior. If behavior changes, update `contracts/SPEC.md`, fixtures/schemas, Kotlin contract tests, and Swift ContractRunner.
- Determinism: pass `nowMs`/clock; inject seeded RNG; keep output ordering canonical.
- Gradle repos: `settings.gradle.kts` enforces `repositoriesMode = FAIL_ON_PROJECT_REPOS`; do not add repos in module `build.gradle.kts`.

Code quality principles:
- Keep solutions simple: follow KISS, DRY, and YAGNI.
- Refactor existing logic instead of layering new code on top.
- Remove obsolete, unused, or commented-out code.
- Keep functions focused on a single responsibility.
- Avoid premature abstractions; solve the immediate problem first.
- Prefer self-documenting names over comments.
- Reuse existing utilities before creating new ones.

Contracts:
- Fixtures include `contract_version`, `suite`, `case_id` (and `now_ms` when specified).
- Suites currently covered include: `player_machine`, `continue_watching`, `sync_planner`, `storage_v1`, `player_progress`.
- If you change behavior: bump `contract_version` (per SPEC), update fixtures/schemas, and keep Kotlin + Swift implementations in lockstep.

Kotlin (Android + JVM):
- Formatting: official Kotlin style (`kotlin.code.style=official`); 4-space indent.
- Imports: no wildcard imports; group stdlib -> Android/AndroidX -> third-party -> internal.
- Types: use nullability for optional values; normalize early (trim; treat blank as missing).
- Naming: `UpperCamelCase` types, `lowerCamelCase` functions/vars, `SCREAMING_SNAKE_CASE` consts; prefer contract domain terms.
- Architecture: keep pure rules in `android/core-domain` (no Android types/IO); use immutable state + reducers where it fits.
- Errors: no exceptions for normal control flow in domain; return explicit results; preserve coroutine cancellation (don’t swallow `CancellationException`).

Kotlin details (common patterns in this repo):
- Model actions/events as `sealed interface` + `data class`/`data object`.
- Prefer explicit mapping helpers for contract string values (canonical casing/format).
- Avoid nondeterminism: no `System.currentTimeMillis()` in domain; no iteration over unordered maps when output order matters.
- When enriching/merging metadata, keep precedence stable (server-first; fill missing only).

Swift (ContractRunner + placeholders):
- Keep APIs small and explicit; prefer `struct`.
- Imports: minimal; `Foundation` first.
- Fixture/test parsing: use `guard` + descriptive thrown errors; avoid force unwraps.
- Determinism: do not read system time directly; mirror contract heuristics exactly.

Swift details:
- Parse JSON fixtures into dictionaries/structs with explicit required/optional helpers; throw `LocalizedError` with fixture name and missing key.
- Keep output stable (ordering, tie-breakers) to match fixture expectations.

Python (tooling):
- Hermetic, deterministic scripts; non-zero exit on failure; errors include fixture path + JSON location.

## Single-change checklist

- `python3 scripts/validate_contracts.py`
- `python3 scripts/validate_workflows.py`
- `./gradlew :android:core-domain:desktopTest :android:core-domain:testAndroidHostTest`
- `./gradlew :android:app:testAndroidHostTest` (the composition root; this is the gate before touching `PlaybackDependencies`, `AppDistribution` or the two service providers)
- `./gradlew :android:app:desktopTest` (`:app`'s `commonTest`; the gate before touching the settings repositories, the date formatting, or any other `commonMain` file in `:app`)
- `./gradlew :android:backend:desktopTest` (the port tests; the gate before changing `BackendApi` or `AccountApi`, since `UnusedBackendApi` fails to compile on a new member)
- `./gradlew :android:home:desktopTest :android:home:testAndroidHostTest` (`:home`'s moved home services; the gate before touching `CalendarService` or `UpNextService`)
- `python3 scripts/verify_kmp_outputs.py` (after any compile; catches a stale class a green build cannot)
- `swift test --package-path ios/ContractRunner` (if Swift logic touched)
- Ensure `:android:tv` and tvOS placeholder builds still compile
