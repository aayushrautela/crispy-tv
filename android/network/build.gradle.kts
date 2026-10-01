plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

/**
 * HTTP transport and the trailer-extraction seam.
 *
 * ## Why this module is mostly `androidMain`
 *
 * OkHttp publishes **no Kotlin/Native artifact**, and plan §5.3's constraint says
 * Android and desktop JVM cannot share an intermediate source set. So there is no
 * source set that both can put the OkHttp implementation in, which is a property
 * of the dependency rather than a choice. Everything that touches OkHttp or
 * `android.content.Context` therefore lives in `androidMain`, permanently for now,
 * and the desktop adapter arrives in Phase 5 when there is a desktop caller.
 *
 * ## No flavour axis, and no `HttpClientPort` yet
 *
 * This module used to carry `store` / `sideload` so `YouTubeTrailerExtractor` could
 * have two implementations and NewPipeExtractor could be declared
 * `sideloadImplementation`. That was the only reason for the axis, and it made the
 * module unconsumable by `:app` once `:app` became multiplatform: the KMP library
 * plugin is single-variant, so it expressed no preference between
 * `storeDebugApiElements` and `sideloadDebugApiElements` and Gradle failed with an
 * ambiguous-variant error. The axis now lives only in `:androidApp`.
 *
 * Plan §6 Phase 2 also called for an `HttpClientPort` here. It is deliberately not
 * added yet. A port designed now would be designed against nothing: no portable
 * caller exists, so its shape -- whether it needs multipart bodies, how headers
 * are represented without OkHttp's `Headers`, whether a URL is a `String` or
 * something structured -- would be guesswork, and the first module that actually
 * uses it would redesign it. The call sites today are 14 files across six modules
 * -- `:android:addons`, `:android:app`, `:android:backend`, `:android:network`,
 * `:android:tv`, `:android:watchhistory` -- and every one of them is in a
 * non-`commonMain` source set. They read `code`, `body`, `headers`, `isSuccessful`
 * and `url` off the response and pass `CrispyHttpClient` itself into constructors,
 * which is the shape a port would have to answer to. (The two `commonMain` files
 * that mention `CrispyHttpClient` are the port KDocs saying why `BackendApi` and
 * `AccountApi` are not transport abstractions, so they are not call sites and are
 * not counted. An earlier version of this comment published per-member counts; they
 * were removed rather than re-derived, because no reader could reproduce the command
 * that produced them. Count with
 * `grep -rl --include=*.kt 'CrispyHttpClient\|httpClient\.' android | grep -v src/commonMain`.)
 * The port lands with the first consumer in the `:android:watchhistory` step, with a
 * real implementation and real call sites behind it.
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
        commonTest.dependencies {
            implementation(kotlin("test"))
            // No `libs.coroutines.test`, unlike `:app`, `:home` and `:player`: both
            // functions under test are plain and non-suspend, so a coroutine test
            // dispatcher would be dead weight. Recording the omission so the next
            // agent does not add it "for consistency" with the other four.
        }

        androidMain.dependencies {
            implementation(libs.coroutines.android)

            // `api`, and deliberately so: five modules -- :app, :tv,
            // :watchhistory, :backend and :addons -- import okhttp3 without declaring
            // it, relying on this module re-exporting it. Declared in `androidMain`
            // rather than `commonMain` because there is no Native artifact to
            // resolve; Android consumers are unaffected, since androidMain's `api`
            // lands on the same android variant configurations it did before.
            api(libs.okhttp)
            implementation(libs.okhttp.logging.interceptor)
        }
    }
}
