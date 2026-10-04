plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

/**
 * Player-facing interfaces. The first Phase 2 module to become multiplatform, and
 * the one that establishes the recipe the other five follow.
 *
 * Nothing in here touched a platform API: all 6 files were already clean, so
 * every file moved to `commonMain` unchanged and this was a module-shape change
 * rather than a code change.
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

        // This module had no test source set at all, and unlike `:addons` there is
        // no Android-shaped sibling here to explain it: all six files are
        // `commonMain` and `androidMain` is empty. So 233 lines of
        // `WatchHistoryService` -- including a progress computation and twelve
        // fallback bodies -- and 156 lines of `MetadataLabResolver` ran on four
        // targets and were asserted by none of them. Without this block the
        // `commonTest` directory below exists, nothing compiles or runs it on
        // Android, and AGP only warns.
        withHostTest {}
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

        commonTest.dependencies {
            implementation(kotlin("test"))
            // `runTest`. Every member of `WatchHistoryService` is `suspend` and both
            // `MetadataLabResolver.resolve` and the data source it calls are too, so
            // `kotlin("test")` alone leaves the whole suite unable to call the code it
            // is about: 30 errors, and every one of them a cascade from this one missing
            // artifact rather than a mistake in a test.
            implementation(libs.coroutines.test)
        }
    }
}
