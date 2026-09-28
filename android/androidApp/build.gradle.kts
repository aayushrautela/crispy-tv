plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * The Android entry point.
 *
 * Created by the Phase 1 split: everything application-shaped used to live in
 * `:app` alongside 159 source files. AGP 9 requires the entry point to be its
 * own module, and keeping it separate is also what lets `:app` become a Kotlin
 * Multiplatform library.
 *
 * What lives here, and why:
 *
 * | here                          | because                                                        |
 * |-------------------------------|----------------------------------------------------------------|
 * | `AndroidManifest.xml`, `res/` | the application, not the code                                   |
 * | `MainActivity`, `CrispyApplication` | entry points; `CrispyApplication` is also the only place that names a variant |
 * | `productFlavors { store, sideload }` | `:app` is single-variant, so the variant lives here      |
 * | `BuildDistributionComponents` | one per flavour source set, same name in each                  |
 * | signing, ProGuard, splits, packaging | ship-level concerns                                     |
 * | the screenshot tests          | they need the merged manifest and the real app theme            |
 *
 * What deliberately did *not* move here:
 *
 * - `buildConfig = true` and its `buildConfigField`s. Nothing references
 *   `BuildConfig` any more; the generated `AppConfig` in `:android:platform-core`
 *   replaced it (`86eaa9c2`). See :android:app for the same reasoning.
 * - `testInstrumentationRunner` and the `androidTestImplementation` deps. There
 *   are zero instrumentation sources in this project.
 */

/**
 * Golden-screenshot mode. Off by default (verify); pass -Proborazzi.record=true
 * to re-record the committed PNGs under src/test/screenshots.
 */
val roborazziRecord = providers.gradleProperty("roborazzi.record").orNull == "true"

/**
 * Generates a fontconfig file pointing at the font committed in
 * `src/test/fonts`, and points the unit-test JVM at it.
 *
 * Roborazzi draws a label onto its diff canvas with Java2D, so a *failing*
 * screenshot test needs a host font. Without this a bare container reports
 * `Fontconfig head is null` instead of the real difference. Generated rather
 * than committed because fontconfig requires an absolute <dir>.
 */
val testFontsConfig = layout.buildDirectory.file("test-fonts/fonts.conf")
val testFontsDir = rootProject.layout.projectDirectory.dir("test-fonts")

