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
import com.crispy.tv.streams.AddonStreamsService

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

    private val addonStreamsService by lazy {
        AddonStreamsService(
            context = appContext,
            addonManifestUrlsCsv = BuildConfig.METADATA_ADDON_URLS,
            httpClient = httpClient,
        )
    }

    private val detailsUseCases: DetailsUseCases by lazy {
        DetailsUseCases(
            sessionRepository = sessionRepository,
            catalogRepository = catalogRepository,
            userMediaRepository = userMediaRepository,
            aiRepository = aiInsightsRepository,
            addonStreamsService = addonStreamsService,
            backendContextResolver = BackendContextResolverProvider.get(appContext),
        )
    }

    fun detailsViewModelFactory(
        mediaKey: String,
        mediaType: String,
        runtimeEntry: RuntimeDetailsEntry? = null,
    ): ViewModelProvider.Factory {
        return DetailsViewModel.factory(
            mediaKey = mediaKey,
            mediaType = mediaType,
            runtimeEntry = runtimeEntry,
            detailsUseCases = detailsUseCases,
        )
    }
}

fun Context.appGraph(): AppGraph {
    return (applicationContext as CrispyApplication).appGraph
}
