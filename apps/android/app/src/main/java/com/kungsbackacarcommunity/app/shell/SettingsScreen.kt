package com.kungsbackacarcommunity.app.shell

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CardMembership
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import com.kungsbackacarcommunity.app.BuildConfig
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing

/**
 * The Settings screen reached from the profile-picture ("More") menu. It groups
 * account destinations that used to sit loose in the More hub (subscription,
 * notification settings, blocked users, partner statistics, feedback, account
 * deletion) plus community/legal external links, and shows the app version at
 * the bottom.
 *
 * In-app destinations are passed as null-guarded callbacks so an unavailable
 * dependency simply hides its row (mirroring [HubEntry]). External links are
 * opened here via [LocalContext], restricted to http(s) / Play-store intents.
 */
@Composable
fun SettingsScreen(
    onManageSubscription: (() -> Unit)?,
    onNotificationSettings: (() -> Unit)?,
    onBlockedUsers: (() -> Unit)?,
    onPartnerStats: (() -> Unit)?,
    onFeedback: (() -> Unit)?,
    onDeleteAccount: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val instagramUrl = stringResource(R.string.url_instagram)
    val privacyUrl = stringResource(R.string.url_privacy)
    val termsUrl = stringResource(R.string.url_terms)

    AeroPage(title = stringResource(R.string.settingsMenu_title), modifier = modifier) {
        SettingsSectionHeader(stringResource(R.string.settingsMenu_accountSection))
        if (onManageSubscription != null) {
            HubRow(
                stringResource(R.string.settingsMenu_manageSubscription),
                Icons.Filled.CardMembership,
                onManageSubscription,
            )
        }
        if (onNotificationSettings != null) {
            HubRow(
                stringResource(R.string.settingsMenu_notificationSettings),
                Icons.Filled.NotificationsActive,
                onNotificationSettings,
            )
        }
        if (onBlockedUsers != null) {
            HubRow(
                stringResource(R.string.settings_blockedUsers),
                Icons.Filled.Block,
                onBlockedUsers,
            )
        }
        if (onPartnerStats != null) {
            HubRow(
                stringResource(R.string.settingsMenu_partnerStats),
                Icons.Filled.BarChart,
                onPartnerStats,
            )
        }
        if (onFeedback != null) {
            HubRow(
                stringResource(R.string.settingsMenu_feedback),
                Icons.Filled.BugReport,
                onFeedback,
            )
        }
        if (onDeleteAccount != null) {
            HubRow(
                stringResource(R.string.settingsMenu_deleteAccount),
                Icons.Filled.DeleteForever,
                onDeleteAccount,
            )
        }

        SettingsSectionHeader(stringResource(R.string.settingsMenu_communitySection))
        HubRow(
            stringResource(R.string.settingsMenu_instagram),
            Icons.Filled.Groups,
        ) { openExternalUrl(context, instagramUrl) }
        HubRow(
            stringResource(R.string.settingsMenu_reviewPlayStore),
            Icons.Filled.Stars,
        ) { openPlayStoreListing(context) }

        SettingsSectionHeader(stringResource(R.string.settingsMenu_legalSection))
        HubRow(
            stringResource(R.string.settingsMenu_privacy),
            Icons.Filled.Lock,
        ) { openExternalUrl(context, privacyUrl) }
        HubRow(
            stringResource(R.string.settingsMenu_terms),
            Icons.Filled.Description,
        ) { openExternalUrl(context, termsUrl) }

        Text(
            text = stringResource(R.string.settingsMenu_version, BuildConfig.VERSION_NAME),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = KccSpacing.s4),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    Text(
        text = title,
        modifier = Modifier.padding(start = KccSpacing.s2, top = KccSpacing.s2),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

/**
 * Opens an external web document via ACTION_VIEW, restricted to http(s) so a
 * misconfigured/empty URL resource can never launch a non-web intent. Silently
 * no-ops if there is no browser.
 */
private fun openExternalUrl(context: Context, url: String) {
    val uri = Uri.parse(url.trim())
    val scheme = uri.scheme?.lowercase()
    if (scheme != "http" && scheme != "https") return
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: ActivityNotFoundException) {
        // No browser available — nothing to open.
    }
}

/**
 * Opens the Play-store listing for this app: the Play-store app via a
 * `market://` intent, falling back to the https listing in a browser when the
 * Play-store app is unavailable.
 */
private fun openPlayStoreListing(context: Context) {
    val packageName = context.packageName
    val marketUri = Uri.parse("market://details?id=$packageName")
    try {
        context.startActivity(
            // Target the Play Store explicitly so the market:// intent can't be
            // hijacked by another app registering the scheme; the catch below
            // falls back to the https listing when Play Store is unavailable.
            Intent(Intent.ACTION_VIEW, marketUri)
                .setPackage("com.android.vending")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: ActivityNotFoundException) {
        openExternalUrl(
            context,
            "https://play.google.com/store/apps/details?id=$packageName",
        )
    }
}
