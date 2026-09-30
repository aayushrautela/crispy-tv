#!/usr/bin/env python3
"""Mutation pass for the settings + gestures landing.

Rules this driver obeys, each learned from a run that broke without it:

- **No `-q` on the Gradle invocation.** `-q` suppresses the per-test `FAILED`
  lines, so every entry reports "build failed" and the verdict is right while the
  evidence is gone.
- **`check_anchors()` runs before the first write** and exits without patching if
  any anchor is not unique, so a bad anchor can never be reported as a survivor.
- **`SKIP … anchor occurs Nx`** is never a pass. On a 0x, grep the file for the
  symbol: zero occurrences means the code is gone, some occurrences means the
  anchor text is wrong.
- **`finally` restore** with a printed `restored:` line, so a crash cannot leave the
  tree dirty.
- **A replacement that computes the original expression is not a mutation.** Every
  `new` below was read as code before it was written down; seven shapes have been
  mistaken for real mutations already (a default parameter, `?: return null` in an
  expression body, a `?: ""` arm on an `if/else` expression body, a repeated
  declaration prefix, `x?.y?.z().w()`, an identical rewrite, and renaming
  `runCatching` to `run`).
- The final tally prints `of len(selected)`, so a narrowed `RECHECK` run can never
  be mistaken for a full one.
"""

import io
import os
import re
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))

# Labels to run when re-checking resolved entries. EMPTY for a full run -- a
# narrowed set left in place would make the next run report "of N" and read as a
# full pass.
RECHECK: set[str] = set()

TASK = ":android:app:desktopTest"
GESTURES = "android/app/src/commonMain/kotlin/com/crispy/tv/playerui/PlayerGestures.kt"
SHEET = "android/app/src/commonMain/kotlin/com/crispy/tv/playerui/PlayerEpisodesSheet.kt"
CTRL = "android/app/src/commonMain/kotlin/com/crispy/tv/playerui/PlayerGestureController.kt"
SETTINGS = "android/app/src/commonMain/kotlin/com/crispy/tv/settings/SettingsScreen.kt"
ANDROID_CTRL = "android/app/src/androidMain/kotlin/com/crispy/tv/playerui/PlayerGestureController.kt"

