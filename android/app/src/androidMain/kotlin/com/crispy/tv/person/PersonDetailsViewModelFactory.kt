package com.crispy.tv.person

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.crispy.tv.app.appGraph
import java.util.Locale
import kotlinx.coroutines.Dispatchers

/**
 * Builds the `ViewModelProvider.Factory` for [PersonDetailsViewModel].
 *
 * This is the whole reason the viewmodel's class body is in `commonMain` and this
 * function is not: it is the only place that names a `Context`. **The two clients used to
 * be the reason; they are not any more** — they come from the `commonMain` graph, and what
 * is left here is `Locale.getDefault()` and `Dispatchers.IO`, both of which cannot be
 * written in `commonMain` at all. The viewmodel itself depends on nothing but a `suspend`
 * loader and a language tag, both of which are portable.
 *
 * The loader takes the person id and a **BCP-47 tag string**, not a `Locale`. The
 * backend has always wanted a tag, so converting here -- at the platform edge --
 * rather than inside the viewmodel is what lets the class move. The viewmodel used
 * to hold a `java.util.Locale` and hand it down; see `SearchViewModel` for the same
 * change made for the same reason.
 */
fun personDetailsViewModelFactory(
    appContext: Context,
    personId: String,
): ViewModelProvider.Factory {
    val context = appContext.applicationContext
    val graph = context.appGraph().graph
    val supabase = graph.accountClient
    val backend = graph.backendClient
    return object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (!modelClass.isAssignableFrom(PersonDetailsViewModel::class.java)) {
                throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
            }

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

            @Suppress("UNCHECKED_CAST")
            return PersonDetailsViewModel(
                personId = personId,
                personLoader = personLoader,
                languageTagProvider = { Locale.getDefault().toLanguageTag() },
                ioDispatcher = Dispatchers.IO,
            ) as T
        }
    }
}
