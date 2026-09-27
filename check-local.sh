#!/usr/bin/env bash
# Local verification entry point for the KMP migration.
#
# Deliberately lists individual tasks instead of relying on `build` / `check` /
# `allTests`: the KMP modules declare iosArm64 and iosSimulatorArm64, and
# Kotlin/Native cannot compile Apple targets on a Linux host. Aggregate tasks
# reach those targets and fail for reasons unrelated to your change.
#
# Linux proves Android + desktop JVM. Apple targets are proven by macOS CI.

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
    :android:app:assemblePlayDebug \
    :android:app:assembleFossDebug \
    :android:tv:assembleDebug \
    "$@"
