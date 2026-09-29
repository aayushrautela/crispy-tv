package com.crispy.tv.person

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.crispy.tv.backend.MetadataPersonDetail
import com.crispy.tv.backend.PersonSocials
import com.crispy.tv.addons.mapping.normalizedCatalogMediaType
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.domain.person.KnownForPartitioner
import com.crispy.tv.domain.person.KnownForRail
import com.crispy.tv.catalog.toCatalogItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Immutable
data class PersonKnownForRail(
    val rail: KnownForRail,
    val items: List<CatalogItem>,
)

@Immutable
data class PersonDetails(
    val personId: String,
    val name: String,
    val knownForDepartment: String?,
    val biography: String?,
    val birthday: String?,
    val placeOfBirth: String?,
    val profileUrl: String?,
    val socials: PersonSocials,
    val knownForRails: List<PersonKnownForRail>,
)

@Immutable
data class PersonDetailsUiState(
    val isLoading: Boolean = true,
    val person: PersonDetails? = null,
    val errorMessage: String? = null
)

/**
 * Loads one person's details.
 *
 * This used to take a `java.util.Locale` and pass it down to the loader, which
 * converted it with `toLanguageTag()` on the way to the backend. The wire always
 * spoke a BCP-47 tag, so the conversion was happening one layer too low. The
 * platform's answer now arrives as a tag string from [languageTagProvider], which
 * the factory in `androidMain` supplies from the platform locale -- the same rule
 * `SearchViewModel` follows.
 */
class PersonDetailsViewModel internal constructor(
    private val personId: String,
    private val personLoader: suspend (String, String) -> PersonDetails?,
    private val languageTagProvider: () -> String,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
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
                // The `null` arm is currently unreachable, and that is worth knowing
                // rather than guessing about. The only states this class can hold are:
                // (loading, null, null) at init, (false, person, null) after a
                // success, (false, null, "Failed to load") after a failure, and the
                // two in-flight variants of those. A failed load *replaces* the whole
                // state rather than merging, so `person != null` implies
                // `errorMessage == null` always. Deleting the null arm fails every
                // test in PersonDetailsViewModelTest. It is kept because it is the
                // statement of the intent -- a refresh that already has a person on
                // screen should not leave a stale error under it -- and because a
                // future change that merges instead of replacing would make it
                // load-bearing again. See the redundant-guard findings in AGENTS.md.
                errorMessage = if (current.person != null) null else current.errorMessage,
            )
        refreshJob = viewModelScope.launch {
            val person =
                withContext(ioDispatcher) {
                    personLoader(personId, languageTagProvider())
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
}

internal fun MetadataPersonDetail.toUiModel(): PersonDetails {
    val rails = KnownForPartitioner.partition(
        items = knownFor,
        typeOf = { it.normalizedCatalogMediaType() },
        genresOf = { it.genres },
    )
    return PersonDetails(
        personId = personId,
        name = name,
        knownForDepartment = knownForDepartment,
        biography = biography,
        birthday = birthday,
        placeOfBirth = placeOfBirth,
        profileUrl = profileUrl,
        socials = socials,
        knownForRails = rails.map { (rail, cards) ->
            PersonKnownForRail(
                rail = rail,
                items = cards.mapNotNull { it.toCatalogItem() }
                    .distinctBy { "${it.type}:${it.id}" },
            )
        },
    )
}
