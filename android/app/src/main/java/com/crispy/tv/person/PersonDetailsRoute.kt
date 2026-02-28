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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.crispy.tv.catalog.CatalogItem
import com.crispy.tv.home.HomeCatalogPosterCard
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun PersonDetailsRoute(
    personId: String,
    onBack: () -> Unit,
    onItemClick: (CatalogItem) -> Unit
) {
    val context = LocalContext.current
    val viewModel: PersonDetailsViewModel =
        viewModel(
            key = "person:$personId",
            factory = PersonDetailsViewModel.factory(context, personId)
        )
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    PersonDetailsScreen(
        uiState = uiState,
        onBack = onBack,
        onItemClick = onItemClick
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun PersonDetailsScreen(
    uiState: PersonDetailsUiState,
    onBack: () -> Unit,
    onItemClick: (CatalogItem) -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        when {
            uiState.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    LoadingIndicator()
                }
            }

            uiState.person != null -> {
                PersonDetailsContent(
                    person = uiState.person,
                    onItemClick = onItemClick,
                    modifier = Modifier.fillMaxSize()
                )
            }

            else -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(uiState.errorMessage ?: "Something went wrong")
                }
            }
        }

        FloatingBackButton(onBack = onBack, modifier = Modifier.align(Alignment.TopStart))
    }
}

@Composable
private fun PersonDetailsContent(
    person: PersonDetails,
    onItemClick: (CatalogItem) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.imePadding(),
        contentPadding = PaddingValues(bottom = 28.dp)
    ) {
        item(key = "hero") {
            PersonHero(person = person)
        }

        item(key = "body") {
            PersonBody(person = person, onItemClick = onItemClick)
        }
    }
}

@Composable
private fun PersonHero(person: PersonDetails) {
    val heroShape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(450.dp)
                .clip(heroShape)
    ) {
        if (person.profileUrl.isNullOrBlank()) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
            )
        } else {
            AsyncImage(
                model = person.profileUrl,
                contentDescription = person.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
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

        Column(
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(horizontal = responsivePageHorizontalPadding(), vertical = 24.dp)
        ) {
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

@Composable
private fun PersonBody(
    person: PersonDetails,
    onItemClick: (CatalogItem) -> Unit
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
                    PersonMeta(label = "BORN", value = born, modifier = Modifier.weight(1f, fill = false))
                }
                if (from != null) {
                    PersonMeta(label = "FROM", value = from, modifier = Modifier.weight(1f, fill = false))
                }
            }
            Spacer(modifier = Modifier.height(18.dp))
        }

        PersonSocialLinks(person = person)

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
            items(items = person.knownFor, key = { "${it.type}:${it.id}" }) { item ->
                HomeCatalogPosterCard(item = item, onClick = { onItemClick(item) })
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

@Composable
private fun PersonSocialLinks(person: PersonDetails) {
    val uriHandler = LocalUriHandler.current

    val imdbUrl = person.imdbId?.trim()?.takeIf { it.isNotBlank() }?.let { "https://www.imdb.com/name/$it/" }
    val instagramUrl =
        person.instagramId?.trim()?.takeIf { it.isNotBlank() }?.let { "https://www.instagram.com/$it/" }
    val twitterUrl = person.twitterId?.trim()?.takeIf { it.isNotBlank() }?.let { "https://twitter.com/$it" }

    val links =
        remember(imdbUrl, instagramUrl, twitterUrl) {
            listOfNotNull(
                imdbUrl?.let { "IMDb" to it },
                instagramUrl?.let { "Instagram" to it },
                twitterUrl?.let { "Twitter" to it }
            )
        }

    if (links.isEmpty()) {
        return
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        links.forEach { (label, url) ->
            OutlinedButton(
                onClick = { uriHandler.openUri(url) },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                colors =
                    ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onBackground
                    )
            ) {
                Text(text = label, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun FloatingBackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.padding(16.dp),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(44.dp)) {
            Icon(imageVector = Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
        }
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
