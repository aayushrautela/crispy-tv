package com.crispy.tv.addons.registry

import com.crispy.tv.addons.optJsonArray
import com.crispy.tv.addons.optLongOrNull
import com.crispy.tv.addons.optStringOrEmpty
import com.crispy.tv.addons.stringAtOrEmpty
import com.crispy.tv.platform.KeyValueStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlin.concurrent.Volatile
import okio.ByteString.Companion.encodeUtf8

data class AddonManifestSeed(
    val installationId: String,
    val manifestUrl: String,
    val originalManifestUrl: String,
    val addonIdHint: String,
    val baseUrl: String,
    val encodedQuery: String?,
    val cachedManifestJson: String?
)

data class CloudAddonRow(
    val manifestUrl: String,
    val sortOrder: Int
)

/**
 * The installed-addon registry: which manifest URLs are installed, in what order,
 * their cached manifest JSON, and which addons the user removed.
 *
 * ## The four pins this file was moved out of `androidMain` for
 *
 * It was 573 lines in `androidMain` behind four things, and **three of them were
 * constructor or call arguments rather than walls** -- which is the shape the
 * `:backend` ports established and the reason this file was re-audited instead of
 * re-described:
 *
 * - **`Context`, for one `SharedPreferences` file.** That is `:platform-core`'s
 *   `KeyValueStore`, already implemented on Android by
 *   `SharedPreferencesKeyValueStore` and already used by `:app`, `:backend` and
 *   `:watchhistory`. It arrives as a [store] slot with no default, and
 *   `metadataAddonRegistry(context)` in `androidMain` is the only place the
 *   platform type is named.
 * - **`System.currentTimeMillis()`, twice, at `:81` and `:222`.** It needs no
 *   import, so no token scan can see it, and it is the pin that survives a
 *   mechanical read of the import list. It is [nowMs], with no default, exactly
 *   as `:backend`'s `AccountSessionStore` took one.
 * - **`@Volatile` and `@Synchronized`, seven sites**, which resolved from the JVM's
 *   default import of `kotlin.jvm.*` -- a default import a `commonMain` file does
 *   not get. **The two replacements do not come from the same place and only the
 *   compiler knows that**: `@Volatile` is `kotlin.concurrent.Volatile` and works,
 *   while the newer `kotlin.concurrent.Synchronized` does not resolve at all
 *   (`Unresolved reference 'Synchronized'`) and `kotlin.jvm.Synchronized` is
 *   rejected as an *error* by `compileKotlinLinuxX64`. So the answer is
 *   `@JvmSynchronized`, an `@OptionalExpectation` annotation typealiased to
 *   `kotlin.jvm.Synchronized` on Android -- see its KDoc for what that does and
 *   does not guarantee on the other targets. **This is the `Dispatchers.IO` rule
 *   arriving through a different symbol: a green JVM build proves nothing here,
 *   and `compileKotlinLinuxX64` is the gate that saw it.**
 * - **`android.net.Uri`.** The one real wall: no URL parser exists in any
 *   `commonMain` in this repository, and `core-domain`'s `normalizeAddonUrl` is a
 *   stricter *rule*, not a parser. [ManifestUri] is the replacement, written
 *   against `UriBehaviourHostTest`'s 26-row table -- which is also why that
 *   measurement ran first. See [ManifestUri] for the three findings.
 *
 * `MessageDigest("SHA-1")` and `StandardCharsets.UTF_8` went at the same time as
 * `Uri`, and they were the one place where *merely equivalent* was not good
 * enough: [installationId] is half of every persisted addon identity, so a digest
 * that merely computed the same function would re-identify every installed addon
 * on every device. Its ten goldens in `MetadataAddonRegistryTest` were produced by
 * running the old `MessageDigest` code on the JVM and are compared against okio's
 * `sha1()`.
 */
