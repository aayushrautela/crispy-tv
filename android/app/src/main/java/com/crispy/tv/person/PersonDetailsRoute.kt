@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
)

package com.crispy.tv.person

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.details.initials
import com.crispy.tv.home.HomeCatalogPosterCard
import com.crispy.tv.ui.components.CardStyle
import com.crispy.tv.ui.components.rememberCrispyImageModel
import com.crispy.tv.ui.components.skeletonElement
import com.crispy.tv.ui.navigation.LocalNavAnimatedContentScope
import com.crispy.tv.ui.navigation.LocalSharedTransitionScope
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val PersonAvatarSize = 120.dp
private const val PERSON_PROFILE_KEY_PREFIX = "backdrop-personProfile-"

@Composable
fun PersonDetailsRoute(
    personId: String,
    onBack: () -> Unit,
    onItemClick: (CatalogItem, String?) -> Unit,
    initialProfileUrl: String? = null,
) {
    val context = LocalContext.current
    val viewModel: PersonDetailsViewModel =
        viewModel(
            key = personId,
            factory = PersonDetailsViewModel.factory(context, personId)
        )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    PersonDetailsScreen(
        uiState = uiState,
        personId = personId,
        initialProfileUrl = initialProfileUrl,
        onBack = onBack,
        onItemClick = onItemClick,
    )
}

@Composable
private fun PersonDetailsScreen(
    uiState: PersonDetailsUiState,
    personId: String,
    initialProfileUrl: String?,
    onBack: () -> Unit,
    onItemClick: (CatalogItem, String?) -> Unit,
) {
    val listState = rememberLazyListState()
    val topBarAlpha by remember {
        derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) {
                1f
            } else {
                (listState.firstVisibleItemScrollOffset / 420f).coerceIn(0f, 1f)
            }
        }
    }
    val person = uiState.person

    Box(modifier = Modifier.fillMaxSize()) {
        if (person != null || uiState.isLoading) {
            LazyColumn(
                state = listState,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .navigationBarsPadding()
                        .imePadding(),
                contentPadding = PaddingValues(bottom = 28.dp)
            ) {
                item(key = "header") {
                    PersonHeader(
                        person = person,
                        personId = personId,
                        isLoading = uiState.isLoading,
                        initialProfileUrl = initialProfileUrl,
                    )
                }

                item(key = "body") {
                    PersonBody(
                        person = person,
                        isLoading = uiState.isLoading,
                        onItemClick = onItemClick,
                    )
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = uiState.errorMessage ?: "Something went wrong",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
            }
        }

        TopAppBar(
            title = {
                Text(
                    text = if (topBarAlpha > 0.65f) person?.name.orEmpty() else "",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            },
            windowInsets = TopAppBarDefaults.windowInsets,
            colors =
                TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background.copy(alpha = topBarAlpha),
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                )
        )
    }
}

@Composable
private fun PersonHeader(
    person: PersonDetails?,
    personId: String,
    isLoading: Boolean,
    initialProfileUrl: String?,
) {
    val horizontalPadding = responsivePageHorizontalPadding()
    val sharedTransitionScope = LocalSharedTransitionScope.current
    val animatedVisibilityScope = LocalNavAnimatedContentScope.current
    val profileKey = "$PERSON_PROFILE_KEY_PREFIX$personId"
    val displayName = person?.name?.trim().orEmpty()
    val displayProfileUrl = person?.profileUrl?.trim()?.takeIf { it.isNotEmpty() } ?: initialProfileUrl
    val profileModel = rememberCrispyImageModel(
        url = displayProfileUrl,
        width = PersonAvatarSize,
        height = PersonAvatarSize,
        memoryCacheKey = profileKey,
    )

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(modifier = Modifier.size(PersonAvatarSize)) {
            if (profileModel != null) {
                val imageModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                    with(sharedTransitionScope) {
                        Modifier
                            .sharedElement(
                                rememberSharedContentState(key = profileKey),
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
                    contentDescription = displayName.ifBlank { null },
                    contentScale = ContentScale.Crop,
                    modifier = imageModifier,
                )
            } else {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = initials(displayName),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isLoading && person == null) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth(0.55f)
                            .height(28.dp)
                            .skeletonElement(pulse = false)
                )
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth(0.34f)
                            .height(18.dp)
                            .skeletonElement(pulse = false)
                )
            } else {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold
                )
                person?.knownForDepartment?.takeIf { it.isNotBlank() }?.let { department ->
                    Text(
                        text = department,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }
}

