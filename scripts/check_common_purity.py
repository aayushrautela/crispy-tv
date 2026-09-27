#!/usr/bin/env python3

"""Fail the build when platform-specific imports leak into a KMP commonMain.

The Kotlin compiler already enforces this for declared targets, but only for
targets you happen to build. CI proves Android + desktop JVM; Apple targets are
only compiled on macOS. This check is the cheap, host-independent gate that
runs everywhere.

Scans every `src/commonMain` tree in the repo for imports that cannot resolve
in a platform-neutral source set.
"""

import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

# Imports that cannot resolve in a platform-neutral source set.
#
# `androidx.compose.*` is deliberately NOT forbidden. Compose Multiplatform
# 1.11.x republishes the androidx.compose packages for every target -- verified
# by unzipping the resolved AARs: 790 androidx/compose classes in
# runtime-android, 1336 in ui-android, and zero org/jetbrains/compose classes
# in any of them. JetBrains converged the two namespaces, so `org.jetbrains.compose.*`
# imports are the ones that are actually wrong now, and those do not compile
# anywhere. :android:sharedUI is a Compose module and legitimately imports them
# from commonMain.
#
# Consequence, and it is an accepted one: an *Android-only* androidx.compose API
# in a commonMain (e.g. LocalConfiguration) passes this grep and is caught by the
# compiler instead, because :android:sharedUI declares desktop and iOS targets
# that do not have it. check-local.sh compiles the desktop target, so the inner
# loop still fails fast on a Linux host.
FORBIDDEN_PREFIXES = (
    "android.",
    "androidx.activity",
    "androidx.annotation",
    "androidx.appcompat",
    "androidx.core",
    "androidx.fragment",
    "androidx.lifecycle",
    "androidx.media3",
    "androidx.navigation",
    "androidx.paging",
    "androidx.room",
    "androidx.work",
    "dalvik.",
    "java.",
    "javax.",
    "kotlinx.android.",
    "com.android.",
    "com.google.android.",
)

# `java.*` was previously allowlisted in six :core-domain files while every
# declared target was JVM. Those files are clear, so the exemption is gone: a
# java import in any commonMain now fails everywhere, which is what an Apple
# target on that module requires.

# `import a.b.C` / `import a.b.C as D` at any indentation.
IMPORT_RE = re.compile(
    r"^[ \t]*import[ \t]+(" + "|".join(re.escape(p) for p in FORBIDDEN_PREFIXES) + r")",
    re.MULTILINE,
)


def common_main_roots() -> list[Path]:
    return sorted(REPO_ROOT.glob("android/*/src/commonMain"))


def scan(root: Path) -> list[tuple[Path, int, str]]:
    violations: list[tuple[Path, int, str]] = []
    for source in sorted(root.rglob("*.kt")):
        text = source.read_text(encoding="utf-8")
        for match in IMPORT_RE.finditer(text):
            token = match.group(1)
            line = text.count("\n", 0, match.start()) + 1
            violations.append((source, line, token))
    return violations


def main() -> int:
    roots = common_main_roots()
    if not roots:
        print("check_common_purity: no commonMain source sets found; nothing to check")
        return 0

    total = 0
    for root in roots:
        violations = scan(root)
        if not violations:
            print(f"  ok  {root.relative_to(REPO_ROOT)}")
            continue
        print(f"  FAIL  {root.relative_to(REPO_ROOT)}")
        for source, line, token in violations:
            print(f"        {source.relative_to(REPO_ROOT)}:{line}: imports '{token}'")
        total += len(violations)

    if total:
        print(
            f"\ncheck_common_purity: {total} platform import(s) in commonMain.\n"
            "commonMain must compile for every declared target, including desktop "
            "JVM and iOS.\nMove the code to androidMain, or hide the platform type "
            "behind an interface in :android:platform-core."
        )
        return 1

    print(f"\ncheck_common_purity: {len(roots)} commonMain source set(s) clean")
    return 0


if __name__ == "__main__":
    sys.exit(main())
