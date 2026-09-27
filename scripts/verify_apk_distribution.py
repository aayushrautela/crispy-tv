#!/usr/bin/env python3

"""Assert that store APKs do not ship the sideload-only engines.

`:android:app:verifyDistributionExclusions` checks the *resolved dependency
graph*, which is the right place to catch a build-file mistake. This checks the
*built artefact*, which is the only thing that actually ships. A dependency can
be declared correctly and still leak classes in through a transitive path, and
only the dex knows.

Each marker is asserted in both directions:

  store APK    every marker must be ABSENT   (the actual requirement)
  sideload APK every marker must be PRESENT  (the control)

The second assertion is what keeps this honest. Without it, a marker that
stopped matching -- because a package was renamed, or a dependency was
repackaged -- would quietly turn the store check into a no-op that always
passes. Here it fails instead.

Reads the dex directly rather than shelling out to aapt/dexdump, so it needs
nothing but a Python interpreter and works the same for debug and release.

Usage:
    verify_apk_distribution.py --store app-store-release.aab --sideload app-sideload-release.apk
"""

import argparse
import sys
import zipfile
from pathlib import Path

# Byte substrings that only exist in a dex when the matching code is packaged.
# These are package prefixes, not single class names, so a class moving within
# its package does not invalidate the guard.
SIDELOAD_ONLY_MARKERS = {
    "torrent engine (com.crispy.tv.torrentengine)": b"com/crispy/tv/torrentengine/",
    "plugin runtime (com.crispy.tv.plugins)": b"com/crispy/tv/plugins/",
    "QuickJS engine (com.dokar.quickjs)": b"com/dokar/quickjs/",
    "YouTube extractor (org.schabi.newpipe)": b"org/schabi/newpipe/",
}


def read_dex(apk: Path) -> bytes:
    """Concatenated dex from an APK or an Android App Bundle.

    An APK keeps `classes*.dex` at the root; a bundle keeps them under
    `base/dex/`, so the same check covers a store AAB as well as an APK. That
    matters because the release workflow publishes a bundle for the store flavor
    and APKs for sideload.
    """
    try:
        with zipfile.ZipFile(apk) as archive:
            names = [
                n for n in archive.namelist()
                if n.endswith(".dex") and (n.startswith("classes") or "/dex/classes" in n)
            ]
            if not names:
                raise ValueError("no classes*.dex entries (not an APK or bundle?)")
            return b"".join(archive.read(name) for name in sorted(names))
    except zipfile.BadZipFile as error:
        raise ValueError(f"not a readable APK/zip: {error}") from error


def check(path: Path, must_be_absent: bool) -> list[str]:
    """Returns a list of human-readable problems; empty means the APK is fine."""
    try:
        dex = read_dex(path)
    except (OSError, ValueError) as error:
        return [f"  {path}: {error}"]

    problems = []
    for label, marker in SIDELOAD_ONLY_MARKERS.items():
        present = marker in dex
        if must_be_absent and present:
            problems.append(f"  {path}: store build CONTAINS {label}")
        if not must_be_absent and not present:
            problems.append(
                f"  {path}: sideload build is MISSING {label} -- the marker no longer"
                " matches, so the store check above has stopped being meaningful"
            )
    return problems


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--store", action="append", required=True, type=Path,
                        metavar="APK", help="APK that must NOT contain the sideload engines")
    parser.add_argument("--sideload", action="append", required=True, type=Path,
                        metavar="APK", help="APK that MUST contain them (control)")
    args = parser.parse_args()

    problems: list[str] = []
    checked = 0
    for apk in args.store:
        checked += 1
        problems += check(apk, must_be_absent=True)
    for apk in args.sideload:
        checked += 1
        problems += check(apk, must_be_absent=False)

    if problems:
        print("verify_apk_distribution: FAILED", file=sys.stderr)
        print("\n".join(problems), file=sys.stderr)
        return 1

    print(f"verify_apk_distribution: {checked} APK(s) OK")
    for apk in args.store:
        print(f"  store    {apk}  - no sideload-only engine present")
    for apk in args.sideload:
        print(f"  sideload {apk}  - all sideload-only engines present")
    return 0


if __name__ == "__main__":
    sys.exit(main())
