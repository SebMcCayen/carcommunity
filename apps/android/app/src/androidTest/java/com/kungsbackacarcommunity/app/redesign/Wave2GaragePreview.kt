package com.kungsbackacarcommunity.app.redesign

import androidx.compose.runtime.Composable
import com.kungsbackacarcommunity.app.badges.Badge
import com.kungsbackacarcommunity.app.badges.BadgeCounters
import com.kungsbackacarcommunity.app.badges.BadgeShowcase
import com.kungsbackacarcommunity.app.garage.GarageScreen
import com.kungsbackacarcommunity.app.garage.GarageState
import com.kungsbackacarcommunity.app.garage.Vehicle
import com.kungsbackacarcommunity.app.garage.VehiclePowertrain
import com.kungsbackacarcommunity.app.profile.ProfileEditStatus
import com.kungsbackacarcommunity.app.profile.ProfileScreen
import com.kungsbackacarcommunity.app.profile.ProfileStatsSummary
import com.kungsbackacarcommunity.app.profile.UserProfile

/**
 * Main owns theme, visible fixture caption and capture harness. These cases use
 * production screens with local androidTest-only data. No repositories, photo
 * paths, download URLs, Firebase initialization, or production writes.
 */
@Composable
fun Wave2GaragePreview(screen: String) {
    when (screen) {
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
