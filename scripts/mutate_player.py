#!/usr/bin/env python3
"""Mutation driver for `:android:player`'s first test source set.

Covers `WatchHistoryServiceTest` and `MetadataLabResolverTest`, the two suites that
now exist because the module had no test source set at all (see the KDoc added to
`android/player/build.gradle.kts`).

    python3 scripts/mutate_player.py            # every entry
    RECHECK=3 python3 scripts/mutate_player.py # re-run entry N only

Rules this driver obeys, and why each is here:

* `env = {**os.environ, ...}` — `subprocess.run(env=…)` **replaces** the environment
  rather than merging it, so passing only the additions strips `PATH` and `JAVA_HOME`
  and `./gradlew` never starts. That reports "no test failed" for every entry while
  the log is four lines of `JAVA_HOME is not set`.
* An empty failure list is **not** a survivor. `NO EVIDENCE` is a third verdict,
  taken when the log contains neither `BUILD SUCCESSFUL` nor `BUILD FAILED`.
* Never `-q`. It suppresses the per-test `FAILED` lines, so every entry falls back to
  "the build failed" and the verdict survives while the evidence is lost.
* The log is truncated **per entry**. Appending across entries makes each one report
  every earlier failure as its own evidence.
* Two `FAILED` line shapes are accepted, because both occur in this repository:
  `desktopTest` prints `Class[desktop] > method FAILED` and the host task prints
  `Class > method FAILED` with no bracket.
* A surviving mutation is a claim about the code, not about the suite. Every survivor
  is listed in the closing report for hand-checking.
* **The task is never filtered and never cached.** `--tests` names a *class*, and
  `FROM-CACHE` means no test ran; see `run_gradle`'s docstring and the `FROM-CACHE`
  guard in `main`, which record what each of those cost this driver and how the tell
  looked. Between them they turned a correct suite into 19 false survivors and then a
  false survivor on a suite that had already been fixed.
"""

import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import glob
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
LOG = Path("/tmp/opencode/mutate-player-entry.log")

WHS = "android/player/src/commonMain/kotlin/com/crispy/tv/player/WatchHistoryService.kt"
LAB = "android/player/src/commonMain/kotlin/com/crispy/tv/player/MetadataLabResolver.kt"

# `re.M` belongs to `compile`, never to `finditer` — on a compiled pattern the second
# argument of `finditer` is `pos`, so `^` can never match again and the scan returns
# nothing for a suite that failed by name.
FAILED_LINE = re.compile(r"^\s*(\S+)\s+>\s+(\S+)\s+FAILED", re.M)

# Two capture groups, both used, so the index is 1 and 2 — not 3, which is what a
# sibling driver in this repository uses for a pattern with three groups.
CLASS_GROUP, METHOD_GROUP = 1, 2


def failed_tests(output: str) -> set:
    """Test names Gradle reported as FAILED, read from the task's own output."""
    names = set()
    for cls, method in FAILED_LINE.findall(output):
        names.add(f"{cls}.{method}")
    return names


def failures_from_xml(task: str) -> set:
    """Test names from the result XML — a second source, since the log is truncated."""
    names = set()
    for path in glob.glob(str(ROOT / f"android/player/build/test-results/{task}/*.xml")):
        root = ET.parse(path).getroot()
        for case in root.iter("testcase"):
            for child in list(case):
                if child.tag in ("failure", "error"):
                    names.add(f"{case.get('classname')}.{case.get('name')}")
    return names


