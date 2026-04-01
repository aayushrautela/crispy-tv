package com.crispy.tv.contracts

import com.crispy.tv.domain.home.HomeCatalogDiscoverRef
import com.crispy.tv.domain.home.HomeCatalogHeroItem
import com.crispy.tv.domain.home.HomeCatalogHeroResult
import com.crispy.tv.domain.home.HomeCatalogItem
import com.crispy.tv.domain.home.HomeCatalogList
import com.crispy.tv.domain.home.HomeCatalogPageResult
import com.crispy.tv.domain.home.HomeCatalogPresentation
import com.crispy.tv.domain.home.HomeCatalogFeedPlan
import com.crispy.tv.domain.home.HomeCatalogSection
import com.crispy.tv.domain.home.HomeCatalogSnapshot
import com.crispy.tv.domain.home.HomeCatalogSource
import com.crispy.tv.domain.home.buildCatalogPage
import com.crispy.tv.domain.home.listDiscoverCatalogs
import com.crispy.tv.domain.home.planPersonalHomeFeed
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

class HomeCatalogsContractTest {
    @Test
    fun fixturesMatchHomeCatalogPlanningRules() {
        val fixturePaths = ContractTestSupport.fixtureFiles("home_catalogs")
        assertTrue(fixturePaths.isNotEmpty(), "Expected at least one home_catalogs fixture")

        fixturePaths.forEach { path ->
            val fixture = ContractTestSupport.parseFixture(path)
            val caseId = fixture.requireString("case_id", path)
            assertEquals("home_catalogs", fixture.requireString("suite", path), "$caseId: wrong suite")

            val input = fixture.requireJsonObject("input", path)
            val expected = fixture.requireJsonObject("expected", path)
            val snapshot = parseSnapshot(input.requireJsonObject("snapshot", path), path)
            val discoverInput = input.requireJsonObject("discover", path)
            val pageInput = input.requireJsonObject("page", path)

            val actualPersonalFeed = planPersonalHomeFeed(
                snapshot = snapshot,
                heroLimit = input.requireInt("hero_limit", path),
                sectionLimit = input.requireInt("section_limit", path),
            )
            val actualDiscover = listDiscoverCatalogs(
                snapshot = snapshot,
                mediaType = discoverInput.optionalString("media_type", path),
                limit = discoverInput.requireInt("limit", path),
            )
            val actualPage = buildCatalogPage(
                snapshot = snapshot,
                sectionCatalogId = pageInput.requireString("catalog_id", path),
                page = pageInput.requireInt("page", path),
                pageSize = pageInput.requireInt("page_size", path),
            )

            assertEquals(
                parsePersonalFeed(expected.requireJsonObject("personal_feed", path), path),
                actualPersonalFeed,
                "$caseId: personal_feed",
            )
            assertEquals(
                parseDiscover(expected.requireJsonObject("discover", path), path),
                actualDiscover,
                "$caseId: discover",
            )
            assertEquals(
                parsePage(expected.requireJsonObject("page", path), path),
                actualPage,
                "$caseId: page",
            )
        }
    }

    private fun parseSnapshot(json: JsonObject, path: Path): HomeCatalogSnapshot {
        return HomeCatalogSnapshot(
            profileId = json.optionalString("profile_id", path),
            lists = json.requireJsonArray("lists", path).map { parseList(it.jsonObject, path) },
            statusMessage = json.requireString("status_message", path),
        )
    }

    private fun parseList(json: JsonObject, path: Path): HomeCatalogList {
        return HomeCatalogList(
            kind = json.requireString("kind", path),
            variantKey = json.optionalString("variant_key", path) ?: "default",
            source = HomeCatalogSource.fromRaw(json.requireString("source", path))
                ?: error("${path.fileName}: invalid source"),
            presentation = HomeCatalogPresentation.fromRaw(json.optionalString("presentation", path)),
            layout = json.optionalString("layout", path),
            name = json.optionalString("name", path).orEmpty(),
            heading = json.optionalString("heading", path).orEmpty(),
            title = json.optionalString("title", path).orEmpty(),
            subtitle = json.optionalString("subtitle", path).orEmpty(),
            items = json.requireJsonArray("items", path).map { parseItem(it.jsonObject, path) },
            mediaTypes = json.requireJsonArray("media_types", path).toStringList(path).toSet(),
        )
    }

