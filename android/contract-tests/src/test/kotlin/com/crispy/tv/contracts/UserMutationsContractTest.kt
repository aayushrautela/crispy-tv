package com.crispy.tv.contracts

import com.crispy.tv.domain.optimistic.DerivedUserState
import com.crispy.tv.domain.optimistic.EpisodeWatchedMutation
import com.crispy.tv.domain.optimistic.FieldSync
import com.crispy.tv.domain.optimistic.MutationKind
import com.crispy.tv.domain.optimistic.MutationStatus
import com.crispy.tv.domain.optimistic.OutboxAction
import com.crispy.tv.domain.optimistic.RatingMutation
import com.crispy.tv.domain.optimistic.SeasonWatchedMutation
import com.crispy.tv.domain.optimistic.TitleWatchedMutation
import com.crispy.tv.domain.optimistic.UserMutation
import com.crispy.tv.domain.optimistic.UserStateSnapshot
import com.crispy.tv.domain.optimistic.WatchlistMutation
import com.crispy.tv.domain.optimistic.deriveUserState
import com.crispy.tv.domain.optimistic.planOutbox
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UserMutationsContractTest {
    @Test
    fun fixturesResolveOptimisticState() {
        val fixturePaths = ContractTestSupport.fixtureFiles("optimistic_state")
        assertTrue(fixturePaths.isNotEmpty(), "Expected at least one optimistic_state fixture")

        fixturePaths.forEach { path ->
            val fixture = ContractTestSupport.parseFixture(path)
            val caseId = fixture.requireString("case_id", path)
            assertEquals("optimistic_state", fixture.requireString("suite", path), "$caseId: wrong suite")

            val operation = fixture.requireString("operation", path)
            val input = fixture.requireJsonObject("input", path)
            val expected = fixture.requireJsonObject("expected", path)

            val snapshot = parseSnapshot(input.requireJsonObject("snapshot", path), path)
            val mutations = parseMutations(input.requireJsonArray("mutations", path), path)

            when (operation) {
                "derive" -> assertDerived(caseId, deriveUserState(snapshot, mutations), expected, path)
                "plan_outbox" -> {
                    val nowMs = input.optionalLong("now_ms", path) ?: 0L
                    val actual = planOutbox(mutations, nowMs).map { OutboxAction(it.mutationId, it.kind) }
                    val expectedActions = parseExpectedActions(expected, path)
                    assertEquals(expectedActions, actual, "$caseId: actions")
                }
                else -> error("$caseId: unknown operation '$operation'")
            }
        }
    }

    private fun parseSnapshot(obj: JsonObject, path: Path): UserStateSnapshot {
        val episodeWatched = obj.requireJsonObject("episode_watched", path)
            .mapValues { (_, v) -> (v as JsonPrimitive).booleanOrNull ?: false }
        val seasonWatched = obj.requireJsonObject("season_watched", path)
            .mapNotNull { (k, v) ->
                val value = (v as JsonPrimitive).booleanOrNull ?: return@mapNotNull null
                k.toIntOrNull()?.let { it to value }
            }
            .toMap()
        return UserStateSnapshot(
            isInWatchlist = obj.requireBoolean("is_in_watchlist", path),
            isWatched = obj.requireBoolean("is_watched", path),
            isRated = obj.requireBoolean("is_rated", path),
            userRating = obj.optionalInt("user_rating", path),
            episodeWatched = episodeWatched,
            seasonWatched = seasonWatched,
        )
    }

    private fun parseMutations(array: kotlinx.serialization.json.JsonArray, path: Path): List<UserMutation> {
        return array.mapIndexed { index, element ->
            val obj = element.jsonObject
            val kind = when (obj.requireString("kind", path)) {
                "watchlist" -> MutationKind.WATCHLIST
                "title_watched" -> MutationKind.TITLE_WATCHED
                "episode_watched" -> MutationKind.EPISODE_WATCHED
                "season_watched" -> MutationKind.SEASON_WATCHED
                "rating" -> MutationKind.RATING
                else -> error("${path.toDisplayPath()}: invalid kind at mutations[$index]")
            }
            val id = obj.requireString("id", path)
            val entityId = obj.requireString("entity_id", path)
            val createdAtMs = obj.requireInt("created_at_ms", path).toLong()
            val attempt = obj.requireInt("attempt", path)
            val nextAttemptAtMs = obj.optionalLong("next_attempt_ms", path) ?: 0L
            val status = parseStatus(obj, path)
            when (kind) {
                MutationKind.WATCHLIST -> WatchlistMutation(
                    id, entityId, entityId, createdAtMs, attempt, status, nextAttemptAtMs,
                    desired = obj.requireBoolean("desired", path),
                )
                MutationKind.TITLE_WATCHED -> TitleWatchedMutation(
                    id, entityId, entityId, createdAtMs, attempt, status, nextAttemptAtMs,
                    contentType = com.crispy.tv.domain.optimistic.MediaContentType.MOVIE,
                    desired = obj.requireBoolean("desired", path),
                )
                MutationKind.RATING -> RatingMutation(
                    id, entityId, entityId, createdAtMs, attempt, status, nextAttemptAtMs,
                    desired = obj.optionalInt("desired", path),
                )
                MutationKind.EPISODE_WATCHED -> EpisodeWatchedMutation(
                    id, entityId, entityId, createdAtMs, attempt, status, nextAttemptAtMs,
                    itemId = entityId,
                    season = obj.requireInt("season", path),
                    episode = obj.optionalInt("episode", path),
                    videoId = obj.requireString("video_id", path),
                    desired = obj.requireBoolean("desired", path),
                )
                MutationKind.SEASON_WATCHED -> SeasonWatchedMutation(
                    id, entityId, entityId, createdAtMs, attempt, status, nextAttemptAtMs,
                    seasonItemId = obj.requireString("season_item_id", path),
                    seasonNumber = obj.requireInt("season_number", path),
                    desired = obj.requireBoolean("desired", path),
                )
            }
        }
    }

    private fun parseStatus(obj: JsonObject, path: Path): MutationStatus {
        return when (obj.requireString("status", path)) {
            "pending" -> MutationStatus.Pending
            "inflight" -> MutationStatus.Inflight
            "failed" -> MutationStatus.Failed(
                reason = obj.optionalString("status_reason", path) ?: "",
                retryable = true,
            )
            "conflict" -> MutationStatus.Conflict(obj.optionalString("status_server_value", path))
            else -> error("${path.toDisplayPath()}: invalid status")
        }
    }

    private fun parseExpectedActions(expected: JsonObject, path: Path): List<OutboxAction> {
        return expected.requireJsonArray("actions", path).mapIndexed { index, element ->
            val obj = element.jsonObject
            val kind = when (obj.requireString("kind", path)) {
                "watchlist" -> MutationKind.WATCHLIST
                "title_watched" -> MutationKind.TITLE_WATCHED
                "episode_watched" -> MutationKind.EPISODE_WATCHED
                "season_watched" -> MutationKind.SEASON_WATCHED
                "rating" -> MutationKind.RATING
                else -> error("${path.toDisplayPath()}: invalid action kind at actions[$index]")
            }
            OutboxAction(mutationId = obj.requireString("mutation_id", path), kind = kind)
        }
    }

    private fun assertDerived(
        caseId: String,
        actual: DerivedUserState,
        expected: JsonObject,
        path: Path,
    ) {
        assertFieldBool("$caseId: watchlist", actual.watchlist, expected.requireJsonObject("watchlist", path))
        assertFieldBool("$caseId: title_watched", actual.titleWatched, expected.requireJsonObject("title_watched", path))
        assertFieldRating("$caseId: rating", actual.rating, expected.requireJsonObject("rating", path))

        val expectedEpisodes = expected.requireJsonObject("episode_watched", path)
        assertEquals(expectedEpisodes.keys.sorted(), actual.episodeWatched.keys.sorted(), "$caseId: episode keys")
        expectedEpisodes.forEach { (key, _) ->
            assertFieldBool("$caseId: episode $key", actual.episodeWatched[key]!!, expectedEpisodes[key]!!.jsonObject)
        }

        val expectedSeasons = expected.requireJsonObject("season_watched", path)
        assertEquals(expectedSeasons.keys.sorted(), actual.seasonWatched.keys.map { it.toString() }.sorted(), "$caseId: season keys")
        expectedSeasons.forEach { (key, _) ->
            assertFieldBool("$caseId: season $key", actual.seasonWatched[key.toInt()]!!, expectedSeasons[key]!!.jsonObject)
        }
    }

    private fun assertFieldBool(
        label: String,
        field: Pair<Boolean, com.crispy.tv.domain.optimistic.MutationSyncView>,
        expected: JsonObject,
    ) {
        assertEquals(expected.requireBoolean("value", Path.of(label)), field.first, "$label: value")
        assertEquals(parseSync(expected.requireString("sync", Path.of(label))), field.second.status, "$label: sync")
    }

    private fun assertFieldRating(
        label: String,
        field: Pair<Int?, com.crispy.tv.domain.optimistic.MutationSyncView>,
        expected: JsonObject,
    ) {
        assertEquals(expected.optionalInt("value", Path.of(label)), field.first, "$label: value")
        assertEquals(parseSync(expected.requireString("sync", Path.of(label))), field.second.status, "$label: sync")
    }

    private fun parseSync(value: String): FieldSync = when (value) {
        "idle" -> FieldSync.IDLE
        "syncing" -> FieldSync.SYNCING
        "error" -> FieldSync.ERROR
        else -> error("invalid sync '$value'")
    }
}
