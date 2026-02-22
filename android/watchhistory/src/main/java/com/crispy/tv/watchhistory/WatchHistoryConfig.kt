package com.crispy.tv.watchhistory

data class WatchHistoryConfig(
    val traktClientSecret: String = "",
    val traktRedirectUri: String = "",
    val simklClientSecret: String = "",
    val simklRedirectUri: String = "",
    val appVersion: String = "dev",
)
