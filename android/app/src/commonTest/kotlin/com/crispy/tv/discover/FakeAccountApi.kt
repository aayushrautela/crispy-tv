package com.crispy.tv.discover

import com.crispy.tv.accounts.AccountApi
import com.crispy.tv.accounts.SignUpResult
import com.crispy.tv.accounts.Session

/**
 * The minimum an account port has to do to let a repository be constructed and
 * driven, plus a call counter so a test can prove it was *not* consulted.
 *
 * The counter is the point of this double rather than a convenience: the
 * "nothing to plan means nothing is asked" case can only be pinned by observing
 * that [ensureValidSessionCalls] is still zero after the call returns.
 *
 * `open` so a test that needs a *throwing* [AccountApi.ensureValidSession] can override the one
 * member rather than writing a second double in its own file: a null session is the "not signed
 * in" answer, and no amount of constructing this double with a different session produces the
 * "backend is down" one.
 */
internal open class FakeAccountApi(
    private val session: Session? = null,
) : AccountApi {
    var ensureValidSessionCalls = 0
        private set

    override fun isConfigured(): Boolean = true

    override fun currentSession(): Session? = session

    override suspend fun ensureValidSession(): Session? {
        ensureValidSessionCalls++
        return session
    }

    override suspend fun signInWithEmail(email: String, password: String): Session =
        error("signInWithEmail is not stubbed")

    override suspend fun signUpWithEmail(
        email: String,
        password: String,
        metadata: Map<String, String?>,
    ): SignUpResult = error("signUpWithEmail is not stubbed")

    override suspend fun signOut(): Unit = error("signOut is not stubbed")
}
