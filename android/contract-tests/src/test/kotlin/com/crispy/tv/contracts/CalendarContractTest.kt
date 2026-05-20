package com.crispy.tv.contracts

import com.crispy.tv.domain.media.normalizeCalendarEnvelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class CalendarContractTest {
    @Test
    fun fixturesMatchServerCalendarContract() {
        val fixturePaths = ContractTestSupport.fixtureFiles("calendar_contract")
            .filter { it.toString().contains("/v4/") }
        assertTrue(fixturePaths.isNotEmpty(), "Expected at least one calendar_contract v4 fixture")

        fixturePaths.forEach { path ->
            val fixture = ContractTestSupport.parseFixture(path)
            val caseId = fixture.requireString("case_id", path)
            assertEquals("calendar_contract", fixture.requireString("suite", path), "$caseId: wrong suite")

            val payload = fixture.requireJsonObject("input", path).requireJsonObject("payload", path).toKotlinMap()
            val actual = normalizeCalendarEnvelope(payload)
            val expectedValid = fixture.requireJsonObject("expected", path).requireBoolean("valid", path)

            assertEquals(expectedValid, actual != null, "$caseId: valid")
        }
    }
}

private fun kotlinx.serialization.json.JsonObject.toKotlinMap(): Map<String, Any?> {
    return entries.associate { (key, value) -> key to value.toKotlinValue() }
}

private fun kotlinx.serialization.json.JsonElement.toKotlinValue(): Any? {
    return when (this) {
        is kotlinx.serialization.json.JsonNull -> null
        is kotlinx.serialization.json.JsonObject -> toKotlinMap()
        is kotlinx.serialization.json.JsonArray -> map { element -> element.toKotlinValue() }
        is kotlinx.serialization.json.JsonPrimitive -> jsonPrimitive.booleanOrNull ?: jsonPrimitive.intOrNull ?: jsonPrimitive.doubleOrNull ?: jsonPrimitive.content
    }
}
