package com.crispy.tv.contracts

import com.crispy.tv.domain.account.validateProfileName
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileNameValidationContractTest {
    @Test
    fun profileNameValidationFixtures() {
        ContractTestSupport.fixtureFiles("profile_name_validation").forEach { path ->
            val root = ContractTestSupport.parseFixture(path)
            assertEquals("profile_name_validation", root.requireString("suite", path))
            val input = root.requireJsonObject("input", path)
            val expected = root.requireJsonObject("expected", path)

            val raw = input.requireString("raw_name", path)
            val result = validateProfileName(raw)

            val expectedValid = expected.requireBoolean("is_valid", path)
            val expectedNormalized = expected.optionalString("normalized_name", path)
            val expectedError = expected.optionalString("error", path)

            when (result) {
                is com.crispy.tv.domain.account.ProfileNameResult.Valid -> {
                    assertEquals(true, expectedValid, "Expected invalid in ${path.toDisplayPath()}")
                    assertEquals(expectedNormalized, result.normalized, "Normalized mismatch in ${path.toDisplayPath()}")
                }
                is com.crispy.tv.domain.account.ProfileNameResult.Invalid -> {
                    assertEquals(false, expectedValid, "Expected valid in ${path.toDisplayPath()}")
                    assertEquals(expectedError, result.reason, "Error reason mismatch in ${path.toDisplayPath()}")
                }
            }
        }
    }
}

private fun JsonObject.optionalString(key: String, path: java.nio.file.Path): String? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    val primitive = value as? kotlinx.serialization.json.JsonPrimitive
        ?: error("${path.toDisplayPath()}: '$key' must be string or null")
    return primitive.content
}
