#!/usr/bin/env python3
"""Mutation driver for DetailsMetadataLoader's two recommendation-filter stages.

Every rule this repository has about mutation drivers is stated here, because a
driver is run unattended and a rule read only in AGENTS.md is a rule that will
be forgotten:

  * Never pass -q. It suppresses Gradle's per-test FAILED lines, so every entry
    reports "build failed" instead of a name. The verdict survives; the evidence
    is lost. Never read the log with `grep -E` and PCRE syntax either -- the
    FAILED-line pattern needs -P (or plain grep). Same failure one level up.
  * Pass re.M to re.compile, never to finditer. On a compiled pattern the second
    argument is `pos`, so `finditer(out, re.M)` becomes finditer(out, 8): a
    start offset, `^` never matches again, and the driver reports "no named
    failure" for a suite that failed by name. That is the worst of the two
    failure modes -- a trustworthy verdict over silently empty evidence.
  * Take the capture-group index from the pattern in front of you, not from
    another driver. This pattern has two groups, both bracket groups
    non-capturing, so the class name is group 1 and the method is group 2.
  * Run check_anchors() before the first write. On a 0x count, grep the file:
    zero occurrences means the code is gone, several means the anchor is wrong.
    Print `SKIP ... anchor occurs Nx` and never count it as a pass -- on a 0x
    the driver would otherwise report "SURVIVED -- the suite does not cover
    this" against unmutated code, recording a nonexistent gap as a fact.
  * Restore in a finally with a printed `restored:` line, end with
    `if __name__ == "__main__": main()`, and print the tally of len(entries) so
    a narrowed RECHECK run cannot be mistaken for a full one.
  * A surviving mutation is a claim about the code, not a test failure. Read the
    callee before deciding; if the guard is genuinely unreachable, keep it and
    write the reasoning at the guard.
"""

import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TARGET = os.path.join(
    ROOT, "android/app/src/commonMain/kotlin/com/crispy/tv/details/DetailsMetadataLoader.kt"
)
TASK = ":android:app:desktopTest"
CLASS = "com.crispy.tv.details.DetailsMetadataLoaderTest"
LOG = "/tmp/opencode/mutate-loader.log"

# Two groups: (1) the class, (2) the method. Bracket groups are non-capturing
# because desktopTest prints `Class[desktop] > method FAILED` and the host task
# prints `Class > method FAILED` with no bracket at all.
FAILED = re.compile(r"^(\w+)(?:\[.*?\])? > (\S+?)(?:\[.*?\])? FAILED", re.M)

ENTRIES = [
    {
        "name": "recommendation-exclusion-is-dropped",
        "old": ".filter { it.itemId !in currentKeys }",
        "new": ".filter { true }",
        "expect": [
            "theTitleAlreadyOnScreenIsExcludedUnderBothOfItsIds",
            "onlyTheFetchedIdIsExcludedWhenNoTitleIsOpen",
        ],
        "why": (
            "The exclusion is the whole reason detailsIdKeys collects BOTH "
            "details.itemId and details.id: the details page can be opened with "
            "a catalogue id while the backend returns the same title under its "
            "addon id. Remove the filter and the rail recommends the title the "
            "user is already looking at -- a bug nothing else in the build can "
            "see, because the golden suite does not render this rail."
        ),
    },
    {
        "name": "recommendation-distinct-is-dropped",
        "old": '.distinctBy { "${it.type}:${it.id}" }',
        "new": "",
        "expect": ["theSameCardFromTwoListsIsPublishedOnce"],
        "why": (
            "A title can appear in two of the backend's curated lists, and the "
            "rail then shows the same card twice. Removing the de-duplication is "
            "the mutation, not narrowing its key: `toCatalogItem` sets id and "
            "itemId to the SAME normalised value, so `distinctBy { it.id }` is "
            "unobservable and a driver that reported it caught would be a false "
            "positive about the suite."
        ),
    },
]


def check_anchors() -> bool:
    with open(TARGET, encoding="utf-8") as handle:
        source = handle.read()
    ok = True
    for entry in ENTRIES:
        count = source.count(entry["old"])
        if count != 1:
            print(f"SKIP {entry['name']} anchor occurs {count}x")
            ok = False
        else:
            print(f"anchor ok {entry['name']} occurs {count}x")
    return ok


def run_task() -> str:
    env = dict(os.environ)
    env["JAVA_TOOL_OPTIONS"] = "-Djava.awt.headless=true"
    completed = subprocess.run(
        ["./gradlew", TASK, f"--tests={CLASS}"],
        cwd=ROOT,
        env=env,
        capture_output=True,
        text=True,
    )
    with open(LOG, "a", encoding="utf-8") as handle:
        handle.write(completed.stdout)
        handle.write(completed.stderr)
    return completed.stdout + completed.stderr


def main() -> None:
    if not check_anchors():
        print("ABORT: an anchor does not occur exactly once")
        return

    selected = [e for e in ENTRIES if e["name"] in RECHECK] if RECHECK else ENTRIES
    with open(TARGET, encoding="utf-8") as handle:
        original = handle.read()

    caught = 0
    survived = []
    for entry in selected:
        with open(TARGET, encoding="utf-8") as handle:
            source = handle.read()
        if source.count(entry["old"]) != 1:
            print(f"SKIP {entry['name']} anchor no longer unique")
            continue
        mutated = source.replace(entry["old"], entry["new"])
        try:
            with open(TARGET, "w", encoding="utf-8") as handle:
                handle.write(mutated)
            output = run_task()
        finally:
            with open(TARGET, "w", encoding="utf-8") as handle:
                handle.write(source)
            print(f"restored: {TARGET} ({len(entry['old'])} -> {len(entry['new'])})")

        names = [m.group(2) for m in FAILED.finditer(output)]
        hit = [name for name in names if name in entry["expect"]]
        if hit:
            caught += 1
            print(f"CAUGHT {entry['name']} -> {', '.join(hit)}")
            print(f"  why: {entry['why']}")
        else:
            survived.append(entry["name"])
            print(f"SURVIVED {entry['name']} (failures seen: {names or 'none'})")
            print(f"  why: {entry['why']}")

    print(f"\n{caught} caught of len({len(selected)}) entries")
    if survived:
        print("survived (check by hand before recording a gap): " + ", ".join(survived))


RECHECK = []

if __name__ == "__main__":
    main()