class MetadataAddonRegistry(
    private val store: KeyValueStore,
    private val nowMs: () -> Long,
) {
    @Volatile
    private var cachedState: RegistryState? = null

    @JvmSynchronized
    fun orderedSeeds(): List<AddonManifestSeed> {
        val state = ensureState()
        return state.addonOrder.mapNotNull { installationId ->
            state.installedAddons[installationId]?.toManifestSeed()
        }
    }

    @JvmSynchronized
    fun exportCloudAddons(): List<CloudAddonRow> {
        val state = ensureState()
        return state.addonOrder.mapIndexedNotNull { index, installationId ->
            val addon = state.installedAddons[installationId] ?: return@mapIndexedNotNull null
            CloudAddonRow(
                manifestUrl = addon.manifestUrl,
                sortOrder = index
            )
        }
    }

    @JvmSynchronized
    fun reconcileCloudAddons(rows: List<CloudAddonRow>): Int {
        if (rows.isEmpty()) {
            return 0
        }

        val parsedRows =
            rows
                .sortedWith(compareBy<CloudAddonRow> { it.sortOrder }.thenBy { it.manifestUrl.lowercase() })
                .mapNotNull { row ->
                    parseManifestSeed(
                        manifestUrl = row.manifestUrl,
                        addonIdHintOverride = null
                    )
                }
        if (parsedRows.isEmpty()) {
            return 0
        }

        val state = ensureState()
        val now = nowMs()
        val installed = linkedMapOf<String, PersistedAddon>()
        val orderedInstallations = mutableListOf<String>()
        var includesOpenSubtitles = false

        parsedRows.forEach { seed ->
            includesOpenSubtitles =
                includesOpenSubtitles ||
                    seed.addonIdHint.equals(DEFAULT_OPENSUBTITLES_ADDON_ID, ignoreCase = true) ||
                    seed.manifestUrl.contains("opensubtitles", ignoreCase = true)

            val existing = state.installedAddons[seed.installationId]
            installed[seed.installationId] =
                if (existing == null) {
                    PersistedAddon(
                        installationId = seed.installationId,
                        addonIdHint = seed.addonIdHint,
                        manifestUrl = seed.manifestUrl,
                        originalManifestUrl = seed.originalManifestUrl,
                        baseUrl = seed.baseUrl,
                        encodedQuery = seed.encodedQuery,
                        addedAtEpochMs = now,
                        cachedManifestJson = null,
                        manifestAddonId = null,
                        manifestVersion = null
                    )
                } else {
                    existing.copy(
                        addonIdHint = seed.addonIdHint,
                        manifestUrl = seed.manifestUrl,
                        originalManifestUrl = seed.originalManifestUrl,
                        baseUrl = seed.baseUrl,
                        encodedQuery = seed.encodedQuery
                    )
                }

            if (seed.installationId !in orderedInstallations) {
                orderedInstallations += seed.installationId
            }
        }

        val nextRemovedIds =
            state.userRemovedAddonIds.filterNot { removedId ->
                includesOpenSubtitles && removedId.equals(DEFAULT_OPENSUBTITLES_ADDON_ID, ignoreCase = true)
            }.toSet()

        persistState(
            state.copy(
                installedAddons = LinkedHashMap(installed),
                addonOrder = orderedInstallations,
                userRemovedAddonIds = nextRemovedIds
            )
        )

        return orderedInstallations.size
    }

    @JvmSynchronized
    fun cacheManifest(seed: AddonManifestSeed, manifest: JsonObject) {
        val state = ensureState()
        val existing = state.installedAddons[seed.installationId] ?: return
        val updated = existing.copy(
            cachedManifestJson = manifest.toString(),
            manifestAddonId = nonBlank(manifest.optStringOrEmpty("id")) ?: existing.manifestAddonId,
            manifestVersion = nonBlank(manifest.optStringOrEmpty("version")) ?: existing.manifestVersion
        )
        if (updated == existing) {
            return
        }

        val installed = LinkedHashMap(state.installedAddons)
        installed[seed.installationId] = updated
        persistState(state.copy(installedAddons = installed))
    }

    @JvmSynchronized
    fun markAddonRemoved(addonId: String) {
        if (addonId.isBlank()) {
            return
        }

        val state = ensureState()
        val removedIds = state.userRemovedAddonIds.toMutableSet()
        if (!removedIds.add(addonId.trim())) {
            return
        }

        val installed = LinkedHashMap(state.installedAddons)
        val removedInstallations =
            installed.values
                .filter { addon -> addon.matchesAddonId(addonId) }
                .map { addon -> addon.installationId }
                .toSet()
        if (removedInstallations.isNotEmpty()) {
            removedInstallations.forEach(installed::remove)
        }

        val nextOrder =
            state.addonOrder
                .filterNot { installationId -> removedInstallations.contains(installationId) }
                .distinct()

        persistState(
            state.copy(
                installedAddons = installed,
                addonOrder = nextOrder,
                userRemovedAddonIds = removedIds
            )
        )
    }

    @JvmSynchronized
    private fun ensureState(): RegistryState {
        val existing = cachedState ?: readStateFromPrefs()
        val next = normalizeState(existing)
        if (next != existing || cachedState == null) {
            persistState(next)
        } else {
            cachedState = next
        }
        return next
    }

    private fun normalizeState(state: RegistryState): RegistryState {
        val installed = LinkedHashMap(state.installedAddons)
        val userRemovedAddonIds = state.userRemovedAddonIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()

        val defaultRemovalTargets =
            setOf(DEFAULT_OPENSUBTITLES_ADDON_ID)
                .filter { id -> userRemovedAddonIds.any { it.equals(id, ignoreCase = true) } }
                .toSet()
        if (defaultRemovalTargets.isNotEmpty()) {
            val installationIdsToRemove =
                installed.values
                    .filter { addon -> defaultRemovalTargets.any { target -> addon.matchesAddonId(target) } }
                    .map { addon -> addon.installationId }
            installationIdsToRemove.forEach(installed::remove)
        }

        val desiredSeeds = buildDesiredSeeds(userRemovedAddonIds)
        val now = nowMs()
        desiredSeeds.forEach { desired ->
            val existing = installed[desired.installationId]
            if (existing == null) {
                installed[desired.installationId] =
                    PersistedAddon(
                        installationId = desired.installationId,
                        addonIdHint = desired.addonIdHint,
                        manifestUrl = desired.manifestUrl,
                        originalManifestUrl = desired.originalManifestUrl,
                        baseUrl = desired.baseUrl,
                        encodedQuery = desired.encodedQuery,
                        addedAtEpochMs = now,
                        cachedManifestJson = null,
                        manifestAddonId = null,
                        manifestVersion = null
                    )
            } else {
                installed[desired.installationId] =
                    existing.copy(
                        addonIdHint = desired.addonIdHint,
                        manifestUrl = desired.manifestUrl,
                        originalManifestUrl = desired.originalManifestUrl,
                        baseUrl = desired.baseUrl,
                        encodedQuery = desired.encodedQuery
                    )
            }
        }

        val orderedIds = mutableListOf<String>()
        state.addonOrder.forEach { installationId ->
            if (installationId !in orderedIds && installed.containsKey(installationId)) {
                orderedIds += installationId
            }
        }

        val opensubtitlesIds =
            installed.values
                .filter { addon -> addon.matchesAddonId(DEFAULT_OPENSUBTITLES_ADDON_ID) }
                .sortedWith(compareBy<PersistedAddon> { it.addedAtEpochMs }.thenBy { it.installationId })
                .map { addon -> addon.installationId }
        opensubtitlesIds.forEach { installationId ->
            if (installationId !in orderedIds) {
                orderedIds += installationId
            }
        }

        installed.values
            .sortedWith(compareBy<PersistedAddon> { it.addedAtEpochMs }.thenBy { it.installationId })
            .forEach { addon ->
                if (addon.installationId !in orderedIds) {
                    orderedIds += addon.installationId
                }
            }

        return RegistryState(
            installedAddons = installed,
            addonOrder = orderedIds,
            userRemovedAddonIds = userRemovedAddonIds
        )
    }

    private fun buildDesiredSeeds(userRemovedAddonIds: Set<String>): List<ParsedAddonSeed> {
        val parsedSeeds = mutableListOf<ParsedAddonSeed>()

        if (userRemovedAddonIds.none { it.equals(DEFAULT_OPENSUBTITLES_ADDON_ID, ignoreCase = true) }) {
            parseManifestSeed(
                manifestUrl = DEFAULT_OPENSUBTITLES_MANIFEST,
                addonIdHintOverride = DEFAULT_OPENSUBTITLES_ADDON_ID
            )?.let(parsedSeeds::add)
        }

        val unique = LinkedHashMap<String, ParsedAddonSeed>()
        parsedSeeds.forEach { seed ->
            val key = seed.manifestUrl.lowercase()
            if (key !in unique) {
                unique[key] = seed
            }
        }
        return unique.values.toList()
    }

    private fun parseManifestSeed(
        manifestUrl: String,
        addonIdHintOverride: String?
    ): ParsedAddonSeed? {
        val input = manifestUrl.trim()
        if (input.isEmpty()) {
            return null
        }

        val normalizedInput =
            when {
                input.startsWith("stremio://", ignoreCase = true) -> "https://${input.substringAfter("://")}"
                URI_SCHEME_REGEX.containsMatchIn(input) -> input
                else -> "https://$input"
            }

        // `ManifestUri.parse` needs a scheme and a non-blank host, and every branch
        // of the normalization above guarantees the scheme: `stremio://` is
        // rewritten, a `URI_SCHEME_REGEX` match keeps its own, everything else is
        // prefixed. **That guarantee is why the old `buildUpon().scheme("https")`
        // arm for a null/blank/`stremio` scheme is deleted rather than ported** --
        // it was unreachable, and carrying it over would be porting dead code.
        // `UriBehaviourHostTest` measured all 26 shapes to establish that.
        val uri = ManifestUri.parse(normalizedInput) ?: return null

        val host = uri.host
        val pathSegments = uri.pathSegments
        val basePath =
            if (pathSegments.lastOrNull().equals("manifest.json", ignoreCase = true)) {
                pathSegments.dropLast(1)
            } else {
                pathSegments
            }

        val baseUrl = uri.baseUrlFor(basePath)

        val addonIdHint =
            addonIdHintOverride
                ?: when {
                    host.contains("cinemeta", ignoreCase = true) ||
                        input.contains("cinemeta", ignoreCase = true) -> DEFAULT_CINEMETA_ADDON_ID
                    host.contains("opensubtitles", ignoreCase = true) ||
                        input.contains("opensubtitles", ignoreCase = true) -> DEFAULT_OPENSUBTITLES_ADDON_ID
                    else -> host
                }

        val normalizedManifestUrl = uri.toString()
        return ParsedAddonSeed(
            installationId = installationId(addonIdHint = addonIdHint, manifestUrl = normalizedManifestUrl),
            addonIdHint = addonIdHint,
            manifestUrl = normalizedManifestUrl,
            originalManifestUrl = input,
            baseUrl = baseUrl,
            encodedQuery = uri.encodedQuery
        )
    }

    private fun installationId(addonIdHint: String, manifestUrl: String): String {
        // `MessageDigest("SHA-1")` over `StandardCharsets.UTF_8`, then the first
        // **six bytes** rendered as lower-case hex by `"%02x".format(byte)`.
        // okio's `ByteString.hex()` is already lower-case, so the first 12
        // characters of the full 40 are those twelve digits -- *measured*, not
        // assumed: see the ten goldens in `MetadataAddonRegistryTest`, which were
        // produced by running the code this replaces. A digest that merely
        // computed the same function would still re-identify every installed addon.
        val hash = manifestUrl.lowercase().encodeUtf8().sha1().hex().take(SHA1_HASH_CHARS)
        val normalizedHint = addonIdHint.lowercase().replace(NON_ID_CHARS_REGEX, "-").trim('-')
            .ifEmpty { "addon" }
        return "$normalizedHint:$hash"
    }

    private fun persistState(state: RegistryState) {
        store.putString(KEY_STATE, state.toJson().toString())
        cachedState = state
    }

    private fun readStateFromPrefs(): RegistryState {
        val raw = store.getString(KEY_STATE, null) ?: return RegistryState.empty()
        return runCatching {
            // `Json.parseToJsonElement` raises `SerializationException` where
            // `JSONObject(String)` raised `JSONException`; the surrounding
            // `runCatching` absorbs either.
            RegistryState.fromJson(Json.parseToJsonElement(raw).jsonObject)
        }.getOrElse {
            RegistryState.empty()
        }
    }

    companion object {
        /**
         * The `SharedPreferences` file's name on Android, and the [KeyValueStore]
         * name everywhere else. It is a name and not an Android type on purpose:
         * the `SharedPreferences` *file* it names is created by
         * `metadataAddonRegistry(context)` in `androidMain`, and that constant is
         * the only thing the two halves have to agree on.
         */
        const val STORE_NAME = "metadata_addon_registry"

        private const val KEY_STATE = "state_json"

        /** Six bytes of SHA-1, rendered as hex. */
        private const val SHA1_HASH_CHARS = 12

        private const val DEFAULT_CINEMETA_ADDON_ID = "com.linvo.cinemeta"
        private const val DEFAULT_OPENSUBTITLES_ADDON_ID = "org.stremio.opensubtitlesv3"
        private const val DEFAULT_OPENSUBTITLES_MANIFEST = "stremio://opensubtitles-v3.strem.io/manifest.json"

        private val URI_SCHEME_REGEX = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://")
        private val NON_ID_CHARS_REGEX = Regex("[^a-z0-9._-]")
    }
}

