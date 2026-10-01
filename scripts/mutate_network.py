#!/usr/bin/env python3
"""Mutation driver for `:android:network`'s first test source set.

Covers `TrailerSourceTest`, the suite covering the module's only two pure
decisions: `classifyTrailerSource` and `extractYouTubeVideoId`.

    python3 scripts/mutate_network.py            # every entry
    RECHECK=3 python3 scripts/mutate_network.py  # re-run entry N only

Every guard below exists because it was needed here or in the sibling driver and is
stated in both files' docstrings:

* `env = {**os.environ, ...}` — `subprocess.run(env=…)` **replaces** the environment
  rather than merging it, so passing only the additions strips `PATH` and `JAVA_HOME`
  and `./gradlew` never starts, which reads as "no test failed" for every entry.
* `--no-build-cache` on every invocation. Without it Gradle serves the test task
  `FROM-CACHE` and leaves the compile `UP-TO-DATE`, so a perturbed source is never
  compiled and the entry is scored against the *previous* code. The task then reports
  `BUILD SUCCESSFUL` in about a second, which is why this needs a **text** guard and
  not just the `NO EVIDENCE` rule.
* The task is **never filtered**. `--tests` names a *class*; passing a method name
  matches nothing and fails the task in a shape a driver reads as a survivor.
* The log is truncated **per entry**, and the results directory is removed per entry.
* An empty failure list is not a survivor — `NO EVIDENCE` is a third verdict, and
  `COMPILE FAILED` is also not evidence.
* `re.M` goes to `compile`, never to `finditer`/`findall` as a second argument.
"""

import os
import re
import subprocess
import sys
import glob
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LOG = Path("/tmp/opencode/mutate-network-entry.log")
SRC = "android/network/src/commonMain/kotlin/com/crispy/tv/details/trailer/TrailerSource.kt"
TASK = ":android:network:desktopTest"

# Two capture groups, both used — so the indices are 1 and 2. The three-group pattern
# in the sibling drivers needs index 3; this pattern does not.
FAILED_LINE = re.compile(r"^\s*(\S+)\s+>\s+(\S+)\s+FAILED", re.M)
CLASS_GROUP, METHOD_GROUP = 1, 2

# The two ways a perturbed run produces no evidence while still reporting success.
STALE_TASK = re.compile(
    rf"^> Task {re.escape(TASK)} (FROM-CACHE|UP-TO-DATE)$", re.M
)


def failed_tests(output: str) -> set:
    return {f"{c}.{m}" for c, m in FAILED_LINE.findall(output)}


def failures_from_xml(task: str) -> set:
    names = set()
    for path in glob.glob(str(ROOT / f"android/network/build/test-results/{task}/*.xml")):
        root = ET.parse(path).getroot()
        for case in root.iter("testcase"):
            for child in list(case):
                if child.tag in ("failure", "error"):
                    names.add(f"{case.get('classname')}.{case.get('name')}")
    return names


