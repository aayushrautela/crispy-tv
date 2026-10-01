package com.crispy.tv.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.crispy.tv.PlaybackDependencies
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.backend.BackendContextResolverProvider
import com.crispy.tv.backend.BackendServicesProvider
import com.crispy.tv.network.AppHttp
import com.crispy.tv.platform.android.AndroidAppLogger
import com.crispy.tv.platform.android.AndroidTimeSource
import com.crispy.tv.watchhistory.sync.OkHttpWatchSyncSource
import kotlinx.coroutines.Dispatchers

/**
 * The `androidMain` construction site for [HomeViewModel]. It holds every line
 * the port moved out of the class: the `Context`, the service providers, the
 * OkHttp client and the wall clock. The viewmodel itself never names any of
 * them.
 */
fun homeViewModelFactory(context: Context): ViewModelProvider.Factory {
    val appContext = context.applicationContext
    return object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(HomeViewModel::class.java)) {
                val watchHistoryService = PlaybackDependencies.watchHistoryServiceFactory(appContext)
                val suppressionStore = continueWatchingSuppressionStore(appContext)
                val backendClient = BackendServicesProvider.backendClient(appContext)
                val backendResolver = BackendContextResolverProvider.get(appContext)
                val logger = AndroidAppLogger(appContext)
                @Suppress("UNCHECKED_CAST")
                return HomeViewModel(
                    refreshCoordinator =
                        HomeRefreshCoordinator(
                            homeCatalogService = SupabaseServicesProvider.homeCatalogService(appContext),
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
