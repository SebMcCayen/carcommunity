package com.kungsbackacarcommunity.app.shell

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.drives.DriveDeleteStatus
import com.kungsbackacarcommunity.app.drives.DriveHistoryListState
import com.kungsbackacarcommunity.app.drives.DriveSubscriptionTier
import com.kungsbackacarcommunity.app.drives.DrivesListScreen
import com.kungsbackacarcommunity.app.drives.SavedDrive
import com.kungsbackacarcommunity.app.drives.SavedDriveDetailScreen
import com.kungsbackacarcommunity.app.drives.DRIVE_STATS_ENTRY_TAG
import com.kungsbackacarcommunity.app.drives.DRIVE_HISTORY_LIST_TAG
import com.kungsbackacarcommunity.app.friends.AddFriendState
import com.kungsbackacarcommunity.app.friends.FriendSummary
import com.kungsbackacarcommunity.app.friends.FriendsScreen
import com.kungsbackacarcommunity.app.friends.FriendsStatus
import com.kungsbackacarcommunity.app.garage.GarageScreen
import com.kungsbackacarcommunity.app.garage.GarageState
import com.kungsbackacarcommunity.app.garage.Vehicle
import com.kungsbackacarcommunity.app.garage.VehicleDetailScreen
import com.kungsbackacarcommunity.app.garage.VehiclePowertrain
import com.kungsbackacarcommunity.app.drives.DriveRouteGeometry
import com.kungsbackacarcommunity.app.map.DriveRouteMapRenderer
import com.kungsbackacarcommunity.app.notifications.AppNotification
import com.kungsbackacarcommunity.app.notifications.NotificationCategory
import com.kungsbackacarcommunity.app.notifications.NotificationsScreen
import com.kungsbackacarcommunity.app.notifications.NotificationsState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Same production-screen checks on JVM and native Android, with only local fixtures. */
@RunWith(AndroidJUnit4::class)
class ScopedOwnerShortcutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var backs = 0
    private val vehicle = Vehicle(
        id = "local-car", make = "Volvo", model = "240", modelYear = 1988,
        powertrain = VehiclePowertrain.PETROL, engineDescription = null,
    )
    private val drive = SavedDrive(
        rideId = "local-drive", title = "Local drive", distanceMeters = 1000.0,
        durationSeconds = 600L, averageSpeedMetersPerSecond = null,
        startedAtMillis = null, endedAtMillis = null, createdAtMillis = null,
    )
    private fun label(id: Int) = compose.activity.getString(id)

    private fun render(content: @Composable () -> Unit) {
        compose.setContent {
            KccTheme {
                BackHandler { backs++ }
                CompositionLocalProvider(
                    LocalAeroOwnerHeader provides AeroOwnerHeader(null, true, {}),
                    LocalAeroBackAvailable provides true,
                    content = content,
                )
            }
        }
    }

    private fun assertShortcutAbsentAndBackWorks() {
        compose.onNodeWithTag(AeroOwnerAvatarTag).assertDoesNotExist()
        compose.onNodeWithTag(AeroBackButtonTag).assertIsDisplayed().assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test fun vehicleInfoKeepsEditAndBackWithoutTheOwnerShortcut() {
        var edits = 0
        render {
            VehicleDetailScreen(vehicle, { edits++ }, {}, {})
        }
        assertShortcutAbsentAndBackWorks()
        compose.onNodeWithText(label(R.string.garage_editVehicle)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, edits) }
    }

    @Test fun friendsKeepMemberProfilesAndMessagingWithoutTheOwnerShortcut() {
        var profiles = 0
        var messages = 0
        render {
            FriendsScreen(
                status = FriendsStatus.Loaded(listOf(FriendSummary("friend", "Local Kim", null, null)), emptyList(), emptyList()),
                addState = AddFriendState.Idle, actionError = null, busyRows = emptySet(),
                onSend = {}, onChooseCandidate = {}, onDismissAdd = {},
                onAccept = {}, onDecline = {}, onCancel = {}, onRemove = {}, onClearActionError = {},
                onMessageFriend = { messages++ }, onViewProfile = { profiles++ },
            )
        }
        assertShortcutAbsentAndBackWorks()
        compose.onNodeWithTag("friends_list").performScrollToNode(hasText("Local Kim"))
        compose.onNodeWithText("Local Kim").performClick()
        compose.onNodeWithText(label(R.string.friends_message)).performClick()
        compose.runOnIdle { assertEquals(1, profiles); assertEquals(1, messages) }
    }

    @Test fun settingsKeepDestinationCallbacksAndBackWithoutTheOwnerShortcut() {
        var places = 0
        render {
            SettingsScreen(
                onManageSubscription = null, onSavedPlaces = { places++ },
                onNotificationSettings = null, onBlockedUsers = null, onPartnerStats = null,
                onFeedback = null, onDeleteAccount = null, onWhatsNew = {},
            )
        }
        assertShortcutAbsentAndBackWorks()
        compose.onNodeWithText(label(R.string.settingsMenu_savedPlaces)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, places) }
    }

    @Test fun notificationsKeepReadActionsAndBackWithoutTheOwnerShortcut() {
        var marked = 0
        render {
            NotificationsScreen(
                state = NotificationsState.Loaded(listOf(AppNotification(
                    id = "local-notification", category = NotificationCategory.EVENT_REMINDER,
                    title = "Local notification", previewText = null, body = null,
                    isRead = false, createdAtMillis = null,
                ))),
                onMarkRead = { marked++ }, onMarkAllRead = {}, onBack = {},
            )
        }
        assertShortcutAbsentAndBackWorks()
        compose.onNodeWithText("Local notification").performClick()
        compose.runOnIdle { assertEquals(1, marked) }
    }

    @Test fun myDrivesKeepSelectionStatsAndBackWithoutTheOwnerShortcut() {
        var selected: String? = null
        var stats = 0
        render {
            DrivesListScreen(
                state = DriveHistoryListState.Loaded(listOf(drive), DriveSubscriptionTier.COMMUNITY, 0, false),
                onSelect = { selected = it }, onDelete = {}, deleteStatus = DriveDeleteStatus.Idle,
                onShowStats = { stats++ },
            )
        }
        assertShortcutAbsentAndBackWorks()
        compose.onNodeWithTag(DRIVE_STATS_ENTRY_TAG).performClick()
        compose.onNodeWithTag(DRIVE_HISTORY_LIST_TAG).performScrollToNode(hasText("Local drive"))
        compose.onNodeWithText("Local drive").performClick()
        compose.runOnIdle { assertEquals(1, stats); assertEquals(drive.rideId, selected) }
    }

    @Test fun driveDetailsKeepDeleteConfirmationAndBackWithoutTheOwnerShortcut() {
        var deleted: String? = null
        render {
            SavedDriveDetailScreen(
                drive, DriveDeleteStatus.Idle, { deleted = it }, {},
                routeMapRenderer = NoNativeMapRenderer,
            )
        }
        assertShortcutAbsentAndBackWorks()
        compose.onNodeWithText(label(R.string.savedDrives_deleteAction)).performScrollTo().performClick()
        compose.runOnIdle { assertEquals(null, deleted) }
        compose.onNodeWithText(label(R.string.savedDrives_deleteConfirmAction)).performClick()
        compose.runOnIdle { assertEquals(drive.rideId, deleted) }
    }

    @Test fun returningFromGarageKeepsTheActualMapAvatarAndProfileMenu() {
        val showMap = mutableStateOf(false)
        val surface = StubMapSurface(MapLoadState.Loaded, autoLoad = false)
        render {
            if (showMap.value) {
                MapHome(
                    mapSurface = surface, isLiveSharing = false, participantCount = 0,
                    userLabel = "Local member", onSearch = {}, onOpenSavedPlaces = {},
                    moreMenuEntries = listOf(HubEntry("Local profile", Icons.Filled.Person, {})),
                )
            } else {
                GarageScreen(GarageState.Loaded(emptyList()), 2, {}, {}, {})
            }
        }
        compose.onNodeWithTag(AeroOwnerAvatarTag).assertDoesNotExist()
        compose.runOnIdle { showMap.value = true }
        compose.onNodeWithTag(MAP_HOME_MORE_TAG).assertIsDisplayed().assertHasClickAction().performClick()
        compose.onNodeWithTag(MAP_HOME_MORE_POPUP_TAG).assertIsDisplayed()
        compose.onNodeWithText("Local profile").assertIsDisplayed()
    }
}

/** Keep configured builds away from Mapbox JNI; this test concerns page chrome. */
private object NoNativeMapRenderer : DriveRouteMapRenderer {
    @Composable override fun isAvailable() = false
    @Composable override fun Preview(route: DriveRouteGeometry, modifier: Modifier) = Unit
    @Composable override fun Fullscreen(route: DriveRouteGeometry, onDismiss: () -> Unit) = Unit
}
