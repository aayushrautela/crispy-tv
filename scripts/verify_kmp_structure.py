#!/usr/bin/env python3
"""Measure the module graph's layering, and assert the KMP invariant.

The invariant this exists to state: **a `commonMain` source set may only
depend on another `commonMain` source set.**  That is the whole structural
content of "is this a KMP project" -- everything else is variation.

Two module kinds break it by construction, and neither is fixable by editing
the Kotlin:

  * a plain `com.android.library` publishes no JVM variant at all, so no KMP
    `commonMain` can name its types however pure the code looks;
  * a `kotlin.jvm` module is JVM-only, so it is legal in `commonMain` of
    another module only if that module is never compiled for Native.

Both are reported separately, because only the first is unconditionally wrong.

Run:  python3 scripts/verify_kmp_structure.py [--json]
Exit: 0 clean, 1 on a violation, 2 on a measurement problem.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
from collections import defaultdict

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# A module kind decides whether its types are nameable from a KMP commonMain.
KMP = "kmp"                      # publishes a JVM + common variant: safe
ANDROID_LIB = "android-library"  # plain com.android.library: NO jvm variant
ANDROID_APP = "android-app"      # entry point
KOTLIN_JVM = "kotlin-jvm"        # JVM only, no common variant

# Which plugin aliases in a `plugins { }` block imply which kind.
KIND_BY_PLUGIN = {
    "kotlin.multiplatform": KMP,
    "android.kotlin.multiplatform.library": None,  # only meaningful with the above
    "compose.multiplatform": None,
    "kotlin.compose": None,
    "kotlin.serialization": None,
    "android.library": ANDROID_LIB,
    "android.application": ANDROID_APP,
    "kotlin.jvm": KOTLIN_JVM,
}

# Source sets that a KMP module compiles for every target.  A commonMain edge
# into any of these is free; an edge into a platform set is a violation.
COMMON_SETS = ("commonMain", "commonTest")

# Which sets a resolved name is DELIBERATELY shared through, as opposed to
# coupled to Android by.  `jvmMain` and `appUi` belong here and not in
# COMMON_SETS: they are real source sets so their files must be counted in the
# per-module table (line 409 sums everything outside COMMON_SETS as "other", and
# folding them in would delete their counts from the report entirely), but a name
# resolving into `:app`'s `jvmMain` is the shared-JVM-layer technique working as
# intended, not the Android coupling `platform` exists to surface.
SHARED_SETS = COMMON_SETS + ("appUi", "jvmMain")


def git(*args: str) -> str:
    return subprocess.run(
        ["git", *args], cwd=REPO, check=True, capture_output=True, text=True
    ).stdout


def strip_comments(text: str) -> str:
    """Remove // and /* */ comments without eating `//` inside a string literal.

    A regex cannot do this: `maven(url = "https://...")` and a trailing
    `// note` after a dependency both end in `//`, and one of them is code.
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
                if i + 1 < n:
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


def edges_as_lists(edges: dict[str, dict[str, set[str]]]) -> dict:
    """JSON-safe, order-stable view of the dependency edges.

    Sorted rather than merely converted, because a `set` has no order and this
    output is the artifact a diff would be taken against.
    """
    return {mod: {sset: sorted(deps) for sset, deps in sets.items()}
            for mod, sets in edges.items()}


def module_dirs() -> dict[str, str]:
    """Map ':android:app' -> 'android/app', from settings.gradle.kts."""
    text = strip_comments(open(os.path.join(REPO, "settings.gradle.kts")).read())
    mods = {}
    for m in re.finditer(r'include\("([^"]+)"\)', text):
        mods[m.group(1)] = m.group(1).lstrip(":").replace(":", "/")
    return mods


