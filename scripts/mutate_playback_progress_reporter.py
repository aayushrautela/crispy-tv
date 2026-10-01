#!/usr/bin/env python3
"""Mutation driver for PlaybackProgressReporter's three documented orderings.

Every rule this driver relies on is stated here, because a driver is run
unattended and a driver that reports nothing is indistinguishable from one whose
mutations did not compile.

    Run:  python3 scripts/mutate_playback_progress_reporter.py
    Edit: RECHECK = [] names the entries to re-run after a surviving mutation.

The four rules that decide whether a run's output means anything:

  1. Never pass -q. It suppresses Gradle's per-test FAILED lines, so every entry
     reports "build failed" instead of a name. The verdict survives; the
     evidence does not.

  2. Pass re.M to re.compile, never to finditer. On a compiled pattern the
     second positional argument is `pos`, so an int there is a start offset and
     `^` can never match again -- the scan returns an empty list and the driver
     reports "no test failed" for a suite that failed by name.

  3. Take the capture-group index from the pattern below, not from another
     driver. This pattern has two groups (both bracket groups are
     non-capturing), so the method name is group 2. A sibling driver uses 3 and
     raises IndexError here.

  4. Read every replacement as code before writing the entry: does it change an
     answer, and does it compile? Two entries were rejected during authoring
     because they provably could not -- see the note on `minimum-position` below.

`COMPILE FAILED` is not evidence. If a patched file does not compile, the
verdict for that entry is meaningless and it is reported as such rather than as
a pass or a survivor.

The fourth rule, learned from this driver's own first run: **an empty failure
list is not a survivor.** `subprocess.run(env=...)` REPLACES the environment
rather than adding to it, so passing `{"JAVA_TOOL_OPTIONS": ...}` alone
stripped PATH and JAVA_HOME and `./gradlew` never ran -- yet the driver printed
`SURVIVED ...: []` for all four entries and `0 caught`, which reads exactly
like a suite that fails to cover its own code. A survivor claim therefore
requires positive evidence that the task ran: `BUILD SUCCESSFUL` or
`BUILD FAILED` in the output. Without one the entry is reported as
`NO EVIDENCE -- the task did not run`, which is not a result in either
direction.
"""

import os
import re
import subprocess
from os.path import dirname

ROOT = dirname(dirname(__file__))
TARGET = (
    ROOT + "/android/app/src/commonMain/kotlin/com/crispy/tv/playerui/"
    "PlaybackProgressReporter.kt"
)
TASK = ":android:app:desktopTest"
CLASS = "com.crispy.tv.playerui.PlaybackProgressReporterTest"
LOG = "/tmp/opencode/mutate-reporter.log"

# Two groups: class (1) and method (2). Both bracket groups are non-capturing.
FAILED = re.compile(r"^(\w+)(?:\[.*?\])? > (\S+?)(?:\[.*?\])? FAILED", re.M)

# Names of entries to re-run. Empty means "run everything".
RECHECK: list[str] = []

# Recheck-only: not a survivor, a *discriminator*.
#
# Removing the start branch's `return` ALONE is unobservable, because the guard
# below catches the fall-through: the branch has just set
# `hasReportedPlaybackStart = true` AND `lastProgressSyncAtElapsedMs =
# nowElapsedMs`, so `!hasReportedPlaybackStart` is false and the delta is 0,
# which is under the interval either way. So `return` is a redundant guard
# *given* the assignment.
#
# This entry removes BOTH, which is the only version of the question the suite
# can answer, and it is expected to be CAUGHT. Two lines are removed and they are
# contiguous, so one anchor carries both.
#
# Note what the first version of this entry got wrong, because it is the exact
# shape of bug that produces a believable false result: its `name` and `why`
# said "returns-nor-assigns-its-own-timestamp", and its `old`/`new` removed only
# the assignment. The anchor existed, the pre-flight passed, and the entry
# reported SURVIVED -- indistinguishable from a suite gap. Read the replacement,
# not the entry's name.
DISCRIMINATOR = {
    "name": "start-branch-returns-nor-assigns-its-own-timestamp",
    "why": (
        "Removes the start branch's `return` AND its "
        "`lastProgressSyncAtElapsedMs = nowElapsedMs`. Expected CAUGHT: it is "
        "what distinguishes 'the return is redundant' from 'the return is what "
        "stops the double report'."
    ),
    "old": (
        "            lastProgressSyncAtElapsedMs = nowElapsedMs\n"
        "            scope.launch {\n"
        "                watchHistoryService.onPlaybackStarted(\n"
        "                    identity = playbackIdentity,\n"
        "                    positionMs = positionMs,\n"
        "                    durationMs = durationMs,\n"
        "                )\n"
        "            }\n"
        "            return\n"
    ),
    "new": (
        "            scope.launch {\n"
        "                watchHistoryService.onPlaybackStarted(\n"
        "                    identity = playbackIdentity,\n"
        "                    positionMs = positionMs,\n"
        "                    durationMs = durationMs,\n"
        "                )\n"
        "            }\n"
    ),
    "expect": [
        "aFirstPollOnALongRunningSessionStillReportsOnlyAStart",
        "theFirstSettledPollReportsAStartAndNotAProgress",
    ],
}

