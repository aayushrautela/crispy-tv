package com.crispy.rewrite.player

data class SupabaseSyncAuthState(
    val configured: Boolean = false,
    val authenticated: Boolean = false,
    val anonymous: Boolean = false,
    val userId: String? = null,
    val email: String? = null
)

data class SupabaseSyncLabResult(
    val statusMessage: String,
    val authState: SupabaseSyncAuthState = SupabaseSyncAuthState(),
    val syncCode: String? = null,
    val pushedAddons: Int = 0,
    val pushedWatchedItems: Int = 0,
    val pulledAddons: Int = 0,
    val pulledWatchedItems: Int = 0
)

interface SupabaseSyncLabService {
    suspend fun initialize(): SupabaseSyncLabResult

    suspend fun signUpWithEmail(email: String, password: String): SupabaseSyncLabResult

    suspend fun signInWithEmail(email: String, password: String): SupabaseSyncLabResult

    suspend fun signOut(): SupabaseSyncLabResult

    suspend fun pushAllLocalData(): SupabaseSyncLabResult

    suspend fun pullAllToLocal(): SupabaseSyncLabResult

    suspend fun syncNow(): SupabaseSyncLabResult

    suspend fun generateSyncCode(pin: String): SupabaseSyncLabResult

    suspend fun claimSyncCode(code: String, pin: String): SupabaseSyncLabResult

    fun authState(): SupabaseSyncAuthState
}

object DefaultSupabaseSyncLabService : SupabaseSyncLabService {
    private fun unavailableResult(): SupabaseSyncLabResult {
        return SupabaseSyncLabResult(
            statusMessage = "Supabase sync service unavailable.",
            authState = SupabaseSyncAuthState()
        )
    }

    override suspend fun initialize(): SupabaseSyncLabResult = unavailableResult()

    override suspend fun signUpWithEmail(email: String, password: String): SupabaseSyncLabResult = unavailableResult()

    override suspend fun signInWithEmail(email: String, password: String): SupabaseSyncLabResult = unavailableResult()

    override suspend fun signOut(): SupabaseSyncLabResult = unavailableResult()

    override suspend fun pushAllLocalData(): SupabaseSyncLabResult = unavailableResult()

    override suspend fun pullAllToLocal(): SupabaseSyncLabResult = unavailableResult()

    override suspend fun syncNow(): SupabaseSyncLabResult = unavailableResult()

    override suspend fun generateSyncCode(pin: String): SupabaseSyncLabResult = unavailableResult()

    override suspend fun claimSyncCode(code: String, pin: String): SupabaseSyncLabResult = unavailableResult()

    override fun authState(): SupabaseSyncAuthState = SupabaseSyncAuthState()
}
