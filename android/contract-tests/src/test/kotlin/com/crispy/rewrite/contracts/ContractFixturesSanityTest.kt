package com.crispy.rewrite.contracts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

class ContractFixturesSanityTest {
    private val json = Json { ignoreUnknownKeys = false }
    private val requiredKeys = setOf("contract_version", "suite", "case_id")

    @Test
    fun fixtureDirectoryExists() {
        val fixturesRoot = repositoryRoot().resolve("contracts/fixtures")
        assertTrue(Files.exists(fixturesRoot), "Expected fixtures directory at $fixturesRoot")
    }

    @Test
    fun allFixturesHaveMinimumRequiredFields() {
        val fixturesRoot = repositoryRoot().resolve("contracts/fixtures")
        val fixtures = fixtureFiles(fixturesRoot)

        assertTrue(fixtures.isNotEmpty(), "No contract fixture files found at $fixturesRoot")

        fixtures.forEach { path ->
            val element = runCatching { json.parseToJsonElement(Files.readString(path)) }
                .getOrElse { error ->
                    fail("Invalid JSON in ${path.toDisplayPath()}: ${error.message}")
                }

            val rootObject = element as? JsonObject
                ?: fail("Fixture root must be a JSON object in ${path.toDisplayPath()}")

            requiredKeys.forEach { key ->
                if (!rootObject.containsKey(key)) {
                    fail("Missing required key '$key' in ${path.toDisplayPath()}")
                }
            }

            val contractVersion = rootObject["contract_version"]?.jsonPrimitive?.int
                ?: fail("contract_version must be an integer in ${path.toDisplayPath()}")

            assertTrue(contractVersion > 0, "contract_version must be > 0 in ${path.toDisplayPath()}")
        }
    }

    private fun fixtureFiles(root: Path): List<Path> {
        if (!Files.exists(root)) {
            return emptyList()
        }

        Files.walk(root).use { stream ->
            return stream
                .filter { Files.isRegularFile(it) && it.toString().endsWith(".json") }
                .sorted()
                .toList()
        }
    }

    private fun repositoryRoot(): Path {
        var current = Paths.get("").toAbsolutePath()

        while (true) {
            if (Files.exists(current.resolve("settings.gradle.kts"))) {
                return current
            }

            val parent = current.parent ?: break
            current = parent
        }

        error("Could not find repository root containing settings.gradle.kts")
    }

    private fun Path.toDisplayPath(): String {
        return repositoryRoot().relativize(this).toString()
    }
}
