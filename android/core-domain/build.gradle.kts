plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    jvmToolchain(17)

    android {
        namespace = "com.crispy.tv.domain"
        compileSdk = 37
        minSdk = 26
        withHostTest {}
    }

    jvm("desktop")

    // Declared, not merely intended. `commonMain` carries no java.* / android.*
    // imports, so these compile from the same sources as Android and desktop.
    // Apple targets cannot be built on Linux, so they are proven by the
    // `apple` job in .github/workflows/apple-ci.yml rather than by check-local.sh.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