ENTRIES = [
    # ------------------------------------------------------------------ classifyTrailerSource
    {
        "name": "the watch-url marker is dropped",
        "file": SRC,
        "old": 'lower.contains("youtube.com/watch")',
        "new": 'lower.contains("www.youtube.com/watch")',
        "expect": ["aWatchUrlIsYouTube", "aBareHostWithNoSchemeStillClassifies"],
    },
    {
        "name": "the short-link marker is dropped",
        "file": SRC,
        "old": 'if (lower.contains("youtube.com/watch") || lower.contains("youtu.be/")) {',
        "new": 'if (lower.contains("youtube.com/watch")) {',
        "expect": ["aShortYouTubeUrlIsYouTube"],
    },
    {
        "name": "the short-link marker loses its slash",
        "file": SRC,
        "old": 'lower.contains("youtu.be/")',
        "new": 'lower.contains("youtu.be")',
        "expect": ["theShortLinkMarkerNeedsItsSlash"],
    },
    {
        "name": "classification stops lowercasing",
        "file": SRC,
        "old": "val lower = url.lowercase()",
        "new": "val lower = url",
        "expect": ["classificationIgnoresCase"],
    },
    {
        # Widening the marker to the bare host makes a YouTube page that is not a
        # watch link classify as YOUTUBE. The case that sees it asserts DIRECT for
        # `/feed/subscriptions`, so this is a narrowing that widens the answer set.
        "name": "the marker widens to the bare host",
        "file": SRC,
        "old": 'lower.contains("youtube.com/watch")',
        "new": 'lower.contains("youtube.com/")',
        "expect": ["theMarkerIsTheHostAndPathRatherThanTheWordYouTube"],
    },
    # ------------------------------------------------------------------ extractYouTubeVideoId
    {
        "name": "the empty check is removed",
        "file": SRC,
        "old": '    if (trimmed.isEmpty()) return null\n',
        "new": "",
        "expect": ["anEmptyValueIsTheOnlyNull"],
    },
    {
        "name": "the non-youTube early return is removed",
        "file": SRC,
        "old": '    if (!trimmed.contains("youtu", ignoreCase = true)) return trimmed\n',
        "new": "",
        "expect": [
            "aValueWithNoYouTubeInItComesBackWhole",
            "aQueryParameterIsNotAnIdWithoutYouTubeInTheUrl",
        ],
    },
    {
        # This is the entry the suite's hardest case was written for. An uppercase host
        # with a lowercase `v=` reaches the gate and the two answers part company; the
        # obvious uppercase fixture does not, because the pattern's alternatives are
        # lowercase too and the fallback returns the same string either way.
        "name": "the gate stops ignoring case",
        "file": SRC,
        "old": 'trimmed.contains("youtu", ignoreCase = true)',
        "new": 'trimmed.contains("youtu")',
        "expect": ["theGateIsCaseInsensitiveEvenThoughEveryAlternativeInThePatternIsNot"],
    },
    {
        "name": "the no-match fallback returns null",
        "file": SRC,
        "old": "return YOUTUBE_VIDEO_ID_REGEX.find(trimmed)?.groupValues?.getOrNull(1) ?: trimmed",
        "new": "return YOUTUBE_VIDEO_ID_REGEX.find(trimmed)?.groupValues?.getOrNull(1)",
        "expect": [
            "aBareIdComesBackUnchanged",
            "noNonBlankInputReturnsNull",
        ],
    },
    {
        "name": "the shorts alternative is dropped from the pattern",
        "file": SRC,
        "old": 'Regex("(?:[?&]v=|youtu\\\\.be/|/shorts/|/embed/)([A-Za-z0-9_-]{6,})")',
        "new": 'Regex("(?:[?&]v=|youtu\\\\.be/|/embed/)([A-Za-z0-9_-]{6,})")',
        "expect": ["everyYouTubeLinkShapeYieldsItsId"],
    },
    {
        "name": "the embed alternative is dropped from the pattern",
        "file": SRC,
        "old": 'Regex("(?:[?&]v=|youtu\\\\.be/|/shorts/|/embed/)([A-Za-z0-9_-]{6,})")',
        "new": 'Regex("(?:[?&]v=|youtu\\\\.be/|/shorts/)([A-Za-z0-9_-]{6,})")',
        "expect": ["everyYouTubeLinkShapeYieldsItsId"],
    },
    {
        "name": "the minimum id length drops from six to five",
        "file": SRC,
        "old": '([A-Za-z0-9_-]{6,})',
        "new": '([A-Za-z0-9_-]{5,})',
        "expect": ["anIdShorterThanSixCharactersIsNotAnId"],
    },
    {
        # Narrowing the id class stops the capture short of the first `_` or `-`, and
        # with a six-character minimum a hyphenated id then matches nothing at all and
        # falls through to the whole-URL fallback.
        "name": "underscores and hyphens leave the id class",
        "file": SRC,
        "old": '([A-Za-z0-9_-]{6,})',
        "new": '([A-Za-z0-9]{6,})',
        "expect": ["anUnderscoreOrHyphenIsPartOfTheId"],
    },
    {
        "name": "the question-mark alternative of the query form is dropped",
        "file": SRC,
        "old": 'Regex("(?:[?&]v=|youtu\\\\.be/|/shorts/|/embed/)([A-Za-z0-9_-]{6,})")',
        "new": 'Regex("(?:&v=|youtu\\\\.be/|/shorts/|/embed/)([A-Za-z0-9_-]{6,})")',
        "expect": ["theQueryParameterFormIsReadWithOrWithoutTheAmpersand"],
    },
    {
        "name": "the pattern is anchored to the whole value",
        "file": SRC,
        "old": "YOUTUBE_VIDEO_ID_REGEX.find(trimmed)",
        "new": "YOUTUBE_VIDEO_ID_REGEX.matchEntire(trimmed)",
        "expect": [
            "everyYouTubeLinkShapeYieldsItsId",
            "anUnrelatedQueryParameterIsReadAsTheIdWhenTheUrlMentionsYouTube",
        ],
    },
    {
        # The trimming is only observable on an input that has whitespace *and* takes
        # the early return, because a URL with whitespace in the middle would not match
        # the pattern anyway and would come back trimmed either way.
        "name": "the value stops being trimmed",
        "file": SRC,
        "old": "val trimmed = value.trim()",
        "new": "val trimmed = value",
        "expect": ["aBareIdIsTrimmedFirst", "anEmptyValueIsTheOnlyNull"],
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
    bad = False
    for index, entry in selected:
        count = (ROOT / entry["file"]).read_text().count(entry["old"])
        if count != 1:
            print(f"SKIP entry {index} {entry['name']!r}: anchor occurs {count}x")
            bad = True
    if bad:
        print("\nFix the anchors before running. Zero occurrences means the code is "
              "gone; more than one means the anchor is too short.")
        sys.exit(2)


def main() -> None:
    recheck = os.environ.get("RECHECK")
    selected = (
        [(i, e) for i, e in enumerate(ENTRIES) if i == int(recheck)]
        if recheck else list(enumerate(ENTRIES))
    )
    check_anchors(selected)

    caught, survived, no_evidence, compile_failed = [], [], [], []

    for index, entry in selected:
        path = ROOT / entry["file"]
        original = path.read_text()
        print(f"\n=== entry {index}: {entry['name']}")
        try:
            if entry["old"] not in original:
                print("  ANCHOR GONE — not evidence")
                no_evidence.append(index)
                continue
            # Every new content is computed before any file is opened for writing, so
            # a failure on entry 7 cannot truncate entry 3.
            mutated = original.replace(entry["old"], entry["new"], 1)
            assert mutated != original, "replacement is identical to the original"
            path.write_text(mutated)
            subprocess.run(
                ["rm", "-rf", str(ROOT / "android/network/build/test-results/desktopTest")],
                check=False,
            )
            output = run_gradle()
            if "BUILD SUCCESSFUL" not in output and "BUILD FAILED" not in output:
                print("  NO EVIDENCE — the task did not run")
                no_evidence.append(index)
                continue
            if "e: " in output:
                print("  COMPILE FAILED -- not evidence")
                compile_failed.append(index)
                continue
            stale = STALE_TASK.search(output)
            if stale:
                print(f"  NO EVIDENCE — {TASK} was {stale.group(1)}, so the perturbed "
                      "source was never compiled")
                no_evidence.append(index)
                continue
            names = failed_tests(output) | failures_from_xml("desktopTest")
            # Substring over the method name, so the same test works for the log's
            # `Class[desktop] > method FAILED` and the XML's classname + name.
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
            print(f"  restored: {entry['file']}")

    print(f"\nTALLY of len(selected)={len(selected)}  caught={len(caught)}  "
          f"survived={len(survived)}  no_evidence={len(no_evidence)}  "
          f"compile_failed={len(compile_failed)}")
    print(f"RECHECK = {survived}")
    if survived:
        print("\nA survivor is a claim about the code. Read the `(failures seen: …)` "
              "line above before writing any code: a `SURVIVED` printed directly above "
              "a correct failure name is a stale `expect` list, not a gap in the suite.")


if __name__ == "__main__":
    main()
