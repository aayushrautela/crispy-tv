package com.crispy.tv.tv.session

import android.content.Context
import android.os.Build
import com.crispy.tv.accounts.Session
import com.crispy.tv.network.CrispyHttpClient
import com.crispy.tv.network.CrispyHttpResponse
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.util.UUID

/**
 * RFC 8628 device authorization client against the Crispy backend. The TV requests codes via
 * [authorize], shows the user code + QR, then polls [poll] until the user approves, denies, or
 * the code expires. Approval yields a long-lived personal access token ("cp_pat_…") which is
 * wrapped as a [Session] and persisted by the caller through [com.crispy.tv.accounts.SecureTokenStore].
 */
class DeviceLoginClient(
    private val httpClient: CrispyHttpClient,
    private val backendUrl: String,
    context: Context,
) {
    private val devicePrefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isConfigured(): Boolean = backendUrl.isNotBlank()

    data class DeviceAuthorization(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val verificationUriComplete: String,
        val expiresInSec: Long,
        val intervalSec: Long,
    )

    sealed interface PollResult {
        data object Pending : PollResult
        data class SlowDown(val intervalSec: Long) : PollResult
        data class Approved(val session: Session) : PollResult
        data object Denied : PollResult
        data object Expired : PollResult
    }

    suspend fun authorize(): DeviceAuthorization {
        checkConfigured()
        val payload = JSONObject()
            .put("clientId", CLIENT_ID)
            .put("deviceName", deviceName())
        storedDeviceId()?.let { payload.put("deviceId", it) }
        val response = httpClient.postJson(
            "$backendUrl/v1/auth/device/authorize".toHttpUrl(),
            payload.toString(),
            jsonHeaders(),
            callTimeoutMs = CALL_TIMEOUT_MS,
        )
        val data = requireData(response)
        val authorization = DeviceAuthorization(
            deviceCode = data.optString("deviceCode").trim(),
            userCode = data.optString("userCode").trim(),
            verificationUri = data.optString("verificationUri").trim(),
            verificationUriComplete = data.optString("verificationUriComplete").trim(),
            expiresInSec = data.optLong("expiresIn", 900L).coerceAtLeast(1L),
            intervalSec = data.optLong("interval", 5L).coerceAtLeast(1L),
        )
        if (authorization.deviceCode.isBlank() ||
            authorization.userCode.isBlank() ||
            authorization.verificationUriComplete.isBlank()
        ) {
            throw IllegalStateException("Device authorization response is incomplete.")
        }
        return authorization
    }

    suspend fun poll(deviceCode: String): PollResult {
        checkConfigured()
        val response = httpClient.postJson(
            "$backendUrl/v1/auth/device/token".toHttpUrl(),
            JSONObject().put("deviceCode", deviceCode).toString(),
            jsonHeaders(),
            callTimeoutMs = CALL_TIMEOUT_MS,
        )
        if (response.code == 429) {
            return PollResult.SlowDown(intervalSec = DEFAULT_INTERVAL_SEC)
        }
        val data = requireData(response)
        return when (data.optString("status").trim()) {
            "slow_down" -> PollResult.SlowDown(data.optLong("interval", DEFAULT_INTERVAL_SEC + 5L).coerceAtLeast(1L))
            "access_denied" -> PollResult.Denied
            "expired_token" -> PollResult.Expired
            "approved" -> PollResult.Approved(sessionFromApproval(data))
            else -> PollResult.Pending
        }
    }

    private fun sessionFromApproval(data: JSONObject): Session {
        val accessToken = data.optString("plaintextToken").trim()
        if (!accessToken.startsWith("cp_pat_")) {
            throw IllegalStateException("Device sign-in did not return a valid token.")
        }
        val user = data.optJSONObject("user")
        data.optString("deviceId").trim().takeIf { it.isNotBlank() }?.let { saveDeviceId(it) }
        return Session(
            accessToken = accessToken,
            refreshToken = "",
            expiresAtEpochSec = System.currentTimeMillis() / 1000L + PAT_TTL_SEC,
            userId = user?.optString("id")?.trim()?.ifBlank { null },
            email = user?.optString("email")?.trim()?.ifBlank { null },
            anonymous = false,
        )
    }

    private fun storedDeviceId(): String? {
        val existing = devicePrefs.getString(KEY_DEVICE_ID, null)?.trim()
        if (!existing.isNullOrBlank()) return existing
        val created = UUID.randomUUID().toString()
        devicePrefs.edit().putString(KEY_DEVICE_ID, created).apply()
        return created
    }

    private fun saveDeviceId(deviceId: String) {
        devicePrefs.edit().putString(KEY_DEVICE_ID, deviceId).apply()
    }

    private fun deviceName(): String {
        return Build.MODEL.trim().ifBlank { "Android TV" }.take(60)
    }

    private fun checkConfigured() {
        if (!isConfigured()) throw IllegalStateException("Backend URL is not configured.")
    }

    private fun jsonHeaders(): Headers {
        return Headers.Builder()
            .add("Content-Type", "application/json")
            .add("Accept", "application/json")
            .build()
    }

    private fun requireData(response: CrispyHttpResponse): JSONObject {
        if (response.code !in 200..299) {
            throw IllegalStateException(extractErrorMessage(response) ?: "HTTP ${response.code}")
        }
        val json = runCatching { JSONObject(response.body) }.getOrNull()
            ?: throw IllegalStateException("Unexpected response from server.")
        return json.optJSONObject("data")
            ?: throw IllegalStateException("Response missing 'data' envelope")
    }

    private fun extractErrorMessage(response: CrispyHttpResponse): String? {
        val json = runCatching { JSONObject(response.body) }.getOrNull() ?: return null
        return json.optJSONObject("error")?.optString("message")?.trim()?.ifBlank { null }
            ?: json.optString("message").trim().ifBlank { null }
    }

    private companion object {
        private const val CLIENT_ID = "crispy-tv"
        private const val PREFS_NAME = "crispy_tv_device_identity"
        private const val KEY_DEVICE_ID = "device_id"
        private const val CALL_TIMEOUT_MS = 15_000L
        private const val DEFAULT_INTERVAL_SEC = 5L
        private const val PAT_TTL_SEC = 90L * 24L * 60L * 60L
    }
}
