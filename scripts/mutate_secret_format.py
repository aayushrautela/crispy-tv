#!/usr/bin/env python3
"""
Mutation driver for :android:platform-core's SecretFormat.

Run unattended:  python3 scripts/mutate_secret_format.py

## What this suite is for

`SecretFormat` is the encrypted-secret contract that the Android store
(`SecureTokenStore`), the desktop store (`DesktopSecretStore`) and any future
Apple one all implement. The constants were already shared; this landing moved
the *shape* -- joining and splitting the two halves -- here as well, so two
implementations cannot disagree about it without the compiler noticing.

That makes the four rules below worth pinning, because each was duplicated in
both implementations before this suite existed and **neither** implementation's
own suite could catch them: the Android store cannot be constructed on a JVM at
all (`AndroidKeyStore` is reached in its constructor), so there was no test on
either side that could have run the shared code.

## Rules this driver follows (they are why a run is trustworthy)

- **Never pass `-q`.** It suppresses Gradle's per-test `FAILED` lines, so every
  entry would report "build failed" instead of a name. The verdict survives and
  the evidence does not.
- **Truncate the log per entry.** Appending makes every entry report all earlier
  failures as its own evidence, so a name this entry expects that happened to
  fail under an earlier mutation scores as a catch. A longer failure list is not
  a more specific one.
- **`check_anchors()` runs before the first write** and aborts if any anchor does
  not occur exactly once, so an entry can never report "SURVIVED" against
  unmutated code.
- **`env` merges `os.environ`.** `subprocess.run(env=...)` *replaces* the
  environment; passing only `JAVA_TOOL_OPTIONS` strips `PATH` and `JAVA_HOME` and
  `./gradlew` never starts.
- **A `NO EVIDENCE` verdict exists** because an empty failure list is not a
  survivor -- a survivor is the absence of a failure *after positive evidence
  that the task ran*.
- **`re.M` goes to `re.compile`, never to `finditer`.** On a compiled pattern
  `finditer(s, re.M)` reads the `8` as a start offset and returns nothing.
- **The capture-group index is read off this file's own pattern.** It has two
  groups, so the method name is group 2. An index quoted from a sibling driver
  raised `IndexError` once already.
- **A survivor is a claim about the code, not a result.** Print the reminder and
  check it by hand: read the callee. If the guard is genuinely unreachable, keep
  it and write the measurement at the guard; if it is reachable, the test was
  missing.
"""

import os
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
TARGET = ROOT / "android/platform-core/src/commonMain/kotlin/com/crispy/tv/platform/SecretFormat.kt"
TASK = ":android:platform-core:desktopTest"
CLASS = "com.crispy.tv.platform.SecretFormatTest"
LOG = Path("/tmp/opencode/mutate-secret-format.log")

# Two groups: the class and the method. Both bracket groups are non-capturing,
# so the method name is group 2 -- not the 3 a sibling driver uses.
FAILED = re.compile(r"^(\w+)(?:\[.*?\])? > (\S+?)(?:\[.*?\])? FAILED", re.M)

