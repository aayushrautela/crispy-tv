package com.crispy.tv.accounts

import com.crispy.tv.backend.Profile

/**
 * One-time account bootstrap: is anyone signed in, is the first profile set up, and
 * the sign-out that has to leave nothing behind.
 *
 * A type-level port. The implementation is pinned in `androidMain` by two things that
 * genuinely cannot travel — `SecureTokenStore` reaches `AndroidKeyStore`, and
 * `clearImageCache` reaches Coil — but **neither of those is a reason the interface
 * cannot be here.** Only the implementation is pinned. That distinction is the whole
 * reason this file exists: the previous version of this class sat in `androidMain`
 * behind a KDoc claiming both reasons were unavoidable, which was true of the class
 * and false of its two callers.
 *
 * `AppBootstrapViewModel` names this interface and is therefore able to live in
 * `commonMain`; only its `factory(context)` companion stays in `androidMain`.
 */
interface AccountBootstrapRepository {
    suspend fun bootstrap(): BootstrapResult

    suspend fun bootstrapPrimaryProfile(
        name: String,
        interfaceLanguage: String,
        avatarUrl: String,
        region: String? = null,
    ): Profile

    /** Signs out and clears everything derived from the session, including caches. */
    suspend fun signOut()
}

/**
 * The outcome of [AccountBootstrapRepository.bootstrap].
 *
 * Lifted out of the androidMain implementation because it is the return type of a
 * member on this interface, and a type declared beside the thing it describes is
 * pinned to that thing's source set — the same rule that forced `HomeHeroItem` and
 * `SignUpResult` out of theirs. It is plain data and has no reason to be Android-only.
 */
data class BootstrapResult(
    val signedIn: Boolean,
    val anonymous: Boolean,
    val onboardingComplete: Boolean,
    val session: Session?,
)
