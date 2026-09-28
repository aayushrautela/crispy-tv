plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

/**
 * The Crispy backend client, the Supabase account client, and the auth/profile
 * stores.
 *
 * The largest Phase 2 module at 3,422 lines, and the one where the honest split is
 * narrow. Eight of its twelve files are HTTP or JSON adapters over
 * `CrispyBackendClient`, and they stay in `androidMain` for the same two reasons
 * documented on `:android:network` and `:android:watchhistory`: OkHttp and
 * `org.json` have no Kotlin/Native artifact, and replacing `org.json` with
 * `kotlinx.serialization` is a behaviour change on the parsing boundary, not
 * plumbing.
 *
 * | file | where | why |
 * |---|---|---|
 * | `Session` | `commonMain` | a data class, portable as-is |
 * | `ActiveProfileStore` | `commonMain` | `KeyValueStore` only, once `:android:platform-core` is a `commonMain` dependency; the `Context` is gone |
 * | `BackendContextResolver` | `commonMain` | coroutines only, once `ActiveProfileStore` and `SupabaseAccountClient` are; `SupabaseAccountClient` is OkHttp, so this follows it rather than leading it |
 * | `AiInsightsModels` | `androidMain` | names `CrispyBackendClient.ResponsiveImageSet`. That type is three nullable strings and would itself be portable, but it is one of ~40 nested types inside the client; pulling out that single one so a 57-line file looks portable would be arbitrary. It moves when the client does |
 * | `SecureTokenStore` | `androidMain` | Android keystore crypto and `javax.crypto`. It is the `SecretStore` implementation |
 * | the six `CrispyBackend*` files, `SupabaseAccountClient` | `androidMain` | OkHttp and `org.json` |
 *
 * The dependency direction matters for the migration: `BackendContextResolver` and
 * `AiInsightsModels` are *consumers* of the adapters, so they cannot move until the
 * adapters do. They are listed with their blocker rather than moved optimistically
 * and left failing to compile.
 */
kotlin {
    jvmToolchain(21)

    android {
        namespace = "com.crispy.tv.backend"
        compileSdk = 37
        minSdk = 26
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
    }
}
