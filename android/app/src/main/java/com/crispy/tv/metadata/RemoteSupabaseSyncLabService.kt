package com.crispy.tv.metadata

import android.content.Context
import com.crispy.tv.accounts.SupabaseAccountClient
import com.crispy.tv.player.SupabaseSyncAuthState
import com.crispy.tv.player.SupabaseSyncLabResult
import com.crispy.tv.player.SupabaseSyncLabService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RemoteSupabaseSyncLabService(
    context: Context,
    private val supabase: SupabaseAccountClient,
    addonManifestUrlsCsv: String,
) : SupabaseSyncLabService {
    private val addonRegistry = MetadataAddonRegistry(context.applicationContext, addonManifestUrlsCsv)

    override suspend fun initialize(): SupabaseSyncLabResult = withContext(Dispatchers.IO) {
        if (!supabase.isConfigured()) {
            return@withContext result("Supabase sync is not configured. Set SUPABASE_URL and SUPABASE_ANON_KEY.")
        }

        val session = supabase.ensureValidSession()
        if (session == null) {
            return@withContext result("Supabase configured. Sign in to enable cloud sync.")
        }

        runCatching {
            pullAllInternal(session.accessToken)
        }.fold(
            onSuccess = { pulled ->
                result(
                    message = pulled.status,
                    pulledAddons = pulled.addons,
                    pulledWatchedItems = pulled.watched,
                )
            },
            onFailure = { error ->
                result("Supabase session restored, but startup pull failed: ${error.message ?: "unknown error"}")
            },
        )
    }

    override suspend fun signUpWithEmail(email: String, password: String): SupabaseSyncLabResult = withContext(Dispatchers.IO) {
        if (!supabase.isConfigured()) {
            return@withContext result("Supabase sync is not configured.")
        }

        val normalizedEmail = email.trim()
        val normalizedPassword = password.trim()
        if (normalizedEmail.isEmpty() || normalizedPassword.isEmpty()) {
            return@withContext result("Email and password are required for sign-up.")
        }

        runCatching {
            val signUp = supabase.signUpWithEmail(normalizedEmail, normalizedPassword)
            signUp.message
        }.fold(
            onSuccess = { message -> result(message) },
            onFailure = { error -> result("Supabase sign-up failed: ${error.message ?: "unknown error"}") },
        )
    }

    override suspend fun signInWithEmail(email: String, password: String): SupabaseSyncLabResult = withContext(Dispatchers.IO) {
        if (!supabase.isConfigured()) {
            return@withContext result("Supabase sync is not configured.")
        }

        val normalizedEmail = email.trim()
        val normalizedPassword = password.trim()
        if (normalizedEmail.isEmpty() || normalizedPassword.isEmpty()) {
            return@withContext result("Email and password are required for sign-in.")
        }

        runCatching {
            supabase.signInWithEmail(normalizedEmail, normalizedPassword)
            "Supabase sign-in successful."
        }.fold(
            onSuccess = { message -> result(message) },
            onFailure = { error -> result("Supabase sign-in failed: ${error.message ?: "unknown error"}") },
        )
    }

    override suspend fun signOut(): SupabaseSyncLabResult = withContext(Dispatchers.IO) {
        runCatching { supabase.signOut() }
        result("Signed out from Supabase sync.")
    }

    override suspend fun pushAllLocalData(): SupabaseSyncLabResult = withContext(Dispatchers.IO) {
        if (!supabase.isConfigured()) {
            return@withContext result("Supabase sync is not configured.")
        }

        val session = supabase.ensureValidSession()
            ?: return@withContext result("Sign in to Supabase before pushing local data.")

        runCatching {
            pushAllInternal(session.accessToken)
        }.fold(
            onSuccess = { pushed ->
                result(
                    message = pushed.status,
                    pushedAddons = pushed.addons,
                    pushedWatchedItems = pushed.watched,
                )
            },
            onFailure = { error ->
                result("Supabase push failed: ${error.message ?: "unknown error"}")
            },
        )
    }

    override suspend fun pullAllToLocal(): SupabaseSyncLabResult = withContext(Dispatchers.IO) {
        if (!supabase.isConfigured()) {
            return@withContext result("Supabase sync is not configured.")
        }

        val session = supabase.ensureValidSession()
            ?: return@withContext result("Sign in to Supabase before pulling cloud data.")

        runCatching {
            pullAllInternal(session.accessToken)
        }.fold(
            onSuccess = { pulled ->
                result(
                    message = pulled.status,
                    pulledAddons = pulled.addons,
                    pulledWatchedItems = pulled.watched,
                )
            },
            onFailure = { error ->
                result("Supabase pull failed: ${error.message ?: "unknown error"}")
            },
        )
    }

    override suspend fun syncNow(): SupabaseSyncLabResult {
        return pullAllToLocal()
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

    private suspend fun pushAllInternal(accessToken: String): SyncCounts {
        val pushedAddons = pushAddons(accessToken)
        val pushedWatched = 0
        return SyncCounts(
            status = "Pushed $pushedAddons addon rows. Watched sync not implemented.",
            addons = pushedAddons,
            watched = pushedWatched,
        )
    }

    private suspend fun pullAllInternal(accessToken: String): SyncCounts {
        val pulledAddons = pullAddons(accessToken)
        val pulledWatched = 0
        return SyncCounts(
            status = "Pulled $pulledAddons addon rows. Watched sync not implemented.",
            addons = pulledAddons,
            watched = pulledWatched,
        )
    }

    private suspend fun pushAddons(accessToken: String): Int {
        val localRows = addonRegistry.exportCloudAddons()
        val addons =
            localRows.map {
                SupabaseAccountClient.HouseholdAddon(
                    url = it.manifestUrl,
                    enabled = true,
                    name = null,
                )
            }
        supabase.replaceHouseholdAddons(accessToken, addons)
        return localRows.size
    }

    private suspend fun pullAddons(accessToken: String): Int {
        val rows =
            supabase.getHouseholdAddons(accessToken)
                .filter { it.enabled }
                .mapIndexed { index, addon ->
                    CloudAddonRow(manifestUrl = addon.url, sortOrder = index)
                }
        addonRegistry.reconcileCloudAddons(rows)
        return rows.size
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

private data class SyncCounts(
    val status: String,
    val addons: Int,
    val watched: Int,
)
