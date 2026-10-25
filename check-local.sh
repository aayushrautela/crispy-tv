#!/usr/bin/env bash
# Local verification entry point for the KMP migration.
#
# Deliberately lists individual tasks instead of relying on `build` / `check` /
# `allTests`: the KMP modules declare iosArm64 and iosSimulatorArm64, and
# Kotlin/Native cannot compile Apple targets on a Linux host. Aggregate tasks
# reach those targets and fail for reasons unrelated to your change.
#
# Linux proves Android and, via the compile-only linuxX64 target, Kotlin/Native.
# Apple targets are proven by macOS CI in .github/workflows/apple.yml.
#
# :android:app and :android:sharedUI have no linuxX64 target on purpose: both are
# Compose modules, and Compose Multiplatform publishes no linuxX64 artifacts, so
# declaring it makes every compose.* dependency fail to resolve. For :app the
# local non-Android signal is compileCommonMainKotlinMetadata -- it compiles
# commonMain against the metadata variant of every dependency, which is the only
# task that catches a JVM-only symbol the Android compile would accept.
# :android:core-domain, :android:platform-core and the other pure-Kotlin modules
# do use linuxX64, because that is the one Kotlin/Native target a Linux host can
# build and it enforces the same "no JVM API" rule for them.
#
# The contract suite lives in :android:core-domain's commonTest, not a module of
# its own, so `testAndroidHostTest` runs all 96 fixtures on the Android host and
# apple.yml runs the same suite on the simulator. `compileTestKotlinLinuxX64` is
# the gate that proves the suite holds no JVM API, which is what lets the Apple
# targets compile it at all -- and the import-based purity gate cannot see that
# class of bug, because it only scans commonMain.
#
# :android:app is a KMP library. Phase 1 proved the module graph; Phase 4 has
# since moved the design-agnostic half of it into commonMain (re-measure with
# `find android/app/src/commonMain android/app/src/androidMain -name '*.kt' | wc -l`
# per directory rather than trusting a number), so the purity gate now has real
# work to do there.
#
# :android:app:testAndroidHostTest is the composition root's gate and the only way
# the module is tested. It compiles both halves in one JVM: commonTest, which sees
# commonMain + appUi but not androidMain, so it covers the settings repositories
# and nothing in the composition root -- and androidHostTest, which sees
# androidMain and covers exactly the reverse. Robolectric is there for a `Context`
# and nothing else -- no view is inflated and no resource is read, so
# `@Config(manifest = Config.NONE)` is enough and these tests do not need the
# merged manifest or the real app theme a rendering test would require.
#
# :android:backend:testAndroidHostTest covers BackendContextResolver, which moved
# to commonMain when AccountApi replaced SupabaseAccountClient. The same suite runs
# on the Apple targets in apple.yml, which is what notices the file reaching for a
# JVM API again; a local Android-only run would not.
#
# :android:home:testAndroidHostTest covers the two home services that moved to
# commonMain -- CalendarService's bucketing and key building, and UpNextService's
# drop rules and clock fallback -- and :android:home:compileKotlinLinuxX64 is the
# native compile, the only check of the Apple targets a Linux host can make.
#
# :android:androidApp:testStoreDebugUnitTest runs the app module's Robolectric
# unit tests. The tests live in :androidApp rather than :app because they need
# the merged manifest and the real app theme.
#
# :android:tv:test runs the TV module's unit tests. The module is a plain
# com.android.application, so its tests are plain JUnit with no Robolectric: they
# can only assert pure decisions, and the ones that exist are the source ordering
# and the token mapping. The gate is here and in android.yml because the module
# had a test suite that no gate ran.
#
# :android:addons:testAndroidHostTest found the argument-order break nothing
# compiled: ten commonTest files plus one androidHostTest, and no gate ran either.
# UriBehaviourHostTest is Robolectric-only, for Android's `Uri`, so a Linux
# aarch64 host reports eleven failures and every one of them is
# `DefaultNativeRuntimeLoader` -- the native runtime, not the code. An x86_64
# runner is where those tests are meaningful.
#
# :android:watchhistory, :player, :network, :platform-core and :sharedUI host
# tests are one to three files each and sat in the same hole: small suites that no
# gate compiled, which is how a break survives review.

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

# Every module declaring an Apple target must have it compiled in apple.yml. Ten
# modules declared them and two were built, which is a claim rather than a check.
# Runs right after the workflow validator because it reads that same file.
"$PY" scripts/verify_apple_targets.py

# The module graph's one invariant: a commonMain source set may only depend on
# another commonMain. Nothing in the Gradle build or the Kotlin compiler states
# this, because it is not a resolution failure -- a commonMain edge into a plain
# com.android.library resolves on Android and is simply unavailable everywhere
# else, so the breakage appears on a target nobody was running. That is a
# property of `plugins { }`, which no other gate reads.
#
# It also reports same-package names resolved across a module boundary with no
# Gradle edge, which is invisible to the build graph by construction: a rename in
# the owning module breaks the consumer with no diff in the consumer. Those are
# classified UNDECLARED / PLATFORM / SHARED so the deliberate
# package-preservation technique is not reported as a defect.
"$PY" scripts/verify_kmp_structure.py

# JVM args (including MaxMetaspaceSize=1g for the Kotlin/Native compiler) are
# set in ~/.gradle/gradle.properties. Passing -Dorg.gradle.jvmargs here would
# spawn a second daemon with different opts, so we let the user-level config apply.

./gradlew \
    :android:core-domain:testAndroidHostTest \
    :android:core-domain:compileKotlinLinuxX64 \
    :android:core-domain:compileTestKotlinLinuxX64 \
    :android:platform-core:compileKotlinLinuxX64 \
    :android:sharedUI:compileAndroidMain \
    :android:app:compileCommonMainKotlinMetadata \
    :android:app:compileAndroidMain \
    :android:app:testAndroidHostTest \
    :android:backend:testAndroidHostTest \
    :android:home:compileKotlinLinuxX64 \
    :android:home:testAndroidHostTest \
    :android:addons:testAndroidHostTest \
    :android:watchhistory:testAndroidHostTest \
    :android:player:testAndroidHostTest \
    :android:network:testAndroidHostTest \
    :android:platform-core:testAndroidHostTest \
    :android:sharedUI:testAndroidHostTest \
    :android:androidApp:verifyDistributionExclusions \
    :android:androidApp:testStoreDebugUnitTest \
    :android:androidApp:testSideloadDebugUnitTest \
    :android:androidApp:assembleStoreDebug \
    :android:androidApp:assembleSideloadDebug \
    :android:tv:test \
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
