package com.crispy.tv.app

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import com.crispy.tv.PlaybackDependencies
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.ai.aiInsightsRepository
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
import com.crispy.tv.optimistic.FileBackedPendingMutationStore
import com.crispy.tv.optimistic.UserMediaMutationExecutor
import com.crispy.tv.optimistic.UserMutationOutbox
import com.crispy.tv.platform.android.AndroidAppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

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

    private val aiInsightsRepository by lazy {
        aiInsightsRepository(appContext)
    }

    private val detailsUseCases: DetailsUseCases by lazy {
        DetailsUseCases(
            sessionRepository = sessionRepository,
            catalogRepository = catalogRepository,
            userMediaRepository = userMediaRepository,
            backendContextResolver = BackendContextResolverProvider.get(appContext),
            backendApi = BackendServicesProvider.backendClient(appContext),
            logger = AndroidAppLogger(appContext),
            cachedInsights = { itemId, languageTag ->
                aiInsightsRepository.loadCached(itemId, languageTag)
            },
            generateInsights = { itemId, languageTag ->
                aiInsightsRepository.generate(itemId, languageTag)
            },
        )
    }

    fun detailsViewModelFactory(
        itemId: String,
        itemType: String,
        runtimeEntry: RuntimeDetailsEntry? = null,
    ): ViewModelProvider.Factory {
        // The member name shadows the top-level factory, so this is qualified:
        // AppGraph only forwards its own wiring, it makes no decisions here.
        return com.crispy.tv.details.detailsViewModelFactory(
            itemId = itemId,
            itemType = itemType,
            runtimeEntry = runtimeEntry,
            detailsUseCases = detailsUseCases,
            outbox = userMutationOutbox,
            appContext = appContext,
        )
    }

    val userMutationOutbox: UserMutationOutbox by lazy {
        val store = FileBackedPendingMutationStore(
            File(appContext.filesDir, "pending_mutations.json"),
        )
        val executor = UserMediaMutationExecutor(userMediaRepository)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        UserMutationOutbox(
            store = store,
            executor = executor,
            scope = scope,
            // The clock was a defaulted lambda whose body was a JVM call; a default that
            // cannot compile in commonMain is not a default, it is a decision the
            // composition root has to make, so it makes it here.
            clock = { System.currentTimeMillis() },
        )
    }

    internal fun detailsUseCases(): DetailsUseCases = detailsUseCases
}

/**
 * Implemented by the `Application` subclass so [appGraph] can reach the graph.
 *
 * `:androidApp` owns `CrispyApplication` and `:app` owns [AppGraph], and
 * `:androidApp` depends on `:app` -- so `:app` cannot name the Application
 * class and a cast to it is not expressible across the module boundary. This
 * interface is that boundary: one side declares it, the other implements it.
 *
 * The alternative -- leaving `CrispyApplication` in `:app` so the cast keeps
 * working -- is rejected. Installing the build's components would then have to
 * happen in `MainActivity.onCreate` rather than `Application.onCreate`, and
 * playback can be resumed from a media notification with no Activity running.
 * A torrent resolver installed one step too late is the bug that `911f8d75`
 * fixed; do not trade it back for one fewer interface.
 */
interface AppGraphHost {
    val appGraph: AppGraph
}

fun Context.appGraph(): AppGraph {
    return (applicationContext as AppGraphHost).appGraph
}
