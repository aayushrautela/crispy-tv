package com.crispy.tv

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.crispy.tv.accounts.ActiveProfileStore
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.backend.BackendServicesProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertSame

/**
 * The caching contract of the two service providers the composition root reads.
 *
 * `SupabaseServicesProvider` and `BackendServicesProvider` are the same shape as
 * `PlaybackDependencies`: a `@Volatile` field, a double-checked accessor, and a
 * public `var` factory the composition root may install. What differs is that
 * these two construct their dependency rather than receive it, so what is being
 * pinned is that a construction actually happens once.
 *
 * The cost of getting it wrong is not a leak but a second copy of the session:
 * two backend clients are two OkHttp connection pools and two sets of auth
 * headers for one user, and two `ActiveProfileStore`s are two writers to the
 * same SharedPreferences file.
 *
 * ## What is deliberately absent, and why
 *
 * `secureTokenStore`, `accountClient` and `homeCatalogService` are not here and
 * cannot be, in a test of any kind on this classpath. All three reach
 * `SecureTokenStore`, whose constructor calls `KeyStore.getInstance("AndroidKeyStore")`
 * — and `homeCatalogService` gets there indirectly, through
 * `BackendContextResolverProvider.get`, which needs the account client to read
 * the signed-in profile. There is no `AndroidKeyStore` on a JVM, so the failure
 * is `KeyStoreException: AndroidKeyStore not found` before any assertion runs.
 * It is the same wall that makes the screenshot suite boot
 * `ScreenshotTestApplication` instead of `CrispyApplication`, and a fake
 * keystore would prove nothing about the real one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class ServiceProviderCachingTest {

    private val applicationContext: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun theBackendClientIsBuiltOnceFromTheApplicationContext() {
        val scopedContext = ContextWrapper(applicationContext)

        val first = BackendServicesProvider.backendClient(scopedContext)
        val second = BackendServicesProvider.backendClient(scopedContext)

        val why = "a second backend client is a second OkHttp connection pool and a second " +
            "set of auth headers for one user"
        assertSame(first, second, why)
    }

    @Test
    fun theActiveProfileStoreIsBuiltOnce() {
        val first: ActiveProfileStore = SupabaseServicesProvider.activeProfileStore(applicationContext)
        val second: ActiveProfileStore = SupabaseServicesProvider.activeProfileStore(applicationContext)

        val why = "two stores read the same SharedPreferences file, so a write through one " +
            "is invisible to the other until the process restarts"
        assertSame(first, second, why)
    }
}
