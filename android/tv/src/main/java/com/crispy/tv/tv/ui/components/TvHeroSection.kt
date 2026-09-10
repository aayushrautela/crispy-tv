package com.crispy.tv.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil3.compose.AsyncImage

/**
 * Full-bleed hero backdrop in the NuvioTV / Google TV style: the caller sizes and
 * pins the region; background-color fades keep the left text column and bottom edge
 * readable, logo (or title fallback), metadata row and overview sit at bottom-start.
 */
@Composable
fun TvHeroSection(
    item: CrispyCardItem?,
    modifier: Modifier = Modifier,
) {
    val bgColor = MaterialTheme.colorScheme.background
    Box(
        modifier = modifier.fillMaxWidth(),
    ) {
        if (item?.imageUrl != null) {
            AsyncImage(
                model = item.imageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Box(modifier = Modifier.fillMaxSize().background(bgColor))
        }

        // Proportional readability scrim (left fade + bottom fade) so the title
        // and bottom rail transition into the page background at any hero size.
        Box(
            modifier = Modifier
                .matchParentSize()
                .tvHeroScrim(bgColor),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 48.dp, end = 320.dp, bottom = 24.dp),
        ) {
            if (item != null) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            val metaParts = buildList {
                item?.year?.let { add(it) }
                item?.certification?.let { add(it) }
                item?.genre?.let { add(it) }
                item?.rating?.let { add("★ $it") }
            }
            if (metaParts.isNotEmpty()) {
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        text = metaParts.joinToString(" · "),
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            item?.description?.takeIf { it.isNotBlank() }?.let { description ->
                Spacer(Modifier.height(10.dp))
                Text(
                    text = description,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.78f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            item?.badge?.let { badge ->
                Spacer(Modifier.height(12.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(TvCardStyle.CardCornerRadiusDp.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = badge,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}