def module_kind(path: str) -> str:
    text = strip_comments(open(path).read())
    block = re.search(r"plugins\s*\{(.*?)\n\}", text, re.S)
    plugins = block.group(1) if block else ""
    # Last write wins, and the pair (kotlin.multiplatform,
    # android.kotlin.multiplatform.library) must collapse to KMP, so only the
    # two kinds that are *exclusively* theirs may set the result directly.
    kind = None
    for alias, k in KIND_BY_PLUGIN.items():
        if k and re.search(rf"alias\(libs\.plugins\.{re.escape(alias)}\)", plugins):
            kind = k
    if re.search(r"alias\(libs\.plugins\.kotlin\.multiplatform\)", plugins) and \
       re.search(r"alias\(libs\.plugins\.android\.kotlin\.multiplatform\.library\)", plugins):
        kind = KMP
    if kind is None:
        # Exit 2, not 1: an unclassifiable module is a broken *measurement*,
        # and 1 means "the invariant is violated".  Collapsing the two makes a
        # gate that cannot see anything indistinguishable from a red build.
        print(f"could not classify {path}: no recognised plugin alias in its "
              f"plugins {{ }} block", file=sys.stderr)
        sys.exit(2)
    return kind


def source_set_counts(mod_dir: str) -> dict[str, int]:
    """Count tracked .kt files per source set, each file counted once.

    `git ls-files` is the only trustworthy source: `find` and `git ls-files`
    overlap, and combining them double-counts.
    """
    files = git("ls-files", "--", os.path.join(mod_dir, "src")).splitlines()
    counts: dict[str, int] = defaultdict(int)
    for f in files:
        if not f.endswith(".kt"):
            continue
        m = re.search(r"/src/([^/]+)/", f)
        if m:
            counts[m.group(1)] += 1
    return dict(counts)


def project_edges(path: str) -> dict[str, set[str]]:
    """Map source-set name -> set of ':android:x' it declares a project dep on.

    Handles three shapes, all of which occur in this repository and only the
    first of which is obvious:

      * `commonMain.dependencies { }`  -- a KMP source set;
      * `getByName("androidHostTest").dependencies { }`;
      * a bare top-level `dependencies { }` -- an Android app / library, which
        applies to every source set it has.  Missing this one is what made
        `:androidApp -> :android:app` look like an undeclared reference.

    Counts nesting so a closing brace of an inner block cannot end the outer one.
    """
    text = strip_comments(open(path).read())
    edges: dict[str, set[str]] = defaultdict(set)
    header = re.compile(
        r'(?:(\w+)\.|getByName\("(\w+)"\)\.)?dependencies\s*(?:\(\s*\))?\s*\{'
    )
    for m in header.finditer(text):
        name = m.group(1) or m.group(2) or ""  # "" == top-level block
        depth, i = 1, m.end()
        while i < len(text) and depth:
            if text[i] == "{":
                depth += 1
            elif text[i] == "}":
                depth -= 1
            i += 1
        body = text[m.end() : i - 1]
        for p in re.finditer(r'project\(\s*"([^"]+)"\s*\)', body):
            edges[name].add(p.group(1))
    return edges


# What a source set can see besides its own declarations.  Getting this wrong
# in the strict direction invents couplings: `:app`'s `commonTest` really does
# see every dependency `:app`'s `commonMain` declares, so a same-package name
# resolving into `:backend/commonMain` from `commonTest` is declared, not
# invisible.
#
# `jvmMain` and `appUi` are `:app`'s own intermediate sets, and they were
# missing here while existing in the build, which made this table a claim about
# the module rather than a measurement of it.  `appUi` sits between
# `commonMain` and the platform sets; `jvmMain` sits between `appUi` and the
# two JVM targets, because both `androidMain` and `desktopMain` now `dependsOn`
# it -- which is what lets a file pinned only by `java.*` or `Dispatchers.IO`
# be written once instead of as an androidMain/desktopMain pair.  One level of
# closure, listed explicitly, is what `declares()` walks.
ANCESTORS = {
    "commonMain": (),
    "appUi": ("commonMain",),
    "jvmMain": ("appUi", "commonMain"),
    "androidMain": ("jvmMain", "appUi", "commonMain"),
    "desktopMain": ("jvmMain", "appUi", "commonMain"),
    "main": (),
    "desktop": (),
    "linuxX64": (),
    "commonTest": ("commonMain",),
    "androidHostTest": ("androidMain", "jvmMain", "appUi", "commonMain"),
    "androidTest": ("androidMain", "jvmMain", "appUi", "commonMain"),
    "test": ("main", "androidMain", "jvmMain", "appUi", "commonMain", "store", "sideload"),
    "store": ("main", "androidMain", "jvmMain", "appUi", "commonMain"),
    "sideload": ("main", "androidMain", "jvmMain", "appUi", "commonMain"),
}


