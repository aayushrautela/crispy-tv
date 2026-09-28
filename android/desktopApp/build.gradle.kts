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
 * ## The blocker that used to stop this, and how it was removed
 *
 * This module could not render `:app`'s screens because `:app` was written against
 * Material3 Expressive via `androidx.compose.material3:1.5.0-alpha26`, which
 * publishes no `material3-desktop` artifact -- only the `android` one -- so a
 * Compose Multiplatform module declaring it could not resolve for desktop. An
 * earlier revision of this comment concluded that Phase 4 therefore "has to drop or
 * replace Material3 Expressive", and `:app`'s own dependency comment said the same.
 *
 * **That was wrong**, and the cost of leaving it recorded was that a shipping design
 * feature was about to be deleted to work around a version pin. Expressive is
 * supported on desktop and iOS. `org.jetbrains.compose.material3:material3` is a thin
 * alias that delegates to `androidx.compose.material3:material3-android` on Android
 * and ships the real implementation -- `LoadingIndicator`, `MaterialShapes` and all
 * -- on desktop and iOS. So there is exactly one material3 on the classpath, the
 * `androidx.compose.material3` package and imports are identical on every target, and
 * nothing had to be dropped.
 *
 * The `compose.material3` alias could not be used because it deliberately tracks the
 * latest *stable* Material3 (1.4.0), which has no Expressive; declaring the
 * coordinate explicitly is the whole fix. See the comment on `composeMaterial3` in
 * the version catalog for the version choice, and why the one nearby version that
 * would have moved the whole Compose stack *backwards* was rejected.
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
    // Compose Multiplatform accessors for runtime/foundation/ui, which resolve
    // identically on every target. material3 is NOT taken from an accessor: the
    // `compose.material3` alias tracks the latest *stable* Material3 (1.4.0) and
    // has no Expressive, and Gradle now deprecates it outright. It comes from
    // `:android:sharedUI`, which declares the one shared coordinate as `api`.
    implementation(compose.runtime)
    implementation(compose.foundation)
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
