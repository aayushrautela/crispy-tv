#!/usr/bin/env python3
"""Mutation pass for the date/clock landing.

Every entry names a decision the new suites claim to cover. A surviving mutation is
a finding about the suite, not about the code, so the driver prints one of three
outcomes and never just "no test failed":

  CAUGHT   -- the compile failed, or at least one test failed
  SKIP     -- the anchor text was not found; NOT evidence either way
  SURVIVED -- the change compiled and every test still passed

It restores the file in a `finally` and prints `restored: True`, so a crash cannot
leave the tree dirty. It is a script, never an import: it must not run on import.
"""

import io
import os
import re
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))

ISO = "android/core-domain/src/commonMain/kotlin/com/crispy/tv/domain/watch/Iso8601.kt"
LIB = "android/app/src/commonMain/kotlin/com/crispy/tv/library/LibraryScreen.kt"
HDR = "android/app/src/commonMain/kotlin/com/crispy/tv/details/DetailsHeader.kt"

# (label, path, old, new, gradle-task, --tests filter)
MUTATIONS = [
    # ---- Iso8601.kt / CivilMonthKeyTest -------------------------------------
    (
        "month-key-offsets-the-instant-the-other-way",
        ISO,
        "val (year, month, _) = civilFromEpochDay(utcEpochDayOf(epochMillis + utcOffsetMillis))",
        "val (year, month, _) = civilFromEpochDay(utcEpochDayOf(epochMillis - utcOffsetMillis))",
        ":android:core-domain:desktopTest",
        "CivilMonthKeyTest",
    ),
    (
        "month-key-drops-the-offset-entirely",
        ISO,
        "utcEpochDayOf(epochMillis + utcOffsetMillis)",
        "utcEpochDayOf(epochMillis)",
        ":android:core-domain:desktopTest",
        "CivilMonthKeyTest",
    ),
    (
        "previous-month-does-not-decrement-the-year",
        ISO,
        "return if (month == 1) monthKeyOf(year - 1, 12) else monthKeyOf(year, month - 1)",
        "return if (month == 1) monthKeyOf(year, 12) else monthKeyOf(year, month - 1)",
        ":android:core-domain:desktopTest",
        "CivilMonthKeyTest",
    ),
    (
        "previous-month-does-not-wrap-december",
        ISO,
        "return if (month == 1) monthKeyOf(year - 1, 12) else monthKeyOf(year, month - 1)",
        "return if (month == 1) monthKeyOf(year - 1, 11) else monthKeyOf(year, month - 1)",
        ":android:core-domain:desktopTest",
        "CivilMonthKeyTest",
    ),
    (
        "previous-month-accepts-month-zero",
        ISO,
        # The bare `if (month !in 1..12) return null` also matches
        # parseIso8601MonthNumber's identical guard, so it counted 2x. The
        # disambiguating line is the one above it, which only this function has.
        "    val month = monthKey.readDigits(5, 7) ?: return null\n    if (month !in 1..12) return null",
        "    val month = monthKey.readDigits(5, 7) ?: return null\n    if (month !in 0..12) return null",
        ":android:core-domain:desktopTest",
        "CivilMonthKeyTest",
    ),
    (
        "previous-month-does-not-refuse-year-zero-january",
        ISO,
        "if (month == 1 && year == 0) return null",
        "if (month == 1 && year == 1) return null",
        ":android:core-domain:desktopTest",
        "CivilMonthKeyTest",
    ),
    (
        "month-key-year-is-not-zero-padded",
        ISO,
        "year.toString().padStart(4, '0') + \"-\" + month.toString().padStart(2, '0')",
        "year.toString() + \"-\" + month.toString().padStart(2, '0')",
        ":android:core-domain:desktopTest",
        "CivilMonthKeyTest",
    ),
    (
        "month-key-month-is-not-zero-padded",
        ISO,
        "year.toString().padStart(4, '0') + \"-\" + month.toString().padStart(2, '0')",
        "year.toString().padStart(4, '0') + \"-\" + month.toString()",
        ":android:core-domain:desktopTest",
        "CivilMonthKeyTest",
    ),
    (
        "month-key-parser-failure-is-not-null",
        ISO,
        "parseIso8601InstantToEpochMillis(value) ?: return null,",
        "parseIso8601InstantToEpochMillis(value) ?: 0L,",
        ":android:core-domain:desktopTest",
        "CivilMonthKeyTest",
    ),
    # ---- LibraryScreen.kt / LibraryMonthKeyTest ------------------------------
    (
        "history-month-key-does-not-read-the-offset",
        LIB,
        "if (timestamp.isNullOrBlank()) \"unknown\" else civilMonthKey(timestamp, utcOffsetMillis) ?: \"unknown\"",
        "if (timestamp.isNullOrBlank()) \"unknown\" else civilMonthKey(timestamp, 0L) ?: \"unknown\"",
        ":android:app:desktopTest",
        "LibraryMonthKeyTest",
    ),
    (
        "history-month-key-does-not-treat-blank-as-unknown",
        LIB,
        "if (timestamp.isNullOrBlank()) \"unknown\" else",
        "if (timestamp.isNullOrEmpty()) \"unknown\" else",
        ":android:app:desktopTest",
        "LibraryMonthKeyTest",
    ),
    (
        "history-month-label-prefers-this-month-over-unknown",
        LIB,
        'monthKey == "unknown" -> "Unknown date"\n    monthKey == currentMonthKey -> "This Month"',
        'monthKey == currentMonthKey -> "This Month"\n    monthKey == "unknown" -> "Unknown date"',
        ":android:app:desktopTest",
        "LibraryMonthKeyTest",
    ),
    (
        "history-month-label-ignores-the-slot-on-the-fallback-arm",
        LIB,
        "else -> monthName(monthKey)",
        'else -> monthKey',
        ":android:app:desktopTest",
        "LibraryMonthKeyTest",
    ),
    (
        "history-sections-do-not-prefer-last-activity",
        LIB,
        "val key = historyMonthKey(item.lastActivityAt ?: item.watchedAt, utcOffsetMillis)",
        "val key = historyMonthKey(item.watchedAt, utcOffsetMillis)",
        ":android:app:desktopTest",
        "LibraryMonthKeyTest",
    ),
    (
        "history-sections-skip-the-collapse",
        LIB,
        "return result.map { section -> section.copy(items = collapseEpisodesByShow(section.items)) }",
        "return result",
        ":android:app:desktopTest",
        "LibraryMonthKeyTest",
    ),
    (
        "collapse-does-not-add-the-incoming-count",
        LIB,
        "val updated = existing.copy(episodeCount = (existing.episodeCount ?: 0) + (item.episodeCount ?: 1))",
        "val updated = existing.copy(episodeCount = existing.episodeCount ?: 0)",
        ":android:app:desktopTest",
        "LibraryMonthKeyTest",
    ),
    (
        "collapse-keys-on-row-id-rather-than-item-id",
        LIB,
        "val existing = mergedByShow[item.itemId]",
        "val existing = mergedByShow[item.id]",
        ":android:app:desktopTest",
        "LibraryMonthKeyTest",
    ),
    (
        "watchlist-group-reads-the-year-before-the-month",
        LIB,
        "addedMonthKey == currentMonthKey -> WATCHLIST_GROUP_THIS_MONTH",
        "addedYear == currentYear -> WATCHLIST_GROUP_THIS_MONTH",
        ":android:app:desktopTest",
        "LibraryMonthKeyTest",
    ),
    (
        "watchlist-group-does-not-wrap-last-month-over-new-year",
        LIB,
        "addedMonthKey == previousMonthKey(currentMonthKey) -> WATCHLIST_GROUP_LAST_MONTH",
        "addedMonthKey == previousMonthKey(currentMonthKey) && addedYear == currentYear -> WATCHLIST_GROUP_LAST_MONTH",
        ":android:app:desktopTest",
        "LibraryMonthKeyTest",
    ),
    (
        "watchlist-group-does-not-treat-blank-as-older",
        LIB,
        "if (addedAt.isNullOrBlank()) return WATCHLIST_GROUP_OLDER",
        "if (addedAt.isNullOrEmpty()) return WATCHLIST_GROUP_OLDER",
        ":android:app:desktopTest",
        "LibraryMonthKeyTest",
    ),
    (
        "watchlist-group-unparseable-is-not-older",
        LIB,
        "val addedMonthKey = civilMonthKey(addedAt, utcOffsetMillis) ?: return WATCHLIST_GROUP_OLDER",
        "val addedMonthKey = civilMonthKey(addedAt, utcOffsetMillis) ?: currentMonthKey",
        ":android:app:desktopTest",
        "LibraryMonthKeyTest",
    ),
    (
        "watchlist-group-drops-the-last-year-band",
        LIB,
        "addedYear == currentYear - 1 -> WATCHLIST_GROUP_LAST_YEAR",
        "addedYear == currentYear - 2 -> WATCHLIST_GROUP_LAST_YEAR",
        ":android:app:desktopTest",
        "LibraryMonthKeyTest",
    ),
    (
        "watchlist-label-last-year-reads-as-older",
        LIB,
        'WATCHLIST_GROUP_LAST_YEAR -> "Last Year"',
        'WATCHLIST_GROUP_LAST_YEAR -> "Older"',
        ":android:app:desktopTest",
        "LibraryMonthKeyTest",
    ),
    (
        "watchlist-label-unknown-key-blanks-the-header",
        LIB,
        '        WATCHLIST_GROUP_OLDER -> "Older"\n        else -> "Older"',
        '        WATCHLIST_GROUP_OLDER -> "Older"\n        else -> groupKey',
        ":android:app:desktopTest",
        "LibraryMonthKeyTest",
    ),
    (
        "watchlist-sections-do-not-collapse",
        LIB,
        "val key = watchlistGroupKey(item.addedAt, currentMonthKey, utcOffsetMillis)",
        "val key = item.addedAt ?: WATCHLIST_GROUP_OLDER",
        ":android:app:desktopTest",
        "LibraryMonthKeyTest",
    ),
    (
        "watchlist-sections-split-on-every-item",
        LIB,
        "if (key != currentKey && currentKey != null && currentItems.isNotEmpty()) {\n            result.add(WatchlistDateSectionUi(currentKey, watchlistGroupLabel(currentKey), currentItems.toList()))",
        "if (true) {\n            result.add(WatchlistDateSectionUi(currentKey, watchlistGroupLabel(currentKey), currentItems.toList()))",
        ":android:app:desktopTest",
        "LibraryMonthKeyTest",
    ),
    (
        "rating-bands-are-not-ordered-liked-first",
        LIB,
        "RATING_BAND_LIKED to \"Liked\",\n            RATING_BAND_DISLIKED to \"Disliked\",",
        "RATING_BAND_DISLIKED to \"Disliked\",\n            RATING_BAND_LIKED to \"Liked\",",
        ":android:app:desktopTest",
        "LibraryMonthKeyTest",
    ),
    (
        "rating-bands-keep-an-empty-band",
        LIB,
        "}.filter { it.items.isNotEmpty() }",
        "}",
        ":android:app:desktopTest",
        "LibraryMonthKeyTest",
    ),
    (
        "rating-bands-admit-an-unrated-item",
        LIB,
        "RATING_BAND_LIKED -> items.filter { it.liked == true }",
        "RATING_BAND_LIKED -> items.filter { it.liked != false }",
        ":android:app:desktopTest",
        "LibraryMonthKeyTest",
    ),
    (
        # NOT a double-read mutation. `currentMonthKeyOf` is a one-line delegation
        # with a single `clock()` call site, and my first attempt at this entry
        # rewrote it as `clock() + if (utcOffsetMillis == 0L) 0L else 0L` -- which is
        # `+ 0`, so it survived and reported an uncovered guard that does not exist.
        # What is load-bearing here is that the *offset* crosses, and the offset is
        # invisible at UTC, so this has to be a call that disagrees with the suite.
        "current-month-key-drops-the-offset",
        LIB,
        "civilMonthKeyFromEpochMillis(clock(), utcOffsetMillis)",
        "civilMonthKeyFromEpochMillis(clock(), 0L)",
        ":android:app:desktopTest",
        "LibraryMonthKeyTest",
    ),
    # ---- DetailsHeader.kt / DetailsHeaderSubtextTest ------------------------
    (
        "cta-subtext-prefers-the-running-time-over-the-rewatch",
        HDR,
        "watchCta.kind == WatchCtaKind.REWATCH && watchCta.lastWatchedAtEpochMs != null -> {",
        "watchCta.remainingMinutes != null -> {",
        ":android:app:desktopTest",
        "DetailsHeaderSubtextTest",
    ),
    (
        "cta-subtext-drops-the-timestamp-guard",
        HDR,
        "watchCta.kind == WatchCtaKind.REWATCH && watchCta.lastWatchedAtEpochMs != null -> {",
        "watchCta.kind == WatchCtaKind.REWATCH -> {",
        ":android:app:desktopTest",
        "DetailsHeaderSubtextTest",
    ),
    (
        "cta-subtext-requires-a-positive-runtime",
        HDR,
        "watchCta.remainingMinutes != null -> {",
        "(watchCta.remainingMinutes ?: 0) > 0 -> {",
        ":android:app:desktopTest",
        "DetailsHeaderSubtextTest",
    ),
    (
        "cta-subtext-drops-the-second-call",
        HDR,
        '"Last watched on $date"',
        '"Last watched"',
        ":android:app:desktopTest",
        "DetailsHeaderSubtextTest",
    ),
    (
        # Again NOT what the label said. The first version of this entry appended
        # `+ 0L` to the same expression, which is `+ 0`, and survived. What is
        # load-bearing is that the *clock* is added at all: the suite's assertions
        # read the exact epoch, so dropping the clock is observable.
        "cta-subtext-forgets-the-clock",
        HDR,
        "val endsAtMs = clock() + (watchCta.remainingMinutes * 60_000L)",
        "val endsAtMs = watchCta.remainingMinutes * 60_000L",
        ":android:app:desktopTest",
        "DetailsHeaderSubtextTest",
    ),
    (
        "cta-subtext-does-not-scale-minutes-to-millis",
        HDR,
        "val endsAtMs = clock() + (watchCta.remainingMinutes * 60_000L)",
        "val endsAtMs = clock() + (watchCta.remainingMinutes * 1_000L)",
        ":android:app:desktopTest",
        "DetailsHeaderSubtextTest",
    ),
]


