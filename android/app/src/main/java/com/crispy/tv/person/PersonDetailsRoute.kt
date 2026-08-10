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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.home.HomeCatalogPosterCard
import com.crispy.tv.ui.components.skeletonElement
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

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
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scrollTopBarAlpha by remember {
        androidx.compose.runtime.derivedStateOf {
            if (listState.firstVisibleItemIndex > 0) {
                1f
            } else {
                (listState.firstVisibleItemScrollOffset / 420f).coerceIn(0f, 1f)
            }
        }
    }
    val topBarAlpha = if (uiState.person != null) scrollTopBarAlpha else 1f
    val containerColor = MaterialTheme.colorScheme.background.copy(alpha = topBarAlpha)
    val contentColor =
        androidx.compose.ui.graphics.lerp(
            Color.White,
            MaterialTheme.colorScheme.onBackground,
            topBarAlpha
        )

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            uiState.isLoading && uiState.person == null -> {
                PersonDetailsLoadingSkeleton(modifier = Modifier.fillMaxSize())
            }

            uiState.person != null -> {
                PersonDetailsContent(
                    person = uiState.person,
                    personId = personId,
                    initialProfileUrl = initialProfileUrl,
                    onItemClick = onItemClick,
                    listState = listState,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            else -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(uiState.errorMessage ?: "Something went wrong")
                }
            }
        }

        androidx.compose.material3.TopAppBar(
            title = {
                Text(
                    text = if (topBarAlpha > 0.65f) uiState.person?.name.orEmpty() else "",
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
            windowInsets = androidx.compose.material3.TopAppBarDefaults.windowInsets,
            colors =
                androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                    containerColor = containerColor,
                    titleContentColor = contentColor,
                    navigationIconContentColor = contentColor,
                    actionIconContentColor = contentColor
                )
        )
    }
}

@Composable
private fun PersonDetailsContent(
    person: PersonDetails,
    personId: String,
    initialProfileUrl: String?,
    onItemClick: (CatalogItem, String?) -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        state = listState,
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.background)
                .navigationBarsPadding()
                .imePadding(),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        item(key = "hero") {
            PersonHero(person = person, personId = personId, initialProfileUrl = initialProfileUrl)
        }

        item(key = "body") {
            PersonBody(person = person, onItemClick = onItemClick)
        }
    }
}

@Composable
private fun PersonDetailsLoadingSkeleton(modifier: Modifier = Modifier) {
    val horizontalPadding = responsivePageHorizontalPadding()
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(450.dp)
                .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
                .skeletonElement(
                    shape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp),
                    pulse = false,
                ),
        ) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = horizontalPadding, vertical = 24.dp),
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.5f)
                            .height(34.dp)
                            .skeletonElement(pulse = false),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.32f)
                            .height(18.dp)
                            .skeletonElement(pulse = false),
                    )
                }
            }
        }

        Column(modifier = Modifier.padding(horizontal = horizontalPadding)) {
            Spacer(modifier = Modifier.height(22.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.36f)
                        .height(20.dp)
                        .skeletonElement(pulse = false),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.94f)
                        .height(14.dp)
                        .skeletonElement(pulse = false),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.86f)
                        .height(14.dp)
                        .skeletonElement(pulse = false),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .height(14.dp)
                        .skeletonElement(pulse = false),
                )
            }

            Spacer(modifier = Modifier.height(22.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.4f)
                    .height(20.dp)
                    .skeletonElement(pulse = false),
            )
            Spacer(modifier = Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                items(count = 4, contentType = { "personKnownForSkeleton" }) {
                    Box(
                        modifier = Modifier
                            .size(width = 120.dp, height = 180.dp)
                            .skeletonElement(pulse = false),
                    )
                }
            }
        }
    }
}

@Composable
private fun PersonHero(person: PersonDetails, personId: String, initialProfileUrl: String?) {
    val heroShape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)
    val horizontalPadding = responsivePageHorizontalPadding()
    val sharedTransitionScope = com.crispy.tv.ui.navigation.LocalSharedTransitionScope.current
    val animatedVisibilityScope = com.crispy.tv.ui.navigation.LocalNavAnimatedContentScope.current
    val profileKey = "backdrop-personProfile-$personId"

    val displayProfileUrl = person.profileUrl?.takeIf { it.isNotBlank() } ?: initialProfileUrl
    val profileModel = com.crispy.tv.ui.components.rememberCrispyImageModel(
        url = displayProfileUrl,
        width = 450.dp,
        height = 450.dp,
        memoryCacheKey = profileKey,
    )

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(450.dp)
                .clip(heroShape)
    ) {
        if (profileModel != null) {
            val imageModifier = if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                with(sharedTransitionScope) {
                    Modifier
                        .sharedElement(
                            rememberSharedContentState(key = profileKey),
                            animatedVisibilityScope = animatedVisibilityScope,
                        )
                        .fillMaxSize()
                }
            } else {
                Modifier.fillMaxSize()
            }
            AsyncImage(
                model = profileModel,
                contentDescription = person.name,
                contentScale = ContentScale.Crop,
                modifier = imageModifier,
            )
        } else {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors =
                                listOf(
                                    Color.Transparent,
                                    MaterialTheme.colorScheme.background
                                ),
                            startY = 0f
                        )
                    )
        )

        Row(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = horizontalPadding, vertical = 24.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = person.name,
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold
                )
                person.knownForDepartment?.takeIf { it.isNotBlank() }?.let { dept ->
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = dept,
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
    person: PersonDetails,
    onItemClick: (CatalogItem, String?) -> Unit
) {
    Column(modifier = Modifier.padding(horizontal = responsivePageHorizontalPadding())) {
        Spacer(modifier = Modifier.height(18.dp))

        val bio = person.biography?.trim().orEmpty()
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

        val born = formatBirthday(person.birthday)
        val from = person.placeOfBirth?.trim().takeIf { !it.isNullOrBlank() }
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

        if (person.knownFor.isNotEmpty()) {
            Spacer(modifier = Modifier.height(22.dp))
            Text(
                text = "Known For",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(12.dp))
        }
    }

    if (person.knownFor.isNotEmpty()) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(horizontal = responsivePageHorizontalPadding())
        ) {
            items(items = person.knownFor, key = { "${it.type}:${it.id}" }, contentType = { "poster" }) { item ->
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
