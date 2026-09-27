plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.crispy.tv.network"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("store") {
            dimension = "distribution"
        }
        create("sideload") {
            dimension = "distribution"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(libs.coroutines.android)

    api(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)

    // JitPack git-tag version, not a normal release — left out of the catalog.
    "sideloadImplementation"("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
}
