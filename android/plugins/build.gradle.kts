plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.crispy.tv.plugins"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("play") {
            dimension = "distribution"
        }
        create("foss") {
            dimension = "distribution"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    testOptions {
        unitTests {
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.coroutines.android)

    "fossImplementation"(libs.quickjs.kt)
    "fossImplementation"(libs.jsoup)
    "fossImplementation"(libs.okhttp)
    "fossImplementation"(libs.serialization.json)
    "fossImplementation"(project(":android:addons"))
    "fossImplementation"(project(":android:player"))

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.org.json)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    "testFossImplementation"(libs.quickjs.kt.jvm)

    configurations.matching {
        it.name.startsWith("testFoss") &&
            (it.name.endsWith("CompileClasspath") || it.name.endsWith("RuntimeClasspath"))
    }.configureEach {
        resolutionStrategy.dependencySubstitution {
            substitute(module("io.github.dokar3:quickjs-kt"))
                .using(module("io.github.dokar3:quickjs-kt-jvm:1.0.14"))
                .because("Desktop JVM tests cannot load the Android native library")
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
