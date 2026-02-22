package com.crispy.rewrite.oauth

import android.content.Context
import android.net.Uri
import com.crispy.rewrite.player.WatchProvider
import java.security.MessageDigest

internal data class PendingOAuthRecord(
    val provider: WatchProvider,
    val callbackUri: String,
    val createdAtEpochMs: Long,
)

internal class PendingOAuthStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun savePending(
        provider: WatchProvider,
        callbackUri: String,
        createdAtEpochMs: Long = System.currentTimeMillis(),
    ) {
        prefs.edit()
            .putString(KEY_PENDING_PROVIDER, provider.name.lowercase())
            .putString(KEY_PENDING_CALLBACK_URI, callbackUri)
            .putLong(KEY_PENDING_CREATED_AT_EPOCH_MS, createdAtEpochMs)
            .remove(KEY_PENDING_ERROR_MESSAGE)
            .apply()
    }

    fun loadPending(): PendingOAuthRecord? {
        val provider = parseProvider(prefs.getString(KEY_PENDING_PROVIDER, null)) ?: return null
        val callbackUri = prefs.getString(KEY_PENDING_CALLBACK_URI, null)?.trim().orEmpty()
        if (callbackUri.isBlank()) {
            return null
        }

        val createdAtEpochMs = prefs.getLong(KEY_PENDING_CREATED_AT_EPOCH_MS, 0L)
        return PendingOAuthRecord(
            provider = provider,
            callbackUri = callbackUri,
            createdAtEpochMs = createdAtEpochMs,
        )
    }

    fun hasPendingOAuth(): Boolean {
        return loadPending() != null
    }

    fun clearPending() {
        prefs.edit()
            .remove(KEY_PENDING_PROVIDER)
            .remove(KEY_PENDING_CALLBACK_URI)
            .remove(KEY_PENDING_CREATED_AT_EPOCH_MS)
            .apply()
    }

    fun setLastCompletedCallbackId(callbackId: String) {
        prefs.edit()
            .putString(KEY_LAST_COMPLETED_CALLBACK_ID, callbackId)
            .apply()
    }

    fun lastCompletedCallbackId(): String? {
        return prefs.getString(KEY_LAST_COMPLETED_CALLBACK_ID, null)
            ?.trim()
            ?.takeIf { value -> value.isNotEmpty() }
    }

    fun setPendingErrorMessage(message: String?) {
        val editor = prefs.edit()
        if (message.isNullOrBlank()) {
            editor.remove(KEY_PENDING_ERROR_MESSAGE)
        } else {
            editor.putString(KEY_PENDING_ERROR_MESSAGE, message)
        }
        editor.apply()
    }

    private fun parseProvider(raw: String?): WatchProvider? {
        val normalized = raw?.trim().orEmpty()
        return when {
            WatchProvider.TRAKT.name.equals(normalized, ignoreCase = true) -> WatchProvider.TRAKT
            WatchProvider.SIMKL.name.equals(normalized, ignoreCase = true) -> WatchProvider.SIMKL
            else -> null
        }
    }

    companion object {
        private const val PREFS_NAME = "pending_oauth_store"
        private const val KEY_PENDING_PROVIDER = "pending_provider"
        private const val KEY_PENDING_CALLBACK_URI = "pending_callback_uri"
        private const val KEY_PENDING_CREATED_AT_EPOCH_MS = "pending_created_at_epoch_ms"
        private const val KEY_LAST_COMPLETED_CALLBACK_ID = "last_completed_callback_id"
        private const val KEY_PENDING_ERROR_MESSAGE = "pending_error_message"
        private const val SHA_256 = "SHA-256"

        fun callbackIdForUri(callbackUri: String): String {
            val normalized = normalizeCallbackUri(callbackUri)
            val hash = MessageDigest.getInstance(SHA_256).digest(normalized.toByteArray(Charsets.UTF_8))
            val output = StringBuilder(hash.size * 2)
            for (byte in hash) {
                output.append(((byte.toInt() ushr 4) and 0x0F).toString(16))
                output.append((byte.toInt() and 0x0F).toString(16))
            }
            return output.toString()
        }

        private fun normalizeCallbackUri(callbackUri: String): String {
            val parsed = runCatching { Uri.parse(callbackUri) }.getOrNull() ?: return callbackUri.trim()
            val scheme = parsed.scheme?.lowercase().orEmpty()
            val authority = parsed.authority?.lowercase().orEmpty()
            val path = parsed.encodedPath.orEmpty()

            if (scheme.isEmpty() && authority.isEmpty()) {
                return callbackUri.trim()
            }

            val queryParameters =
                parsed.queryParameterNames
                    .sorted()
                    .flatMap { key ->
                        val values = parsed.getQueryParameters(key)
                        if (values.isEmpty()) {
                            listOf(key to null)
                        } else {
                            values.sorted().map { value -> key to value }
                        }
                    }

            val builder = Uri.Builder().scheme(scheme).encodedAuthority(authority)
            if (path.isNotEmpty()) {
                builder.encodedPath(path)
            }
            for ((name, value) in queryParameters) {
                builder.appendQueryParameter(name, value)
            }
            return builder.build().toString()
        }
    }
}
