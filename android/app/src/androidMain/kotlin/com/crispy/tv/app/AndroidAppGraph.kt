package com.crispy.tv.app

import android.content.Context
import androidx.lifecycle.ViewModelProvider
import com.crispy.tv.details.DetailsUseCases
import com.crispy.tv.details.RuntimeDetailsEntry
import com.crispy.tv.optimistic.FileBackedPendingMutationStore
import com.crispy.tv.optimistic.UserMediaMutationExecutor
import com.crispy.tv.optimistic.UserMutationOutbox
import com.crispy.tv.services.AppServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import okio.FileSystem
import okio.Path.Companion.toPath

/**
 * What [AppGraph] still cannot build, because what is left needs a `Context` for a reason that
 * has not been discharged.
 *
 * [AppGraph] is in `commonMain` and takes one [AppServices]. This is the other half, and it takes
 * a `Context` for one named reason: **the details view model** reaches `appContext` for
 * `Locale` and `android.text.format.DateFormat`, and `StreamResolverProvider`,
 * `AppDistribution.pluginStreamLoader` and `PlayerStreamHandoff.stash` alongside it. None of those
 * exist off Android yet, so this class is where they wait -- and when the desktop player lands,
 * this type goes away with the last `Context`.
 *
 * ## What used to be here, and why it left
 *
 * There were four members, and three of this file's own KDoc reasons were wrong. Each is recorded
 * here because a corrected premise with two surviving copies is worse than the original.
 *
 * - **watch history** was said to be reached through `PlaybackDependencies.watchHistoryServiceFactory`,
 *   "an overridable `@Volatile` indirection into which `:androidApp`'s `store` and `sideload`
 *   variants install". **No flavour installs into it.** Measured: the only assignments to any
 *   `PlaybackDependencies` member in the tree are `DistributionComponents` -- which writes
 *   `torrentResolverFactory`, from inside `:app` -- and one test that writes the same member. So
 *   the indirection had exactly one answer, and keeping it would have meant the desktop writing
 *   into an `androidMain` global to get one. `AppGraph` now builds it directly; the factory and
 *   its indirection are gone.
 * - **the user-media repository** was said to be pinned because the watch-history service was.
 *   That followed the first bullet, so it went with it: `graph.userMediaRepository`.
 * - **the details use cases** could move as easily -- all six collaborators are [AppGraph] members
 *   or [AppServices] products -- but nothing off Android can *call* them, because the caller is
 *   `detailsViewModelFactory` and that is still the pin. Moving a member no reader can reach is the
 *   same edit in a place that only reads like progress, so it stays until the factory it feeds can
 *   be built.
 * - **the outbox** is the one that is genuinely still here, and its remaining pin is a member, not
 *   an import: `FileSystem.SYSTEM` lives in okio's `systemFileSystemMain` source set, so
 *   `:android:app:compileCommonMainKotlinMetadata` reports `Unresolved reference 'SYSTEM'` for it
 *   and **declaring `libs.okio.core` in `:app`'s `commonMain` does not help** -- the catalogue
 *   note that `coil-core` brings okio to `:app` is true of the *types* (`FileSystem`, `Path`,
 *   `buffer`, `use` all resolve today with no dependency line) and false of that one member.
 *   Everything the outbox needs from `services` is already portable, so this is the last thing
 *   standing between it and [AppGraph].
 */
class AndroidAppGraph(
    /** The portable half, and the half that holds everything this file cannot build. */
    val graph: AppGraph,
    context: Context,
) {
    private val appContext: Context = context.applicationContext

    /**
     * Read back off [graph] rather than built here, because there must be one set per process and
     * the graph is the thing that already has it.
     */
    private val services: AppServices = graph.services

    /**
     * The one member that is still here for a mechanical reason rather than a design one.
     *
     * Everything it needs from [services] is already portable, so the only thing keeping it out of
     * [AppGraph] is [FileSystem]`.`SYSTEM` -- okio declares it in `systemFileSystemMain`, and
     * `:android:app:compileCommonMainKotlinMetadata` reports `Unresolved reference 'SYSTEM'` for it
     * even with `libs.okio.core` declared in `commonMain`. Its own scope is deliberately *not*
     * [AppServices.serviceScope]: `start()` is called from `Application.onCreate`, so the drain has
     * to outlive any one screen, and tying it to a scope this class does not own is the change this
     * is not making.
     */
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
            executor = UserMediaMutationExecutor(graph.userMediaRepository),
            // Its own job, uncancelled, and that is unchanged: `services.serviceScope` is the same
            // answer (`SupervisorJob` on the IO dispatcher), and switching to it would tie the
            // outbox's drain to a scope this graph does not own. `UserMutationOutbox.start()` is
            // called from `Application.onCreate`, so the scope has to outlive any one screen.
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
            userMediaRepository = graph.userMediaRepository,
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