val generateTestFontsConfig by tasks.registering {
    val fontsDirectory = testFontsDir.asFile.absolutePath
    val outputFile = testFontsConfig
    inputs.dir(testFontsDir)
    outputs.file(testFontsConfig)
    doLast {
        val file = outputFile.get().asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            <?xml version="1.0" encoding="utf-8"?>
            <!DOCTYPE fontconfig SYSTEM "urn:fontconfig:fonts.dtd">
            <fontconfig>
              <dir>$fontsDirectory</dir>
              <cachedir>${file.parentFile.absolutePath}/cache</cachedir>
              <match target="pattern">
                <edit name="family" mode="append_last"><string>Roboto</string></edit>
              </match>
            </fontconfig>
            """.trimIndent()
        )
    }
}

val releaseKeystorePath = providers.gradleProperty("RELEASE_KEYSTORE_PATH").orNull
val releaseKeystorePassword = providers.gradleProperty("RELEASE_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.gradleProperty("RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.gradleProperty("RELEASE_KEY_PASSWORD").orNull

val debugKeystorePath = providers.gradleProperty("DEBUG_KEYSTORE_PATH").orNull
val debugKeystorePassword = providers.gradleProperty("DEBUG_KEYSTORE_PASSWORD").orNull
val debugKeyAlias = providers.gradleProperty("DEBUG_KEY_ALIAS").orNull
val debugKeyPassword = providers.gradleProperty("DEBUG_KEY_PASSWORD").orNull

android {
    namespace = "com.crispy.tv"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "com.crispy.tv"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("store") {
            dimension = "distribution"
        }
        create("sideload") {
            dimension = "distribution"
            versionNameSuffix = "-sideload"
        }
    }

    splits {
        abi {
            isEnable = project.hasProperty("buildSideloadApks")
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        val hasReleaseSigning =
            !releaseKeystorePath.isNullOrBlank() &&
                !releaseKeystorePassword.isNullOrBlank() &&
                !releaseKeyAlias.isNullOrBlank() &&
                !releaseKeyPassword.isNullOrBlank()

        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }

        val hasDebugSigning =
            !debugKeystorePath.isNullOrBlank() &&
                !debugKeystorePassword.isNullOrBlank() &&
                !debugKeyAlias.isNullOrBlank() &&
                !debugKeyPassword.isNullOrBlank()

        if (hasDebugSigning) {
            getByName("debug") {
                storeFile = file(debugKeystorePath!!)
                storePassword = debugKeystorePassword
                keyAlias = debugKeyAlias
                keyPassword = debugKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            // Robolectric needs the merged resources and manifest to inflate
            // themes, so the goldens render against the real app theme rather
            // than a stub. This is why the screenshot suite lives in the
            // application module and not in :app.
            isIncludeAndroidResources = true

            all {
                it.dependsOn(generateTestFontsConfig)

                // Roborazzi writes PNGs to disk and Robolectric reaches for
                // android-all jars; neither tolerates a narrow heap.
                it.maxHeapSize = "2g"
                it.systemProperty("robolectric.graphicsMode", "NATIVE")

                // Verify is the DEFAULT, so an ordinary test run is a rendering
                // gate. Recording is explicit (`-Proborazzi.record=true`) and
                // the resulting PNGs under src/test/screenshots are committed,
                // which is what makes a missing golden a build failure rather
                // than a silently accepted new baseline.
                it.systemProperty("roborazzi.test.record", roborazziRecord.toString())
                it.systemProperty("roborazzi.test.verify", (!roborazziRecord).toString())
                // Keep the rendered diff image. Roborazzi builds the diff canvas
                // on any mismatch regardless, so this only controls whether the
                // artefact is written -- which is what CI uploads.
                it.systemProperty("roborazzi.test.compare", (!roborazziRecord).toString())

                // Gives the diff canvas a real font, so a mismatch reports the
                // actual difference instead of dying in fontconfig.
                it.environment("FONTCONFIG_FILE", testFontsConfig.get().asFile.absolutePath)

                // Relative to the test JVM working directory, which Gradle
                // sets to the project dir. CI uploads this tree on failure.
                it.systemProperty("roborazzi.output.dir", "build/outputs/roborazzi")
            }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }

        jniLibs {
            useLegacyPackaging = true
            pickFirsts += setOf("**/libc++_shared.so")
        }
    }
}

/**
 * Store builds must not ship the sideload-only engines. The modules are simply
 * not declared for the store flavor, so this asserts the resolved graph rather
 * than trusting the build files to stay that way.
 *
 * This lives here rather than in :app because it reads `<variant>RuntimeClasspath`,
 * and the KMP library plugin is single-variant: only the module that declares
 * the flavours has those configurations.
 */
val storeExclusionForbidden = listOf(
    ":android:torrent-engine",
    ":android:plugins",
    ":android:youtube-extractor",
    "NewPipeExtractor",
    "quickjs-kt",
)

tasks.register("verifyStoreBuildExclusions") {
    group = "verification"
    description = "Asserts store variants exclude the torrent engine and plugin runtime."

    val storeVariants = listOf("storeDebug", "storeRelease")

    doLast {
        storeVariants.forEach { variant ->
            val configuration = configurations.findByName("${variant}RuntimeClasspath")
                ?: error("No ${variant}RuntimeClasspath configuration")
            val offenders = configuration.incoming.resolutionResult.allComponents
                .mapNotNull { it.id.displayName }
                .filter { id -> storeExclusionForbidden.any { id.contains(it) } }
                .distinct()
            check(offenders.isEmpty()) {
                "Store variant $variant must not contain: ${offenders.joinToString()}"
            }
        }
    }
}

tasks.register("verifyDistributionExclusions") {
    group = "verification"
    description = "Runs every distribution exclusion check."
    dependsOn("verifyStoreBuildExclusions")
}

dependencies {
    // :app is the KMP library holding every screen. :androidApp is a thin
    // entry point on top of it.
    implementation(project(":android:app"))

    // The sideload-only engines. Declared here, not in :app, so :app is
    // flavour-free -- which is the whole point of the split. NewPipeExtractor
    // comes in transitively through :android:youtube-extractor and is not
    // declared again here.
    "sideloadImplementation"(project(":android:plugins"))
    "sideloadImplementation"(project(":android:torrent-engine"))
    "sideloadImplementation"(project(":android:youtube-extractor"))

    // :app declares everything as `implementation`, so none of it leaks to this
    // module's compile classpath. These are the modules :androidApp names
    // directly, which is the whole list -- the entry point is thin by design, and
    // anything it does not reference stays out of here.
    implementation(project(":android:core-domain"))
    implementation(project(":android:platform-core"))
    implementation(project(":android:addons"))
    implementation(project(":android:backend"))
    implementation(project(":android:network"))
    implementation(project(":android:player"))
    implementation(project(":android:ui-assets"))
    // MainActivity applies CrispyRewriteTheme, so the entry point owns the
    // design system. :app resolves the same symbols through its own
    // `commonMain` dependency on this module -- there is one copy, not two.
    implementation(project(":android:sharedUI"))

    coreLibraryDesugaring(libs.desugar.jdk.libs.nio)

    // The Compose BOM. :androidApp is a plain Android module, so `platform()`
    // works here even though it is unavailable in a KMP source set. It supplies
    // versions for the versionless catalog entries below and keeps the app's own
    // Compose artifacts on the same versions :app compiles against.
    //
    // The BOM's material3 is 1.4.0 while :app pins 1.5.0-alpha26 for the
    // Material3 Expressive APIs. A declared version outranks a platform, so the
    // alpha wins in the merged graph -- which is what both the old single-module
    // build and the new two-module build produce.
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)

    // CrispyApplication builds the process-wide ImageLoader, so the app module
    // owns Coil configuration rather than leaving it implicit inside a library.
    // `SingletonImageLoader` is in the `coil` artifact; `coil-compose` used to
    // drag it in transitively when the application and the UI were one module.
    implementation(libs.coil.singleton)
    implementation(libs.coil.core)
    implementation(libs.coil.network.okhttp)

    // MainActivity reports jank for the frame metrics overlay.
    implementation(libs.metrics.performance)

    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Golden-screenshot tests. These run on a plain JVM (no emulator, no KVM),
    // which is the only way a rendering regression gets caught in CI on this
    // repository. See src/test/java/.../screenshot for the harness.
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.compose.ui.test)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
