package com.kungsbackacarcommunity.app.shell

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CardMembership
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.NewReleases
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.BuildConfig
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.design.LocalThemeController
import com.kungsbackacarcommunity.app.design.ThemePreference
import com.kungsbackacarcommunity.app.navigation.openDefaultMapAppSettings

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
    onSavedPlaces: () -> Unit,
    onNotificationSettings: (() -> Unit)?,
    onBlockedUsers: (() -> Unit)?,
    onPartnerStats: (() -> Unit)?,
    onFeedback: (() -> Unit)?,
    onDeleteAccount: (() -> Unit)?,
    onWhatsNew: () -> Unit,
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
        // Always available: saved places are device-local (no backend dependency
        // to guard on), so unlike the rows around it this needs no null check.
        HubRow(
            stringResource(R.string.settingsMenu_savedPlaces),
            Icons.Filled.Bookmark,
            onSavedPlaces,
        )
        // Always available (opens a system screen, no backend dependency): make
        // KCC the app Android offers for map links. Honest label — it opens the
        // system "open by default" screen where the member picks KCC; there is
        // no OS default-navigator role to set, and no API to set one.
        HubRow(
            stringResource(R.string.settingsMenu_defaultMapApp),
            Icons.Filled.Map,
        ) { openDefaultMapAppSettings(context) }
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

        // Appearance: Automatic / Light / Dark. Automatic is the default and
        // reproduces the original behaviour (follow the device). Light and Dark
        // are sticky — the app stops reacting to the system flipping (scheduled
        // sunset->sunrise, battery saver), which is the point of the setting.
        // Applies immediately: the store's StateFlow re-themes the running app,
        // so this screen restyles under the user's finger with no restart.
        SettingsSectionHeader(stringResource(R.string.settingsMenu_appearanceSection))
        val themeController = LocalThemeController.current
        ThemeOptionRow(
            label = stringResource(R.string.settingsMenu_themeAutomatic),
            description = stringResource(R.string.settingsMenu_themeAutomaticDescription),
            icon = Icons.Filled.BrightnessAuto,
            selected = themeController.preference == ThemePreference.SYSTEM,
            testTag = SETTINGS_THEME_AUTOMATIC_TAG,
            onClick = { themeController.setPreference(ThemePreference.SYSTEM) },
        )
        ThemeOptionRow(
            label = stringResource(R.string.settingsMenu_themeLight),
            description = stringResource(R.string.settingsMenu_themeLightDescription),
            icon = Icons.Filled.LightMode,
            selected = themeController.preference == ThemePreference.LIGHT,
            testTag = SETTINGS_THEME_LIGHT_TAG,
            onClick = { themeController.setPreference(ThemePreference.LIGHT) },
        )
        ThemeOptionRow(
            label = stringResource(R.string.settingsMenu_themeDark),
            description = stringResource(R.string.settingsMenu_themeDarkDescription),
            icon = Icons.Filled.DarkMode,
            selected = themeController.preference == ThemePreference.DARK,
            testTag = SETTINGS_THEME_DARK_TAG,
            onClick = { themeController.setPreference(ThemePreference.DARK) },
        )

        SettingsSectionHeader(stringResource(R.string.settingsMenu_communitySection))
        HubRow(
            stringResource(R.string.settingsMenu_instagram),
            Icons.Filled.Groups,
        ) { openExternalUrl(context, instagramUrl) }
        HubRow(
            stringResource(R.string.settingsMenu_reviewPlayStore),
            Icons.Filled.Stars,
        ) { openPlayStoreListing(context) }

        // About the app: the bundled "Vad är nytt" changelog. Always available —
        // the data ships with the APK, so unlike the rows above this needs no
        // `if (callback != null)` availability guard.
        SettingsSectionHeader(stringResource(R.string.settingsMenu_aboutSection))
        HubRow(
            stringResource(R.string.settingsMenu_whatsNew),
            Icons.Filled.NewReleases,
            onWhatsNew,
        )

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

/** Test tags for the three Appearance options, so UI tests can drive them. */
const val SETTINGS_THEME_AUTOMATIC_TAG = "settings_theme_automatic"
const val SETTINGS_THEME_LIGHT_TAG = "settings_theme_light"
const val SETTINGS_THEME_DARK_TAG = "settings_theme_dark"

/**
 * One Appearance choice: icon, label + explanatory line, and a trailing radio
 * showing which is active.
 *
 * Mirrors [HubRow]'s Surface/Row shape so the section sits visually with the
 * rest of Settings, but carries `selectableGroup`-style semantics
 * ([Role.RadioButton] + `selected`) instead of a button's: these three rows are
 * a single-choice set, and TalkBack should announce them as such. The radio is
 * non-clickable — the whole row is the target, so the click is declared once on
 * the Surface and the radio is decoration.
 */
@Composable
private fun ThemeOptionRow(
    label: String,
    description: String,
    icon: ImageVector,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag)
            .semantics { role = Role.RadioButton },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        selected = selected,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(KccSpacing.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KccSpacing.s4),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(KccSpacing.s6),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RadioButton(
                selected = selected,
                // The row owns the click and already announces its selected
                // state; a separately focusable control here would duplicate
                // the target for touch and TalkBack.
                onClick = null,
            )
        }
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
