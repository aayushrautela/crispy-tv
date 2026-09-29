package com.crispy.tv.accounts

/**
 * A [AccountBootstrapRepository] that answers from a value and records the calls.
 *
 * Exhaustive over the interface's three members by design: a narrow double in the same
 * module as the exhaustive `RecordingBackendApi` would let a new interface member arrive
 * unexamined. The androidMain implementation is unreachable here — its constructor needs
 * a `Context` and a `SecureTokenStore`, which reaches `AndroidKeyStore` — so the class
 * is only ever testable through this port, which is the point of the port.
 */
internal class FakeAccountBootstrapRepository(
    private var result: BootstrapResult? = null,
) : AccountBootstrapRepository {
    val bootstrapCalls = mutableListOf<Unit>()
    val signOutCalls = mutableListOf<Unit>()
    val bootstrapPrimaryProfileCalls =
        mutableListOf<Triple<String, String, String?>>()

    var bootstrapFailure: Throwable? = null
    var signOutFailure: Throwable? = null

    fun answerWith(result: BootstrapResult?) {
        this.result = result
    }

    override suspend fun bootstrap(): BootstrapResult {
        bootstrapCalls += Unit
        bootstrapFailure?.let { throw it }
        return result ?: BootstrapResult(
            signedIn = false,
            anonymous = false,
            onboardingComplete = false,
            session = null,
        )
    }

    override suspend fun bootstrapPrimaryProfile(
        name: String,
        interfaceLanguage: String,
        avatarUrl: String,
        region: String?,
    ) = throw UnsupportedOperationException("bootstrapPrimaryProfile is not stubbed")

    override suspend fun signOut() {
        signOutCalls += Unit
        signOutFailure?.let { throw it }
    }
}
