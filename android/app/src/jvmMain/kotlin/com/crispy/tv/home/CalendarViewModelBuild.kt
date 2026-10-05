package com.crispy.tv.home

import com.crispy.tv.app.AppGraph
import kotlinx.coroutines.Dispatchers

/**
 * The collaborators of [CalendarViewModel] — the half of the factory that `jvmMain` shares
 * between the Android and the desktop target, and the only half worth sharing.
 *
 * ## Why this file is `jvmMain` and not `commonMain` or `androidMain`
 *
 * It used to be the whole `androidMain` `calendarViewModelFactory`, taking a `Context` whose only
 * use was reaching `appGraph().graph`. The collaborators are already `commonMain` —
 * `backendClient` and `backendContextResolver` are members of the `commonMain` [AppGraph], and
 * [CalendarService] lives in `:home`'s `commonMain` — so the wiring is portable. What keeps it out
 * of `commonMain` are the two lines below: `Dispatchers.IO` and `System.currentTimeMillis()`,
 * neither of which can be written in `commonMain`:
 *
 * - **Not `commonMain`**, because `Dispatchers.IO` is `internal` on Kotlin/Native and
 *   `System.currentTimeMillis()` has no common spelling at all. The class takes both as required
 *   no-default slots, and `Dispatchers.Default` would compile everywhere while silently putting a
 *   blocking service call on a CPU-sized pool. Writing the right values here once is the reason
 *   the slots are required rather than defaulted.
 * - **Not `androidMain` any more**, because `jvmMain` is compiled by *both* JVM targets. The two
 *   `dependsOn` edges in `build.gradle.kts` are what make "pinned only by a JVM API" a *place*
 *   instead of a reason to be written twice.
 *
 * `CalendarViewModel` was `private` and the factory its sibling `companion object` member; that
 * split is recorded in `CalendarScreen.kt`'s KDoc, and it is the reason the class is `internal`
 * and the factory is a separate file — the two halves live in different source sets.
 */
internal fun buildCalendarViewModel(graph: AppGraph): CalendarViewModel {
    val calendarService = CalendarService(
        backendClient = graph.backendClient,
        backendContextResolver = graph.backendContextResolver,
        logger = graph.services.logger,
    )
    return CalendarViewModel(
        calendarService = calendarService,
        ioDispatcher = Dispatchers.IO,
        clock = { System.currentTimeMillis() },
    )
}