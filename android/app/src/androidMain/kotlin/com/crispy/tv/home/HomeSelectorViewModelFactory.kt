package com.crispy.tv.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.app.appGraph
import com.crispy.tv.backend.BackendServicesProvider
import com.crispy.tv.distribution.AppDistribution
import com.crispy.tv.platform.android.AndroidAppLogger
import com.crispy.tv.playerui.PlayerStreamHandoff
import com.crispy.tv.streams.StreamResolverProvider

/**
 * Builds the [HomeSelectorViewModel], which is the part of it that needs a [Context].
 *
 * The viewmodel itself is in `commonMain` and takes its collaborators as constructor
 * parameters; everything that has to reach the composition root — the backend client,
 * the account client, the stream resolver, the user media repository, the plugin stream
 * loader and the logger — is wired here instead. This is the same split as
 * `SearchViewModelFactory` and `personDetailsViewModelFactory`, and the reason is the
 * same: a `Context` is the one dependency no `commonMain` file can name.
 *
 * `stashHandoff` is `PlayerStreamHandoff::stash` rather than the object itself. Only
 * the producer crosses the source-set boundary here; `consume` is still called directly
 * from `PlayerSessionViewModel`, which is also in `androidMain`.
 */
fun homeSelectorViewModelFactory(context: Context): ViewModelProvider.Factory {
    val appContext = context.applicationContext
    return object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val backendClient = BackendServicesProvider.backendClient(appContext)
            val supabase = SupabaseServicesProvider.accountClient(appContext)
            val streamResolver = StreamResolverProvider.get(appContext)
            return HomeSelectorViewModel(
                streamResolver = streamResolver,
                logger = AndroidAppLogger(appContext),
                getMetadataItemDetail = { token, itemId ->
                    backendClient.getMetadataItemDetail(accessToken = token, itemId = itemId)
                },
                sessionTokenProvider = { supabase.ensureValidSession()?.accessToken },
                pluginStreamLoader = AppDistribution.current.pluginStreamLoader(appContext),
                userMediaRepository = appContext.appGraph().userMediaRepository,
                stashHandoff = { stream, lookupId -> PlayerStreamHandoff.stash(stream, lookupId) },
            ) as T
        }
    }
}
