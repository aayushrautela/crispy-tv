package com.crispy.tv.library

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.crispy.tv.app.appGraph
import com.crispy.tv.network.AppHttp
import com.crispy.tv.optimistic.newUserMutationId
import com.crispy.tv.watchhistory.sync.OkHttpWatchSyncSource
import kotlinx.coroutines.Dispatchers
import okio.FileSystem
import okio.Path.Companion.toPath

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
 * - the mutation-id generator, as `newMutationId`. This note used to say the slot was
 *   there because `java.util.UUID` has no Kotlin/Native equivalent, "which is the only
 *   reason it is a slot" -- and that was false, measured against the resolved stdlib:
 *   `kotlin.uuid.Uuid.random()` is stable in Kotlin 2.4.10, so the mint now lives in
 *   `commonMain` in `newUserMutationId`. The slot's actual reason is determinism: a
 *   viewmodel that mints its own ids cannot be asserted on, and that has nothing to do
 *   with where the generator is written.
 *
 * The locale-aware month name is *not* built here. It is a rendering decision, so
 * it belongs with the other two renderers in
 * [com.crispy.tv.details.localeDateFormatters] and reaches the screen as the
 * `monthName` argument of `HistorySectionContent` -- the screen names the decision, this
 * file only names the socket.
 */
fun libraryViewModelFactory(context: Context): ViewModelProvider.Factory {
    val appContext = context.applicationContext
    val graph = appContext.appGraph().graph
    val backendClient = graph.backendClient
    return object : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(LibraryViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return LibraryViewModel(
                    backend = backendClient,
                    backendContextResolver = graph.backendContextResolver,
                    // The graph's own repository, not a second one built here. This factory used
                    // to construct a `DefaultUserMediaRepository` per call over a watch-history
                    // service of its own; `DefaultUserMediaRepository` is a delegating wrapper
                    // with no state, so the two instances were the same object wearing two names.
                    userMediaRepository = graph.userMediaRepository,
                    // **Not `graph`.** The outbox stayed on [AndroidAppGraph] because it needs
                    // `FileSystem.SYSTEM`, which okio declares in `systemFileSystemMain` and not
                    // in `commonMain`, so no `commonMain` file can name it. The two collaborators
                    // this factory takes therefore still come from different halves, and the
                    // graph below is reached for the three that moved.
                    outbox = appContext.appGraph().userMutationOutbox,
                    libraryCache = LibraryDiskCacheStore(
                        fileSystem = FileSystem.SYSTEM,
                        // **`.absolutePath` first, and that is not a style choice.**
                        // `java.io.File.toPath()` and `okio.String.toPath()` both
                        // exist, and a `File` receiver picks the JDK's — which
                        // answers `java.nio.file.Path` and fails the parameter with
                        // `actual type is 'java.nio.file.Path!', but 'okio.Path' was
                        // expected`. **It is the same shadowing as okio's `use`: a
                        // name that exists in two libraries, resolved by whichever
                        // one the receiver's platform type names.** Routing through
                        // the `String` makes the okio overload the only candidate.
                        cacheRoot = appContext.filesDir.absolutePath.toPath(),
                        ioDispatcher = Dispatchers.IO,
                    ),
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

