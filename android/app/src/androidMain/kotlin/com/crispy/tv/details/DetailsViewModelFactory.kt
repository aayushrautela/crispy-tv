package com.crispy.tv.details

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.crispy.tv.app.appGraph
import com.crispy.tv.distribution.AppDistribution
import com.crispy.tv.optimistic.UserMutationOutbox
import com.crispy.tv.optimistic.newUserMutationId
import com.crispy.tv.platform.android.AndroidAppLogger
import com.crispy.tv.playerui.PlayerStreamHandoff
import com.crispy.tv.streams.StreamResolverProvider
import java.util.Locale
import kotlinx.coroutines.Dispatchers

/**
 * Composition root for [DetailsViewModel].
 *
 * The class lives in `commonMain` and takes only ports and function slots; every
 * Android type and every service lookup stays here. `Locale` is converted to a
 * BCP-47 tag at this boundary (third instance of the slot rule), the clock is
 * the platform's answer rather than a default, and `PlayerStreamHandoff.stash`
 * crosses as a lambda because only its producer side ever leaves `androidMain`.
 */
internal fun detailsViewModelFactory(
    itemId: String,
    itemType: String,
    runtimeEntry: RuntimeDetailsEntry?,
    detailsUseCases: DetailsUseCases,
    outbox: UserMutationOutbox,
    appContext: Context,
): ViewModelProvider.Factory {
    val app = appContext.applicationContext
    return object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return DetailsViewModel(
                itemId = itemId,
                itemType = itemType,
                runtimeEntry = runtimeEntry,
                detailsUseCases = detailsUseCases,
                outbox = outbox,
                streamResolver = StreamResolverProvider.get(app),
                logger = AndroidAppLogger(app),
                getMetadataItemDetail = { token, metadataItemId ->
                    app.appGraph().graph.backendClient
                        .getMetadataItemDetail(accessToken = token, itemId = metadataItemId)
                },
                sessionTokenProvider = {
                    app.appGraph().graph.accountClient.ensureValidSession()?.accessToken
                },
                pluginStreamLoader = AppDistribution.current.pluginStreamLoader(app),
                stashHandoff = { stream, lookupId -> PlayerStreamHandoff.stash(stream, lookupId) },
                newMutationId = { newUserMutationId() },
                languageTagProvider = { Locale.getDefault().toLanguageTag() },
                clock = { System.currentTimeMillis() },
                ioDispatcher = Dispatchers.IO,
            ) as T
        }
    }
}
