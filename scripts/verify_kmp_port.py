#!/usr/bin/env python3
"""Measure `:app`'s source-set inventory and assert the migration plan agrees.

Why this exists: `kmp-migration-plan.md` carried a hand-built partition of the
files still in `:app`'s `androidMain`, and it drifted from its own prose twice
-- once because a row was never updated when its bucket lost a member, and once
because two different readings of "what counts as a pin" filled the same table.
**A partition a human maintains by hand cannot be trusted to still sum, and a
table that does not sum is worse than no table, because it looks like a
measurement.**

So this file gates exactly the one thing that is *exactly* measurable -- the
file counts, in the tree and in the plan -- and it **reports** a pin summary
built only from signals that cannot be wrong in the way a type resolver is.

**It deliberately does not resolve a type name to its declaring module.**  That
approach was built and thrown away, and the reasons are worth keeping, because
the failure is silent rather than loud:

  * a name is ambiguous -- `CatalogItem` is declared once, in `:home`'s
    `commonMain`, and a first-cut resolver still filed files under `:tv`;
  * a naive declaration regex captures an **extension function's receiver** as
    the declared name, so `fun Modifier.crispyTheme()` declares `Modifier` and
    `val Int.dp` declares `Int`.  The report then read `Int declared in
    android/tv` for a declaration that does not exist anywhere;
  * a file names its own declarations, so a file that declares `AppGraph` was
    reported as pinned *by* `AppGraph`.

Each of those was a plausible, confident, wrong line in the output, and a gate
that emits them gets switched off -- the one outcome worse than having no gate.
Resolving a name to a declaration needs overload resolution, star imports,
same-package precedence and the module graph, and a partial version of it is
not a weaker measurement, it is a different and wrong one.

**What is left is the pin summary, and it is a lower bound, deliberately.**  It
counts only unambiguous signals: an import *coordinate* of a third-party
Android package, an `R.<type>` reference, a JVM-only member that needs no
import, and a name declared in `:app`'s own `androidMain` by *another* file.
The last one is a real pin -- the type is unreachable from a `commonMain` until
it is lifted or slot-thread -- and it is decidable without guessing, because a
name declared in exactly one file of the module cannot be a collision.  Every
bucket is asserted non-empty before the sum is asserted, and a bucket allowed
to be empty has to name the landing that emptied it.

Run:  python3 scripts/verify_kmp_port.py [--json]
Exit: 0 clean, 1 on a finding, 2 on a measurement problem.
"""

from __future__ import annotations

import functools
import json
import os
import re
import subprocess
import sys
from collections import defaultdict

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PLAN = os.path.join(REPO, "kmp-migration-plan.md")
APP = "android/app"
MODULES = (
    "commonMain", "androidMain", "commonTest", "androidHostTest", "test",
    "androidUnitTest", "androidInstrumentedTest", "main",
)

# ---------------------------------------------------------------------------
# Pin signals, in first-match order.  Order is part of the rule: a first-match
# partition evaluates the later rules only for the files the first one caught, so
# a bucket is a FLOOR rather than a description, and a two-pin file is
# invisible in its second pin.  `PlayerSessionDecisions.kt` and
# `PersonDetailsRoute.kt` have each been two-pin files in earlier analyses of
# this tree, and the only way to see the second is to read the file.

# Third-party.  For someone else's artifact the coordinate IS the identity, so
# unlike our own types these are read from imports legitimately -- and this is
# the distinction the discarded resolver got backwards in the other direction.
PIN_EXTERNAL = (
    ("androidx.navigation", "navigation"),
    ("androidx.media3", "media3"),
    ("androidx.media.", "media3"),
    ("androidx.paging.runtime", "paging-runtime"),
    ("androidx.window.", "androidx-window"),
    ("androidx.paging.compose", "paging-compose (KMP; runtime is not)"),
    ("androidx.activity.compose", "activity-compose (KMP artifact, Android Host)"),
    ("androidx.core.", "androidx-core (KMP artifact, Android Host)"),
    ("android.", "android.jar"),
)

