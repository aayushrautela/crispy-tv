package com.crispy.tv.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.compose.AsyncImage
import com.crispy.tv.accounts.ActiveProfileInfo
import com.crispy.tv.accounts.loadActiveProfile

private val ProfileIconButtonSize = 40.dp
private val ProfileIconContainerSize = 32.dp

@Composable
fun ProfileIconButton(onClick: () -> Unit) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val lifecycleOwner = LocalLifecycleOwner.current
    var refreshKey by remember { mutableIntStateOf(0) }
    val profile by produceState<ActiveProfileInfo?>(initialValue = null, appContext, refreshKey) {
        value = loadActiveProfile(appContext)
    }

    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    refreshKey += 1
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val initials = profile?.name
        ?.takeIf { it.isNotBlank() }
        ?.trim()
        ?.split(Regex("\\s+"))
        ?.filter { it.isNotBlank() }
        ?.take(2)
        ?.mapNotNull { it.firstOrNull()?.uppercaseChar() }
        ?.joinToString(separator = "")
        ?.ifBlank { null }

    IconButton(
        onClick = onClick,
        modifier = Modifier
            .size(ProfileIconButtonSize)
            .semantics { contentDescription = "Open profile menu" },
    ) {
        Surface(
            modifier = Modifier.size(ProfileIconContainerSize),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            tonalElevation = 0.dp,
        ) {
            when {
                profile?.avatarUrl != null -> {
                    AsyncImage(
                        model = profile!!.avatarUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                    )
                }
                !initials.isNullOrBlank() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .clip(CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = initials,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
                else -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
