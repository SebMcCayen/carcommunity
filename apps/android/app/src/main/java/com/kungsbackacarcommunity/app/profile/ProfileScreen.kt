package com.kungsbackacarcommunity.app.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.badges.Badge
import com.kungsbackacarcommunity.app.badges.BadgeCounters
import com.kungsbackacarcommunity.app.badges.BadgeShowcase
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.media.ImageUploadStatus
import com.kungsbackacarcommunity.app.points.PointsEntry
import com.kungsbackacarcommunity.app.shell.AeroPage

/**
 * Profile view/edit screen (Phase 12 slice 2).
 *
 * View mode shows the display name and bio; edit mode edits the two
 * whitelisted owner-writable fields (Phase 9a) with inline validation
 * ([ProfileValidation]) and saves via [onSave] (a direct users/{uid}
 * write). Avatar, contact details, and privacy toggles are deferred to a
 * later settings slice. Wrap in [KccTheme].
 */
@Composable
fun ProfileScreen(
    profile: UserProfile?,
    saveStatus: ProfileEditStatus,
    onSave: (displayName: String, bio: String) -> Unit,
    onBack: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    // Avatar: the resolved current-avatar URL (null renders a placeholder), the
    // in-flight upload status, and the change-picture action. All optional so a
    // config-less build (no uploader) hides the change button gracefully.
    avatarUrl: String? = null,
    avatarUploadStatus: ImageUploadStatus = ImageUploadStatus.Idle,
    onChangeAvatar: (() -> Unit)? = null,
    // At-a-glance "my stats" summary (own profile only). Null while the owner
    // reads it aggregates are still loading, or in a config-less build — the
    // section simply doesn't render. Shown only in view mode, not while editing.
    statsSummary: ProfileStatsSummary? = null,
    // Badge wall + climb to the next tier (own profile only — users/{uid}/badges
    // is an owner-only read). Null while the owner badge listener is loading.
    badgeShowcase: BadgeShowcase? = null,
    // Kronpoäng balance and the newest few credits behind it (own profile only).
    pointsBalance: Long? = null,
    recentPointsEarnings: List<PointsEntry> = emptyList(),
) {
    var editing by remember { mutableStateOf(false) }
    var nameField by remember { mutableStateOf("") }
    var bioField by remember { mutableStateOf("") }

    val saving = saveStatus == ProfileEditStatus.Saving
    // Leave edit mode only on a successful save; a failure keeps the drafts.
    LaunchedEffect(saveStatus) {
        if (saveStatus == ProfileEditStatus.Saved) editing = false
    }

    AeroPage(
        title = stringResource(R.string.profile_title),
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
            AvatarSection(
                avatarUrl = avatarUrl,
                uploadStatus = avatarUploadStatus,
                onChangeAvatar = onChangeAvatar,
            )

            if (editing) {
                val validation = ProfileValidation.validate(nameField, bioField)
                OutlinedTextField(
                    value = nameField,
                    onValueChange = { nameField = it },
                    label = { Text(stringResource(R.string.profile_displayNameLabel)) },
                    isError = validation.displayNameError != null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FieldError(validation.displayNameError)
                OutlinedTextField(
                    value = bioField,
                    onValueChange = { bioField = it },
                    label = { Text(stringResource(R.string.profile_bioLabel)) },
                    isError = validation.bioError != null,
                    modifier = Modifier.fillMaxWidth(),
                )
                FieldError(validation.bioError)

                if (saveStatus == ProfileEditStatus.Failed) {
                    Text(
                        text = stringResource(R.string.profile_saveError),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = { onSave(nameField.trim(), bioField.trim()) },
                    enabled = validation.isValid && !saving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (saving) {
                        CircularProgressIndicator(modifier = Modifier.height(20.dp))
                    } else {
                        Text(stringResource(R.string.profile_saveButton))
                    }
                }
                TextButton(
                    onClick = { editing = false },
                    enabled = !saving,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.profile_cancelButton))
                }
            } else {
                Text(
                    text = profile?.displayName?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.profile_emptyDisplayName),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
                Text(
                    text = profile?.bio?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.profile_emptyBio),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    onClick = {
                        nameField = profile?.displayName.orEmpty()
                        bioField = profile?.bio.orEmpty()
                        editing = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.profile_editButton))
                }

                ProfilePointsSection(
                    balance = pointsBalance,
                    recentEarnings = recentPointsEarnings,
                )

                ProfileBadgesSection(showcase = badgeShowcase)

                ProfileStatsSection(summary = statsSummary)
            }
    }
}

@Composable
private fun AvatarSection(
    avatarUrl: String?,
    uploadStatus: ImageUploadStatus,
    onChangeAvatar: (() -> Unit)?,
) {
    val uploading = uploadStatus == ImageUploadStatus.Uploading
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (avatarUrl != null) {
                // Coil renders nothing (keeps the placeholder tint) when no URL
                // resolves — a config-less build never crashes on rendering.
                AsyncImage(
                    model = avatarUrl,
                    contentDescription = stringResource(R.string.profile_avatarAlt),
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = "?",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (uploading) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
            }
        }

        if (onChangeAvatar != null) {
            OutlinedButton(onClick = onChangeAvatar, enabled = !uploading) {
                Text(
                    text = stringResource(
                        if (uploading) R.string.profile_avatarUploading else R.string.profile_avatarChange,
                    ),
                )
            }
        }
        when (uploadStatus) {
            ImageUploadStatus.TooLarge ->
                Text(
                    text = stringResource(R.string.profile_avatarTooLarge),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            ImageUploadStatus.Failed ->
                Text(
                    text = stringResource(R.string.profile_avatarUploadError),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            else -> Unit
        }
    }
}

@Composable
private fun FieldError(error: ProfileValidation.FieldError?) {
    if (error == null) return
    val message =
        when (error) {
            ProfileValidation.FieldError.REQUIRED -> stringResource(R.string.profile_errorNameRequired)
            ProfileValidation.FieldError.TOO_LONG -> stringResource(R.string.profile_errorTooLong)
        }
    Text(
        text = message,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.error,
    )
}

@Preview(name = "Profile", showBackground = true)
@Composable
private fun ProfileScreenPreview() {
    KccTheme {
        ProfileScreen(
            profile = UserProfile(displayName = "Sebbe", bio = "Volvo-entusiast", onboardingComplete = true),
            saveStatus = ProfileEditStatus.Idle,
            onSave = { _, _ -> },
            onBack = {},
            onSignOut = {},
            statsSummary =
                ProfileStatsSummary(
                    totalDrives = 42,
                    totalDistanceMeters = 1_234_000.0,
                    totalDurationSeconds = 90_000,
                    badgeCount = 3,
                    pointsBalance = 150,
                    memberSinceMillis = 1_700_000_000_000L,
                ),
        )
    }
}
