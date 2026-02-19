package com.crispy.rewrite.contracts

import com.crispy.rewrite.domain.sync.CloudAddonInstall
import com.crispy.rewrite.domain.sync.GetHouseholdAddonsCall
import com.crispy.rewrite.domain.sync.HouseholdRole
import com.crispy.rewrite.domain.sync.RawAddonInstall
import com.crispy.rewrite.domain.sync.ReplaceHouseholdAddonsCall
import com.crispy.rewrite.domain.sync.SyncPlannerInput
import com.crispy.rewrite.domain.sync.SyncRpcCall
import com.crispy.rewrite.domain.sync.UpsertProfileDataCall
import com.crispy.rewrite.domain.sync.planSyncRpcCalls
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncPlannerContractTest {
    @Test
    fun fixturesPlanCanonicalRpcCalls() {
        val fixturePaths = ContractTestSupport.fixtureFiles("sync_planner")
        assertTrue(fixturePaths.isNotEmpty(), "Expected at least one sync_planner fixture")

        fixturePaths.forEach { path ->
            val fixture = ContractTestSupport.parseFixture(path)
            val caseId = fixture.requireString("case_id", path)
            assertEquals("sync_planner", fixture.requireString("suite", path), "$caseId: wrong suite")

            val input = fixture.requireJsonObject("input", path)
            val expected = fixture.requireJsonObject("expected", path)

            val actualCalls = planSyncRpcCalls(parseInput(input, path))
            val expectedCalls = parseExpectedCalls(expected, path)

            assertEquals(expectedCalls, actualCalls, "$caseId: rpc_calls")
        }
    }

    private fun parseInput(input: JsonObject, path: Path): SyncPlannerInput {
        val role = when (input.requireString("role", path)) {
            "owner" -> HouseholdRole.OWNER
            "member" -> HouseholdRole.MEMBER
            else -> error("${path.toDisplayPath()}: invalid role")
        }

        val addons = input.requireJsonArray("addons", path)
            .mapIndexed { index, element ->
                val obj = element.jsonObjectOrError(path, index)
                RawAddonInstall(
                    url = obj.requireString("url", path),
                    enabled = obj.optionalBoolean("enabled", path),
                    name = obj.optionalString("name", path),
                )
            }

        return SyncPlannerInput(
            role = role,
            pullRequested = input.optionalBoolean("pull_requested", path) ?: false,
            flushRequested = input.optionalBoolean("flush_requested", path) ?: false,
            debounceMs = input.optionalLong("debounce_ms", path) ?: 2000,
            nowMs = input.optionalLong("now_ms", path),
            householdDirty = input.requireBoolean("household_dirty", path),
            householdChangedAtMs = input.optionalLong("household_changed_at_ms", path),
            profileDirty = input.requireBoolean("profile_dirty", path),
            profileChangedAtMs = input.optionalLong("profile_changed_at_ms", path),
            profileId = input.requireString("profile_id", path),
            addons = addons,
            settings = input.requireJsonObject("settings", path).toStringMap(path, "settings"),
            catalogPrefs = input.requireJsonObject("catalog_prefs", path).toStringMap(path, "catalog_prefs"),
            traktAuth = input.requireJsonObject("trakt_auth", path).toStringMap(path, "trakt_auth"),
            simklAuth = input.requireJsonObject("simkl_auth", path).toStringMap(path, "simkl_auth"),
        )
    }

    private fun parseExpectedCalls(expected: JsonObject, path: Path): List<SyncRpcCall> {
        val calls = expected.requireJsonArray("rpc_calls", path)

        return calls.mapIndexed { index, element ->
            val obj = element.jsonObjectOrError(path, index)
            val name = obj.requireString("name", path)
            val params = obj.requireJsonObject("params", path)

            when (name) {
                "get_household_addons" -> {
                    // Params must be an empty object (enforced by schema).
                    GetHouseholdAddonsCall
                }

                "replace_household_addons" -> {
                    val addons = params.requireJsonArray("p_addons", path)
                        .mapIndexed { addonIndex, addonElement ->
                            val addonObj = addonElement.jsonObjectOrError(path, addonIndex)
                            CloudAddonInstall(
                                url = addonObj.requireString("url", path),
                                enabled = addonObj.requireBoolean("enabled", path),
                                name = addonObj.optionalString("name", path),
                            )
                        }
                    ReplaceHouseholdAddonsCall(addons = addons)
                }

                "upsert_profile_data" -> {
                    UpsertProfileDataCall(
                        profileId = params.requireString("p_profile_id", path),
                        settings = params.requireJsonObject("p_settings", path).toStringMap(path, "p_settings"),
                        catalogPrefs = params.requireJsonObject("p_catalog_prefs", path).toStringMap(path, "p_catalog_prefs"),
                        traktAuth = params.requireJsonObject("p_trakt_auth", path).toStringMap(path, "p_trakt_auth"),
                        simklAuth = params.requireJsonObject("p_simkl_auth", path).toStringMap(path, "p_simkl_auth"),
                    )
                }

                else -> error("${path.toDisplayPath()}: unknown rpc call name '$name'")
            }
        }
    }
}

private fun JsonElement.jsonObjectOrError(path: Path, index: Int): JsonObject {
    return this as? JsonObject
        ?: error("${path.toDisplayPath()}: expected object at index $index")
}

private fun JsonObject.optionalBoolean(key: String, path: Path): Boolean? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive
        ?: error("${path.toDisplayPath()}: '$key' must be boolean or null")
    return primitive.booleanOrNull
        ?: error("${path.toDisplayPath()}: '$key' must be boolean or null")
}

private fun JsonObject.optionalLong(key: String, path: Path): Long? {
    val value = this[key] ?: return null
    if (value is JsonNull) return null
    val primitive = value as? JsonPrimitive
        ?: error("${path.toDisplayPath()}: '$key' must be integer or null")
    return primitive.longOrNull
        ?: error("${path.toDisplayPath()}: '$key' must be integer or null")
}

private fun JsonObject.toStringMap(path: Path, label: String): Map<String, String> {
    return entries.associate { (key, element) ->
        val primitive = element as? JsonPrimitive
            ?: error("${path.toDisplayPath()}: '$label.$key' must be string")
        key to primitive.content
    }
}
