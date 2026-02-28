package com.crispy.tv.person

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.crispy.tv.BuildConfig
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.metadata.tmdb.TmdbApi
import com.crispy.tv.metadata.tmdb.TmdbJsonClient
import com.crispy.tv.network.AppHttp
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

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

data class PersonDetailsUiState(
    val isLoading: Boolean = true,
    val person: PersonDetails? = null,
    val errorMessage: String? = null
)

class PersonDetailsViewModel internal constructor(
    private val personId: String,
    private val tmdbClient: TmdbJsonClient,
    private val localeProvider: () -> Locale = { Locale.getDefault() }
) : ViewModel() {

    private val _uiState = MutableStateFlow(PersonDetailsUiState())
    val uiState: StateFlow<PersonDetailsUiState> = _uiState

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            _uiState.value = PersonDetailsUiState(isLoading = true)

            val tmdbPersonId = extractTmdbId(personId)
            if (tmdbPersonId == null) {
                _uiState.value = PersonDetailsUiState(isLoading = false, errorMessage = "Invalid person id")
                return@launch
            }

            val languageTag = localeProvider().toTmdbLanguageTag()
            val json =
                withContext(Dispatchers.IO) {
                    tmdbClient.getJson(
                        path = "person/$tmdbPersonId",
                        query =
                            mapOf(
                                "append_to_response" to "combined_credits,external_ids",
                                "language" to languageTag
                            )
                    )
                }

            if (json == null) {
                _uiState.value = PersonDetailsUiState(isLoading = false, errorMessage = "Failed to load")
                return@launch
            }

            val name = json.optStringOrNull("name") ?: ""
            val knownForDepartment = json.optStringOrNull("known_for_department")
            val biography = json.optStringOrNull("biography")
            val birthday = json.optStringOrNull("birthday")
            val placeOfBirth = json.optStringOrNull("place_of_birth")

            val profileUrl = TmdbApi.imageUrl(json.optStringOrNull("profile_path"), "h632")

            val externalIds = json.optJSONObject("external_ids")
            val imdbId = externalIds?.optStringOrNull("imdb_id")
            val instagramId = externalIds?.optStringOrNull("instagram_id")
            val twitterId = externalIds?.optStringOrNull("twitter_id")

            val knownFor = parseKnownFor(json)

            _uiState.value =
                PersonDetailsUiState(
                    isLoading = false,
                    person =
                        PersonDetails(
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
                            knownFor = knownFor
                        )
                )
        }
    }

    private fun parseKnownFor(json: JSONObject): List<CatalogItem> {
        val credits = json.optJSONObject("combined_credits") ?: return emptyList()
        val cast = credits.optJSONArray("cast") ?: JSONArray()

        data class Entry(val item: CatalogItem, val popularity: Double)

        val seenKeys = linkedSetOf<String>()
        val entries =
            buildList {
                for (i in 0 until cast.length()) {
                    val c = cast.optJSONObject(i) ?: continue
                    val mediaType = c.optStringOrNull("media_type")?.lowercase() ?: continue
                    val type =
                        when (mediaType) {
                            "movie" -> "movie"
                            "tv" -> "series"
                            else -> continue
                        }

                    val tmdbId = c.optInt("id", 0)
                    if (tmdbId <= 0) {
                        continue
                    }
                    val id = "tmdb:$tmdbId"
                    val key = "$type:$id"
                    if (!seenKeys.add(key)) {
                        continue
                    }

                    val title =
                        if (mediaType == "movie") {
                            c.optStringOrNull("title") ?: c.optStringOrNull("name")
                        } else {
                            c.optStringOrNull("name") ?: c.optStringOrNull("title")
                        }?.trim().orEmpty()
                    if (title.isBlank()) {
                        continue
                    }

                    val year =
                        when (mediaType) {
                            "movie" -> parseYear(c.optStringOrNull("release_date"))
                            else -> parseYear(c.optStringOrNull("first_air_date"))
                        }

                    val posterUrl = TmdbApi.imageUrl(c.optStringOrNull("poster_path"), "w500")
                    val rating = c.optDoubleOrNull("vote_average")
                    val popularity = c.optDoubleOrNull("popularity") ?: 0.0

                    add(
                        Entry(
                            item =
                                CatalogItem(
                                    id = id,
                                    title = title,
                                    posterUrl = posterUrl,
                                    backdropUrl = null,
                                    addonId = "tmdb",
                                    type = type,
                                    rating = rating?.toString(),
                                    year = year?.toString(),
                                    genre = null
                                ),
                            popularity = popularity
                        )
                    )
                }
            }

        return entries.sortedByDescending { it.popularity }.take(20).map { it.item }
    }

    private fun extractTmdbId(value: String): Int? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            return null
        }
        trimmed.toIntOrNull()?.let { return it }

        val tmdbMatch = TMDB_ID_REGEX.find(trimmed)
        tmdbMatch?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }

        val digits = ANY_DIGITS_REGEX.find(trimmed)?.groupValues?.getOrNull(1)
        return digits?.toIntOrNull()
    }

    private fun Locale.toTmdbLanguageTag(): String? {
        val language = language.trim().takeIf { it.isNotBlank() } ?: return null
        val country = country.trim().takeIf { it.isNotBlank() }
        return if (country == null) language else "$language-$country"
    }

    private fun JSONObject.optStringOrNull(key: String): String? {
        val raw = optString(key, "").trim()
        return raw.takeIf { it.isNotBlank() && it != "null" }
    }

    private fun JSONObject.optDoubleOrNull(key: String): Double? {
        val value = opt(key) ?: return null
        val number =
            when (value) {
                is Number -> value.toDouble()
                is String -> value.toDoubleOrNull()
                else -> null
            }
        return number?.takeIf { it.isFinite() }
    }

    private fun parseYear(value: String?): Int? {
        val raw = value?.trim().orEmpty()
        if (raw.length < 4) {
            return null
        }
        val year = raw.take(4).toIntOrNull() ?: return null
        return year.takeIf { it in 1800..3000 }
    }

    companion object {
        private val TMDB_ID_REGEX = Regex("\\btmdb:(?:person:)?(\\d+)", RegexOption.IGNORE_CASE)
        private val ANY_DIGITS_REGEX = Regex("\\b(\\d+)\\b")

        fun factory(appContext: Context, personId: String): ViewModelProvider.Factory {
            val context = appContext.applicationContext
            return object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (!modelClass.isAssignableFrom(PersonDetailsViewModel::class.java)) {
                        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                    }

                    val tmdbClient =
                        TmdbJsonClient(
                            apiKey = BuildConfig.TMDB_API_KEY,
                            httpClient = AppHttp.client(context)
                        )

                    @Suppress("UNCHECKED_CAST")
                    return PersonDetailsViewModel(personId = personId, tmdbClient = tmdbClient) as T
                }
            }
        }
    }
}
