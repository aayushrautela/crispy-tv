package com.crispy.tv.details

import com.crispy.tv.accounts.AccountApi
import com.crispy.tv.addons.mapping.seasonNumbers
import com.crispy.tv.addons.model.MediaDetails
import com.crispy.tv.backend.BackendApi
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.catalog.toCatalogItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Fetches the two "extras" that hang off a details page: the season numbers a series
 * has, and the recommended items shown in the "More like this" rail.
 *
 * ## Why this is in `commonMain` and the view model was not
 *
 * The plan filed the `PlayerSessionViewModel` split under Phase 4 on the stated ground
 * that its collaborators are Android types. Measured, that is true of the file and
 * false of this cluster. Every type the two methods touch is already shared:
 * `MediaDetails` and `seasonNumbers` are in `:android:addons`' `commonMain`,
 * `BackendApi` and `MetadataTitleExtrasResponse` are in `:android:backend`'s
 * `commonMain` (`CrispyBackendClient` merely *implements* the interface), `CatalogItem`
 * is in `:android:home`'s `commonMain`, and `toCatalogItem` is in `:app`'s own
 * `commonMain`. What actually blocked the move is `PlayerUiState`, which the loader
 * cannot see, so the write-back is expressed as the four narrow slots below.
 *
 * ## The one platform fact, stated because it is not visible from `commonMain`
 *
 * Both methods originally hopped to `Dispatchers.IO`, and **`Dispatchers.IO` does not
 * exist in `commonMain`** — it is declared in the JVM and Native source sets, so
 * `commonMain` sees only `Dispatchers.Default` and `Dispatchers.Main`. The dispatcher
 * is therefore a required slot supplied by the platform side, and `:android:app` passes
 * `Dispatchers.IO` to preserve the threads these calls actually ran on. Choosing
 * `Default` as a default would compile everywhere and silently move blocking I/O onto
 * a pool sized for CPU work.
 */
class DetailsMetadataLoader(
    private val accountApi: AccountApi,
    private val backendApi: BackendApi,
    private val scope: CoroutineScope,
    /**
     * The platform's I/O dispatcher. Required rather than defaulted: see the class
     * KDoc. A caller that passed `Dispatchers.Default` would be a behaviour change,
     * not a simplification.
     */
    private val ioDispatcher: CoroutineDispatcher,
    /** Publishes the season numbers a series has. */
    private val onSeasons: (List<Int>) -> Unit,
    /** Publishes whether the "More like this" rail is fetching. */
    private val onMoreLoading: (Boolean) -> Unit,
    /** Publishes the recommended items, already filtered and de-duplicated. */
    private val onRecommended: (List<CatalogItem>) -> Unit,
    /**
     * The details currently on screen, read lazily because the loader outlives any one
     * page. `null` while no title is open.
     */
    private val currentDetails: () -> MediaDetails?,
) {

    /**
     * Whether the recommendations have already been requested for this page.
     *
     * Owned here rather than in the view model because the "fetch once" rule belongs
     * to the fetch, not to the screen: a view model that owned it would have to expose
     * it for the loader to consult, and the next caller of the loader would then have
     * to know to consult it too.
     */
    private var titleExtrasFetched = false

    /**
     * Publishes the season numbers for [itemId].
     *
     * A blank id, a missing session and a failed request all publish nothing rather
     * than publishing an empty list, because the previous seasons stay on screen and
     * an empty list would clear them.
     */
    fun fetchSeasons(itemId: String?) {
        val id = itemId?.trim()?.takeIf { it.isNotBlank() } ?: return
        scope.launch {
            val session = runCatching { withContext(ioDispatcher) { accountApi.ensureValidSession() } }
                .getOrNull() ?: return@launch
            val extras = runCatching {
                withContext(ioDispatcher) {
                    backendApi.getMetadataItemExtras(accessToken = session.accessToken, itemId = id)
                }
            }.getOrNull() ?: return@launch
            val numbers = extras.seasonNumbers()
            if (numbers.isEmpty()) return@launch
            onSeasons(numbers)
        }
    }

    /**
     * Publishes the recommended items for [itemId], at most once per page.
     *
     * The second call is dropped by [titleExtrasFetched] rather than by a comparison
     * of the published list, so a repeat call cannot re-enter the network even if the
     * first produced nothing.
     */
    fun fetchTitleExtras(itemId: String?) {
        val id = itemId?.trim()?.takeIf { it.isNotBlank() } ?: return
        if (titleExtrasFetched) return
        titleExtrasFetched = true
        // Published before the launch, not inside it: the details screen shows a
        // spinner the moment the user scrolls to the rail, and a coroutine that has
        // not started yet would leave that scroll with nothing for a frame.
        onMoreLoading(true)
        scope.launch {
            val session = runCatching { withContext(ioDispatcher) { accountApi.ensureValidSession() } }
                .getOrNull()
            val extras = if (session == null) {
                null
            } else {
                runCatching {
                    withContext(ioDispatcher) {
                        backendApi.getMetadataItemExtras(accessToken = session.accessToken, itemId = id)
                    }
                }.getOrNull()
            }
            if (extras == null) {
                onMoreLoading(false)
                return@launch
            }
            val currentKeys = buildSet {
                add(id)
                detailsIdKeys()?.let(::addAll)
            }
            val recommended = extras.lists
                .flatMap { it.items }
                .mapNotNull { it.toCatalogItem() }
                .filter { it.itemId !in currentKeys }
                .distinctBy { "${it.type}:${it.id}" }
            onRecommended(recommended)
            onMoreLoading(false)
        }
    }

    /**
     * The ids that identify the title already on screen, so a recommendation of it is
     * not suggested back to the user.
     *
     * Both the catalogue id and the addon id are included because the same title can
     * arrive under either: the details page was opened with one and the backend may
     * return the other in a "more like this" list. `null` when no title is open, which
     * the caller reads as "add nothing" rather than as "match everything".
     */
    private fun detailsIdKeys(): Set<String>? {
        val details = currentDetails() ?: return null
        return buildSet {
            details.itemId?.trim()?.takeIf { it.isNotBlank() }?.let(::add)
            details.id.trim().takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}
