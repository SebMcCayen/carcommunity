package com.kungsbackacarcommunity.app.profile

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.design.KccTheme

/**
 * The member's social links, as tappable icons — the row that sits directly
 * under the profile picture on BOTH the owner's own profile and the read-only
 * member profile.
 *
 * RENDERS NOTHING when the member has filled none in: no empty row, no greyed
 * placeholders, not even the spacing. [SocialLinks.links] returning an empty
 * list is the single seam that decides this, which is what the unit tests
 * assert (a Compose test would be an instrumented test and would not run in
 * the JVM gate).
 *
 * The URL is built here from a constant host plus the stored HANDLE, and the
 * handle is re-validated on the way out, so what this opens can never be a
 * host another member chose.
 */
@Composable
fun ProfileSocialLinksRow(
    handles: SocialHandles,
    modifier: Modifier = Modifier,
) {
    val links = SocialLinks.links(handles)
    if (links.isEmpty()) return

    val context = LocalContext.current
    val openFailed = stringResource(R.string.profile_social_openFailed)
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(KccSpacing.s1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        links.forEach { link ->
            val presentation = link.platform.presentation()
            IconButton(onClick = { openSocialLink(context, link.url, openFailed) }) {
                Icon(
                    painter = painterResource(presentation.icon),
                    // The handle is spoken too: a screen-reader user gets the
                    // same information sighted users get from the glyph plus
                    // the destination, not just "Instagram".
                    contentDescription =
                        stringResource(presentation.contentDescription, link.handle),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    // 28dp glyph inside IconButton's 48dp touch target: the
                    // glyphs are drawn to read from 24dp up.
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

private data class SocialPresentation(
    @DrawableRes val icon: Int,
    @StringRes val contentDescription: Int,
    @StringRes val label: Int,
)

private fun SocialPlatform.presentation(): SocialPresentation =
    when (this) {
        SocialPlatform.FACEBOOK ->
            SocialPresentation(
                R.drawable.ic_social_facebook,
                R.string.profile_social_openFacebook,
                R.string.profile_social_facebookLabel,
            )
        SocialPlatform.INSTAGRAM ->
            SocialPresentation(
                R.drawable.ic_social_instagram,
                R.string.profile_social_openInstagram,
                R.string.profile_social_instagramLabel,
            )
        SocialPlatform.YOUTUBE ->
            SocialPresentation(
                R.drawable.ic_social_youtube,
                R.string.profile_social_openYoutube,
                R.string.profile_social_youtubeLabel,
            )
    }

/** The human-readable platform name, for labels and error messages. */
@Composable
fun socialPlatformLabel(platform: SocialPlatform): String =
    stringResource(platform.presentation().label)

/**
 * Opens a canonical social URL in the browser (or the platform's app, if it
 * claims the link).
 *
 * TWO FAILURE MODES, AND ONLY ONE OF THEM IS REACHABLE.
 *
 * The https guard returns SILENTLY, deliberately. Its only caller passes
 * [SocialLink.url], which [SocialLinks.canonicalUrl] builds as the literal
 * prefix `https://` plus a handle that has already been matched against a
 * pattern admitting neither ':' nor '/', so the scheme is `https` by
 * construction and this branch is unreachable today. It exists so that a future
 * caller passing something else cannot launch a non-web intent (`intent://`,
 * `javascript:`) from a member-controlled value. There is no toast because
 * there is no member-visible state to explain: reaching it would be a
 * programming error, not something the member did or could fix.
 *
 * The reachable failure is the OTHER one — a device with no browser and no
 * platform app to handle the link — and that one does toast rather than dying
 * as a dead tap.
 */
private fun openSocialLink(context: Context, url: String, failureMessage: String) {
    val uri = Uri.parse(url)
    // lowercase() folds with Locale.ROOT, so a Turkish device cannot turn the
    // 'I' of a scheme into a dotless 'ı' and fail this comparison.
    if (uri.scheme?.lowercase() != "https") return
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, failureMessage, Toast.LENGTH_SHORT).show()
    }
}

@Preview(name = "Social links", showBackground = true)
@Composable
private fun ProfileSocialLinksRowPreview() {
    KccTheme {
        ProfileSocialLinksRow(
            handles =
                SocialHandles(
                    facebook = "sebmccayen",
                    instagram = "sebmccayen",
                    youtube = "SebMcCayen",
                ),
        )
    }
}
