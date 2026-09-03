package com.crispy.tv.plugins.repo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class PluginManifest(
    val name: String,
    val version: String,
    val description: String? = null,
    val author: String? = null,
    val scrapers: List<PluginManifestScraper> = emptyList(),
)

@Serializable
data class PluginManifestScraper(
    val id: String,
    val name: String,
    val description: String? = null,
    val version: String,
    val filename: String,
    @SerialName("supportedTypes") val supportedTypes: List<String> = listOf("movie", "tv"),
    val enabled: Boolean = true,
    val hasSettings: Boolean = false,
    val logo: String? = null,
    @SerialName("contentLanguage") val contentLanguage: List<String>? = null,
    @SerialName("disabledPlatforms") val disabledPlatforms: List<String>? = null,
)

internal class PluginRepositoryException(message: String) : Exception(message)

internal object PluginManifestParser {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    fun parse(payload: String): PluginManifest {
        val manifest = json.decodeFromString<PluginManifest>(payload)
        require(manifest.name.isNotBlank()) { "Manifest name is missing" }
        require(manifest.version.isNotBlank()) { "Manifest version is missing" }
        require(manifest.scrapers.isNotEmpty()) { "Manifest has no scrapers" }
        return manifest
    }

    fun write(manifest: PluginManifest): String = json.encodeToString(manifest)
}
