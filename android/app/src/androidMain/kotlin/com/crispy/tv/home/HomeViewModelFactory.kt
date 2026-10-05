package com.crispy.tv.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.crispy.tv.app.appGraph
import com.crispy.tv.network.AppHttp
import com.crispy.tv.platform.android.AndroidAppLogger
import com.crispy.tv.platform.android.AndroidTimeSource
import com.crispy.tv.watchhistory.sync.OkHttpWatchSyncSource
import kotlinx.coroutines.Dispatchers

/**
 * The `androidMain` construction site for [HomeViewModel]. It holds every line
 * the port moved out of the class: the OkHttp client, the player seam and the
 * wall clock. The viewmodel itself never names any of them.
 *
 * **The backend client, the context resolver, the home catalog service and the watch-history
 * service are no longer reached through a `Context`** — the first three are members of the
 * `commonMain` graph and the fourth is one of them now. What is left of that list is
 * [AppHttp.okHttp], a raw OkHttp client rather than the graph's `CrispyHttpClient`, and it is
 * here because `OkHttpWatchSyncSource` needs the socket itself.
 */
fun homeViewModelFactory(context: Context): ViewModelProvider.Factory {
    val appContext = context.applicationContext
    return object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
                val graph = appContext.appGraph().graph
                val watchHistoryService = graph.watchHistoryService
                val suppressionStore = continueWatchingSuppressionStore(appContext)
                val backendClient = graph.backendClient
                val backendResolver = graph.backendContextResolver
                val logger = AndroidAppLogger(appContext)
                @Suppress("UNCHECKED_CAST")
                return HomeViewModel(
                    refreshCoordinator =
                        HomeRefreshCoordinator(
                            homeCatalogService = graph.homeCatalogService,
                            homeWatchActivityService = HomeWatchActivityService(),
                            watchHistoryService = watchHistoryService,
                            calendarService =
                                CalendarService(
                                    backendClient = backendClient,
                                    backendContextResolver = backendResolver,
                                    logger = logger,
                                ),
                            upNextService =
                                UpNextService(
                                    backendClient = backendClient,
                                    backendContextResolver = backendResolver,
                                    timeSource = AndroidTimeSource(),
                                    logger = logger,
                                ),
                            suppressionStore = suppressionStore,
                            timeSource = AndroidTimeSource(),
                        ),
                    watchHistoryService = watchHistoryService,
                    suppressionStore = suppressionStore,
                    backendResolver = backendResolver,
                    watchSyncFactory = { accessToken, profileId, onEffect ->
                        OkHttpWatchSyncSource(
                            httpClient = AppHttp.okHttp(appContext),
                            baseUrl = backendClient.baseUrl,
                            accessToken = accessToken,
                            profileId = profileId,
                            onEffect = onEffect,
                        )
                    },
                    timeSource = AndroidTimeSource(),
                    logger = logger,
                    ioDispatcher = Dispatchers.IO,
                ) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
        }
    }
}
