plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

/**
 * The Crispy backend client, the Supabase account client, and the auth/profile
 * stores.
 *
 * The largest Phase 2 module at 3,422 lines, and the one where the honest split is
 * narrow. Nine of its thirteen files are HTTP or JSON adapters over
 * `CrispyBackendClient`, and they stay in `androidMain` for the two reasons
 * documented on `:android:network` and `:android:watchhistory`.
 *
 * Those reasons, stated so they are not re-derived wrongly. OkHttp is a declared
 * JVM/Android dependency with no Kotlin/Native artifact, so a `commonMain` file
 * cannot name it at all. `org.json` is worse, and in a way that is easy to get
 * backwards: it is **not a dependency of this project**. It is a class of the
 * Android platform, supplied by `android.jar`, and it appears in exactly one
 * build file here — `testImplementation` in `:android:plugins`. So there is no
 * version to bump and no artifact to swap; it is simply absent from
 * `commonMainCompileClasspath` (verified for `:android:app`). Replacing it means
 * adding `kotlinx.serialization`, and that is a behaviour change on the parsing
 * boundary, not plumbing. It is not to be done as a side effect of moving a
 * file.
 *
 * | file | where | why |
 * |---|---|---|
 * | `Session` | `commonMain` | a data class, portable as-is |
 * | `ActiveProfileStore` | `commonMain` | `KeyValueStore` only; the `Context` is gone |
 * | `BackendTypes` | `commonMain` | the 52 response and request types that used to be nested in `CrispyBackendClient`. Pure data; lifted out because 38 files across 8 modules referenced them, and the client is `androidMain`, so a data class's nesting was pinning all of them to `androidMain` |
 * | `AiInsightsModels` | `commonMain` | moved once `BackendTypes` was. It was blocked by naming `CrispyBackendClient.ResponsiveImageSet` and by nothing else, so it became portable the moment that type did |
 * | `BackendApi`, `AccountApi` | `commonMain` | the type-level ports the two Android clients implement. Neither abstracts the transport: `CrispyHttpClient` leaks `okhttp3.HttpUrl` and `Headers` in its own signature, so a client is a wrapper *around* OkHttp and cannot be typed by it |
 * | `BackendPayloads`, `SignUpResult` | `commonMain` | input and result types that were nested inside `androidMain` clients. A nested type is as pinned as the file that declares it |
 * | `BackendContextResolver`, `CachingBackendContextResolver` | `commonMain` | moved once both parameters became ports. It had no OkHttp of its own; it followed `SupabaseAccountClient` only because it named it. Later split into the interface and the caching implementation, because the three account repositories that depend on it cannot be tested against a final class |
 * | `SecureTokenStore` | `androidMain` | Android keystore crypto and `javax.crypto`. It is the `SecretStore` implementation |
 * | the six `CrispyBackend*` files, `SupabaseAccountClient` | `androidMain` | OkHttp and `org.json` |
 *
 * A port retype frees a *file* only when that file's own body is already clean,
 * so these moves are measured in files rather than in the count of call sites
 * retyped. `BackendContextResolver` needed one parameter changed; a repository
 * named in seventeen places may still be pinned by its own `Context`.
 */
kotlin {
    jvmToolchain(21)

    android {
        namespace = "com.crispy.tv.backend"
        compileSdk = 37
        minSdk = 26
        withHostTest {}
    }

    jvm("desktop")

    // Compile-only verification target, never shipped and never run. See
    // :android:core-domain for why it exists and why the import-based purity gate
    // cannot substitute for it.
    linuxX64()

    iosArm64()
    iosSimulatorArm64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(project(":android:core-domain"))

            // The Android implementations are deliberately *not* here. `Session`,
            // `ActiveProfileStore` and `AiInsightsModels` need only the contracts,
            // and depending on `:android:platform-android` from `commonMain` would
            // reintroduce exactly the platform leak the interfaces exist to remove.
            api(project(":android:platform-core"))

            api(libs.coroutines.core)
        }

        androidMain.dependencies {
            // The Android half of platform-core's contracts, for `SecureTokenStore`.
            api(project(":android:platform-android"))
            implementation(project(":android:network"))

            implementation(libs.coroutines.android)
        }

        // `commonTest`, not `androidHostTest`. `BackendContextResolver` reached
        // `commonMain` in the same commit that declared `AccountApi`, and it is the
        // one piece of this module with real logic that has no Android type left in
        // its signature -- it takes two interfaces and a `KeyValueStore`-backed
        // `ActiveProfileStore`. Putting the test in `commonTest` means it also runs on
        // the Apple targets, which is the whole point of having made the class
        // portable: a test that only runs on Android would not notice if the class
        // reached for a JVM API again.
        //
        // `withHostTest {}` above is what keeps that choice honest rather than
        // accidental. Without it, a `commonTest` source set on a KMP module makes
        // AGP print "android host tests are not enabled" -- i.e. the tests would run
        // on desktop and Apple but not on Android, which is the platform the module
        // actually ships to. :android:core-domain declares the same block.
        commonTest.dependencies {
            implementation(kotlin("test"))

            // `runTest`, so the `Mutex` in `BackendContextResolver` is exercised the
            // way production runs it rather than on `runBlocking`.
            implementation(libs.coroutines.test)
        }

        // Robolectric is here for `org.json` itself, which is the first time in
        // this repository it has been needed for a framework class other than a
        // `Context`. :app's host tests want a `Context` to hand to a
        // composition root; this one wants a *real* `org.json.JSONObject`.
        //
        // The reason it cannot be `commonTest`, and cannot be a plain JVM test,
        // is the whole point of this source set. `org.json` is a class of the
        // Android platform, supplied by `android.jar`, and there are two
        // implementations of it:
        //
        //   * the platform's (AOSP `libcore/json`), which is what the app ships
        //     and what Robolectric loads out of `android-all`, and
        //   * `org.json:json`, a reference implementation on Maven Central that
        //     looks interchangeable and is not.
        //
        // They disagree on the type of a *fractional* number: AOSP hands back
        // a `Double`, `org.json:json` a `BigDecimal`, while agreeing on every
        // whole number. A cast to `Double` therefore works on every device this
        // app ships to and fails against the artifact, and the reverse for
        // `BigDecimal` -- there is no answer that is right on both. Written
        // against `org.json:json`, the artifact this repository already depends
        // on in :android:plugins and the obvious thing to add here, this suite
        // would describe an `org.json` no device has.
        //
        // The first draft of this comment claimed a different divergence, on
        // `optString` of a JSON null, and said both had been measured. Neither
        // had: the claim was derived by reading AOSP's `JSON.toString` in
        // isolation and `optString` itself was never checked. A mutation run
        // refuted it -- see `JsonAccessorPolicyHostTest` and AGENTS.md.
        //
        // `@Config(manifest = Config.NONE)` is right here and
        // `isIncludeAndroidResources` is not: no view is inflated and no
        // resource is read, because `android-all` supplies `org.json` whether or
        // not there is a manifest.
        getByName("androidHostTest").dependencies {
            implementation(libs.robolectric)
        }
    }
}
