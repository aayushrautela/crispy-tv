# Intentionally minimal for initial scaffold.

## Rules for NewPipeExtractor / Rhino
#
# :android:tv resolves the sideload variants (see missingDimensionStrategy), so
# it pulls in the QuickJS plugin runtime and with it Rhino, exactly as :app does.
# Rhino references java.beans / javax.script / jdk.dynalink, none of which exist
# on Android, so R8 fails the release build without these. Mirrors
# android/app/proguard-rules.pro.
-dontwarn org.mozilla.javascript.tools.**
-dontwarn java.beans.**
-dontwarn javax.script.**
-dontwarn jdk.dynalink.**

# The -keep rules are deliberately not copied from :android:app. Nothing in the
# TV app evaluates Rhino scripts directly -- it goes through the plugin runtime,
# which ships its own ProGuard rules -- so keeping the whole Rhino tree open
# would only enlarge the release APK.
