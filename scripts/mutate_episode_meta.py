#!/usr/bin/env python3
"""Mutation pass for the `formatEpisodeReleaseDate` / episode-metadata landing.

Covers the eight decisions that became reachable in this landing and the
extractions that made them reachable.

Rules this driver obeys, each learned from a run that got it wrong:

- **No `-q`.** Gradle's `-q` suppresses the per-test `FAILED` lines, so every
  caught mutation reports "build failed" with no name attached. The exit code is
  still trustworthy; the evidence is not.
- **`SKIP … anchor occurs Nx` is printed, never counted as a pass**, and every
  anchor is checked for uniqueness before the run starts.
- **A replacement is read for what it computes.** A `+ 0` is not a mutation.
- **`finally` restores, and prints `restored: True`**, so a crash cannot leave the
  tree dirty.
- It ends in `if __name__ == "__main__": main()` and is never imported.
"""

import io
import os
import re
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))

ROW = "android/app/src/commonMain/kotlin/com/crispy/tv/playerui/PlayerEpisodeRow.kt"
SHEET = "android/app/src/commonMain/kotlin/com/crispy/tv/playerui/PlayerInfoSheet.kt"
CTX = "android/app/src/commonMain/kotlin/com/crispy/tv/playerui/PlayerEpisodeContext.kt"
SEL = "android/app/src/commonMain/kotlin/com/crispy/tv/streams/StreamSelectorContent.kt"

APP_TEST = ":android:app:desktopTest"

