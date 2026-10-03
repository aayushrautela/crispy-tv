#!/usr/bin/env python3
r"""Refresh every number `kmp-migration-plan.md` states about the port's progress.

This replaces three throwaway scripts that lived in a scratch directory, and it
exists because of a specific failure rather than for tidiness: a landing put TWO
files into `commonMain` and one figure was passed on a command line instead of
being measured, so the plan read 160 while the tree said 161 -- and
`verify_kmp_port.py` stayed green throughout, because it reads the `androidMain`
identity and cannot see a `commonMain` change at all. A stale number survived a
green gate for a whole landing.

**So nothing here is typed. Every figure is derived**, from three sources and no
others:

  * `verify_kmp_port.py --json` for the census buckets, the module inventory and
    the `androidMain` total;
  * `verify_plan_remainder.measure()` for the role-predicate count, imported rather
    than executed as a subprocess because it refuses to run while the plan is
    stale -- which is exactly the state this script exists to fix;
  * `git ls-files` for the repo totals, cross-checked against `find` so the two
    cannot silently disagree.

There are no arguments. The previous version took six numbers on the command line,
which is the defect restated: an argument is a number someone remembered.

USAGE:
    python3 scripts/update_plan_counts.py            # report only, write nothing
    python3 scripts/update_plan_counts.py --write    # apply

The two-phase split is deliberate and is the same reason the gates stay red until
someone acts: `--write` is the only thing that edits the document, and it still
refuses to write unless every figure agrees with every other figure *before* the
write, not after it.
"""
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PLAN = os.path.join(ROOT, "kmp-migration-plan.md")
sys.path.insert(0, os.path.join(ROOT, "scripts"))

# The plan states `:app`'s counts in five places and the gate reads a sixth.
# Each row is (gate bucket prefix, a substring unique to the plan's row for it).
# The prefix resolution is needed because the gate's keys carry a parenthetical
# gloss -- `androidx-core (KMP artifact, Android Host)` -- so an exact-key lookup
# finds nothing.
CENSUS_ROWS = [
    ("android.jar", "`android.jar` proper"),
    ("navigation", "`androidx.navigation`"),
    ("media3", "`androidx.media3`"),
    ("jvm-only-or-resource", "a JVM-only member that needs **no import**"),
    ("activity-compose", "`androidx.activity.compose`"),
    ("app-androidMain-type", "reached with **no import**"),
    ("paging-compose", "`androidx.paging.compose`"),
    ("androidx-core", "`androidx.core`"),
]
ROW_RE = re.compile(r"^\| \*\*(\d+)\*\* \| ")
# The closing `**` is part of the pattern, plus however many stray `.**` a previous
# revision of this script already appended, so the replacement cannot double them.
SUM_RE = re.compile(r"\*\*([0-9]+(?: \+ [0-9]+)*) = ([0-9]+), nothing unmatched(?:\.\*\*)*")


def run(cmd):
    return subprocess.run(cmd, capture_output=True, text=True, check=False, cwd=ROOT)


def git_ls_files_count(source_set):
    """`.kt` files under a source set, from the index.

    `git ls-files` reads the INDEX, which is what makes it the right instrument
    here: a `git mv` is staged before the landing commits, so the tree and the
    index agree on where the file now lives, while `git ls-files` against `HEAD`
    would report the pre-move location.

    **The pathspec is the grep form, not a git glob, and the difference is not
    cosmetic**: `git ls-files android '*/src/commonMain'` returned **583** where
    the answer is 274, because in a git pathspec `*` **crosses `/`**, so the
    pattern matches every path with that suffix anywhere -- not only at one
    level. This is the same trap already recorded for
    `git grep -l 'import okhttp3' -- '*/src/commonMain'`, where the unrunnable
    command was also the useless one. The cross-check against `find` is what
    caught it: a wrong instrument that returns a plausible number is exactly what
    the check is for, and it is why the check is not optional here.
    """
    out = run(["git", "ls-files", "android"]).stdout
    return sum(1 for line in out.splitlines()
               if line.endswith(".kt") and f"/src/{source_set}/" in line)


