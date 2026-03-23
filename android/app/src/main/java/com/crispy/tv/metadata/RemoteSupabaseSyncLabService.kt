package com.crispy.tv.metadata

import android.content.Context
import com.crispy.tv.accounts.SupabaseAccountClient
import com.crispy.tv.player.SupabaseSyncAuthState
import com.crispy.tv.player.SupabaseSyncLabResult
import com.crispy.tv.player.SupabaseSyncLabService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Suppress("UNUSED_PARAMETER")
class RemoteSupabaseSyncLabService(
    context: Context,
    private val supabase: SupabaseAccountClient,
    addonManifestUrlsCsv: String,
) : SupabaseSyncLabService {
    override suspend fun initialize(): SupabaseSyncLabResult = withContext(Dispatchers.IO) {
        result("Sync Lab is temporarily disabled until addon sync moves to backend APIs.")
    }

    override suspend fun signUpWithEmail(email: String, password: String): SupabaseSyncLabResult = withContext(Dispatchers.IO) {
        result("Sync Lab sign-up is disabled until addon sync moves to backend APIs.")
    }

    override suspend fun signInWithEmail(email: String, password: String): SupabaseSyncLabResult = withContext(Dispatchers.IO) {
        result("Sync Lab sign-in is disabled until addon sync moves to backend APIs.")
    }

    override suspend fun signOut(): SupabaseSyncLabResult = withContext(Dispatchers.IO) {
        result("Sync Lab sign-out is disabled because Sync Lab is temporarily unavailable.")
    }

    override suspend fun pushAllLocalData(): SupabaseSyncLabResult = withContext(Dispatchers.IO) {
        result("Sync Lab push is disabled until addon sync moves to backend APIs.")
    }

    override suspend fun pullAllToLocal(): SupabaseSyncLabResult = withContext(Dispatchers.IO) {
        result("Sync Lab pull is disabled until addon sync moves to backend APIs.")
    }

    override suspend fun syncNow(): SupabaseSyncLabResult {
        return result("Sync Lab is disabled until addon sync moves to backend APIs.")
    }

    override suspend fun generateSyncCode(pin: String): SupabaseSyncLabResult = withContext(Dispatchers.IO) {
        result("Sync codes are not supported.")
    }

    override suspend fun claimSyncCode(code: String, pin: String): SupabaseSyncLabResult = withContext(Dispatchers.IO) {
        result("Sync codes are not supported.")
    }

    override fun authState(): SupabaseSyncAuthState {
        val session = supabase.currentSession()
        return SupabaseSyncAuthState(
            configured = supabase.isConfigured(),
            authenticated = supabase.isConfigured() && session?.accessToken?.isNotBlank() == true,
            anonymous = session?.anonymous == true,
            userId = session?.userId,
            email = session?.email,
        )
    }

    private fun result(
        message: String,
        syncCode: String? = null,
        pushedAddons: Int = 0,
        pushedWatchedItems: Int = 0,
        pulledAddons: Int = 0,
        pulledWatchedItems: Int = 0,
    ): SupabaseSyncLabResult {
        return SupabaseSyncLabResult(
            statusMessage = message,
            authState = authState(),
            syncCode = syncCode,
            pushedAddons = pushedAddons,
            pushedWatchedItems = pushedWatchedItems,
            pulledAddons = pulledAddons,
            pulledWatchedItems = pulledWatchedItems,
        )
    }
}