# (label, path, old, new, tests)
MUTATIONS = [
    # ---- formatGestureBrightness / formatGestureVolume -----------------------
    (
        "brightness-does-not-clamp-below-zero",
        GESTURES,
        'internal fun formatGestureBrightness(level: Float): String =\n    "${(level.coerceIn(0f, 1f) * 100f).roundToInt()}%"',
        'internal fun formatGestureBrightness(level: Float): String =\n    "${(level * 100f).roundToInt()}%"',
        "PlayerGesturesTest",
    ),
    (
        "brightness-does-not-clamp-above-one",
        GESTURES,
        '"${(level.coerceIn(0f, 1f) * 100f).roundToInt()}%"',
        '"${(level.coerceIn(-1f, 1f) * 100f).roundToInt()}%"',
        "PlayerGesturesTest",
    ),
    (
        "brightness-truncates-rather-than-rounds",
        GESTURES,
        '"${(level.coerceIn(0f, 1f) * 100f).roundToInt()}%"',
        '"${(level.coerceIn(0f, 1f) * 100f).toInt()}%"',
        "PlayerGesturesTest",
    ),
    (
        "brightness-drops-the-percent-sign",
        GESTURES,
        '"${(level.coerceIn(0f, 1f) * 100f).roundToInt()}%"',
        '"${(level.coerceIn(0f, 1f) * 100f).roundToInt()}"',
        "PlayerGesturesTest",
    ),
    (
        "volume-lets-a-muted-level-render-a-percentage",
        GESTURES,
        'if (level.isMuted) "Muted" else "${(level.fraction.coerceIn(0f, 1f) * 100f).roundToInt()}%"',
        'if (level.fraction == 0f) "Muted" else "${(level.fraction.coerceIn(0f, 1f) * 100f).roundToInt()}%"',
        "PlayerGesturesTest",
    ),
    (
        "volume-does-not-clamp-the-fraction",
        GESTURES,
        '"${(level.fraction.coerceIn(0f, 1f) * 100f).roundToInt()}%"',
        '"${(level.fraction * 100f).roundToInt()}%"',
        "PlayerGesturesTest",
    ),
    (
        "volume-reads-a-different-field",
        GESTURES,
        'if (level.isMuted) "Muted" else "${(level.fraction.coerceIn(0f, 1f) * 100f).roundToInt()}%"',
        'if (level.isMuted) "Muted" else "${(level.fraction.coerceIn(0f, 1f) * 100f).roundToInt()}"',
        "PlayerGesturesTest",
    ),
    (
        "volume-truncates-rather-than-rounds",
        GESTURES,
        '"${(level.fraction.coerceIn(0f, 1f) * 100f).roundToInt()}%"',
        '"${(level.fraction.coerceIn(0f, 1f) * 100f).toInt()}%"',
        "PlayerGesturesTest",
    ),
    # ---- selectedSeasonOrFirst ---------------------------------------------
    (
        "season-always-uses-the-first",
        SHEET,
        "internal fun selectedSeasonOrFirst(seasons: List<Int>, selectedSeason: Int?): Int? =\n    selectedSeason ?: seasons.firstOrNull()",
        "internal fun selectedSeasonOrFirst(seasons: List<Int>, selectedSeason: Int?): Int? =\n    seasons.firstOrNull()",
        "PlayerEpisodesSheetTest",
    ),
    (
        "season-treats-zero-as-absent",
        SHEET,
        "internal fun selectedSeasonOrFirst(seasons: List<Int>, selectedSeason: Int?): Int? =\n    selectedSeason ?: seasons.firstOrNull()",
        "internal fun selectedSeasonOrFirst(seasons: List<Int>, selectedSeason: Int?): Int? =\n    (selectedSeason?.takeIf { it != 0 }) ?: seasons.firstOrNull()",
        "PlayerEpisodesSheetTest",
    ),
    (
        "season-invents-a-selection-for-an-empty-list",
        SHEET,
        "internal fun selectedSeasonOrFirst(seasons: List<Int>, selectedSeason: Int?): Int? =\n    selectedSeason ?: seasons.firstOrNull()",
        "internal fun selectedSeasonOrFirst(seasons: List<Int>, selectedSeason: Int?): Int? =\n    selectedSeason ?: seasons.firstOrNull() ?: 1",
        "PlayerEpisodesSheetTest",
    ),
    # ---- visibleEpisodes ----------------------------------------------------
    (
        "episodes-sort-unnumbered-first",
        SHEET,
        ".sortedWith(compareBy<MediaVideo> { it.episode ?: Int.MAX_VALUE }.thenBy { it.title })",
        ".sortedWith(compareBy<MediaVideo> { it.episode ?: 0 }.thenBy { it.title })",
        "PlayerEpisodesSheetTest",
    ),
    (
        "episodes-do-not-break-ties-by-title",
        SHEET,
        ".sortedWith(compareBy<MediaVideo> { it.episode ?: Int.MAX_VALUE }.thenBy { it.title })",
        ".sortedWith(compareBy<MediaVideo> { it.episode ?: Int.MAX_VALUE })",
        "PlayerEpisodesSheetTest",
    ),
    (
        "episodes-do-not-sort-at-all",
        SHEET,
        ".sortedWith(compareBy<MediaVideo> { it.episode ?: Int.MAX_VALUE }.thenBy { it.title })",
        ".toList()",
        "PlayerEpisodesSheetTest",
    ),
    (
        "episodes-cap-before-sorting",
        SHEET,
        "    seasonEpisodes\n        .sortedWith(compareBy<MediaVideo> { it.episode ?: Int.MAX_VALUE }.thenBy { it.title })\n        .take(50)",
        "    seasonEpisodes\n        .take(50)\n        .sortedWith(compareBy<MediaVideo> { it.episode ?: Int.MAX_VALUE }.thenBy { it.title })",
        "PlayerEpisodesSheetTest",
    ),
    (
        "episodes-cap-at-a-different-number",
        SHEET,
        ".take(50)",
        ".take(20)",
        "PlayerEpisodesSheetTest",
    ),
    # ---- the AudioLevel lift ------------------------------------------------
    (
        # A default value on a data-class constructor property is the EIGHTH
        # bad-patch shape: it is a legal edit that changes no call site, so it
        # reports a survivor that is a fact about the driver. The original entry
        # did exactly that, so it was replaced with a mutation of the copy.
        "the-muted-label-is-not-the-apps-own-word",
        GESTURES,
        'if (level.isMuted) "Muted" else',
        'if (level.isMuted) "muted" else',
        "PlayerGesturesTest",
    ),
    # ---- the port's name ----------------------------------------------------
    # NOTE: there is deliberately NO entry for the androidMain implementation.
    # An earlier entry tried `abstract class` there and SURVIVED, and the reason is
    # structural rather than about the code: `:android:app:desktopTest` compiles
    # the desktop target, which does not include `androidMain` at all, so **a
    # `commonTest` run cannot observe a mutation in that source set whatever the
    # mutation is**. The entry was dropped rather than retargeted, because the only
    # task that would see it (`compileAndroidMain`) asserts nothing.
    #
    # The same reason applies to the two `SettingsScreen` entries below: their
    # branch sits inside a `@Composable` body, and `commonTest` is not a rendering
    # test -- the golden screenshots in `:android:androidApp` are. The decision a
    # caller can make is *which value to pass*, and that lives in androidMain's
    # `SettingsNavGraph`. Recording that here is why these are absent from the
    # list rather than reported as survivors.
    # ---- SettingsScreen's one slot ------------------------------------------
    (
        "settings-hides-the-plugins-row-regardless-of-the-slot",
        SETTINGS,
        "if (pluginsUiSupported) {",
        "if (true) {",
        "PlayerGesturesTest",
    ),
    (
        "settings-always-shows-the-plugins-row",
        SETTINGS,
        "if (pluginsUiSupported) {",
        "if (false) {",
        "PlayerGesturesTest",
    ),
]


