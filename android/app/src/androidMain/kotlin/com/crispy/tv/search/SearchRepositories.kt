package com.crispy.tv.search

import android.content.Context
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.backend.BackendServicesProvider

/**
 * Builds the two search repositories from the composition root.
 *
 * Both repository classes used to carry a `companion object { fun create(context) }`,
 * and that factory was the only thing pinning them to `androidMain` — the classes
 * themselves hold nothing but two ports and the mapping rules, so they travel once
 * `CrispyBackendClient` is retyped to `BackendApi` and the factory moves here.
 *
 * The same split `BackendBrowseRepository` got in the browse batch: a function, not
 * a cached object, because the factory it replaces built a fresh instance per call
 * and making these singletons would be a behaviour change dressed as a refactor.
 * It is called from the viewmodel factory, which is itself created once per store.
 */
fun backendSearchRepository(context: Context): BackendSearchRepository {
    val appContext = context.applicationContext
    return BackendSearchRepository(
        supabase = SupabaseServicesProvider.accountClient(appContext),
        backend = BackendServicesProvider.backendClient(appContext),
    )
}

fun aiSearchRepository(context: Context): AiSearchRepository {
    val appContext = context.applicationContext
    return AiSearchRepository(
        supabase = SupabaseServicesProvider.accountClient(appContext),
        activeProfileStore = SupabaseServicesProvider.activeProfileStore(appContext),
        backend = BackendServicesProvider.backendClient(appContext),
    )
}
