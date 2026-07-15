package com.kungsbackacarcommunity.app.chatchannels

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.Badge
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.dm.ChatRoute
import com.kungsbackacarcommunity.app.dm.ConversationListRoute
import com.kungsbackacarcommunity.app.dm.DmRepository
import com.kungsbackacarcommunity.app.notifications.NotificationsCoordinator
import com.kungsbackacarcommunity.app.notifications.NotificationsRepository
import com.kungsbackacarcommunity.app.notifications.NotificationsRoute

/** Test tag on the chat-hub root, so UI tests can assert it renders. */
const val CHAT_HUB_TEST_TAG = "chat_hub"

/**
 * Surface alpha for the chat hub when shown as a transparent popup over the map —
 * matches the map-home popups' `POPUP_SURFACE_ALPHA` (0.92f) so the live map
 * shows faintly through the card, the same translucent idiom as the map-layers
 * and live-share popups.
 */
const val CHAT_HUB_POPUP_ALPHA = 0.92f

/** The four sections of the chat hub. */
enum class ChatTab { Community, Convoys, Friends, Notifications }

/**
 * The 3-channel chat hub as a full-screen opaque route (legacy presentation, kept
 * for [com.kungsbackacarcommunity.app.shell.ShellRoute.ChatHub]). The map-home
 * chat bubble now opens [ChatHubPopup] instead, so the hub floats as a
 * translucent popup over the map; this route remains as a migration-safe fallback
 * and shares the same [ChatHubContent] body.
 */
@Composable
fun ChatHubRoute(
    uid: String,
    communityChatRepository: CommunityChatRepository?,
    convoyChatRepository: ConvoyChatRepository?,
    dmRepository: DmRepository?,
    notificationsRepository: NotificationsRepository?,
    notificationsCoordinator: NotificationsCoordinator?,
    communityUnread: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize().testTag(CHAT_HUB_TEST_TAG),
        color = MaterialTheme.colorScheme.background,
    ) {
        ChatHubContent(
            uid = uid,
            communityChatRepository = communityChatRepository,
            convoyChatRepository = convoyChatRepository,
            dmRepository = dmRepository,
            notificationsRepository = notificationsRepository,
            notificationsCoordinator = notificationsCoordinator,
            communityUnread = communityUnread,
            onClose = onClose,
            applyStatusBarInset = true,
        )
    }
}

/**
 * The 3-channel chat hub rendered as a TRANSPARENT popup *over* the map — the same
 * idiom as the map-layers and live-share popups: a [Popup] (not a full route or a
 * [androidx.compose.ui.window.Dialog]) so there is NO dimming scrim and the live
 * map stays visible behind (and faintly through) a translucent surface. Tapping
 * outside the card or pressing Back dismisses it (focusable popup + the content's
 * own BackHandler). The card leaves a strip of map visible above it and reaches
 * the bottom of the window so the message-input row's navigation-bar inset lifts
 * it clear of the system bars. Content/tabs/functionality are identical to the
 * route form ([ChatHubContent]); only the container/presentation differs.
 */
@Composable
fun ChatHubPopup(
    uid: String,
    communityChatRepository: CommunityChatRepository?,
    convoyChatRepository: ConvoyChatRepository?,
    dmRepository: DmRepository?,
    notificationsRepository: NotificationsRepository?,
    notificationsCoordinator: NotificationsCoordinator?,
    communityUnread: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Popup(
        alignment = Alignment.BottomCenter,
        onDismissRequest = onClose,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            modifier =
                modifier
                    .fillMaxSize()
                    // Push the card below the status bar and reveal a strip of the
                    // live map above it, so the popup reads as floating over the map
                    // rather than a full page.
                    .statusBarsPadding()
                    .padding(top = KccSpacing.s6, start = KccSpacing.s2, end = KccSpacing.s2)
                    .testTag(CHAT_HUB_TEST_TAG),
            shape = RoundedCornerShape(topStart = KccRadius.lg, topEnd = KccRadius.lg),
            // Translucent so the map shows through — matches the map-home popups.
            color = MaterialTheme.colorScheme.surface.copy(alpha = CHAT_HUB_POPUP_ALPHA),
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
        ) {
            ChatHubContent(
                uid = uid,
                communityChatRepository = communityChatRepository,
                convoyChatRepository = convoyChatRepository,
                dmRepository = dmRepository,
                notificationsRepository = notificationsRepository,
                notificationsCoordinator = notificationsCoordinator,
                communityUnread = communityUnread,
                onClose = onClose,
                // The card is already offset below the status bar, so the content
                // must NOT add its own status-bar inset again.
                applyStatusBarInset = false,
            )
        }
    }
}