def find_count(source_set):
    """The same count from the working tree, for the cross-check.

    `-path` rather than `in`: `os.walk` gives a path with the platform separator,
    so matching on `f"/src/{source_set}"` silently answers zero on Windows and is
    easy to get subtly wrong elsewhere.
    """
    return sum(1 for _d, _dn, fns in os.walk(os.path.join(ROOT, "android"))
               for f in fns
               if f.endswith(".kt") and os.sep + f"src{os.sep}{source_set}{os.sep}" in _d)


def measure():
    raw = json.loads(run([sys.executable, "scripts/verify_kmp_port.py", "--json"]).stdout)
    import verify_plan_remainder as remainder

    rem = remainder.measure()

    def bucket(prefix):
        hits = [k for k in raw["buckets"] if k == prefix or k.startswith(prefix)]
        if len(hits) != 1:
            sys.exit(f"gate bucket {prefix!r} resolved to {len(hits)} keys")
        value = raw["buckets"][hits[0]]
        return len(value) if isinstance(value, list) else int(value)

    counts = [bucket(p) for p, _ in CENSUS_ROWS]
    app = raw["inventory"]["android/app"]

    # The repo total is summed from the index, not derived as a delta from the
    # plan's own stated total. A delta inherits whatever the plan already claimed,
    # which is how a wrong total survives.
    totals = {}
    for source_set in ("commonMain", "androidMain"):
        index = git_ls_files_count(source_set)
        tree = find_count(source_set)
        if index != tree:
            sys.exit(f"index says {source_set}={index}, find says {tree}")
        totals[source_set] = index

    return {
        "raw": raw,
        "app": app,
        "rem": rem,
        "counts": counts,
        "census_total": sum(counts),
        "totals": totals,
    }