# (label, path, old, new, filter)
MUTATIONS = [
    # ---- episodeRowMeta -----------------------------------------------------
    (
        "row-meta-needs-only-the-season",
        ROW,
        "if (season != null && episodeNumber != null) {",
        "if (season != null) {",
        "EpisodeMetaTest",
    ),
    (
        "row-meta-needs-only-the-episode-number",
        ROW,
        "if (season != null && episodeNumber != null) {",
        "if (episodeNumber != null) {",
        "EpisodeMetaTest",
    ),
    (
        "row-meta-drops-the-date",
        ROW,
        "formatLongDate(episode.released)?.let(parts::add)",
        "Unit",
        "EpisodeMetaTest",
    ),
    (
        "row-meta-renders-an-empty-line",
        ROW,
        "return parts.takeIf { it.isNotEmpty() }?.joinToString(\" • \")",
        "return parts.joinToString(\" • \")",
        "EpisodeMetaTest",
    ),
    (
        "row-meta-reads-the-episode-field-not-the-season",
        ROW,
        "parts += \"S$season E$episodeNumber\"",
        "parts += \"S$episodeNumber E$season\"",
        "EpisodeMetaTest",
    ),
    # ---- episodeHeaderMetadata ----------------------------------------------
    (
        "header-metadata-renders-an-empty-year",
        SEL,
        "} else {\n    details?.year?.trim()?.takeIf { it.isNotBlank() }\n}",
        "} else {\n    (details?.year?.trim()?.takeIf { it.isNotBlank() }) ?: \"\"\n}",
        "EpisodeMetaTest",
    ),
    (
        "header-metadata-does-not-blank-check-the-year",
        SEL,
        "details?.year?.trim()?.takeIf { it.isNotBlank() }",
        "details?.year?.trim()",
        "EpisodeMetaTest",
    ),
    (
        "header-metadata-does-not-trim-the-year",
        SEL,
        "details?.year?.trim()?.takeIf { it.isNotBlank() }",
        "details?.year?.takeIf { it.isNotBlank() }",
        "EpisodeMetaTest",
    ),
    (
        "header-metadata-always-prefers-the-year",
        SEL,
        "= if (episode != null) {\n    episodeRowMeta(episode)",
        "= if (false) {\n    episodeRowMeta(episode ?: MediaVideo(\"\", \"\", null, null, null, null, null))",
        "EpisodeMetaTest",
    ),
    # ---- PlayerInfoSheet: logoUrlFor ---------------------------------------
    (
        "logo-url-is-not-trimmed",
        SHEET,
        "internal fun logoUrlFor(details: MediaDetails?): String? =\n    details?.logoUrl?.trim()?.takeIf { it.isNotBlank() }",
        "internal fun logoUrlFor(details: MediaDetails?): String? =\n    details?.logoUrl?.takeIf { it.isNotBlank() }",
        "PlayerInfoSheetTest",
    ),
    (
        "logo-url-accepts-a-blank-string",
        SHEET,
        "internal fun logoUrlFor(details: MediaDetails?): String? =\n    details?.logoUrl?.trim()?.takeIf { it.isNotBlank() }",
        "internal fun logoUrlFor(details: MediaDetails?): String? =\n    details?.logoUrl?.trim()",
        "PlayerInfoSheetTest",
    ),
    # ---- PlayerInfoSheet: metaRowFor / isEmpty ------------------------------
    (
        "meta-row-is-empty-ignores-the-genres",
        SHEET,
        "rating == null && certification == null && year == null && runtime == null && genres.isEmpty()",
        "rating == null && certification == null && year == null && runtime == null",
        "PlayerInfoSheetTest",
    ),
    (
        "meta-row-is-empty-ignores-the-rating",
        SHEET,
        "rating == null && certification == null && year == null && runtime == null && genres.isEmpty()",
        "certification == null && year == null && runtime == null && genres.isEmpty()",
        "PlayerInfoSheetTest",
    ),
    (
        "meta-row-caps-genres-at-three",
        SHEET,
        "genres = details?.genres?.filter { it.isNotBlank() }.orEmpty().take(2),",
        "genres = details?.genres?.filter { it.isNotBlank() }.orEmpty().take(3),",
        "PlayerInfoSheetTest",
    ),
    (
        "meta-row-caps-genres-before-filtering",
        SHEET,
        "genres = details?.genres?.filter { it.isNotBlank() }.orEmpty().take(2),",
        "genres = details?.genres?.orEmpty().take(2).filter { it.isNotBlank() },",
        "PlayerInfoSheetTest",
    ),
    (
        "meta-row-does-not-trim-the-certification",
        SHEET,
        "certification = details?.certification?.trim()?.takeIf { it.isNotBlank() },",
        "certification = details?.certification,",
        "PlayerInfoSheetTest",
    ),
    (
        "meta-row-does-not-normalise-the-rating",
        SHEET,
        "rating = normalizeRatingText(details?.rating),",
        "rating = details?.rating,",
        "PlayerInfoSheetTest",
    ),
    (
        "meta-row-does-not-format-the-runtime",
        SHEET,
        "runtime = formatRuntimeForHeader(details?.runtime),",
        "runtime = details?.runtime,",
        "PlayerInfoSheetTest",
    ),
    # ---- PlayerInfoSheet: overviewTextFor ------------------------------------
    (
        "overview-prefers-the-show-description",
        SHEET,
        "    episodeContext?.overview\n        ?: details?.description?.trim()?.takeIf { it.isNotBlank() }",
        "    details?.description?.trim()?.takeIf { it.isNotBlank() }\n        ?: episodeContext?.overview",
        "PlayerInfoSheetTest",
    ),
    (
        "overview-does-not-trim-the-show-description",
        SHEET,
        "episodeContext?.overview\n        ?: details?.description?.trim()?.takeIf { it.isNotBlank() }",
        "episodeContext?.overview\n        ?: details?.description",
        "PlayerInfoSheetTest",
    ),
    (
        "overview-re-trims-the-episode-overview",
        SHEET,
        "    episodeContext?.overview\n        ?: details?.description?.trim()?.takeIf { it.isNotBlank() }",
        "    episodeContext?.overview?.trim()?.takeIf { it.isNotBlank() }\n        ?: details?.description?.trim()?.takeIf { it.isNotBlank() }",
        "PlayerInfoSheetTest",
    ),
    # ---- PlayerInfoSheet: castNamesFor --------------------------------------
    (
        "cast-caps-before-filtering",
        SHEET,
        "internal fun castNamesFor(details: MediaDetails?): List<String> =\n    details?.cast?.filter { it.isNotBlank() }.orEmpty().take(5)",
        "internal fun castNamesFor(details: MediaDetails?): List<String> =\n    details?.cast?.orEmpty().take(5).filter { it.isNotBlank() }",
        "PlayerInfoSheetTest",
    ),
    (
        "cast-caps-at-four",
        SHEET,
        "details?.cast?.filter { it.isNotBlank() }.orEmpty().take(5)",
        "details?.cast?.filter { it.isNotBlank() }.orEmpty().take(4)",
        "PlayerInfoSheetTest",
    ),
    (
        "cast-does-not-drop-blanks",
        SHEET,
        "details?.cast?.filter { it.isNotBlank() }.orEmpty().take(5)",
        "details?.cast.orEmpty().take(5)",
        "PlayerInfoSheetTest",
    ),
    # ---- PlayerInfoSheet: parseCastEntry ------------------------------------
    (
        "cast-entry-takes-the-last-separator",
        SHEET,
        'val separator = entry.indexOf(" as ")',
        'val separator = entry.lastIndexOf(" as ")',
        "PlayerInfoSheetTest",
    ),
    (
        "cast-entry-separator-is-case-insensitive",
        SHEET,
        'if (separator < 0) return entry to null',
        'if (separator < 0) { val alt = entry.indexOf(" AS "); if (alt < 0) return entry to null; return entry.substring(0, alt).trim() to entry.substring(alt + 4).trim().takeIf { it.isNotBlank() } }',
        "PlayerInfoSheetTest",
    ),
    (
        "cast-entry-does-not-blank-check-the-character",
        SHEET,
        'val character = entry.substring(separator + 4).trim().takeIf { it.isNotBlank() }',
        'val character = entry.substring(separator + 4).trim()',
        "PlayerInfoSheetTest",
    ),
    (
        "cast-entry-does-not-trim-the-name",
        SHEET,
        "val name = entry.substring(0, separator).trim()",
        "val name = entry.substring(0, separator)",
        "PlayerInfoSheetTest",
    ),
    # ---- PlayerInfoSheet: buildCreditLine -----------------------------------
    (
        "credit-line-does-not-read-item-type-case-insensitively",
        SHEET,
        'val isMovie = details?.itemType.equals("movie", ignoreCase = true)',
        'val isMovie = details?.itemType == "movie"',
        "PlayerInfoSheetTest",
    ),
    (
        "credit-line-treats-a-missing-item-type-as-a-movie",
        SHEET,
        'val isMovie = details?.itemType.equals("movie", ignoreCase = true)',
        'val isMovie = details?.itemType != "series"',
        "PlayerInfoSheetTest",
    ),
    (
        "credit-line-uses-the-wrong-verb-for-a-movie",
        SHEET,
        '?.let { "Directed by $it" }',
        '?.let { "Created by $it" }',
        "PlayerInfoSheetTest",
    ),
    (
        "credit-line-a-movie-does-not-drop-blank-directors",
        SHEET,
        '        details\n            ?.directors\n            ?.filter { it.isNotBlank() }',
        '        details\n            ?.directors',
        "PlayerInfoSheetTest",
    ),
    (
        "credit-line-drops-the-post-join-blank-check",
        SHEET,
            '            ?.takeIf { it.isNotBlank() }\n            ?.let { "Created by $it" }',
            '            ?.let { "Created by $it" }',
        "PlayerInfoSheetTest",
    ),
    # ---- PlayerEpisodeContext ----------------------------------------------
    (
        "season-episode-label-needs-only-the-season",
        CTX,
        'get() = if (season != null && episode != null) "S${season}E${episode}" else ""',
        'val seasonEpisodeLabel: String get() = if (season != null) "S${season}E${episode}" else ""',
        "PlayerInfoSheetTest",
    ),
    (
        "season-episode-label-pads-with-a-space",
        CTX,
        'if (season != null && episode != null) "S${season}E${episode}" else ""',
        'if (season != null && episode != null) "S${season} E${episode}" else ""',
        "PlayerInfoSheetTest",
    ),
    (
        "details-context-needs-only-the-season-number",
        CTX,
        "if (seasonNumber == null || episodeNumber == null) return null",
        "if (seasonNumber == null) return null",
        "PlayerInfoSheetTest",
    ),
    (
        "details-context-does-not-match-the-episode",
        CTX,
        "videos.firstOrNull { it.season == seasonNumber && it.episode == episodeNumber }\n        ?: videos.firstOrNull()",
        "videos.firstOrNull { it.episode == episodeNumber } ?: videos.firstOrNull()",
        "PlayerInfoSheetTest",
    ),
    (
        "details-context-does-not-fall-back-to-any-video",
        CTX,
        "videos.firstOrNull { it.season == seasonNumber && it.episode == episodeNumber }\n        ?: videos.firstOrNull()",
        "videos.firstOrNull { it.season == seasonNumber && it.episode == episodeNumber }",
        "PlayerInfoSheetTest",
    ),
    (
        "video-context-needs-only-the-season",
        CTX,
        "if (season == null || episode == null) return null",
        "if (season == null) return null",
        "PlayerInfoSheetTest",
    ),
    (
        "video-context-does-not-trim-its-text",
        CTX,
        "title = title.trim().takeIf { it.isNotBlank() },\n        overview = overview?.trim()?.takeIf { it.isNotBlank() },",
        "title = title,\n        overview = overview,",
        "PlayerInfoSheetTest",
    ),
    (
        "details-context-does-not-prefer-the-episode-overview",
        CTX,
        "overview = episode?.overview?.trim()?.takeIf { it.isNotBlank() }\n            ?: description?.trim()?.takeIf { it.isNotBlank() },",
        "overview = description?.trim()?.takeIf { it.isNotBlank() } ?: episode?.overview?.trim()?.takeIf { it.isNotBlank() }",
        "PlayerInfoSheetTest",
    ),
]


