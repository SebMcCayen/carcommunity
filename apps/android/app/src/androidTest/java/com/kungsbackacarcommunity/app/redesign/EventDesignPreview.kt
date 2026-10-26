package com.kungsbackacarcommunity.app.redesign

import androidx.compose.runtime.Composable
import com.kungsbackacarcommunity.app.chat.ChatMessage
import com.kungsbackacarcommunity.app.chat.ChatMessagesState
import com.kungsbackacarcommunity.app.chat.ChatReportStatus
import com.kungsbackacarcommunity.app.chat.ChatSendStatus
import com.kungsbackacarcommunity.app.chat.EventChatScreen
import com.kungsbackacarcommunity.app.events.CheckInUiState
import com.kungsbackacarcommunity.app.events.CreateEventInput
import com.kungsbackacarcommunity.app.events.CreateEventScreen
import com.kungsbackacarcommunity.app.events.EventAttendanceStatus
import com.kungsbackacarcommunity.app.events.EventDetail
import com.kungsbackacarcommunity.app.events.EventDetailScreen
import com.kungsbackacarcommunity.app.events.EventStatus
import com.kungsbackacarcommunity.app.events.EventSummary
import com.kungsbackacarcommunity.app.events.EventShareSheet
import com.kungsbackacarcommunity.app.events.EventsListScreen
import com.kungsbackacarcommunity.app.events.EventsListState
import com.kungsbackacarcommunity.app.events.RsvpCounts
import com.kungsbackacarcommunity.app.events.RsvpStatus
import com.kungsbackacarcommunity.app.events.RsvpStatusUi
import com.kungsbackacarcommunity.app.groupdrive.GroupDriveActionStatus
import com.kungsbackacarcommunity.app.groupdrive.GroupDriveParticipant
import com.kungsbackacarcommunity.app.groupdrive.GroupDriveScreen
import com.kungsbackacarcommunity.app.groupdrive.GroupDriveStatus
import com.kungsbackacarcommunity.app.friends.FriendSummary
import com.kungsbackacarcommunity.app.friends.FriendsRepository
import com.kungsbackacarcommunity.app.friends.FriendsResult
import com.kungsbackacarcommunity.app.friends.FriendsData
import com.kungsbackacarcommunity.app.dm.DmRepository
import com.kungsbackacarcommunity.app.dm.DmConversationsState
import com.kungsbackacarcommunity.app.dm.DmThreadState
import com.kungsbackacarcommunity.app.dm.DmOlderResult
import com.kungsbackacarcommunity.app.dm.DmSendResult
import kotlinx.coroutines.flow.emptyFlow
import java.time.Instant

/**
 * Test-only native views for main's capture harness. Wrap in KccTheme and the
 * existing visible local-test-data caption. No runtime sample data, image URLs,
 * live repositories, location or map instances are supplied by this fixture.
 *
 * Cases: events (S41), event-detail[-paid/-creator] (S42), event-create/edit/saving
 * (S43), event-chat[-restricted] (S44), event-check-in (S45), event-group-drive
 * (S46), event-share (S47). Main owns capturing viewport/theme/locale/font-scale variants.
 */
