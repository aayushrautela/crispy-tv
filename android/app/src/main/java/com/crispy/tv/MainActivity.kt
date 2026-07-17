package com.crispy.tv

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.crispy.tv.accounts.PendingProviderAuthStore
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.startup.AppStartup
import com.crispy.tv.ui.AppRoot
import com.crispy.tv.ui.theme.CrispyRewriteTheme


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)

        AppStartup.run(applicationContext)
        handleDeepLink(intent)

        setContent {
            CrispyRewriteTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppRoot()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val data: Uri = intent?.data ?: return
        if (data.scheme != "crispytv" || data.host != "oauth-callback") return
        val provider = data.getQueryParameter("provider").orEmpty().trim()
        if (provider.isBlank()) return
        // The server redirects here after the provider OAuth callback (status=ok|error).
        // We only care that we came back; the actual connection state is re-fetched
        // from the backend. Park the provider so AccountSettingsRoute can consume it
        // via consumePendingProviderAuth() and mark the sync provider as connected.
        SupabaseServicesProvider.pendingProviderAuthStore(applicationContext).put(provider, "")
    }


}
