# Fonts for the rendering test suites

`Roboto-Regular.ttf` is bundled here because `:android:desktopApp`'s tests
render real Compose UI on a desktop JVM, and Skia resolves typefaces through
fontconfig. On a bare container with no fonts installed and no fontconfig, the
render fails with `IllegalStateException: Could not load font` -- so a real
regression would be indistinguishable from a broken build host.

Roboto is used because it is the Android system font, so what the tests draw
matches what the app itself draws.

- Font: Roboto, https://fonts.google.com/specimen/Roboto
- Licence: Apache License 2.0, see `LICENSE-Apache-2.0.txt`
- Obtained from the Google Fonts API (`fonts.googleapis.com/css2?family=Roboto:wght@400`)

`fonts.conf` is **generated** at build time into the module's `build/` rather than
committed, because fontconfig needs an absolute `<dir>` and the checkout path
differs per machine. `:android:desktopApp` points `FONTCONFIG_FILE` at its
generated file for the test JVM.
