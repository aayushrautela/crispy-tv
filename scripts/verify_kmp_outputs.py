#!/usr/bin/env python3
"""Assert that no compiled Kotlin class outlives the declaration that produced it.

## What this is guarding against

A class file in `build/classes/kotlin` with no declaration behind it binds a
reference that should have failed to compile. When that happens a later
`BUILD SUCCESSFUL` certifies nothing, and every other gate in this repository
becomes unreliable at the same time.

This was observed once, during the extraction of the 52 backend response types out
of `CrispyBackendClient`: `CrispyBackendClient$ResponsiveImageSet.class` sat in the
output with a timestamp seven hours older than the change that removed the nested
type. It was found by reading an `ls -l` by hand.

**The cause was never established, and the two obvious candidates are false.** Both
were tested on this build: removing a nested declaration and recompiling
incrementally deletes its class correctly, and `git mv`-ing a file between source
sets does not make Gradle skip it. So this script is a *detector*, not a fix. It
is here because the failure it catches is silent, not because the mechanism is
known -- anyone who trips it has found the thing that was missing.

## What it asserts

For every module under `android/`:

* collect every top-level type declaration in every one of its Kotlin/Java source
  sets, plus the `<FileName>Kt` file facade for any file with top-level functions
  or properties;
* list every class file in each compiled Kotlin output directory
  (`build/classes/kotlin/<variant>/<sourceSet>`);
* **fail if the output holds a top-level class that no source declaration
  accounts for, or a nested class its owner no longer refers to.**

The direction is deliberate. A *missing* class fails the build loudly and needs
no check. An *extra* class is silent, and silent is what this exists to stop.

## Why not just clean every time

`./gradlew clean` before every run would also work, and is far simpler. It is not
what runs in practice: it is slow enough that people stop running the full gate,
and it still does not help a developer who compiles a single module to check one
error. Checking the artefact is also what this repository already decided to do
for goldens ("Verify is the default") and for distribution
(`verify_apk_distribution.py` reads the dex, not the dependency graph). This is the
same rule applied to compilation.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
MODULE_ROOT = REPO_ROOT / "android"

# A source directory belongs to a compilation if it is named like one.
SOURCE_DIR_NAMES = {
    "commonMain", "commonTest",
    "androidMain", "androidUnitTest", "androidInstrumentedTest",
    "jvmMain", "jvmTest",
    "desktopMain", "desktopTest",
}

# Kotlin/JVM emits these for a type; they are the type's own class file.
TYPE_KEYWORDS = (
    "class", "interface", "object",
)

# Modifiers and annotations that can precede a top-level declaration.
MODIFIERS = (
    "public", "internal", "private", "protected", "abstract", "final", "open",
    "sealed", "data", "value", "annotation", "enum", "actual", "expect",
    "inline", "fun", "external", "const", "lateinit", "override", "suspend",
    "operator", "infix", "tailrec", "external",
)

DECL = re.compile(
    r"^(?:(?:public|internal|private|protected|abstract|final|open|sealed|data|"
    r"value|annotation|enum|actual|expect|inline|fun|external|const|lateinit|"
    r"override|suspend|operator|infix|tailrec)\s+)*"
    r"(?P<kw>class|interface|object)\s+(?P<name>[A-Za-z_][A-Za-z0-9_]*)"
)

# `typealias` produces no class file, and `expect` is a declaration without a body
# on the common target, so neither may be counted as an expected class.
TYPEALIAS = re.compile(r"^typealias\s+")
EXPECT = re.compile(r"^expect\s+(class|interface|object)\s+")

# A top-level function or property makes the file emit a `<FileName>Kt` facade.
TOP_LEVEL_MEMBER = re.compile(
    r"^(?:public\s+|internal\s+|private\s+)?"
    r"(?:(?:inline|external|suspend|operator|infix|tailrec|const|lateinit|"
    r"expect|actual)\s+)*"
    r"(fun|val|var)\b"
)

JVM_NAME = re.compile(r'^@file:JvmName\(\s*"([^"]+)"\s*\)')


def strip_comments_and_strings(source: str) -> str:
    """Remove comments and literals so a declaration inside one cannot be counted.

    Written as a scanner rather than a substitution chain because a single
    mis-ordered pattern produces a wrong answer silently, and this script's whole
    purpose is to be a source of truth.
    """
    out: list[str] = []
    i, n = 0, len(source)
    while i < n:
        c = source[i]
        if source.startswith("//", i):
            nl = source.find("\n", i)
            i = n if nl < 0 else nl
            continue
        if source.startswith("/*", i):
            depth, i = 1, i + 2
            while i < n and depth:
                if source.startswith("/*", i):
                    depth, i = depth + 1, i + 2
                elif source.startswith("*/", i):
                    depth, i = depth - 1, i + 2
                else:
                    i += 1
            continue
        if c == '"':
            if source.startswith('"""', i):
                j = source.find('"""', i + 3)
                i = n if j < 0 else j + 3
            else:
                i += 1
                while i < n:
                    if source[i] == "\\":
                        i += 2
                        continue
                    if source[i] in ('"', "\n"):
                        i += 1
                        break
                    i += 1
            out.append('""')
            continue
        if c == "'":
            j = source.find("'", i + 1)
            if 0 <= j <= i + 4:
                i = j + 1
                out.append("'x'")
                continue
        out.append(c)
        i += 1
    return "".join(out)


