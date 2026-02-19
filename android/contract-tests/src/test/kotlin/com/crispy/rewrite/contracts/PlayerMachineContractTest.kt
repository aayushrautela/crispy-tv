package com.crispy.rewrite.contracts

import com.crispy.rewrite.domain.player.PlayerAction
import com.crispy.rewrite.domain.player.initialPlayerState
import com.crispy.rewrite.domain.player.reducePlayerState
import com.crispy.rewrite.domain.player.toContractValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.contentOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class PlayerMachineContractTest {
    private val json = Json { ignoreUnknownKeys = false }

    @Test
    fun playerMachineFixtures() {
        val fixturesRoot = repositoryRoot().resolve("contracts/fixtures/player_machine")
        val fixtures = fixtureFiles(fixturesRoot)
        assertTrue(fixtures.isNotEmpty(), "No player_machine fixtures found at $fixturesRoot")

        fixtures.forEach { fixturePath ->
            val root = parseFixture(fixturePath)
            val nowMs = root.requireLong("now_ms", fixturePath)
            val suite = root.requireString("suite", fixturePath)
            assertEquals("player_machine", suite, "Wrong suite in ${fixturePath.toDisplayPath()}")
            val caseId = root.requireString("case_id", fixturePath)

            var state = initialPlayerState(sessionId = caseId, nowMs = nowMs)
            val steps = root["steps"]?.jsonArray
                ?: fail("Missing 'steps' array in ${fixturePath.toDisplayPath()}")

            steps.forEachIndexed { index, stepElement ->
                val step = stepElement.asObject("step[$index]", fixturePath)
                val tMs = step.requireLong("t_ms", fixturePath)
                val event = step["event"]?.jsonObject
                    ?: fail("Missing 'event' in step[$index] of ${fixturePath.toDisplayPath()}")
                val expected = step["expect"]?.jsonObject
                    ?: fail("Missing 'expect' in step[$index] of ${fixturePath.toDisplayPath()}")

                val action = eventToAction(event, fixturePath, index)
                state = reducePlayerState(state, action, nowMs + tMs)

                val expectedPhase = expected.requireString("phase", fixturePath)
                val expectedIntent = expected.requireString("intent", fixturePath)
                assertEquals(
                    expectedPhase,
                    state.phase.toContractValue(),
                    "Phase mismatch for ${fixturePath.toDisplayPath()} step[$index]"
                )
                assertEquals(
                    expectedIntent,
                    state.intent.toContractValue(),
                    "Intent mismatch for ${fixturePath.toDisplayPath()} step[$index]"
                )

                expected["engine"]?.jsonPrimitive?.contentOrNull?.let { expectedEngine ->
                    assertEquals(
                        expectedEngine,
                        state.engine,
                        "Engine mismatch for ${fixturePath.toDisplayPath()} step[$index]"
                    )
                }
            }
        }
    }

    private fun eventToAction(event: JsonObject, fixturePath: Path, stepIndex: Int): PlayerAction {
        val type = event.requireString("type", fixturePath)
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
            else -> fail(
                "Unsupported event type '$type' in ${fixturePath.toDisplayPath()} step[$stepIndex]"
            )
        }
    }

    private fun parseFixture(path: Path): JsonObject {
        val element = runCatching { json.parseToJsonElement(Files.readString(path)) }
            .getOrElse { error -> fail("Invalid JSON in ${path.toDisplayPath()}: ${error.message}") }
        return element.asObject("root", path)
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

    private fun JsonElement.asObject(location: String, path: Path): JsonObject {
        return this as? JsonObject
            ?: fail("Expected JSON object at $location in ${path.toDisplayPath()}")
    }

    private fun JsonObject.requireString(key: String, path: Path): String {
        return this[key]?.jsonPrimitive?.contentOrNull
            ?: fail("Missing or invalid '$key' in ${path.toDisplayPath()}")
    }

    private fun JsonObject.requireLong(key: String, path: Path): Long {
        return this[key]?.jsonPrimitive?.long
            ?: fail("Missing or invalid '$key' in ${path.toDisplayPath()}")
    }

    private fun Path.toDisplayPath(): String {
        return repositoryRoot().relativize(this).toString()
    }
}
