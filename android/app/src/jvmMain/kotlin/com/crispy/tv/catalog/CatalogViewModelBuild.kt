package com.crispy.tv.catalog

import com.crispy.tv.app.AppGraph
import kotlinx.coroutines.Dispatchers

/**
 * The collaborators of [CatalogViewModel] — the half of the factory that `jvmMain` shares
 * between the Android and the desktop target, and the only half worth sharing.
 *
 * ## Why this file is `jvmMain` and not `commonMain` or `androidMain`
 *
 * It used to be `androidMain`, taking a `Context` whose *only* use was reaching
 * `appGraph().graph.homeCatalogService`. That service is a member of the `commonMain`
 * `AppGraph`, so the parameter became `graph: AppGraph` and the `Context` import went with it.
 * What is left is `Dispatchers.IO`, which is why it is `jvmMain` rather than `commonMain`:
 *
 * - **Not `commonMain`**, because `Dispatchers.IO` is `internal` on Kotlin/Native, so a
 *   `commonMain` default would not compile there at all. The class takes the dispatcher as a
 *   required no-default slot, and `Dispatchers.Default` would compile everywhere while
 *   silently putting a blocking add-on fetch on a CPU-sized pool. Writing the right value here
 *   once is the reason the slot is required rather than defaulted.
 * - **Not `androidMain` any more**, because `jvmMain` is compiled by *both* JVM targets.
 *   `applyDefaultHierarchyTemplate` registers this source set in `:app`, but nothing depended
 *   on it until now, so it was a set the build never compiled — a file placed here would have
 *   been silently inert rather than an error. The two `dependsOn` edges in `build.gradle.kts`
 *   are what make "pinned only by a JVM API" a *place* instead of a reason to be written twice.
 *
 * ## Why the `ViewModelProvider.Factory` is not here
 *
 * The factory's class check is the one genuinely platform-shaped detail, and it is documented
 * in `AccountViewModelBuilders.kt`, which is the record this file follows:
 * `create(Class<T>)` exists on Android and its common metadata declares only
 * `create(KClass<T>, extras)`, and `KClass.isAssignableFrom` does not exist in Kotlin 2.4.10's
 * common `KClass`, so a portable factory can only compare for equality. Android's
 * `ViewModelProvider` only ever reaches a factory through the `Class` overload, so Android
 * keeps that spelling in `androidMain` — unchanged, still `isAssignableFrom` — and the desktop
 * consumes this builder through `viewModelFactoryOf`.
 *
 * That split is the point of the exercise: what gets written once is the wiring, which is the
 * part that is both portable and expensive to keep correct. What stays twice is a five-line
 * shim whose two versions differ in exactly one line.
 */
internal fun buildCatalogViewModel(
    graph: AppGraph,
    section: CatalogSectionRef,
): CatalogViewModel =
    CatalogViewModel(
        homeCatalogService = graph.homeCatalogService,
        section = section,
        ioDispatcher = Dispatchers.IO,
    )