/**
 * Shared hub body: a top bar (title + close) and a tab row switching between the
 * COMMUNITY channel, the caller's CONVOY channels, FRIENDS (the existing 1:1 DMs),
 * and the in-app NOTIFICATIONS inbox. Rendered inside either the full-screen
 * [ChatHubRoute] surface or the translucent [ChatHubPopup].
 *
 * Friends and Convoys have a second level (a list → a thread/channel); that
 * nested navigation is local state here, and system Back pops it before closing
 * the whole hub. Each channel/inbox is wired to its own backend via the reused
 * routes ([ConversationListRoute]/[ChatRoute], [NotificationsRoute],
 * [CommunityChannelRoute], [ConvoyChannelRoute]). Any repository may be null in a
 * config-less build; that tab then renders an informational placeholder.
 *
 * @param applyStatusBarInset when true the body pads for the status bar itself
 *   (full-screen route); the popup form passes false because its card is already
 *   offset below the status bar.
 */
@Composable
private fun ChatHubContent(
    uid: String,
    communityChatRepository: CommunityChatRepository?,
    convoyChatRepository: ConvoyChatRepository?,
    dmRepository: DmRepository?,
    notificationsRepository: NotificationsRepository?,
    notificationsCoordinator: NotificationsCoordinator?,
    // Hoisted from AuthenticatedApp, which already collects observeUnread(uid) to
    // drive the map chat-bubble dot. Passed in (not re-subscribed here) so a
    // single Firestore listener backs both the bubble and this tab's dot.
    communityUnread: Boolean,
    onClose: () -> Unit,
    applyStatusBarInset: Boolean,
) {
    var selectedTab by rememberSaveable { mutableStateOf(ChatTab.Community) }

    // Friends sub-nav: the open DM thread's target, or null to show the inbox.
    var dmOtherUid by rememberSaveable { mutableStateOf<String?>(null) }
    var dmOtherName by rememberSaveable { mutableStateOf<String?>(null) }
    // Convoys sub-nav: the open convoy, or null to show the convoy list.
    var openConvoyId by rememberSaveable { mutableStateOf<String?>(null) }
    var openConvoyTitle by rememberSaveable { mutableStateOf<String?>(null) }

    val inFriendThread = selectedTab == ChatTab.Friends && dmOtherUid != null
    val inConvoyChannel = selectedTab == ChatTab.Convoys && openConvoyId != null
    val inSubScreen = inFriendThread || inConvoyChannel

    val popSubScreen = {
        dmOtherUid = null
        dmOtherName = null
        openConvoyId = null
        openConvoyTitle = null
    }

    // System Back pops an open sub-screen first; otherwise closes the hub. This
    // nested handler composes deeper than the shell's route BackHandler, so it
    // wins while the hub is open.
    BackHandler(enabled = true) {
        if (inSubScreen) popSubScreen() else onClose()
    }

    val title =
        when {
            inConvoyChannel ->
                openConvoyTitle ?: stringResource(R.string.chatHub_convoyUntitled)
            inFriendThread -> dmOtherName ?: stringResource(R.string.dm_unknownMember)
            else -> stringResource(R.string.chatHub_title)
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .then(if (applyStatusBarInset) Modifier.statusBarsPadding() else Modifier),
    ) {
        // Everything below is identical for the route and popup forms; only the
        // surrounding container (opaque route surface vs translucent popup card)
        // differs.
        run {
            // Top bar: back (when in a sub-screen) or nothing, the title, close.
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = KccSpacing.s2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (inSubScreen) {
                    IconButton(onClick = popSubScreen) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.chatHub_back),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                Text(
                    text = title,
                    modifier = Modifier.weight(1f).padding(horizontal = KccSpacing.s2),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = stringResource(R.string.chatHub_close),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Hide the tab row while a sub-screen (thread/channel) is open, so the
            // thread gets the full height and Back is the way out of it.
            if (!inSubScreen) {
                TabRow(selectedTabIndex = selectedTab.ordinal) {
                    ChatTabItem(
                        tab = ChatTab.Community,
                        selected = selectedTab,
                        icon = Icons.AutoMirrored.Filled.Chat,
                        label = stringResource(R.string.chatHub_tabCommunity),
                        showDot = communityUnread,
                        onSelect = { selectedTab = it },
                    )
                    ChatTabItem(
                        tab = ChatTab.Convoys,
                        selected = selectedTab,
                        icon = Icons.Filled.Route,
                        label = stringResource(R.string.chatHub_tabConvoys),
                        onSelect = { selectedTab = it },
                    )
                    ChatTabItem(
                        tab = ChatTab.Friends,
                        selected = selectedTab,
                        icon = Icons.Filled.Person,
                        label = stringResource(R.string.chatHub_tabFriends),
                        onSelect = { selectedTab = it },
                    )
                    ChatTabItem(
                        tab = ChatTab.Notifications,
                        selected = selectedTab,
                        icon = Icons.Filled.Notifications,
                        label = stringResource(R.string.chatHub_tabNotifications),
                        onSelect = { selectedTab = it },
                    )
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (selectedTab) {
                    ChatTab.Community ->
                        if (communityChatRepository != null) {
                            CommunityChannelRoute(repository = communityChatRepository, uid = uid)
                        } else {
                            TabPlaceholder(stringResource(R.string.chatHub_unavailable))
                        }

                    ChatTab.Convoys ->
                        if (convoyChatRepository != null) {
                            if (openConvoyId != null) {
                                ConvoyChannelRoute(
                                    repository = convoyChatRepository,
                                    uid = uid,
                                    convoyId = openConvoyId!!,
                                )
                            } else {
                                ConvoyListRoute(
                                    repository = convoyChatRepository,
                                    onOpenConvoy = { convoy ->
                                        openConvoyId = convoy.convoyId
                                        openConvoyTitle = convoy.title
                                    },
                                )
                            }
                        } else {
                            TabPlaceholder(stringResource(R.string.chatHub_unavailable))
                        }

                    ChatTab.Friends ->
                        if (dmRepository != null) {
                            if (dmOtherUid != null) {
                                ChatRoute(
                                    repository = dmRepository,
                                    uid = uid,
                                    otherUid = dmOtherUid!!,
                                    otherName = dmOtherName,
                                )
                            } else {
                                ConversationListRoute(
                                    repository = dmRepository,
                                    uid = uid,
                                    onOpenConversation = { conversation ->
                                        // A malformed conversation can yield an empty
                                        // other uid; don't open a broken thread.
                                        if (conversation.otherUser.uid.isNotBlank()) {
                                            dmOtherUid = conversation.otherUser.uid
                                            dmOtherName = conversation.otherUser.displayName
                                        }
                                    },
                                )
                            }
                        } else {
                            TabPlaceholder(stringResource(R.string.chatHub_unavailable))
                        }

                    ChatTab.Notifications ->
                        if (notificationsRepository != null) {
                            NotificationsRoute(
                                repository = notificationsRepository,
                                coordinator = notificationsCoordinator,
                                uid = uid,
                                // The inbox has no Back of its own inside the hub;
                                // closing routes through the hub's close.
                                onBack = onClose,
                            )
                        } else {
                            TabPlaceholder(stringResource(R.string.chatHub_unavailable))
                        }
                }
            }
        }
    }
}

@Composable
private fun ChatTabItem(
    tab: ChatTab,
    selected: ChatTab,
    icon: ImageVector,
    label: String,
    onSelect: (ChatTab) -> Unit,
    showDot: Boolean = false,
) {
    Tab(
        selected = selected == tab,
        onClick = { onSelect(tab) },
        text = {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.labelMedium,
            )
        },
        icon = {
            if (showDot) {
                androidx.compose.material3.BadgedBox(badge = { Badge() }) {
                    Icon(imageVector = icon, contentDescription = null)
                }
            } else {
                Icon(imageVector = icon, contentDescription = null)
            }
        },
    )
}

@Composable
private fun TabPlaceholder(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(KccSpacing.s6), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
