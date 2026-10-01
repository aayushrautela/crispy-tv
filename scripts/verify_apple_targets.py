#!/usr/bin/env python3
"""Every Kotlin module that declares an Apple target must be compiled for it in CI.

## Why this exists

Found by measurement on 2026-10-01, while checking whether a `platform-apple` module
had any consumer. The question turned up a different defect: **ten** Gradle modules
declared `iosArm64`/`iosSimulatorArm64`, and `.github/workflows/apple.yml` compiled
exactly **two** of them — `:core-domain` and `:platform-core`.

The eight others carried an Apple target that **no workflow, on any runner, ever
compiled**. A target nobody builds is a claim rather than a check: it cannot fail, so
it says nothing about whether the code in it is actually platform-free. `AGENTS.md`
records the same shape twice already — the `androidx.navigation` "not on
`commonMain`" claim and `LocalWindowInfo` — and both were settled by a measurement
somebody bothered to make. This script is the measurement, made cheap enough that the
next person does not have to remember to run it.

## The rule

A module that declares `iosArm64` must have a `compileKotlinIosArm64` task invoked in
`apple.yml`, and likewise for `iosSimulatorArm64`.

The converse is deliberately **not** checked. Removing a module's Apple targets is a
product decision, and this script has no business making it.

## Usage

    python3 scripts/verify_apple_targets.py

Exits non-zero and names every module whose target is declared but not compiled.
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
WORKFLOW = ROOT / ".github/workflows/apple.yml"
KOTLIN_DIR = ROOT / "android"

TARGETS = ("iosArm64", "iosSimulatorArm64")

# `> Task :android:app:compileKotlinIosArm64` is not how Gradle is invoked, so read
# what the workflow *runs*: `:android:app:compileKotlinIosArm64` as a bare token.
INVOKED = re.compile(r":android:([A-Za-z0-9_-]+):(compileKotlin(?:Test)?Ios\w+)")


def declared_modules() -> dict:
    """Module name -> the Apple targets its build file declares."""
    declared = {}
    for build_file in sorted(KOTLIN_DIR.glob("*/build.gradle.kts")):
        text = build_file.read_text()
        found = [target for target in TARGETS if re.search(rf"\b{target}\(\)", text)]
        if found:
            declared[build_file.parent.name] = found
    return declared


def invoked_tasks() -> set:
    """Task names the Apple workflow actually invokes."""
    return set(INVOKED.findall(WORKFLOW.read_text()))


def main() -> None:
    declared = declared_modules()
    if not declared:
        print("verify_apple_targets: no module declares an Apple target -- is the "
              "layout still `android/<module>/build.gradle.kts`?", file=sys.stderr)
        return 1

    # Compare case-insensitively, and do it on purpose.
    #
    # The obvious spelling is `f"compileKotlin{target}"`, which builds
    # `compileKotliniosArm64` -- the target is `iosArm64` and the task is
    # `compileKotlinIosArm64`. That version of this script reported all twenty tasks
    # missing on a workflow that invoked every one of them, which is the failure mode
    # this repository has already paid for twice: a gate that fires on correct code
    # gets switched off, and then it protects nothing. A gate is proven by running it
    # against a deliberately broken input, not by reading it.
    invoked = {(module, task.lower()) for module, task in invoked_tasks()}
    missing = []
    for module, targets in declared.items():
        for target in targets:
            task = f"compileKotlin{target}"
            if (module, task.lower()) not in invoked:
                missing.append((module, task))

    print(f"verify_apple_targets: {len(declared)} module(s) declare Apple targets")
    for module in sorted(declared):
        print(f"  {module}: {', '.join(declared[module])}")

    if missing:
        print("\nDeclared but never compiled in .github/workflows/apple.yml:", file=sys.stderr)
        for module, task in missing:
            print(f"  :android:{module}:{task}", file=sys.stderr)
        print(
            "\nAn Apple target no workflow compiles cannot fail, so it asserts "
            "nothing. Add the compile task to the 'Compile Kotlin Multiplatform "
            "Apple targets' step, or remove the target declaration -- which is a "
            "product decision, not a mechanical one.",
            file=sys.stderr,
        )
        return 1

    print("verify_apple_targets: every declared Apple target is compiled in CI")
    return 0


if __name__ == "__main__":
    sys.exit(main())
