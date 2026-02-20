package com.crispy.rewrite.contracts

import com.crispy.rewrite.domain.watch.TraktScrobbleStage
import com.crispy.rewrite.domain.watch.decideTraktScrobble
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.test.Test
import kotlin.test.assertEquals

class TraktScrobblePolicyContractTest {
    @Test
    fun traktScrobblePolicyFixtures() {
        val fixtures = ContractTestSupport.fixtureFiles("trakt_scrobble_policy")
        fixtures.forEach { path ->
            val root = ContractTestSupport.parseFixture(path)
            assertEquals("trakt_scrobble_policy", root.requireString("suite", path), "Wrong suite in ${path.toDisplayPath()}")

            val input = root.requireJsonObject("input", path)
            val expected = root.requireJsonObject("expected", path)

            val stage =
                when (input.requireString("stage", path).lowercase()) {
                    "start" -> TraktScrobbleStage.START
                    "stop" -> TraktScrobbleStage.STOP
                    else -> error("${path.toDisplayPath()}: invalid stage")
                }

            val progress = input.requireDouble("progress_percent", path)
            val actual = decideTraktScrobble(stage = stage, progressPercent = progress)

            assertEquals(expected.requireString("endpoint", path), actual.endpoint, "Endpoint mismatch in ${path.toDisplayPath()}")
            assertEquals(expected.requireBoolean("marks_watched", path), actual.marksWatched, "marks_watched mismatch in ${path.toDisplayPath()}")
            assertEquals(
                expected.requireBoolean("updates_playback_progress", path),
                actual.updatesPlaybackProgress,
                "updates_playback_progress mismatch in ${path.toDisplayPath()}"
            )
        }
    }
}

private fun JsonObject.requireDouble(key: String, path: java.nio.file.Path): Double {
    val primitive = this[key] as? JsonPrimitive
        ?: error("${path.toDisplayPath()}: missing double '$key'")
    return primitive.doubleOrNull
        ?: error("${path.toDisplayPath()}: '$key' must be double")
}
