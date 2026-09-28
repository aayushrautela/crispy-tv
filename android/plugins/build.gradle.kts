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

    // No flavour axis, and it no longer needs one. This module *is* the QuickJS
    // plugin runtime: its whole `sideload` source set is the real implementation
    // and there is nothing in `store` to implement. That `store` source set held
    // a single line -- `internal val PluginsRuntimeSupported = false` -- which
    // nothing read, and it was never compiled, because :androidApp declares
    // `:android:plugins` as `sideloadImplementation` and so a store variant never
    // puts this module on its classpath at all. An unreachable stub is worse than
    // no stub: it reads as a supported configuration.
    //
    // The engine is now excluded the same structural way as
    // :android:torrent-engine and :android:youtube-extractor, by being a module
    // only the sideload variant depends on.

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

    implementation(libs.quickjs.kt)
    implementation(libs.jsoup)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(project(":android:addons"))
    implementation(project(":android:player"))

    testImplementation(kotlin("test"))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.org.json)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.quickjs.kt.jvm)

    configurations.matching {
        it.name.startsWith("test") &&
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
