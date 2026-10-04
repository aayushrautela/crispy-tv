package com.crispy.tv.app

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import com.crispy.tv.PlaybackDependencies
import com.crispy.tv.data.repository.DefaultUserMediaRepository
import com.crispy.tv.details.DetailsUseCases
import com.crispy.tv.details.RuntimeDetailsEntry
import com.crispy.tv.domain.repository.UserMediaRepository
import com.crispy.tv.optimistic.FileBackedPendingMutationStore
import com.crispy.tv.optimistic.UserMediaMutationExecutor
import com.crispy.tv.optimistic.UserMutationOutbox
import com.crispy.tv.services.AppServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * The player half of the graph: the three things [AppGraph] cannot build because each one is
 * still an Android seam rather than a `Context`-free construction.
 *
 * [AppGraph] is in `commonMain` and takes one [AppServices]. This is the other half, and it takes
 * a `Context` for three named reasons, each of which is a decision that belongs to the platform:
 *
 * - **watch history** is reached through `PlaybackDependencies.watchHistoryServiceFactory`, an
 *   overridable `@Volatile` indirection into which `:androidApp`'s `store` and `sideload` variants
 *   install. Building `BackendWatchHistoryService` directly would answer a different question
 *   than the one the indirection exists to answer -- "who provides watch history", not "what does
 *   watch history need" -- and it would bypass any install.
 * - **the details view model** needs `StreamResolverProvider`, `AppDistribution.pluginStreamLoader`
 *   and `PlayerStreamHandoff.stash`, none of which exist off Android yet.
 * - **the outbox** needs the watch-history repository above, and nothing else: its file path,
 *   its dispatcher and its clock all come from [AppServices] now, so all that is left here is the
 *   one collaborator that has not moved.
 *
 * So this class is not a second graph and not a delegate: it is the part of one graph that could
 * not be written yet. When the player lands, these three members join [AppGraph] and this type goes
 * away with the last `Context`.
 */
class AndroidAppGraph(
    /** The portable half. Exposed so a caller can reach a service *and* one of these three. */
    val graph: AppGraph,
    context: Context,
) {
    private val appContext: Context = context.applicationContext

    /**
     * Read back off [graph] rather than built here, because there must be one set per process and
     * the graph is the thing that already has it.
     */
    private val services: AppServices = graph.services

    val userMediaRepository: UserMediaRepository by lazy {
        DefaultUserMediaRepository(PlaybackDependencies.watchHistoryServiceFactory(appContext))
    }

    val userMutationOutbox: UserMutationOutbox by lazy {
        // The store itself is `commonMain`; the three things it deliberately does not name are
        // supplied here, at the composition root. `FileSystem.SYSTEM` is okio's real filesystem
        // and okio reaches this module through `coil3`, so there is no dependency line for it in
        // `build.gradle.kts` -- see the KDoc on `FileBackedPendingMutationStore`.
        val store = FileBackedPendingMutationStore(
            fileSystem = FileSystem.SYSTEM,
            // The path is joined by hand because `java.io.File` cannot appear in `commonMain`;
            // `AppServices.dataDirectoryPath` is a `String` for exactly this reason.
            path = "${services.dataDirectoryPath}/pending_mutations.json".toPath(),
            ioDispatcher = services.ioDispatcher,
        )
        UserMutationOutbox(
            store = store,
            executor = UserMediaMutationExecutor(userMediaRepository),
            scope = CoroutineScope(SupervisorJob() + services.ioDispatcher),
            // The clock was a defaulted lambda whose body was a JVM call; a default that cannot
            // compile in `commonMain` is not a default, it is a decision the composition root has
            // to make, and `services.timeSource` is where that decision now lives.
            clock = { services.timeSource.nowMs() },
        )
    }

    private val detailsUseCases: DetailsUseCases by lazy {
        DetailsUseCases(
            sessionRepository = graph.sessionRepository,
            catalogRepository = graph.catalogRepository,
            userMediaRepository = userMediaRepository,
            backendContextResolver = graph.backendContextResolver,
            backendApi = graph.backendClient,
            logger = services.logger,
            cachedInsights = { itemId, languageTag ->
                graph.aiInsightsRepository.loadCached(itemId, languageTag)
            },
            generateInsights = { itemId, languageTag ->
                graph.aiInsightsRepository.generate(itemId, languageTag)
            },
        )
    }

    fun detailsViewModelFactory(
        itemId: String,
        itemType: String,
        runtimeEntry: RuntimeDetailsEntry? = null,
    ): ViewModelProvider.Factory {
        // The member name shadows the top-level factory, so this is qualified:
        // AndroidAppGraph only forwards its own wiring, it makes no decisions here.
        return com.crispy.tv.details.detailsViewModelFactory(
            itemId = itemId,
            itemType = itemType,
            runtimeEntry = runtimeEntry,
            detailsUseCases = detailsUseCases,
            outbox = userMutationOutbox,
            appContext = appContext,
        )
    }

    internal fun detailsUseCases(): DetailsUseCases = detailsUseCases
}

/**
 * Implemented by the `Application` subclass so [appGraph] can reach the graph.
 *
 * `:androidApp` owns `CrispyApplication` and `:app` owns [AndroidAppGraph], and
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
    val appGraph: AndroidAppGraph
}

fun Context.appGraph(): AndroidAppGraph {
    return (applicationContext as AppGraphHost).appGraph
}