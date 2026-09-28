package com.crispy.tv.contracts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Shared fixture access and JSON field extraction for the contract suite.
 *
 * ## Why there is no `Path` here any more
 *
 * Every helper used to take a `java.nio.file.Path` purely so that a failure
 * message could name the offending fixture. That type is what made this suite
 * JVM-only: `java.nio.file` does not exist on Kotlin/Native, and on the other
 * targets the fixture root is a different path (an Android unit test starts in
 * the module directory, a desktop test in the root project, an iOS simulator
 * test in a container path that is neither). The old code hid that behind a
 * `repositoryRoot()` walk up from the working directory looking for
 * `settings.gradle.kts`.
 *
 * A [ContractFixture] carries its own repository-relative path and renders as
 * that path in [toString], so it does the job a `Path` was doing while working
 * identically on every target. See `ContractFixtures.kt.in` for why the fixture
 * bytes are compiled in rather than read.
 */
internal object ContractTestSupport {
    val json = Json { ignoreUnknownKeys = false }

    fun fixtureFiles(suite: String): List<ContractFixture> = ContractFixtures.inSuite(suite)

    fun parseFixture(fixture: ContractFixture): JsonObject {
        return runCatching { json.parseToJsonElement(fixture.json).jsonObject }
            .getOrElse { error -> error("Invalid JSON in $fixture: ${error.message}") }
    }
}

internal fun JsonObject.requireString(key: String, path: ContractFixture): String {
    val primitive = this[key] as? JsonPrimitive
        ?: error("$path: missing string '$key'")
    return primitive.content
}

internal fun JsonObject.requireBoolean(key: String, path: ContractFixture): Boolean {
    val primitive = this[key] as? JsonPrimitive
        ?: error("$path: missing boolean '$key'")
    return primitive.booleanOrNull
        ?: error("$path: '$key' must be boolean")
}

internal fun JsonObject.requireInt(key: String, path: ContractFixture): Int {
    val primitive = this[key] as? JsonPrimitive
        ?: error("$path: missing integer '$key'")
    return primitive.intOrNull
        ?: error("$path: '$key' must be integer")
}

internal fun JsonObject.optionalInt(key: String, path: ContractFixture): Int? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive
        ?: error("$path: '$key' must be integer or null")
    return primitive.intOrNull
        ?: error("$path: '$key' must be integer or null")
}

internal fun JsonObject.optionalBoolean(key: String, path: ContractFixture): Boolean? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive
        ?: error("$path: '$key' must be boolean or null")
    return primitive.booleanOrNull
        ?: error("$path: '$key' must be boolean or null")
}

internal fun JsonObject.optionalString(key: String, path: ContractFixture): String? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive
        ?: error("$path: '$key' must be string or null")
    return primitive.content
}

internal fun JsonObject.optionalLong(key: String, path: ContractFixture): Long? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive
        ?: error("$path: '$key' must be integer or null")
    return primitive.longOrNull
        ?: error("$path: '$key' must be integer or null")
}

internal fun JsonObject.optionalJsonArray(key: String, path: ContractFixture): JsonArray? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    return value as? JsonArray
        ?: error("$path: '$key' must be array or null")
}

internal fun JsonObject.requireJsonObject(key: String, path: ContractFixture): JsonObject {
    return this[key]?.jsonObject
        ?: error("$path: missing object '$key'")
}

internal fun JsonObject.optionalJsonObject(key: String, path: ContractFixture): JsonObject? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    return value as? JsonObject
        ?: error("$path: '$key' must be object or null")
}

internal fun JsonObject.requireJsonArray(key: String, path: ContractFixture): JsonArray {
    return this[key]?.jsonArray
        ?: error("$path: missing array '$key'")
}

internal fun JsonArray.toStringList(path: ContractFixture): List<String> {
    return mapIndexed { index, value ->
        val primitive = value as? JsonPrimitive
            ?: error("$path: expected string at index $index")
        primitive.content
    }
}

internal fun JsonArray.toIntList(path: ContractFixture): List<Int> {
    return mapIndexed { index, value ->
        val primitive = value as? JsonPrimitive
            ?: error("$path: expected integer at index $index")
        primitive.intOrNull
            ?: error("$path: expected integer at index $index")
    }
}
