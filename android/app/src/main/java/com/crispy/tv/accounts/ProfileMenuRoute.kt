package com.crispy.tv.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.crispy.tv.avatar.AvatarUrlResolver
import com.crispy.tv.backend.BackendServicesProvider
import com.crispy.tv.ui.components.StandardTopAppBar
import com.crispy.tv.ui.theme.Dimensions
import com.crispy.tv.ui.theme.responsivePageHorizontalPadding
import com.crispy.tv.ui.utils.appBarScrollBehavior

/**
 * Google-style account menu shown when the profile icon is tapped. It surfaces the active
 * profile, a primary "Open settings" action into the full Settings page, quick access to
 * profile management, and sign-out — mirroring how first-party Google apps present an
 * account switcher before diving into Settings.
 */
data class ActiveProfileInfo(
    val id: String,
    val name: String?,
    val avatarUrl: String?,
)

suspend fun loadActiveProfile(context: android.content.Context): ActiveProfileInfo? {
    val appContext = context.applicationContext
    val supabase = SupabaseServicesProvider.accountClient(appContext)
    val backend = BackendServicesProvider.backendClient(appContext)
    val activeProfileStore = SupabaseServicesProvider.activeProfileStore(appContext)
    return runCatching {
        val session = supabase.ensureValidSession() ?: supabase.currentSession() ?: return null
        val me = backend.getMe(session.accessToken)
        val userId = (session.userId?.ifBlank { me.user.id } ?: me.user.id).trim()
        if (userId.isBlank()) return null
        val activeId = activeProfileStore.getActiveProfileId(userId)?.trim().orEmpty()
        val profile = me.profiles.firstOrNull { it.id == activeId } ?: me.profiles.firstOrNull()
        profile?.let {
            ActiveProfileInfo(
                id = it.id,
                name = it.name,
                avatarUrl = AvatarUrlResolver.resolveAvatarUrl(it.avatarKey),
            )
        }
    }.getOrNull()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileMenuRoute(
    onOpenSettings: () -> Unit,
    onManageProfiles: () -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val appContext = remember(context) { context.applicationContext }
    val profile by produceState<ActiveProfileInfo?>(initialValue = null, appContext) {
        value = loadActiveProfile(appContext)
    }
    val scrollBehavior = appBarScrollBehavior()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            StandardTopAppBar(
                title = "Profile",
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(
                    horizontal = responsivePageHorizontalPadding(),
                    vertical = Dimensions.SectionSpacing,
                ),
            verticalArrangement = Arrangement.spacedBy(Dimensions.SectionSpacing),
        ) {
            ProfileMenuHeader(profile = profile)

            Button(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Settings, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text("Open settings")
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.extraLarge)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                ProfileMenuRow(
                    icon = Icons.Outlined.Person,
                    label = "Manage profiles",
                    onClick = onManageProfiles,
                )
                HorizontalDivider(
                    modifier = Modifier.padding(start = 68.dp),
                    thickness = 0.5.dp,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                ProfileMenuRow(
                    icon = Icons.AutoMirrored.Outlined.ExitToApp,
                    label = "Sign out",
                    onClick = onSignOut,
                )
            }
        }
    }
}

@Composable
private fun ProfileMenuHeader(profile: ActiveProfileInfo?) {
    val name = profile?.name?.takeIf { it.isNotBlank() }
    val initials = name
        ?.split(Regex("\\s+"))
        ?.filter { it.isNotBlank() }
        ?.take(2)
        ?.mapNotNull { it.firstOrNull()?.uppercaseChar() }
        ?.joinToString(separator = "")
        ?.ifBlank { null }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (profile?.avatarUrl != null) {
                AsyncImage(
                    model = profile.avatarUrl,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                )
            } else if (!initials.isNullOrBlank()) {
                Text(
                    text = initials,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    textAlign = TextAlign.Center,
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(36.dp),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = if (name != null) "Hi, $name!" else "Your profile",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (name != null) {
                Text(
                    text = "Manage your account and preferences",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ProfileMenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    ListItem(
        leadingContent = {
            Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge) 
    }
}