private data class ParsedAddonSeed(
    val installationId: String,
    val addonIdHint: String,
    val manifestUrl: String,
    val originalManifestUrl: String,
    val baseUrl: String,
    val encodedQuery: String?
)

private data class PersistedAddon(
    val installationId: String,
    val addonIdHint: String,
    val manifestUrl: String,
    val originalManifestUrl: String,
    val baseUrl: String,
    val encodedQuery: String?,
    val addedAtEpochMs: Long,
    val cachedManifestJson: String?,
    val manifestAddonId: String?,
    val manifestVersion: String?
) {
    fun toManifestSeed(): AddonManifestSeed {
        return AddonManifestSeed(
            installationId = installationId,
            manifestUrl = manifestUrl,
            originalManifestUrl = originalManifestUrl,
            addonIdHint = manifestAddonId ?: addonIdHint,
            baseUrl = baseUrl,
            encodedQuery = encodedQuery,
            cachedManifestJson = cachedManifestJson
        )
    }

    fun matchesAddonId(addonId: String): Boolean {
        if (manifestAddonId?.equals(addonId, ignoreCase = true) == true) {
            return true
        }
        if (addonIdHint.equals(addonId, ignoreCase = true)) {
            return true
        }

        return when {
            addonId.equals("com.linvo.cinemeta", ignoreCase = true) ->
                manifestUrl.contains("cinemeta", ignoreCase = true) ||
                    baseUrl.contains("cinemeta", ignoreCase = true)
            addonId.equals("org.stremio.opensubtitlesv3", ignoreCase = true) ->
                manifestUrl.contains("opensubtitles", ignoreCase = true) ||
                    baseUrl.contains("opensubtitles", ignoreCase = true)
            else -> false
        }
    }

    /**
     * **A null field is an ABSENT key here, and that is not a detail.**
     *
     * `org.json`'s `put(key, null)` *removes* the mapping, so the five nullable
     * fields were written by omitting them. `buildJsonObject`'s
     * `put(key, value: String?)` does the opposite: it writes `JsonNull`. And the
     * two are not the same on the way back in — an absent key answers `""` to
     * `optString`, while a stored JSON null answered the four characters
     * `"null"` on AOSP. **So a plain `put(…, null)` would have changed what this
     * file reads back, and the `?.let` below is what keeps the old answer.**
     */
    fun toJson(): JsonObject {
        return buildJsonObject {
            put("installation_id", installationId)
            put("addon_id_hint", addonIdHint)
            put("manifest_url", manifestUrl)
            originalManifestUrl?.let { put("original_manifest_url", it) }
            put("base_url", baseUrl)
            encodedQuery?.let { put("encoded_query", it) }
            put("added_at_epoch_ms", addedAtEpochMs)
            cachedManifestJson?.let { put("cached_manifest_json", it) }
            manifestAddonId?.let { put("manifest_addon_id", it) }
            manifestVersion?.let { put("manifest_version", it) }
        }
    }

    companion object {
        fun fromJson(json: JsonObject): PersistedAddon {
            return PersistedAddon(
                installationId = json.optStringOrEmpty("installation_id"),
                addonIdHint = json.optStringOrEmpty("addon_id_hint"),
                manifestUrl = json.optStringOrEmpty("manifest_url"),
                originalManifestUrl = json.optStringOrEmpty("original_manifest_url"),
                baseUrl = json.optStringOrEmpty("base_url"),
                encodedQuery = json.optStringOrEmpty("encoded_query").takeIf { it.isNotBlank() },
                // `longOrNull` *parses* where `optLong` *truncated*. The
                // difference is invisible for a whole number, and this file
                // writes nothing but a `Long`, so the answer is the same.
                addedAtEpochMs = json.optLongOrNull("added_at_epoch_ms") ?: 0L,
                cachedManifestJson = json.optStringOrEmpty("cached_manifest_json").takeIf { it.isNotBlank() },
                manifestAddonId = json.optStringOrEmpty("manifest_addon_id").takeIf { it.isNotBlank() },
                manifestVersion = json.optStringOrEmpty("manifest_version").takeIf { it.isNotBlank() }
            )
        }
    }
}

