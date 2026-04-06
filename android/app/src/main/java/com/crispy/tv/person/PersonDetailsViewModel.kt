package com.crispy.tv.person

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crispy.tv.accounts.SupabaseServicesProvider
import com.crispy.tv.backend.BackendServicesProvider
import com.crispy.tv.backend.CrispyBackendClient
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.ratings.formatRating
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class PersonDetails(
    val tmdbPersonId: Int,
    val name: String,
    val knownForDepartment: String?,
    val biography: String?,
    val birthday: String?,
    val placeOfBirth: String?,
    val profileUrl: String?,
    val imdbId: String?,
    val instagramId: String?,
    val twitterId: String?,
    val knownFor: List<CatalogItem>
)

@Immutable
data class PersonDetailsUiState(
    val isLoading: Boolean = true,
    val person: PersonDetails? = null,
    val errorMessage: String? = null
)

class PersonDetailsViewModel internal constructor(
    private val personId: String,
    private val personLoader: suspend (String, Locale) -> PersonDetails?,
    private val localeProvider: () -> Locale = { Locale.getDefault() },
) : ViewModel() {

    private val _uiState = MutableStateFlow(PersonDetailsUiState())
    val uiState: StateFlow<PersonDetailsUiState> = _uiState
    private var refreshJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        if (refreshJob?.isActive == true) {
            return
        }
        val current = _uiState.value
        _uiState.value =
            current.copy(
                isLoading = true,
                errorMessage = if (current.person != null) null else current.errorMessage,
            )
        refreshJob = viewModelScope.launch {
            val person =
                withContext(Dispatchers.IO) {
                    personLoader(personId, localeProvider())
                }

            if (person == null) {
                _uiState.value = PersonDetailsUiState(isLoading = false, errorMessage = "Failed to load")
                return@launch
            }

            _uiState.value =
                PersonDetailsUiState(
                    isLoading = false,
                    person = person,
                )
        }
    }

    companion object {
        fun factory(appContext: Context, personId: String): ViewModelProvider.Factory {
            val context = appContext.applicationContext
            val supabase = SupabaseServicesProvider.accountClient(context)
            val backend = BackendServicesProvider.backendClient(context)
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (!modelClass.isAssignableFrom(PersonDetailsViewModel::class.java)) {
                        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                    }

                    val personLoader: suspend (String, Locale) -> PersonDetails? = { requestedPersonId, locale ->
                        val session = supabase.ensureValidSession()
                        if (session == null) {
                            null
                        } else {
                            runCatching {
                                backend.getMetadataPersonDetail(
                                    accessToken = session.accessToken,
                                    id = requestedPersonId,
                                    language = locale.toLanguageTag(),
                                ).toUiModel()
                            }.getOrNull()
                        }
                    }

                    @Suppress("UNCHECKED_CAST")
                    return PersonDetailsViewModel(personId = personId, personLoader = personLoader) as T
                }
            }
        }
    }
}

private fun CrispyBackendClient.MetadataPersonDetail.toUiModel(): PersonDetails {
    return PersonDetails(
        tmdbPersonId = tmdbPersonId,
        name = name,
        knownForDepartment = knownForDepartment,
        biography = biography,
        birthday = birthday,
        placeOfBirth = placeOfBirth,
        profileUrl = profileUrl,
        imdbId = imdbId,
        instagramId = instagramId,
        twitterId = twitterId,
        knownFor = knownFor.mapNotNull { it.toCatalogItem() },
    )
}

private fun CrispyBackendClient.MetadataPersonKnownForItem.toCatalogItem(): CatalogItem? {
    val type = if (mediaType.equals("movie", ignoreCase = true)) "movie" else "series"
    val normalizedMediaKey = mediaKey.trim().ifBlank { return null }
    val normalizedProvider = provider?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val normalizedProviderId = providerId?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val normalizedPosterUrl = posterUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
    return CatalogItem(
        id = normalizedMediaKey,
        mediaKey = normalizedMediaKey,
        title = title,
        posterUrl = normalizedPosterUrl,
        backdropUrl = null,
        addonId = "tmdb",
        type = type,
        rating = formatRating(rating),
        year = releaseYear?.toString(),
        genre = null,
        provider = normalizedProvider,
        providerId = normalizedProviderId,
    )
}
