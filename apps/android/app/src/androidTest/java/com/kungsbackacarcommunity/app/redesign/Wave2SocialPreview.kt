package com.kungsbackacarcommunity.app.redesign

import androidx.compose.runtime.Composable
import com.kungsbackacarcommunity.app.crownhunt.CrownHuntScreen
import com.kungsbackacarcommunity.app.crownhunt.CrownLeaderboardRow
import com.kungsbackacarcommunity.app.crownhunt.CrownPersonalStats
import com.kungsbackacarcommunity.app.crownhunt.CrownSeasonBoard
import com.kungsbackacarcommunity.app.crownhunt.CrownStatsUiState
import com.kungsbackacarcommunity.app.events.EventDetail
import com.kungsbackacarcommunity.app.events.EventDetailScreen
import com.kungsbackacarcommunity.app.events.EventStatus
import com.kungsbackacarcommunity.app.events.EventSummary
import com.kungsbackacarcommunity.app.events.EventsListScreen
import com.kungsbackacarcommunity.app.events.EventsListState
import com.kungsbackacarcommunity.app.events.RsvpCounts
import com.kungsbackacarcommunity.app.events.RsvpStatus
import com.kungsbackacarcommunity.app.events.RsvpStatusUi
import java.time.Instant

/**
 * Screenshot entry point for MAIN's themed emulator harness. All fixture data
 * lives in androidTest; these are real production screens with inert callbacks.
 * No repositories, Firebase, map, location, images or network are instantiated.
 * MAIN supplies the visible test-data caption and KccTheme.
 *
 * cases: events -> S41, crown -> S17/S21/S22; event-detail -> S42 (free viewer).
 */
@Composable
fun Wave2SocialPreview(screen: String) {
    when (screen) {
        "events" -> EventsListScreen(
            state = EventsListState.Loaded(
                listOf(
                    previewEvent(),
                    previewEvent().copy(
                        id = "local-event-2",
                        title = "Testevent · Söndagsträff",
                        startsAtMillis = Instant.parse("2026-11-01T12:00:00Z").toEpochMilli(),
                        counts = RsvpCounts(8, 2, 0),
                        isOfficial = false,
                    ),
                ),
            ),
            onOpenEvent = {},
            onSelectTab = {},
            onCreateEvent = {},
        )
        "crown" -> CrownHuntScreen(
            statsState = CrownStatsUiState.Loaded(
                personal = CrownPersonalStats(
                    points = 1250,
                    crownsCollected = 12,
                    seasonRank = 2,
                    seasonPoints = 250,
                    seasonCrowns = 4,
                    byRarity = emptyMap(),
                    streakCurrent = 3,
                    streakBest = 5,
                    seasonsWon = 0,
                    rarest = null,
                ),
                board = CrownSeasonBoard(
                    seasonId = "2026-10",
                    rows = listOf(
                        CrownLeaderboardRow(1, "local-one", "Testförare 1", 500, 6, false),
                        CrownLeaderboardRow(2, "local-viewer", "Testförare 2", 250, 4, true),
                    ),
                    viewerRank = 2,
                ),
            ),
            passesMemberGate = true,
            onBack = {},
        )
        "event-detail" -> EventDetailScreen(
            event = previewEvent(),
            detail = EventDetail("Lokal testdata för förhandsvisning av eventdetaljen.", "Testplats"),
            myRsvp = RsvpStatus.GOING,
            passesMemberGate = true,
            canViewAttendees = false,
            rsvpStatus = RsvpStatusUi.Idle,
            onRsvp = {},
            onBack = {},
        )
        else -> error("Unknown social preview case: $screen")
    }
}

private fun previewEvent() = EventSummary(
    id = "local-event-1",
    title = "Testevent · Kvällsträff",
    summary = "Lokal testdata för Android-förhandsvisning.",
    startsAtMillis = Instant.parse("2026-10-31T17:00:00Z").toEpochMilli(),
    endsAtMillis = Instant.parse("2026-10-31T20:00:00Z").toEpochMilli(),
    approximateArea = "Kungsbacka",
    locationName = "Testplats",
    isOfficial = true,
    status = EventStatus.PUBLISHED,
    counts = RsvpCounts(12, 3, 1),
)
