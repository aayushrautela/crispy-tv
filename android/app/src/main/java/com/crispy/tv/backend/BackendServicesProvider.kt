package com.crispy.tv.backend

import android.content.Context
import com.crispy.tv.BuildConfig
import com.crispy.tv.network.AppHttp

object BackendServicesProvider {
    @Volatile
    private var backendClient: CrispyBackendClient? = null

    fun backendClient(context: Context): CrispyBackendClient {
        backendClient?.let { return it }
        synchronized(this) {
            backendClient?.let { return it }
            val appContext = context.applicationContext
            val created = CrispyBackendClient(
                httpClient = AppHttp.client(appContext),
                backendUrl = BuildConfig.CRISPY_BACKEND_URL,
            )
            backendClient = created
            return created
        }
    }
}
