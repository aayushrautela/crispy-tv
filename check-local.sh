#!/usr/bin/env bash
# Local verification entry point for the KMP migration.
#
# Deliberately lists individual tasks instead of relying on `build` / `check` /
# `allTests`: the KMP modules declare iosArm64 and iosSimulatorArm64, and
# Kotlin/Native cannot compile Apple targets on a Linux host. Aggregate tasks
# reach those targets and fail for reasons unrelated to your change.
#
# Linux proves Android, desktop JVM, and -- via the compile-only linuxX64
# target -- Kotlin/Native. Apple targets are proven by macOS CI
# .github/workflows/apple.yml.
#
# :android:app and :android:sharedUI have no linuxX64 target on purpose: both are
# Compose modules, and Compose Multiplatform publishes no linuxX64 artifacts --
# its targets are Android, iOS and Desktop (JVM) only -- so declaring it makes
# every compose.* dependency fail to resolve. desktop JVM is their local purity
# gate. :android:core-domain and :android:platform-core are pure Kotlin and do
# use linuxX64, because that is the one Kotlin/Native target a Linux host can
# build and it enforces the same "no JVM API" rule for them.
#
# The contract suite lives in :android:core-domain's commonTest, not a module of
# its own, so `desktopTest` and `testAndroidHostTest` both run all 96 fixtures.
# `compileTestKotlinLinuxX64` is the gate that proves the suite holds no JVM API,
# which is what lets the Apple targets compile it at all -- and the import-based
# purity gate cannot see that class of bug, because it only scans commonMain.
#
# :android:desktopApp is the seam proof: a real Compose Multiplatform render on a
# plain JVM. It needs libGL.so.1, libX11.so.6 and libfontconfig.so.1 for Skia, and
# a font (bundled in test-fonts/, pointed at by a generated fonts.conf). A bare
# container with none of those reports `Could not load font` or a Skiko native-load
# error, neither of which says anything about the seam -- see AGENTS.md.
#
# :android:app is a KMP library. Phase 1 proved the module graph; Phase 4 has
# since moved the design-agnostic half of it into commonMain (37 files at the
# time of writing, against 120 still in androidMain), so the purity gate now has
# real work to do there. commonMain is a strict subset of the Android build, so
# androidMain and desktopMain both have to compile and the two catch different
# mistakes. The measure of what is left, and why the rest of it is a refactor
# rather than a file move, is in kmp-migration-plan.md under "Phase 4, Step 6".
#
# :android:app:testAndroidHostTest is the composition root's gate. `:app` has no
# other way to be tested: `desktopTest` sees only commonMain + appUi, and the
# composition root is in androidMain. Robolectric is there for a `Context` and
# nothing else -- no view is inflated and no resource is read, so
# `@Config(manifest = Config.NONE)` is enough and these tests do not need the
# merged manifest or the real app theme the golden screenshots require.
#
# :android:backend:desktopTest covers BackendContextResolver, which moved to
# commonMain when AccountApi replaced SupabaseAccountClient. It runs on desktop
# rather than the Android host test on purpose: a test that only ran on Android
# would not notice that file reaching for a JVM API again.
#
# :android:app:desktopTest and :android:app:testAndroidHostTest are two halves of
# one module and neither replaces the other. `commonTest` reaches only commonMain,
# so it covers the settings repositories that moved onto `KeyValueStore` and
# nothing in the composition root; `androidHostTest` reaches androidMain and
# covers the opposite. A module that declares `commonTest` without
# `withHostTest {}` gets an AGP warning instead of an error, and its common tests
# then run on no Android target at all -- :android:backend had exactly that.
#
# :android:androidApp:testStoreDebugUnitTest is the golden-screenshot gate. It verifies
# by default; re-record with
#   ./gradlew :android:androidApp:testStoreDebugUnitTest -Proborazzi.record=true
# The tests live in :androidApp rather than :app because they need the merged
# manifest and the real app theme, while rendering composables that live in :app.

set -euo pipefail

cd "$(dirname "$0")"

# validate_contracts.py needs jsonschema, which lives in the repo venv.
if [[ -x .venv/bin/python ]]; then
    PY=.venv/bin/python
else
    PY=python3
fi

# Host-local purity gate: fails if platform imports leak into any commonMain.
"$PY" scripts/check_common_purity.py
"$PY" scripts/validate_contracts.py

# Workflow YAML is checked before the expensive tasks because a duplicate key
# makes a workflow fail to load: it produces a run that fails in under a second
# with an empty log, which reads as "the tests failed" when no test ever ran.
"$PY" scripts/validate_workflows.py

# JVM args (including MaxMetaspaceSize=1g for the Kotlin/Native compiler) are
# set in ~/.gradle/gradle.properties. Passing -Dorg.gradle.jvmargs here would
# spawn a second daemon with different opts, so we let the user-level config apply.

./gradlew \
    :android:core-domain:compileKotlinDesktop \
    :android:core-domain:desktopTest \
    :android:core-domain:testAndroidHostTest \
    :android:core-domain:compileKotlinLinuxX64 \
    :android:core-domain:compileTestKotlinLinuxX64 \
    :android:platform-core:compileKotlinDesktop \
    :android:platform-core:compileKotlinLinuxX64 \
    :android:sharedUI:compileKotlinDesktop \
    :android:sharedUI:compileAndroidMain \
    :android:app:compileAndroidMain \
    :android:app:compileKotlinDesktop \
    :android:app:desktopTest \
    :android:app:testAndroidHostTest \
    :android:backend:desktopTest \
    :android:backend:testAndroidHostTest \
    :android:desktopApp:compileKotlin \
    :android:desktopApp:test \
    :android:androidApp:verifyDistributionExclusions \
    :android:androidApp:testStoreDebugUnitTest \
    :android:androidApp:testSideloadDebugUnitTest \
    :android:androidApp:assembleStoreDebug \
    :android:androidApp:assembleSideloadDebug \
    :android:tv:assembleDebug \
    "$@"

# Assert the built artefacts, not just the dependency graph: a store APK must
# not contain the torrent engine, the QuickJS plugin runtime or the YouTube
# extractor. Every ABI split is checked, and the sideload APKs act as the
# control that proves the markers still match.
"$PY" scripts/verify_apk_distribution.py \
    --store android/androidApp/build/outputs/apk/store/debug/*.apk \
    --sideload android/androidApp/build/outputs/apk/sideload/debug/*.apk

# Assert the compiled output matches the sources, not just that the tasks
# reported success. An incremental compile can leave a class behind that no
# declaration produces any more -- `git mv` preserves mtime, and the Kotlin
# incremental compiler does not reliably delete the class of a declaration
# removed from a file it still considers current. A stale class binds a
# reference that should fail to compile, so it makes every other gate here
# meaningless. This is the same "verify the artefact" rule as the two above,
# applied to compilation; it reads build/classes/kotlin, so it runs after
# the gradle tasks. A CI runner is always clean and never sees this, which is
# exactly why the check belongs to the local gate.
"$PY" scripts/verify_kmp_outputs.py