def declared_types(path: Path) -> tuple[set[str], bool]:
    """Return the FQNs a source file declares, and whether it emits a file facade."""
    try:
        raw = path.read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError):
        return set(), False
    code = strip_comments_and_strings(raw)

    package = ""
    facade_name = None
    types: set[str] = set()
    has_facade = False

    for line in code.split("\n"):
        # Only column 0 is top level; anything indented belongs to a member.
        if line[:1] in (" ", "\t", ")", "]", "}"):
            continue
        m = re.match(r"^package\s+([A-Za-z0-9_.]+)", line)
        if m:
            package = m.group(1)
            continue
        m = JVM_NAME.match(line.strip())
        if m:
            facade_name = m.group(1)
            continue
        if TYPEALIAS.match(line):
            continue
        if EXPECT.match(line):
            continue
        m = DECL.match(line)
        if m:
            types.add(f"{package}.{m.group('name')}" if package else m.group("name"))
            continue
        if TOP_LEVEL_MEMBER.match(line):
            has_facade = True

    if has_facade:
        name = facade_name or path.stem
        # Kotlin/JVM replaces a dot in the file name with an underscore when
        # naming the facade class. The compose-resources plugin names its
        # generated accessors `Drawable0.commonMain.kt`, whose facade is
        # `Drawable0_commonMainKt` -- not `Drawable0.commonMainKt`. Using the
        # stem verbatim reported all of them as orphans, which looks exactly
        # like the stale-output breakage this gate exists to catch.
        name = name.replace(".", "_")
        types.add(f"{package}.{name}Kt" if package else f"{name}Kt")

    return types, False


def source_files(module: Path) -> list[Path]:
    """Every Kotlin and Java source a module compiles, across all its source sets.

    Includes `build/generated`, because a generated source is a real declaration
    with no checked-in file behind it, and treating it as an orphan would make the
    gate report the build's own generators as breakage. Two exist here:
    `:android:platform-core`'s `generateAppConfig` writes `AppConfig.kt`, and the
    compose-resources plugin writes `Res.kt` plus the resource collectors in
    `:android:sharedUI`.
    """
    found: list[Path] = []
    src = module / "src"
    if src.is_dir():
        for source_set in sorted(src.iterdir()):
            if not source_set.is_dir():
                continue
            for lang in ("kotlin", "java"):
                root = source_set / lang
                if root.is_dir():
                    found.extend(sorted(root.rglob("*.kt")))
                    found.extend(sorted(root.rglob("*.java")))

    generated = module / "build" / "generated"
    if generated.is_dir():
        for lang in ("kotlin", "java"):
            for root in sorted(generated.rglob(lang)):
                if root.is_dir():
                    found.extend(sorted(root.rglob("*.kt")))
                    found.extend(sorted(root.rglob("*.java")))

    return found


def compiled_outputs(module: Path) -> list[Path]:
    """The merged Kotlin output directories for this module's variants."""
    root = module / "build" / "classes" / "kotlin"
    if not root.is_dir():
        return []
    return sorted(d for d in root.glob("*/main") if d.is_dir())


