package com.crispy.tv.accounts

/**
 * An [AccountApi] whose answers the test sets, counting the calls that matter.
 *
 * Only four of [AccountApi]'s six members are reachable from
 * `BackendContextResolver` and the tests around it, so this implements those and
 * throws on the other two rather than pretending to be a real Supabase client.
 * `throwOn` is there so a test that wires the wrong double in gets a failure
 * naming the method, not a silent `null`.
 */
internal class FakeAccountApi(
    private val configured: Boolean = true,
    private var session: Session? = null,
    private val onEnsureValidSession: () -> Unit = {},
) : AccountApi {

    var ensureValidSessionCalls: Int = 0
        private set

    override fun isConfigured(): Boolean = configured

    override fun currentSession(): Session? = throw UnsupportedOperationException(
        "currentSession() is not exercised by this test; use ensureValidSession() instead."
    )

    override suspend fun ensureValidSession(): Session? {
        ensureValidSessionCalls++
        onEnsureValidSession()
        return session
    }

    override suspend fun signInWithEmail(email: String, password: String): Session =
        throw UnsupportedOperationException("signInWithEmail() is not exercised by this test.")

    override suspend fun signUpWithEmail(
        email: String,
        password: String,
        metadata: Map<String, String?>,
    ): SignUpResult = throw UnsupportedOperationException("signUpWithEmail() is not exercised by this test.")

    override suspend fun signOut(): Unit =
        throw UnsupportedOperationException("signOut() is not exercised by this test.")

    fun withSession(session: Session?) = apply { this.session = session }
}
