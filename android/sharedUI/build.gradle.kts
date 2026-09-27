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
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
