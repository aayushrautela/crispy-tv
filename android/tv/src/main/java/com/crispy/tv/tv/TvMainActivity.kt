package com.crispy.tv.tv

import android.os.Bundle
import android.widget.TextView
import android.graphics.Color
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity

class TvMainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)

        val text = TextView(this).apply {
            text = "Crispy Rewrite TV placeholder"
            textSize = 24f
            setPadding(48, 48, 48, 48)
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.BLACK)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        setContentView(text)
    }
}
