package com.crispy.tv.watchhistory

data class WatchHistoryConfig(
    val traktRedirectUri: String = "",
    val simklRedirectUri: String = "",
    val supabaseUrl: String = "",
    val supabasePublishableKey: String = "",
    val appVersion: String = "dev",
)