ENTRIES = [
    # ---------------------------------------------------------------- WatchHistoryService.kt
    {
        "name": "the progress guard loses its zero",
        "file": WHS,
        "old": "get() = if (durationSeconds <= 0.0) 0.0 else (currentTimeSeconds / durationSeconds) * 100.0",
        "new": "get() = if (durationSeconds < 0.0) 0.0 else (currentTimeSeconds / durationSeconds) * 100.0",
        "expect": ["aZeroDurationIsZeroRatherThanInfiniteOrNotANumber"],
    },
    {
        "name": "the progress stops being a percentage",
        "file": WHS,
        "old": "else (currentTimeSeconds / durationSeconds) * 100.0",
        "new": "else (currentTimeSeconds / durationSeconds)",
        "expect": [
            "progressIsAPercentageOfTheDuration",
            "progressIsNotClampedToTheDuration",
        ],
    },
    {
        "name": "the one error-flagged default stops flagging itself",
        "file": WHS,
        "old": 'return CanonicalContinueWatchingResult(statusMessage = "Canonical continue watching unavailable.", isError = true)',
        "new": 'return CanonicalContinueWatchingResult(statusMessage = "Canonical continue watching unavailable.", isError = false)',
        "expect": ["theCanonicalContinueWatchingDefaultIsTheOnlyOneThatFlagsItselfAsAnError"],
    },
    {
        "name": "an anime item is folded into the show label",
        "file": WHS,
        "old": '"anime" -> MetadataLabMediaType.ANIME.label',
        "new": '"anime" -> MetadataLabMediaType.SERIES.label',
        "expect": ["theItemTypeIsFoldedIntoTheThreeMediaLabels"],
    },
    {
        "name": "the item type match becomes case-sensitive",
        "file": WHS,
        "old": "get() = when (itemType.lowercase()) {",
        "new": "get() = when (itemType) {",
        "expect": ["theItemTypeMatchIsCaseInsensitive"],
    },
    {
        "name": "the unknown-item fallback becomes an empty label",
        "file": WHS,
        "old": 'else -> MetadataLabMediaType.MOVIE.label\n    }',
        "new": 'else -> ""\n    }',
        "expect": ["anUnrecognisedItemTypeIsAMovieRatherThanNothing"],
    },
    {
        "name": "watchedAtEpochMs stops aliasing lastUpdatedEpochMs",
        "file": WHS,
        "old": "val watchedAtEpochMs: Long\n    get() = lastUpdatedEpochMs",
        "new": "val watchedAtEpochMs: Long\n    get() = 0L",
        "expect": ["theWatchedTimestampIsTheLastUpdatedOneUnderASecondName"],
    },
    {
        "name": "the title-level rating diverges from the playback-level one",
        "file": WHS,
        "old": "    suspend fun setTitleLiked(\n        itemId: String,\n        liked: Boolean?,\n    ): WatchHistoryResult {\n        return WatchHistoryResult(statusMessage = \"Rating unavailable.\")",
        "new": "    suspend fun setTitleLiked(\n        itemId: String,\n        liked: Boolean?,\n    ): WatchHistoryResult {\n        return WatchHistoryResult(statusMessage = \"Title rating unavailable.\")",
        "expect": ["aTitleLevelRatingSaysExactlyWhatThePlaybackOneSays"],
    },
    {
        "name": "the three read-only defaults return an empty snapshot instead of null",
        "file": WHS,
        "old": "suspend fun getCanonicalWatchState(identity: PlaybackIdentity): CanonicalWatchStateSnapshot? {\n        return null\n    }",
        "new": "suspend fun getCanonicalWatchState(identity: PlaybackIdentity): CanonicalWatchStateSnapshot? {\n        return CanonicalWatchStateSnapshot(\n            isWatched = false,\n            watchedAtEpochMs = null,\n            isInWatchlist = false,\n            liked = null,\n        )\n    }",
        "expect": ["theThreeReadOnlyDefaultsAreNullRatherThanAnEmptyResult"],
    },
    # ---------------------------------------------------------------- MetadataLabResolver.kt
    {
        "name": "SERIES stops being labelled 'show'",
        "file": LAB,
        "old": 'SERIES -> "show"',
        "new": 'SERIES -> "series"',
        "expect": ["theMediaTypeLabelIsAProviderTermAndNotTheEnumName"],
    },
    {
        "name": "the blank-id check is removed from the core resolver",
        "file": LAB,
        "old": '        val contentId = request.rawId.trim()\n        require(contentId.isNotBlank()) { "content id is required" }\n        val parsedLookupId = parseTmdbLookupId(contentId)\n\n        val payload = dataSource.load(request = request)',
        "new": '        val contentId = request.rawId.trim()\n        val parsedLookupId = parseTmdbLookupId(contentId)\n\n        val payload = dataSource.load(request = request)',
        "expect": ["aBlankIdIsRejectedBeforeTheDataSourceIsAsked"],
    },
    {
        "name": "the empty-addon-results check is removed",
        "file": LAB,
        "old": '        require(payload.addonResults.isNotEmpty()) { "addon results must not be empty" }\n',
        "new": "",
        "expect": ["emptyAddonResultsAreRejectedAfterTheDataSourceHasBeenAsked"],
    },
    {
        "name": "sources becomes every addon rather than the first",
        "file": LAB,
        "old": "sources = listOf(primary.addonId),",
        "new": "sources = payload.addonResults.map { it.addonId },",
        "expect": ["onlyTheFirstAddonResultBecomesThePrimary"],
    },
    {
        # The duplicate-rule finding, applied to the copy in this module. `:addons`
        # needs a *purely numeric two-part* id to see this one, because loosening the
        # arity makes `"tt1234:5"` still fail the numeric guard. `"5:7"` is the
        # discriminator and is in the suite precisely for it.
        "name": "the lookup-id arity guard admits two parts",
        "file": LAB,
        "old": "if (parts.size >= 3) {",
        "new": "if (parts.size >= 2) {",
        "expect": ["aTwoPartIdIsNeverAnEpisodeId", "aSingleColonBearingIdIsNotAnEpisodeId"],
    },
    {
        "name": "the lookup-id base drops one part too few",
        "file": LAB,
        "old": 'val baseId = parts.dropLast(2).joinToString(":").trim()',
        "new": 'val baseId = parts.dropLast(1).joinToString(":").trim()',
        "expect": ["aFourPartIdKeepsEveryColonButTheLastTwo", "aFivePartIdDropsExactlyTheLastTwoParts"],
    },
    {
        "name": "the season guard admits zero",
        "file": LAB,
        "old": "if (season != null && season > 0 && episode != null && episode > 0) {",
        "new": "if (season != null && season >= 0 && episode != null && episode > 0) {",
        "expect": ["aZeroSeasonOrEpisodeIsNotAnEpisodeId"],
    },
    {
        "name": "the episode guard admits zero",
        "file": LAB,
        "old": "if (season != null && season > 0 && episode != null && episode > 0) {",
        "new": "if (season != null && season > 0 && episode != null && episode >= 0) {",
        "expect": ["aZeroSeasonOrEpisodeIsNotAnEpisodeId"],
    },
    {
        "name": "the tmdb record stops winning over the addon record",
        "file": LAB,
        "old": "val merged = withDerivedSeasons(payload.tmdbMeta ?: payload.addonMeta, domainMediaType)",
        "new": "val merged = withDerivedSeasons(payload.addonMeta, domainMediaType)",
        "expect": ["theTmdbRecordWinsOverTheAddonRecordWhenBothArePresent"],
    },
    {
        "name": "the unavailable resolver stops saying Unavailable",
        "file": LAB,
        "old": 'primaryTitle = "Unavailable",',
        "new": 'primaryTitle = "Unknown",',
        "expect": ["theDefaultResolverReportsTheTitleAsUnavailableAndClaimsNoSources"],
    },
    {
        "name": "the unavailable resolver starts asking for enrichment",
        "file": LAB,
        "old": 'primaryTitle = "Unavailable",\n            sources = emptyList(),\n            needsEnrichment = false,',
        "new": 'primaryTitle = "Unavailable",\n            sources = emptyList(),\n            needsEnrichment = true,',
        "expect": ["theDefaultResolverAsksForEnrichmentEvenThoughItHasNoDataSource"],
    },
    {
        "name": "the unavailable resolver offers no bridge candidate",
        "file": LAB,
        "old": "bridgeCandidateIds = listOf(parsedLookupId.videoId ?: parsedLookupId.baseId),",
        "new": "bridgeCandidateIds = emptyList(),",
        "expect": [
            "theDefaultResolverOffersTheLookupIdAsItsOwnBridgeCandidate",
            "theDefaultResolverPrefersTheBareIdWhenThereIsNoEpisode",
        ],
    },
    {
        "name": "the addon lookup id prefers the base over the video id",
        "file": LAB,
        "old": "addonLookupId = parsedLookupId.videoId ?: parsedLookupId.baseId,\n            primaryId = primary.mediaId,",
        "new": "addonLookupId = parsedLookupId.baseId,\n            primaryId = primary.mediaId,",
        # This is the **core** resolver's line, and the case that reaches it is the one
        # added when this entry first survived. `aThreePartIdSplitsIntoABaseAndAVideo`
        # drives `DefaultMetadataLabResolver` and never touches this line, which is how
        # the entry survived a suite that was otherwise correct: the failure was in the
        # log all along, under a name this list no longer contained.
        "expect": ["theAddonLookupIdIsTheVideoIdWhenThereIsOneAndTheBaseOtherwise"],
    },
    {
        "name": "the core resolver hands the data source a trimmed request",
        "file": LAB,
        "old": "val payload = dataSource.load(request = request)",
        "new": "val payload = dataSource.load(request = request.copy(rawId = contentId))",
        "expect": ["theDataSourceIsGivenTheRequestExactlyAsItArrived"],
    },
]


