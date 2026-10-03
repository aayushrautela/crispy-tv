#!/usr/bin/env python3
r"""Re-measure the two whole-source-set numbers the migration plan's remainder
section quotes, and assert them against the plan.

## Why this exists

`verify_kmp_port.py` reads the plan's census *identity* and the census *table*.
The remainder section further down quotes two more numbers that **no gate reads**
-- the count of files matching a role predicate, and the count importing nothing
but `android.content.Context` -- and both had drifted while every other gate
stayed green: the section said "34 of the 55" where the census table said 32 of
52, and "over the 65 files" where the source set held 51. Two files it names in
prose had left `androidMain` entirely (`ProfileDataCloudSync`, `PictureInPicture-
Config`) and a third had never had the import set attributed to it
(`PersonDetailsRoute`).

**A gate that checks one sentence of a document has not checked the document, and
the part it skips is the part that rots.** So the part it skips is now measured
too.

## What it asserts

Both numbers are read out of `kmp-migration-plan.md` with the pattern below and
compared against a fresh measurement of `:app`'s `androidMain`:

    Measured over the (\d+) files of `androidMain`, \*\*(\d+)\*\* declare

A third finding is the section's *central claim* -- that **no** file imports
`android.content.Context` and nothing else at all -- asserted as zero, so the
sentence the section leads with cannot rot into a number nobody recomputes.

The section's heading deliberately quotes no count. It used to read "The 34's
import sets", which is ambiguous between the census's `android.jar` bucket and
the whole source set the section actually measures; a heading that names one of
two numbers is a claim a reader cannot check, so the gate reports a finding if a
count reappears there rather than trying to guess which of the two was meant.

Exit codes:
    0  the plan agrees with the tree
    1  a finding: the plan states a number the tree does not produce
    2  a measurement failure: a file unreadable, the plan unparseable, or a
       bucket the gate cannot find (deliberately distinct from 1, so a stack
       trace is never read as "the plan is wrong")

Note the second clause of the role predicate is deliberately loose: "declare a
`*Provider`/`*Factory`/`*Graph` type or a function taking a `Context`". It is
quoted from the plan so the two cannot drift apart silently.
"""
import json
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PLAN = os.path.join(ROOT, "kmp-migration-plan.md")
ANDROID_MAIN = "android/app/src/androidMain"

# The role predicate, exactly as the plan states it.
PROVIDERISH = re.compile(r"\b(?:class|object|interface)\s+\w*(?:Provider|Factory|Graph)\b")
FUN_CONTEXT = re.compile(r"\bfun\s+\w*\s*\([^)]*\b(?:Context\b|context\s*:\s*Context\b)", re.S)
IMPORT_RE = re.compile(r"^import\s+([\w.]+)", re.M)


def measurement_failure(msg):
    sys.stderr.write("verify_plan_remainder: MEASUREMENT FAILURE: %s\n" % msg)
    sys.exit(2)


def android_main_files():
    out = subprocess.run(
        ["git", "ls-files", "-co", "--exclude-standard", ANDROID_MAIN],
        cwd=ROOT, capture_output=True, text=True,
    )
    if out.returncode != 0:
        measurement_failure("git ls-files failed: %s" % out.stderr.strip())
    return sorted(f for f in out.stdout.split("\n") if f.endswith(".kt"))


def measure():
    files = android_main_files()
    if not files:
        measurement_failure("no .kt files under %s" % ANDROID_MAIN)
    role, context_only = [], []
    for rel in files:
        text = text_of(os.path.join(ROOT, rel))
        if PROVIDERISH.search(text) or FUN_CONTEXT.search(text):
            role.append(rel)
        imports = sorted({m.group(1) for m in IMPORT_RE.finditer(text)})
        if imports == ["android.content.Context"]:
            context_only.append(rel)
    return {
        "androidMain": len(files),
        "role_predicate": len(role),
        "role_files": role,
        "context_only": len(context_only),
        "context_only_files": context_only,
    }


def text_of(path):
    try:
        with open(path, encoding="utf-8") as f:
            return f.read()
    except OSError as exc:
        measurement_failure("cannot read %s: %s" % (path, exc))


def plan_numbers():
    text = text_of(PLAN)
    # \s+ rather than a literal space, everywhere. A gate whose regex breaks when the
    # sentence it reads is re-wrapped goes dormant and reports a clean tree it never
    # measured -- but this one exits 2 instead, which is the other half of the fix.
    role = re.search(
        r"Measured\s+over\s+the\s+(\d+)\s+files\s+of\s+`androidMain`,\s*\*\*(\d+)\s+declare",
        text,
    )
    if not role:
        measurement_failure(
            "the plan no longer states the role predicate's source-set size and count -- "
            "this gate parses prose it does not own, so it is refusing to report a clean "
            "tree it cannot measure"
        )
    return {
        "plan_source_set": int(role.group(1)),
        "plan_role_predicate": int(role.group(2)),
    }


def main():
    measured = measure()
    stated = plan_numbers()
    findings = []
    if stated["plan_source_set"] != measured["androidMain"]:
        findings.append(
            "plan says the role predicate was measured over %d files, tree has %d"
            % (stated["plan_source_set"], measured["androidMain"])
        )
    if stated["plan_role_predicate"] != measured["role_predicate"]:
        findings.append(
            "plan states %d files match the role predicate, tree has %d"
            % (stated["plan_role_predicate"], measured["role_predicate"])
        )
    # The heading deliberately carries no count any more. It used to read "The 34's
    # import sets", which is ambiguous between the census's android.jar bucket and
    # the whole source set the section actually measures -- and a heading that names
    # one of two numbers is a claim a reader cannot check. There is no check here for
    # a number the gate does not measure; there is a check for not quoting one.
    if re.search(r"#### The \d+'s import sets", text_of(PLAN)):
        findings.append(
            "the import-set heading names a count again; it is ambiguous between the census "
            "bucket and the source set, and this gate measures the source set"
        )
    if measured["context_only"] != 0:
        findings.append(
            "the plan's central claim is that %d files import nothing but "
            "android.content.Context, tree has %d: %s"
            % (0, measured["context_only"], ", ".join(measured["context_only_files"]))
        )

    if "--json" in sys.argv:
        print(json.dumps({"measured": measured, "plan": stated, "findings": findings}, indent=2))
        return 1 if findings else 0

    for line in findings:
        print("FINDING: %s" % line)
    if not findings:
        print(
            "plan agrees with the tree: %d androidMain files, %d match the role predicate, "
            "%d import nothing but Context"
            % (measured["androidMain"], measured["role_predicate"], measured["context_only"])
        )
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