@Composable
private fun PersonBody(
    person: PersonDetails?,
    isLoading: Boolean,
    onItemClick: (CatalogItem, String?) -> Unit
) {
    val horizontalPadding = responsivePageHorizontalPadding()
    val showPlaceholders = person == null && isLoading

    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = horizontalPadding)
    ) {
        Spacer(modifier = Modifier.height(18.dp))

        if (showPlaceholders) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(0.28f)
                        .height(20.dp)
                        .skeletonElement(pulse = false)
            )
            Spacer(modifier = Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0.94f, 0.88f, 0.92f, 0.84f, 0.9f, 0.52f).forEach { lineWidthFraction ->
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth(lineWidthFraction)
                                .height(14.dp)
                                .skeletonElement(pulse = false)
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                PersonMetaSkeleton(modifier = Modifier.weight(1f))
                PersonMetaSkeleton(modifier = Modifier.weight(1f))
            }
        } else {
            val bio = person?.biography?.trim().orEmpty()
            if (bio.isNotBlank()) {
                Text(
                    text = "Biography",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(10.dp))

                var expanded by rememberSaveable { mutableStateOf(false) }
                Text(
                    text = bio,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (expanded) Int.MAX_VALUE else 6,
                    overflow = TextOverflow.Ellipsis
                )

                if (bio.length >= 240) {
                    TextButton(onClick = { expanded = !expanded }) {
                        Text(if (expanded) "Show Less" else "Read More")
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
            }

            val born = formatBirthday(person?.birthday)
            val from = person?.placeOfBirth?.trim()?.takeIf { it.isNotEmpty() }
            if (born != null || from != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    if (born != null) {
                        PersonMeta(label = "BORN", value = born, modifier = Modifier.weight(1f))
                    }
                    if (from != null) {
                        PersonMeta(label = "FROM", value = from, modifier = Modifier.weight(1f))
                    }
                }
                Spacer(modifier = Modifier.height(18.dp))
            }
        }

        Spacer(modifier = Modifier.height(22.dp))
        if (showPlaceholders) {
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth(0.3f)
                        .height(20.dp)
                        .skeletonElement(pulse = false)
            )
        } else if (person?.knownFor?.isNotEmpty() == true) {
            Text(
                text = "Known For",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
    }

    if (showPlaceholders || person?.knownFor?.isNotEmpty() == true) {
        val knownFor = person?.knownFor.orEmpty()
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = horizontalPadding)
        ) {
            if (showPlaceholders) {
                items(count = 4, contentType = { "personKnownForSkeleton" }) {
                    PersonPosterSkeleton()
                }
            } else {
                items(
                    items = knownFor,
                    key = { "${it.type}:${it.id}" },
                    contentType = { "poster" }
                ) { item ->
                    val key = "person-knownFor-${item.itemId}"
                    HomeCatalogPosterCard(
                        item = item,
                        sharedElementKey = key,
                        onClick = { onItemClick(item, key) },
                    )
                }
            }
        }
    }
}

@Composable
private fun PersonPosterSkeleton() {
    Box(
        modifier =
            Modifier
                .width(CardStyle.landscapeCardWidth())
                .aspectRatio(CardStyle.LandscapeAspectRatio)
                .skeletonElement(shape = RoundedCornerShape(CardStyle.CardCornerRadiusDp.dp), pulse = false)
    )
}

@Composable
private fun PersonMetaSkeleton(modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier =
                Modifier
                    .width(44.dp)
                    .height(10.dp)
                    .skeletonElement(pulse = false)
        )
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .skeletonElement(pulse = false)
        )
    }
}

@Composable
private fun PersonMeta(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatBirthday(value: String?): String? {
    val raw = value?.trim().orEmpty()
    if (raw.isEmpty()) {
        return null
    }

    return runCatching {
        LocalDate.parse(raw)
            .format(DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.getDefault()))
    }.getOrElse { raw }
}
