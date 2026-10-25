package com.crispy.tv.person

import com.crispy.tv.app.AppGraph
import java.util.Locale
import kotlinx.coroutines.Dispatchers

/**
 * The collaborators of [PersonDetailsViewModel] — the half of the factory that `jvmMain` shares
 * between the Android and the desktop target, and the only half worth sharing.
 *
 * ## Why this file is `jvmMain` and not `commonMain` or `androidMain`
 *
 * It used to be the whole `androidMain` `personDetailsViewModelFactory`, which took a `Context`
 * whose only use was reaching `appGraph().graph`. The collaborators are already `commonMain` —
 * the two clients come off the `commonMain` [AppGraph], and the viewmodel's `personLoader` is a
 * `suspend (String, String) -> PersonDetails?` over the backend's own types — so the wiring is
 * portable. What keeps it out of `commonMain` is `Dispatchers.IO`, which is why it is `jvmMain`
 * rather than `commonMain`:
 *
 * - **Not `commonMain`**, because `Dispatchers.IO` is `internal` on Kotlin/Native, so a
 *   `commonMain` default would not compile there at all. The class takes the dispatcher as a
 *   required no-default slot, and `Dispatchers.Default` would compile everywhere while silently
 *   putting a blocking metadata fetch on a CPU-sized pool. Writing the right value here once is
 *   the reason the slot is required rather than defaulted.
 * - **Not `androidMain` any more**, because `jvmMain` is compiled by *both* JVM targets. The two
 *   `dependsOn` edges in `build.gradle.kts` are what make "pinned only by a JVM API" a *place*
 *   instead of a reason to be written twice.
 *
 * ## What `buildPersonDetailsViewModel` answers, and what it deliberately does not
 *
 * It answers which person to load and which language tag to ask for, and nothing more. The
 * loader is built here rather than in the viewmodel because the viewmodel must not name a
 * `java.util.Locale`: the backend has always wanted a BCP-47 tag, so converting at the platform
 * edge — here, in `languageTagProvider` — is what lets the class stay in `commonMain`. The
 * conversion is a platform fact, not a shared one, and it is the one line that keeps this file
 * out of `commonMain` alongside `Dispatchers.IO`.
 */
internal fun buildPersonDetailsViewModel(
    graph: AppGraph,
    personId: String,
): PersonDetailsViewModel {
    val supabase = graph.accountClient
    val backend = graph.backendClient

    val personLoader: suspend (String, String) -> PersonDetails? = { requestedPersonId, languageTag ->
        val session = supabase.ensureValidSession()
        if (session == null) {
            null
        } else {
            runCatching {
                backend.getMetadataPersonDetail(
                    accessToken = session.accessToken,
                    personId = requestedPersonId,
                    language = languageTag,
                ).toUiModel()
            }.getOrNull()
        }
    }

    return PersonDetailsViewModel(
        personId = personId,
        personLoader = personLoader,
        languageTagProvider = { Locale.getDefault().toLanguageTag() },
        ioDispatcher = Dispatchers.IO,
    )
}