ENTRIES = [
    {
        "name": "unprefixed-values-are-rejected",
        "why": (
            "The compatibility rule. A store that shipped before the prefix existed "
            "holds values without it, so decode must read them; rejecting them locks "
            "those users out of their own sessions on upgrade."
        ),
        "old": "val payload = if (isEncrypted(stored)) stored.removePrefix(PREFIX) else stored",
        "new": "val payload = if (isEncrypted(stored)) stored.removePrefix(PREFIX) else return null",
        "expect": ["aValueWrittenBeforeThePrefixExistedIsStillRead", "aValueMissingItsCiphertextIsNotThisFormat"],
    },
    {
        "name": "wrong-arity-becomes-a-guess",
        "why": (
            "The wrong number of fields must be null rather than a guess. Loosening "
            "the check to `size < 2` lets a three-field value be read as its first two, "
            "which is how a future format gets misread as this one."
        ),
        "old": "        if (parts.size != 2) return null\n",
        "new": "        if (parts.size < 2) return null\n",
        "expect": ["aValueCarryingAnExtraFieldIsNotThisFormat"],
    },
    {
        "name": "prefix-test-becomes-a-suffix-test",
        "why": (
            "isEncrypted is defined as a prefix test. Changing it to a suffix test "
            "makes every value encode produced report unencrypted, and decode then "
            "splits a value that still carries its prefix."
        ),
        "old": "fun isEncrypted(value: String): Boolean = value.startsWith(PREFIX)",
        "new": "fun isEncrypted(value: String): Boolean = value.endsWith(PREFIX)",
        "expect": ["encodeJoinsTheHalvesWithThePrefixAndTheSeparator", "aRoundTripReturnsBothHalvesExactly"],
    },
    {
        "name": "empty-halves-become-unreadable",
        "why": (
            "encode must never reject its own input -- only the caller knows what a "
            "valid IV looks like, so a bad IV is a crypto failure to report, not a "
            "format failure to hide."
        ),
        "old": "        if (parts.size != 2) return null\n",
        "new": "        if (parts.size != 2 || parts[0].isEmpty() || parts[1].isEmpty()) return null\n",
        "expect": ["theFormatDoesNotValidateItsOwnFields"],
    },
    {
        "name": "the-two-halves-are-swapped",
        "why": (
            "encode writes the IV first, so decode must read it back first. A swap "
            "round-trips *shape* perfectly, which is why the round-trip case asserts "
            "each half by name and the swapped case asserts that the format cannot "
            "tell them apart -- one of the two has to be positional."
        ),
        "old": "        return EncodedSecret(ivBase64 = parts[0], ciphertextBase64 = parts[1])",
        "new": "        return EncodedSecret(ivBase64 = parts[1], ciphertextBase64 = parts[0])",
        "expect": ["aRoundTripReturnsBothHalvesExactly"],
    },
]

RECHECK: list[str] = []


def check_anchors(entries) -> bool:
    ok = True
    source = TARGET.read_text(encoding="utf-8")
    for entry in entries:
        count = source.count(entry["old"])
        if count == 1:
            print(f"anchor ok {entry['name']}: occurs 1x")
        else:
            ok = False
            print(f"SKIP {entry['name']}: anchor occurs {count}x")
            if count == 0:
                print("   zero occurrences means the code is gone; grep the file")
    return ok


def run_task() -> str:
    env = {**os.environ, "JAVA_TOOL_OPTIONS": "-Djava.awt.headless=true"}
    proc = subprocess.run(
        ["./gradlew", TASK, f"--tests={CLASS}"],
        cwd=ROOT,
        env=env,
        capture_output=True,
        text=True,
    )
    return proc.stdout + proc.stderr


def main() -> None:
    selected = [e for e in ENTRIES if e["name"] in RECHECK] if RECHECK else ENTRIES
    if not check_anchors(selected):
        print("ABORT: an anchor does not occur exactly once; nothing was written")
        return

    caught = 0
    for entry in selected:
        original = TARGET.read_text(encoding="utf-8")
        try:
            mutated = original.replace(entry["old"], entry["new"], 1)
            assert mutated != original, f"{entry['name']}: the replacement is a no-op"
            TARGET.write_text(mutated, encoding="utf-8")
            LOG.write_text("", encoding="utf-8")  # truncate per entry, not once per run
            output = run_task()
            LOG.write_text(output, encoding="utf-8")
            ran = "BUILD SUCCESSFUL" in output or "BUILD FAILED" in output
            failures = [m.group(2) for m in FAILED.finditer(output)] if ran else []
            if failures:
                caught += 1
                print(f"CAUGHT   {entry['name']}: {failures}")
            elif not ran:
                print(f"NO EVIDENCE {entry['name']}: the task did not run; see {LOG}")
            else:
                print(f"SURVIVED {entry['name']}: [] -- check by hand before recording a gap")
        finally:
            TARGET.write_text(original, encoding="utf-8")
            print(f"restored: {TARGET.name} (len {len(original)} -> {len(TARGET.read_text(encoding='utf-8'))})")

    print(f"\n{caught} caught of len({len(selected)}) entries")
    if caught != len(selected):
        print("A survivor is a claim about the code: read the callee before deciding.")


if __name__ == "__main__":
    main()