package com.crispy.tv.contracts

import com.crispy.tv.domain.media.ContractContinueWatchingItem
import com.crispy.tv.domain.media.ContractHistoryItem
import com.crispy.tv.domain.media.ContractLandscapeCard
import com.crispy.tv.domain.media.ContractPageInfo
import com.crispy.tv.domain.media.ContractRatingItem
import com.crispy.tv.domain.media.ContractRatingState
import com.crispy.tv.domain.media.ContractRegularCard
import com.crispy.tv.domain.media.ContractWatchProgress
import com.crispy.tv.domain.media.ContractWatchlistItem
import com.crispy.tv.domain.media.WatchCollectionContractEnvelope
import com.crispy.tv.domain.media.WatchCollectionContractItem
import com.crispy.tv.domain.media.normalizeWatchCollectionEnvelope
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

class WatchCollectionsContractTest {
    @Test
    fun fixturesMatchServerWatchCollectionContract() {
        val fixturePaths = ContractTestSupport.fixtureFiles("watch_collections_contract")
        assertTrue(fixturePaths.isNotEmpty(), "Expected at least one watch_collections_contract fixture")

        fixturePaths.forEach { path ->
            val fixture = ContractTestSupport.parseFixture(path)
            val caseId = fixture.requireString("case_id", path)
            assertEquals("watch_collections_contract", fixture.requireString("suite", path), "$caseId: wrong suite")

            val input = fixture.requireJsonObject("input", path)
            val expected = fixture.requireJsonObject("expected", path)
            val payload = input.requireJsonObject("payload", path).toKotlinMap()

            val actual = normalizeWatchCollectionEnvelope(payload)
            val expectedValid = expected.requireBoolean("valid", path)
            val expectedNormalized = expected.optionalJsonObject("normalized", path)?.let { parseEnvelope(it, path) }

            assertEquals(expectedValid, actual != null, "$caseId: valid")
            assertEquals(expectedNormalized, actual, "$caseId: normalized")
        }
    }

    private fun parseEnvelope(json: JsonObject, path: Path): WatchCollectionContractEnvelope {
        val kind = json.requireString("kind", path)
        return WatchCollectionContractEnvelope(
            profileId = json.requireString("profile_id", path),
            kind = kind,
            source = json.requireString("source", path),
            generatedAt = json.requireString("generated_at", path),
            items = json.requireJsonArray("items", path).map { parseItem(it.jsonObject, path, kind) },
            pageInfo = parsePageInfo(json.requireJsonObject("page_info", path), path),
        )
    }

    private fun parseItem(json: JsonObject, path: Path, kind: String): WatchCollectionContractItem {
        return when (kind) {
            "continue-watching" -> parseContinueWatchingItem(json, path)
            "history" -> parseHistoryItem(json, path)
            "watchlist" -> parseWatchlistItem(json, path)
            "ratings" -> parseRatingItem(json, path)
            else -> error("${path.fileName}: unsupported collection kind $kind")
        }
    }

    private fun parseContinueWatchingItem(json: JsonObject, path: Path): ContractContinueWatchingItem {
        return ContractContinueWatchingItem(
            id = json.requireString("id", path),
            media = parseLandscapeCard(json.requireJsonObject("media", path), path),
            progress = json.optionalJsonObject("progress", path)?.let { parseWatchProgress(it, path) },
            lastActivityAt = json.requireString("last_activity_at", path),
            origins = json.requireJsonArray("origins", path).toStringList(path),
            dismissible = json.requireBoolean("dismissible", path),
        )
    }

    private fun parseHistoryItem(json: JsonObject, path: Path): ContractHistoryItem {
        return ContractHistoryItem(
            id = json.requireString("id", path),
            media = parseRegularCard(json.requireJsonObject("media", path), path),
            watchedAt = json.requireString("watched_at", path),
            origins = json.requireJsonArray("origins", path).toStringList(path),
        )
    }