def run_gradle() -> str:
    """Run the whole task. Deliberately unfiltered — see the note below.

    **`--tests` takes a *class* name, never a method name.** The first version of this
    driver passed `--tests com.crispy.tv.player.<methodName>` for every entry with a
    single expected failure, on the reasonable reading that it would narrow the run.
    It does not: Gradle resolves the filter as a class pattern, matches nothing, and
    fails the task with "No tests found for given includes" — a `BUILD FAILED` with no
    `e:` line and no per-test `FAILED` line. A driver that decides `CAUGHT` vs
    `SURVIVED` by reading failure names therefore scores **every filtered entry a
    SURVIVED**, and the tally looked like a suite problem when it was a driver problem.

    The tell was that all four caught entries were the four with *two* expected
    failures — the four the unfiltered path happened to cover. **When N of your entries
    share a code path and only the other ones report, the difference in the path is the
    bug.** Every entry here now runs the full task; it costs a couple of seconds and
    removes a whole class of false survivors.
    """
    env = {**os.environ}
    env.pop("JAVA_TOOL_OPTIONS", None)
    # No `--rerun-tasks`: `write_text` updates the mtime, so the Kotlin incremental
    # compile sees the change, and forcing every dependency to recompile per entry
    # multiplies the run by an order of magnitude for no added signal.
    #
    # `--no-build-cache` is **not** optional here. Without it Gradle serves
    # `desktopTest` `FROM-CACHE` when it believes the inputs are unchanged, the
    # Kotlin compile is skipped as `UP-TO-DATE`, and the mutation under test is never
    # compiled at all — the run reports `BUILD SUCCESSFUL` in about a second and the
    # driver records a confident `SURVIVED` from a test task that did not execute. It
    # did exactly that to entry 21 twice, including once when the suite *was* correct
    # and the entry was genuinely caught. **A verdict from a `FROM-CACHE` task is not
    # evidence about the code**, and the `NO EVIDENCE` rule does not catch it because
    # the task did report a verdict.
    cmd = ["./gradlew", ":android:player:desktopTest", "--no-build-cache"]
    LOG.parent.mkdir(parents=True, exist_ok=True)
    # Truncated per entry on purpose: an accumulating log makes every entry report
    # every earlier failure as its own evidence, and a longer failure list is not a
    # more specific one.
    with open(LOG, "w") as handle:
        result = subprocess.run(
            cmd, cwd=str(ROOT), env=env, stdout=handle, stderr=subprocess.STDOUT
        )
    return result.returncode


