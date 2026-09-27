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
FORBIDDEN_PREFIXES = (
    "android.",
    "androidx.",
    "dalvik.",
    "java.",
    "javax.",
    "kotlinx.android.",
    "com.android.",
    "com.google.android.",
)

# `java.*` is legal in a commonMain whose every target is JVM, because the JDK is
# then on the common compile classpath. It stops being legal the moment an Apple
# target is declared, so it is allowed per-file rather than per-module: adding a
# java import to any other file, or to a module that also targets iOS, still
# fails.
#
# These six files are the tracked debt that blocks iosArm64 on :core-domain.
# Clearing them is the next step: `lowercase(Locale.X)` collapses to
# `lowercase()`, and the URLEncoder and java.time call sites need common
# replacements. Both are covered by the contract fixtures.
JAVA_ALLOWED_IN = frozenset(
    {
        "android/core-domain/src/commonMain/kotlin/com/crispy/tv/domain/catalog/CatalogUrlBuilder.kt",
        "android/core-domain/src/commonMain/kotlin/com/crispy/tv/domain/home/HomeCatalogPlanner.kt",
        "android/core-domain/src/commonMain/kotlin/com/crispy/tv/domain/metadata/MetadataTmdbEnhancer.kt",
        "android/core-domain/src/commonMain/kotlin/com/crispy/tv/domain/metadata/OmdbNormalizer.kt",
        "android/core-domain/src/commonMain/kotlin/com/crispy/tv/domain/sync/SyncPlanner.kt",
        "android/core-domain/src/commonMain/kotlin/com/crispy/tv/domain/watch/FindNextEpisode.kt",
    }
)

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
        rel = str(source.relative_to(REPO_ROOT))
        text = source.read_text(encoding="utf-8")
        for match in IMPORT_RE.finditer(text):
            token = match.group(1)
            if token == "java." and rel in JAVA_ALLOWED_IN:
                continue
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
