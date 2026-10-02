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
  `:android:desktopApp` entries under *Project Layout* are where that gets answered. **Two
  consequences that a migration plans hit in this exact order.** **A file whose receiver is
  pinned cannot be freed by changing its arguments** — 39 of `CrispyBackendParsers.kt`'s 45
  functions are `internal fun CrispyBackendClient.parseX(json: JSONObject)`, so the pin rides in
  on the *extension receiver*, and a `JsonElement` parameter would have changed nothing about
  where the function lives. **And a consumer that cannot move makes its helpers worth nothing to
  move either**: `:app`'s two JSON accessor files are pure, with zero `android` imports and one
  consumer each, and porting them moves zero files, because the consumers are
  `ProfileDataShadowStore` (pinned by `getSharedPreferences`) and `LibraryDiskCacheStore` (60+
  `org.json` touchpoints plus `java.io.File` and `MessageDigest`). **Both of those pins are
  gone — `LibraryDiskCacheStore` is `commonMain` now (okio plus `ByteString.sha256()`) — so this
  sentence is a record of the pin, not a description of the wall, and a reader who takes it for
  the latter goes to re-audit a wall that no longer exists.** The same is true one clause down.
  `CrispyBackendClient` read as nearly free to port because it spoke OkHttp in only three places
  and never touched `OkHttpClient` at all, and what pinned it was
  `CrispyHttpResponse(val url: HttpUrl, …, val headers: Headers, …)` naming OkHttp in its own
  **constructor** — **and that transport has since been ported too.**
  `android/network/src/commonMain/…/CrispyHttpClient.kt` declares
  `data class CrispyHttpResponse(val code: Int, val body: String)` and
  `data class HttpRequest(val method, val url: String, val headers: Map<String, String>, val body: String?)`,
  and **`git grep -l 'import okhttp3' -- '*/src/commonMain'` returns zero hits repo-wide.** So
  **"port `CrispyBackendClient`" now has to be re-measured rather than read off this paragraph:**
  okhttp 5.5.0's own `okhttp-5.5.0.module` publishes **8 variants — two metadata, `android`
  (api/runtime/sources), `jvm` (api/runtime/sources) — and no Native, no JS, no linux**, so
  `HttpUrl` is not an available answer *and* `OkHttpCrispyHttpClient` cannot move. The blocker
  the receiver already names, 39 of `CrispyBackendParsers.kt`'s 45 functions being
  `internal fun CrispyBackendClient.parseX(…)`, is the one thing here still worth re-counting,
  and what it asks for is that the *receiver* become an `interface`.
  padding a mutation driver with `expect_survive` entries** — and it is worth an order of
  magnitude more to record the *negative result* than to spend the churn, because the finding is
  what stops the work being re-attempted.

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

**A `commonMain` file is worth nothing until a non-Android target consumes it**, so a file count is
not progress on its own: ask what runs it. The `apple.yml` and `:android:desktopApp` entries below are
where that gets answered.

| Module | Kind | Notes |
|---|---|---|
| `:android:androidApp` | `com.android.application` | manifest, app-only `res/`, signing, ProGuard, ABI splits, the `store`/`sideload` flavours, the golden screenshots |
| `:android:app` | KMP + Compose | shared UI and presentation. 128 `commonMain` / 68 `androidMain`. See the table in `android/app/build.gradle.kts` for what holds what |
| `:android:sharedUI` | KMP + Compose | the design system **and the design assets**; produces the `CrispyUI` iOS framework |
| `:android:ui-assets` | `com.android.library` | only what CMP cannot carry — launcher mipmaps, splash colour + 2 drawables, 9 provider-logo SVGs |
| `:android:core-domain` | pure KMP | domain rules, no Android types/IO, **and the contract suite in `commonTest`** |
| `:android:player`, `:network`, `:addons`, `:home`, `:backend`, `:watchhistory`, `:platform-core` | KMP | **`:addons` is the one module with nothing left to port — 20 `commonMain` / 2 `androidMain`, and both remaining files are deliberate platform seams** (one names `Context`, one typealiases `kotlin.jvm.Synchronized`). It took six files, and the finding across all six is that **every pin was a constructor parameter** and **not one of them needed a rewrite**; the module's build file had listed the blockers wrongly on four of the six. **`:backend` is the same shape**: 17 `commonMain` and exactly 1 `androidMain` file, `SecureTokenStore`, on Android Keystore, permanently — and **the two ports that emptied it were both constructor parameters** (`AccountSessionStore`, `nowMs: () -> Long`), so the census counting `org.json` tokens could not have found its pins. Its `androidHostTest` is gone; all 33 of its cases run from `commonTest` |
| `:android:platform-desktop` | `kotlin.jvm` | the desktop side of all six ports. Exists because `desktopApp` is a real caller |
| `:android:desktopApp` | `kotlin.jvm` | the desktop entry point and the **seam proof** (plan §3) |
| `:android:tv` | `com.android.application` | Android TV placeholder; stays on the Android source set forever |
| `:android:youtube-extractor`, `:android:torrent-engine` | sideload-only | optional engines, excluded **structurally** from the store build |
| `:android:native-engine` | `com.android.library` | the MPV/Media3 player and nothing else optional |
| `:android:plugins` | plain Android lib | QuickJS bridge, `store` source set unread on purpose |
| `android/torrent-engine`, `android/plugins`, `android/tv` | plain Android | **a plain `com.android.library` cannot be consumed from a KMP `commonMain` at all** |

**`CrispySharedTransitionLayout` is in `commonMain` because the mechanism is not navigation.** 14
`commonMain` files read `LocalSharedTransitionScope`, a `staticCompositionLocalOf<SharedTransitionScope?>
{ null }` whose `content` slot has **no default**, so a caller cannot obtain a provider that provides
nothing. Three of the nine nav graphs are in `commonMain` and the rest are still in `androidMain`, and
the finding is that **the graphs were never the unit of work: a file can move once its _callees_ are
in `commonMain`, so movement propagates upward from the leaves, and the leaves are the screens.**
**A set moves as a unit only when the references are mutual, and a graph calling a route is a one-way
edge** -- `androidMain` sees `commonMain`, so a route moves out from under a graph that has not moved
yet, which is why `LibraryNavGraph` could move at all once `LibraryRoute` below it had. The two other
ways a graph was pinned are the nav half of §1's *run the audit in both directions*: **a pin that
arrives through a _call_ is invisible to every import scan**, and **a file in a package can be pinned
by a sibling in the same package with no import at all.** The three landings' narratives are in the
git history.

**An `R` reference blocks a file completely but usually blocks only a few lines of it, and the two
halves belong in opposite source sets.** `:app` has **zero** `expect`/`actual`, so introducing one for
a single `when` on a resource id is against the grain. `DetailsCastSection.kt` (241 lines) split into
216 in `commonMain` and 25 in an `androidMain` badge, reached through a composable-slot parameter with
**no default**, so a call site cannot forget it.

**Golden screenshots (`:androidApp:testStoreDebugUnitTest`)** — Robolectric + Roborazzi on a plain JVM;
the only rendering coverage in the repository. **Verify is the default**; re-record with
`-Proborazzi.record=true`. They live in `:androidApp`, not `:app`, because they need
`isIncludeAndroidResources`. The Roborazzi plugin is deliberately **not** applied: 1.43.1 fails
against AGP 9.3. Screenshot tests must set `application = ScreenshotTestApplication::class`, or
Robolectric boots `CrispyApplication` and reaches an `AndroidKeyStore` that cannot exist on a JVM, and
`mainClock.autoAdvance` must be frozen or animated content never matches. **A golden that waits on a
signal the component itself emits is not a flaky golden; a golden that waits on a duration is.** Host
prerequisites: Skia needs `libGL`, `libX11` and `libfontconfig`, and at least one font — `test-fonts/`
at the repository root plus a generated `fonts.conf` covers hosts that have the libraries but no fonts,
and a *failing* golden without one reports `Fontconfig head is null` instead of the real difference.

**Composition-root tests (`:app:testAndroidHostTest`)** — `:app`'s **only** test compilation, created by
`withHostTest {}`. **`androidHostTest.dependencies { }` does not resolve; only
`getByName("androidHostTest").dependencies { }` does.** Robolectric is here for a `Context` and nothing
else. **Pin `sdk = [35]` on every one of these classes**: with `Config.NONE` there is no manifest, so
Robolectric falls back to **SDK 21**, below `minSdk`, and the failure reads `Method … not mocked` —
naming a real app class and blaming nothing. **The sandbox classloader is shared across every test class
with the same `@Config` in one worker JVM**, so a test about a process-wide singleton needs a
`companion object` and `@FixMethodOrder`, not an instance field. `SecureTokenStore` is untestable on a
JVM, so everything reaching it is untestable — leave those out rather than mocking around them.

**`commonTest` without `withHostTest {}` runs on no Android target, and AGP only *warns*** (the
message is `The 'commonTest' source directory exists, but android host tests are not enabled`) — so a
fourth `commonTest` must add the block in the same change. **`commonTest` and `androidHostTest` are
complementary halves of one module and one step of CI is not enough to cover both.** **Which source set
a test belongs in follows the source set of the code under test** — an `androidMain` class cannot be
tested from `commonTest` at all. `commonTest` is not published, so nothing in one module's is visible
from another's. **An exhaustive test double is a property of the module that owns the interface**:
`UnusedBackendApi` (52 members, throws) lives in `:backend`'s `commonTest` and is wired into
`check-local.sh`, so a new `BackendApi` member fails to compile there; a narrow double in another module
rots silently. The test-writing rules themselves are in §3, not repeated here.

**`:android:platform-core`** is platform-free `commonMain`: the six port interfaces plus **`SecretFormat`**,
the encrypted-secret contract both platform stores implement. **Shared constants are not the shared
format** — `SecretFormat` now owns `encode`/`decode` rather than each store joining and splitting the
value by eye, and takes `String`s because base64 is a platform concern. **A rule both implementations
depend on and neither tests needs its own suite; it does not come free with the constant.**

