package com.crispy.tv.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.crispy.tv.tv.ui.TvApp
import com.crispy.tv.tv.ui.theme.CrispyTvTheme

class TvMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CrispyTvTheme {
                TvApp()
            }
        }
    }
}
