#!/usr/bin/env python3
"""Mutation driver for :app's extracted player-session decisions.

`PlayerSessionDecisions.kt` holds four pure decisions lifted out of a
1,411-line view model that cannot be constructed without a real player. These
entries check that the suite pinned them, and each one is chosen because it
**compiles, reads sensibly, and is wrong** -- which is the only kind of mutation
worth spending a run on. A mutation that fails to compile is not evidence.

- `fallback-conjunct-is-not-equals-libmpv` -- the third conjunct of
  `shouldFallBackToMpv` is `preference != ExoPlayer`, not `preference == Libmpv`.
  The reversed form reads like the more natural phrasing of "the user pinned
  libmpv" and silently makes `Auto` never fall back, reducing `Auto` to
  `ExoPlayer`.
- `initial-seek-readiness-is-checked-after-already-there` -- the readiness check
  and the "already at the target" check can both be true of a `PREPARING`
  snapshot, so their order *is* the behaviour. Swapping them drops a pending
  seek and resumes the episode at the wrong place.
- `initial-seek-tolerance-is-exclusive` -- `>=` to `>` on the tolerance line.
  The difference is a single millisecond, which is exactly why the suite pins
  both sides of the boundary rather than the line.

Run with no `-q`: Gradle's per-test FAILED lines are the evidence, and `-q`
suppresses them, leaving a verdict with no name attached.
"""

import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TARGET = ROOT / "android/app/src/androidMain/kotlin/com/crispy/tv/playerui/PlayerSessionDecisions.kt"
TASK = ":android:app:testAndroidHostTest"
CLASS_FILTER = "com.crispy.tv.playerui.PlayerSessionDecisionsTest"

# Group 3 is the method name. `desktopTest` prints `Class[desktop] > method FAILED`
# and the host task prints `Class > method FAILED` with no bracket, so the bracket
# is optional in both halves.
#
# MULTILINE is passed to `compile`, never to `finditer`. `Pattern.finditer(s, re.M)`
# does not raise and does not set a flag: the second argument is `pos`, so the integer
# 8 is read as a start offset, `^` can never match again, and the driver reports
# "no test failed" for a suite that failed by name. The first run of this driver did
# exactly that on all three entries. Compile with the flag; scan with one argument.
FAILED = re.compile(r"^(\w+)(?:\[.*?\])? > (\S+?)(?:\[.*?\])? FAILED", re.M)

READINESS_THEN_THERE = """    if (state == NativePlaybackState.IDLE || state == NativePlaybackState.PREPARING) {
        return InitialSeekDecision.Wait
    }
    if (positionMs >= targetPositionMs - INITIAL_SEEK_TOLERANCE_MS) {
        return InitialSeekDecision.Clear
    }
"""

THERE_THEN_READINESS = """    if (positionMs >= targetPositionMs - INITIAL_SEEK_TOLERANCE_MS) {
        return InitialSeekDecision.Clear
    }
    if (state == NativePlaybackState.IDLE || state == NativePlaybackState.PREPARING) {
        return InitialSeekDecision.Wait
    }
"""

ENTRIES = [
    {
        "name": "fallback-conjunct-is-not-equals-libmpv",
        "old": "        preference != NativePlaybackEnginePreference.ExoPlayer",
        "new": "        preference == NativePlaybackEnginePreference.Libmpv",
    },
    {
        "name": "initial-seek-readiness-is-checked-after-already-there",
        "old": READINESS_THEN_THERE,
        "new": THERE_THEN_READINESS,
    },
    {
        "name": "initial-seek-tolerance-is-exclusive",
        "old": "    if (positionMs >= targetPositionMs - INITIAL_SEEK_TOLERANCE_MS) {",
        "new": "    if (positionMs > targetPositionMs - INITIAL_SEEK_TOLERANCE_MS) {",
    },
]

RECHECK = {
    "fallback-conjunct-is-not-equals-libmpv",
    "initial-seek-readiness-is-checked-after-already-there",
    "initial-seek-tolerance-is-exclusive",
}


def check_anchors(text):
    """Every anchor must occur exactly once, or the run is measuring nothing."""
    ok = True
    for entry in ENTRIES:
        count = text.count(entry["old"])
        if count != 1:
            print(f"ABORT: anchor for {entry['name']} occurs {count}x, expected 1x")
            ok = False
    return ok


def run():
    original = TARGET.read_text(encoding="utf-8")
    if not check_anchors(original):
        print("ABORT: an anchor does not occur exactly once. Nothing was written.")
        return 1

    selected = [e for e in ENTRIES if e["name"] in RECHECK] or ENTRIES
    caught, survived, broken = [], [], []

    try:
        for entry in selected:
            mutated = original.replace(entry["old"], entry["new"], 1)
            assert mutated != original, f"{entry['name']} did not change the file"
            TARGET.write_text(mutated, encoding="utf-8")

            proc = subprocess.run(
                [
                    "./gradlew", TASK, "--tests", CLASS_FILTER,
                ],
                cwd=ROOT,
                capture_output=True,
                text=True,
            )
            out = proc.stdout + proc.stderr
            failures = sorted({m.group(2) for m in FAILED.finditer(out)})

            if proc.returncode == 0:
                survived.append(entry["name"])
                print(f"SURVIVED  {entry['name']}  -- the suite does not cover this")
            elif not failures and re.search(r"^e: ", out, re.M):
                broken.append(entry["name"])
                print(f"COMPILE FAILED -- not evidence  {entry['name']}")
            elif not failures:
                broken.append(entry["name"])
                print(f"NO NAMED FAILURE -- check the task's own output  {entry['name']}")
            else:
                caught.append(entry["name"])
                print(f"CAUGHT    {entry['name']}  ->  {', '.join(failures)}")
    finally:
        TARGET.write_text(original, encoding="utf-8")
        print("restored:", TARGET.relative_to(ROOT), "is byte-identical to the original")

    print(f"\nCAUGHT {len(caught)} / SURVIVED {len(survived)} / NO-EVIDENCE {len(broken)}"
          f", of len({len(selected)}) entries")
    for name in survived:
        print("  survived:", name)
    for name in broken:
        print("  no evidence:", name)
    return 0


if __name__ == "__main__":
    sys.exit(run())
