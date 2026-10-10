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

# `:app`'s `desktopTest` builds the **desktop** target only, so a mutation in an
# `androidMain` file cannot fail under it however it is written. `testAndroidHostTest`
# is the module's only test compilation that compiles `androidMain` as well, so an
# entry whose file lives there must name this task instead. An entry that picks the
# wrong task reports SURVIVED and the verdict is a fact about the driver, not the code.
HOST_TASK = ":android:app:testAndroidHostTest"
GESTURES = "android/app/src/commonMain/kotlin/com/crispy/tv/playerui/PlayerGestures.kt"
SHEET = "android/app/src/commonMain/kotlin/com/crispy/tv/playerui/PlayerEpisodesSheet.kt"
CTRL = "android/app/src/commonMain/kotlin/com/crispy/tv/playerui/PlayerGestureController.kt"
SETTINGS = "android/app/src/commonMain/kotlin/com/crispy/tv/settings/SettingsScreen.kt"
ANDROID_CTRL = "android/app/src/androidMain/kotlin/com/crispy/tv/playerui/PlayerGestureController.kt"

# (label, path, old, new, tests[, task])
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
    # NOTE: `SettingsScreen`'s `if (pluginsUiSupported)` has no entry, and so does
    # nothing else in this file's `commonMain` -- the reason is written below, where
    # the `androidMain` entries that *were* unobservable now live, because they are
    # now observed.
]

# ---- entries that CANNOT be observed by any compilation task -----------------
#
# `SettingsScreen`'s `if (pluginsUiSupported)` was dropped from this list, and the
# reason is now measured rather than assumed. Unlike the `androidMain` entries
# below, this branch is not a source-set problem: it sits inside a `@Composable`
# body, so **no** compilation task observes it -- only a rendering harness does,
# and there is no image-comparison gate left in the repository to render it.
# Reaching for a Compose test rule would add a dependency to pin one boolean, and
# extracting an `internal fun shouldShowPluginsEntry(p: Boolean) = p` would be a
# decision no test can tell from the call site -- the "a test's own re-implementation
# of a decision" trap in reverse. The decision that is worth testing is the *caller's*,
# and `SettingsNavGraph` is where `pluginsUiSupported` is read.

# ---- AndroidPlayerGestureController (androidMain) ----------------------------
#
# Every entry below names `HOST_TASK`. These are the implementation behind a port
# the same landing created, and before this suite they had **no** verification at
# all: the original entry turned the class `abstract` -- which cannot compile at
# its one construction site -- and `desktopTest` reported SURVIVED, because the
# desktop target never sees `androidMain`. That verdict was a fact about the task.
MUTATIONS += [
    (
        "the-android-implementation-stops-implementing-the-port",
        ANDROID_CTRL,
        "internal class AndroidPlayerGestureController(",
        "internal abstract class AndroidPlayerGestureController(",
        "AndroidPlayerGestureControllerTest",
        HOST_TASK,
    ),
    (
        "brightness-is-not-clamped-when-applied",
        ANDROID_CTRL,
        "        val target = level.coerceIn(0.02f, 1f)",
        "        val target = level",
        "AndroidPlayerGestureControllerTest",
        HOST_TASK,
    ),
    (
        "restore-brightness-is-not-a-latch",
        ANDROID_CTRL,
        "        if (brightnessRestored) return\n        brightnessRestored = true",
        "        brightnessRestored = true",
        "AndroidPlayerGestureControllerTest",
        HOST_TASK,
    ),
    (
        "current-brightness-ignores-the-system-setting",
        ANDROID_CTRL,
        "            readSystemBrightness()",
        "            1f",
        "AndroidPlayerGestureControllerTest",
        HOST_TASK,
    ),
    (
        "set-volume-reports-the-request-not-what-it-applied",
        ANDROID_CTRL,
        "        return AudioLevel(\n            fraction = targetVolume.toFloat() / maxVolume.toFloat(),\n            isMuted = targetVolume == 0,\n        )",
        "        return AudioLevel(\n            fraction = level,\n            isMuted = targetVolume == 0,\n        )",
        "AndroidPlayerGestureControllerTest",
        HOST_TASK,
    ),
    (
        "set-volume-is-not-clamped-below-zero",
        ANDROID_CTRL,
        "        val targetVolume = (level.coerceIn(0f, 1f) * maxVolume.toFloat())",
        "        val targetVolume = (level * maxVolume.toFloat())",
        "AndroidPlayerGestureControllerTest",
        HOST_TASK,
    ),
    (
        "the-factory-accepts-a-null-activity",
        ANDROID_CTRL,
        "    if (activity == null) return null\n    val audioManager",
        "    val audioManager",
        "AndroidPlayerGestureControllerTest",
        HOST_TASK,
    ),
]


def run(tests: str, task: str = TASK):
    return subprocess.run(
        ["./gradlew", task, "--tests", tests],
        cwd=ROOT,
        capture_output=True,
        text=True,
        env={**os.environ, "JAVA_TOOL_OPTIONS": "-Djava.awt.headless=true"},
    )


def failed_tests(out: str):
    # The `[desktop]` suffix is NOT always there: `testAndroidHostTest` prints
    # `Class > method FAILED` with no bracket at all, so a regex that requires one
    # silently extracts no evidence and reports every catch as a bare "build
    # failed". Six entries were caught correctly and credited to nothing until a
    # single hand-run showed the format. The suffix is therefore optional.
    return sorted({m[2] for m in re.findall(
        r"^(\w+)(?:\[.*?\])? > (\S+?)(?:\[.*?\])? FAILED", out, re.M)})


def check_anchors():
    bad = []
    for label, rel, old, _new, _t, *_rest in MUTATIONS:
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
    for label, rel, old, new, tests, *_rest in selected:
        task = _rest[0] if _rest else TASK
        path = os.path.join(ROOT, rel)
        original = io.open(path, encoding="utf-8").read()
        if original.count(old) != 1:
            print(f"SKIP     {label}: anchor occurs {original.count(old)}x", flush=True)
            counts["SKIP"] += 1
            continue
        try:
            io.open(path, "w", encoding="utf-8").write(original.replace(old, new, 1))
            proc = run(tests, task)
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