    private fun parseItem(json: JsonObject, path: Path): HomeCatalogItem {
        return HomeCatalogItem(
            id = json.requireString("id", path),
            title = json.requireString("title", path),
            posterUrl = json.optionalString("poster_url", path),
            backdropUrl = json.optionalString("backdrop_url", path),
            addonId = json.requireString("addon_id", path),
            type = json.requireString("type", path),
            rating = json.optionalString("rating", path),
            year = json.optionalString("year", path),
            description = json.optionalString("description", path),
            provider = json.requireString("provider", path),
            providerId = json.requireString("provider_id", path),
        )
    }

    private fun parsePersonalFeed(json: JsonObject, path: Path): HomeCatalogFeedPlan {
        return HomeCatalogFeedPlan(
            heroResult = parseHeroResult(json.requireJsonObject("hero_result", path), path),
            sections = json.requireJsonArray("sections", path).map { parseSection(it.jsonObject, path) },
            sectionsStatusMessage = json.requireString("sections_status_message", path),
        )
    }

    private fun parseHeroResult(json: JsonObject, path: Path): HomeCatalogHeroResult {
        return HomeCatalogHeroResult(
            items = json.requireJsonArray("items", path).map { parseHeroItem(it.jsonObject, path) },
            statusMessage = json.requireString("status_message", path),
        )
    }

    private fun parseHeroItem(json: JsonObject, path: Path): HomeCatalogHeroItem {
        return HomeCatalogHeroItem(
            id = json.requireString("id", path),
            title = json.requireString("title", path),
            description = json.requireString("description", path),
            rating = json.optionalString("rating", path),
            year = json.optionalString("year", path),
            genres = json.requireJsonArray("genres", path).toStringList(path),
            backdropUrl = json.requireString("backdrop_url", path),
            addonId = json.requireString("addon_id", path),
            type = json.requireString("type", path),
            provider = json.requireString("provider", path),
            providerId = json.requireString("provider_id", path),
        )
    }

    private fun parseSection(json: JsonObject, path: Path): HomeCatalogSection {
        return HomeCatalogSection(
            catalogId = json.requireString("catalog_id", path),
            source = HomeCatalogSource.fromRaw(json.requireString("source", path))
                ?: error("${path.fileName}: invalid section source"),
            presentation = HomeCatalogPresentation.fromRaw(json.optionalString("presentation", path)),
            layout = json.optionalString("layout", path),
            variantKey = json.optionalString("variant_key", path) ?: "default",
            name = json.optionalString("name", path).orEmpty(),
            heading = json.optionalString("heading", path).orEmpty(),
            title = json.optionalString("title", path).orEmpty(),
            subtitle = json.optionalString("subtitle", path).orEmpty(),
        )
    }

    private fun parseDiscover(json: JsonObject, path: Path): Pair<List<HomeCatalogDiscoverRef>, String> {
        return json.requireJsonArray("catalogs", path).map { parseDiscoverRef(it.jsonObject, path) } to
            json.requireString("status_message", path)
    }

    private fun parseDiscoverRef(json: JsonObject, path: Path): HomeCatalogDiscoverRef {
        return HomeCatalogDiscoverRef(
            section = parseSection(json.requireJsonObject("section", path), path),
            addonName = json.requireString("addon_name", path),
            genres = json.requireJsonArray("genres", path).toStringList(path),
        )
    }

    private fun parsePage(json: JsonObject, path: Path): HomeCatalogPageResult {
        return HomeCatalogPageResult(
            items = json.requireJsonArray("items", path).map { parseItem(it.jsonObject, path) },
            statusMessage = json.requireString("status_message", path),
            attemptedUrls = json.requireJsonArray("attempted_urls", path).toStringList(path),
        )
    }
}
