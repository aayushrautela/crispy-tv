#!/usr/bin/env python3
"""Mutation driver for `:android:tv`'s first unit test — the token mapping.

`android/tv` had no `src/test` of any kind, and `AGENTS.md` recorded the reason as
":tv is a plain com.android.application". That reason was wrong: `CrispyTvDarkColors`
is a top-level `val` calling `darkColorScheme(...)`, which builds a `ColorScheme` data
class out of `androidx.compose.ui.graphics.Color` — an inline value class over `ULong`.
No `Context`, no resource, no view, so a plain JVM unit test reaches it. The blocker
was the `private` modifier, which the same landing widened to `internal`.

    python3 scripts/mutate_tv_theme.py
    RECHECK=3 python3 scripts/mutate_tv_theme.py

**Two entries are expected to survive, and that is the point of the file.**
`expect_survive` marks them. They are the two limits the suite's KDoc states out loud:

* Ten of `CrispyPalette`'s 37 roles carry the identical value `0xFFFFFFFF`, so a swap
  among those ten leaves every assertion green.
* `CrispyPalette.scrim` equals `androidx.tv.material3`'s own default for `scrim`, so
  **no** assertion can tell a mapped `scrim` from a dropped one.

Both are reported as `EXPECTED SURVIVOR` rather than as findings, because a survivor
here is a measurement about what a value assertion can prove, not a gap. A survivor
that is *not* marked is a finding, and the driver says so.

Rules this driver obeys, and why each is here:
* `env = {**os.environ, ...}` — `subprocess.run(env=…)` replaces rather than merges, so
  passing only the additions strips `PATH` and `JAVA_HOME` and `./gradlew` never starts.
* An empty failure list is not a survivor. `NO EVIDENCE` is a third verdict, taken when
  the log contains neither `BUILD SUCCESSFUL` nor `BUILD FAILED`, or when the test task
  reports `FROM-CACHE`/`UP-TO-DATE` — a one-second green from a task you just perturbed
  is not a result.
* Never `-q`; never filter with `--tests` (it names a *class*, and a filter matching
  nothing fails the task in a shape a driver reads as a survivor); `--no-build-cache`
  on every invocation.
* The log is truncated **per entry**, so a caught entry's failure list is its own.
* `re.compile(..., re.M)`, then `findall` with one argument.
"""

import os
import re
import subprocess
import sys
import glob
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LOG = Path("/tmp/opencode/mutate-tv-entry.log")
TASK = ":android:tv:testDebugUnitTest"
THEME = "android/tv/src/main/java/com/crispy/tv/tv/ui/theme/Theme.kt"

FAILED_LINE = re.compile(r"^\s*(\S+)\s+>\s+(\S+)\s+FAILED", re.M)
STALE_TASK = re.compile(rf"^> Task {re.escape(TASK)} (FROM-CACHE|UP-TO-DATE)$", re.M)
CLASS_GROUP, METHOD_GROUP = 1, 2

ENTRIES = [
    {
        "name": "the border role is wired to outlineVariant",
        "file": THEME,
        "old": "border = CrispyPalette.outline,",
        "new": "border = CrispyPalette.outlineVariant,",
        "expect": [
            "everyMappedRoleCarriesThePaletteValueItClaimsTo",
            "theBorderRolesAreTheOutlinesUnderTheOtherLibrarysNames",
        ],
    },
    {
        "name": "the borderVariant role is wired to outline",
        "file": THEME,
        "old": "borderVariant = CrispyPalette.outlineVariant,",
        "new": "borderVariant = CrispyPalette.outline,",
        "expect": [
            "everyMappedRoleCarriesThePaletteValueItClaimsTo",
            "theBorderRolesAreTheOutlinesUnderTheOtherLibrarysNames",
        ],
    },
    {
        "name": "primary is wired to onPrimary",
        "file": THEME,
        "old": "primary = CrispyPalette.primary,",
        "new": "primary = CrispyPalette.onPrimary,",
        "expect": ["everyMappedRoleCarriesThePaletteValueItClaimsTo"],
    },
    {
        "name": "scrim is wired to surfaceTint",
        "file": THEME,
        "old": "scrim = CrispyPalette.scrim,",
        "new": "scrim = CrispyPalette.surfaceTint,",
        "expect": ["everyMappedRoleCarriesThePaletteValueItClaimsTo"],
    },
    {
        # A line deletion, not a substitute value: `Theme.kt` imports no `Color`, and
        # deleting the line is the more faithful statement of "the role is not passed",
        # which is the mistake this suite exists to catch.
        "name": "onErrorContainer is dropped from the mapping",
        "file": THEME,
        "old": "    onErrorContainer = CrispyPalette.onErrorContainer,\n",
        "new": "",
        "expect": ["everyMappedRoleCarriesThePaletteValueItClaimsTo"],
    },
    {
        "name": "inverseSurface is wired to error",
        "file": THEME,
        "old": "inverseSurface = CrispyPalette.inverseSurface,",
        "new": "inverseSurface = CrispyPalette.error,",
        "expect": ["everyMappedRoleCarriesThePaletteValueItClaimsTo"],
    },
    {
        # EXPECTED SURVIVOR — the value-equal limit.
        "name": "onError is wired to onBackground (both 0xFFFFFFFF)",
        "file": THEME,
        "old": "onError = CrispyPalette.onError,",
        "new": "onError = CrispyPalette.onBackground,",
        "expect": [],
        "expect_survive": True,
    },
    {
        # EXPECTED SURVIVOR — the default-collision limit.
        "name": "scrim is dropped from the mapping entirely",
        "file": THEME,
        "old": "    scrim = CrispyPalette.scrim,\n",
        "new": "",
        "expect": [],
        "expect_survive": True,
    },
]


