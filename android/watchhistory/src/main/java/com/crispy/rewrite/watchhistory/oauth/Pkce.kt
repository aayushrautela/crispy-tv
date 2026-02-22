package com.crispy.rewrite.watchhistory.oauth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal class Pkce {
    private val secureRandom = SecureRandom()
    private val base64UrlEncoder = Base64.getUrlEncoder().withoutPadding()

    fun generateUrlSafeToken(byteLength: Int): String {
        val bytes = ByteArray(byteLength)
        secureRandom.nextBytes(bytes)
        return base64UrlEncoder.encodeToString(bytes)
    }

    fun codeChallengeFromVerifier(codeVerifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(Charsets.UTF_8))
        return base64UrlEncoder.encodeToString(digest)
    }
}
