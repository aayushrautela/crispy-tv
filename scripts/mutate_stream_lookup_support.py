#!/usr/bin/env python3
"""Mutation driver for :addons' StreamLookupSupport.kt.

Every rule in AGENTS.md section 5 is stated here because this file runs
unattended. The load-bearing ones, and what each cost when it was ignored:

  * Never pass -q. It suppresses Gradle's per-test FAILED lines, so every entry
    reports "build failed" instead of a name. The verdict survives; the evidence
    does not.
  * An empty failure list is not a survivor. A survivor is the absence of a
    failure AFTER positive evidence that the task ran, so a NO EVIDENCE verdict
    requires "BUILD SUCCESSFUL" or "BUILD FAILED" in the output. A driver that
    reports every entry surviving at once is usually the driver at fault --
    check the log for an env that never found java.
  * subprocess.run(env=...) REPLACES the environment. Merge os.environ or
    ./gradlew cannot start and every entry reads as a survivor.
  * Pass flags to re.compile, never to finditer: on a compiled pattern the second
    argument of finditer is `pos`, so re.M as a second argument silently becomes
    a start offset and the scan returns nothing.
  * Take the capture-group index from the pattern below, not from another driver.
    This pattern has two groups, so the method is group 2.
  * Truncate the log per entry. A shared log makes every entry report all
    earlier failures as its evidence, and a longer failure list is not a more
    specific one.
  * Read every replacement as code before writing the entry: does it change an
    answer, and does it compile? An entry's name and `why` can describe a
    mutation its old/new does not implement, and the result is a SURVIVED that
    reads exactly like a genuine suite gap.

Usage: python3 scripts/mutate_stream_lookup_support.py
"""

import os
import re
import subprocess
from os.path import dirname, exists, join

ROOT = dirname(dirname(__file__))
TARGET = "android/addons/src/commonMain/kotlin/com/crispy/tv/addons/lookup/StreamLookupSupport.kt"
TASK = ":android:addons:desktopTest"
CLASS = "com.crispy.tv.addons.lookup.StreamLookupSupportTest"
LOG = "/tmp/opencode/mutate-stream-lookup.log"

# Two groups: the class, then the method. Bracket groups are non-capturing
# because desktopTest prints `Class[desktop] > method FAILED` on one target and
# `Class > method FAILED` on the other.
FAILED = re.compile(r"^(\w+)(?:\[.*?\])? > (\S+?)(?:\[.*?\])? FAILED", re.M)

ENTRIES = [
    {
        "name": "parse-lookup-id-accepts-a-zero-season",
        "why": (
            "parseLookupId guards both halves with `> 0`. Loosening to `>= 0` would "
            "start reporting season 0 for an id whose season half is a zero, and "
            "would no longer fall back to treating the whole string as a base id."
        ),
        "old": "if (season != null && season > 0 && episode != null && episode > 0) {",
        "new": "if (season != null && season >= 0 && episode != null && episode >= 0) {",
        "expect": ["aSeasonOrEpisodeOfZeroIsNotAParseAndTheWholeStringStaysTheBaseId"],
    },
    {
        "name": "parse-lookup-id-accepts-a-two-part-id",
        "why": (
            "The parse needs three parts to have a season AND an episode half. "
            "Loosening the arity lets `tt1234:5` claim a season with no episode, "
            "which would silently attach season 1 to an episode lookup."
        ),
        "old": "if (parts.size >= 3) {",
        "new": "if (parts.size >= 2) {",
        "expect": ["anIdWithTwoOrFewerPartsNeverParses"],
    },
    {
        "name": "parse-lookup-id-drops-the-base-id-rejoin",
        "why": (
            "baseId is reconstructed by dropping the last two parts and re-joining "
            "the rest with ':', which is the only reason an id whose base itself "
            "contains a colon survives. Taking the first part alone truncates it."
        ),
        "old": 'val baseId = parts.dropLast(2).joinToString(":").trim()',
        "new": 'val baseId = parts.first().trim()',
        "expect": ["onlyTheLastTwoPartsAreTakenSoAColonBearingBaseIdSurvives"],
    },
    {
        "name": "player-subtitle-stops-suppressing-a-repeated-series-title",
        "why": (
            "The series title is dropped when the player is already showing it. "
            "Without the check a series whose title matches the player's shows "
            "'Show - Show - S01E02', which is what the check exists to prevent."
        ),
        "old": "?.takeUnless { it == normalizedPlayerTitle }",
        "new": "?.let { it }",
        "expect": ["theSeriesTitleIsSuppressedWhenItIsAlsoWhatThePlayerIsAlreadyShowing"],
    },
    {
        "name": "identity-lookup-stops-requiring-a-tt-prefix",
        "why": (
            "Only an imdbId shaped like ttNNNNNN is usable; a numeric id parked in "
            "the imdbId field has to be discarded so itemId answers instead. "
            "Case-insensitive, so 'TT0903747' is accepted."
        ),
        "old": 'identity.imdbId?.trim()?.takeIf { it.startsWith("tt", ignoreCase = true) }',
        "new": 'identity.imdbId?.trim()?.takeIf { it.isNotBlank() }',
        "expect": ["anImdbIdThatDoesNotStartWithTtIsDroppedWithoutSignal"],
    },
    {
        "name": "episode-lookup-stops-matching-on-the-lookup-id",
        "why": (
            "An episode is found by either its id or its addon lookupId. Dropping "
            "the lookupId half means an episode keyed by the addon id is "
            "unfindable, which is the case the cache exists to serve."
        ),
        "old": "episode.lookupId?.equals(normalizedLookupId, ignoreCase = true) == true",
        "new": "false",
        "expect": ["currentEpisodesWinOverCachedOnesAndEitherIdKindMatches"],
    },
    {
        "name": "provider-result-drops-an-unknown-provider",
        "why": (
            "applyProviderResult appends a provider nobody was seeded with rather "
            "than discarding it. Without the append, an addon that appears late is "
            "resolved and then thrown away."
        ),
        "old": "return if (matched) updated else updated + result.toUiState()",
        "new": "return updated",
        "expect": ["anUnknownProviderIsAppendedRatherThanIgnored"],
    },
]