# JVM-only members that need no import, so no import scan can find them.
# `kotlin.text.String.format` is here because it made a 442-line file pass a
# careful audit as clean and then fail the metadata compile with two
# `Unresolved reference 'format'` errors.  A pin can arrive through a member
# call on a value, and "the import list is clean" is not a measurement.
PIN_NO_IMPORT = (
    (re.compile(r'"[^"]*%[0-9]*[a-zA-Z][^"]*"\s*\.\s*format\s*\('), "String.format"),
    (re.compile(r'\bjava\.util\.Locale|Locale\.(ENGLISH|US|ROOT)\b'), "java.util.Locale"),
    (re.compile(r'\bSystem\.currentTimeMillis\s*\('), "System.currentTimeMillis"),
    (re.compile(r'(?<![\w.])synchronized\s*\('), "synchronized"),
    (re.compile(r'\bjava\.util\.UUID\b'), "java.util.UUID"),
    (re.compile(r'\bjava\.io\.File\b'), "java.io.File"),
    # `R.<lowercase>` rather than an enumeration of resource types.  The first
    # version listed drawable|layout|string|id|color|dim|style|array and it
    # reported `no-pin-found` for the two provider-badge files -- which are
    # pinned twice over, on `R.raw.*` SVGs, and which this script is supposed to
    # be the one thing that never gets wrong.  **An enumeration of cases is a
    # list of what someone remembered, and a shape is a rule.**  `R.` followed
    # by a lowercase identifier is always a resource; Kotlin has no such form.
    (re.compile(r'\bR\s*\.\s*[a-z][A-Za-z0-9_]*'), "an R.<type> reference"),
    (re.compile(r'@[A-Za-z]*Volatile\b'), "Volatile via kotlin.jvm's default import"),
    (re.compile(r'@[A-Za-z]*Synchronized\b'), "Synchronized via kotlin.jvm's default import"),
)

_MODIFIERS = (
    "public", "internal", "private", "protected", "expect", "actual", "open",
    "abstract", "final", "sealed", "data", "value", "annotation", "enum",
    "inner", "companion", "tailrec", "inline", "infix", "operator", "suspend",
    "const",
)


@functools.lru_cache(maxsize=1)
def _decl() -> re.Pattern:
    """A declaration line.  Anchored on the declaration form AND required not to
    be followed by a `.` -- that is what excludes an extension receiver, which
    was the bug that made the discarded resolver report `Int declared in
    android/tv` for a declaration that exists nowhere in the tree.

    Compiled lazily rather than at module scope on purpose.  **A module-level
    exception is not caught by the `if __name__` handler around `main()`**, so
    the first version of this regex -- `[ \\t]+` immediately followed by `*`, a
    `multiple repeat` error -- exited 1, which is this script's *finding* code.  A
    stack trace read as a verdict, which is the same defect the exit-2 split
    exists for, arriving through a door the split did not cover.
    """
    return re.compile(
        r'^[ \t]*(?:@\w+(?:\([^)]*\))?[ \t]+)*'
        rf'(?:(?:{"|".join(_MODIFIERS)})[ \t]+)*'
        r'(?:class|interface|object|fun|val|var|typealias)[ \t]+'
        r'([A-Za-z_][A-Za-z0-9_]*)'
        r'(?![ \t]*[.(=<])',        # not a receiver, not a property with a type
        re.M,
    )


@functools.lru_cache(maxsize=1)
def _use() -> re.Pattern:
    return re.compile(r'\b([A-Z][A-Za-z0-9_]{2,})\b')


def git(*args: str) -> str:
    return subprocess.run(
        ["git", *args], cwd=REPO, check=True, capture_output=True, text=True
    ).stdout


def tracked(*pats: str) -> list[str]:
    """`git ls-files -co --exclude-standard` -- tracked OR untracked-not-ignored.

    Never `find`: `find` and `git ls-files` overlap and summing both double
    counts.  That produced a "131 files" reading where HEAD and the plan both
    said 129 -- the discrepancy was in the tool, not in the tree.
    """
    return [f for f in git("ls-files", "-co", "--exclude-standard", *pats).splitlines()
            if f.endswith(".kt")]


