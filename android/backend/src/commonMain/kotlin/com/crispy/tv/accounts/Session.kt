package com.crispy.tv.accounts

/**
 * The single representation of a Supabase auth session. Owning it here (rather than as an
 * inner type on the client) lets the encrypted token store and every consumer share one model.
 */
data class Session(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochSec: Long?,
    val userId: String?,
    val email: String?,
    val anonymous: Boolean,
)
