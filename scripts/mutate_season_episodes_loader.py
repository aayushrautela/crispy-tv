"""Mutation driver for SeasonEpisodesLoader.

Rules this driver obeys, all of them learned the hard way in this repository
and all of them restated here because this file is run unattended:

* Never pass ``-q`` to Gradle. It suppresses the per-test ``FAILED`` lines, so
  the verdict survives and the evidence is lost.
* ``subprocess.run(env=...)`` **replaces** the environment; it does not merge.
  Use ``{**os.environ, ...}`` or ``./gradlew`` cannot start at all.
* An empty failure list is not a "survivor". A survivor is the absence of a
  failure *after positive evidence the task ran* -- hence the NO EVIDENCE
  verdict below.
* Pass ``re.M`` to ``re.compile``, never to ``finditer``: on a compiled pattern
  the second argument is ``pos``, so ``^`` silently stops matching.
* Take the capture-group index from the pattern below, not from a sibling
  driver. This one has two groups and the method name is group 2.
* ``check_anchors()`` runs before any write, so an anchor that does not occur
  exactly once aborts with the file untouched.
* Every entry is restored in a ``finally`` with a printed ``restored:`` line.
* The log is truncated **per entry**, not once per run: an accumulating log reports every
  earlier entry's failures as evidence for the current one.
"""
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TARGET = ROOT / "android/app/src/commonMain/kotlin/com/crispy/tv/playerui/SeasonEpisodesLoader.kt"
TASK = ":android:app:desktopTest"
CLASS = "com.crispy.tv.playerui.SeasonEpisodesLoaderTest"
LOG = Path("/tmp/opencode/mutate-season-episodes.log")
RECHECK: list[str] = []

FAILED = re.compile(r"^(\w+)(?:\[.*?\])? > (\S+?)(?:\[.*?\])? FAILED", re.M)

ENTRIES = [
    {
        "name": "stale-season-guard-is-dropped",
        "why": "The guard is the whole reason this class was worth extracting. Without it a "
               "response for season N overwrites what the screen is showing for season M.",
        "old": "        if (selectedSeason() != season) return\n        onOutcome(outcome())",
        "new": "        onOutcome(outcome())",
        "expect": [
            "aResponseArrivingAfterTheUserChangedSeasonIsDropped",
            "aFailureArrivingAfterTheUserChangedSeasonIsDropped",
        ],
    },
    {
        "name": "force-no-longer-bypasses-the-cache",
        "why": "force exists so re-selecting a season can re-ask. If it stops bypassing, the "
               "cache can only ever fill once and the user cannot refresh a stale list.",
        "old": "val cached = if (force) null else cache[season]",
        "new": "val cached = cache[season]",
        "expect": ["forceRefetchesACachedSeason"],
    },
    {
        "name": "a-throwing-session-is-no-longer-distinguished",
        "why": "A backend that threw and a user who is not signed in are different answers. "
               "Collapsing them tells a signed-in user with a flaky network to sign in again.",
        "old": "            if (sessionAttempt.isFailure) {\n"
               "                return@launch publishFailure(season, EPISODES_FAILED_MESSAGE)\n"
               "            }\n",
        "new": "",
        "expect": ["aThrownSessionLookupReportsAFailureRatherThanASignInPrompt"],
    },
    {
        "name": "loading-is-published-inside-the-coroutine",
        "why": "The spinner must appear on the same frame the user asked for the season. Queued "
               "into the coroutine it appears a frame later, or never if the scope is busy.",
        "old": "        onOutcome(SeasonEpisodesOutcome.Loading)\n        scope.launch {",
        "new": "        scope.launch {\n            onOutcome(SeasonEpisodesOutcome.Loading)",
        "expect": ["theFirstRequestPublishesLoadingBeforeTheCoroutineRuns"],
    },
    {
        "name": "the-cache-is-no-longer-written",
        "why": "The cache is shared with the view model, which reads it for four other decisions. "
               "If the load stops filling it, those reads always see nothing.",
        "old": "            cache[season] = videos\n",
        "new": "",
        "expect": [
            "aCachedSeasonPublishesWithoutARequestAndWithoutLoading",
            "theLoadWritesIntoTheCacheTheCallerOwns",
        ],
    },
]


def check_anchors(entries: list[dict]) -> bool:
    source = TARGET.read_text(encoding="utf-8")
    ok = True
    for entry in entries:
        n = source.count(entry["old"])
        if n == 1:
            print(f"anchor ok {entry['name']}: occurs 1x")
        else:
            print(f"SKIP {entry['name']}: anchor occurs {n}x")
            ok = False
    return ok


def run_task() -> str:
    # Truncate per entry, not once per run. An accumulating log makes each entry report
    # every *earlier* entry's failures too, so a name expected by this entry that happened
    # to fail under a previous mutation is scored as evidence for this one. That is a false
    # positive that reads exactly like a pass.
    LOG.write_text("", encoding="utf-8")
    env = {**os.environ, "JAVA_TOOL_OPTIONS": "-Djava.awt.headless=true"}
    with LOG.open("a", encoding="utf-8") as log:
        proc = subprocess.run(
            ["./gradlew", TASK, f"--tests={CLASS}"],
            cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, text=True,
        )
    return LOG.read_text(encoding="utf-8") if LOG.exists() else f"exit {proc.returncode}"


def main() -> None:
    selected = [e for e in ENTRIES if not RECHECK or e["name"] in RECHECK]
    if not check_anchors(selected):
        print("ABORT: an anchor does not occur exactly once")
        return

    source = TARGET.read_text(encoding="utf-8")
    caught = 0
    for entry in selected:
        patched = source.replace(entry["old"], entry["new"], 1)
        assert patched != source, f"{entry['name']} did not change the file"
        TARGET.write_text(patched, encoding="utf-8")
        try:
            output = run_task()
            if "BUILD SUCCESSFUL" not in output and "BUILD FAILED" not in output:
                print(f"NO EVIDENCE {entry['name']}: the task did not run")
                continue
            failed = [m.group(2) for m in FAILED.finditer(output)]
            if set(entry["expect"]) <= set(failed):
                caught += 1
                print(f"CAUGHT   {entry['name']}: {failed}")
            else:
                print(f"SURVIVED {entry['name']}: {failed} -- check by hand")
        finally:
            TARGET.write_text(source, encoding="utf-8")
            print(f"restored: {TARGET.name} (len {len(patched)} -> {len(source)})")

    print(f"{caught} caught of len({len(selected)}) entries")
    print(f"of len({len(ENTRIES)}) total; RECHECK={RECHECK}")
    sys.exit(0)


if __name__ == "__main__":
    main()