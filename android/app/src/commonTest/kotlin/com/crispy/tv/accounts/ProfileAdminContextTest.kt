package com.crispy.tv.accounts

import com.crispy.tv.backend.AccountSettings
import com.crispy.tv.backend.BackendContext
import com.crispy.tv.backend.BackendContextResolver
import com.crispy.tv.backend.Profile
import com.crispy.tv.backend.UpdateProfileInput
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server authorises account and profile writes against the **admin**
 * profile, not the profile the user happens to be looking at: a write that
 * carries a kid profile id comes back `403 admin_profile_required`. These
 * tests pin where that id comes from -- the account's own profile list -- so
 * the client cannot drift into sending the active profile instead.
 */
class ProfileAdminContextTest {
    private class FixedContextResolver : BackendContextResolver {
        override suspend fun resolve(): BackendContext? = null

        override fun clear() = Unit
    }

    private class ProfileWriteBackendApi(
        private val profiles: List<Profile>,
    ) : RecordingBackendApi() {
        val createProfileAdminIds = mutableListOf<String>()
        val updateProfileAdminIds = mutableListOf<String>()
        val patchAccountSettingsAdminIds = mutableListOf<String>()
        val deleteAccountAdminIds = mutableListOf<String>()

        override suspend fun listProfiles(accessToken: String): List<Profile> = profiles

        override suspend fun createProfile(
            accessToken: String,
            adminProfileId: String,
            name: String,
            sortOrder: Int?,
            isKids: Boolean,
            avatarKey: String?,
            interfaceLanguage: String?,
        ): Profile {
            createProfileAdminIds += adminProfileId
            return profile(id = "created", name = name)
        }

        override suspend fun updateProfile(
            accessToken: String,
            profileId: String,
            input: UpdateProfileInput,
            adminProfileId: String,
        ): Profile {
            updateProfileAdminIds += adminProfileId
            return profile(id = profileId)
        }

        override suspend fun patchAccountSettings(
            accessToken: String,
            settings: Map<String, String>,
            adminProfileId: String,
        ): AccountSettings {
            patchAccountSettingsAdminIds += adminProfileId
            return AccountSettings(pricingTier = null, hasMdbListAccess = false)
        }

        override suspend fun deleteAccount(accessToken: String, adminProfileId: String): Boolean {
            deleteAccountAdminIds += adminProfileId
            return true
        }
    }

    @Test
    fun createProfileAuthorisesWithTheAdminProfileNotTheKidProfile() = runTest {
        val backend = ProfileWriteBackendApi(
            profiles = listOf(
                profile(id = "kid", isKids = true, isAdmin = false),
                profile(id = "parent", isKids = false, isAdmin = true),
            ),
        )
        val repository = ProfileRepository(FixedContextResolver(), backend)

        repository.createProfile(accessToken = "token", name = "Second", isKids = false, avatarKey = null)

        assertEquals(listOf("parent"), backend.createProfileAdminIds)
    }

    @Test
    fun createProfileFailsWhenTheAccountHasNoAdminProfile() = runTest {
        val backend = ProfileWriteBackendApi(
            profiles = listOf(profile(id = "kid", isKids = true, isAdmin = false)),
        )
        val repository = ProfileRepository(FixedContextResolver(), backend)

        val error = runCatching {
            repository.createProfile(accessToken = "token", name = "Second", isKids = false, avatarKey = null)
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals(emptyList<String>(), backend.createProfileAdminIds)
    }

    @Test
    fun updateProfileAuthorisesWithTheAdminProfile() = runTest {
        val backend = ProfileWriteBackendApi(
            profiles = listOf(
                profile(id = "kid", isKids = true, isAdmin = false),
                profile(id = "parent", isKids = false, isAdmin = true),
            ),
        )
        val repository = ProfileRepository(FixedContextResolver(), backend)

        repository.updateProfile(
            accessToken = "token",
            profileId = "kid",
            name = "Renamed",
            isKids = true,
            avatarKey = null,
        )

        assertEquals(listOf("parent"), backend.updateProfileAdminIds)
    }

    @Test
    fun accountWritesAuthoriseWithTheAdminProfile() = runTest {
        val backend = ProfileWriteBackendApi(
            profiles = listOf(
                profile(id = "kid", isKids = true, isAdmin = false),
                profile(id = "parent", isKids = false, isAdmin = true),
            ),
        )
        val repository = AccountSettingsRepository(backend)

        repository.patchSettings(accessToken = "token", settings = mapOf("language" to "en"))
        repository.deleteAccount(accessToken = "token")

        assertEquals(listOf("parent"), backend.patchAccountSettingsAdminIds)
        assertEquals(listOf("parent"), backend.deleteAccountAdminIds)
    }
}

private fun profile(
    id: String,
    name: String = "Profile $id",
    isKids: Boolean = false,
    isAdmin: Boolean = false,
) = Profile(
    id = id,
    name = name,
    avatarKey = null,
    isKids = isKids,
    isAdmin = isAdmin,
    sortOrder = 0,
    createdByUserId = null,
    createdAt = null,
    updatedAt = null,
)
