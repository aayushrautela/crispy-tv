package com.crispy.tv.accounts

import android.content.Context
import com.crispy.tv.backend.BackendServicesProvider

/**
 * Builds the [loadProfile] slot that the common `ProfileMenuRoute` and
 * `ProfileIconButton` take.
 *
 * It returns a lambda rather than the profile itself so that the three collaborators are
 * built once here and the common code never names any of them — a `Context` cannot appear
 * in a `commonMain` signature, and a `Context` used for a *call* is a capability rather
 * than wiring, which is a function slot and not a factory-shaped constructor argument.
 *
 * Callers must wrap the result in `remember`. The lambda's identity is a `produceState`
 * key, so a fresh lambda on every recomposition would restart the load each time.
 */
fun activeProfileLoader(context: Context): suspend () -> ActiveProfileInfo? {
    val appContext = context.applicationContext
    val supabase = SupabaseServicesProvider.accountClient(appContext)
    val backend = BackendServicesProvider.backendClient(appContext)
    val activeProfileStore = SupabaseServicesProvider.activeProfileStore(appContext)
    return { loadActiveProfile(supabase, backend, activeProfileStore) }
}