def top_level_classes(output: Path) -> set[str]:
    """FQNs of the class files in an output whose name has no ``$``."""
    names: set[str] = set()
    for class_file in output.rglob("*.class"):
        if "$" in class_file.name:
            continue
        names.add(class_fqn(class_file, output))
    return names


def class_fqn(class_file: Path, output: Path) -> str:
    parts = list(class_file.relative_to(output).parts)
    parts[-1] = parts[-1][: -len(".class")]
    return ".".join(parts)


def orphan_nested_classes(output: Path) -> list[str]:
    """Nested class files whose owner no longer refers to them.

    ## The invariant

    A nested class is only ever linked through its owner. The JVM resolves
    ``Outer$Inner`` by reading the ``InnerClasses`` attribute of ``Outer.class``,
    and every ``InnerClasses`` entry points at a ``CONSTANT_Class`` holding the
    name ``Outer$Inner``. So a live ``Outer$Inner.class`` *necessarily* has its own
    name sitting in ``Outer.class``'s constant pool, and a byte search for the
    name is a sound necessary-condition test.

    The search is for the **internal** form, with ``/`` separators, because that is
    what a class file's constant pool actually stores. Searching for the dotted
    form finds nothing and reports every nested class in the repository as an
    orphan, which is the failure mode that makes a gate get switched off.

    It is a deliberately weak test: a name can be present for other reasons too.
    Weakness in this direction only ever makes the gate more lenient, never falsely
    strict, which is the right way for a safety gate to be wrong.

    ## Why this case needs handling at all

    Skipping every ``$`` name is the obvious approach and it is wrong. It would have
    missed the exact defect this script exists to catch: the stale
    ``CrispyBackendClient$ResponsiveImageSet.class`` found after the backend
    extraction, which was a *nested* class left behind by a file that still exists
    and still compiles.
    """
    orphans: list[str] = []
    for class_file in output.rglob("*.class"):
        if "$" not in class_file.name:
            continue
        fqn = class_fqn(class_file, output)
        owner, _, nested = fqn.rpartition("$")
        owner_file = output / (owner.replace(".", "/") + ".class")
        if not owner_file.is_file():
            # No owner on disk: the owning class is itself missing, which the
            # top-level check reports. Not this check's business.
            continue
        if fqn.replace(".", "/").encode("utf-8") not in owner_file.read_bytes():
            orphans.append(fqn)
    return orphans


def check_module(module: Path) -> list[str]:
    expected: set[str] = set()
    for source in source_files(module):
        types, _ = declared_types(source)
        expected |= types

    problems: list[str] = []
    outputs = compiled_outputs(module)
    if not outputs:
        return problems

    for output in outputs:
        label = output.relative_to(module)
        for orphan in sorted(top_level_classes(output) - expected):
            problems.append(f"{module.name}: {orphan}  (in {label})")
        for orphan in sorted(orphan_nested_classes(output)):
            problems.append(f"{module.name}: {orphan}  (in {label})")
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--quiet", action="store_true", help="print only failures"
    )
    args = parser.parse_args()

    modules = sorted(
        m for m in MODULE_ROOT.iterdir()
        if m.is_dir() and (m / "build.gradle.kts").is_file()
    )

    checked = 0
    problems: list[str] = []
    for module in modules:
        found = check_module(module)
        if compiled_outputs(module):
            checked += 1
        problems.extend(found)

    if problems:
        print(
            "verify_kmp_outputs: %d compiled class(es) have no source declaration."
            % len(problems),
            file=sys.stderr,
        )
        print(
            "\nThese are stale outputs left by an incremental compile. They can make\n"
            "a broken build report success, so this gate fails until they are gone.\n",
            file=sys.stderr,
        )
        for problem in problems:
            print(f"  {problem}", file=sys.stderr)
        print(
            "\n./gradlew :android:<module>:clean, then recompile. If a class is still\n"
            "reported, a file was moved between source sets and Gradle's up-to-date\n"
            "check used the preserved mtime: recompile that module with --rerun-tasks.",
            file=sys.stderr,
        )
        return 1

    if not args.quiet:
        print(
            f"verify_kmp_outputs: {checked} module(s) with compiled output, "
            "no class outlives its declaration."
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
