package com.crispy.tv.contracts

import com.crispy.tv.domain.account.validateSignupProfile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class SignupProfileContractTest {
    @Test
    fun signupProfileFixtures() {
        ContractTestSupport.fixtureFiles("signup_profile").forEach { path ->
            val root = ContractTestSupport.parseFixture(path)
            assertEquals("signup_profile", root.requireString("suite", path), "Wrong suite in ${path.toDisplayPath()}")

            val input = root.requireJsonObject("input", path)
            val expected = root.requireJsonObject("expected", path)

            val rawName = input.requireString("raw_name", path)
            val rawLanguage = input.optionalString("raw_language", path)
            val rawRegion = input.optionalString("raw_region", path)
            val rawAvatarUrl = input.optionalString("raw_avatar_url", path)

            val result = validateSignupProfile(
                rawName = rawName,
                rawLanguage = rawLanguage,
                rawRegion = rawRegion,
                rawAvatarUrl = rawAvatarUrl,
            )

            val expectedComplete = expected.requireBoolean("is_complete", path)
            val expectedMissing = expected.optionalJsonArray("missing", path)?.map { any ->
                val primitive = any as? JsonPrimitive
                    ?: error("${path.toDisplayPath()}: missing entry must be string")
                primitive.content
            } ?: emptyList()
            val expectedName = expected.optionalString("normalized_name", path)
            val expectedLanguage = expected.optionalString("normalized_language", path)
            val expectedRegion = expected.optionalString("normalized_region", path)
            val expectedAvatarUrl = expected.optionalString("normalized_avatar_url", path)

            assertEquals(expectedComplete, result.isComplete, "isComplete mismatch in ${path.toDisplayPath()}")
            assertEquals(expectedMissing, result.missing, "missing mismatch in ${path.toDisplayPath()}")
            assertEquals(expectedName, result.normalizedName, "name mismatch in ${path.toDisplayPath()}")
            assertEquals(expectedLanguage, result.normalizedLanguage, "language mismatch in ${path.toDisplayPath()}")
            assertEquals(expectedRegion, result.normalizedRegion, "region mismatch in ${path.toDisplayPath()}")
            assertEquals(expectedAvatarUrl, result.normalizedAvatarUrl, "avatarUrl mismatch in ${path.toDisplayPath()}")
        }
    }
}

private fun JsonObject.optionalString(key: String, path: java.nio.file.Path): String? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive
        ?: error("${path.toDisplayPath()}: '$key' must be string or null")
    return primitive.content
}

private fun JsonObject.optionalJsonArray(key: String, path: java.nio.file.Path): JsonArray? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    return value as? JsonArray
        ?: error("${path.toDisplayPath()}: '$key' must be array or null")
}
