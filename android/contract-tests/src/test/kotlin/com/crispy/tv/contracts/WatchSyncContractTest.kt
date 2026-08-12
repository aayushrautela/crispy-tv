package com.crispy.tv.contracts

import com.crispy.tv.domain.watch.WatchSyncEvent
import com.crispy.tv.domain.watch.createWatchSyncState
import com.crispy.tv.domain.watch.reduceWatchSync
import com.crispy.tv.domain.watch.toContractValue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class WatchSyncContractTest {
    @Test
    fun watchSyncFixtures() {
        val fixtures = ContractTestSupport.fixtureFiles("watch_sync")
        fixtures.forEach { path ->
            val root = ContractTestSupport.parseFixture(path)
            assertEquals("watch_sync", root.requireString("suite", path), "Wrong suite in ${path.toDisplayPath()}")

            val input = root.requireJsonObject("input", path)
            val expected = root.requireJsonObject("expected", path)
            val profileId = input.requireString("profile_id", path)

            var state = createWatchSyncState(profileId)
            val actualEffects = mutableListOf<String>()

            input.requireJsonArray("events", path).forEach { entry ->
                val obj = entry as? JsonObject ?: error("${path.toDisplayPath()}: event must be object")
                val type = obj.requireString("type", path)
                val event =
                    when (type) {
                        "surface_visible" -> WatchSyncEvent.SurfaceBecameVisible
                        "surface_hidden" -> WatchSyncEvent.SurfaceHidden
                        "connection_opened" -> WatchSyncEvent.ConnectionOpened
                        "connection_closed" -> WatchSyncEvent.ConnectionClosed
                        "invalidation" ->
                            WatchSyncEvent.InvalidationReceived(
                                profileId = obj.requireString("profile_id", path),
                                atMs = obj.optionalLong("at_ms", path) ?: 0L,
                            )
                        "max_duration_elapsed" -> WatchSyncEvent.MaxDurationElapsed
                        else -> error("${path.toDisplayPath()}: unknown watch_sync event type '$type'")
                    }
                val result = reduceWatchSync(state, event)
                state = result.state
                actualEffects += result.effects.map { it.toContractValue() }
            }

            val expectedEffects = expected.requireJsonArray("effects", path).toStringList(path)
            assertEquals(expectedEffects, actualEffects, "Effects mismatch in ${path.toDisplayPath()}")
        }
    }
}
