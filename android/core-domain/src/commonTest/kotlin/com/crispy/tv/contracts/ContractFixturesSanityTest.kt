package com.crispy.tv.contracts

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Guards the fixtures themselves, independent of any suite's behaviour.
 *
 * This is the Kotlin half of fixture validation. `scripts/validate_contracts.py`
 * remains the authority on schema conformance — it checks every fixture against
 * `contracts/schemas/` and is the gate in both check-local.sh and CI. What this
 * covers is the property the Python script cannot: that the fixtures actually
 * made it into the compilation. A generator bug, a stale `build/` directory, or
 * a fixture added without rerunning the task would otherwise show up as a suite
 * silently running against 95 fixtures instead of 96 and nobody noticing.
 */
class ContractFixturesSanityTest {
    private val requiredKeys = setOf("contract_version", "suite", "case_id")

    @Test
    fun fixturesWereGenerated() {
        assertTrue(
            ContractFixtures.all.isNotEmpty(),
            "No contract fixtures were compiled in. Check generateContractFixtures.",
        )
    }

    @Test
    fun everySuiteHasExactlyOneVersionDirectory() {
        // The fixture layout is contracts/fixtures/<suite>/<version>/<case>.json and
        // every suite has exactly one version directory today.
        //
        // Enforcing it matters because `PlayerMachineContractTest` pins
        // "player_machine/v2" explicitly. If a second version ever appeared, that pin
        // would silently stop covering the new cases and the suite would report
        // success while testing nothing new. A second version is a spec revision and
        // should arrive as a failure here, not as a quiet change of coverage.
        ContractFixtures.suites.forEach { suite ->
            val versions = ContractFixtures.versionDirectories.filter { it.startsWith("$suite/") }
            assertEquals(
                1,
                versions.size,
                "Suite '$suite' has ${versions.size} version directories ($versions); " +
                    "expected exactly one",
            )
        }
    }

    @Test
    fun everyDeclaredSuiteHasFixtures() {
        ContractFixtures.suites.forEach { directory ->
            assertTrue(
                ContractFixtures.inSuite(directory).isNotEmpty(),
                "Suite '$directory' is declared but contains no fixtures",
            )
        }
    }

    @Test
    fun fixturePathsAreUniqueAndRelative() {
        val duplicates = ContractFixtures.all
            .groupBy { it.path }
            .filterValues { it.size > 1 }
            .keys
        assertTrue(duplicates.isEmpty(), "Duplicate fixture paths: $duplicates")

        ContractFixtures.all.forEach { fixture ->
            assertTrue(
                !fixture.path.startsWith("/") && !fixture.path.contains(".."),
                "Fixture path must be relative and free of '..': ${fixture.path}",
            )
            assertTrue(
                fixture.path.endsWith(".json"),
                "Fixture path must end in .json: ${fixture.path}",
            )
        }
    }

    @Test
    fun allFixturesHaveMinimumRequiredFields() {
        assertTrue(
            ContractFixtures.all.isNotEmpty(),
            "No contract fixtures were compiled in. Check generateContractFixtures.",
        )

        ContractFixtures.all.forEach { fixture ->
            val rootObject = runCatching { ContractTestSupport.parseFixture(fixture) }
                .getOrElse { error ->
                    fail("Invalid JSON in $fixture: ${error.message}")
                }

            requiredKeys.forEach { key ->
                if (!rootObject.containsKey(key)) {
                    fail("Missing required key '$key' in $fixture")
                }
            }

            val contractVersion = rootObject["contract_version"]?.jsonPrimitive?.int
                ?: fail("contract_version must be an integer in $fixture")

            assertTrue(contractVersion > 0, "contract_version must be > 0 in $fixture")
        }
    }

    /**
     * The `suite` field inside each fixture must match the directory it lives in.
     *
     * Suites select fixtures by directory, so a fixture whose `suite` disagrees
     * with its location would be exercised by one test while claiming to belong
     * to another. Nothing else in the suite would catch it: each test only reads
     * `suite` where it asserts on it, and several suites assert on nothing but
     * behaviour.
     */
    @Test
    fun fixtureSuiteMatchesItsDirectory() {
        ContractFixtures.all.forEach { fixture ->
            val suite = (ContractTestSupport.parseFixture(fixture)["suite"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.content
                ?: fail("suite must be a string in $fixture")
            assertTrue(
                suite == fixture.suite,
                "Fixture $fixture declares suite '$suite' but lives in '${fixture.suite}'",
            )
        }
    }
}
