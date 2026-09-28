# Fonts for the rendering test suites

`Roboto-Regular.ttf` is bundled here because the Roborazzi verification path
draws a label onto its diff canvas using Java2D, which needs a host font. On a
bare container with no fonts installed and no fontconfig, every *failing*
screenshot test died with `Fontconfig head is null` instead of reporting the
actual difference -- so a real regression was indistinguishable from a broken
build host.

Bundling the font also makes the diff label render identically everywhere, which
matters because a screenshot gate that reports differently per machine is not a
gate.

Roboto is used because it is the Android system font, so the diff label matches
what the app itself draws.

- Font: Roboto, https://fonts.google.com/specimen/Roboto
- Licence: Apache License 2.0, see `LICENSE-Apache-2.0.txt`
- Obtained from the Google Fonts API (`fonts.googleapis.com/css2?family=Roboto:wght@400`)

`fonts.conf` is **generated** at build time into each module's `build/` rather than
committed, because fontconfig needs an absolute `<dir>` and the checkout path
differs per machine. Both `:android:androidApp` and `:android:desktopApp` point
`FONTCONFIG_FILE` at their generated file for the test JVM.

This lives at the repository root rather than inside either module because two
modules need it. `:android:androidApp` needs it for the Java2D diff label;
`:android:desktopApp` needs it because Skia resolves typefaces through
fontconfig, and a container with no system fonts fails a Compose render with
`IllegalStateException: Could not load font` -- the same root cause as the
fontconfig case above, reached by a different route.
