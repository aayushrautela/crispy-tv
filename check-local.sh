#!/usr/bin/env bash
# Local verification entry point for the KMP migration.
#
# Deliberately lists individual tasks instead of relying on `build` / `check` /
# `allTests`: the KMP modules declare iosArm64 and iosSimulatorArm64, and
# Kotlin/Native cannot compile Apple targets on a Linux host. Aggregate tasks
# reach those targets and fail for reasons unrelated to your change.
#
# Linux proves Android + desktop JVM. Apple targets are proven by macOS CI
# (.github/workflows/apple-ci.yml).
#
# :android:app:testStoreDebugUnitTest is the golden-screenshot gate. It verifies
# by default; re-record with
#   ./gradlew :android:app:testStoreDebugUnitTest -Proborazzi.record=true

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

./gradlew \
    :android:core-domain:compileKotlinDesktop \
    :android:core-domain:desktopTest \
    :android:platform-core:compileKotlinDesktop \
    :android:contract-tests:test \
    :android:app:verifyDistributionExclusions \
    :android:app:testStoreDebugUnitTest \
    :android:app:assembleStoreDebug \
    :android:app:assembleSideloadDebug \
    :android:tv:assembleDebug \
    "$@"

# Assert the built artefacts, not just the dependency graph: a store APK must
# not contain the torrent engine, the QuickJS plugin runtime or the YouTube
# extractor. Every ABI split is checked, and the sideload APKs act as the
# control that proves the markers still match.
"$PY" scripts/verify_apk_distribution.py \
    --store android/app/build/outputs/apk/store/debug/*.apk \
    --sideload android/app/build/outputs/apk/sideload/debug/*.apk
