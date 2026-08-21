package com.crispy.tv.contracts

import com.crispy.tv.domain.watch.PlaybackProgressPolicy
import kotlinx.serialization.json.JsonObject
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayerProgressContractTest {
    @Test
    fun fixturesResolveProgressWrites() {
        val fixturePaths = ContractTestSupport.fixtureFiles("player_progress")
        assertTrue(fixturePaths.isNotEmpty(), "Expected at least one player_progress fixture")

        fixturePaths.forEach { path ->
            val fixture = ContractTestSupport.parseFixture(path)
            val caseId = fixture.requireString("case_id", path)
            assertEquals("player_progress", fixture.requireString("suite", path), "$caseId: wrong suite")

            val input = fixture.requireJsonObject("input", path)
            val expected = fixture.requireJsonObject("expected", path)

            val positionMs = input.optionalLong("position_ms", path)
                ?: error("${path.toDisplayPath()}: missing position_ms")
            val durationMs = input.optionalLong("duration_ms", path)
                ?: error("${path.toDisplayPath()}: missing duration_ms")
            val actual = PlaybackProgressPolicy.resolveProgressWrite(positionMs = positionMs, durationMs = durationMs)

            val storeOrDrop = expected.requireString("store_or_drop", path)
            val expectedCompleted = expected.requireBoolean("is_completed", path)

            when (storeOrDrop) {
                "drop" -> assertNull(actual, "$caseId: expected drop but got $actual")
                "store" -> {
                    assertTrue(actual != null, "$caseId: expected store but was dropped")
                    assertEquals(expectedCompleted, actual!!.isCompleted, "$caseId: is_completed")
                    expected.optionalLong("stored_position_ms", path)?.let { expectedStored ->
                        assertEquals(expectedStored, actual.storedPositionMs, "$caseId: stored_position_ms")
                    }
                    expected.optionalLong("event_position_ms", path)?.let { expectedEvent ->
                        assertEquals(expectedEvent, actual.eventPositionMs, "$caseId: event_position_ms")
                    }
                }
                else -> error("${path.toDisplayPath()}: unknown store_or_drop '$storeOrDrop'")
            }
        }
    }
}
