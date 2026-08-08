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
 * View mode shows the display name, the bio and — directly under the profile
 * picture — the member's social links; edit mode edits the whitelisted
 * owner-writable fields (Phase 9a: displayName, bio, and the three social
 * handles) with inline validation ([ProfileValidation]) and saves via [onSave]
 * (a direct users/{uid} write). Contact details and privacy toggles are
 * deferred to a later settings slice. Wrap in [KccTheme].
 *
 * [onSave] receives the CANONICAL handles from [ProfileValidation], never the
 * raw text: what a member typed is normalised before it can be stored.
 */
@Composable
fun ProfileScreen(
    profile: UserProfile?,
    saveStatus: ProfileEditStatus,
    onSave: (displayName: String, bio: String, social: SocialHandles) -> Unit,
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
    // Badge wall + the climb to the next tier. OWN PROFILE ONLY: the badges
    // themselves are public and also render on the read-only member-profile
    // screen, but the progress bars, the observable counters and the locked
    // ladders behind this model are the owner's alone. Null while the owner
    // badge listener is still loading.
    badgeShowcase: BadgeShowcase? = null,
    // Kronpoäng balance and the newest few credits behind it (own profile only).
    pointsBalance: Long? = null,
    recentPointsEarnings: List<PointsEntry> = emptyList(),
    // Opens the full Kronpoäng ledger (credits AND debits, each dated) that the
    // points card above only summarises. Since the "Points" row was removed from
    // the map-home profile menu (Seb, 2026-07-31 — points belong on the profile
    // page), this tap-through is the app's ONLY way into that ledger. Null in a
    // config-less build with no points repository wired.
    onOpenPoints: (() -> Unit)? = null,
) {
    var editing by remember { mutableStateOf(false) }
    var nameField by remember { mutableStateOf("") }
    var bioField by remember { mutableStateOf("") }
    var facebookField by remember { mutableStateOf("") }
    var instagramField by remember { mutableStateOf("") }
    var youtubeField by remember { mutableStateOf("") }

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

            // Directly under the profile picture, as asked, and ONLY when the
            // member has filled something in — the row draws nothing at all
            // otherwise (ProfileSocialLinksRow). Hidden while editing, where
            // the fields themselves are the surface.
            if (!editing) {
                ProfileSocialLinksRow(
                    handles = profile?.social ?: SocialHandles.EMPTY,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }

            if (editing) {
                val validation =
                    ProfileValidation.validate(
                        displayName = nameField,
                        bio = bioField,
                        facebook = facebookField,
                        instagram = instagramField,
                        youtube = youtubeField,
                    )
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

                ProfileSocialLinksEditor(
                    facebook = facebookField,
                    instagram = instagramField,
                    youtube = youtubeField,
                    validation = validation,
                    onFacebookChange = { facebookField = it },
                    onInstagramChange = { instagramField = it },
                    onYoutubeChange = { youtubeField = it },
                    enabled = !saving,
                )

                if (saveStatus == ProfileEditStatus.Failed) {
                    Text(
                        text = stringResource(R.string.profile_saveError),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))
                Button(
                    // The canonical handles, not the raw fields — the same
                    // validation pass that enabled this button produced them.
                    onClick = { onSave(nameField.trim(), bioField.trim(), validation.social) },
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
                        // Seeded with the STORED handles, so re-saving an
                        // untouched form is a no-op rather than a silent clear.
                        facebookField = profile?.social?.facebook.orEmpty()
                        instagramField = profile?.social?.instagram.orEmpty()
                        youtubeField = profile?.social?.youtube.orEmpty()
                        editing = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.profile_editButton))
                }

                ProfilePointsSection(
                    balance = pointsBalance,
                    recentEarnings = recentPointsEarnings,
                    onOpenLedger = onOpenPoints,
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
            profile =
                UserProfile(
                    displayName = "Sebbe",
                    bio = "Volvo-entusiast",
                    onboardingComplete = true,
                    social =
                        SocialHandles(
                            facebook = "sebmccayen",
                            instagram = "sebmccayen",
                            youtube = "SebMcCayen",
                        ),
                ),
            saveStatus = ProfileEditStatus.Idle,
            onSave = { _, _, _ -> },
            onBack = {},
            onSignOut = {},
            statsSummary =
                ProfileStatsSummary(
                    totalDrives = 42,
                    totalDistanceMeters = 1_234_000.0,
                    totalDurationSeconds = 90_000,
                    highestMaxSpeedMps = 33.3,
                    badgeCount = 3,
                    pointsBalance = 150,
                    memberSinceMillis = 1_700_000_000_000L,
                ),
            badgeShowcase =
                BadgeShowcase.from(
                    badges =
                        listOf(
                            Badge("kronjagare_brons", "Kronjägare Brons", 1_700_000_000_000L),
                            Badge("vagfarare_brons", "Vägfarare Brons", 1_700_500_000_000L),
                            Badge("garage_created", "Garageprofil skapad", 1_699_000_000_000L),
                        ),
                    // Two observable counters → two honest bars; the other four
                    // ladders show their goal line without one.
                    counters = BadgeCounters(savedDriveDistanceMeters = 234_000.0, vehiclesInGarage = 2),
                ),
            pointsBalance = 150,
            recentPointsEarnings =
                listOf(
                    PointsEntry("a", 25L, 150L, "Märke upplåst: Vägfarare Brons", 1_700_500_000_000L),
                    PointsEntry("b", 15L, 125L, "Sparad körning", 1_700_400_000_000L),
                ),
        )
    }
}
