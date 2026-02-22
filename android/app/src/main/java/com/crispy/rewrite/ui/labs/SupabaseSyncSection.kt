package com.crispy.rewrite.ui.labs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.crispy.rewrite.player.PlaybackLabUiState
import com.crispy.rewrite.player.PlaybackLabViewModel

@Composable
internal fun SupabaseSyncSection(
    uiState: PlaybackLabUiState,
    viewModel: PlaybackLabViewModel
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Supabase Sync", style = MaterialTheme.typography.titleMedium)
            Text(
                "Cloud push/pull for addons and watched history with Trakt-aware gating.",
                style = MaterialTheme.typography.bodyMedium
            )

            OutlinedTextField(
                value = uiState.supabaseEmail,
                onValueChange = viewModel::onSupabaseEmailChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("supabase_email_input"),
                label = { Text("Supabase email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
            )

            OutlinedTextField(
                value = uiState.supabasePassword,
                onValueChange = viewModel::onSupabasePasswordChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("supabase_password_input"),
                label = { Text("Supabase password") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
            )

            OutlinedTextField(
                value = uiState.supabasePin,
                onValueChange = viewModel::onSupabasePinChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("supabase_pin_input"),
                label = { Text("Sync PIN") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )

            OutlinedTextField(
                value = uiState.supabaseSyncCode,
                onValueChange = viewModel::onSupabaseSyncCodeChanged,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("supabase_code_input"),
                label = { Text("Sync code") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.testTag("supabase_initialize_button"),
                    enabled = !uiState.isUpdatingSupabase,
                    onClick = { viewModel.onInitializeSupabaseRequested() }
                ) {
                    Text("Initialize")
                }

                Button(
                    modifier = Modifier.testTag("supabase_signup_button"),
                    enabled = !uiState.isUpdatingSupabase,
                    onClick = { viewModel.onSupabaseSignUpRequested() }
                ) {
                    Text("Sign Up")
                }

                Button(
                    modifier = Modifier.testTag("supabase_signin_button"),
                    enabled = !uiState.isUpdatingSupabase,
                    onClick = { viewModel.onSupabaseSignInRequested() }
                ) {
                    Text("Sign In")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.testTag("supabase_signout_button"),
                    enabled = !uiState.isUpdatingSupabase,
                    onClick = { viewModel.onSupabaseSignOutRequested() }
                ) {
                    Text("Sign Out")
                }

                Button(
                    modifier = Modifier.testTag("supabase_push_button"),
                    enabled = !uiState.isUpdatingSupabase,
                    onClick = { viewModel.onSupabasePushRequested() }
                ) {
                    Text("Push")
                }

                Button(
                    modifier = Modifier.testTag("supabase_pull_button"),
                    enabled = !uiState.isUpdatingSupabase,
                    onClick = { viewModel.onSupabasePullRequested() }
                ) {
                    Text("Pull")
                }

                Button(
                    modifier = Modifier.testTag("supabase_sync_now_button"),
                    enabled = !uiState.isUpdatingSupabase,
                    onClick = { viewModel.onSupabaseSyncNowRequested() }
                ) {
                    Text(if (uiState.isUpdatingSupabase) "Working..." else "Sync Now")
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    modifier = Modifier.testTag("supabase_generate_code_button"),
                    enabled = !uiState.isUpdatingSupabase,
                    onClick = { viewModel.onSupabaseGenerateCodeRequested() }
                ) {
                    Text("Generate Code")
                }

                Button(
                    modifier = Modifier.testTag("supabase_claim_code_button"),
                    enabled = !uiState.isUpdatingSupabase,
                    onClick = { viewModel.onSupabaseClaimCodeRequested() }
                ) {
                    Text("Claim Code")
                }
            }

            Text(
                modifier = Modifier.testTag("supabase_auth_text"),
                text =
                    "configured=${uiState.supabaseAuthState.configured} | " +
                        "auth=${uiState.supabaseAuthState.authenticated} | " +
                        "anon=${uiState.supabaseAuthState.anonymous} | " +
                        "user=${uiState.supabaseAuthState.userId ?: "none"}",
                style = MaterialTheme.typography.bodySmall
            )

            Text(
                modifier = Modifier.testTag("supabase_status_text"),
                text = uiState.supabaseStatusMessage,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
