package com.crispy.tv.search

import com.crispy.tv.app.AppGraph
import java.util.Locale
import kotlinx.coroutines.Dispatchers

/**
 * The collaborators of [SearchViewModel] — the half of the factory that `jvmMain` shares
 * between the Android and the desktop target, and the only half worth sharing.
 *
 * ## Why this file is `jvmMain` and not `commonMain` or `androidMain`
 *
 * It used to be the whole `androidMain` `searchViewModelFactory`, which took a `Context` whose
 * only use was reaching `appGraph().graph`. The collaborators are already `commonMain` — the two
 * search repositories are members of the `commonMain` [AppGraph], and [KeyValueSearchHistoryStore]
 * takes a [com.crispy.tv.platform.KeyValueStore] rather than a `Context` — so the wiring is
 * portable. What keeps it out of `commonMain` are the two lines below, neither of which can be
 * written there:
 *
 * - **Not `commonMain`**, because `Dispatchers.IO` is `internal` on Kotlin/Native and
 *   `Locale.getDefault()` has no common spelling at all. The class takes both as required
 *   no-default slots, and `Dispatchers.Default` would compile everywhere while silently putting
 *   a blocking HTTP call on a CPU-sized pool. Writing the right values here once is the reason
 *   the slots are required rather than defaulted.
 * - **Not `androidMain` any more**, because `jvmMain` is compiled by *both* JVM targets. The two
 *   `dependsOn` edges in `build.gradle.kts` are what make "pinned only by a JVM API" a *place*
 *   instead of a reason to be written twice.
 *
 * The history store is built here rather than in the viewmodel because the viewmodel must not
 * name a platform: the store takes a [com.crispy.tv.platform.KeyValueStore], and the platform
 * hands one out by name through the graph's `services.keyValueStores`. The name
 * `"search_preferences"` is a composition decision, and it is the one thing that stays here.
 */
internal fun buildSearchViewModel(graph: AppGraph): SearchViewModel =
    SearchViewModel(
        searchRepository = graph.backendSearchRepository(),
        aiSearchRepository = graph.aiSearchRepository(),
        searchHistoryStore =
            KeyValueSearchHistoryStore(
                graph.services.keyValueStores.store("search_preferences"),
            ),
        languageTagProvider = { Locale.getDefault().toLanguageTag() },
        ioDispatcher = Dispatchers.IO,
    )