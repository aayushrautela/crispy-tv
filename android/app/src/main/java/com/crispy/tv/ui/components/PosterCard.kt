package com.crispy.tv.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.crispy.tv.images.ResponsiveImageSet
import com.crispy.tv.ratings.formatRating

private val PosterGradientFallback = Color(0xFF151515)

@Composable
fun PosterCard(
    title: String,
    posterUrl: String?,
    backdropUrl: String?,
    rating: String?,
    year: String? = null,
    maturityRating: String? = null,
    genre: String? = null,
    logoUrl: String? = null,
    poster: ResponsiveImageSet? = ResponsiveImageSet.fromSingle(posterUrl),
    backdrop: ResponsiveImageSet? = ResponsiveImageSet.fromSingle(backdropUrl),
    logo: ResponsiveImageSet? = ResponsiveImageSet.fromSingle(logoUrl),
    gradientColorHex: String? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val fallbackColor = MaterialTheme.colorScheme.surfaceVariant
    val image = poster?.takeUnless { it.isEmpty } ?: backdrop
    val imageModel = rememberCrispyImageModel(
        image = image,
        width = 124.dp,
        height = 186.dp,
    )
    val logoModel = rememberCrispyImageModel(
        image = logo,
        width = 96.dp,
        height = 36.dp,
    )
    val gradientColor = remember(gradientColorHex) {
        gradientColorHex?.toComposeColorOrNull() ?: PosterGradientFallback
    }
    val formattedRating = formatRating(rating?.toDoubleOrNull())
    val yearText = year?.trim()?.ifBlank { null }
    val maturityText = maturityRating?.trim()?.ifBlank { null }
    val genreText = genre?.trim()?.ifBlank { null }?.let { shortenGenre(it) }
    val metadataColor = Color.White.copy(alpha = 0.78f)

    Card(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(2f / 3f)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.large
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (imageModel != null) {
                AsyncImage(
                    model = imageModel,
                    contentDescription = title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(fallbackColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = title.take(1).uppercase(),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.58f)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                gradientColor.copy(alpha = 0.52f),
                                gradientColor.copy(alpha = 0.88f),
                            )
                        )
                    )
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom
            ) {
                if (yearText != null || maturityText != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        yearText?.let { value ->
                            Text(
                                text = value,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = metadataColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        maturityText?.let { value ->
                            Surface(
                                shape = MaterialTheme.shapes.small,
                                color = Color.Transparent,
                                contentColor = metadataColor,
                                border = BorderStroke(1.dp, metadataColor),
                            ) {
                                Text(
                                    text = value,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                }

                if (logoModel != null) {
                    AsyncImage(
                        model = logoModel,
                        contentDescription = title,
                        modifier = Modifier
                            .fillMaxWidth(0.82f)
                            .height(46.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White.copy(alpha = 0.96f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 104.dp),
                    )
                }

                if (genreText != null || formattedRating != null) {
                    Spacer(modifier = Modifier.height(3.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        genreText?.let { value ->
                            Text(
                                text = value,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = metadataColor,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        formattedRating?.let { value ->
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = null,
                                modifier = Modifier.size(11.dp),
                                tint = metadataColor
                            )
                            Text(
                                text = value,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = metadataColor,
                                fontSize = 11.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}

private val genreShortLabels =
    mapOf(
        "Action & Adventure" to "Action",
        "Sci-Fi & Fantasy" to "Sci-Fi",
        "Science Fiction" to "Sci-Fi",
        "War & Politics" to "War",
        "Documentary" to "Doc",
        "Adventure" to "Adv",
    )

private fun shortenGenre(genre: String): String =
    genreShortLabels[genre] ?: genre

private fun String.toComposeColorOrNull(): Color? {
    val normalized = trim().removePrefix("#")
    val value = normalized.toLongOrNull(16) ?: return null
    return when (normalized.length) {
        6 -> Color(0xFF000000 or value)
        8 -> Color(value)
        else -> null
    }
}
