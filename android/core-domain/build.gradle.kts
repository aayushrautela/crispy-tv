plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    jvmToolchain(21)

    android {
        namespace = "com.crispy.tv.domain"
        compileSdk = 37
        minSdk = 26
        withHostTest {}
    }

    jvm("desktop")

    // Compile-only verification target. Kotlin/Native enforces exactly the same
    // "no JVM API" rule as the Apple targets, and linuxX64 is the one Native
    // target that builds on a Linux host. So `compileKotlinLinuxX64` in
    // check-local.sh catches platform leakage in seconds instead of waiting
    // for macOS CI. It is never shipped and never run.
    linuxX64()

    // Declared, not merely intended. `commonMain` carries no java.* / android.*
    // imports, so these compile from the same sources as Android and desktop.
    // Apple targets cannot be built on Linux, so they are proven by the
    // .github/workflows/apple.yml rather than by check-local.sh.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