**`:android:platform-desktop`** — a plain `kotlin.jvm` module, not KMP, because every implementation is
a JVM call. Four things in it are expensive to relearn: **`FileKeyValueStore` is one file per store
name, never a key prefix inside one file** (a prefix has to be re-applied on every read and write, and
one missed prefix is a silent leak between stores — which is also why it holds no in-memory cache, so a
write through one instance is visible to the next read through another); **`sanitize` must reject a
name that is only dots**, because `..` sanitises to `..` and `File(root, "..")` resolves to the root's
*parent*; **`Properties.load` does not throw on a line that merely looks wrong** (`=not a key` parses as
an empty key — only a malformed `\u` escape is rejected, so a fixture meant to prove "unreadable file reads as
empty" must use one); and **`DesktopSecretStore` is not a keystore and says so** — the key is a file
next to the data, so `keyFile` is a constructor parameter precisely so an OS keychain is a wiring change
rather than a contract change.

**`org.json` in a `commonMain` file is settled, and the decision is the node type rather than the
accessor policy.** `org.json` is not a dependency of this project at all — it is an `android.jar`
platform class appearing in exactly one build file (`testImplementation` in `:android:plugins`) — so a
file that parses or writes JSON stays in `androidMain`. Neutralising the accessors moves nothing, because
consumers name an `org.json` node **in the signature**, and an untyped `Any?` tree cannot carry it since
`JSONObject.NULL` and an absent key are different events. **The decision is
`kotlinx.serialization.json.JsonElement`** — the only candidate that keeps a JSON null distinguishable
from an absent key, and a node type of our own would be a parser we must then test as carefully as the
one it replaces. It was already in `libs.versions.toml` with zero consumers, so adopting it is a
declaration rather than a version resolution.
**`WatchProgressStore.kt` was the first file that moved, and its two behaviour changes are the shape of
the rest.** 405 lines of `androidMain` imported **not one `android.*` type** because it already took the
four `:platform-core` ports as constructor parameters, so the JSON node type was the only pin. **A stored
JSON null `remoteImdbId` now reads as `null`** rather than the four characters `"null"` — the old answer
was a platform rendering leaking through a string accessor — while a *non-string* primitive still goes
through `contentOrNull`, which is why the code is `contentOrNull` and not a `jsonPrimitive.string` cast
that would throw. **A stored `Long.MIN_VALUE` tombstone is now kept and only a non-numeric entry is
dropped**, because the old reader used `optLong(key, MIN)` as its accept filter and `optLong` answers
its default for anything unreadable — so a sentinel was the *only* way to detect a non-number and a real
`MIN_VALUE` was indistinguishable from one. **The masking did not vanish, it moved** (a `Long` map, not
JSON), so the reader now hands back a real `MIN_VALUE` the gate reads as "no tombstone" — benign,
because timestamps are `nowMs()`, and **two sentinels on opposite sides of one value is the shape to
watch for when a type change removes one of them.**
**A file's dependency belongs in the source set the file is in, not where it used to be.**
`:platform-core` was declared in `androidMain.dependencies`, reachable only through the plain
`com.android.library` `:platform-android`, which publishes no JVM variant -- so nothing in a
`commonMain` could see it, and the member-level errors on every port member stopped the moment
`api(project(":android:platform-core"))` moved down with the file. *This is the same rule recorded for
`HttpClientPort`, and it is the reason a port declared as an `androidMain` dependency is invisible to
`commonMain` even though the code compiles there.* The other half -- that the library which ships and
the library a JVM test can stand in for are different implementations -- is a rule of its own, in section 3.

**`:backend` then paid that cost, and it took all five files plus the client, not the two that were
first named.** `CrispyBackendJsonExtensions.kt` (13 policies) and `CrispyBackendParsers.kt` (45 parsers)
moved to `commonMain` **after** `CrispyBackendClient.kt` and its three API files did** — and the order was
forced, not chosen: 39 of the parsers are `internal fun CrispyBackendClient.parseX(...)`, so the moment
the parsers compiled in `commonMain` the compiler produced 35 identical
`Unresolved reference 'CrispyBackendClient'` errors. **A file whose receiver is pinned cannot be freed by
changing its arguments, so the wall is whatever pins the receiver** — here `org.json` in eight places
inside the client's own two envelope methods, and nothing else. **The real cost of that wall was not the
811 lines of parsers but the 45 read sites the client's return type switched underneath**, and
`CrispyBackendClient` was a *receiver* long after it was an *implementation*.

**`:app`'s `androidMain` is a knot, not a list of independent files, and the table of what holds what
lives in `android/app/build.gradle.kts`** — read it before planning any move. **A replacement for a JVM
library call goes in the file that already replaces that library, not next to the caller** — look for
the existing replacement before writing a second one in the same repo. **`okio` is that replacement for
`java.io.File` and it arrived through `coil3`**: `coil-core` 3.5.0 resolves `okio:3.17.0`, and
`FileBackedPendingMutationStore` uses it with **no dependency line of its own** — so
`desktopCompileClasspath | grep -c okio` answers "is it there" and
`dependencyInsight --dependency com.squareup.okio` answers "from where" in two commands. **Its
`commonMain` API is smaller than the JVM jar suggests** (no `read`/`write` String overloads at all, and
no String-helper file to find), so *the surface is in the file, not here* — the two probes that resolve
are the whole API, and the older `readerAction`/`writerAction` overloads are internal inline forms.
**The test half is a separate artifact and does need declaring**: `okio-fakefilesystem` (version-matched
to the `okio` that resolves, because a `FileSystem` subclass compiled against a different okio is an
abstract-method error at best) is the only reason `FileBackedPendingMutationStore`'s wire format has any
coverage, and it is what caught the `flush`/`close` bug below. **A strict in-memory `FileSystem` is
worth a test dependency for one reason: it fails where `FileSystem.SYSTEM` succeeds.** `FileSystem.SYSTEM`
wrote a file that `readText` then read as `""`, and every green test in the world would have said the
store worked. **And okio discharges two more pins, both of which were believed to have no KMP answer
at all.** `ByteString.encodeUtf8().sha256().hex()` replaces `MessageDigest.getInstance("SHA-256")` plus a
hand-rolled nibble loop, and `.md5()` is there too — **so "no `java.security` equivalent" is the same
shape of false premise as "`java.util.UUID` has no Kotlin/Native equivalent"**, and the two claims were
in the same class of file. A digest that is merely *equivalent* is not good enough, though: see the
golden rule below. **`delete` answers `Unit` where `java.io.File.delete()` answered `Boolean`**, and
`LibraryDiskCache.invalidate` returns `Result<Boolean>`, so the answer has to be *reconstructed* with a
`metadataOrNull` first — `mustExist = true` throws for an absent file and `false` silently succeeds, and
**neither is the old answer.** The compiler catches this one for free, which is the strongest argument
for reproducing a signature verbatim: the mismatch is a red build rather than a caller that quietly
stops being able to see whether a delete happened. **And `use` needs `import okio.use`, not the stdlib's**
— on the JVM okio's `Closeable` *is*
`java.io.Closeable`, so `kotlin.io.use` applies and every local gate compiles it, while on Native
`BufferedSink` is not an `AutoCloseable` and the stdlib overload has no applicable candidate. **That
is the `Dispatchers.IO` rule arriving through a different symbol, and it cost a red `apple.yml` run
on two lines.** Push the pure half of a split
toward `commonMain` and the platform half toward `androidMain`, even when the platform half is the
smaller one** — otherwise the pure half is untestable. A **large file holding a small pure thing no test
can reach is an *extraction*, not a move**: deciding which is the measurement.

**`:android:home`** shares the package `com.crispy.tv.home` with `:app`, so a type declared in its
`androidMain` is reachable from `:app` **with no import at all** — the purest form of the trap that
makes an import-only audit report confidently-empty answers. `linuxX64` is its only Apple-target check
on Linux. Its `CalendarService` cache is **per-profile and written only on a non-error fetch**, so a
cache written for one profile is never handed to another and a failed read never replaces a good
snapshot; the `isError` guard on the write is deliberately redundant and says so where it is.

**`:android:sharedUI`** — CMP `1.11.1` pinned to Kotlin `2.4.10`; bump them together or not at all. **Do
not re-litigate Material3 Expressive, and do not "fix" it by dropping it** — it is available on every
target; the blocker was one build-file line, and taking the `compose.material3` alias instead would
have deleted a shipping design feature to work around a version pin. Other facts: `android { }` is
current and `androidLibrary { }` is deprecated; **CMP 1.11.x ships `androidx.compose.*`, not
`org.jetbrains.compose.*`**, so moving a file changes *coordinates*, never imports; **`platform(...)`
does not exist on a KMP source set**, so a KMP library pins versions itself; and **no `linuxX64` on
Compose modules** — its local purity gate is `jvm("desktop")`. `CrispyPalette` is the token layer both
surfaces read, and **`:tv` maps two roles onto it under different names** (`border`/`borderVariant` vs
`outline`/`outlineVariant`) with the same two hex values written out twice — **a missing mapping is a
silent colour change, not a compile error**, which is why the values are pinned in `commonTest`.

## Rules

Distilled from every gate that fired, every compile that failed and every mutation that survived
this session. Each one is here because it changed a result; none of it is a story about a file.
The per-landing narrative this replaced is in the git history, where it belongs.

### 1. Audit before you plan

- **A module's untested `commonMain` is usually untested because nobody created a test source set —
  so check `git ls-files <module>/src/commonTest` first, before concluding anything about a file.**
  The corollary: **`:app` is the one module with no Native compile gate, and it is the one module that
  was broken.** Every pure-Kotlin KMP module declares `linuxX64` as a compile-only gate and exactly
  those are clean of JVM API; `:app` is a Compose module, CMP publishes no `linuxX64` artifacts, so it
  could not have the gate that catches the bug class it contained. Five classes of JVM API sat in its
  `commonMain` and **three needed no import** (`System.currentTimeMillis()`, a fully-qualified
  `java.time.LocalDate`, `synchronized`), so `check_common_purity.py` was blind too. **A gate that
  cannot reach a module is not a weak gate, it is no gate, and the fix is to find a gate that can,
  not to relax the one that cannot.** A `linuxX64` block with a comment saying why it is absent beats
  no comment — `android/app/build.gradle.kts` carries one under a heading that reads
  `## No linuxX64 here`, **and a grep for a target declaration matches its own refutation.**
- **A gate that does not compile the source set you changed certifies nothing, and it returns
  `BUILD SUCCESSFUL`.** `:backend:compileKotlinDesktop` compiles `commonMain` plus `desktop`; it does not
  compile `androidMain` at all. It reported `EXIT=0`, 0 `e:` lines, twice, while `CrispyBackendClient`,
  the three API files and 646 lines of parsers sat broken in `androidMain` — **and the second time it was
  run deliberately, as the check after a fix.** The task that holds them is
  **`:android:<module>:compileAndroidMain`** (`:backend:tasks --all` names it;
  `compileAndroidHostTest` compiles both halves, which is why the test task is the safer single
  command). **A green compile of the wrong source set is worse than a red one, because it is read as
  evidence** — and it was only caught by noticing that the *fix* I had just made was in a file that task
  does not see. **Before trusting a compile, check which source sets it names.**
- **The explanation for one module's gap does not generalise, and assuming it does hides the more
  useful cause.** `:addons` had no test source set because five of its files are `Context`/OkHttp
  adapters and a test directory for one file in an Android-shaped module reads as wrong. **`:player`
  is the counter-example that identifies the real mechanism:** all six of its files are `commonMain`
  with an **empty** `androidMain`, so nothing about it looked untestable — and its own build-file KDoc
  calls it the template the other five modules follow, **and the template omitted a test source set.**
  So when a module has no tests, read its KDoc before theorising: a module that describes itself as the
  template has told you the others are copies.
- **The two ends of that sweep are opposites, and the opposite end is the one that hides a decision.**
  `:player` had an empty `androidMain`; `:network` is the mirror — **all four of its `androidMain`
  files are OkHttp/`Context` adapters no `commonMain` can reach**, so the module reads as Android-only
  while its `commonMain` is 27 lines of pure string handling. **A module that looks untestable is not
  a module with nothing to test** — read the `commonMain` file list and line counts before
  concluding anything from the `androidMain` shape.
- **A module with no test source set is a question, and the question has to be the _module_, not the
  source set a sweep happened to look in.** `:watchhistory` was recorded as "a measured non-finding"
  because its `commonMain` is two files re-asserting the compiler. **That was a correct claim about
  `commonMain` and the wrong scope**: the module had no test source set of any kind, and the
  unmeasured content was 405 lines of `androidMain` whose sole pin is `org.json` and which imports
  **not one `android.*` type** — it was designed portable. **A "measured non-finding" is a claim
  about a scope, and the scope is often inherited from the sweep rather than chosen by the question.**
- **Which source set a test belongs in follows the source set of the code under test, and that is a
  fact to read rather than a preference to express.** An `androidMain` class cannot be tested from
  `commonTest` at all, so "put it in `commonTest` because `commonTest` runs everywhere" is backwards
  for it — and where the pin is `org.json`, that means `androidHostTest` under Robolectric, which is
  there for `org.json` itself and **not** for a `Context`, because the two implementations disagree.
- **Run the import audit in both directions.** A forbidden-token scan answers *pinned by an import*.
  Subtracting every type declared in every module's `commonMain` from the capitalised identifiers a
  file uses answers *pinned by a sibling* — `:app`, `:home` and `:addons` all declare overlapping
  `com.crispy.tv.*` packages, so a declaration in another module's `androidMain` is reachable with
  **no import at all**, which the forward scan cannot see. And **a file with zero forbidden imports
  can still be unpinnable, because the blocker can be a _type_**: `grep -rn "class X"` its distinctive
  types and read the owning module's `plugins { }` block. A plain `com.android.library` publishes no
  JVM variant, so no KMP `commonMain` can name its types however clean the code looks.
  **A full re-measurement of the 68 `:app` `androidMain` files that remain answered the question no
  per-file scan was asking: _not one of them is movable_** — `44 android.jar / 12 same-module
  declaration / 6 android.view interop / 4 R / 1 plain-library type / 1 java.*`, with three further
  buckets at exactly **zero**, which is the finding and not a gap. And **a census answer of "one pin"
  is a claim about the file that was scanned, not about the set the file belongs to**: an earlier
  four-of-70 scan offered four unpinned files, and all four are now either moved or shown to be
  correctly placed.
  Two consequences of the 68 are worth more than any landing they stopped. **25 of the 44 import
  nothing but `android.content.Context`**, and `fun create(context: Context)` *is* a composition root
  with the wiring `Context` belonging in the factory — so **a bucket that lumps a wiring `Context`
  with real platform use reads as 44 blocked files and is really one blocked file and 25 correct
  ones.** And **a first-match partition can only report a file's _first_ pin**, so
  `PlayerSessionDecisions.kt` (also `:native-engine`-pinned) and `PersonDetailsRoute.kt` (in the
  `java.time` bucket *and* the same-module bucket) each carry two — **a bucket is a floor, not a
  description, and a two-pin file needs a second look the output cannot give it.**
  **And the gate caught that census's own hole, which is the part to keep.** Its `sibling
  declaration` rule first collected from *other modules only*, so it reported **10 files with no pin**
  — precisely the ten three earlier landings had recorded as held by a **same-module** `androidMain`
  declaration reachable with no import. Adding `:app`'s own `androidMain` dropped the bucket to zero.
  **A zero bucket is the most informative thing a first-match partition produces, and it is a claim
  about a _rule_ before it is a claim about the set** — the near-miss explanation ("`:addons`
  finished") was true of the *other-module* rule and irrelevant to the hole, and the two rules look
  identical in the output. **So the gate must assert every bucket is non-empty before it asserts they
  sum, and every empty bucket must be allowlisted with the landing that emptied it** — otherwise the
  sum passes over a `Counter` that never incremented, which is the `True`-checksum disaster wearing a
  different hat. Three separate failures produced one wrong census and **all three manufactured
  candidates rather than losing them**: a bash tally with space-containing bucket keys printed six
  rows of zeros under a `True` checksum, a rule ordering let the coarse bucket absorb the navigation
  files, and `set(hits) <= {"LocalContext","LocalConfiguration"}` classified every unpinned file as
  Compose-local, because it is true of the empty set. **So a bucket name that asserts a negative is a
  claim the measurement must TEST, not a label it may print; in a first-match partition the rules
  after the first are never evaluated for the files the first one caught, so a sole-pin bucket cannot
  be produced that way at all; and when a tally produces a bucket that looks like a finding, write
  down what its rule _excludes_ first, then run the script that would refute it.** *A check that does
  not run is not a weak check, it is no check.*
  **And a pin is per file, not per token — the token you hunted is rarely the one that decides
  whether the file moves, and a scan that tests for the tokens you are chasing is not a test for
  the pins you are not.** `DetailsRoute.kt`'s only `java.*` use was `Locale.US` at `:35`, which
  reads as the whole story; `LocalContext` at `:7` and `appGraph()` at `:10` are what actually kept
  it in `androidMain`, and both were in the rows the scan had already printed. **So read the whole
  import list of a file you are about to claim you understand, and treat every second pin as the one
  that decides.**
  **And when a whole family is re-scanned token by token, the tokens are usually no longer the wall —
  so classify a family by its blocker before choosing a file in it, or each landing finds a different
  token and each finds it was not the pin** (a defaulted `clock` behind `LocalContext` and
  `paging-compose`; a screen reading the wall clock behind `android.content.Intent`; a `Locale`
  argument behind `android.util.Log`). **And when the census is near-zero, recording the partition is
  worth an order of magnitude more than a landing**, because the finding is what stops the work being
  re-attempted — and what remains is **a dependency decision, not a code one**, the same class of
  finding as the navigation wall.
- **A private decision is an untestable decision, and a private member is worse than a private
  function** — `private` is a property of the class, not of the file, so a `private` member cannot be
  named by a test in its own module either. Name it in production (`:tv`'s `CrispyTvDarkColors` was
  widened to `internal` for exactly this), and prefer **lifting to a top-level `internal` in the same
  package**: a same-package declaration leaves every existing call site resolving to it, so
  `git diff --numstat` reads deletions-only (`0 43` on `WatchProgressStore`, `0 10` and `0 20` on the
  two `:app` stores). **Watch the direction where a companion member shadows a new same-named
  top-level declaration** — the class keeps reading its own copy, so two strings that must agree have
  nothing making them agree.
- **A dead private member is a product question, and so is an owned scope with no owner to hand it
  from.** `normalizedImdbIdOrNull` is 17 lines of real imdb-id validation with **exactly one hit in
  the repository: its own declaration** — while `buildWpKeyString` has ten. Deleting it is a product
  call, so it is recorded. And the antipattern fix "pass the caller's scope" **can move the problem up
  a level rather than remove it**: `WatchProgressStore`'s default scope is live, but
  `BackendWatchHistoryService` owns no scope either, so injecting the caller's would just relocate it.
- **A composite key built by string concatenation is a parser, and the parser is wrong on exactly the
  inputs the format cannot represent.** `removeAllWatchProgressForContent` splits the stored key and
  takes `subList(2, size)`, which assumes **the id contributes exactly one part**; a
  provider-qualified id does not, so the removal addresses a key that does not exist, and leaves a
  tombstone there that would later block a legitimate write. **A separator in a composite key is a
  claim that the field cannot contain it, and nothing states that claim anywhere.** A format that
  cannot round-trip is a product decision to migrate or to constrain, not a bug to patch — changing
  it orphans every stored key, and here that is a user-visible resume position. Note also that
  **a key format is not visible in a signature**: three of that suite's first-run failures were me
  writing expectations from the parameter names rather than the format. **And a measured golden is
  the same mistake one level up, which is harder to see because the measurement is real.**
  `libraryCacheFileName(profileId, sectionId)` hashes `"library:${profileId.trim()}:${sectionId.trim()}"`
  and its seven-value golden was built by feeding pre-joined key strings to `MessageDigest` in a
  standalone JVM — which cannot see the `trim()`. The whitespace row therefore held the digest of
  the **untrimmed** key `library:  :  `, a string the function never produces, and it failed against
  `e1db8a0e…`, the correct digest of `library::`. **A correct SHA-256 of a string the function does
  not build is still a wrong expectation**, and "I measured it" reads like evidence in a way that
  "I wrote it out" does not. **So a golden has to be measured *through* the function** — a
  `commonTest` that prints, compared against the old implementation — and never from the format the
  function is assumed to assemble. The second failure in the same run was the mirror image: the
  collision pair `("a/b", "c:history")` / `("a", "b/c:history")` does not collide, because a `/`
  was mistaken for the `:`. The pair that does is `("a", "b:c")` / `("a:b", "c")` — **the separator
  has to be the field boundary, not a character that looks like one** — and a collision test with no
  non-collision test beside it is satisfied by a function that hashes only its second argument.
- **A comment claiming a choice between two orderings that coincide is unobservable**, and a test for
  it would pass on either implementation — sorting `prefix + s` is the same order as sorting `s` for
  a constant prefix. Record it rather than assert it, and pin the comment's *real* content (that it
  sorts at all, because the underlying collection carries no order guarantee). **A statement of intent
  with no state to change is a redundant guard wearing prose.** The other instance is
  `optBooleanOrNull`'s `if (!has(key) || isNull(key)) return null` in front of a `when` that
  already answers all three cases: `opt(name)` is Java `null` for an absent key, `JSONObject.NULL`
  for a stored one, and `JSONObject.NULL` is neither a `Boolean` nor a `String` so it reaches
  `else -> null`. **Deleting it was the fix, not a cleanup** — the guard was hiding that the
  function could never return `null` for a present-but-unreadable value, which is the defect the
  return type was named for.
- **A document's own heading, prose class, or type names are not evidence about the code — and a
  second numbered plan for one repository is a defect even when every sentence in it is correct.**
  `architecture.md` used **seven type names that never existed**, and `DataSource`'s only hits are
  Media3's inside `:native-engine`, so a reader implementing the proposal would hit a collision. The
  prose class is the part nobody guesses: `## Target Use Cases` carries an explicit disclaimer that
  its names are not the point, so its six invented interfaces are obviously illustrative, while
  `## Fetch And Cache Policy` is the same kind of section with no disclaimer — **so the register of
  the surrounding prose is part of the audit.** It also carried a seven-phase `## Migration Plan`
  whose numbers meant something entirely different from the tracked plan's; the fix was a **scope
  split, not a deletion**, because **two numbered plans for one repository is worse than one plan even
  when both are correct** — the collision is only visible to a reader holding both.
- **A refactor list is a set of claims about the code, so re-measure it; a list where most entries are
  finished is worse than no list.** Three of nine `## What To Refactor First In This Repo` items were
  already done. **Split it into what is still true and what is done, keeping the numbering**, so item 6
  reads as half-done rather than the reader guessing from its first half.
- **Re-test a premise this file or a build file states as settled.** **Measure the artifact you are
  about to declare, never the family -- and the recorded answer here was itself half false**:
  `LocalWindowInfo` does not exist in any resolved Compose artifact and `LocalConfiguration` is
  Android-only in `ui-android`, yet `paging-common` is KMP, `paging-runtime` really is Android-only, and
  **`paging-compose` is KMP**, which this sentence used to deny. Three siblings, one family, three
  different answers. And **a KDoc sentence about one caller is not a statement about the function** --
  a comment explaining *why* one caller behaves unusually is a comment about that caller.
  **A build file's per-file table is the same kind of premise, and it was wrong about both of the
  entries it listed for one file.** `:addons`' table said `RemoteSupabaseSyncLabService` was in
  `androidMain` for "`Context` and `org.json`". It named no `org.json` type at all, and the `Context`
  was a constructor parameter **the class never read** -- it had been under a
  `@Suppress("UNUSED_PARAMETER")` the whole time. **So the pin was a parameter nobody consulted, and
  the fix was to DELETE it rather than slot it: a wrapper's parameter list pins a file exactly as
  hard as its imports do, and an import scan cannot see it.** The same file in `:app` had a second
  one (`watchHistoryService`), and removing it from the private function *and* from the public hook's
  type changed nothing else -- the hook had zero users outside the file, which is what you measure
  before deciding a type can change. **Read the property initialisers of a wrapper before planning its
  port, not its import list and not its KDoc row.**
  **A premise about the *ecosystem* ages in one direction, so "no equivalent exists" is the version
  that rots.** Three KDocs asserted "`java.util.UUID` has no Kotlin/Native equivalent", and it was true
  when written: `kotlin.uuid` did not exist. It is now `Uuid.random()`, **stable** in the resolved
  Kotlin 2.4.10 stdlib -- and *stable* is a measurement, not a version number: the class carries
  `kotlin.WasExperimental`, and of the companion's members **only `generateV4` still carries
  `kotlin.uuid.ExperimentalUuidApi`**. `javap` and the constant pool answer the opt-in question a
  version number cannot, and the compile answers the rest. **One of the three claims was in
  `commonMain`**, so a `commonMain` reader was being told a `commonMain` file could not exist. **When
  a KDoc names a platform capability as the reason a file cannot move, re-run that claim against the
  resolved artifact before planning the file, and correct every copy in the same commit: a corrected
  premise with two surviving copies is worse than the original, because the next reader finds both.**
  **And one of those copies can be the GATE itself, so a coordinate swap's blast radius is every token
  list that mentions the old one, not only the prose.** `f1c5b4ff` corrected seven doc claims and
  missed `scripts/check_common_purity.py`, whose `FORBIDDEN_PREFIXES` still forbade
  `androidx.navigation`; `54f0189c` then went **red on BOTH workflows** on a build that compiles and
  tests clean locally, because both run that gate. *A stale document misleads a reader; a stale gate
  fails the build.* **So after any swap, grep the scripts, not just the docs** -- and the gate had
  **already had this fix applied twice** (`androidx.paging`, `androidx.lifecycle`), so the third case
  was a copy nobody made rather than a situation nobody met. The fix is the file's **own** pattern
  (remove the prefix, add the measured comment, give the re-add trigger) and **not an allowlist entry
  for the offending file** -- a safety gate that fires on correct code gets switched off. **Re-prove a
  gate you have changed by violating its premise and confirming it still fires**: injecting
  `androidx.room` and `androidx.media3` imports each returned exit 1, the restore returned 0, and the
  file was byte-identical afterwards. *Removing a token is not the same evidence as the gate still
  working, and only the second one is a measurement.*
- **A count written about a set that later grows is stale silently, and re-measuring it is one
  command.** Three undercounts, each making work look smaller than it is, and each corrected twice
  over (a plan said 28 shared-transition files, then "10 across 6", and the measurement was **27**).
  `git ls-files <path> | grep -c <pattern>` settles it in under a second, and **a count two documents
  share is twice as likely to be believed and no more likely to be right.** The general form: **an
  omission nobody mentioned reads as a decision nobody made.**
  **And a count PREDICTED from the change is a count that has to include the change's own second
  half.** `CalendarScreen.kt` moved `androidMain` → `commonMain` and the landing predicted 123/72 —
  **but it also created `CalendarScreenFactory.kt` in `androidMain`, so `androidMain` is
  net-zero and the measured answer is 123/73.** A landing that both moves a file and extracts a
  factory does not reduce the source set it extracted into. **A "move" and a "create" are two
  facts about two different sets, and quoting only the first is the same class of error as
  quoting a count off the working tree instead of out of `HEAD`** — one number was measured and
  one was extrapolated, and they are formatted identically.
  **A checksum that prints `True` is a claim, not a check** — and a bash associative array whose
  keys contain spaces breaks in a way that satisfies the check exactly when the counting is most
  broken: `printf '%s\n' "${!bucket[@]}" | sort` word-splits every key, so the *reporting* loop
  iterates words while the *counting* loop is untouched, and `sum(counts.values())` over a
  `Counter` that never incremented is `0` — which compares equal to the total you expected, and
  prints `True` above six rows of zeros. **Do a partition tally in a language with a real dict,
  and make the check assert that the buckets are non-empty before it asserts they sum.**
- **A target declaration is a claim, and a target no CI job builds cannot fail — so it asserts nothing
  about whether the code is platform-free.** Ten modules declared `iosArm64`; `apple.yml` compiled two,
  and eight carried a target nothing ever built. `scripts/verify_apple_targets.py` now fails when a
  module declares a target the workflow does not build, and the converse is deliberately unchecked
  because **removing a target is a product decision and a script has no business making one.**
  **An assertion nothing executes is not a weak assertion, it is no assertion** — and the gate needed
  proving before it could be trusted (its first version built `compileKotliniosArm64` where the task is
  `compileKotlinIosArm64` and reported all twenty tasks missing on a workflow that invoked every one).
  **A gate that could not have been written is a stronger version of the same thing, and prose
  about one reads exactly like prose about a weak one.** `LibraryDiskCacheJsonAccessors.kt`'s
  KDoc deferred a consolidation on the grounds that *"`JsonAccessorsDivergenceTest` in
  `androidHostTest` pins both sides of every row"* — and that suite does not exist: zero tracked
  files match `Divergence`, and the only hit repo-wide is the KDoc line naming it. **Worse, it
  could not exist where it was named**: both copies are `internal` in two different modules, so
  `:app`'s tests cannot see `:backend`'s and vice versa, and no source set in the graph sees both.
  The deferral's stated reason was *"a behaviour change on three call paths"*, which is the count
  of the three **files** a consolidation would touch; the defective function had exactly **one**
  call site. **A count in a KDoc is a claim about the code and re-measuring it is one command**,
  and here the claim overstated the work by 3x while the gate it deferred to did not exist.
- **The Apple client is not this codebase, and a promise in a module's KDoc is not a plan.**
  `:platform-android`'s KDoc promised Apple port implementations "in Phase 6"; measured, there are
  **zero** Swift hits for all six ports, `ios/CrispyKit` is a 19-file Swift reimplementation of the
  whole data layer, and `CrispyUI` is built by nothing and imported by nothing. So **zero lines of
  Kotlin execute in the shipping iOS/tvOS app**, and a `platform-apple` module would have no consumer:
  it could only be "verified" by adding it to the very list being edited to verify it, which is
  circular. **The desktop module landed because `desktopApp` is a real caller; the Apple gap is a
  product decision, not a technical block.**
- **Measure the JVM's real output before replacing a JVM call, and copy the measurement into the
  test.** `DateTimeFormatter`'s `yyyy` is a *year of era*, so `0000-01-01` formats as `Jan 1, 0001`; a
  replacement's output matching byte for byte is not the end of the question — an identical-output
  result can still hide a causal claim (the month-day form matches *because* it prints no year, which
  is the only reason that is safe). **After a measurement, ask what it made true that nothing said
  before.**
- **Smaller commands that save a compile.** `dependencyInsight` on a configuration a dependency is not
  declared in answers in seconds. **Diff every moved file against its original in `HEAD`** —
  `diff <(git show HEAD:<src>) <dest>` is the only check that proves a non-executed line survived, and
  byte-identical is the strongest form of the answer. **Resolve a large error cascade by distinct
  unresolved _names_**, mapped back to their declaring file, not by line.
- **Two libraries naming one role differently is a mapping, not a divergence — and it is invisible
  until you put the two files side by side.** `:tv`'s `androidx.tv.material3` calls the border roles
  `border`/`borderVariant` where `:sharedUI`'s calls them `outline`/`outlineVariant`, and both sides
  held the same two hex values written out twice. Read as two schemes this looks exactly like a
  deliberate TV palette divergence that must be preserved. **Read the receiving side's own hand-written
  mapping before deciding a divergence is a product decision** — it is independent proof when it is
  not. The same rule covers **a duplicated `public` constant that is dead because the same name in a
  different package hides it** (`:tv`'s second `CrispySpinner`, 11 call sites on the other one, zero
  on its own): *an identical declaration reachable with no diff.*
- **"This is bound to navigation" is a statement about the file, not about the mechanism.** Across the
  repository there are three distinct imports of shared-transition machinery and **zero** of the
  participating files import `androidx.navigation` for the transition itself — the provider was two
  lines inside a file in a package called `ui/navigation`, which is exactly why it read as
  navigation-bound, and **neither of those two lines named navigation.** *The dependency is in the
  file, not in the lines that matter.* And **a missing provider is a silent null**: all 14
  `commonMain` participants read a `staticCompositionLocalOf { null }`, so off Android every one of
  them rendered with no transition and no error. **When a landing supplies a host, assert the value is
  non-null under it and null without it** — the second assertion is what makes the first mean "the
  host supplied it" rather than "the local defaults to it".

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
  **The rule was right and the commit that broke on it quoted the import list as evidence
  that it did not apply — so the missing half is the mechanism, not the rule.** `Dispatchers.IO`
  is `public` on the JVM and **`internal` on Kotlin/Native**, so the compiler says *"Cannot access
  'val IO: CoroutineDispatcher': it is internal in `kotlinx.coroutines.Dispatchers`"* rather than
  `Unresolved reference`, **and `Dispatchers.IO` needs no import of its own** — `Dispatchers` is
  the import, so an import scan is structurally incapable of finding the use. **Every local gate in
  this repository compiles it as public**: `desktopTest` compiles `commonMain`+`desktop`,
  `androidHostTest` compiles `commonMain`+`androidMain`, and both are JVM. **A green desktop compile
  is therefore not evidence about a `commonMain` file**, and this cost **three red `apple.yml` runs**
  on **one line** — `CatalogPagingSource.kt:26:50`, `2b4c5033`, fixed in the landing after it.
  **`apple.yml` on a macOS runner is the only gate that can see this class of error, so a dispatched
  run has to be *read*: 204 means accepted, not green, and dispatching and forgetting it is what let
  three consecutive red runs sit unread.**
  **The same error arrives through a symbol that was never an import, and the two halves of one file
  can need different packages.** `MetadataAddonRegistry` used `@Volatile` and `@Synchronized` with
  **no import at all** — they resolved from the JVM's default import of `kotlin.jvm.*`, which a
  `commonMain` file does not get, so a scan cannot see either. Their replacements do **not** come
  from the same place: `@Volatile` is `kotlin.concurrent.Volatile` and works, while
  **`kotlin.concurrent.Synchronized` does not resolve at all** in Kotlin 2.4.10
  (`Unresolved reference 'Synchronized'`) and **`kotlin.jvm.Synchronized` resolves on the JVM and is
  rejected as an `error` by `compileKotlinLinuxX64`**. The answer the compiler itself prints is
  *"introduce your own optional-expectation annotation and actualize it with a typealias"* — which is
  `JvmSynchronized`, an `@OptionalExpectation` annotation typealiased on Android. **So the newer name
  is the one that does not exist and the older name is the one that is forbidden**, and *the
  migration a symbol's name suggests was not available*. Note which gate saw each: `compileKotlinDesktop`
  and `compileAndroidMain` were **green** on the rejected import, and `compileKotlinLinuxX64` -- a target
  **`:app` does not have** because it is a Compose module -- is what refused it. A `Mutex` was the
  portable alternative and was rejected on its merits rather than on a preference: `withLock`
  suspends, so it would have made all five public methods `suspend` and reached every caller.
  **The same shape arrived a landing later as `synchronized(lock) { }`, and it is stronger: it is an
  `actual` on the JVM and absent from common metadata altogether**, so the Linux gate answered
  `Unresolved reference 'synchronized'` *plus* a cascading `'return' is prohibited here` that reads like a
  control-flow bug. Like `Dispatchers.IO` it needs no import, so no scan can find it, and unlike the
  annotation pair the portable answer is not a rename. `Mutex` cost nothing here **because both
  guarded regions were inside a `private suspend fun`**: the `Mutex`-makes-everything-`suspend` bill
  arrives with the *visibility* of the method holding the lock, not with the lock. `CachingStreamResolver`
  in the same package already held a `Mutex`, so a replacement for a JVM call went in the file that
  already replaces that library.
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
  **The same rule says which half of a class is the factory, and a `Context` *holder* in a
  constructor parameter is the pin that keeps an otherwise-portable class out of `commonMain`.**
  `AiInsightsRepository` named four collaborators and all but one were already `:backend`
  `commonMain`; the single `Context`-and-`SharedPreferences` holder in its constructor was the
  whole reason it sat in `androidMain`, and *a file whose parameter names a concrete platform
  holder cannot be read from `commonMain` however portable its own body is*. So the landing was
  the class moving while the factory stayed — `AiInsightsRepository` to `commonMain`, its
  `companion object { fun create(context) }` to a top-level `androidMain` `aiInsightsRepository(context)`
  — with a two-member `AiInsightsCache` interface between them. **A class whose companion
  constructs it from a `Context` is a composition root wearing a class's clothes, and that is
  one landing, not two.** Two corollaries: **a composition root's own comment can assert the very
  placement the landing is about to change** (`AppGraph.kt` carried "`AiInsightsRepository` stays
  in androidMain", which this landing made false — correct it in the same commit rather than leave
  it contradicting the diff), and **a value crossing as itself is worth checking for a round
  trip**: `AppGraph` held a BCP-47 tag, rebuilt a `Locale` from it, and the repository called
  `toLanguageTag()` on the result — two conversions carrying no information between them.
  **And the split is an *extraction*, not a move, and a `private` class is what forces it.**
  `CalendarScreen.kt` (321) took the same shape: `CalendarViewModel` held a value
  (`CalendarService`) and its `companion object` held `factory(context: Context)`. The factory
  became a sibling `androidMain` file — **and the class had to be widened from `private` to
  `internal` to make that possible, because a `private` member cannot be named by anything
  outside its own file, including the factory that exists to construct it.** So the factory is a
  new top-level `calendarViewModelFactory(context)`, and *the cheapest pins to discharge are the
  ones that die with their sole consumer:* the `Context` read in `CalendarRoute` existed only to
  reach the factory, so it disappeared without a slot of its own, and `remember` went with it
  because that was its only use in the file. **A pin that vanishes when the thing that read it
  moves is not visible as a pin at all while both halves sit in the same file** — which is the
  reason the per-file import scan found four pins here and the `commonMain` side needed two slots.
- **No-default slots for anything a call site must not forget.** A defaulted capability lets a call
  site silently hide a row the build ships.
- **When a platform composition local is unreachable, the answer is usually a value the caller
  already has.** `isWideScreen`, `isCompact` and `pluginsUiSupported` all crossed as data from a
  value already in scope; the alternative is inventing a lookup that may not exist.
- **The slot should carry the whole platform step, including the step that looks portable.**
  `titlecase(Locale.ENGLISH)` is locale-dependent while `replaceFirstChar { it.uppercase() }` is
  not, so splitting them puts the Turkish dotless-i bug in shared code. *A step is platform work if
  its answer depends on the platform; "it is just a `String` call" is not the test.*
  **And read what the consumer DOES with the value before typing the slot — a value it *stores*
  must stay a lambda, a value it merely passes on may be the product.** `SearchNavGraph`'s slot
  was first typed `() -> ViewModelProvider.Factory` and the compile rejected it with
  *`actual type is 'ViewModelProvider.Factory', but '() -> ViewModelProvider.Factory' was expected`*,
  because the caller is a composable and `remember`s it, so the product is what arrives — and
  `ProfileManagementRoute:798` then does `viewModel(factory = viewModelFactory)`, handing it on.
  `AuthNavGraph`'s third slot is the **counterexample that completes the rule**:
  `ProfileMenuRoute:98` is `produceState<ActiveProfileInfo?>(initialValue = null, loadProfile)`,
  **so the lambda is a `produceState` key** — a fresh one each recomposition restarts the profile
  load, so it must stay `suspend () -> ActiveProfileInfo?`. *The question is not whether a slot
  is a lambda or a product; it is whether the consumer keys on its identity.* Two more facts from
  the same landing: **a `Context` its caller must `remember` is already reachable at the call
  site** — `AppNavHost` had no `Context` at all until it read `LocalPlatformContext.current` in
  its own body — and **two graphs that each call the same loader must each get their own
  `remember`ed instance**, because sharing one keys both graphs' state to a single identity and a
  recomposition in one restarts the other's load.
  **And sometimes the platform step is deleted rather than moved, which is a behaviour fix and not
  a simplification.** `String.lowercase(Locale)` is the JVM-only overload and Kotlin's
  `lowercase()` is locale-invariant, so five `s.lowercase(Locale.US)` sites became `s.lowercase()`
  — and `Locale.US` was a real answer and the wrong one, because a locale is a *rendering*
  context: rendering a manifest URL in Turkish lowercases `I` to a dotless `ı`, so the same
  addon would key differently on a Turkish device and every household sync would install and
  uninstall the same row. `SharedPreferencesSearchHistoryStore` was already using `Locale.ROOT`
  for its dedupe key, so this was the second instance of one rule. **When a `Locale` argument is
  passed to a comparison, ask whether the answer depends on the device at all — if it does not,
  the argument is the bug.** The other two `Locale` uses are *not* deletable and the difference
  is worth keeping straight: `Locale.getDefault().toLanguageTag()` is a genuine reading of a
  platform value and belongs at the edge (three factories), while
  `DateTimeFormatter.ofPattern(…, Locale.getDefault())` is a genuine *formatting* locale that
  `kotlinx-datetime` cannot express the same way — **counting all three as one `Locale.` is a
  census that cannot tell a deletion from a port.**
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
  first grep hit.** **And the two copies can be in different *modules*, where a name-only grep is
  worse than a wrong package: it answers about the wrong declaration and the count looks
  plausible.** `:addons`' `JsonAccessors.kt` and `:app`'s `LibraryDiskCacheJsonAccessors.kt` declare
  `optStringOrThrow`, `optBooleanOrThrow`, `optIntOrNull` and seven more under **identical simple
  names**, so grepping the three accessors that `:addons` turned out not to call returns **five
  `:app` call sites and zero `:addons` ones** -- the names are live, the declarations are not. The
  only sound scope is the **one compilation unit that can see an `internal` declaration at all**,
  and the asymmetry is the other half of the finding: `:app`'s copy is a superset of **thirteen**,
  all thirteen called, while `:addons`'s eleven had **eight** called. So the stale copy was the
  small one, and *consolidating the two is a port only after the bodies are diffed* -- `:app` and
  `:backend` already hold two `optNullableString` copies that disagree.
- **A caller that already collapses every failure into one answer does not need a port to
  distinguish them; a caller that answers two different ways does.** This is the same rule as the
  bullet below, applied to a port's *shape*. Three call sites held `toHttpUrlOrNull()` when
  `CrispyHttpClient` became a `commonMain` interface: two of them wrapped the call in
  `runCatching { }.getOrNull()`, so a malformed url and a failed request were already the same
  answer and they kept the throwing member unchanged. **Only the third needed anything** — it maps
  an unusable url to one sealed case and a thrown request to another, *where the first is not
  retryable and the second is*, so a port whose every member throws would have made a malformed
  url retryable. **So the port gained exactly one member with exactly one caller**, and the
  decision came from reading each caller's body rather than from the three sites sharing a name.
  **`null` means "this was never a request"; a throw still means "the request failed".**
- **`flush()` is not `close()`, and nothing in the old code said the difference mattered.**
  Porting `File.writeText` to okio, `writeUtf8(t).flush()` compiles, reads like a
  completed write, and **silently commits nothing** -- okio buffers, and an
  unclosed sink has not been handed to the filesystem. A read on the same path
  then sees the *previous* contents, and a second write fails with `file is
  already open for writing`. **The signature of a missing `close` is seventeen of
  twenty-one tests failing at once with a mix of `List is empty` and
  `expected:<[…]> but was:<[]>`** -- i.e. every assertion that reads back what it
  just wrote -- which is not what a bad fixture looks like, and a reader who
  assumed it was one would start rewriting fixtures. **`File.writeText` closed
  implicitly, so the obligation was invisible until the port made it explicit,
  and `flush` discharges the *other* half of the same mental model.** The fix is
  `.use { it.writeUtf8(t) }`, and the same applies to every `source`/`sink` pair.
  **A strict in-memory `FileSystem` is what made this visible on any target** --
  see the next bullet, which is why the fake was worth a dependency.

- **A `null` and a `throw` are different answers, and `runCatching { }.getOrNull()` collapses
  them silently.** `SeasonEpisodesLoader` asks for a session; the original reported "Failed to
  load episodes." when that lookup *threw* and "Sign in to load episodes." when it returned null.
  The transcription that came first used `getOrNull()` and would have told a signed-in user with
  a flaky network to sign in again — no compile error, no test failure, just a worse product.
  Keep `isFailure` and `getOrNull()` as **two** questions wherever the original answered them
  separately.
- **A lock around two fields does not make the pair atomic, and holding the pair as one immutable
  value inside the critical section fixes it for free.** `SubtitleRepository` guarded
  `cachedKey` and `cachedSubtitles` with `synchronized(cacheLock)`. Each field's access was safe
  and **the pair's consistency was luck**: a reader could take the new key and the old list,
  because its two reads sit either side of the point where the writer is mid-update. This was
  found while replacing `synchronized` for portability, and it is a latent bug independent of it —
  the same code was already wrong on the JVM. **When a lock protects more than one field, ask
  what a reader sees halfway through a write; if the answer is "a mix of two states", the
  invariant is in the fields' relationship and not in the lock.**
- **A class that builds its own `CoroutineScope(SupervisorJob() + ...)` owns a job nothing
  cancels, and every coroutine it starts outlives the session that asked.** `SubtitleRepository`
  built its fetch scope in its own constructor default, so a subtitle fetch for a player session
  that was already torn down kept running. Its only production caller is
  `PlayerSessionViewModel`, which has its own `viewModelScope` that `onCleared` cancels; passing
  that instead retires the leak. **A defaulted scope is worse than a required one for the same
  reason a defaulted dispatcher is: it hides the lifetime decision from every call site.**
- **A cache with other readers must be shared, not moved in.** `seasonEpisodesCache` is read by
  five places in the view model other than the fetch it was extracted from; a map owned by the
  loader would have been a second cache, and the screen would have consulted the first. The first
  draft's KDoc asserted the opposite — "it belongs to this fetch, not the screen, so it moved
  with it" — and five measured readers disproved it. **A cache is a thing you share**, and the
  readers are worth counting before a KDoc reasons about ownership.
- **A mechanical port changes behaviour in *both* directions, and each direction is invisible
  until it fails — so port a type by asking what its old members permitted, not what its new ones
  accept.** Three failures in the `:backend` JSON port, all on `org.json` -> `JsonElement`, and the
  three are the three shapes of the mistake: **`org.json` was lenient where the new type is strict**
  (`jsonPrimitive` *throws* on a container, where `optString` returned its JSON text, so a naive
  `this[key]?.jsonPrimitive?.contentOrNull` turns a readable value into a crash); **`org.json` was
  mutable where the new type is not** (`json[key] = value` does not compile, because `JSONObject`'s
  whole API was `put` and `JsonObject` has no members at all); and **`JsonPrimitive` has no
  `Any?` constructor**, so `else -> JsonPrimitive(this)` needed a `when` keeping `is Boolean` and
  `is Number` intact — stringifying both would have been a *second* silent change stacked on the
  node type itself. **And `else -> this` is the arm that survives a port least often, because it was
  right about the old type and wrong about the new one.** In `toKotlinValue`, `else -> this` was
  correct for `org.json`, where a primitive *already was* a `Boolean`/`String`/`Number`, and it would
  have handed out raw `JsonElement`s from `toAnyMap` for the new type — where a primitive is **one
  class holding a literal**, so "pass it through unchanged" no longer means anything.
  **The coercion direction decides the fix: a lenient accessor feeding a strict parser is a
  truncation, not a parse.** `org.json`'s `Number.toInt()` *truncated*; `JsonPrimitive.intOrNull`
  *parses*, so `optIntOrNull("fraction")` over `{"fraction":42.9}` went `42` -> `null`. Fixing it
  by narrowing to `intOrNull` would have been the port; fixing it by reproducing AOSP's widths
  (`Int`, then `Long`, then `Double`) is the port *plus* the behaviour, and **only the second one is
  behaviour-preserving.**

### 3. Tests

- **A count that says a symbol is still used is evidence to keep its import, not permission to
  delete it.** Counting `Headers` in one file while excluding import lines returned `1`; the `1`
  *was* a live `headers = Headers.headersOf("Accept", "application/json")` in a function, and
  removing the import produced `Unresolved reference 'Headers'`. **The count is a list of what to
  go and read, not a list of what is dead** — and a compile is what settles it, not the grep.
- **Clear every module you will read, and read no wider than the tasks you ran — a `rm -rf` list
  shorter than the read glob turns a green suite into three phantom failures.** A seven-task run
  reported `1712 tests, 3 failures` with a real-looking signature (base64 halves transposed,
  `aXY` against `Y2lwaGVydGV4dA=`), in a module this change had not touched
  (`git status --porcelain -- android/platform-core/ | wc -l` -> `0`, and the format's `encode`
  and `decode` verified correct on disk). **The `rm` covered five modules and omitted the sixth;
  the read glob was wider than the tasks invoked.** The re-run of that module's own task was
  `tests=8 failures=0`. *A failure list that does not move when the code under it changed means
  you are reading the previous run — and here the code under it was never changed at all.*
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
- **Moving a file into `commonMain` does not make it testable — a _concrete class_ in its
  constructor does, and a file whose only untestable collaborator is a class has to be recorded
  as a negative result rather than left looking finished.** `AiInsightsRepository` is now
  `:app`'s `commonMain` and **nothing can test it**: its third collaborator is
  `CrispyBackendClient`, a `class` and not an `interface`, so a `commonTest` can neither
  construct it nor stand in for it — and measured, it has **zero references anywhere in `:app`'s
  `commonTest`**, so it was never nameable from there. Its other three seams are faked today
  (`FakeAccountApi`; `ActiveProfileStore` over a `FakeKeyValueStore` in four suites; the
  `AiInsightsCache` interface added alongside). **A suite that runs and a suite that could be
  written look identical in a build log, and the second is the one a reader counts as
  progress** — so the landing's own test count staying at 475/513 is the *honest* number here,
  and a same-day count is not evidence of coverage. *The fix is a type, not a test:* making
  `CrispyBackendClient` an interface, whose own blocker is that 39 of
  `CrispyBackendParsers.kt`'s 45 functions are `internal fun CrispyBackendClient.parseX(…)` —
  *the receiver is the thing to change this time.*
  **That wall is scoped to the module that owns the type, and a consumer outside it was never
  behind it at all.** Those 39 extensions are `internal` to `:backend`, so they block
  `:backend`'s own `commonTest` and nothing else — and `class CrispyBackendClient(...)` **is
  already in `commonMain`** and already implements `BackendApi`. `HouseholdAddonsCloudSync` moved
  and gained a 12-case suite by changing one constructor parameter from `CrispyBackendClient` to
  `BackendApi`: **a no-behaviour change on the one wiring call site, and the whole difference
  between testable and not.** So before recording a `class` collaborator as a wall, ask *whose*
  `commonTest` it blocks — the fix may be a parameter type rather than an `interface`.
- **A decision is not "in the composable" because it renders; it is in the composable only if it
  needs the composition.** A `when` inside a `@Composable` body is uncallable, so its arms cannot
  be covered. A condition over several values written inline is *five decisions wearing one coat* —
  as one named predicate each clause is separately testable.
- **A fixture that equals its own transformation is a vacuous assertion.** Asserting
  `"8.4"` for a rating proved the standard library, because `formatOneDecimal(8.4)` is also `"8.4"`;
  use `"10"` → `"10.0"`. Same shape: a cap case whose fixture was already sorted could not see a
  cap applied *before* the sort. **When a normaliser has an identity input, that is the input to
  avoid in the fixture meant to prove the normaliser ran.**
  **The same defect has a counting shape, and it fires when the number is written from memory.**
  Nine error messages in a service class asserted as a set — and the *count* was asserted from
  recall as 7 when the answer is **8**, because `initialize`'s string differs from `syncNow`'s by one
  inserted word, which is precisely the copy-paste pair the suite exists to catch. *A count is a
  claim about the code; read the strings before asserting how many there are.*
  **The defaulted-expected-value shape is the same defect wearing a default argument, and it fails
  *en masse* rather than one case at a time.** `ManifestUriTest` has a `row(raw, host, baseUrl,
  pathSegments = emptyList(), encodedQuery = null)` helper over a 26-row measured table, and the
  default asserted that **every** row's path is empty. Thirteen cases failed together with
  `expected:<[]> but was:<[manifest.json]>`, and only the four rows written out disagreed with the
  default. **A defaulted expected value is a fixture that agrees with every case it was not given
  to**, so the fix is no default at all: every row states it, and a row added later cannot inherit a
  wrong one. Read together with the counting shape above, the pattern is that **a value the author
  filled in from memory is a value about the code rather than about the case** — `7` when the answer
  was `8`, `emptyList()` when the answer was `["manifest.json"]`, and `Example.COM` when the answer was
  `example.com` because the id lowercases the hint. **Three defaults, three wrong, and in each case
  the fixture was the defect and the production code was right.**
  **And a list built eagerly is already cumulative by the time a loop reads it** — the
  dispatcher-slot test built all nine results first, so the counter a `RecordingDispatcher` was
  holding had already reached 8 when the loop inspected its first member, and the failure
  (`expected:<1> but was:<8>`) read like a production bug. **When the property under test is a
  running total, the collection has to be invoked member-by-member and measured by a before/after
  delta** — a counter asserted against a pre-collected result list proves nothing about ordering,
  and it fails with a number that looks like a defect in the code rather than in the test.
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
- **The round-trip half of that is a shape worth naming, because here the code was right and the
  property was wrong.** A suite for the ported `java.net.URLEncoder` asserted
  `percentDecode(formUrlEncodeComponent(x)) == x`, which is false for exactly one input, `"a b"`:
  it encodes to `a+b`, and the decoder is *Uri's path* decoder, which does not read `+` as a space.
  **The pair that must not collide is the pair the server sees, not the pair a path decode can
  tell apart** — `formUrlEncodeComponent` is injective on the encoded string, which is what keeps
  `tt1234567:1:5` and `tt1234567-1-5` from naming the same resource, and no local decoder can
  check that. So the property is replaced by injectivity over a table of near-miss inputs, and
  **a codec's own decoder is the wrong instrument for a codec's property: it is a different
  decoder, with a different alphabet.**
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
  *order*. **An assertion whose *type* contradicts its intent still compiles**, and the type
  system is no help at all: `assertNull(value == false)` type-checks because `assertNull` takes
  `Any?`, so a `Boolean` is an acceptable argument to an assertion about nullness. It then fails,
  and the failure is the only witness — `null == false` is `false` in Kotlin, never a `null`.
  The second half is that the *name* is a claim too, and a rename is part of a behaviour change
  rather than a follow-up to it: two cases here were named
  `aPresentButUnreadableBooleanIsFalseAndNotNullBecauseNothingCanBeNullHere` and
  `theTwoWaysToGetNullAreBothAboutTheKeyAndNeitherIsAboutTheValue`, and both names state the old
  policy in prose, so both had to move with their bodies.
  **The mirror image is a name WIDER than its body, where the code is right.** A case called
  `anExpiredEntryIsGoneAndStaysGoneBecauseItIsRemoved` asserted that an expired cache entry is
  *removed*, and `cachedStreams` answers `null` for a stale key whether or not it was evicted, so
  **"answers null" and "does not keep the entry" are two different facts and a contract-only suite
  cannot tell them apart.** Deleting `cache.remove(...)` left every one of 24 assertions green. The
  name claimed a fact the body could not reach, and the fix was not to weaken the name.
- **A path that returns early does not run the shared tail, so a suite written from the method's
  *name* asserts a completion the code never logs.** `pullToLocal`'s "no active session" arm
  logs one line and returns; `logOutcome` sits after the `try`, so a skip produces **no** `pull
  completed` line. The failing expectation read `[\"pull skipped: no active session\", \"pull
  completed\"]` and was wrong about the second entry. This is the same defect as a name wider
  than its body, through the other door: **read the early return, because the shared tail is
  after it and a suite cannot tell an unexecuted tail from a wrong message.**
- **Robolectric's `android-all` lives in `~/.m2`, not in the Gradle cache, and a `find` in the
  wrong place is evidence of nothing.** `:backend`'s first host test wants a real `org.json` rather
  than a `Context`, and `find ~/.gradle/caches -iname '*android-all*'` returned **0 results** on
  the host where Robolectric then ran 33 tests without fetching anything. The jar is at
  `~/.m2/repository/org/robolectric/android-all-instrumented/15-robolectric-12650502-i7/`, and
  Robolectric's **`15` is Android 15, i.e. API 35** — so the existing `sdk = [35]` rule is also a
  cache rule: **the SDKs a host has needed are the only ones it has fetched, so the absence of the
  one you just asked for is absence of evidence, not evidence of absence.** Reading that as
  "Robolectric cannot run here" nearly cost this landing.
- **The library that ships is not the library a JVM test can stand in for, and for `org.json` the
  two disagree on exactly the input a guard is written for.** `android-all` carries AOSP's
  `libcore/json`; `org.json:json:20240303` — the artifact in this repository's Gradle cache, used
  by `:plugins` — is a *different* implementation. Measured, in both directions:
  **`optString` of a `JSONObject.NULL` is `"null"` on AOSP and `""` on the reference**, while `opt`
  of one is `JSONObject.NULL` on both and of an absent key is Java `null` on both; and a
  **fractional** number is `Double` on AOSP and `BigDecimal` on the reference, while **both agree
  on every whole number** (`1`→`Integer`, `-7`→`Integer`, `3000000000`→`Long`). **So there is no
  common answer for a fractional number, which is the argument for reading every JSON number as
  `Number`** — exactly what `optIntOrNull`'s `is Number ->` / `is String ->` arms do, and the only
  reason they were portable before anyone measured any of this. `optBoolean` is strict on **both**
  (`Boolean`, or case-insensitive `"true"`/`"false"`; `"yes"`, `1`, `"1"` all→`false`), so a suite
  pinned against either one agrees about it. **The substitute's answer is not the platform's
  answer, and on a parsing boundary the substitute's answer is the one a JVM test reports.**

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

Every rule here is also stated in each driver's docstring, because a driver runs unattended.

- **A survivor is the absence of a failure _after positive evidence the task ran_; an empty failure
  list is not one.** `subprocess.run(env=…)` **replaces** the environment, stripping `PATH` and
  `JAVA_HOME`, so `./gradlew` never starts. Use `env = {**os.environ, ...}` and give the driver a
  `NO EVIDENCE` verdict requiring `BUILD SUCCESSFUL`/`BUILD FAILED` in the output. **When a driver
  reports every entry surviving at once, suspect the driver before the code.**
- **Read an entry's `old`/`new` as code, not its `name`/`why`.** Prose can describe a mutation the
  patch does not implement, and the verdict then reads exactly like a genuine gap. The tell is *a
  comment reasoning carefully about an outcome its code cannot produce.*
- **Read every replacement as code: does it change an answer, and does it compile?** Nine shapes that
  all look like competent edits — a default *parameter*; a default on a **data-class constructor
  property** (the most deceptive, the diff reads as a behaviour change); `?: return null` in an
  expression body; a `?: ""` arm on an `if/else` expression body; a repeated declaration prefix;
  `x?.y?.z().w()` (a `?.` chain covers only the next call); a rewrite *identical* to the original
  because the value already was; renaming `runCatching` to `run`; appending a comment or `+ 0L`.
  **A narrowing over a key two fields populate identically is not a mutation at all** — read the
  value, not the name, or the entry can only report a false positive. `COMPILE FAILED` is not
  evidence.
- **Print the task's own failure lines once before trusting the regex that reads them.**
  `desktopTest` prints `Class[desktop] > method FAILED`, the host task prints `Class > method FAILED`.
  **Never pass `-q`** — it suppresses those lines, so the verdict survives and the evidence does
  not. Truncate the driver's log **per entry**; a superset of the `expect` set means the log is
  shared.
- **Never pass a method name to `--tests` — it names a class.** A filter matching nothing fails as
  `BUILD FAILED` with no `e:` and no `FAILED` line, which a name-reading driver scores SURVIVED. So
  "many survivors, no compile errors" is a driver verdict before it is a suite verdict. **The
  companion half is to make sure the task *owns* the class**, because the symptom is
  indistinguishable from a compile failure: `com.crispy.tv.platform.SecretFormatTest` is
  `platform-core/src/commonTest`, and filtering for it on `:core-domain:desktopTest` returned
  `EXIT=1` with **zero `e:` lines and zero tests found** — the same `EXIT=1`-with-no-`e:`
  signature as task selection.
- **Pass `--no-build-cache`, and guard on `> Task … (FROM-CACHE|UP-TO-DATE)` as text.** A
  `FROM-CACHE` task never compiles the mutated source and reports `BUILD SUCCESSFUL in 1s` — **a
  one-second green from a task you just perturbed is not a result.**
- **Read the driver's `(failures seen: …)` line before writing any code.** It is the cheapest
  evidence in the workflow, and a stale `expect` list looks exactly like a genuine survivor and is
  invisible in the tally. **A caught mutation is evidence that _some_ test caught it, not that the
  one you would have pointed at did** — a case whose name claims a rule its body does not check is
  a hole shaped like coverage, and a name narrower than its body is the same defect.
- **A test-filtered task only observes the source sets it compiles.** `:app:desktopTest` cannot see
  an `androidMain` mutation however extreme; `testAndroidHostTest` compiles both. *"This cannot be
  observed" is only ever a claim about the task you ran* — the fix is a different task, not a
  deleted entry. **A branch inside a `@Composable` body is the one thing that really cannot be
  observed**: it needs a rendering harness, and the goldens do not render every screen. The
  decision worth testing there is the caller's.
- **A guard can be masked by a neighbouring condition, and masking is indistinguishable from
  absence.** Write down what each world would answer; if the strings are equal, the case is
  decoration. The discriminator is the input that **reaches the guard you meant and fails only
  there** — a purely numeric `"5:7"` for an arity check masked by a numeric check; an uppercase
  host carrying a _lowercase_ `v=` for an `ignoreCase` gate.
- **A surviving mutation is a claim about the code, so check it by hand.** Read the callee. If the
  guard is genuinely unreachable, keep it and write the measurement **at the guard**. **Two lines
  that look redundant and together cover one rule is the shape to watch for** — ask which one a
  reader would delete if the other were gone.
  **A survivor is also reachable and merely *unobservable*, which is a different finding from an
  unreachable guard.** Read the callee before either conclusion: this `cache.remove` was live, and
  the alternative was to keep it untested. The answer is to **widen the observation rather than delete
  the behaviour**, an `internal fun cachedEntryCount()` (the same move as widening a `private` member
  to `internal`, which `internal` then keeps out of the consumer module), and then *re-run the same
  mutation*, which failed exactly one case. Padding the driver with `expect_survive` here would have
  recorded a real leak as an accepted risk.
- **A set of names is derived by subtracting names, never values.** Ten of `CrispyPalette`'s 37
  roles share `0xFFFFFFFF`, so a value-based difference deleted the very roles the assertion was
  about and passed for the wrong reason. Because so many share a value, **no value assertion can
  tell them apart** — the suite must name the interchangeable set, and a test that cannot cover one
  case asserts the set of uncovered cases (`:tv`'s `scrim` equals the library's own default, so a
  mapped `scrim` and a dropped one are indistinguishable).
- **A port's implementation is the file most worth covering** — it is what a `commonTest` suite
  cannot reach at all, especially an implementation behind a port created in the same landing.
- **A mutation entry list is a design decision, and padding it with `expect_survive` states the
  opposite of the truth.** A defect whose only symptom is *not compiling for a platform* has no
  local observation path, so the driver says so in prose and names the remote gate.
- **Mechanics.** Run a `check_anchors()` pre-flight and print `SKIP … anchor occurs Nx` — never
  count it as a pass; on a 0×, grep the file, because zero occurrences means the code is gone and
  some means your anchor is wrong. Restore in a `finally` with a printed `restored:` line, end with
  `if __name__ == "__main__": main()`, and print `of len(selected)` so a narrowed `RECHECK` run
  cannot be mistaken for a full one. `Pattern.finditer(s, re.M)` does not set a flag and does not
  raise — on a *compiled* pattern the second argument is `pos`, so `^` can never match again and the
  scan returns empty, which reports "no test failed" for a suite that failed by name; use
  `re.compile(p, re.M)` and scan with one argument. **Take a capture-group index from the pattern
  in front of you**, not from a sibling driver.

### 6. Editing safely

- **Never rewrite source with a regex.** It cannot see inside comments or string literals, and a
  "sound" cleanup rule once self-matched on its own package and deleted 140 live imports. For a
  mechanical change, let the compiler find the sites, or parse properly — the character-scanner
  lexer in `scripts/verify_kmp_outputs.py` is the model.
  **The same class of self-reference is reachable from a KDoc, and it produced 47 cascading errors.**
  Writing the pathspec literally as ``git grep -l 'import okhttp3' -- '*/src/commonMain'`` inside a
  block comment closes it: the `*/` in `*/src/commonMain` **is a comment terminator**, so everything
  after it was parsed as Kotlin and the file answered `Syntax error: Expecting a top level
  declaration` from the line holding the KDoc. The fix is the form that also works anyway,
  `git grep -l 'import okhttp3' | grep '/src/commonMain/'`, because `git grep -- '*/src/commonMain'`
  matches *nothing* (the pathspec needs the `src/commonMain/` grep form or no leading `*`), so the
  unrunnable command was also the useless one. **A command quoted into a comment is code, and `*/`
  is in every glob.**
- **A python patch must compute every new content before it opens any file for writing, assert each
  anchor exactly once, and read the region back afterwards.** Three failures, each of which
  destroyed something or hid it:
  - **`open(path, "w")` truncates before the argument is evaluated**, so a rewrite that computes its
    content *inside* the `write()` call raises on a bad anchor **after** the file is already empty.
    A `results` dict of every new content, written only at the end, also means an assertion failure
    on the seventh file leaves the first six untouched.
  - **Replacements must be applied cumulatively.** Computing two replacements from the same original
    and writing both loses the first — and every `occurs 1x` assertion still passes.
  - **A count assertion does not check the brackets around what it replaced**, and only `diff` sees
    a four-space shift.
- **Structural line-range edits must precede string replacements earlier in the same file.** A
  replacement changes the line count and moves the range out from under the edit.
- **An anchor copied from a tool's rendered output can differ in one character** — the source had
  an em-dash `—` where I typed `--`, giving `occurs 0x`.
- **A count you assert while patching is a claim about the code, and the code is the only thing
  that settles it.** A patch asserting a guard occurs "4 times" aborted with `AssertionError: 5` and
  wrote nothing — the guard was five-armed, and the "four times" figure in the KDoc was wrong for
  the same reason. The assertion did its job; the number was the error.
- **A file's first line is not its `package` line.** A `@file:OptIn` or `@file:JvmName` may come
  first, so a patch rewriting the region above the package declaration eats the package statement
  and leaves the import pasted onto it. **The signature is one distinct unresolved name
  (`CrispyPalettepackage`) inside a 328-error cascade**; find the mangled name before reading the
  cascade, and locate the package line by regex rather than by indexing line 0.
- **Inserting a declaration between an annotation and its target silently re-targets the
  annotation**, and the main compile will not catch it — a `@Composable` function returning `String?`
  is legal. It surfaces in the *test* compilation as "must be marked @Composable" plus an error at
  every call site. **When every error is that, the annotation is misplaced, not the calls.**
- **Extracting a decision out of a `val` can destroy a smart cast the branch depended on.** The fix
  is a safe call that is behaviourally identical, and the compiler is right to complain.
- **A "no forbidden token remains" guard must be scoped to code, not prose** — you will write about
  the token in the sentence that documents its removal.
- **`git mv` preserves mtime**, so Gradle's incremental Kotlin compile can skip a moved file and
  report success with no class produced. Compile a moved file once with `--rerun-tasks`.
- **After any revert, check `git status --short` for ` D` in the index** and compile *both* source
  sets. A revert that only compiles the source set you moved *from* proves nothing, and a partial
  revert looks exactly like a completed one. `git restore --staged --worktree <path>` is the
  reliable restore; the index is the part people forget.
  **And the index is not a backup of your work, it is a backup of your last commit, which is why
  `git checkout --` is the wrong tool for undoing a mutation.** Restoring a mutated file that has
  *uncommitted rewrites* silently reverted the entire port, because the index still held the file as
  it was at `git mv` time, which is the untouched `androidMain` original. Two tells, both printed and
  both misread at the time: the restore script reported `0` for a grep it had expected to match, and
  the next mutation's run came back `compileKotlinDesktop FAILED` **for a mutation that removes a
  statement and therefore cannot fail to compile**. **Copy the file to `/tmp` before mutating and
  copy it back after**, and `git restore --staged --worktree <path>` stays the right tool for the case
  it is actually for: undoing a *revert*.

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
- **A class file in the output can outlive the declaration that produced it, and the cause is not established.** It happened once here (`CrispyBackendClient$ResponsiveImageSet.class` survived the extraction that removed the nested type, and a `clean` plus a normal build left it visible). A stale class binds a reference that should have failed to compile, so a later green build certifies nothing and **every other gate in this repository becomes unreliable**. That risk is real even though the mechanism is unexplained — **and the two obvious explanations were tested and are both false**, so do not repeat them as established: removing a nested declaration and recompiling incrementally deletes its class correctly, and a `git mv` between source sets does not make Gradle skip the file. `scripts/verify_kmp_outputs.py` therefore exists as a *detector* rather than a fix: it asserts no compiled class has no source declaration, and it is proven by injecting orphans. It runs at the end of `check-local.sh` and in `android.yml`, and it reads `build/classes/kotlin`, so it must run *after* the compile tasks. A CI runner is always clean, which is why the check belongs to the local gate. **If it ever fires on an artefact you did not inject, that is the observation which explains it** — capture it rather than reaching for a mechanism.
- **The detector has to know how Kotlin names a file facade when the file name contains a dot.** It infers `<FileName>Kt` from the source stem, and the compose-resources plugin names its generated accessors `Drawable0.commonMain.kt`, whose facade is `Drawable0_commonMainKt`. Using the stem verbatim reported all 111 migrated resources as orphans — indistinguishable from the stale-output breakage the gate exists to catch, on a tree that was entirely clean. **A safety gate that fires on correct code gets switched off, so keep its notion of "correct" in step with the code generators it reads.** Re-prove it after any change by injecting all three orphan shapes: a top-level class, a nested class whose owner does not name it, and a facade whose name contains a dot.
  **A second shape fired on the very next landing, and it is a hole in the *rule* rather than in the
  naming: the dot handling above was already correct, and the file that broke it had no dot in it.**
  `actual typealias JvmSynchronized = kotlin.jvm.Synchronized` in `JvmSynchronizedAndroid.kt` emitted
  `JvmSynchronizedAndroidKt`, and the gate reported that class as a stale output on a tree where
  nothing was stale. Two independent reasons, either of which alone is enough: **`TYPEALIAS` matched
  `^typealias` and not `^actual typealias`**, so the line fell through *every* branch — not
  `TYPEALIAS`, not `EXPECT`, not `DECL`, not `TOP_LEVEL_MEMBER` — and `has_facade` stayed false; and
  **the rule itself was wrong**, because a top-level `typealias` declares no type and no property and
  *still* emits a facade. **`expect annotation class` was measured and deliberately still skipped** —
  `JvmSynchronized.kt` declares one and emitted no facade on Android, because nothing actualizes it
  there — so the two look alike in the source and answer differently on the classpath, which is
  exactly the kind of pair a reader will collapse. *An `actual typealias` is the shape to check
  whenever an expect/actual pair lands, because it is the one declaration whose facade exists on one
  target and not on another.*
- **Never rewrite source with a regex** — see §6, where the rule and the backend-extraction revert it caused are recorded. Kept here as a pointer because this is the section a workflow author reads first, and the rule has twice destroyed source while failing silently.
- Names are platform + intent, not Gradle build type. Do not reintroduce `debug`/`release` into workflow names; "debug CI" and "debug build" are different things.
- `verify_apk_distribution.py` reads the **dex** and is only valid on unminified builds, so it runs in `android.yml` (debug) and not in `android-release.yml`. Release asserts via `verifyDistributionExclusions`, which reads the dependency graph and is minification-proof.
- **Flavors live only in `:androidApp`, and that is not negotiable.** AGP's `com.android.kotlin.multiplatform.library` has **no** `productFlavors` at all (unlike `com.android.library`/`com.android.application`), and a KMP library cannot even *consume* a flavored `com.android.library`: the library plugin is single-variant, so it states no preference between a dependency's `store*` and `sideload*` variants and Gradle fails with an ambiguous-variant error naming every candidate. Two modules were forced off that axis by this and both losses turned out to be dead weight: `:android:network` (the YouTube extractor, now the sideload-only module `:android:youtube-extractor` reached through the `TrailerExtractor` interface) and `:android:plugins`, whose entire `store` source set was one unread `internal val PluginsRuntimeSupported = false` that was never even compiled. Do not reintroduce `matchingFallbacks` to paper over this — it would compile `:app` against the store variant while a sideload APK shipped the sideload one, the same class of lie as the unwired torrent resolver fixed in `911f8d75`.
- Distribution is a permanent two-flavor axis: `store` (Play/App Store) and `sideload` (APK/IPA). Optional engines are excluded **structurally** — the torrent engine, the QuickJS plugin runtime and the YouTube extractor are separate modules that only `sideload` depends on. Never reintroduce a null-returning stub for something the store build should simply not contain. Two guards enforce this: `./gradlew :android:androidApp:verifyDistributionExclusions` reads the resolved dependency graph, and `scripts/verify_apk_distribution.py` reads the built dex and asserts in both directions.
- The flavour axis does not stop the migration; it moves with the entry point. `:app` *is* now a flavor-less KMP library, because the KMP plugin cannot carry `productFlavors` while the flavour axis is a product requirement. `:androidApp` is the `com.android.application` that declares them, and `:app` reaches the variant through the `DistributionComponents` seam rather than through source sets.
- Apple targets are declared but cannot compile on Linux. Never run aggregate tasks (`build`, `check`, `allTests`); they reach the Kotlin/Native targets and fail. Use `./check-local.sh` or targeted tasks.
- **One gate per tree, and a unique log path per run — a `GATE_EXIT` read from a log two processes wrote to is not a result.** Launching a second `check-local.sh` while the first was in flight, both redirecting to the same file with `>`, truncated the log the first was writing and interleaved the two: the failed run ended in `BUILD FAILED` with **zero `e:` lines, no `What went wrong`, and mangled task lines like `> Ta> Task …`**, which reads as a compile failure and is not one. The clean second run returned `0` on the same tree with nothing changed. *Gradle serialises its own tasks, which is exactly why the assumption that a second gate is harmless feels safe — the corruption is in the redirect, not in the daemon.* Give each run its own `/tmp/opencode/gate-<name>.log`, and if a failure's only evidence is a mangled log, **re-run before diagnosing: a `BUILD FAILED` with no `e:` line and no `What went wrong` is task selection or harness noise, not compilation.**
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