def build_edits(text, m):
    """(old, new) pairs, each anchored on text parsed out of the document.

    Anchors built from the numbers already in the file are true by construction.
    An anchor typed from memory is a claim about the document, and the three
    predecessors of this script each shipped one.
    """
    app = m["app"]
    cm, am, ct = app["commonMain"], app["androidMain"], app["commonTest"]
    common_sum = cm + am
    pct = round(100 * cm / common_sum)
    role = m["rem"]["role_predicate"]
    edits = []

    def add(old, new):
        edits.append((old, new))

    # --- the five `:app` count sites -------------------------------------------
    # 1. the Phase-4 row, whose parenthetical is a second claim about the same
    #    number and has to move with it
    m1 = re.search(
        r"\*\*(\d+) of (\d+) `:app` files in `commonMain`\*\* "
        r"\(`find` and `git ls-files` agree on (\d+)\)", text)
    if m1:
        add(m1.group(0),
            "**%d of %d `:app` files in `commonMain`** "
            "(`find` and `git ls-files` agree on %d)" % (cm, common_sum, cm))
    else:
        sys.exit("PHASE 1 FAILED: the Phase-4 row no longer states the counts")

    # 2. the three-column module table
    m2 = re.search(r"\| \*\*`:app`\*\* \| \*\*(\d+)\*\* \| \*\*(\d+)\*\* \| \*\*(\d+)\*\* \|", text)
    if m2:
        add(m2.group(0), "| **`:app`** | **%d** | **%d** | **%d** |" % (cm, am, ct))
    else:
        sys.exit("PHASE 1 FAILED: the three-column module table no longer has an :app row")

    # 3 + 4. the two-column module table AND the total row, as ONE edit.
    #
    # These were two edits in the previous version and they collided: the `:app` row
    # `| **164** | **48** |` is a *substring* of the three-column row
    # `| **164** | **48** | **74** |`, so it occurs twice in the document and phase 1
    # correctly refused it. Anchoring on the two-line block -- the `:app` row followed
    # by the `total` row -- is unique, and rewriting both numbers in one replacement
    # is what makes it correct rather than merely legal. **A phase-1 failure naming an
    # anchor that occurs twice is describing an edit list that should have been one
    # edit** -- the two rules are the same rule, and the collision is the message.
    m3 = re.search(
        r"\| \*\*`:app`\*\* \| \*\*\d+\*\* \| \*\*\d+\*\* \|\n"
        r"\| \*\*total\*\* \| \*\*\d+\*\* \| \*\*\d+\*\* \|", text)
    if not m3:
        sys.exit("PHASE 1 FAILED: the two-column module table has no :app row above a total row")
    add(m3.group(0),
        "| **`:app`** | **%d** | **%d** |\n"
        "| **total** | **%d** | **%d** |"
        % (cm, am, m["totals"]["commonMain"], m["totals"]["androidMain"]))

    # 5. the ratio sentence
    m4 = re.search(r"`:app` is \d+ of \d+, i\.e\. \*\*\d+%\*\*", text)
    if m4:
        add(m4.group(0), "`:app` is %d of %d, i.e. **%d%%**" % (cm, common_sum, pct))
    else:
        sys.exit("PHASE 1 FAILED: the ratio sentence is gone")

    # --- the census identity the gate parses ------------------------------------
    # Asserted against NEW rather than OLD. A guard that asks "was this already
    # right for the number I was given" cannot answer "does this need changing",
    # and it sat wrong for two consecutive landings.
    # The identity, rewritten by *substitution on the matched text* rather than by
    # rebuilding it. The sentence is wrapped across two lines in the markdown, so a
    # fixed replacement collapses the wrap and reflows the document on every run --
    # which is diff noise that looks like a correction, and the first version of this
    # script did exactly that. `\s+` in the pattern spans the newline, so the gap
    # between the two figures is whatever the author chose; replacing the digits and
    # nothing else is the only form that cannot reflow it.
    m6 = re.search(r"(tracked file\s+is\s+classified\s*\(`)(\d+)(\s*==\s*)(\d+)(`\s*\))", text)
    if m6:
        add(m6.group(0), "%s%d%s%d%s" % (m6.group(1), am, m6.group(3), am, m6.group(5)))
    else:
        sys.exit("PHASE 1 FAILED: verify_kmp_port.py can no longer parse the identity")

    # --- the remainder count, the heading, and the cross-reference -------------
    # Three sites for one number, and the gate reads none of them.
    m7 = re.search(r"the \d+ that remain are behind the walls", text)
    if m7:
        add(m7.group(0), "the %d that remain are behind the walls" % am)
    for pattern, rebuild in (
        (r"### The \d+ that remain", lambda: "### The %d that remain" % am),
        (r'See \*\*"The \d+ that remain"\*\*', lambda: 'See **"The %d that remain"**' % am),
    ):
        hit = re.search(pattern, text)
        if hit:
            add(hit.group(0), rebuild())

    # --- the census table's eight rows and its sum line ------------------------
    lines = text.split("\n")
    rows = [(i, int(ROW_RE.match(l).group(1)), l)
            for i, l in enumerate(lines) if ROW_RE.match(l)]
    if len(rows) != len(CENSUS_ROWS):
        sys.exit("PHASE 1 FAILED: found %d census rows, expected %d -- the table's shape moved"
                 % (len(rows), len(CENSUS_ROWS)))
    for (prefix, marker), (_i, old_count, line) in zip(CENSUS_ROWS, rows):
        if marker not in line:
            sys.exit("PHASE 1 FAILED: the row for %r lacks its marker %r" % (prefix, marker))
        new_count = m["counts"][CENSUS_ROWS.index((prefix, marker))]
        if old_count != new_count:
            add(line, ROW_RE.sub("| **%d** | " % new_count, line, count=1))
    sum_idx = next((i for i, l in enumerate(lines) if "nothing unmatched" in l), None)
    if sum_idx is None:
        sys.exit("PHASE 1 FAILED: no line carries 'nothing unmatched'")
    old_sum_line = lines[sum_idx]
    total = sum(m["counts"])
    new_sum_line = SUM_RE.sub("**%s = %d, nothing unmatched.**"
                              % (" + ".join(str(c) for c in m["counts"]), total),
                              old_sum_line, count=1)
    if new_sum_line == old_sum_line:
        print("NOTE: the census table already agrees with the gate; leaving it alone")
    else:
        if ".**.**" in new_sum_line:
            sys.exit("GUARD FAILED: the rewritten sum line doubles its closing")
        add(old_sum_line, new_sum_line)

    # --- the three role-predicate numbers --------------------------------------
    # Only one is read by a gate, and the two it cannot see are the two that rot.
    m8 = re.search(r"Measured\s+over\s+the\s+(\d+)\s+files\s+of\s+`androidMain`,\s*\*\*(\d+)\s+declare", text)
    m9 = re.search(r"the other (\d+) are files that reach the platform", text)
    m10 = re.search(r"\*\*The (\d+) does not partition the (\d+) either\*\*", text)
    if not (m8 and m9 and m10):
        sys.exit("PHASE 1 FAILED: the plan no longer states all three role numbers")
    old_size, old_role = int(m8.group(1)), int(m8.group(2))
    # Refuse to write if the document is already internally inconsistent: a
    # complement that does not close means someone edited a number by hand.
    if int(m9.group(1)) != old_size - old_role:
        sys.exit("PHASE 1 FAILED: the complement does not close (%d vs %d - %d)"
                 % (int(m9.group(1)), old_size, old_role))
    if (int(m10.group(1)), int(m10.group(2))) != (old_role, old_size):
        sys.exit("PHASE 1 FAILED: the partition sentence disagrees with the role sentence")
    add(m8.group(0), "Measured over the %d files of `androidMain`, **%d declare" % (am, role))
    add(m9.group(0), "the other %d are files that reach the platform" % (am - role))
    add(m10.group(0), "**The %d does not partition the %d either**" % (role, am))

    return edits


