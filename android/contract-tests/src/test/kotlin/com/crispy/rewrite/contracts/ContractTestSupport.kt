package com.crispy.rewrite.contracts

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolute
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.pathString
import kotlin.io.path.readText
import kotlin.streams.toList

internal object ContractTestSupport {
    val json = Json { ignoreUnknownKeys = false }

    fun fixtureFiles(subdirectory: String): List<Path> {
        val fixtureDir = repositoryRoot().resolve("contracts/fixtures").resolve(subdirectory)
        require(fixtureDir.exists() && fixtureDir.isDirectory()) {
            "Fixture directory missing: $fixtureDir"
        }

        return Files.walk(fixtureDir).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.name.endsWith(".json") }
                .sorted()
                .toList()
        }
    }

    fun parseFixture(path: Path): JsonObject {
        return json.parseToJsonElement(path.readText()).jsonObject
    }

    private fun repositoryRoot(): Path {
        var cursor = Path.of(".").toAbsolutePath().normalize()
        repeat(8) {
            if (cursor.resolve("settings.gradle.kts").exists()) {
                return cursor
            }
            cursor = cursor.parent ?: return@repeat
        }
        error("Unable to locate repository root from ${Path.of(".").absolute().pathString}")
    }
}

internal fun JsonObject.requireString(key: String, path: Path): String {
    val primitive = this[key] as? JsonPrimitive
        ?: error("${path.toDisplayPath()}: missing string '$key'")
    return primitive.content
}

internal fun JsonObject.requireBoolean(key: String, path: Path): Boolean {
    val primitive = this[key] as? JsonPrimitive
        ?: error("${path.toDisplayPath()}: missing boolean '$key'")
    return primitive.booleanOrNull
        ?: error("${path.toDisplayPath()}: '$key' must be boolean")
}

internal fun JsonObject.requireInt(key: String, path: Path): Int {
    val primitive = this[key] as? JsonPrimitive
        ?: error("${path.toDisplayPath()}: missing integer '$key'")
    return primitive.intOrNull
        ?: error("${path.toDisplayPath()}: '$key' must be integer")
}

internal fun JsonObject.optionalInt(key: String, path: Path): Int? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive
        ?: error("${path.toDisplayPath()}: '$key' must be integer or null")
    return primitive.intOrNull
        ?: error("${path.toDisplayPath()}: '$key' must be integer or null")
}

internal fun JsonObject.optionalString(key: String, path: Path): String? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive
        ?: error("${path.toDisplayPath()}: '$key' must be string or null")
    return primitive.content
}

internal fun JsonObject.requireJsonObject(key: String, path: Path): JsonObject {
    return this[key]?.jsonObject
        ?: error("${path.toDisplayPath()}: missing object '$key'")
}

internal fun JsonObject.optionalJsonObject(key: String): JsonObject? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    return value.jsonObject
}

internal fun JsonObject.requireJsonArray(key: String, path: Path): JsonArray {
    return this[key]?.jsonArray
        ?: error("${path.toDisplayPath()}: missing array '$key'")
}

internal fun JsonArray.toStringList(path: Path): List<String> {
    return mapIndexed { index, value ->
        val primitive = value as? JsonPrimitive
            ?: error("${path.toDisplayPath()}: expected string at index $index")
        primitive.content
    }
}

internal fun JsonArray.toIntList(path: Path): List<Int> {
    return mapIndexed { index, value ->
        val primitive = value as? JsonPrimitive
            ?: error("${path.toDisplayPath()}: expected integer at index $index")
        primitive.intOrNull
            ?: error("${path.toDisplayPath()}: expected integer at index $index")
    }
}

internal fun Path.toDisplayPath(): String = toString().replace('\\', '/')
