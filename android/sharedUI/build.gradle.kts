import org.jetbrains.compose.resources.ResourcesExtension

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
}

compose.resources {
    // `Res` is `internal` by default, which is correct for a module that owns and
    // uses its own resources. These resources are owned here but consumed by
    // `:app`, `:tv` and `:androidApp`, so the class has to be public. This is the
    // supported switch; the alternative circulating upstream is a `doLast` block
    // that rewrites `internal object Res` in the generated file, which patches
    // generated output and is labelled a temporary workaround.
    publicResClass = true

    // Default would be `crispy_rewrite.android.sharedui.generated.resources`, which
    // is derived from the Gradle coordinates and leaks the build system into every
    // import. Callers are UI code; `com.crispy.tv.ui.resources` is what that is.
    packageOfResClass = "com.crispy.tv.ui.resources"
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