def failed_tests(output):
    return {f"{c}.{m}" for c, m in FAILED_LINE.findall(output)}


def failures_from_xml():
    names = set()
    for path in glob.glob(str(ROOT / "android/tv/build/test-results/**/*.xml"), recursive=True):
        root = ET.parse(path).getroot()
        for case in root.iter("testcase"):
            for child in list(case):
                if child.tag in ("failure", "error"):
                    names.add(f"{case.get('classname')}.{case.get('name')}")
    return names


def run_gradle():
    env = {**os.environ}
    env.pop("JAVA_TOOL_OPTIONS", None)
    LOG.parent.mkdir(parents=True, exist_ok=True)
    with open(LOG, "w") as handle:
        return subprocess.run(
            ["./gradlew", TASK, "--no-build-cache"],
            cwd=str(ROOT), env=env, stdout=handle, stderr=subprocess.STDOUT,
        ).returncode


def check_anchors(selected):
    bad = False
    for index, entry in selected:
        count = (ROOT / entry["file"]).read_text().count(entry["old"])
        if count != 1:
            print(f"SKIP entry {index} {entry['name']!r}: anchor occurs {count}x")
            bad = True
    if bad:
        print("\nFix the anchors before running. Zero means the code is gone; more than "
              "one means the anchor is too short.")
        sys.exit(2)


def main():
    recheck = os.environ.get("RECHECK")
    if recheck:
        selected = [(i, e) for i, e in enumerate(ENTRIES) if i == int(recheck)]
    else:
        selected = list(enumerate(ENTRIES))

    check_anchors(selected)

    caught, survived, expected, no_evidence, compile_failed = [], [], [], [], []

    for index, entry in selected:
        path = ROOT / entry["file"]
        original = path.read_text()
        print(f"\n=== entry {index}: {entry['name']}")
        try:
            if entry["old"] not in original:
                print("  ANCHOR GONE — not evidence")
                no_evidence.append(index)
                continue
            # Every new content computed before any file is opened, so a failure on
            # entry 7 cannot truncate entry 3.
            mutated = original.replace(entry["old"], entry["new"], 1)
            assert mutated != original, "replacement is identical to the original"
            path.write_text(mutated)
            subprocess.run(["rm", "-rf", str(ROOT / "android/tv/build/test-results")], check=False)
            run_gradle()
            output = LOG.read_text()
            if "BUILD SUCCESSFUL" not in output and "BUILD FAILED" not in output:
                print("  NO EVIDENCE — the task did not run")
                no_evidence.append(index)
                continue
            if STALE_TASK.search(output):
                print(f"  NO EVIDENCE — {TASK} was FROM-CACHE/UP-TO-DATE")
                no_evidence.append(index)
                continue
            if "e: " in output:
                print("  COMPILE FAILED -- not evidence")
                compile_failed.append(index)
                continue
            names = failed_tests(output) | failures_from_xml()
            hit = sorted(n for n in names for e in entry["expect"] if e in n)
            if hit:
                print(f"  CAUGHT by {hit}")
                print(f"  (all failures: {sorted(names)})")
                caught.append(index)
            elif entry.get("expect_survive"):
                print("  EXPECTED SURVIVOR")
                print(f"  (failures seen: {sorted(names)})")
                expected.append(index)
            else:
                print("  SURVIVED")
                print(f"  (failures seen: {sorted(names)})")
                survived.append(index)
        finally:
            path.write_text(original)
            print(f"  restored: {entry['file']}")

    print(f"\nTALLY of len(selected)={len(selected)}"
          f"  caught={len(caught)}  survived={len(survived)}"
          f"  expected_survivors={len(expected)}"
          f"  no_evidence={len(no_evidence)}  compile_failed={len(compile_failed)}")
    print(f"RECHECK = {survived}")
    if survived:
        print("\nAn unmarked survivor is a finding. Read the callee before writing a test "
              "for it, and record the measurement at the guard if the guard turns out to "
              "have no effect.")


if __name__ == "__main__":
    main()
