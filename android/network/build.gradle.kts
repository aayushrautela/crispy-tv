plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

/**
 * HTTP transport and the trailer-extraction seam.
 *
 * ## Why OkHttp lives in `androidMain`
 *
 * OkHttp publishes no Kotlin/Native artifact, so the three OkHttp-backed files
 * cannot live in `commonMain`, and Android is the only JVM target this repository
 * declares -- so `androidMain` is where they sit. `commonMain` keeps only the
 * `CrispyHttpClient` interface, which is the name portable consumers use.
 *
 * The only thing the factory needed from `Context` was `context.cacheDir`, so it
 * takes a `File` instead: `AppHttp` supplies a directory under the Android cache
 * root. That is the whole platform difference.
 *
 * ## No flavour axis
 *
 * This module used to carry `store` / `sideload` so `YouTubeTrailerExtractor` could
 * have two implementations and NewPipeExtractor could be declared
 * `sideloadImplementation`. That was the only reason for the axis, and it made the
 * module unconsumable by `:app` once `:app` became multiplatform: the KMP library
 * plugin is single-variant, so it expressed no preference between
 * `storeDebugApiElements` and `sideloadDebugApiElements` and Gradle failed with an
 * ambiguous-variant error. The axis now lives only in `:androidApp`.
 */
kotlin {
    jvmToolchain(21)

    android {
        namespace = "com.crispy.tv.network"
        compileSdk = 37
        minSdk = 26

        // This module's testable surface is exactly two files, and nothing asserted
        // them. Its `androidMain` is OkHttp/`Context` adapters that no `commonMain`
        // can name, so a reader skimming the module concludes there is
        // nothing here to test -- and the two `commonMain` files that do exist
        // (`TrailerSource.kt` above all: a hand-rolled regex with a non-null fallback,
        // and a classifier that matches two literals where the regex matches four
        // shapes) are the part no target could ever have caught. Without this block
        // the `commonTest` directory below exists, nothing compiles or runs it on
        // Android, and AGP only warns.
        withHostTest {}
    }

    // Compile-only verification target, never shipped and never run. It is the one
    // Kotlin/Native target that builds on a Linux host, so it enforces the same
    // "no JVM API in commonMain" rule as the Apple targets in seconds. This module
    // is the clearest case for why: `System.currentTimeMillis()` is invisible to an
    // import-based scan and `:android:player` shipped one until this gate ran.
    linuxX64()

    iosArm64()
    iosSimulatorArm64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        // OkHttp is a JVM artifact with no Kotlin/Native counterpart, and Android
        // is the only JVM target this repository declares, so the OkHttp-backed
        // implementation sits in `androidMain` next to `AppHttp`. No Kotlin/Native
        // target extracts these files, because there is no OkHttp artifact to
        // resolve for one.
        androidMain.dependencies {
            // `api`, and deliberately so: five modules -- :app, :tv,
            // :watchhistory, :backend and :addons -- import okhttp3 without
            // declaring it, relying on this module re-exporting it.
            api(libs.okhttp)
            implementation(libs.okhttp.logging.interceptor)
            implementation(libs.coroutines.core)

            implementation(libs.coroutines.android)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            // No `libs.coroutines.test`, unlike `:app`, `:home` and `:player`: both
            // functions under test are plain and non-suspend, so a coroutine test
            // dispatcher would be dead weight. Recording the omission so the next
            // agent does not add it "for consistency" with the other four.
        }
    }
}