def strip_comments(text: str) -> str:
    """Remove // and /* */ comments without eating `//` inside a string literal.

    A regex cannot do this: `maven(url = "https://...")` and a trailing
    `// note` after a dependency both end in `//`, and one of them is code.  A
    character scanner, because the file that documents this rule is itself
    parsed by this function.
    """
    out = []
    i, n = 0, len(text)
    in_str = in_line = in_block = False
    quote = ""
    while i < n:
        c = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if in_line:
            if c == "\n":
                in_line = False
                out.append(c)
            i += 1
            continue
        if in_block:
            if c == "*" and nxt == "/":
                in_block = False
                i += 2
                continue
            if c == "\n":
                out.append(c)
            i += 1
            continue
        if in_str:
            out.append(c)
            if c == "\\":
                out.append(text[i + 1])
                i += 2
                continue
            if c == quote:
                in_str = False
            i += 1
            continue
        if c == "/" and nxt == "/":
            in_line = True
            i += 2
            continue
        if c == "/" and nxt == "*":
            in_block = True
            i += 2
            continue
        if c in "\"'":
            in_str, quote = True, c
            out.append(c)
            i += 1
            continue
        out.append(c)
        i += 1
    return "".join(out)


def read(path: str) -> str:
    try:
        return strip_comments(open(os.path.join(REPO, path), encoding="utf-8").read())
    except (UnicodeDecodeError, OSError) as e:  # a binary asset under src/
        raise ValueError(f"cannot read {path}: {e}")


def own_android_main_declarations() -> dict[str, str]:
    """name -> file, for top-level declarations in `:app`'s `androidMain`.

    Only names declared in **exactly one** file are returned.  A name declared
    twice is ambiguous, and an ambiguous name reported as a pin is the exact
    false positive that made the discarded resolver untrustworthy.  Nothing
    stops a two-pin file hiding behind a shared name; a bucket is a floor.
    """
    hits: dict[str, set[str]] = defaultdict(set)
    for p in tracked(f"{APP}/src/androidMain"):
        for name in _decl().findall(read(p)):
            hits[name].add(p)
    return {n: next(iter(fs)) for n, fs in hits.items() if len(fs) == 1}


def counts() -> dict[str, dict[str, int]]:
    out: dict[str, dict[str, int]] = {}
    for mod in sorted({f.rsplit("/src/", 1)[0] for f in git("ls-files").splitlines()
                       if "/src/" in f}):
        d = {ss: len(tracked(f"{mod}/src/{ss}")) for ss in MODULES}
        d = {k: v for k, v in d.items() if v}
        if d:
            out[mod] = d
    return out


def classify(path: str, own: dict[str, str]) -> tuple[str, str]:
    """Return (bucket, detail).  First match wins -- see PIN_EXTERNAL."""
    body = read(path)
    imports = set(re.findall(r'^[ \t]*import[ \t]+([\w.]+)', body, re.M))
    # A file's own declarations are not pins on itself.  That is not a detail:
    # without the exclusion every declaration site reported itself.
    declared_here = set(_decl().findall(body))

    for prefix, bucket in PIN_EXTERNAL:
        # `rstrip(".")` is load-bearing, not tidiness.  Half these prefixes end in a
        # dot and half do not, so the obvious `imp.startswith(prefix + ".")`
        # silently tests `"android.."` for the `android.` entry and can never match.
        # The result was a reported `android.jar: 0` -- a bucket that asserts a
        # negative, produced by a rule that had never fired.  **A bucket that
        # reads zero is the measurement asking to be believed, and this is what
        # asking looks like when the rule is half-written instead of wrong.**
        stem = prefix.rstrip(".")
        for imp in imports:
            if imp == stem or imp.startswith(stem + "."):
                return bucket, imp
    for rx, why in PIN_NO_IMPORT:
        if rx.search(body):
            return "jvm-only-or-resource", why
    for name in sorted(_use().findall(body)):
        owner = own.get(name)
        if owner and owner != path and name not in declared_here:
            return "app-androidMain-type", f"{name} (declared in {os.path.basename(owner)})"
    return "no-pin-found", ""


