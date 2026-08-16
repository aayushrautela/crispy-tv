package com.crispy.tv.app

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import com.crispy.tv.BuildConfig
import com.crispy.tv.CrispyApplication
import com.crispy.tv.PlaybackDependencies
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.ai.AiInsightsRepository
import com.crispy.tv.backend.BackendContextResolverProvider
import com.crispy.tv.backend.BackendServicesProvider
import com.crispy.tv.data.repository.DefaultCatalogRepository
import com.crispy.tv.data.repository.DefaultSessionRepository
import com.crispy.tv.data.repository.DefaultUserMediaRepository
import com.crispy.tv.details.DetailsUseCases
import com.crispy.tv.details.DetailsViewModel
import com.crispy.tv.details.RuntimeDetailsEntry
import com.crispy.tv.domain.repository.CatalogRepository
import com.crispy.tv.domain.repository.SessionRepository
import com.crispy.tv.domain.repository.UserMediaRepository
import com.crispy.tv.network.AppHttp
import com.crispy.tv.streams.StreamResolverProvider

class AppGraph(
    context: Context,
) {
    private val appContext = context.applicationContext

    val sessionRepository: SessionRepository by lazy {
        DefaultSessionRepository(SupabaseServicesProvider.accountClient(appContext))
    }

    val catalogRepository: CatalogRepository by lazy {
        DefaultCatalogRepository(BackendServicesProvider.backendClient(appContext))
    }

    val userMediaRepository: UserMediaRepository by lazy {
        DefaultUserMediaRepository(PlaybackDependencies.watchHistoryServiceFactory(appContext))
    }

    private val httpClient by lazy {
        AppHttp.client(appContext)
    }

    private val aiInsightsRepository by lazy {
        AiInsightsRepository.create(appContext, httpClient)
    }

    private val streamResolver by lazy {
        StreamResolverProvider.get(appContext)
    }

    private val detailsUseCases: DetailsUseCases by lazy {
        DetailsUseCases(
            sessionRepository = sessionRepository,
            catalogRepository = catalogRepository,
            userMediaRepository = userMediaRepository,
            crispyBackendClient = BackendServicesProvider.backendClient(appContext),
            aiRepository = aiInsightsRepository,
            streamResolver = streamResolver,
            backendContextResolver = BackendContextResolverProvider.get(appContext),
        )
    }

    fun detailsViewModelFactory(
        itemId: String,
        itemType: String,
        runtimeEntry: RuntimeDetailsEntry? = null,
    ): ViewModelProvider.Factory {
        return DetailsViewModel.factory(
            itemId = itemId,
            itemType = itemType,
            runtimeEntry = runtimeEntry,
            detailsUseCases = detailsUseCases,
        )
    }

    internal fun detailsUseCases(): DetailsUseCases = detailsUseCases
}

fun Context.appGraph(): AppGraph {
    return (applicationContext as CrispyApplication).appGraph
}