def check_anchors(selected) -> None:
    bad = False
    for index, entry in selected:
        text = (ROOT / entry["file"]).read_text()
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
            # a ValueError on entry 7 cannot truncate entry 3.
            mutated = original.replace(entry["old"], entry["new"], 1)
            assert mutated != original, "replacement is identical to the original"
            path.write_text(mutated)
            for task in ("desktopTest",):
                subprocess.run(
                    ["rm", "-rf", str(ROOT / f"android/player/build/test-results/{task}")],
                    check=False,
                )
            run_gradle()
            output = LOG.read_text()
            if "BUILD SUCCESSFUL" not in output and "BUILD FAILED" not in output:
                print("  NO EVIDENCE — the task did not run")
                no_evidence.append(index)
                continue
            # A cached or skipped test task ran no test. The mutation was then never
            # compiled, so the verdict describes the previous state of the tree.
            if re.search(r"^> Task :android:player:desktopTest (FROM-CACHE|UP-TO-DATE)$", output, re.M):
                print("  NO EVIDENCE — desktopTest was FROM-CACHE/UP-TO-DATE")
                no_evidence.append(index)
                continue
            if "e: " in output:
                print("  COMPILE FAILED -- not evidence")
                compile_failed.append(index)
                continue
            names = failed_tests(output) | failures_from_xml("desktopTest")
            # Gradle writes the failing test as `…Test.method[desktop]` and the XML as
            # `classname` + `name`, so a substring test over the method name is the one
            # match that works for both sources without a second parsing path.
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

    print(f"\nTALLY of len(selected)={len(selected)}"
          f"  caught={len(caught)}  survived={len(survived)}"
          f"  no_evidence={len(no_evidence)}  compile_failed={len(compile_failed)}")
    print(f"RECHECK = {survived}")
    if survived:
        print("\nA survivor is a claim about the code, not about the suite. Read the "
              "callee before writing a test for it, and record the measurement at the "
              "guard if the guard turns out to have no effect.")


if __name__ == "__main__":
    main()
