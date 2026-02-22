package com.crispy.tv.tv

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class TvMainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val text = TextView(this).apply {
            text = "Crispy Rewrite TV placeholder"
            textSize = 24f
            setPadding(48, 48, 48, 48)
        }

        setContentView(text)
    }
}
