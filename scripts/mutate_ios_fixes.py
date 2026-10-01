#!/usr/bin/env python3
"""Mutation driver for the one part of the iOS-fixes landing a local test can see.

    python3 scripts/mutate_ios_fixes.py            # every entry
    RECHECK=2 python3 scripts/mutate_ios_fixes.py  # re-run entry N only

## Why this driver has four entries when the landing touched twenty-three files

The landing (`6c4d3116`) removed five classes of JVM API from `:app`'s `commonMain`:
`Dispatchers.IO` from nine construction sites, a `System.currentTimeMillis()` default
argument, a fully-qualified `java.time.LocalDate`, a `synchronized` block, and a stale
`androidx.compose.ui.res.painterResource` import.

**Four of those five classes cannot be observed by any test that runs on this host, and
that is a property of the defect class rather than a gap in this driver.** They were
defects *because* they do not compile for Kotlin/Native, and the only gate that sees
that is `:android:app:compileKotlinIosArm64` — which only `apple.yml` runs, on a macOS
or Linux runner with the Kotlin/Native toolchain downloaded. Concretely, each of these
mutations compiles cleanly on the JVM and changes no observable answer, because every
caller already passes the argument explicitly:

  * re-adding `private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO` to any
    of `DetailsViewModel`, `HomeSelectorViewModel`, `HomeViewModel`,
    `PersonDetailsViewModel`, `SearchViewModel`, `LibraryPagingSource`,
    `LibraryViewModel`, or `private val scope: CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.IO)` to `SubtitleRepository`;
  * restoring `nowMs: Long = System.currentTimeMillis()` in `UserMediaRepository`;
  * restoring `java.time.LocalDate.parse` in `HomeCalendarComponents` — and this one is
    *provably* unobservable locally rather than merely unobserved, because the JVM
    expression was measured to produce byte-identical output for all twelve months and
    every edge case in the KDoc of the function that replaced it;
  * swapping `ItemActionSheet`'s surviving `painterResource` import back to the
    `androidx.compose.ui.res` one, since neither artifact is on the JVM or Android
    classpath in the way the swap needs.

So those four would be `expect_survive` entries, and sixteen of them would be a tally
that looks like coverage while observing nothing. **They are recorded here as a list
instead, because the honest claim is "no local observation path exists" and a driver
full of expected survivors states the opposite.** `scripts/mutate_tv_theme.py` has two
genuine `expect_survive` entries because those mutations *are* unobservable in a way
that has nothing to do with the host; here the unobservability is a platform
divergence, and `apple.yml` is the gate that resolves it.

What *is* observable is the function the landing added to replace the `java.time`
call, and that is what these four entries cover — the month-table index, the day's
formatting, the parse it depends on, and the month set itself.

Driver rules this obeys, and why each is here:

* `env = {**os.environ, ...}` — `subprocess.run(env=…)` **replaces** the environment
  rather than merging it, stripping `PATH` and `JAVA_HOME` so `./gradlew` never starts.
  That reports "no test failed" for every entry against a log of `JAVA_HOME is not set`.
* `--no-build-cache` always, plus a text guard on `> Task … (FROM-CACHE|UP-TO-DATE)`.
  A `FROM-CACHE` test task looks like a completed run and the driver wrote down a
  confident verdict about code it had never built.
* Never `-q`, and never a method name in `--tests` — it names a *class*, and a filter
  matching nothing is a `BUILD FAILED` with no `e:` line and no per-test `FAILED` line,
  which a name-reading driver scores as 19 of 23 SURVIVED.
* The log is truncated **per entry**; an accumulating log makes every entry report every
  earlier failure as its own evidence.
* Both `FAILED` line shapes are accepted, because both occur here: `desktopTest` prints
  `Class[desktop] > method FAILED` and a host task prints `Class > method FAILED`.
* A surviving mutation is a claim about the code, not about the suite.
"""

import os
import re
import subprocess
import sys
import glob
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LOG = Path("/tmp/opencode/mutate-ios-entry.log")
TASK = ":android:core-domain:desktopTest"
RESULTS = "android/core-domain/build/test-results/desktopTest"

ISO = "android/core-domain/src/commonMain/kotlin/com/crispy/tv/domain/watch/Iso8601.kt"

# `re.M` belongs to `compile`, never to `finditer` — on a compiled pattern the second
# argument of `finditer` is `pos`, so `^` can never match again and the scan returns
# nothing for a suite that failed by name.
FAILED_LINE = re.compile(r"^\s*(\S+)\s+>\s+(\S+)\s+FAILED", re.M)
STALE_TASK = re.compile(rf"^> Task {re.escape(TASK)} (FROM-CACHE|UP-TO-DATE)$", re.M)

CLASS_GROUP, METHOD_GROUP = 1, 2


def failed_tests(output: str) -> set:
    """Test names Gradle reported as FAILED, read from the task's own output."""
    return {f"{c}.{m}" for c, m in FAILED_LINE.findall(output)}


def failures_from_xml() -> set:
    """A second source, since the log is truncated per entry."""
    names = set()
    for path in glob.glob(str(ROOT / f"{RESULTS}/*.xml")):
        for case in ET.parse(path).getroot().iter("testcase"):
            for child in list(case):
                if child.tag in ("failure", "error"):
                    names.add(f"{case.get('classname')}.{case.get('name')}")
    return names