def run(filt):
    return subprocess.run(
        ["./gradlew", APP_TEST, "--tests", filt],
        cwd=ROOT,
        capture_output=True,
        text=True,
        env={**os.environ, "JAVA_TOOL_OPTIONS": "-Djava.awt.headless=true"},
    )


def named_failures(out):
    return sorted(set(re.findall(r"^(\w+)\[.*?\] > (\S+?)\[.*?\] FAILED", out, re.M)))


def check_anchors():
    """Every anchor must be unique, checked before anything is patched."""
    bad = 0
    for label, rel, old, new, filt in MUTATIONS:
        body = io.open(os.path.join(ROOT, rel), encoding="utf-8").read()
        n = body.count(old)
        if n != 1:
            print(f"AMBIGUOUS {label}: anchor occurs {n}x in {rel}")
            bad += 1
    return bad


def main():
    if "list" in sys.argv:
        for m in MUTATIONS:
            print(m[0])
        return

    bad = check_anchors()
    if bad:
        print(f"\n{bad} ambiguous anchor(s); nothing was patched. Fix them and re-run.")
        return

    counts = {"CAUGHT": 0, "SKIP": 0, "SURVIVED": 0}
    for label, rel, old, new, filt in MUTATIONS:
        path = os.path.join(ROOT, rel)
        original = io.open(path, encoding="utf-8").read()
        if original.count(old) != 1:
            print(f"SKIP     {label}: anchor occurs {original.count(old)}x")
            counts["SKIP"] += 1
            continue
        try:
            io.open(path, "w", encoding="utf-8").write(original.replace(old, new, 1))
            proc = run(filt)
            out = proc.stdout + proc.stderr
            if proc.returncode == 0:
                print(f"SURVIVED {label}: compiled and every test still passed")
                counts["SURVIVED"] += 1
            else:
                names = named_failures(out)
                if names:
                    detail = ", ".join(n[1] for n in names[:4])
                    print(f"CAUGHT   {label}: {len(names)} test(s) -- {detail}")
                elif re.search(r"^e: ", out, re.M):
                    first = re.search(r"^e: (.*)$", out, re.M).group(1)
                    print(f"SKIP     {label}: COMPILE FAILED -- not evidence ({first[:110]})")
                    counts["SKIP"] += 1
                else:
                    print(f"CAUGHT   {label}: build failed, no test name in the output")
                counts["CAUGHT"] += 1 if names or not re.search(r"^e: ", out, re.M) else 0
        finally:
            io.open(path, "w", encoding="utf-8").write(original)
            ok = io.open(path, encoding="utf-8").read() == original
            print(f"         restored: {ok}")
            if not ok:
                raise SystemExit("restore failed; tree is dirty")

    print()
    print(f"CAUGHT {counts['CAUGHT']}  SKIP {counts['SKIP']}  SURVIVED {counts['SURVIVED']}  "
          f"of {len(MUTATIONS)}")


if __name__ == "__main__":
    main()
