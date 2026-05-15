package com.crispy.tv.contracts

import com.crispy.tv.domain.media.normalizeWatchCollectionEnvelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

class WatchCollectionsContractTest {
    @Test
    fun fixturesMatchServerWatchCollectionContract() {
        val fixturePaths = ContractTestSupport.fixtureFiles("watch_collections_contract")
            .filter { it.toString().contains("/v2/") }
        assertTrue(fixturePaths.isNotEmpty(), "Expected at least one watch_collections_contract v2 fixture")

        fixturePaths.forEach { path ->
            val fixture = ContractTestSupport.parseFixture(path)
            val caseId = fixture.requireString("case_id", path)
            assertEquals("watch_collections_contract", fixture.requireString("suite", path), "$caseId: wrong suite")

            val payload = fixture.requireJsonObject("input", path).requireJsonObject("payload", path).toKotlinMap()
            val expectedValid = fixture.requireJsonObject("expected", path).requireBoolean("valid", path)
            val actual = normalizeWatchCollectionEnvelope(payload)

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
