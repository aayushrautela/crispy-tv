package com.crispy.tv.contracts

import com.crispy.tv.domain.account.ProfileSettings
import com.crispy.tv.domain.account.mergeProfileSettings
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class ProfileSettingsSyncContractTest {
    @Test
    fun profileSettingsSyncFixtures() {
        ContractTestSupport.fixtureFiles("profile_settings_sync").forEach { path ->
            val root = ContractTestSupport.parseFixture(path)
            assertEquals("profile_settings_sync", root.requireString("suite", path))
            val input = root.requireJsonObject("input", path)
            val expected = root.requireJsonObject("expected", path)

            val local = parseSettings(input.requireJsonObject("local", path), path)
            val server = parseSettings(input.requireJsonObject("server", path), path)
            val merged = mergeProfileSettings(local, server)

            val expectedMerged = parseSettings(expected.requireJsonObject("merged", path), path)
            assertEquals(expectedMerged, merged, "Merged mismatch in ${path.toDisplayPath()}")
        }
    }

    private fun parseSettings(obj: JsonObject, path: java.nio.file.Path): ProfileSettings {
        return ProfileSettings(
            displayName = obj.optionalString("display_name", path),
            avatarUrl = obj.optionalString("avatar_url", path),
            syncProvider = obj.optionalString("sync_provider", path),
            onboardingStep = obj.optionalString("onboarding_step", path),
            onboardingCompletedAtMs = obj.optionalLong("onboarding_completed_at_ms", path)
        )
    }
}
