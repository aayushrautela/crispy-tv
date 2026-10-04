plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

/**
 * HTTP transport and the trailer-extraction seam.
 *
 * ## Why there is a `jvmCommonMain` here
 *
 * OkHttp publishes no Kotlin/Native artifact, but Android and the desktop are both
 * JVM, so the implementation belongs in a source set they share rather than in
 * `androidMain`. `jvmCommonMain` is that set: `androidMain` and `desktopMain` both
 * depend on it, and it holds the three OkHttp-backed files. `commonMain` keeps only
 * the `CrispyHttpClient` interface, which is the name portable consumers use.
 *
 * The only thing the factory needed from `Context` was `context.cacheDir`, so it
 * takes a `File` instead: `AppHttp` supplies a directory under the Android cache
 * root and the desktop adapter supplies its own. That is the whole platform
 * difference.
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
        // them. Its `androidMain` is four OkHttp/`Context` adapters that no
        // `commonMain` can name, so a reader skimming the module concludes there is
        // nothing here to test -- and the two `commonMain` files that do exist
        // (`TrailerSource.kt` above all: a hand-rolled regex with a non-null fallback,
        // and a classifier that matches two literals where the regex matches four
        // shapes) are the part no target could ever have caught. Without this block
        // the `commonTest` directory below exists, nothing compiles or runs it on
        // Android, and AGP only warns.
        withHostTest {}
    }

    jvm("desktop")

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
        // Android and desktop are both JVM, so the OkHttp implementation is shared
        // here instead of living in androidMain. No Kotlin/Native target depends on
        // this set, because there is no OkHttp artifact to resolve for it.
        val jvmCommonMain =
            create("jvmCommonMain") {
                dependsOn(commonMain.get())

                dependencies {
                    // `api`, and deliberately so: five modules -- :app, :tv,
                    // :watchhistory, :backend and :addons -- import okhttp3 without
                    // declaring it, relying on this module re-exporting it.
                    api(libs.okhttp)
                    implementation(libs.okhttp.logging.interceptor)
                    implementation(libs.coroutines.core)
                }
            }
        androidMain.get().dependsOn(jvmCommonMain)
        named("desktopMain").get().dependsOn(jvmCommonMain)

        commonTest.dependencies {
            implementation(kotlin("test"))
            // No `libs.coroutines.test`, unlike `:app`, `:home` and `:player`: both
            // functions under test are plain and non-suspend, so a coroutine test
            // dispatcher would be dead weight. Recording the omission so the next
            // agent does not add it "for consistency" with the other four.
        }

        androidMain.dependencies {
            implementation(libs.coroutines.android)
        }
    }
}