private data class RegistryState(
    val installedAddons: LinkedHashMap<String, PersistedAddon>,
    val addonOrder: List<String>,
    val userRemovedAddonIds: Set<String>
) {
    fun toJson(): JsonObject {
        // `orderArray::put` was a method reference to `JSONArray.put`; `add` is
        // the builder's equivalent and takes the element directly.
        return buildJsonObject {
            put("installed_addons", buildJsonArray { installedAddons.values.forEach { add(it.toJson()) } })
            put("addon_order", buildJsonArray { addonOrder.forEach { add(JsonPrimitive(it)) } })
            put(
                "user_removed_addons",
                buildJsonArray { userRemovedAddonIds.sorted().forEach { add(JsonPrimitive(it)) } },
            )
        }
    }

    companion object {
        fun empty(): RegistryState {
            return RegistryState(
                installedAddons = linkedMapOf(),
                addonOrder = emptyList(),
                userRemovedAddonIds = emptySet()
            )
        }

        fun fromJson(json: JsonObject): RegistryState {
            val installed = linkedMapOf<String, PersistedAddon>()
            val installedArray = json.optJsonArray("installed_addons") ?: JsonArray(emptyList())
            for (element in installedArray) {
                val objectValue = element as? JsonObject ?: continue
                val addon = PersistedAddon.fromJson(objectValue)
                if (addon.installationId.isNotBlank() && addon.manifestUrl.isNotBlank()) {
                    installed[addon.installationId] = addon
                }
            }

            val addonOrder = mutableListOf<String>()
            val orderArray = json.optJsonArray("addon_order") ?: JsonArray(emptyList())
            for (index in 0 until orderArray.size) {
                val installationId = orderArray.stringAtOrEmpty(index).trim()
                if (installationId.isNotEmpty()) {
                    addonOrder += installationId
                }
            }

            val removedIds = mutableSetOf<String>()
            val removedArray = json.optJsonArray("user_removed_addons") ?: JsonArray(emptyList())
            for (index in 0 until removedArray.size) {
                val addonId = removedArray.stringAtOrEmpty(index).trim()
                if (addonId.isNotEmpty()) {
                    removedIds += addonId
                }
            }

            return RegistryState(
                installedAddons = installed,
                addonOrder = addonOrder,
                userRemovedAddonIds = removedIds
            )
        }
    }
}

private fun nonBlank(value: String?): String? {
    val trimmed = value?.trim()
    return if (trimmed.isNullOrEmpty()) null else trimmed
}
