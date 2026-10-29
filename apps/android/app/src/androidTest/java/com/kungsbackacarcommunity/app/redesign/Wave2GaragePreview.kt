package com.kungsbackacarcommunity.app.redesign

import androidx.compose.runtime.Composable
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.badges.Badge
import com.kungsbackacarcommunity.app.badges.BadgeCounters
import com.kungsbackacarcommunity.app.badges.BadgeShowcase
import com.kungsbackacarcommunity.app.garage.GarageScreen
import com.kungsbackacarcommunity.app.garage.GarageState
import com.kungsbackacarcommunity.app.garage.Vehicle
import com.kungsbackacarcommunity.app.garage.VehiclePowertrain
import com.kungsbackacarcommunity.app.garage.VehicleDetailScreen
import com.kungsbackacarcommunity.app.garage.VehicleForm
import com.kungsbackacarcommunity.app.garage.VehicleFormScreen
import com.kungsbackacarcommunity.app.garage.VehicleSaveStatus
import com.kungsbackacarcommunity.app.drives.DriveDeleteStatus
import com.kungsbackacarcommunity.app.drives.DriveStatsScreen
import com.kungsbackacarcommunity.app.drives.DriveStatsSnapshot
import com.kungsbackacarcommunity.app.drives.DriveStatsUiState
import com.kungsbackacarcommunity.app.drives.DriveSubscriptionTier
import com.kungsbackacarcommunity.app.drives.DrivesListScreen
import com.kungsbackacarcommunity.app.drives.DriveHistoryListState
import com.kungsbackacarcommunity.app.drives.RecordingState
import com.kungsbackacarcommunity.app.drives.SavedDrive
import com.kungsbackacarcommunity.app.drives.SavedDriveDetailScreen
import com.kungsbackacarcommunity.app.drives.SessionSummaryDialog
import com.kungsbackacarcommunity.app.profile.ProfileBadgesSection
import com.kungsbackacarcommunity.app.shell.AeroPage
import androidx.compose.ui.res.stringResource
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.profile.ProfileEditStatus
import com.kungsbackacarcommunity.app.profile.ProfileScreen
import com.kungsbackacarcommunity.app.profile.ProfileStatsSummary
import com.kungsbackacarcommunity.app.profile.UserProfile
import com.kungsbackacarcommunity.app.profile.ClubBadge
import com.kungsbackacarcommunity.app.memberprofile.MemberProfileScreen
import com.kungsbackacarcommunity.app.memberprofile.MemberProfileState
import com.kungsbackacarcommunity.app.memberprofile.MemberProfile
import com.kungsbackacarcommunity.app.memberprofile.MemberFriendState
import com.kungsbackacarcommunity.app.memberprofile.MemberBadges
import com.kungsbackacarcommunity.app.friends.FriendRelationship

/**
 * Main owns theme, visible fixture caption and capture harness. These cases use
 * production screens with local androidTest-only data. No repositories, real
 * photo paths, Firebase initialization, or production writes.
 * profileClubFixture=initials|unsafe-logo|long-name adds a public club to the
 * owner/member profile captures; absent or none retains the unaffiliated case.
 */
