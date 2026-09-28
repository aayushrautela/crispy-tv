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
# :android:app is a KMP library whose sources are all still in androidMain (Phase
# 1 of kmp-migration-plan.md proves the module graph; Phase 4 moves them). Its
# commonMain is empty, so the purity gate has nothing to scan there yet -- but
# androidMain and desktopMain both have to compile.
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

# JVM args (including MaxMetaspaceSize=1g for the Kotlin/Native compiler) are
# set in ~/.gradle/gradle.properties. Passing -Dorg.gradle.jvmargs here would
# spawn a second daemon with different opts, so we let the user-level config apply.

./gradlew \
    :android:core-domain:compileKotlinDesktop \
    :android:core-domain:desktopTest \
    :android:core-domain:compileKotlinLinuxX64 \
    :android:platform-core:compileKotlinDesktop \
    :android:platform-core:compileKotlinLinuxX64 \
    :android:sharedUI:compileKotlinDesktop \
    :android:sharedUI:compileAndroidMain \
    :android:app:compileAndroidMain \
    :android:app:compileKotlinDesktop \
    :android:contract-tests:test \
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
