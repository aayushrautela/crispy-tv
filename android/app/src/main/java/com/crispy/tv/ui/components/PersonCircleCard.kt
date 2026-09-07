package com.crispy.tv.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.crispy.tv.ui.navigation.LocalNavAnimatedContentScope
import com.crispy.tv.ui.navigation.LocalSharedTransitionScope

object PersonProfileSharedKeys {
    const val KEY_PREFIX = "backdrop-personProfile-"
    fun forPerson(personId: String): String = "$KEY_PREFIX$personId"
}

internal fun initials(name: String): String {
    val parts =
        name
            .trim()
            .split("\\s+".toRegex())
            .filter { it.isNotBlank() }

    if (parts.isEmpty()) return "?"
    if (parts.size == 1) return parts[0].take(1).uppercase()
    return (parts[0].take(1) + parts[1].take(1)).uppercase()
}

@Composable
fun PersonCircleCard(
    name: String,
    profileUrl: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sharedElementKey: String? = null,
    subtitle: String? = null,
    avatarSize: Dp = 80.dp,
) {
    Column(
        modifier = modifier.width(100.dp).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            modifier = Modifier.size(avatarSize),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            val profileModel = rememberCrispyImageModel(
                url = profileUrl?.trim()?.takeIf { it.isNotBlank() },
                width = avatarSize,
                height = avatarSize,
                memoryCacheKey = sharedElementKey,
            )
            if (profileModel != null) {
                val sharedTransitionScope = LocalSharedTransitionScope.current
                val animatedVisibilityScope = LocalNavAnimatedContentScope.current
                val imageModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null && sharedElementKey != null) {
                    with(sharedTransitionScope) {
                        Modifier
                            .sharedElement(
                                rememberSharedContentState(key = sharedElementKey),
                                animatedVisibilityScope = animatedVisibilityScope,
                            )
                            .clip(CircleShape)
                            .fillMaxSize()
                    }
                } else {
                    Modifier
                        .clip(CircleShape)
                        .fillMaxSize()
                }
                AsyncImage(
                    model = profileModel,
                    contentDescription = name,
                    modifier = imageModifier,
                    contentScale = ContentScale.Crop,
                    onSuccess = { result ->
                        if (sharedElementKey != null) {
                            result.result.memoryCacheKey?.let { cacheKey ->
                                SharedImageMemoryKeys.putCardKey(sharedElementKey, cacheKey)
                            }
                        }
                    },
                )
            } else {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = initials(name),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Text(
            text = name,
            style = MaterialTheme.typography.labelLarge.copy(
                fontSize = 12.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        subtitle?.trim()?.takeIf { it.isNotBlank() }?.let { subtitleText ->
            Text(
                text = subtitleText,
                style = MaterialTheme.typography.labelMedium.copy(
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    textAlign = TextAlign.Center,
                ),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