    private fun parseWatchlistItem(json: JsonObject, path: Path): ContractWatchlistItem {
        return ContractWatchlistItem(
            id = json.requireString("id", path),
            media = parseRegularCard(json.requireJsonObject("media", path), path),
            addedAt = json.requireString("added_at", path),
            origins = json.requireJsonArray("origins", path).toStringList(path),
        )
    }

    private fun parseRatingItem(json: JsonObject, path: Path): ContractRatingItem {
        return ContractRatingItem(
            id = json.requireString("id", path),
            media = parseRegularCard(json.requireJsonObject("media", path), path),
            rating = parseRatingState(json.requireJsonObject("rating", path), path),
            origins = json.requireJsonArray("origins", path).toStringList(path),
        )
    }

    private fun parseRatingState(json: JsonObject, path: Path): ContractRatingState {
        return ContractRatingState(
            value = json.requireDouble("value", path),
            ratedAt = json.requireString("rated_at", path),
        )
    }

    private fun parseWatchProgress(json: JsonObject, path: Path): ContractWatchProgress {
        return ContractWatchProgress(
            positionSeconds = json.optionalDouble("position_seconds", path),
            durationSeconds = json.optionalDouble("duration_seconds", path),
            progressPercent = json.requireDouble("progress_percent", path),
            lastPlayedAt = json.optionalString("last_played_at", path),
        )
    }

    private fun parsePageInfo(json: JsonObject, path: Path): ContractPageInfo {
        return ContractPageInfo(
            nextCursor = json.optionalString("next_cursor", path),
            hasMore = json.requireBoolean("has_more", path),
        )
    }

    private fun parseRegularCard(json: JsonObject, path: Path): ContractRegularCard {
        return ContractRegularCard(
            mediaType = json.requireString("media_type", path),
            mediaKey = json.requireString("media_key", path),
            title = json.requireString("title", path),
            posterUrl = json.requireString("poster_url", path),
            releaseYear = json.optionalInt("release_year", path),
            rating = json.optionalDouble("rating", path),
            genre = json.optionalString("genre", path),
            subtitle = json.optionalString("subtitle", path),
        )
    }

    private fun parseLandscapeCard(json: JsonObject, path: Path): ContractLandscapeCard {
        return ContractLandscapeCard(
            mediaType = json.requireString("media_type", path),
            mediaKey = json.requireString("media_key", path),
            title = json.requireString("title", path),
            posterUrl = json.requireString("poster_url", path),
            backdropUrl = json.requireString("backdrop_url", path),
            releaseYear = json.optionalInt("release_year", path),
            rating = json.optionalDouble("rating", path),
            genre = json.optionalString("genre", path),
            seasonNumber = json.optionalInt("season_number", path),
            episodeNumber = json.optionalInt("episode_number", path),
            episodeTitle = json.optionalString("episode_title", path),
            airDate = json.optionalString("air_date", path),
            runtimeMinutes = json.optionalInt("runtime_minutes", path),
        )
    }
}

private fun JsonObject.requireDouble(key: String, path: Path): Double {
    val value = this[key] as? JsonPrimitive ?: error("${path.toDisplayPath()}: missing double '$key'")
    return value.doubleOrNull ?: error("${path.toDisplayPath()}: '$key' must be double")
}

private fun JsonObject.optionalDouble(key: String, path: Path): Double? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive ?: error("${path.toDisplayPath()}: '$key' must be double or null")
    return primitive.doubleOrNull ?: error("${path.toDisplayPath()}: '$key' must be double or null")
}

private fun JsonObject.toKotlinMap(): Map<String, Any?> {
    return entries.associate { (key, value) -> key to value.toKotlinValue() }
}

private fun JsonElement.toKotlinValue(): Any? {
    return when (this) {
        is JsonNull -> null
        is JsonObject -> toKotlinMap()
        is kotlinx.serialization.json.JsonArray -> map { element -> element.toKotlinValue() }
        is JsonPrimitive -> if (isString) content else booleanOrNull ?: intOrNull ?: doubleOrNull ?: content
    }
}