# Set to a list of names to re-run only those entries. Empty means all of them.
RECHECK = []


def check_anchors(entries):
    """Every anchor must occur exactly once, or nothing is written at all."""
    path = join(ROOT, TARGET)
    source = open(path, encoding="utf-8").read()
    ok = True
    for entry in entries:
        count = source.count(entry["old"])
        if count == 1:
            print("anchor ok  %s: occurs 1x" % entry["name"])
        else:
            ok = False
            print("SKIP %s: anchor occurs %dx" % (entry["name"], count))
    return ok


def run_task():
    """Run the suite and return the full output, truncating the log per entry."""
    open(LOG, "w").close()
    result = subprocess.run(
        ["./gradlew", TASK, "--tests", CLASS],
        cwd=ROOT,
        env={**os.environ, "JAVA_TOOL_OPTIONS": "-Djava.awt.headless=true"},
        capture_output=True,
        text=True,
    )
    output = result.stdout + result.stderr
    with open(LOG, "a", encoding="utf-8") as handle:
        handle.write(output)
    return output


def main():
    if not exists(join(ROOT, "gradlew")):
        raise SystemExit("run me from the repository, not from scripts/")
    path = join(ROOT, TARGET)
    original = open(path, encoding="utf-8").read()

    selected = [e for e in ENTRIES if not RECHECK or e["name"] in RECHECK]
    if not check_anchors(selected):
        raise SystemExit("ABORT: an anchor does not occur exactly once")

    caught = 0
    for entry in selected:
        try:
            source = open(path, encoding="utf-8").read()
            assert source.count(entry["old"]) == 1, entry["name"]
            mutated = source.replace(entry["old"], entry["new"], 1)
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(mutated)

            output = run_task()
            failures = [m.group(2) for m in FAILED.finditer(output)]

            if "BUILD SUCCESSFUL" not in output and "BUILD FAILED" not in output:
                print("NO EVIDENCE %s: the task did not run" % entry["name"])
            elif set(entry["expect"]) <= set(failures):
                caught += 1
                print("CAUGHT   %s: %s" % (entry["name"], sorted(failures)))
            else:
                print("SURVIVED %s: %s -- check by hand" % (entry["name"], sorted(failures)))
        finally:
            with open(path, "w", encoding="utf-8") as handle:
                handle.write(original)
            print("restored: %s (len %d -> %d)" % (path, len(mutated), len(original)))

    print("%d caught of len(%d) entries" % (caught, len(selected)))
    if caught != len(selected):
        print("A survivor is a claim about the code, not a gap in the suite. Read the "
              "callee before recording one.")


if __name__ == "__main__":
    main()