package com.crispy.tv.contracts

import com.crispy.tv.domain.metadata.normalizeNuvioMediaId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MediaIdsContractTest {
    @Test
    fun fixturesMatchNuvioNormalization() {
        val fixturePaths = ContractTestSupport.fixtureFiles("media_ids")
        assertTrue(fixturePaths.isNotEmpty(), "Expected at least one media_ids fixture")

        fixturePaths.forEach { path ->
            val fixture = ContractTestSupport.parseFixture(path)
            val caseId = fixture.requireString("case_id", path)
            assertEquals("media_ids", fixture.requireString("suite", path), "$caseId: wrong suite")

            val input = fixture.requireJsonObject("input", path)
            val expected = fixture.requireJsonObject("expected", path)
            val actual = normalizeNuvioMediaId(input.requireString("raw", path))

            assertEquals(expected.requireString("content_id", path), actual.contentId, "$caseId: content_id")
            assertEquals(expected.optionalString("video_id", path), actual.videoId, "$caseId: video_id")
            assertEquals(expected.requireBoolean("is_episode", path), actual.isEpisode, "$caseId: is_episode")
            assertEquals(expected.optionalInt("season", path), actual.season, "$caseId: season")
            assertEquals(expected.optionalInt("episode", path), actual.episode, "$caseId: episode")
            assertEquals(expected.requireString("kind", path), actual.kind, "$caseId: kind")
            assertEquals(
                expected.requireString("addon_lookup_id", path),
                actual.addonLookupId,
                "$caseId: addon_lookup_id"
            )
        }
    }
}