@Composable
fun Wave2GaragePreview(screen: String) {
    when (screen) {
        "member-profile" -> MemberProfileScreen(
            state = MemberProfileState.Loaded(
                profile = MemberProfile(
                    uid = "local-member", displayName = "Lokal medlemsprofil",
                    bio = "Lokal testprofil för granskning av relationer, garage och utmärkelser.",
                    clubBadge = pass3ClubBadge(),
                ),
                vehicles = listOf(pass3Vehicle(), pass3Vehicle().copy(id = "local-second-car", isMainCar = false)),
                badges = MemberBadges.Available(listOf(Badge("garage_created", null, 1_700_000_000_000L))),
                pointsBalance = 120L,
            ),
            onRetry = {}, onBlock = {}, onUnfriend = {}, onMessage = {},
            friendState = MemberFriendState(relationship = FriendRelationship.Friends),
        )
        "vehicle-detail" -> VehicleDetailScreen(
            vehicle = pass3Vehicle(), onEdit = {}, onDelete = {}, onSetMain = {}, onAddPhoto = {},
        )
        "vehicle-editor" -> VehicleFormScreen(
            initial = VehicleForm(
                makeId = "volvo", modelId = "240", modelYear = 1988,
                powertrain = VehiclePowertrain.PETROL, modifications = "Lokal testanteckning",
            ),
            isEdit = true, saveStatus = VehicleSaveStatus.Idle, currentYear = 2026,
            onSave = {}, onCancel = {}, onChangePhoto = {},
        )
        "awards" -> AeroPage(title = stringResource(R.string.badgeShowcase_title)) {
            ProfileBadgesSection(
                BadgeShowcase.from(
                    badges = listOf(Badge("garage_created", null, 1_700_000_000_000L)),
                    counters = BadgeCounters(vehiclesInGarage = 1),
                ),
            )
        }
        "drive-detail" -> SavedDriveDetailScreen(
            drive = pass3Drive(), deleteStatus = DriveDeleteStatus.Idle, onDelete = {}, onBack = {},
        )
        "drive-history" -> DrivesListScreen(
            state = DriveHistoryListState.Loaded(
                drives = listOf(pass3Drive()), tier = DriveSubscriptionTier.COMMUNITY,
                hiddenDriveCount = 2, hasMore = false,
            ),
            onSelect = {}, onDelete = {}, deleteStatus = DriveDeleteStatus.Idle, onShowStats = {},
        )
        "drive-stats" -> DriveStatsScreen(
            state = DriveStatsUiState.Loaded(
                DriveStatsSnapshot(
                    tier = DriveSubscriptionTier.COMMUNITY, totalDrives = 8,
                    totalDistanceMeters = 80_000.0, totalDurationSeconds = 7_200L,
                    longestDriveMeters = 20_000.0, averageDriveMeters = 10_000.0,
                    fastestAverageSpeedMps = null, highestMaxSpeedMps = null,
                    thisMonthDrives = 2, thisMonthDistanceMeters = 24_000.0,
                ),
            ),
        )
        "drive-save-failed" -> SessionSummaryDialog(
            state = RecordingState.Failed(pointCount = 0, elapsedMillis = 60_000L, code = "UNAVAILABLE"),
            pointsProvider = { emptyList() }, onRetry = {}, onDiscard = {},
        )
        "garage" -> GarageScreen(
            state = GarageState.Loaded(
                listOf(
                    Vehicle(
                        id = "local-main-car", make = "Volvo", model = "V60",
                        makeId = "volvo", modelId = "v60", modelYear = 2020,
                        powertrain = VehiclePowertrain.HYBRID, engineDescription = null,
                        isMainCar = true,
                    ),
                    Vehicle(
                        id = "local-second-car", make = "Saab", model = "900",
                        makeId = "saab", modelId = "900", modelYear = 1990,
                        powertrain = VehiclePowertrain.PETROL, engineDescription = null,
                    ),
                ),
            ),
            vehicleLimit = 5,
            onAdd = {}, onEdit = {}, onDelete = {}, onOpen = {}, onSetMain = { _, _ -> },
        )
        "profile" -> ProfileScreen(
            profile = UserProfile(
                displayName = "Android testprofil",
                bio = "Lokal profil för granskning av layout och tillgänglighet.",
                onboardingComplete = true,
                clubBadge = pass3ClubBadge(),
            ),
            saveStatus = ProfileEditStatus.Idle,
            onSave = { _, _, _ -> }, onBack = {}, onSignOut = {},
            onOpenPoints = {}, onOpenDrives = {},
            pointsBalance = 120L,
            statsSummary = ProfileStatsSummary(
                totalDrives = 4, totalDistanceMeters = 40_000.0,
                totalDurationSeconds = 7_200L, highestMaxSpeedMps = null,
                badgeCount = 1, pointsBalance = 120L, memberSinceMillis = 1_700_000_000_000L,
            ),
            badgeShowcase = BadgeShowcase.from(
                badges = listOf(Badge("garage_created", "", 1_700_000_000_000L)),
                counters = BadgeCounters(
                    crownsCollected = 0, lifetimeDistanceMeters = 40_000,
                    verifiedEventsAttended = 0, bestDayStreak = 0,
                    convoysLed = 0, vehiclesInGarage = 2, seasonsWon = 0,
                ),
            ),
        )
        else -> error("Unknown garage preview case: $screen")
    }
}

private fun pass3ClubBadge(): ClubBadge? {
    val fixture = InstrumentationRegistry.getArguments().getString("profileClubFixture") ?: "none"
    if (fixture == "none") return null
    require(fixture in setOf("initials", "unsafe-logo", "long-name")) { "Unknown profile club fixture: $fixture" }
    return requireNotNull(ClubBadge.fromField(mapOf(
        "clubId" to "local-club",
        "clubName" to if (fixture == "long-name") {
            "Kungsbacka Car Community och Västra Sveriges Bilentusiaster"
        } else "Lokala Bilklubben",
        // An unsafe URL exercises the parser fallback and never reaches Coil.
        "logoUrl" to if (fixture == "unsafe-logo") "https://unsafe.example.test/logo.webp" else null,
    )))
}

private fun pass3Vehicle() = Vehicle(
    id = "local-detail-car", make = "Volvo", model = "240",
    makeId = "volvo", modelId = "240", modelYear = 1988,
    powertrain = VehiclePowertrain.PETROL, engineDescription = "B230",
    modifications = "Lokal testanteckning", isMainCar = true,
)

private fun pass3Drive() = SavedDrive(
    rideId = "local-saved-drive", title = "Lokal testkörning", distanceMeters = 12_400.0,
    durationSeconds = 2_520L, averageSpeedMetersPerSecond = null,
    startedAtMillis = 1_700_000_000_000L, endedAtMillis = 1_700_002_520_000L,
    createdAtMillis = 1_700_002_520_000L,
)
