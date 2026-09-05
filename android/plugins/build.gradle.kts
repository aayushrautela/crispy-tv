plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    "fossImplementation"("io.github.dokar3:quickjs-kt:1.0.14")
    "fossImplementation"("org.jsoup:jsoup:1.18.3")
    "fossImplementation"("com.squareup.okhttp3:okhttp:5.5.0")
    "fossImplementation"("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    "fossImplementation"(project(":android:addons"))
    "fossImplementation"(project(":android:player"))

    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testImplementation("org.json:json:20240303")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    "testFossImplementation"("io.github.dokar3:quickjs-kt-jvm:1.0.14")

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
