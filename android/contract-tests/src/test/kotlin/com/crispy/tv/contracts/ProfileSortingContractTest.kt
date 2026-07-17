package com.crispy.tv.contracts

import com.crispy.tv.domain.account.ProfileSortInput
import com.crispy.tv.domain.account.sortProfiles
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileSortingContractTest {
    @Test
    fun profileSortingFixtures() {
        ContractTestSupport.fixtureFiles("profile_sorting").forEach { path ->
            val root = ContractTestSupport.parseFixture(path)
            assertEquals("profile_sorting", root.requireString("suite", path))
            val input = root.requireJsonObject("input", path)
            val expected = root.requireJsonObject("expected", path)

            val profiles = input.requireJsonArray("profiles", path).map { entry ->
                val obj = entry as? JsonObject
                    ?: error("${path.toDisplayPath()}: profile must be object")
                ProfileSortInput(
                    id = obj.requireString("id", path),
                    name = obj.requireString("name", path),
                    isKids = obj.requireBoolean("is_kids", path),
                    lastUsedMs = obj.optionalLong("last_used_ms", path)
                )
            }

            val actual = sortProfiles(profiles)
            val expectedIds = expected.requireJsonArray("ordered_ids", path).toStringList(path)
            assertEquals(expectedIds, actual, "Order mismatch in ${path.toDisplayPath()}")
        }
    }
}

