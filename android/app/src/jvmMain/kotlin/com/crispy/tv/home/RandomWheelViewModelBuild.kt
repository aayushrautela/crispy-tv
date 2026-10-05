package com.crispy.tv.home

import com.crispy.tv.app.AppGraph
import kotlinx.coroutines.Dispatchers

/**
 * The collaborators of [RandomWheelViewModel] — the half of the factory that `jvmMain` shares
 * between the Android and the desktop target, and the only half worth sharing.
 *
 * ## Why this file is `jvmMain` and not `commonMain` or `androidMain`
 *
 * It used to be the whole `androidMain` `randomWheelViewModelFactory`, which took a `Context`
 * whose only use was reaching `appGraph().graph`. The collaborators are already `commonMain` —
 * `homeCatalogService` is a member of the `commonMain` [AppGraph], and `AppLogger` is the
 * interface in `:platform-core` — so the wiring is portable. What keeps it out of `commonMain`
 * is `Dispatchers.IO`, which is why it is `jvmMain` rather than `commonMain`:
 *
 * - **Not `commonMain`**, because `Dispatchers.IO` is `internal` on Kotlin/Native, so a
 *   `commonMain` default would not compile there at all. The class takes the dispatcher as a
 *   required no-default slot, and `Dispatchers.Default` would compile everywhere while silently
 *   putting a blocking add-on fetch on a CPU-sized pool. Writing the right value here once is the
 *   reason the slot is required rather than defaulted.
 * - **Not `androidMain` any more**, because `jvmMain` is compiled by *both* JVM targets. The two
 *   `dependsOn` edges in `build.gradle.kts` are what make "pinned only by a JVM API" a *place*
 *   instead of a reason to be written twice.
 *
 * The service comes off the `commonMain` [AppGraph], which is a single cached instance per process
 * -- `AppGraphCachingTest` asserts exactly that -- so the wheel and the home screen agree about
 * which snapshot they are reading.
 */
internal fun buildRandomWheelViewModel(graph: AppGraph): RandomWheelViewModel =
    RandomWheelViewModel(
        homeCatalogService = graph.homeCatalogService,
        ioDispatcher = Dispatchers.IO,
        logger = graph.services.logger,
    )