package com.crispy.tv.library

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.crispy.tv.PlaybackDependencies
import com.crispy.tv.app.appGraph
import com.crispy.tv.backend.BackendContextResolverProvider
import com.crispy.tv.backend.BackendServicesProvider
import com.crispy.tv.data.repository.DefaultUserMediaRepository
import com.crispy.tv.network.AppHttp
import com.crispy.tv.optimistic.newUserMutationId
import com.crispy.tv.watchhistory.sync.OkHttpWatchSyncSource
import kotlinx.coroutines.Dispatchers

/**
 * The `libraryViewModelFactory` half of [LibraryViewModel]'s move to `commonMain`.
 *
 * The class used to carry a `Context` and build five collaborators in a companion
 * `factory`; the wiring is unchanged and lives here instead, verbatim. What moved
 * is which decisions stayed behind:
 *
 * - the `okhttp` socket behind [LibraryViewModel]'s `watchSyncFactory` slot, the
 *   same slot [com.crispy.tv.home.HomeViewModel] takes;
 * - the platform clock, as `clock`;
 * - the mutation-id generator, as `newMutationId` -- `java.util.UUID` has no
 *   Kotlin/Native equivalent, which is the only reason it is a slot.
 *
 * The locale-aware month name is *not* built here. It is a rendering decision, so
 * it belongs with the other two renderers in
 * [com.crispy.tv.details.localeDateFormatters] and reaches the screen as the
 * `monthName` argument of `historyItems` -- the screen names the decision, this
 * file only names the socket.
 */
fun libraryViewModelFactory(context: Context): ViewModelProvider.Factory {
    val appContext = context.applicationContext
    val backendClient = BackendServicesProvider.backendClient(appContext)
    return object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(LibraryViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return LibraryViewModel(
                    backend = backendClient,
                    backendContextResolver = BackendContextResolverProvider.get(appContext),
                    userMediaRepository =
                        DefaultUserMediaRepository(
                            PlaybackDependencies.watchHistoryServiceFactory(appContext),
                        ),
                    outbox = appContext.appGraph().userMutationOutbox,
                    libraryCache = LibraryDiskCacheStore(appContext),
                    watchSyncFactory = { accessToken, profileId, onEffect ->
                        OkHttpWatchSyncSource(
                            httpClient = AppHttp.okHttp(appContext),
                            baseUrl = backendClient.baseUrl,
                            accessToken = accessToken,
                            profileId = profileId,
                            onEffect = onEffect,
                        )
                    },
                    clock = { System.currentTimeMillis() },
                    newMutationId = { newUserMutationId() },
                    ioDispatcher = Dispatchers.IO,
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}

/**
 * The device's UTC offset in milliseconds, read fresh on every call.
 *
 * `java.time`'s `ZoneId.systemDefault()` was called once per history entry and once
 * per watchlist entry, and this is a function rather than a value for exactly that
 * reason: an offset remembered at composition time is silently wrong for the hours
 * either side of a daylight-saving change, and a screen that groups rows by month
 * is exactly where being an hour out shows.
 *
 * It lives here rather than in the shared formatter bundle on purpose. A formatter
 * that applied its own zone would shift the epoch value a second time -- the
 * timestamps are already instants, and the caller chose the zone deliberately.
 */
fun deviceUtcOffsetMillis(): Long =
    java.time.ZoneId.systemDefault()
        .rules
        .getOffset(java.time.Instant.now())
        .totalSeconds * 1_000L
