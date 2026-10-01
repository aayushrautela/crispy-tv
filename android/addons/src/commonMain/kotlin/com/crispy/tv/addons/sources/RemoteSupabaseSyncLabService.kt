package com.crispy.tv.addons.sources

import com.crispy.tv.accounts.AccountApi
import com.crispy.tv.player.SupabaseSyncAuthState
import com.crispy.tv.player.SupabaseSyncLabResult
import com.crispy.tv.player.SupabaseSyncLabService
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The account half of the Sync Lab surface: every request answers with a
 * status message saying the operation is disabled, and only [authState] does
 * real work.
 *
 * It is `commonMain`, and the module's own KDoc table used to list it under
 * `Context` and `org.json`. **Neither was ever true**: the file named no
 * `org.json` type at all, and the `Context` was a constructor parameter the
 * class did not read -- the compiler had said so for as long as it had been
 * there, in a `@Suppress("UNUSED_PARAMETER")` on the class itself. The pin
 * was the parameter list, and a parameter nobody reads is not a dependency.
 * Deleting it is what freed the file; [ioDispatcher] replaced the other
 * pin, `Dispatchers.IO`.
 *
 * Every collaborator was already portable when it moved -- `AccountApi` from
 * `:backend`'s `commonMain` and the three `SupabaseSyncLab*` types from
 * `:player`'s `commonMain`, both already on this module's `commonMain`
 * classpath -- so the two numbers this file's move turned on were measured
 * before it was attempted, not discovered by a compile.
 *
 * ## Why the stubs are here at all
 *
 * The messages are product decisions ("disabled until addon sync moves to
 * backend APIs"), so they are the class's behaviour and they are pinned in
 * `commonTest`. They are also *eight distinct* answers, which is what makes
 * the suite worth having: a copy-paste that made `signOut` answer with
 * `initialize`'s message would compile and ship.
 *
 * ## The one member that does not switch dispatchers
 *
 * [syncNow] returns without `withContext` while every other member enters one.
 * That asymmetry is in the original and is left exactly as it is: a stub that
 * does no work has nothing to move off the caller's thread, and every other
 * member was wrapped when the members were real. The suite pins both halves,
 * because a port that "tidied" this would be a behaviour change.
 *
 * @param ioDispatcher where the stubs run. There is **no default**, and the
 *   default in a defaulted-parameter version of this class would be
 *   `Dispatchers.Default`, which compiles on every target and silently puts
 *   blocking work on a CPU-sized pool. The caller passes `Dispatchers.IO`.
 */
class RemoteSupabaseSyncLabService(
    private val supabase: AccountApi,
    private val ioDispatcher: CoroutineDispatcher,
) : SupabaseSyncLabService {
    override suspend fun initialize(): SupabaseSyncLabResult = withContext(ioDispatcher) {
        result("Sync Lab is temporarily disabled until addon sync moves to backend APIs.")
    }

    override suspend fun signUpWithEmail(email: String, password: String): SupabaseSyncLabResult = withContext(ioDispatcher) {
        result("Sync Lab sign-up is disabled until addon sync moves to backend APIs.")
    }

    override suspend fun signInWithEmail(email: String, password: String): SupabaseSyncLabResult = withContext(ioDispatcher) {
        result("Sync Lab sign-in is disabled until addon sync moves to backend APIs.")
    }

    override suspend fun signOut(): SupabaseSyncLabResult = withContext(ioDispatcher) {
        result("Sync Lab sign-out is disabled because Sync Lab is temporarily unavailable.")
    }

    override suspend fun pushAllLocalData(): SupabaseSyncLabResult = withContext(ioDispatcher) {
        result("Sync Lab push is disabled until addon sync moves to backend APIs.")
    }

    override suspend fun pullAllToLocal(): SupabaseSyncLabResult = withContext(ioDispatcher) {
        result("Sync Lab pull is disabled until addon sync moves to backend APIs.")
    }

    /** The one member that does not enter [ioDispatcher] -- see the class KDoc. */
    override suspend fun syncNow(): SupabaseSyncLabResult {
        return result("Sync Lab is disabled until addon sync moves to backend APIs.")
    }

    override suspend fun generateSyncCode(pin: String): SupabaseSyncLabResult = withContext(ioDispatcher) {
        result("Sync codes are not supported.")
    }

    override suspend fun claimSyncCode(code: String, pin: String): SupabaseSyncLabResult = withContext(ioDispatcher) {
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