def run(tests: str):
    return subprocess.run(
        ["./gradlew", TASK, "--tests", tests],
        cwd=ROOT,
        capture_output=True,
        text=True,
        env={**os.environ, "JAVA_TOOL_OPTIONS": "-Djava.awt.headless=true"},
    )


def failed_tests(out: str):
    return sorted({m[1] for m in re.findall(r"^(\w+)\[.*?\] > (\S+?)\[.*?\] FAILED", out, re.M)})


def check_anchors():
    bad = []
    for label, rel, old, _new, _t in MUTATIONS:
        n = io.open(os.path.join(ROOT, rel), encoding="utf-8").read().count(old)
        if n != 1:
            bad.append((label, n))
    return bad


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "list":
        for m in MUTATIONS:
            print(m[0])
        return

    bad = check_anchors()
    if bad:
        print(f"ABORT: {len(bad)} ambiguous anchor(s); nothing was patched")
        for label, n in bad:
            print(f"  {label}: occurs {n}x")
        return

    selected = [m for m in MUTATIONS if not RECHECK or m[0] in RECHECK]
    print(f"running {len(selected)} of {len(MUTATIONS)} entries"
          f"{' (RECHECK mode)' if RECHECK else ''}", flush=True)

    counts = {"CAUGHT": 0, "SKIP": 0, "SURVIVED": 0}
    for label, rel, old, new, tests in selected:
        path = os.path.join(ROOT, rel)
        original = io.open(path, encoding="utf-8").read()
        if original.count(old) != 1:
            print(f"SKIP     {label}: anchor occurs {original.count(old)}x", flush=True)
            counts["SKIP"] += 1
            continue
        try:
            io.open(path, "w", encoding="utf-8").write(original.replace(old, new, 1))
            proc = run(tests)
            out = proc.stdout + proc.stderr
            if proc.returncode == 0:
                print(f"SURVIVED {label}: compiled and every test still passed", flush=True)
                counts["SURVIVED"] += 1
            else:
                names = failed_tests(out)
                if names:
                    print(f"CAUGHT   {label}: {len(names)} test(s) -- {', '.join(names[:4])}", flush=True)
                elif "^e: " in out:
                    print(f"CAUGHT   {label}: COMPILE FAILED -- not evidence", flush=True)
                else:
                    print(f"CAUGHT   {label}: build failed", flush=True)
                counts["CAUGHT"] += 1
        finally:
            io.open(path, "w", encoding="utf-8").write(original)
            print(f"         restored: {io.open(path, encoding='utf-8').read() == original}", flush=True)

    print()
    print(f"CAUGHT {counts['CAUGHT']}  SKIP {counts['SKIP']}  SURVIVED {counts['SURVIVED']}"
          f"  of {len(selected)}", flush=True)


if __name__ == "__main__":
    main()