# A bucket that reaches zero must name the landing that emptied it, because a
# shrinking bucket and a bucket that was always empty look identical in a diff
# and one of them means a measurement stopped running.  Add a key here only when
# a landing genuinely emptied a bucket, and say which.
# A bucket that reaches zero must be accounted for, and the two reasons are
# DIFFERENT CLAIMS that must not share one list:
#
#   EMPTY_ALLOWED  -- a landing emptied it.  A bucket that shrank and a bucket
#                     that was always empty look identical in a diff, so the
#                     cause has to be written down next to the zero.
#   NEVER_MATCHED  -- no `:app/androidMain` file has ever matched this rule.
#                     Kept as a forward-looking rule, not deleted, because the
#                     next landing that imports `androidx.paging.runtime` should
#                     not have to re-derive the prefix.  Naming a landing here
#                     would be a lie, which is why the two lists are separate.
#
# A bucket in neither list, and empty, is a finding.
EMPTY_ALLOWED: dict[str, str] = {
    # `no-pin-found` is the residual: a file that reaches it has been shown to
    # name no pinned type at all.  It is empty now -- every one of the 61 files
    # carries at least one pin -- because the `R.<lowercase>` rule below started
    # matching `R.raw.*` and moved the two provider-badge files out of it.
    "no-pin-found": "emptied by the `R.<lowercase>` rule, which began matching "
                    "`R.raw.*`; every :app/androidMain file now carries a pin",
}

NEVER_MATCHED: set[str] = {
    "androidx-window": "no :app/androidMain file imports androidx.window today",
    "paging-runtime": "no :app/androidMain file imports androidx.paging.runtime "
                      "today; :app uses paging-compose only",
}