ENTRIES = [
    {
        "name": "the month table is indexed off by one",
        "old": 'return "${MONTH_LABELS[month - 1]} $dayOfMonth"',
        "new": 'return "${MONTH_LABELS[month]} $dayOfMonth"',
        "expect": [
            "aDateRendersAsItsThreeLetterMonthAndUnpaddedDay",
            "everyOneOfTheTwelveMonthsRendersItsAgreedLabel",
        ],
    },
    {
        "name": "the day is zero-padded",
        "old": 'return "${MONTH_LABELS[month - 1]} $dayOfMonth"',
        "new": 'return "${MONTH_LABELS[month - 1]} ${dayOfMonth.toString().padStart(2, \'0\')}"',
        "expect": ["theDayIsNotZeroPadded"],
    },
    {
        "name": "an unreadable value renders instead of returning null",
        # The shape the caller's `catch (_: Exception) { null }` used to absorb: the
        # replaced code threw and the caller turned it into null, so a function that
        # rendered *something* here would be a behaviour change at the call site.
        "old": "    val epochDay = parseIso8601DateToEpochDay(value) ?: return null\n"
               "    val (_, month, dayOfMonth) = civilFromEpochDay(epochDay)",
        "new": "    val epochDay = parseIso8601DateToEpochDay(value) ?: 0L\n"
               "    val (_, month, dayOfMonth) = civilFromEpochDay(epochDay)",
        "expect": ["anUnreadableValueIsNullRatherThanRendered"],
    },
    {
        "name": "one month label is wrong",
        # September is the interesting one: `SEPTEMBER.take(3)` is `SEP`, so a
        # derivation from the full name happens to agree. Mutating a different row is
        # what proves the table rather than the derivation is under test — and the
        # twelve-month case is enumerated, so it notices.
        "old": '"Sep", "Oct", "Nov", "Dec",',
        "new": '"Sept", "Oct", "Nov", "Dec",',
        "expect": [
            "septemberIsSepAndNotSept",
            "everyOneOfTheTwelveMonthsRendersItsAgreedLabel",
        ],
    },
]


def run_gradle() -> str:
    env = {**os.environ}
    env.pop("JAVA_TOOL_OPTIONS", None)
    LOG.parent.mkdir(parents=True, exist_ok=True)
    with open(LOG, "w") as handle:
        subprocess.run(
            ["./gradlew", TASK, "--no-build-cache"],
            cwd=str(ROOT), env=env, stdout=handle, stderr=subprocess.STDOUT,
        )
    return LOG.read_text()


def check_anchors(selected) -> None:
    text = (ROOT / ISO).read_text()
    bad = False
    for index, entry in selected:
        count = text.count(entry["old"])
        if count != 1:
            print(f"SKIP entry {index} {entry['name']!r}: anchor occurs {count}x")
            bad = True
    if bad:
        print("\nFix the anchors before running. Zero occurrences means the code is "
              "gone; more than one means the anchor is too short.")
        sys.exit(2)


def main() -> None:
    recheck = os.environ.get("RECHECK")
    if recheck:
        selected = [(i, e) for i, e in enumerate(ENTRIES) if i == int(recheck)]
    else:
        selected = list(enumerate(ENTRIES))

    check_anchors(selected)
    path = ROOT / ISO
    original = path.read_text()

    caught, survived, no_evidence, compile_failed = [], [], [], []

    for index, entry in selected:
        print(f"\n=== entry {index}: {entry['name']}")
        try:
            # Every new content is computed before any file is opened, so an assertion
            # on a later entry cannot leave an earlier one mutated.
            mutated = original.replace(entry["old"], entry["new"], 1)
            assert mutated != original, "replacement is identical to the original"
            path.write_text(mutated)
            subprocess.run(["rm", "-rf", str(ROOT / RESULTS)], check=False)
            output = run_gradle()
            stale = STALE_TASK.search(output)
            if "BUILD SUCCESSFUL" not in output and "BUILD FAILED" not in output:
                print("  NO EVIDENCE — the task did not run")
                no_evidence.append(index)
                continue
            if stale:
                print(f"  NO EVIDENCE — {stale.group(0).strip()} served a cached result")
                no_evidence.append(index)
                continue
            if "e: " in output:
                print("  COMPILE FAILED -- not evidence")
                compile_failed.append(index)
                continue
            names = failed_tests(output) | failures_from_xml()
            # A substring test over the method name, so it works for both the log's
            # `Class[desktop] > method FAILED` and the XML's `classname` + `name`.
            hit = sorted(n for n in names for e in entry["expect"] if e in n)
            if hit:
                print(f"  CAUGHT by {hit}")
                print(f"  (all failures: {sorted(names)})")
                caught.append(index)
            else:
                print("  SURVIVED")
                print(f"  (failures seen: {sorted(names)})")
                survived.append(index)
        finally:
            path.write_text(original)
            print(f"  restored: {ISO}")

    print(f"\nTALLY of len(selected)={len(selected)}"
          f"  caught={len(caught)}  survived={len(survived)}"
          f"  no_evidence={len(no_evidence)}  compile_failed={len(compile_failed)}")
    print(f"RECHECK = {survived}")
    print("\nScope note: the other four defect classes in this landing have no local "
          "observation path at all. `apple.yml`'s `:android:app:compileKotlinIosArm64` "
          "is their gate, and it is the run that decides whether they are fixed.")
    if survived:
        print("\nA survivor is a claim about the code, not about the suite. Read the "
              "callee, and record the measurement at the guard if the guard turns out "
              "to have no effect.")


if __name__ == "__main__":
    main()
