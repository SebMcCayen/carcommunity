package com.kungsbackacarcommunity.app.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing

/**
 * The three optional social fields in the profile edit form.
 *
 * Each accepts EITHER a username or a pasted profile link; what is stored is
 * always the canonical handle ([SocialLinks]). Leaving a field empty clears it.
 *
 * The section leads with an explicit "this is public" line: users/{uid} is
 * readable by every signed-in member, so a member filling these in is choosing
 * to publish them and must be told so on the form, not in a policy page.
 */
@Composable
fun ProfileSocialLinksEditor(
    facebook: String,
    instagram: String,
    youtube: String,
    validation: ProfileValidation.Result,
    onFacebookChange: (String) -> Unit,
    onInstagramChange: (String) -> Unit,
    onYoutubeChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
    ) {
        Text(
            text = stringResource(R.string.profile_social_sectionTitle),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.profile_social_publicNotice),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SocialField(
            platform = SocialPlatform.FACEBOOK,
            value = facebook,
            error = validation.facebookError,
            onValueChange = onFacebookChange,
            enabled = enabled,
        )
        SocialField(
            platform = SocialPlatform.INSTAGRAM,
            value = instagram,
            error = validation.instagramError,
            onValueChange = onInstagramChange,
            enabled = enabled,
        )
        SocialField(
            platform = SocialPlatform.YOUTUBE,
            value = youtube,
            error = validation.youtubeError,
            onValueChange = onYoutubeChange,
            enabled = enabled,
        )
    }
}

@Composable
private fun SocialField(
    platform: SocialPlatform,
    value: String,
    error: SocialLinks.Error?,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(socialPlatformLabel(platform)) },
        placeholder = { Text(stringResource(R.string.profile_social_hint)) },
        isError = error != null,
        singleLine = true,
        enabled = enabled,
        keyboardOptions =
            KeyboardOptions(
                // A handle is never auto-capitalised and never auto-corrected;
                // both would silently corrupt what the member typed.
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
            ),
        modifier = Modifier.fillMaxWidth(),
    )
    if (error != null) {
        Text(
            text = socialErrorMessage(platform, error),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

@Composable
private fun socialErrorMessage(platform: SocialPlatform, error: SocialLinks.Error): String =
    when (error) {
        SocialLinks.Error.FOREIGN_HOST ->
            stringResource(R.string.profile_social_errorForeignHost, socialPlatformLabel(platform))
        SocialLinks.Error.UNSUPPORTED_LINK ->
            stringResource(R.string.profile_social_errorUnsupportedLink)
        SocialLinks.Error.MALFORMED -> stringResource(R.string.profile_social_errorMalformed)
        SocialLinks.Error.TOO_LONG -> stringResource(R.string.profile_social_errorTooLong)
    }
