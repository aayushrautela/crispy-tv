#!/usr/bin/env python3
"""
Prove DesktopImageSettingsTest is not vacuous.

Two mutations, each aimed at a specific guard the suite claims to cover:

  1. Remove the no-op guard in KeyValueStoreImageSettingsRepository. Expected to
     be caught by `choosing the quality already in effect is not a second write`.
  2. Point the environment's image store at the window store name. Expected to be
     caught by `image settings and window settings do not share a file`.

Rules honoured: anchors asserted exactly once, restore in a finally with a printed
`restored:` line, no `-q` (the per-test FAILED lines are the evidence), results read
from the task's own output, and the tally printed so a narrowed run cannot be
mistaken for a full one.
"""
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
TASK = ":android:desktopApp:test"
CLASS = "com.crispy.tv.desktop.DesktopImageSettingsTest"

REPO = ROOT / "android/app/src/commonMain/kotlin/com/crispy/tv/settings/ImageSettingsRepository.kt"
ENV = ROOT / "android/desktopApp/src/main/kotlin/com/crispy/tv/desktop/DesktopEnvironment.kt"

MUTATIONS = [
    (
        "no-op-write-guard",
        REPO,
        "        if (_settings.value.quality == quality) {\n            return\n        }\n",
        "",
    ),
    (
        "image-store-name",
        ENV,
        "FileKeyValueStore(dataDirectory, IMAGE_SETTINGS_STORE_NAME)",
        "FileKeyValueStore(dataDirectory, SETTINGS_STORE_NAME)",
    ),
]


def check_anchors():
    ok = True
    for name, path, old, _ in MUTATIONS:
        n = path.read_text().count(old)
        print(f"anchor {name} occurs {n}x in {path.name}")
        if n != 1:
            ok = False
    return ok


def run(label, path, old, new):
    original = path.read_text()
    assert original.count(old) == 1, f"{label}: anchor is not unique"
    path.write_text(original.replace(old, new, 1))
    try:
        proc = subprocess.run(
            ["./gradlew", TASK, "--tests", CLASS, "--rerun-tasks"],
            cwd=ROOT, capture_output=True, text=True,
        )
        out = proc.stdout + proc.stderr
        compile_errors = [l for l in out.splitlines() if l.startswith("e: ")]
        failed = [l for l in out.splitlines() if "FAILED" in l and ">" in l]
        if compile_errors:
            print(f"{label}: COMPILE FAILED -- not evidence ({len(compile_errors)} e: lines)")
        elif failed:
            print(f"{label}: CAUGHT")
            for line in failed[:4]:
                print(f"    {line.strip()}")
        elif proc.returncode != 0:
            print(f"{label}: build failed with no FAILED line -- treat as not evidence")
            for line in out.splitlines()[-12:]:
                print(f"    {line}")
        else:
            print(f"{label}: SURVIVED -- the suite does not cover this")
    finally:
        path.write_text(original)
        print(f"restored: {path.name} ({label})")


def main():
    if not check_anchors():
        print("ABORT: an anchor does not occur exactly once. Grep the file: "
              "0 occurrences means the code is gone.")
        sys.exit(2)
    for label, path, old, new in MUTATIONS:
        run(label, path, old, new)
    print(f"RECHECK complete, of len({len(MUTATIONS)}) entries")


if __name__ == "__main__":
    main()
