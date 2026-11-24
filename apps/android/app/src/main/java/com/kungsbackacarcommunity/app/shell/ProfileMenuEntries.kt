package com.kungsbackacarcommunity.app.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.BusinessCenter
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.kungsbackacarcommunity.app.partners.FirebasePartnerPortalRepository
import kotlinx.coroutines.CancellationException
import androidx.compose.ui.res.stringResource
import com.kungsbackacarcommunity.app.R

/** Deliberate account order, independent of translated labels. Unavailable rows stay hidden. */
@Composable
internal fun profileMenuEntries(
    profileAvailable: Boolean,
    friendsAvailable: Boolean,
    partnerApplicationAvailable: Boolean,
    reservationsAvailable: Boolean,
    notificationsUnread: Boolean,
    onOpenRoute: (ShellRoute) -> Unit,
    onSignOut: () -> Unit,
    partnerLabel: Int = R.string.shell_morePartnerApplication,
): List<HubEntry> = listOf(
    HubEntry(stringResource(R.string.shell_moreProfile), Icons.Filled.Person,
        if (profileAvailable) ({ onOpenRoute(ShellRoute.Profile) }) else null, id = ShellRoute.Profile.name),
    HubEntry(stringResource(R.string.shell_friendsTitle), Icons.Filled.Groups,
        if (friendsAvailable) ({ onOpenRoute(ShellRoute.Friends) }) else null, id = ShellRoute.Friends.name),
    HubEntry(stringResource(R.string.shell_moreDrives), Icons.Filled.History,
        { onOpenRoute(ShellRoute.MyDrives) }, id = ShellRoute.MyDrives.name),
    HubEntry(stringResource(partnerLabel), Icons.Filled.BusinessCenter,
        if (partnerApplicationAvailable) ({ onOpenRoute(ShellRoute.PartnerApplication) }) else null,
        id = ShellRoute.PartnerApplication.name),
    HubEntry(stringResource(R.string.partnerPortal_reservations), Icons.Filled.QrCode,
        if (reservationsAvailable) ({ onOpenRoute(ShellRoute.PartnerReservations) }) else null,
        id = ShellRoute.PartnerReservations.name),
    HubEntry(stringResource(R.string.shell_moreSettings), Icons.Filled.Settings,
        { onOpenRoute(ShellRoute.Settings) }, id = ShellRoute.Settings.name, hasUnread = notificationsUnread),
    HubEntry(stringResource(R.string.shell_moreSignOut), Icons.AutoMirrored.Filled.Logout,
        onSignOut, id = "sign_out"),
)

/** Keep the existing place picker accessible without preceding Profile in the menu. */
internal fun withSavedPlacesEntry(entries: List<HubEntry>, savedPlaces: HubEntry): List<HubEntry> {
    val drivesIndex = entries.indexOfFirst { it.id == ShellRoute.MyDrives.name }
    val insertionIndex = if (drivesIndex >= 0) drivesIndex + 1 else 0
    return entries.take(insertionIndex) + savedPlaces + entries.drop(insertionIndex)
}

@Composable
internal fun partnerMenuLabel(uid: String): Int {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var label by remember(uid) { mutableStateOf(R.string.shell_morePartnerApplication) }
    LaunchedEffect(context, uid, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            val workspace = try {
                FirebasePartnerPortalRepository.createIfAvailable(context)?.workspace()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            label = when {
                workspace?.activeCompanies?.isNotEmpty() == true -> R.string.partnerPortal_manage
                workspace?.application != null -> R.string.partnerPortal_application
                else -> R.string.shell_morePartnerApplication
            }
        }
    }
    return label
}
