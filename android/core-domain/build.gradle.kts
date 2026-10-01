import java.io.File

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

// ---------------------------------------------------------------------------
// Contract fixtures as generated Kotlin source.
//
// The contract suite used to be the separate `:android:contract-tests` JVM
// module and read `contracts/fixtures/**/*.json` off disk with java.nio.file.
// Moving it into this module's `commonTest` is what makes it run on desktop and
// the Apple targets as well, and `java.nio.file` is exactly what cannot make
// that jump: it does not exist on Kotlin/Native, and on every other target the
// path differs (the old code walked up from the working directory looking for
// `settings.gradle.kts`, so it only worked when Gradle was invoked from the
// expected place).
//
// Generating the fixtures into source removes the path problem entirely and
// makes the tests hermetic: the inputs are part of the compilation, so a fixture
// edit cannot reach a test run without a rebuild.
//
// `scripts/validate_contracts.py` remains the authority on fixture *validity*
// (it checks the JSON Schemas). This task is only concerned with getting the
// bytes into a form the tests can read on any target.
// ---------------------------------------------------------------------------

val contractFixturesDir = rootProject.layout.projectDirectory.dir("contracts/fixtures")
val generatedContractFixturesDir = layout.buildDirectory.dir("generated/contractFixtures/kotlin")

val generateContractFixtures by tasks.registering {
    val fixturesDirectory = contractFixturesDir
    val outputDirectory = generatedContractFixturesDir
    val templates = layout.projectDirectory.dir("src/commonTest/kotlin/com/crispy/tv/contracts")
    val templateFile = templates.file("ContractFixtures.kt.in")

    inputs.dir(fixturesDirectory).withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(templateFile)
    outputs.dir(outputDirectory)

    doLast {
        val fixturesRoot = fixturesDirectory.asFile
        require(fixturesRoot.isDirectory) { "Fixture directory missing: $fixturesRoot" }

        // Sorted by relative path so the generated file is byte-identical for
        // identical inputs. A generated source file that reshuffles itself
        // between machines turns every build into a spurious diff.
        val files = fixturesRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".json") }
            .sortedBy { it.relativeTo(fixturesRoot).invariantSeparatorsPath }
            .toList()

        require(files.isNotEmpty()) { "No contract fixtures found under $fixturesRoot" }

        // Two different groupings, and the distinction is load-bearing: a suite is
        // the first path segment (`player_machine`), while a version directory is
        // the immediate parent (`player_machine/v2`). `PlayerMachineContractTest`
        // pins its version explicitly, so the generated object has to be able to
        // answer for both.
        val relativePaths = files.map { it.relativeTo(fixturesRoot).invariantSeparatorsPath }
        val suites = relativePaths.map { it.substringBefore('/') }.distinct().sorted()
        val versionDirectories = relativePaths.map { it.substringBeforeLast('/') }.distinct().sorted()

        fun kotlinString(value: String): String = buildString {
            append('"')
            value.forEach { char ->
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '$' -> append("\\$")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
            append('"')
        }

        val fixtureEntries = files.joinToString(",\n") { file ->
            val relative = file.relativeTo(fixturesRoot).invariantSeparatorsPath
            "    ContractFixture(${kotlinString(relative)}, ${kotlinString(file.readText())})"
        }

        val generated = templateFile.asFile.readText()
            .replace("__SUITES__", suites.joinToString(",\n        ") { kotlinString(it) })
            .replace("__VERSION_DIRECTORIES__", versionDirectories.joinToString(",\n        ") { kotlinString(it) })
            .replace("__FIXTURES__", fixtureEntries)

        val target = outputDirectory.get().asFile.resolve("com/crispy/tv/contracts/ContractFixtures.kt")
        target.parentFile.mkdirs()
        target.writeText(generated)

        logger.lifecycle(
            "generateContractFixtures: ${files.size} fixtures in ${suites.size} suites " +
                "(${versionDirectories.size} version directories) -> " +
                target.relativeTo(rootProject.projectDir),
        )
    }
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
        commonTest {
            // The contract suite lives here rather than in a separate module, so
            // it runs on every target this module declares. The generated
            // fixtures are wired into the *test* compilation only -- main code
            // has no business embedding test inputs.
            kotlin.srcDir(generateContractFixtures)
            dependencies {
                implementation(kotlin("test"))
                // Only the contract tests need a JSON parser. core-domain's own
                // main code does not use kotlinx.serialization at all, so this
                // stays a test dependency.
                implementation(libs.serialization.json)
            }
        }

        // `withHostTest {}` above created the `androidHostTest` source set and its
        // `testAndroidHostTest` task, but nothing had ever been declared there, so
        // the task ran zero tests and the gate in AGENTS.md reported a pass over an
        // empty compilation. **A task that compiles no test is a green build log
        // and no evidence** -- the same failure as a stubs variant satisfying a
        // dependency declaration without satisfying a compile.
        //
        // It has to be `getByName(...)` and not `androidHostTest.dependencies {}`,
        // and it has to be **inside this `sourceSets { }` block**: written inside
        // `withHostTest {}` it fails to compile with a receiver type mismatch,
        // because the receiver there is the Android library extension rather than
        // a `NamedDomainObjectCollection`. `:app`'s block is the working
        // reference, and its own comment says the same thing.
        //
        // Robolectric is here for exactly one class: `android.net.Uri`. The
        // allow-list of `encodeUriComponent` can only be settled against the real
        // platform implementation, and **the platform artifact cannot tell us what
        // it does** -- `javap -c` on `android.jar` returns `ldc // String Stub!` for
        // `Uri.encode`, because the SDK ships a stub jar with no method bodies. The
        // real implementation is in Robolectric's `android-all` under `~/.m2`.
        // No `androidx.test.core` and no `Context`: `Uri.encode` is a static pure
        // function over a String, which is the case where Robolectric is
        // trustworthy, so this block is deliberately one line.
        getByName("androidHostTest").dependencies {
            implementation(libs.robolectric)
        }
    }
}

// `kotlin.srcDir(generateContractFixtures)` gives the Kotlin *compile* tasks their
// dependency and input tracking, but AGP's lint tasks read the source directories
// themselves and never inherit it. Gradle's validation catches that as
// "Property has implicit dependency ... build/generated/contractFixtures/kotlin",
// and it fires on both `generate*LintModel` and `lintAnalyze*`.
//
// This matters more than a normal ordering fix: the bug only appears when the
// generated directory is absent, so whether a run fails depends on what the
// previous run left behind. That is the worst class of build failure -- it passes
// locally and fails in CI, or the reverse. Matched on the whole lint family
// rather than one task name, because two different task types needed it and a
// narrower filter silently fixed only one of them.
tasks.matching { it.name.contains("lint", ignoreCase = true) }.configureEach {
    dependsOn(generateContractFixtures)
}
