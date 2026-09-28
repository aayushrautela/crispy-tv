package com.crispy.tv.contracts

import com.crispy.tv.domain.player.PlayerAction
import com.crispy.tv.domain.player.initialPlayerState
import com.crispy.tv.domain.player.reducePlayerState
import com.crispy.tv.domain.player.toContractValue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class PlayerMachineContractTest {

    /**
     * Pinned to `v2` rather than taking the suite's only version directory.
     *
     * That is the point: when the spec revises and `v3` appears, this test
     * should keep exercising the behaviour it was written against and the
     * mismatch should be a deliberate decision, not an accident of the
     * directory walk. `ContractFixturesSanityTest` enforces the "one version
     * directory per suite" invariant that makes the pin unambiguous.
     */
    private val directory = "player_machine/v2"

    @Test
    fun playerMachineFixtures() {
        val fixtures = ContractFixtures.inDirectory(directory)
        assertTrue(fixtures.isNotEmpty(), "No player_machine fixtures found in $directory")

        fixtures.forEach { fixture ->
            val root = parseFixture(fixture)
            val nowMs = root.requireLong("now_ms", fixture)
            val suite = root.requireString("suite", fixture)
            assertEquals("player_machine", suite, "Wrong suite in $fixture")
            val caseId = root.requireString("case_id", fixture)

            var state = initialPlayerState(sessionId = caseId, nowMs = nowMs)
            val steps = root["steps"]?.jsonArray
                ?: fail("Missing 'steps' array in $fixture")

            steps.forEachIndexed { index, stepElement ->
                val step = stepElement.asObject("step[$index]", fixture)
                val tMs = step.requireLong("t_ms", fixture)
                val event = step["event"]?.jsonObject
                    ?: fail("Missing 'event' in step[$index] of $fixture")
                val expected = step["expect"]?.jsonObject
                    ?: fail("Missing 'expect' in step[$index] of $fixture")

                val action = eventToAction(event, fixture, index)
                state = reducePlayerState(state, action, nowMs + tMs)

                val expectedPhase = expected.requireString("phase", fixture)
                val expectedIntent = expected.requireString("intent", fixture)
                assertEquals(
                    expectedPhase,
                    state.phase.toContractValue(),
                    "Phase mismatch for $fixture step[$index]",
                )
                assertEquals(
                    expectedIntent,
                    state.intent.toContractValue(),
                    "Intent mismatch for $fixture step[$index]",
                )

                expected["engine"]?.jsonPrimitive?.contentOrNull?.let { expectedEngine ->
                    assertEquals(
                        expectedEngine,
                        state.engine,
                        "Engine mismatch for $fixture step[$index]",
                    )
                }
            }
        }
    }

    private fun eventToAction(event: JsonObject, fixture: ContractFixture, stepIndex: Int): PlayerAction {
        val type = event.requireString("type", fixture)
        val engine = event["engine"]?.jsonPrimitive?.contentOrNull

        return when (type) {
            "OPEN_HTTP" -> PlayerAction.OpenHttp(engine)
            "OPEN_TORRENT" -> PlayerAction.OpenTorrent(engine)
            "TORRENT_STREAM_RESOLVED" -> PlayerAction.TorrentStreamResolved
            "NATIVE_FIRST_FRAME" -> PlayerAction.NativeFirstFrame
            "NATIVE_READY" -> PlayerAction.NativeReady
            "NATIVE_BUFFERING" -> PlayerAction.NativeBuffering
            "NATIVE_END", "NATIVE_ENDED" -> PlayerAction.NativeEnded
            "NATIVE_CODEC_ERROR" -> PlayerAction.NativeCodecError
            "USER_INTENT_PLAY", "PLAY" -> PlayerAction.UserIntentPlay
            "USER_INTENT_PAUSE", "PAUSE" -> PlayerAction.UserIntentPause
            else -> fail("Unsupported event type '$type' in $fixture step[$stepIndex]")
        }
    }

    private fun parseFixture(fixture: ContractFixture): JsonObject {
        val element = runCatching { ContractTestSupport.parseFixture(fixture) }
            .getOrElse { error -> fail("Invalid JSON in $fixture: ${error.message}") }
        return element.asObject("root", fixture)
    }

    private fun JsonElement.asObject(location: String, fixture: ContractFixture): JsonObject {
        return this as? JsonObject
            ?: fail("Expected JSON object at $location in $fixture")
    }

    private fun JsonObject.requireString(key: String, fixture: ContractFixture): String {
        return this[key]?.jsonPrimitive?.contentOrNull
            ?: fail("Missing or invalid '$key' in $fixture")
    }

    private fun JsonObject.requireLong(key: String, fixture: ContractFixture): Long {
        return this[key]?.jsonPrimitive?.long
            ?: fail("Missing or invalid '$key' in $fixture")
    }
}