ENTRIES = [
    {
        "name": "two-clock-reads-collapse-into-one",
        "why": (
            "The KDoc claims elapsedRealtime() is read twice -- once for the settle "
            "guard and once for the interval -- so the second reading is strictly "
            "later. Reusing the guard's reading makes them the same instant. "
            "theIntervalIsMeasuredFromTheSecondClockReading drives a TestClock with "
            "stepMs = 9 specifically so the two readings disagree."
        ),
        "old": (
            "        if (clock.elapsedMs() < seekSettleUntilElapsedMs) {\n"
            "            return\n"
            "        }\n"
            "\n"
            "        val nowElapsedMs = clock.elapsedMs()\n"
        ),
        "new": (
            "        val guardElapsedMs = clock.elapsedMs()\n"
            "        if (guardElapsedMs < seekSettleUntilElapsedMs) {\n"
            "            return\n"
            "        }\n"
            "\n"
            "        val nowElapsedMs = guardElapsedMs\n"
        ),
        "expect": ["theIntervalIsMeasuredFromTheSecondClockReading"],
    },
    {
        "name": "start-branch-no-longer-returns",
        "why": (
            "The start branch returns so a single poll can never send both a start "
            "and a progress report. Removing the return lets the same poll send a "
            "progress report too."
        ),
        "old": (
            "                    durationMs = durationMs,\n"
            "                )\n"
            "            }\n"
            "            return\n"
            "        }\n"
            "\n"
            "        if (!hasReportedPlaybackStart"
        ),
        "new": (
            "                    durationMs = durationMs,\n"
            "                )\n"
            "            }\n"
            "        }\n"
            "\n"
            "        if (!hasReportedPlaybackStart"
        ),
        "expect": ["theFirstSettledPollReportsAStartAndNotAProgress"],
    },
    {
        "name": "stop-latch-moved-below-the-duration-guard",
        "why": (
            "The latch is set before the duration guard, so a stop with no duration "
            "consumes its one chance instead of being retried. Moving the latch "
            "below the guard makes the stop retryable."
        ),
        "old": (
            "        hasReportedPlaybackStop = true\n"
            "\n"
            "        val lastDurationMs = currentDurationMs()\n"
            "        if (lastDurationMs <= 0L) {\n"
            "            return\n"
            "        }\n"
        ),
        "new": (
            "        val lastDurationMs = currentDurationMs()\n"
            "        if (lastDurationMs <= 0L) {\n"
            "            return\n"
            "        }\n"
            "        hasReportedPlaybackStop = true\n"
        ),
        "expect": ["aStopWithNoDurationConsumesItsOnlyChance"],
    },
    {
        "name": "minimum-position-becomes-exclusive",
        "why": (
            "MIN_PROGRESS_POSITION_MS is a floor, not an exclusion. The suite pins "
            "999ms reporting nothing and 1000ms reporting a start, so the "
            "comparison's inclusivity is pinned on both sides of the boundary."
        ),
        "old": (
            "        if (!hasReportedPlaybackStart && isPlaying"
            " && positionMs >= MIN_PROGRESS_POSITION_MS) {\n"
        ),
        "new": (
            "        if (!hasReportedPlaybackStart && isPlaying"
            " && positionMs > MIN_PROGRESS_POSITION_MS) {\n"
        ),
        "expect": ["theMinimumPositionIsInclusive"],
    },
]


def check_anchors(entries) -> bool:
    source = open(TARGET, encoding="utf-8").read()
    ok = True
    for entry in entries:
        n = source.count(entry["old"])
        if n != 1:
            print(f"SKIP {entry['name']}: anchor occurs {n}x")
            ok = False
        else:
            print(f"anchor ok {entry['name']}: occurs 1x")
    return ok


def run_task() -> str:
    # Merge, never replace: `env=` on subprocess.run replaces the whole
    # environment, and losing PATH/JAVA_HOME stops ./gradlew from starting at
    # all while every verdict downstream still looks like a result.
    env = {**os.environ, "JAVA_TOOL_OPTIONS": "-Djava.awt.headless=true"}
    result = subprocess.run(
        ["./gradlew", TASK, f"--tests={CLASS}"],
        cwd=ROOT,
        env=env,
        capture_output=True,
        text=True,
    )
    output = result.stdout + result.stderr
    with open(LOG, "a", encoding="utf-8") as handle:
        handle.write(output)
    return output


def main() -> None:
    if RECHECK == ["__discriminator__"]:
        selected = [DISCRIMINATOR]
    else:
        selected = [e for e in ENTRIES if not RECHECK or e["name"] in RECHECK]

    if not check_anchors(selected):
        print("ABORT: an anchor does not occur exactly once")
        return

    caught = 0
    for entry in selected:
        original = open(TARGET, encoding="utf-8").read()
        assert original.count(entry["old"]) == 1, entry["name"]
        try:
            open(TARGET, "w", encoding="utf-8").write(
                original.replace(entry["old"], entry["new"])
            )
            output = run_task()
            names = [m.group(2) for m in FAILED.finditer(output)]
            ran = ("BUILD SUCCESSFUL" in output) or ("BUILD FAILED" in output)
            if not ran:
                print(f"NO EVIDENCE {entry['name']}: the task did not run -- "
                      f"first line: {output.strip().splitlines()[:1]}")
            elif any(expected in names for expected in entry["expect"]):
                print(f"CAUGHT   {entry['name']}: {names}")
                caught += 1
            elif "compileTestKotlinDesktop FAILED" in output or "e: " in output:
                print(f"COMPILE FAILED -- not evidence: {entry['name']}")
            else:
                print(f"SURVIVED {entry['name']}: {names} -- check by hand")
        finally:
            open(TARGET, "w", encoding="utf-8").write(original)
            print(f"restored: {TARGET} (len {len(original)} -> {len(original)})")

    print(f"{caught} caught of len({len(selected)}) entries")


if __name__ == "__main__":
    main()