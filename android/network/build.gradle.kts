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
 * uses it would redesign it. The measured call-site shape today is `code` (28),
 * `body` (26), `headers` (5), `isSuccessful` (4), `url` (2), spread over 25 files
 * in six modules, none of which is portable yet. The port lands with the first
 * consumer in the `:android:watchhistory` step, with a real implementation and real
 * call sites behind it.
 */
kotlin {
    jvmToolchain(21)

    android {
        namespace = "com.crispy.tv.network"
        compileSdk = 37
        minSdk = 26
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
