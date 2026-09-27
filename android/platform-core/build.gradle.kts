plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

kotlin {
    android {
        namespace = "com.crispy.tv.platform"
        compileSdk = 37
        minSdk = 26
    }

    jvm("desktop")

    // Compile-only verification target; see the note in :android:core-domain.
    // It applies the same "no JVM API" rule as the Apple targets and is the
    // one Native target that builds on a Linux host.
    linuxX64()

    iosArm64()
    iosSimulatorArm64()
}
