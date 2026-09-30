#!/usr/bin/env python3
"""Mutation pass for the language-label extraction.

Covers `commonMain/…/playerui/LanguageLabels.kt`: the six keyword arms, the ISO
639-2 to 639-1 lookup, and the three fallback answers (display name, the
equals-tag guard, the blank/throwing arm).

Rules this driver obeys, each learned the hard way in an earlier pass:

- **No `-q`.** It suppresses Gradle's per-test `FAILED` lines, which cost one whole
  landing 29 entries of their *evidence* while leaving the *verdict* intact.
- **`check_anchors()` runs before the first write** and exits without patching if
  anything is ambiguous, so a wrong anchor can never be reported as a survivor.
- **`SKIP … anchor occurs Nx`** is never counted as a pass.
- **Every replacement is read as code before it is written down.** Known
  bad-patch shapes: a default parameter (a legal edit that changes nothing and
  reports a survivor), `?: return null` inside an expression body (cannot
  compile), a `?: ""` arm on an `if/else` expression body (not expressible), a
  repeated declaration prefix, and `x?.y?.z().w()` where a `?.` chain covers
  only the immediately following call.
- **`finally` restore** plus a printed `restored:` line, so a crash cannot leave
  the tree dirty. It is a script, never an import: `main()` runs only under
  `if __name__ == "__main__"`.
"""

import io
import os
import re
import subprocess
import sys

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), ".."))
LABELS = "android/app/src/commonMain/kotlin/com/crispy/tv/playerui/LanguageLabels.kt"

# (label, old, new)
# Entries already resolved by hand or by a new test case, re-run to confirm they are
# now caught or documented rather than assumed to be. Empty = run everything.
RECHECK: set[str] = set()
# Left empty deliberately, and that is the point: the three entries it held were all
# resolved (two by new test cases, one by rewriting the patch) and re-run green. A
# narrowed set left in place would make the next run report "of 3" and read as a
# full pass, so the final tally prints `of len(selected)` and this stays empty.

MUTATIONS = [
    # ---- the blank guard --------------------------------------------------
    (
        "label-does-not-treat-blank-as-unknown",
        "if (code.isNullOrBlank()) return \"Unknown\"",
        "if (code.isNullOrEmpty()) return \"Unknown\"",
    ),
    (
        "label-lets-a-null-code-through",
        "if (code.isNullOrBlank()) return \"Unknown\"",
        "if (code == null) return \"Unknown\"",
    ),
    # ---- the keyword table -----------------------------------------------
    (
        "label-merges-the-forced-and-default-keywords",
        '"forced" -> "Forced"\n        "default" -> "Default"',
        '"forced" -> "Forced"\n        "default" -> "Forced"',
    ),
    (
        "label-loses-the-undetermined-keyword",
        '"und" -> "Undetermined"',
        '"und" -> "Unknown"',
    ),
    (
        "label-checks-the-keyword-case-sensitively",
        "val lower = code.trim().lowercase()",
        "val lower = code.trim()",
    ),
    # Deleting an arm is the only way to make a keyword fall through, because the
    # other five arms are subject-less string comparisons that would not compile
    # inside a `when { }`.
    (
        "label-lets-the-device-keyword-reach-the-iso-lookup",
        '"device" -> "Device language"\n        "original" -> "Original"',
        '"original" -> "Original"',
    ),
    # ---- the ISO lookup ---------------------------------------------------
    (
        "label-does-not-reduce-a-three-letter-code",
        "val two = if (raw.length == 3) ISO639_2_TO_1[lower] ?: raw else raw",
        "val two = raw",
    ),
    (
        "label-falls-back-to-the-key-instead-of-the-code-on-a-table-miss",
        "val two = if (raw.length == 3) ISO639_2_TO_1[lower] ?: raw else raw",
        "val two = if (raw.length == 3) ISO639_2_TO_1[lower] ?: lower else raw",
    ),
    # ---- the three fallback answers ---------------------------------------
    (
        "label-uses-the-display-name-even-when-it-is-the-tag",
        "if (display.isNotBlank() && !display.equals(two, ignoreCase = true)) display else upper",
        "if (display.isNotBlank()) display else upper",
    ),
    (
        "label-uses-the-display-name-even-when-it-is-blank",
        "if (display.isNotBlank() && !display.equals(two, ignoreCase = true)) display else upper",
        "if (!display.equals(two, ignoreCase = true)) display else upper",
    ),
    (
        "label-ignores-case-in-the-equals-tag-guard",
        "!display.equals(two, ignoreCase = true)",
        "!display.equals(two)",
    ),
    (
        # The seventh bad-patch shape: the first draft rewrote `runCatching {` to
        # `run {`, and `run` is not a Kotlin function -- it cannot compile, so the
        # entry reported COMPILE FAILED and proved nothing. The mutation is "there is
        # no third arm", so the whole wrapper goes, not just its name. The
        # throwing-slot case then fails by propagating the exception, which is what
        # makes `runCatching` observable at all.
        "label-has-no-fallback-for-a-throwing-lookup",
        "            runCatching {\n"
        "                val display = englishDisplayName(two)\n"
        "                if (display.isNotBlank() && !display.equals(two, ignoreCase = true)) display else upper\n"
        "            }.getOrDefault(upper)",
        "            englishDisplayName(two)\n"
        "                .let { if (it.isNotBlank() && !it.equals(two, ignoreCase = true)) it else upper }",
    ),
    (
        "label-uppercases-the-wrong-string-on-the-fallback",
        "val upper = raw.uppercase()",
        "val upper = two.uppercase()",
    ),
    # ---- normalizeLang ----------------------------------------------------
    (
        "normalize-does-not-map-blank-to-und",
        'if (raw.isBlank()) return "und"',
        'if (raw.isEmpty()) return "und"',
    ),
    (
        "normalize-does-not-trim",
        "val raw = code?.trim().orEmpty().lowercase()",
        "val raw = code.orEmpty().lowercase()",
    ),
    (
        "normalize-does-not-lowercase",
        "val raw = code?.trim().orEmpty().lowercase()",
        "val raw = code?.trim().orEmpty()",
    ),
    (
        "normalize-does-not-reduce-a-three-letter-code",
        "return if (raw.length == 3) ISO639_2_TO_1[raw] ?: raw else raw",
        "return raw",
    ),
    (
        "normalize-looks-up-every-code",
        "return if (raw.length == 3) ISO639_2_TO_1[raw] ?: raw else raw",
        "return ISO639_2_TO_1[raw] ?: raw",
    ),
    # ---- the table itself -------------------------------------------------
    (
        "table-drops-the-german-variant-of-french",
        '"fre" to "fr",',
        '"fre" to "de",',
    ),
]