def main():
    write = "--write" in sys.argv[1:]
    m = measure()
    app, rem, raw = m["app"], m["rem"], m["raw"]

    print("measured from the tree and the gates:")
    print("  :app          commonMain=%d androidMain=%d commonTest=%d androidHostTest=%d"
          % (app["commonMain"], app["androidMain"], app["commonTest"], app["androidHostTest"]))
    print("  repo totals   commonMain=%d androidMain=%d  (git ls-files, cross-checked with find)"
          % (m["totals"]["commonMain"], m["totals"]["androidMain"]))
    print("  census        %s = %d   (gate says androidMain=%d, plan states %s)"
          % (" + ".join(str(c) for c in m["counts"]), m["census_total"],
             raw["androidMain"], raw["plan_stated"]))
    print("  role          role_predicate=%d of androidMain=%d, context_only=%d"
          % (rem["role_predicate"], rem["androidMain"], rem["context_only"]))

    # The two gates must already agree with the tree, or this script would be
    # papering over a disagreement rather than reporting it.
    if raw["androidMain"] != app["androidMain"]:
        sys.exit("the gate says androidMain=%d, its own inventory says %d"
                 % (raw["androidMain"], app["androidMain"]))
    if m["census_total"] != app["androidMain"]:
        sys.exit("the census rows sum to %d but androidMain is %d"
                 % (m["census_total"], app["androidMain"]))
    if rem["androidMain"] != app["androidMain"]:
        sys.exit("verify_plan_remainder measures androidMain=%d, verify_kmp_port says %d"
                 % (rem["androidMain"], app["androidMain"]))

    with open(PLAN, encoding="utf-8") as f:
        original = f.read()
    edits = build_edits(original, m)

    # --- phase 1: every anchor unique in the ORIGINAL, writing nothing ----------
    for i, (old, _new) in enumerate(edits):
        n = original.count(old)
        if n != 1:
            sys.exit("PHASE 1 FAILED at edit %d: anchor occurs %dx: %r" % (i, n, old[:70]))
    print("phase 1 ok: %d anchors, each unique in the original" % len(edits))

    # --- phase 2: apply cumulatively, re-asserting at each step ----------------
    text = original
    for i, (old, new) in enumerate(edits):
        if text.count(old) != 1:
            sys.exit("PHASE 2 FAILED at edit %d: anchor no longer unique: %r" % (i, old[:70]))
        # An edit whose replacement contains its own anchor is legitimate, so
        # "the old must be gone" is asserted only where the replacement really
        # removed it. Making that a function of the pair means adding an edit
        # cannot get it wrong.
        if old not in new and old in text.replace(old, "", 1):
            sys.exit("PHASE 2 FAILED at edit %d: the anchor survived its own replacement" % i)
        text = text.replace(old, new, 1)
    print("phase 2 ok: applied cumulatively")

    # --- guards: everything must agree, BEFORE the write ----------------------
    # The identity the gate parses must still parse and must be self-consistent.
    idm = re.search(r"tracked file\s+is\s+classified\s*\(`(\d+)\s*==\s*(\d+)`\s*\)", text)
    if not idm:
        sys.exit("GUARD FAILED: the gate can no longer parse the census identity")
    if idm.group(1) != idm.group(2):
        sys.exit("GUARD FAILED: the identity is not self-consistent: %s" % idm.group(0))
    # The census rows must match the sum line's terms and the sum must close.
    row_counts = [int(ROW_RE.match(l).group(1))
                  for l in text.split("\n") if ROW_RE.match(l)]
    sm = SUM_RE.search(text)
    if not sm:
        sys.exit("GUARD FAILED: the sum line's own regex does not match the rewritten text")
    if row_counts != [int(t) for t in sm.group(1).split(" + ")]:
        sys.exit("GUARD FAILED: rows %s do not match the line's terms %s"
                 % (row_counts, sm.group(1)))
    # Three-way agreement, asserted as ONE condition rather than as two chained
    # comparisons. `if a != b != c` is `a != b and b != c`, which is true whenever
    # the middle disagrees with *either* side -- so it would have passed a sum line
    # whose stated total matched the gate but not its own rows. **A chained comparison
    # in a guard is two questions wearing one coat**, the same shape as `assertNull`
    # on a boolean.
    if not (sum(row_counts) == int(sm.group(2)) == app["androidMain"]):
        sys.exit("GUARD FAILED: rows sum to %d, the line claims %s, the tree has %d"
                 % (sum(row_counts), sm.group(2), app["androidMain"]))
    # The role sentence must read what the tree says, and its complement must close.
    rm = re.search(r"Measured\s+over\s+the\s+(\d+)\s+files\s+of\s+`androidMain`,\s*\*\*(\d+)\s+declare", text)
    cm2 = re.search(r"the other (\d+) are files that reach the platform", text)
    if not (rm and cm2):
        sys.exit("GUARD FAILED: the role sentence no longer parses the way the gate reads it")
    if int(rm.group(1)) != app["androidMain"] or int(rm.group(2)) != rem["role_predicate"]:
        sys.exit("GUARD FAILED: the role sentence reads %s/%s, expected %d/%d"
                 % (rm.group(1), rm.group(2), app["androidMain"], rem["role_predicate"]))
    if int(cm2.group(1)) != int(rm.group(1)) - int(rm.group(2)):
        sys.exit("GUARD FAILED: the complement does not close after the write")
    print("guards ok: identity %s==%s, census rows %s sum to %d, role %s/%s"
          % (idm.group(1), idm.group(2), row_counts, sum(row_counts),
             rm.group(2), rm.group(1)))

    if not write:
        print("\nnothing written (pass --write to apply). %d of %d anchors need no change."
              % (sum(1 for o, n in edits if o == n), len(edits)))
        return 0

    # --- phase 3: write once ----------------------------------------------------
    with open(PLAN, "w", encoding="utf-8") as f:
        f.write(text)
    with open(PLAN, encoding="utf-8") as f:
        back = f.read()
    for old, new in edits:
        if old == new:
            continue
        if back.count(new) != 1:
            sys.exit("READ-BACK FAILED: %r occurs %dx" % (new[:60], back.count(new)))
        # Only assert the old is gone when the replacement genuinely removed it.
        if old not in new and old in back:
            sys.exit("READ-BACK FAILED: %r survived" % old[:60])
    print("wrote %s (%d -> %d bytes)" % (PLAN, len(original), len(text)))
    print("read-back ok")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())