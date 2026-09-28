plugins {
    alias(libs.plugins.kotlin.jvm)
    // Compose Multiplatform 1.6.10+ requires the standalone Kotlin Compose
    // compiler plugin; applying the CMP plugin without it fails at configuration
    // time with a message that reads like a version problem and is not one.
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvmToolchain(21)
}

/**
 * The desktop entry point. One module produces Windows, macOS and Linux: they
 * all run on the JVM, so there is no reason to have three.
 *
 * ## What this is for
 *
 * It exists to prove the seam on a developer's own machine, in seconds, rather
 * than discovering on a CI runner twenty minutes later whether Compose
 * Multiplatform code can actually render off-Android. See §3 of
 * kmp-migration-plan.md for why the sequencing is deliberate.
 *
 * So it renders the real design system from `:android:sharedUI` over the real
 * domain logic from `:android:core-domain`. Nothing here is a mock of the app:
 * the theme, the spacing scale, the cards and the `planContinueWatching` call
 * are all production code. What is new is only the arrangement -- the screens
 * themselves migrate into `:app`'s `appUi` source set in Phase 4, and this
 * module then renders those instead.
 *
 * ## Why it cannot render `:app`'s screens yet
 *
 * `:app` is written against Material3 Expressive (`androidx.compose.material3`
 * 1.5.0-alpha26) for `LoadingIndicator`, `MaterialShapes` and
 * `rememberBottomSheetState`. That version publishes no `material3-desktop`
 * artifact -- only the `android` one -- so a Compose Multiplatform module that
 * declares it cannot resolve for desktop. `:app` therefore pins AndroidX Compose
 * explicitly and does not apply `org.jetbrains.compose` at all.
 *
 * This is the single most consequential finding of the migration and it is the
 * gate on Phase 4: before any screen can move into `appUi`, Material3 Expressive
 * has to be dropped or replaced with something that exists for desktop and iOS.
 * `:app`'s own comments say the same thing at the dependency block.
 *
 * `:android:sharedUI` is the module that *does* compile for desktop today
 * (Compose Multiplatform 1.11.1 / material3 1.4.0), which is why the design
 * system and not a screen is what this renders.
 */

dependencies {
    // The design system: theme, spacing scale, typography. Compose Multiplatform
    // 1.11.x, and it already compiles for desktop in check-local.sh.
    implementation(project(":android:sharedUI"))

    // The real domain logic. Pure Kotlin with no Compose, so it resolves
    // identically on every target.
    implementation(project(":android:core-domain"))

    // Compose Multiplatform accessors rather than AndroidX coordinates, because
    // this module is desktop-only and does not have the Material3 Expressive
    // problem described above.
    implementation(compose.runtime)
    implementation(compose.foundation)
    implementation(compose.material3)
    implementation(compose.ui)

    implementation(compose.desktop.currentOs)

    // SeedData reads a real contract fixture off disk to seed the window.
    implementation(libs.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(compose.desktop.uiTestJUnit4)
}

/**
 * Generates a fontconfig file pointing at the Roboto bundled in `test-fonts/`.
 *
 * Skia resolves typefaces through fontconfig, so a host with no system fonts
 * fails a Compose render with `IllegalStateException: Could not load font` --
 * which says nothing about whether the seam works, and looks exactly like a
 * real failure. The same problem and the same remedy already exist for
 * `:android:androidApp`, where Java2D draws Roborazzi's diff label; the font
 * lives at the repository root because both modules need it.
 *
 * Generated rather than committed because fontconfig requires an absolute
 * `<dir>` and the checkout path differs per machine.
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
            """.trimIndent(),
        )
    }
}

tasks.withType<Test>().configureEach {
    dependsOn(generateTestFontsConfig)
    useJUnitPlatform()

    // Compose Desktop's test environment goes through Skia, which needs a
    // software rasteriser on a headless machine. Without this the test dies with
    // a native-load error rather than an assertion, which is a misleading way to
    // learn that the render worked.
    systemProperty("java.awt.headless", "true")

    // Gives Skia a font to resolve. See generateTestFontsConfig above.
    environment("FONTCONFIG_FILE", testFontsConfig.get().asFile.absolutePath)

    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