def main() -> int:
    argv = sys.argv[1:]
    as_json = "--json" in argv
    if [a for a in argv if a != "--json"]:
        print("unknown argument; only --json is accepted", file=sys.stderr)
        return 2

    inv = counts()
    if not inv:
        print("source-set inventory is empty; the measurement did not run",
              file=sys.stderr)
        return 2
    app = inv.get(APP)
    if not app or not app.get("commonMain"):
        print(f"no {APP} entry with a commonMain in the inventory; refusing to "
              "report a missing measurement as a clean one", file=sys.stderr)
        return 2

    own = own_android_main_declarations()
    targets = sorted(tracked(f"{APP}/src/androidMain"))
    if not targets:
        print("no :app/androidMain files -- is the migration finished? an empty "
              "set cannot be partitioned", file=sys.stderr)
        return 2

    # Every DECLARED bucket is pre-seeded, and that is load-bearing rather than
    # cosmetic.  A `defaultdict(list)` only gains a key when something is
    # appended to it, so a bucket that no file matches into **does not exist in
    # the dict at all** -- and the proof harness caught exactly that: forcing
    # every rule to stop matching produced a silent exit 0, because the
    # empty-bucket check was iterating a dict that could not contain the thing
    # it was looking for.  **A safeguard that cannot fire is not a safeguard,
    # and it is invisible in review precisely because it looks like one.**
    # Deriving the universe from the rule tables also means a rule added without
    # a bucket name shows up here rather than as a silent grouping.
    declared_buckets = {b for _p, b in PIN_EXTERNAL}
    declared_buckets |= {"jvm-only-or-resource", "app-androidMain-type", "no-pin-found"}
    declared_buckets |= set(EMPTY_ALLOWED) | set(NEVER_MATCHED)
    buckets: dict[str, list[tuple[str, str]]] = {b: [] for b in declared_buckets}

    for p in targets:
        b, detail = classify(p, own)
        buckets.setdefault(b, []).append((os.path.basename(p), detail))

    total = sum(len(v) for v in buckets.values())
    if total != len(targets):
        print(f"partition lost files: {total} classified, {len(targets)} read",
              file=sys.stderr)
        return 2

    findings = []
    for b, files in sorted(buckets.items()):
        if not files and b not in EMPTY_ALLOWED and b not in NEVER_MATCHED:
            findings.append(
                f"bucket {b!r} is empty and no landing is recorded as having "
                f"emptied it -- in a first-match partition an empty bucket is a "
                f"claim the measurement must TEST, not a label it may print")
    # An allowlist entry may name a bucket that is absent OR empty, because
    # "a landing emptied it" and "it was never there" are the same state to this
    # script and it cannot tell them apart.  A typo therefore passes silently,
    # which is why every entry is printed with its state: the allowlist is
    # auditable by reading the report rather than by the exit code.  Making it
    # fatal instead would mean failing forever on the entry that records the
    # landing that removed the bucket, which is the opposite of useful.
    empty_state = {
        b: ("absent" if b not in buckets else "empty")
        for b in (*EMPTY_ALLOWED, *NEVER_MATCHED)
        if not buckets.get(b)
    }

    # The plan's stated census size, so the hand-maintained table cannot drift.
    plan = open(PLAN, encoding="utf-8").read() if os.path.exists(PLAN) else ""
    # `\s+` where prose would line-wrap, never a literal space.  The first
    # version required "tracked file is classified" CONTIGUOUSLY, and a re-wrap of
    # that sentence in the plan -- one newline, no content change -- silently
    # stopped the match: `stated` became None, the `if m:` guard skipped the whole
    # comparison, and the gate printed its clean summary and exited 0 while
    # checking nothing at all.  **A gate that goes dormant on a whitespace change
    # and still reports green is worse than no gate, because it is believed.**
    stated = None
    m = re.search(r"tracked file\s+is\s+classified\s*\(`(\d+)\s*==\s*\1`\s*\)", plan)
    if m:
        stated = int(m.group(1))
        if stated != len(targets):
            findings.append(
                f"plan states {stated} `:app/androidMain` files, tree has "
                f"{len(targets)}; kmp-migration-plan.md is refreshed in the same "
                f"commit as any landing that moves a file")
    else:
        # A missing anchor is a MEASUREMENT failure, not a pass.  The old code
        # made it a pass by construction, which is precisely how the assertion
        # above could be deleted by a line-wrap and leave the gate green.
        print("could not read the census size out of kmp-migration-plan.md. This "
              "gate parses a sentence it does not own, so editing that sentence "
              "silently disables the check; it must FAIL rather than report a "
              "clean tree it never compared.", file=sys.stderr)
        return 2

    if as_json:
        print(json.dumps({
            "inventory": inv,
            "androidMain": len(targets),
            "counts": {b: len(v) for b, v in sorted(buckets.items())},
            "buckets": {b: sorted(n for n, _ in v) for b, v in sorted(buckets.items())},
            "plan_stated": stated,
            "empty_allowed": EMPTY_ALLOWED,
            "never_matched": sorted(NEVER_MATCHED),
            "empty_state": empty_state,
            "findings": findings,
        }, indent=2, sort_keys=True))
        return 1 if findings else 0

    print(f":app  {app.get('commonMain', 0)} commonMain / {len(targets)} androidMain")
    print("\n  pin summary (a LOWER BOUND -- see the module docstring):")
    for b, v in sorted(buckets.items(), key=lambda kv: (-len(kv[1]), kv[0])):
        # `.get`, not `[...]`: this line runs for EVERY empty bucket, and an
        # empty bucket with no landing recorded is exactly the state the gate
        # exists to catch -- so indexing raised KeyError on the finding it was
        # built to report.  A reporter that crashes on the condition it is
        # reporting is a reporter that has never seen its own output.
        if not v:
            why = (EMPTY_ALLOWED.get(b)
                   or NEVER_MATCHED.get(b)
                   or "NO LANDING RECORDED -- this is a finding")
            print(f"    {b}: 0   (empty: {why})")
        else:
            print(f"    {b}: {len(v)}")
    for b, v in sorted(buckets.items()):
        for n, d in v:
            print(f"        {n}" + (f"   -- {d}" if d else ""))
    for label, table in (("emptied by a landing", EMPTY_ALLOWED),
                         ("never matched", NEVER_MATCHED)):
        for b, why in sorted(table.items()):
            state = empty_state.get(b, "NON-EMPTY")
            print(f"    allowlisted [{label}] {b}: {state}" + (f"   -- {why}" if why else ""))
    if findings:
        print()
        for f in findings:
            print(f"  FINDING: {f}")
    else:
        print("\n  partition sums, every bucket is non-empty or allowlisted, and "
              "the plan agrees with the tree.")
    return 1 if findings else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except SystemExit:
        raise
    except Exception:  # noqa: BLE001 - the point is to catch *everything*
        import traceback
        traceback.print_exc()
        print("the check did not complete.  Exit 2 is a measurement failure, "
              "deliberately distinct from exit 1, which is a finding -- "
              "otherwise a stack trace reads as a verdict.", file=sys.stderr)
        raise SystemExit(2)
