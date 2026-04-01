package com.crispy.tv.contracts

import com.crispy.tv.domain.media.MediaStateNormalized
import com.crispy.tv.domain.media.normalizeMediaStateCard
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaStateContractTest {
    @Test
    fun fixturesMatchMediaStateContract() {
        val fixturePaths = ContractTestSupport.fixtureFiles("media_state_contract")
        assertTrue(fixturePaths.isNotEmpty(), "Expected at least one media_state_contract fixture")

        fixturePaths.forEach { path ->
            val fixture = ContractTestSupport.parseFixture(path)
            val caseId = fixture.requireString("case_id", path)
            assertEquals(
                "media_state_contract",
                fixture.requireString("suite", path),
                "$caseId: wrong suite"
            )

            val input = fixture.requireJsonObject("input", path)
            val kind = input.requireString("kind", path)
            val payload = input.requireJsonObject("payload", path).toKotlinMap()

            val expected = fixture.requireJsonObject("expected", path)
            val expectedValid = expected.requireBoolean("valid", path)
            val expectedNormalized =
                expected.optionalJsonObject("normalized", path)?.let { normalized ->
                    parseNormalized(normalized, path)
                }

            val actual = normalizeMediaStateCard(payload = payload, kind = kind)

            assertEquals(expectedValid, actual != null, "$caseId: valid")
            assertEquals(expectedNormalized, actual, "$caseId: normalized")
        }
    }

    private fun parseNormalized(object: JsonObject, path: java.nio.file.Path): MediaStateNormalized {
        return MediaStateNormalized(
            cardFamily = object.requireString("card_family", path),
            mediaType = object.optionalString("media_type", path),
            itemId = object.optionalString("item_id", path),
            provider = object.optionalString("provider", path),
            providerId = object.optionalString("provider_id", path),
            title = object.optionalString("title", path),
            posterUrl = object.optionalString("poster_url", path),
            backdropUrl = object.optionalString("backdrop_url", path),
            subtitle = object.optionalString("subtitle", path),
            progressPercent = object.optionalDouble("progress_percent"),
            watchedAt = object.optionalString("watched_at", path),
            lastActivityAt = object.optionalString("last_activity_at", path),
            origins = object.optionalStringArray("origins", path),
            dismissible = object.optionalBoolean("dismissible"),
            layout = object.optionalString("layout", path),
        )
    }
}

private fun JsonObject.optionalDouble(key: String): Double? {
    val element = this[key] ?: return null
    val primitive = element as? JsonPrimitive ?: return null
    return primitive.jsonPrimitive.doubleOrNull
}

private fun JsonObject.optionalBoolean(key: String): Boolean? {
    val element = this[key] ?: return null
    val primitive = element as? JsonPrimitive ?: return null
    return primitive.jsonPrimitive.booleanOrNull
}

private fun JsonObject.optionalStringArray(key: String, path: java.nio.file.Path): List<String>? {
    val array = this[key]?.let { value -> value as? kotlinx.serialization.json.JsonArray } ?: return null
    return array.toStringList(path)
}

private fun JsonObject.toKotlinMap(): Map<String, Any?> {
    return entries.associate { (key, value) -> key to value.toKotlinValue() }
}

private fun kotlinx.serialization.json.JsonElement.toKotlinValue(): Any? {
    return when (this) {
        is kotlinx.serialization.json.JsonNull -> null
        is JsonObject -> toKotlinMap()
        is kotlinx.serialization.json.JsonArray -> map { element -> element.toKotlinValue() }
        is JsonPrimitive -> {
            booleanOrNull
                ?: intOrNull
                ?: doubleOrNull
                ?: content
        }
    }
}