def gradle(filt):
    return subprocess.run(
        ["./gradlew", ":android:app:desktopTest", "--tests", filt],
        cwd=ROOT,
        capture_output=True,
        text=True,
        env={**os.environ, "JAVA_TOOL_OPTIONS": "-Djava.awt.headless=true"},
    )


def failed_tests(out):
    return re.findall(r"^(\S+?)\[.*?\] > (\S+?)\[.*?\] FAILED", out, re.M)


def check_anchors():
    original = io.open(os.path.join(ROOT, LABELS), encoding="utf-8").read()
    bad = [
        (label, original.count(old))
        for label, old, _ in MUTATIONS
        if original.count(old) != 1
    ]
    print(f"entries: {len(MUTATIONS)} ambiguous: {len(bad)}")
    for label, n in bad:
        print(f"  AMBIGUOUS {label}: anchor occurs {n}x")
    return original, bad == []


def main():
    if len(sys.argv) > 1 and sys.argv[1] == "list":
        for m in MUTATIONS:
            print(m[0])
        return

    # `scripts/mutate_episode_meta.py` established this pattern: to re-run a handful
    # of entries after resolving them by hand, set RECHECK to those labels. The count
    # at the end then reads "of len(selected)", so a narrowed run cannot be mistaken
    # for a full one. It must stay in `scripts/` -- ROOT is derived from __file__.
    selected = [m for m in MUTATIONS if not RECHECK or m[0] in RECHECK]
    print(f"running {len(selected)} of {len(MUTATIONS)} entries"
          + (" (RECHECK mode)" if RECHECK else ""))

    original, ok = check_anchors()
    if not ok:
        print("exiting without patching: fix the anchors first")
        return

    path = os.path.join(ROOT, LABELS)
    counts = {"CAUGHT": 0, "SKIP": 0, "SURVIVED": 0}
    for label, old, new in selected:
        try:
            io.open(path, "w", encoding="utf-8").write(original.replace(old, new, 1))
            proc = gradle("com.crispy.tv.playerui.LanguageLabelsTest")
            out = proc.stdout + proc.stderr
            if proc.returncode == 0:
                print(f"SURVIVED {label}: compiled and every test still passed")
                counts["SURVIVED"] += 1
            else:
                names = failed_tests(out)
                if names:
                    print(f"CAUGHT   {label}: {len(names)} test(s) -- {', '.join(n for _, n in names[:4])}")
                else:
                    why = "COMPILE FAILED -- not evidence" if re.search(r"^e: ", out, re.M) else "build failed"
                    print(f"CAUGHT   {label}: {why}")
                counts["CAUGHT"] += 1
        finally:
            io.open(path, "w", encoding="utf-8").write(original)
            same = io.open(path, encoding="utf-8").read() == original
            print(f"         restored: {same}")
            if not same:
                counts["SKIP"] += 1

    print()
    print(f"CAUGHT {counts['CAUGHT']}  SKIP {counts['SKIP']}  SURVIVED {counts['SURVIVED']}  of {len(selected)}")


if __name__ == "__main__":
    main()
