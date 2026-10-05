package com.crispy.tv.discover

import com.crispy.tv.app.AppGraph

/**
 * The collaborators of [DiscoverViewModel] — the half of the factory that `jvmMain` shares
 * between the Android and the desktop target, and the only half worth sharing.
 *
 * ## Why this file is `jvmMain` and not `commonMain` or `androidMain`
 *
 * It used to be the whole `androidMain` `discoverViewModelFactory`, taking a
 * `graph: AppGraph` whose *only* use was reaching `graph.backendBrowseRepository()`.
 * The collaborators are already `commonMain`, and so is [AppGraph], so the body of the
 * wiring is portable. What keeps it out of `commonMain` is `discoverViewModelFactory`'s own
 * `Class<T>` check: `KClass.isAssignableFrom` does not exist in Kotlin 2.4.10's common `KClass`,
 * so a portable factory can only compare for equality. That is the reason the *factory* stays
 * split — a `Class`-based `androidMain` shim and a `viewModelFactoryOf`-based `desktopMain`
 * twin — and this file carries the one shared copy of the wiring, which is the part that is both
 * portable and expensive to keep correct.
 *
 * ## What `buildDiscoverViewModel` answers, and what it deliberately does not
 *
 * It answers which repository the viewmodel reads from, and nothing more. The repository comes off
 * the `commonMain` [AppGraph], a single cached instance per process (`AppGraphCachingTest` asserts
 * exactly that), so the discover screen and the home screen agree about which snapshot they are
 * reading. There is no platform capability in the body: no `Context`, no dispatcher, no logger.
 */
internal fun buildDiscoverViewModel(graph: AppGraph): DiscoverViewModel =
    DiscoverViewModel(
        repository = graph.backendBrowseRepository(),
    )
