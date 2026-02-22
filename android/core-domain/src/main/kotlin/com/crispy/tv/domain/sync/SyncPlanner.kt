package com.crispy.tv.domain.sync

import java.util.Locale

enum class HouseholdRole {
    OWNER,
    MEMBER,
}

data class RawAddonInstall(
    val url: String?,
    val enabled: Boolean?,
    val name: String?,
)

data class CloudAddonInstall(
    val url: String,
    val enabled: Boolean,
    val name: String?,
)

data class SyncPlannerInput(
    val role: HouseholdRole,
    val pullRequested: Boolean = false,
    val flushRequested: Boolean = false,
    val debounceMs: Long = 2000,
    val nowMs: Long? = null,
    val householdDirty: Boolean,
    val householdChangedAtMs: Long? = null,
    val profileDirty: Boolean,
    val profileChangedAtMs: Long? = null,
    val profileId: String,
    val addons: List<RawAddonInstall>,
    val settings: Map<String, String>,
    val catalogPrefs: Map<String, String>,
    val traktAuth: Map<String, String>,
    val simklAuth: Map<String, String>,
)

sealed interface SyncRpcCall {
    val name: String
}

object GetHouseholdAddonsCall : SyncRpcCall {
    override val name: String = "get_household_addons"
}

data class ReplaceHouseholdAddonsCall(
    val addons: List<CloudAddonInstall>,
) : SyncRpcCall {
    override val name: String = "replace_household_addons"
}

data class UpsertProfileDataCall(
    val profileId: String,
    val settings: Map<String, String>,
    val catalogPrefs: Map<String, String>,
    val traktAuth: Map<String, String>,
    val simklAuth: Map<String, String>,
) : SyncRpcCall {
    override val name: String = "upsert_profile_data"
}

fun planSyncRpcCalls(input: SyncPlannerInput): List<SyncRpcCall> {
    val calls = mutableListOf<SyncRpcCall>()

    if (input.pullRequested && !input.householdDirty) {
        calls.add(GetHouseholdAddonsCall)
    }

    if (
        input.householdDirty &&
        input.role == HouseholdRole.OWNER &&
        isDebounceSatisfied(
            nowMs = input.nowMs,
            changedAtMs = input.householdChangedAtMs,
            debounceMs = input.debounceMs,
            flushRequested = input.flushRequested,
        )
    ) {
        calls.add(ReplaceHouseholdAddonsCall(addons = normalizeAddonsForCloud(input.addons)))
    }

    if (
        input.profileDirty &&
        isDebounceSatisfied(
            nowMs = input.nowMs,
            changedAtMs = input.profileChangedAtMs,
            debounceMs = input.debounceMs,
            flushRequested = input.flushRequested,
        )
    ) {
        calls.add(
            UpsertProfileDataCall(
                profileId = input.profileId.trim(),
                settings = normalizeStringMap(input.settings),
                catalogPrefs = normalizeStringMap(input.catalogPrefs),
                traktAuth = normalizeStringMap(input.traktAuth),
                simklAuth = normalizeStringMap(input.simklAuth),
            )
        )
    }

    return calls
}

private fun isDebounceSatisfied(
    nowMs: Long?,
    changedAtMs: Long?,
    debounceMs: Long,
    flushRequested: Boolean,
): Boolean {
    if (flushRequested) return true
    if (nowMs == null) return true
    if (changedAtMs == null) return true
    return (nowMs - changedAtMs) >= debounceMs
}

fun normalizeAddonsForCloud(addons: List<RawAddonInstall>): List<CloudAddonInstall> {
    data class Sortable(
        val urlLower: String,
        val urlRaw: String,
        val index: Int,
        val addon: CloudAddonInstall,
    )

    val sortable = mutableListOf<Sortable>()

    addons.forEachIndexed { index, raw ->
        val url = (raw.url ?: "")
            .trim()
            .trimEnd('/')

        if (url.isBlank()) return@forEachIndexed

        val addon = CloudAddonInstall(
            url = url,
            enabled = raw.enabled ?: true,
            name = raw.name?.trim()?.takeIf { it.isNotEmpty() },
        )

        sortable.add(
            Sortable(
                urlLower = url.lowercase(Locale.ROOT),
                urlRaw = url,
                index = index,
                addon = addon,
            )
        )
    }

    return sortable
        .sortedWith(compareBy<Sortable>({ it.urlLower }, { it.urlRaw }, { it.index }))
        .map { it.addon }
}

fun normalizeStringMap(raw: Map<String, String>): Map<String, String> {
    return raw.entries
        .mapNotNull { (key, value) ->
            val normalizedKey = key.trim()
            if (normalizedKey.isEmpty()) return@mapNotNull null
            normalizedKey to value.trim()
        }
        .sortedBy { it.first }
        .toMap()
}