def declares(mod: str, sset: str, target: str, edges: dict[str, set[str]]) -> bool:
    """Is `target` visible to `mod`'s `sset`, directly or through an ancestor?"""
    if target in edges.get(mod, {}).get(sset, ()):
        return True
    if target in edges.get(mod, {}).get("", ()):  # top-level block: every set
        return True
    return any(target in edges.get(mod, {}).get(a, ()) for a in ANCESTORS.get(sset, ()))


def main() -> int:
    as_json = "--json" in sys.argv
    mods = module_dirs()
    if not mods:
        # A `include(...)` shape this regex does not match would report
        # "modules: 0" and exit 0, which is a silent green on a measurement
        # that never ran -- the same failure mode as a stale `expect` list.
        print("no modules parsed from settings.gradle.kts; the include() "
              "pattern no longer matches, so every count below would be a "
              "confident zero", file=sys.stderr)
        return 2

    kinds, counts, edges = {}, {}, {}
    for name, d in sorted(mods.items()):
        bf = os.path.join(REPO, d, "build.gradle.kts")
        kinds[name] = module_kind(bf)
        counts[name] = source_set_counts(d)
        edges[name] = project_edges(bf)

    # --- the invariant -----------------------------------------------------
    # A commonMain edge may only land in a module that publishes a JVM variant.
    violations = []
    for name, sets in edges.items():
        for dep in sets.get("commonMain", ()):
            k = kinds[dep]
            if k == ANDROID_LIB:
                violations.append((name, dep, "no-jvm-variant"))
            elif k == KOTLIN_JVM:
                violations.append((name, dep, "jvm-only-dependency"))
            elif k == ANDROID_APP:
                violations.append((name, dep, "entry-point-dependency"))

    # --- a module with no commonMain at all --------------------------------
    no_common = [n for n in mods if counts[n].get("commonMain", 0) == 0]

    # --- packages declared in more than one module -------------------------
    pkg_owner: dict[str, set[str]] = defaultdict(set)
    for f in git("ls-files", "--", "android").splitlines():
        # The trailing `/[^/]+\.kt` is load-bearing: without it the "package"
        # is the file name and every package looks unique to its own file, so
        # the shared-package count is a plausible zero that means nothing.
        m = re.search(r"^android/([^/]+)/src/\w+/(?:kotlin/)?(.+)/[^/]+\.kt$", f)
        if not m:
            continue
        mod = ":android:" + m.group(1)
        pkg = m.group(2).replace("/", ".")
        pkg_owner[pkg].add(mod)
    shared_pkgs = {p: sorted(o) for p, o in pkg_owner.items() if len(o) > 1}

    # --- where each shared package is declared, per module AND source set ---
    # The module alone is not the finding.  A type in module B's `commonMain`
    # is visible to module A's `androidMain` (the Android variant), so that
    # overlap is ordinary and harmless.  The overlap that matters is a name
    # declared in B and resolved from A *with no import*, which a move landing
    # can change without any diff in the consuming file.
    decl: dict[tuple[str, str, str], set[str]] = defaultdict(set)  # (pkg, name) -> {(mod, set)}
    own: dict[tuple[str, str], set[str]] = defaultdict(set)       # (mod, set) -> {name}
    for f in git("ls-files", "--", "android").splitlines():
        m = re.search(r"^android/([^/]+)/src/(\w+)/(?:kotlin/)?(.+)/[^/]+\.kt$", f)
        if not m:
            continue
        mod, sset = ":android:" + m.group(1), m.group(2)
        pkg = m.group(3).replace("/", ".")
        src = open(os.path.join(REPO, f), encoding="utf-8").read()
        body = strip_comments(src)
        # Top-level declarations only, and a nested `class Inner` is deliberately
        # excluded: AGENTS.md's "a nested type is as pinned as the file declaring
        # it" means the OUTER name is the one that pins, not the inner one.
        for d in re.finditer(
            r"^(?:@\w+(?:\([^)]*\))?\s*)*(?:public |internal |private |abstract |open |final |"
            r"sealed |data |value |expect |actual |external |inline |fun )*"
            r"(?:class|interface|object|enum class|typealias)\s+([A-Za-z_]\w*)",
            body, re.M,
        ):
            decl[(pkg, d.group(1))].add((mod, sset))
            own[(mod, sset)].add(d.group(1))

    # A declaration nobody resolves is a latent overlap, not a coupling.  The
    # loop therefore runs over FILES, not over declarations: iterating the
    # declaration index and skipping any module that declares the name skips
    # every module by construction, because a name is only *in* that index
    # where some module declares it.  The first version of this check did
    # exactly that and reported a confident, permanent zero.
    file_texts: dict[tuple[str, str], list[tuple[str, str, str]]] = defaultdict(list)
    for f in git("ls-files", "--", "android").splitlines():
        m = re.search(r"^android/([^/]+)/src/(\w+)/", f)
        if m and f.endswith(".kt"):  # a binary asset in src/ is not a source
            file_texts[(":android:" + m.group(1), m.group(2))].append((
                f, strip_comments(open(os.path.join(REPO, f), encoding="utf-8").read()), ""))
    for (mod, sset), entries in file_texts.items():
        for i, (f, body, _) in enumerate(entries):
            pm = re.search(r"^package\s+([\w.]+)", body, re.M)
            if pm:
                file_texts[(mod, sset)][i] = (f, body, pm.group(1))

    used = []
    for (mod, sset), entries in file_texts.items():
        for f, body, pkg in entries:
            if not pkg:
                continue
            for (dpkg, name), owners in decl.items():
                if dpkg != pkg or not re.search(rf"\b{name}\b", body):
                    continue
                for other, osset in owners:
                    if other == mod or name in own[(mod, sset)]:
                        continue  # A declares it too, so the local one wins
                    declared = declares(mod, sset, other, edges)
                    used.append((pkg, name, mod, sset, other, osset, f, declared))

    # Classify, because 90-odd bare-name hits are mostly the codebase working as
    # intended and reporting them all as problems would make the check noise.
    #
    #   undeclared  A resolves a name from B with no import AND no Gradle edge
    #               in this source set.  Invisible to the build graph: a rename
    #               in B breaks A with no diff in A.  The real defect.
    #   platform    A does depend on B, but the name lives in B's *androidMain*.
    #               Legal, and A's androidMain is thereby coupled to Android-only
    #               code -- which is the thing to know when targeting more hosts.
    #   shared      B's commonMain, same package, A depends on B.  This is the
    #               deliberate package-preservation technique and is correct.
    undeclared, platform, shared = [], [], []
    for rec in used:
        _, _, _, _, _, osset, _, declared_ok = rec
        if not declared_ok:
            undeclared.append(rec)
        elif osset not in SHARED_SETS:
            platform.append(rec)
        else:
            shared.append(rec)


    if as_json:
        # `edges` is module -> source set -> set[str].  `dict(v)` unwraps only
        # the outer level, so the leaves are still sets and json.dumps raises
        # TypeError on them -- which is why --json had never once produced JSON.
        # A set is also unordered, so it is not reproducible output; sorted(v)
        # is what makes two runs byte-identical.
        print(json.dumps(
            {"kinds": kinds, "counts": counts, "edges": edges_as_lists(edges),
             "violations": violations, "no_common": no_common,
             "shared_pkgs": shared_pkgs,
             "undeclared": len(undeclared), "platform": len(platform),
             "shared": len(shared)}, indent=2, sort_keys=True))
        # The same predicate as the text path.  This one returned 1 only on
        # `violations`, so a JSON consumer got exit 0 on a tree the human
        # report called undeclared-coupling.
        return 1 if (violations or undeclared) else 0

    print(f"modules: {len(mods)}\n")
    print(f"{'module':<34}{'kind':<17}{'common':>7}{'android':>8}{'other':>7}")
    print("-" * 73)
    for n in sorted(mods):
        c = counts[n]
        cm = c.get("commonMain", 0)
        am = c.get("androidMain", 0)
        other = sum(v for k, v in c.items() if k not in COMMON_SETS and k != "androidMain")
        print(f"{n:<34}{kinds[n]:<17}{cm:>7}{am:>8}{other:>7}")
    print()

    by_kind: dict[str, list[str]] = defaultdict(list)
    for n, k in kinds.items():
        by_kind[k].append(n)
    for k in (KMP, ANDROID_LIB, ANDROID_APP, KOTLIN_JVM):
        if by_kind[k]:
            print(f"  {k:<17} {len(by_kind[k]):>2}  {', '.join(sorted(by_kind[k]))}")
    print()

    print("commonMain dependency edges (the KMP invariant):")
    cm_edges = {n: sorted(edges[n].get("commonMain", ())) for n in sorted(mods)}
    # Keyed on (from, to) only.  The first version compared a 3-tuple whose
    # third element was the *reason* against a literal "", so it never matched
    # and the marker was dead code on the one path where it had to fire.
    bad = {(frm, to) for frm, to, _ in violations}
    any_edge = False
    for n, ds in cm_edges.items():
        if ds:
            any_edge = True
            for d in ds:
                mark = "  <-- VIOLATION" if (n, d) in bad else ""
                print(f"  {n:<32} -> {d}{mark}")
    if not any_edge:
        print("  (none)")
    print()

    if violations:
        print(f"VIOLATIONS: {len(violations)}")
        for n, d, why in violations:
            print(f"  {n:<32} -> {d:<32} {why}")
    else:
        print("VIOLATIONS: 0  (every commonMain edge lands in a JVM-variant module)")

    print()
    if no_common:
        print(f"modules with NO commonMain ({len(no_common)}):")
        for n in sorted(no_common):
            print(f"  {n:<32} {kinds[n]}")
    else:
        print("modules with NO commonMain: 0")

    print()
    if shared_pkgs:
        print(f"packages declared in more than one module ({len(shared_pkgs)}):")
        for p, o in sorted(shared_pkgs.items()):
            print(f"  {p:<44} {', '.join(o)}")
    else:
        print("packages declared in more than one module: 0")

    print()
    print()
    print("BARE-NAME cross-module references, by severity")
    print("  (a same-package name needs no import, so Gradle never sees this edge)")
    for title, rows, why in (
        ("UNDECLARED -- resolved with no Gradle edge at all", undeclared,
         "invisible to the build graph; a rename in the owning module breaks the "
         "consumer with no diff in the consumer"),
        ("PLATFORM -- depends on the module, but the name is in its androidMain", platform,
         "legal, and it is how androidMain becomes coupled to Android-only code"),
        ("SHARED -- depends on the module and the name is in its commonMain", shared,
         "the deliberate package-preservation technique; correct as written"),
    ):
        print()
        print(f"  {title}: {len(rows)}")
        seen: dict[tuple, int] = defaultdict(int)
        for pkg, name, mod, sset, other, osset, f, _ in rows:
            seen[(pkg, name, mod, sset, other, osset)] += 1
        for (pkg, name, mod, sset, other, osset), n in sorted(seen.items()):
            print(f"      {pkg}.{name}")
            print(f"          {mod}/{sset} -> {other}/{osset}   [{n} site(s)]")
        if why:
            print(f"      # {why}")

    return 1 if (violations or undeclared) else 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except SystemExit:
        raise
    except Exception:  # noqa: BLE001 - the point is to catch *everything*
        import traceback

        traceback.print_exc()
        print("the check did not complete.  Exit 2 means the measurement "
              "failed; it is deliberately distinct from exit 1, which means the "
              "invariant is violated -- otherwise a stack trace reads as a "
              "verdict.", file=sys.stderr)
        raise SystemExit(2)
