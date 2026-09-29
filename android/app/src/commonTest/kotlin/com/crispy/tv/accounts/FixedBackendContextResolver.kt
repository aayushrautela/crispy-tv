package com.crispy.tv.accounts

import com.crispy.tv.backend.BackendContext
import com.crispy.tv.backend.BackendContextResolver

/**
 * A resolver that answers from a value instead of from a session, a profile
 * store and a client. Its only job is to let a test say "there is a profile" or
 * "there is not" without standing up the caching implementation.
 */
internal class FixedBackendContextResolver(
    private val context: BackendContext?,
) : BackendContextResolver {
    var resolveCalls: Int = 0
        private set

    override suspend fun resolve(): BackendContext? {
        resolveCalls++
        return context
    }

    override fun clear() {
        // Nothing is cached, so there is nothing to drop. Callers that care assert
        // on the real implementation's behaviour in :android:backend's suite.
    }
}
