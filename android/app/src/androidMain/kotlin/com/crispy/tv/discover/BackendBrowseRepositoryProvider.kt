package com.crispy.tv.discover

import android.content.Context
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.backend.BackendServicesProvider

/**
 * Builds a [BackendBrowseRepository] from the composition root.
 *
 * This is a function rather than a cached `object` on purpose: the factory it
 * replaces built a fresh instance on every call, and turning it into a
 * process-wide singleton would have been a behaviour change disguised as a
 * refactor. It stays androidMain because both providers it reads reach a
 * `Context`, and the repository it returns does not.
 */
fun backendBrowseRepository(context: Context): BackendBrowseRepository {
    val appContext = context.applicationContext
    return BackendBrowseRepository(
        supabase = SupabaseServicesProvider.accountClient(appContext),
        backend = BackendServicesProvider.backendClient(appContext),
    )
}
