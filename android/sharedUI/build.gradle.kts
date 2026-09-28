plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

kotlin {
    jvmToolchain(21)

    // `androidLibrary { }`, NOT `android { }` as used by :core-domain.
    // Both create a target, but org.jetbrains.compose wires itself to
    // `androidLibrary` specifically. With `android { }` the compose dependencies
    // resolve onto `androidCompileClasspath` and are then invisible to the
    // Kotlin compiler, which fails with "Unresolved reference 'org.jetbrains.compose'".
    // This is the shape JetBrains documents for a sharedUI module.
    androidLibrary {
        namespace = "com.crispy.tv.ui.shared"
        compileSdk = 37
        minSdk = 26
        androidResources {
            enable = true
        }
    }

    jvm("desktop")

    // No linuxX64(), unlike the pure-Kotlin KMP modules. Compose Multiplatform
    // publishes no linuxX64 artifacts; its targets are Android, iOS and Desktop.

    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "CrispyUI"
            isStatic = true
        }
    }

    sourceSets {
        // `api`, not `implementation`: this module's public surface *is* Compose --
        // every exported declaration is a `@Composable` function whose signature
        // names `Modifier`, and callers in `:app` call them directly. With
        // `implementation` the Compose types would be absent from a consumer's
        // compile classpath, so consumers would be forced to re-declare the very
        // versions this module already picked. One module owns the Compose
        // versions; see the comment on `composeMaterial3` in the version catalog.
        commonMain.dependencies {
            api(compose.runtime)
            api(compose.foundation)
            api(compose.ui)
            api(compose.components.resources)

            // Deliberately NOT `compose.material3`. The alias tracks the latest
            // stable Material3 (1.4.0), which has no Expressive. This explicit
            // coordinate resolves to androidx material3 on Android and the
            // JetBrains fork on desktop/iOS, so one declaration covers all four
            // targets and there is only ever one material3 on the classpath.
            api(libs.compose.material3)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