@Composable
fun EventDesignPreview(screen: String) {
    val event = eventDesignFixture()
    when (screen) {
        "events" -> EventsListScreen(
            state = EventsListState.Loaded(listOf(
                event,
                event.copy(id = "local-event-2", title = "Testevent · Söndagsträff",
                    startsAtMillis = Instant.parse("2026-11-01T12:00:00Z").toEpochMilli(),
                    counts = RsvpCounts(8, 2, 0), isOfficial = false),
                event.copy(id = "local-event-3", title = "Testevent med en längre titel för radbrytning",
                    startsAtMillis = Instant.parse("2026-11-22T15:00:00Z").toEpochMilli(),
                    counts = RsvpCounts.EMPTY, locationName = null, approximateArea = null),
            )),
            onOpenEvent = {}, onSelectTab = {}, onCreateEvent = {},
        )
        "event-detail", "event-detail-paid", "event-detail-creator", "event-check-in" -> EventDetailScreen(
            event = event,
            detail = EventDetail(
                "Lokal testdata: en längre beskrivning av träffen. Här finns information från arrangören, " +
                    "praktiska detaljer och plats för radbrytning vid större textstorlek.\n\n" +
                    "Den fullständiga beskrivningen och adressen visas även för Community.",
                "Testadress · endast lokal förhandsvisning",
            ),
            myRsvp = RsvpStatus.GOING, passesMemberGate = true,
            canViewAttendees = screen != "event-detail", rsvpStatus = RsvpStatusUi.Idle,
            onRsvp = {}, onBack = {}, onOpenChat = {}, onOpenGroupDrive = {},
            onShareEvent = {}, onAddToCalendar = {}, organizerName = "Testarrangör",
            onUpgrade = {}, onRevealAttendees = {},
            canManage = screen == "event-detail-creator", onEdit = {}, onRemove = {},
            checkInAvailable = screen == "event-check-in",
            checkInState = CheckInUiState.Idle,
            attendance = if (screen == "event-check-in") EventAttendanceStatus(verified = true, sampleCount = 2) else null,
            onCheckIn = if (screen == "event-check-in") ({}) else null,
        )
        "event-create", "event-edit", "event-saving" -> CreateEventScreen(
            saving = screen == "event-saving", errorMessage = null, onSubmit = {}, onCancel = {},
            isEdit = screen == "event-edit",
            initialInput = CreateEventInput(
                title = event.title, startsAtMillis = checkNotNull(event.startsAtMillis),
                description = "Lokal testdata: en träff för communityt. Textfältet visar det riktiga " +
                    "formuläret med backendens teckengränser och valfri beskrivning.",
                address = "Testadress", latitude = 57.48, longitude = 12.07,
            ),
        )
        "event-chat", "event-chat-restricted" -> EventChatScreen(
            state = ChatMessagesState.Loaded(listOf(
                eventChatFixture("local-message-1", "local-author-1", "Testförare 1", "Lokal testtext: Vi ses på träffen."),
                eventChatFixture("local-message-2", "local-author-2", "Testförare med ett längre namn", "Var finns praktisk information om samlingsplatsen?"),
                eventChatFixture("local-message-3", "local-viewer", "Testförare 3", "Informationen finns i eventets fullständiga beskrivning."),
                eventChatFixture("local-message-4", "local-author-1", "Testförare 1", "Lokal testtext med en länk: https://example.com"),
            )),
            currentUid = "local-viewer", canParticipate = screen != "event-chat-restricted",
            sendStatus = ChatSendStatus.Idle, reportStatus = ChatReportStatus.Idle,
            onSend = {}, onReport = { _, _ -> }, onReportDismiss = {}, onBack = {},
            onViewProfile = {}, canBlock = true, eventTitle = event.title,
        )
        "event-group-drive" -> GroupDriveScreen(
            event = event,
            participants = listOf(
                GroupDriveParticipant("local-author-1", "Testförare 1", GroupDriveStatus.ARRIVED),
                GroupDriveParticipant("local-author-2", "Testförare med ett längre namn", GroupDriveStatus.ON_THE_WAY),
                GroupDriveParticipant("local-viewer", "Testförare 3", GroupDriveStatus.JOINED),
            ),
            myStatus = GroupDriveStatus.JOINED, canJoin = true,
            actionStatus = GroupDriveActionStatus.Idle, onJoin = {}, onSetStatus = {},
            onLeave = {}, onShowOnMap = {}, onBack = {},
        )
        "event-share" -> EventShareSheet(
            eventId = event.id, eventTitle = event.title, event = event,
            friendsRepository = EventPreviewFriends, dmRepository = EventPreviewDm,
            onShared = {}, onSendFailed = {}, onDismiss = {},
        )
        else -> error("Unknown event design fixture: $screen")
    }
}

private fun eventDesignFixture() = EventSummary(
    id = "local-event-1", title = "Testevent · Kvällsträff",
    summary = "Lokal testdata för eventets korta introduktion.",
    startsAtMillis = Instant.parse("2026-10-31T17:00:00Z").toEpochMilli(),
    endsAtMillis = Instant.parse("2026-10-31T20:00:00Z").toEpochMilli(),
    approximateArea = null, locationName = "Testplats för communityt",
    isOfficial = true, status = EventStatus.PUBLISHED, counts = RsvpCounts(12, 3, 1),
)

private fun eventChatFixture(id: String, uid: String, name: String, body: String) = ChatMessage(
    id = id, authorUserId = uid, authorDisplayName = name, message = body,
    isRemoved = false, isAutoHidden = false,
    createdAtMillis = Instant.parse("2026-10-31T14:28:00Z").toEpochMilli(),
)

/** Local-only seams for rendering the production share sheet. */
private object EventPreviewFriends : FriendsRepository {
    override suspend fun list() = FriendsResult.Loaded(FriendsData(
        List(12) { FriendSummary("local-friend-$it", "Testvän ${it + 1}", null, null) }, emptyList(), emptyList(),
    ))
    override suspend fun sendRequestByNickname(nickname: String): Nothing = error("Preview has no friend writes")
    override suspend fun sendRequestToUid(toUid: String): Nothing = error("Preview has no friend writes")
    override suspend fun respond(requestId: String, accept: Boolean): Nothing = error("Preview has no friend writes")
    override suspend fun cancelRequest(toUid: String): Nothing = error("Preview has no friend writes")
    override suspend fun remove(friendUid: String): Nothing = error("Preview has no friend writes")
}

private object EventPreviewDm : DmRepository {
    override fun observeConversations(uid: String) = emptyFlow<DmConversationsState>()
    override fun observeThread(conversationId: String) = emptyFlow<DmThreadState>()
    override suspend fun sendMessage(toUid: String, text: String, clientId: String?) =
        DmSendResult.Sent("local-conversation", "local-message")
    override suspend fun loadOlder(conversationId: String, before: String) = DmOlderResult.Failed
    override suspend fun markRead(conversationId: String) = Unit
}
