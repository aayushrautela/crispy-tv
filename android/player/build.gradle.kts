plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

/**
 * Player-facing interfaces. The first Phase 2 module to become multiplatform, and
 * the one that establishes the recipe the other five follow.
 *
 * Nothing in here touched a platform API: 6 of 6 files were already clean, so
 * every file moved to `commonMain` unchanged and this is a module-shape change
 * rather than a code change. That is why it goes first — if the recipe in
 * phase2-data-layer-plan.md is wrong, this is the cheapest place to discover it.
 *
 * The `androidMain` source set is deliberately left empty. `:android:native-engine`
 * owns Media3 and libmpv and stays a plain `com.android.library`; players are
 * Android-only by design, not by accident. The interfaces here are the part that
 * the desktop app and the iOS shell can both see.
 */
kotlin {
    jvmToolchain(21)

    android {
        namespace = "com.crispy.tv.player"
        compileSdk = 37
        minSdk = 26
    }

    jvm("desktop")

    // Compile-only verification target, never shipped and never run. It is the one
    // Kotlin/Native target that builds on a Linux host, so it enforces the same
    // "no JVM API in commonMain" rule as the Apple targets in seconds instead of
    // waiting for macOS CI. `scripts/check_common_purity.py` cannot see this class
    // of bug: `"x".format(y)` is `kotlin.*` and passes an import scan.
    linuxX64()

    iosArm64()
    iosSimulatorArm64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(project(":android:core-domain"))
            api(libs.coroutines.core)
        }
    }
}