def run(task, filt):
    # Deliberately NOT -q. Gradle's per-test `Class[target] > name[target] FAILED`
    # lines are the only evidence that a mutation was caught by a *test* rather than
    # by the compiler, and -q suppresses them: the first run of this driver reported
    # all 29 caught mutations as an indistinguishable "build failed".
    return subprocess.run(
        ["./gradlew", task, "--tests", filt],
        cwd=ROOT,
        capture_output=True,
        text=True,
        env={**os.environ, "JAVA_TOOL_OPTIONS": "-Djava.awt.headless=true"},
    )


def failed_tests(out):
    return sorted(set(re.findall(r"^(\w+)\[.*?\] > (\S+?)\[.*?\] FAILED", out, re.M)))


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "list":
        for m in MUTATIONS:
            print(m[0])
        return

    counts = {"CAUGHT": 0, "SKIP": 0, "SURVIVED": 0}
    for label, rel, old, new, task, filt in MUTATIONS:
        path = os.path.join(ROOT, rel)
        original = io.open(path, encoding="utf-8").read()
        if original.count(old) != 1:
            print(f"SKIP     {label}: anchor occurs {original.count(old)}x")
            counts["SKIP"] += 1
            continue
        try:
            io.open(path, "w", encoding="utf-8").write(original.replace(old, new, 1))
            proc = run(task, filt)
            out = proc.stdout + proc.stderr
            if proc.returncode == 0:
                print(f"SURVIVED {label}: compiled and every test still passed")
                counts["SURVIVED"] += 1
            else:
                names = failed_tests(out)
                if names:
                    detail = ", ".join(n[1] for n in names[:4])
                    print(f"CAUGHT   {label}: {len(names)} test(s) -- {detail}")
                else:
                    marker = "COMPILE FAILED -- not evidence" if "COMPILE FAILED" in out or "^e: " in out else "build failed"
                    print(f"CAUGHT   {label}: {marker}")
                counts["CAUGHT"] += 1
        finally:
            io.open(path, "w", encoding="utf-8").write(original)
            print(f"         restored: {io.open(path, encoding='utf-8').read() == original}")

    print()
    print(f"CAUGHT {counts['CAUGHT']}  SKIP {counts['SKIP']}  SURVIVED {counts['SURVIVED']}  "
          f"of {len(MUTATIONS)}")


if __name__ == "__main__":
    main()
