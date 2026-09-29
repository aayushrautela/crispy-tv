package com.crispy.tv.accounts

/**
 * The outcome of an email sign-up, which is not always a session.
 *
 * Supabase returns a session immediately only when the account needs no email
 * confirmation; otherwise it returns the created user with no tokens, and the
 * message tells the caller what to do next. Modelling that as a nullable
 * [session] plus a human-readable [message] keeps the branch in the transport
 * adapter instead of in every view model.
 *
 * Declared here rather than nested in [SupabaseAccountClient] for the same
 * reason [Session] is: the client is `androidMain` (OkHttp and `org.json`), so a
 * type nested in it could not be named from `commonMain` at all, and the
 * interface that does cross the boundary could not return it.
 */
data class SignUpResult(
    val session: Session?,
    val message: String,
)
