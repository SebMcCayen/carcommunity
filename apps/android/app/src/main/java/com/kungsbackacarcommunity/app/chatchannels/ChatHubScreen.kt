package com.kungsbackacarcommunity.app.chatchannels

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.blocking.BlockingRepository
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.design.KccTypeScale
import com.kungsbackacarcommunity.app.dm.ChatRoute
import com.kungsbackacarcommunity.app.dm.ConversationListRoute
import com.kungsbackacarcommunity.app.dm.DmRepository
import com.kungsbackacarcommunity.app.friends.FriendsRepository
import com.kungsbackacarcommunity.app.notifications.NotificationsCoordinator
import com.kungsbackacarcommunity.app.notifications.NotificationsRepository
import com.kungsbackacarcommunity.app.notifications.NotificationsRoute
import com.kungsbackacarcommunity.app.push.ActiveChat
import com.kungsbackacarcommunity.app.push.ActiveChatRegistry
import com.kungsbackacarcommunity.app.push.PushDeepLink
import com.kungsbackacarcommunity.app.push.PushTarget
import com.kungsbackacarcommunity.app.push.RequestPushPermissionEffect

/** Test tag on the chat-hub root, so UI tests can assert it renders. */
const val CHAT_HUB_TEST_TAG = "chat_hub"

/**
 * Surface alpha for the chat hub when shown as a transparent overlay over the map —
 * matches the map-home popups' `POPUP_SURFACE_ALPHA` (0.92f) so the live map
 * shows faintly through the card, the same translucent idiom as the map-layers
 * and live-share popups.
 */
private const val CHAT_HUB_POPUP_ALPHA = 0.92f

/**
 * Height of the chat-hub overlay card as a fraction of the SAFE area (the window
 * minus the status-bar inset), leaving the top ~8% of that area — plus the
 * status bar itself — as a genuinely uncovered strip of live map that also acts
 * as the tap-to-dismiss area. Deliberately a fraction rather than
 * `fillMaxHeight()` + top padding: the latter still measures to the full parent
 * height (the padding ends up inside the node's own footprint), which leaves no
 * real "outside". Applied inside a status-bar-inset box, so the card's top can
 * never fall under system UI however short the window is.
 */
private const val CHAT_HUB_CARD_HEIGHT_FRACTION = 0.92f

/** The four sections of the chat hub. */
enum class ChatTab { Community, Convoys, Friends, Notifications }

/**
 * The tab labels' font-size floor and ceiling.
 *
 * A [TabRow] splits the row into four EQUAL tabs, so each label gets a quarter of
 * the card's width — about 86dp on a 360dp phone, and the Material text+icon [Tab]
 * overload spent 16dp of that on padding (which [ChatTabItem] now reclaims). The
 * longest labels ("Notifications", "Community") then ellipsized to "Notificati…" /
 * "Commun…" once the user's font scale was raised. Rather than pick a smaller fixed
 * size (which only moves the cliff to a narrower screen, a longer translation or a
 * larger font scale), the label AUTO-SHRINKS within these bounds to whatever fits:
 * [TAB_LABEL_MAX_SP] is the normal `labelMedium` size, so labels are unaffected at
 * the default font scale — measured, every shipped label in both locales fits at
 * the full size on a 360dp phone — and only a label that would not otherwise fit
 * steps down, never below [TAB_LABEL_MIN_SP].
 *
 * The floor is deliberately below the type scale's smallest token. It is a
 * PRE-font-scale value, so it does not mean "8sp on screen": it only ever binds at
 * a raised accessibility font scale, where 8sp renders at least as large as the
 * 12sp a default-font-scale user already reads comfortably (8 × 1.5 = 12). At the
 * default scale nothing shrinks at all. Measured floor across en/sv × 320/360/411dp
 * × font scales 1.0/1.3/1.5: 8.5sp, reached only by "Notifications" on a 320dp
 * screen at font scale 1.5 — see `ChatHubTabLabelTest`, which pins that every
 * shipped label fits across that whole matrix.
 */
internal val TAB_LABEL_MIN_SP = 8.sp
internal val TAB_LABEL_MAX_SP = KccTypeScale.caption

/** Font-size granularity for the tab labels' auto-shrink. */
internal val TAB_LABEL_STEP_SP = 0.5.sp

/**
 * The chat hub as a full-screen opaque route (legacy presentation, kept
 * for [com.kungsbackacarcommunity.app.shell.ShellRoute.ChatHub]). The map-home
 * chat bubble now opens [ChatHubPopup] instead, so the hub floats as a
 * translucent overlay over the map; this route remains as a migration-safe fallback
 * and shares the same [ChatHubContent] body.
 */
@Composable
fun ChatHubRoute(
    uid: String,
    communityChatRepository: CommunityChatRepository?,
    convoyChatRepository: ConvoyChatRepository?,
    // Sources the community @-picker's roster (one friend-list call). Null in a
    // config-less build: the picker then offers only the members talking in the
    // channel.
    friendsRepository: FriendsRepository?,
    dmRepository: DmRepository?,
    notificationsRepository: NotificationsRepository?,
    notificationsCoordinator: NotificationsCoordinator?,
    communityUnread: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onViewProfile: ((String) -> Unit)? = null,
    blockingRepository: BlockingRepository? = null,
    // Set when the hub was opened by tapping a push, so it lands on the tab (and
    // convoy channel) the notification was about instead of the default
    // Community tab. Consumed once — backing out must not re-apply it.
    pushDeepLink: PushDeepLink? = null,
) {
    Surface(
        modifier = modifier.fillMaxSize().testTag(CHAT_HUB_TEST_TAG),
        color = MaterialTheme.colorScheme.background,
    ) {
        ChatHubContent(
            uid = uid,
            communityChatRepository = communityChatRepository,
            convoyChatRepository = convoyChatRepository,
            friendsRepository = friendsRepository,
            dmRepository = dmRepository,
            notificationsRepository = notificationsRepository,
            notificationsCoordinator = notificationsCoordinator,
            communityUnread = communityUnread,
            onClose = onClose,
            applyStatusBarInset = true,
            onViewProfile = onViewProfile,
            blockingRepository = blockingRepository,
            pushDeepLink = pushDeepLink,
        )
    }
}

/**
 * The chat hub rendered as a TRANSPARENT overlay *over* the map — the same idiom
 * as the map-layers and live-share popups: no dimming scrim, so the live map
 * stays visible behind (and faintly through) a translucent surface. Tapping
 * outside the card or pressing Back dismisses it (an explicit transparent tap
 * layer over the map strip handles outside taps; [ChatHubContent]'s own
 * `BackHandler` handles Back). The card is a fixed fraction (92%) of the SAFE
 * area (the window minus the status-bar inset), anchored to the bottom — so it
 * reaches the bottom edge (keeping the message-input row's navigation-bar / IME
 * inset effective) while provably leaving an uncovered, tappable strip of live
 * map above it that always clears system UI, at any window size or orientation.
 * Only the card is opaque/interactive; the strip dismisses on tap.
 * Content/tabs/functionality are identical to the route form ([ChatHubContent]);
 * only the container/presentation differs.
 *
 * Deliberately a plain composable in the HOST window rather than a
 * [androidx.compose.ui.window.Popup]. A Popup gets its OWN window, and that
 * window receives NO window-inset dispatch: measured on API 34,
 * `WindowInsets.navigationBars` reports 0 inside a Popup at the same moment the
 * host activity window reports the real inset (63px), and `WindowInsets.ime` is
 * likewise always 0 there. The message input's
 * `WindowInsets.ime.union(WindowInsets.navigationBars)` padding therefore
 * evaluated to ZERO in the popup form — the input pinned itself flush to the
 * window's bottom edge under the nav bar, and with no app-side IME handling the
 * framework's legacy ADJUST_PAN (the popup window's softInputMode defaulted to
 * SOFT_INPUT_ADJUST_UNSPECIFIED) panned the whole window when the keyboard
 * opened, flinging the input up the screen. Hosting the hub in the activity's
 * own window — which `enableEdgeToEdge()` already puts in charge of its own
 * insets — makes both states correct by construction and needs no per-window
 * softInputMode patching.
 *
 * Must be composed inside a full-window [Box] (it fills its parent), and last
 * among its siblings so it draws above the map and the shell's tabs.
 */
@Composable
fun ChatHubPopup(
    uid: String,
    communityChatRepository: CommunityChatRepository?,
    convoyChatRepository: ConvoyChatRepository?,
    // Sources the community @-picker's roster (one friend-list call). Null in a
    // config-less build: the picker then offers only the members talking in the
    // channel.
    friendsRepository: FriendsRepository?,
    dmRepository: DmRepository?,
    notificationsRepository: NotificationsRepository?,
    notificationsCoordinator: NotificationsCoordinator?,
    communityUnread: Boolean,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    onViewProfile: ((String) -> Unit)? = null,
    blockingRepository: BlockingRepository? = null,
) {
    // Full-window container: the card is deliberately SHORTER than the window,
    // so the area above it is a genuine, visible, tappable "outside". A
    // transparent tap layer fills the window; the card (composed after) sits on
    // top at the bottom, so only the uncovered map strip actually receives the
    // dismiss tap. A raw pointerInput handler plus clearAndSetSemantics keeps
    // this invisible dismiss layer out of the accessibility tree (mirrors the
    // map-home outside-tap scrims).
    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) { detectTapGestures { onClose() } }
                    .clearAndSetSemantics {},
        )
        // Safe-area box for the card: status-bar inset FIRST, so the height
        // fraction below is measured against the safe area rather than the raw
        // window. Without this the card's top is a fixed fraction of the WINDOW,
        // which is not guaranteed to clear system UI — on a short window
        // (landscape, split-screen, a tall status bar / cutout) the remaining
        // fraction can be smaller than the status bar and the hub's top bar would
        // render underneath it. Insetting first makes the card's top
        // statusBar + (1 - fraction) * safeHeight — provably below system UI at
        // any window size or orientation. Only the top is inset, so the box (and
        // the bottom-anchored card in it) still reaches the window's bottom edge,
        // keeping the message input's nav-bar / IME inset effective.
        Box(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Surface(
                modifier =
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        // An explicit FRACTION, not fillMaxHeight(): fillMaxHeight
                        // fills the parent's max-height constraint, so any preceding
                        // padding lands INSIDE the node's footprint and the card
                        // still spans the whole parent — no real strip. A fraction
                        // provably leaves the top (1 - fraction) of the safe area
                        // uncovered for the map + dismiss layer, while BottomCenter
                        // alignment keeps the card on the bottom edge. Sized BEFORE
                        // the horizontal padding, which only insets the sides.
                        .fillMaxHeight(CHAT_HUB_CARD_HEIGHT_FRACTION)
                        .padding(horizontal = KccSpacing.s2)
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
                    friendsRepository = friendsRepository,
                    dmRepository = dmRepository,
                    notificationsRepository = notificationsRepository,
                    notificationsCoordinator = notificationsCoordinator,
                    communityUnread = communityUnread,
                    onClose = onClose,
                    // The enclosing box already applies the status-bar inset, so
                    // the content must NOT add it again.
                    applyStatusBarInset = false,
                    onViewProfile = onViewProfile,
                    blockingRepository = blockingRepository,
                )
            }
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
 * @param onViewProfile opens a member's read-only profile, passed down to every
 *   member-bearing tab (community + convoy sender headers, the DM thread title).
 *   That profile is a shell ROUTE, so opening it from the popup form closes the
 *   hub — the popup's gate in AuthenticatedApp only holds while no route is open.
 *   Null (config-less build) leaves those affordances inert.
 * @param blockingRepository backs the block action behind every message-bearing
 *   tab's long-press moderation sheet. Null (config-less build) leaves the
 *   sheet's block row off. Unlike [onViewProfile] this closes nothing: the sheet
 *   and its confirm dialog compose INSIDE the hub, opening no route.
 */
@Composable
private fun ChatHubContent(
    uid: String,
    communityChatRepository: CommunityChatRepository?,
    convoyChatRepository: ConvoyChatRepository?,
    // Sources the community @-picker's roster (one friend-list call). Null in a
    // config-less build: the picker then offers only the members talking in the
    // channel.
    friendsRepository: FriendsRepository?,
    dmRepository: DmRepository?,
    notificationsRepository: NotificationsRepository?,
    notificationsCoordinator: NotificationsCoordinator?,
    // Hoisted from AuthenticatedApp, which already collects observeUnread(uid) to
    // drive the map chat-bubble dot. Passed in (not re-subscribed here) so a
    // single Firestore listener backs both the bubble and this tab's dot.
    communityUnread: Boolean,
    onClose: () -> Unit,
    applyStatusBarInset: Boolean,
    onViewProfile: ((String) -> Unit)?,
    blockingRepository: BlockingRepository?,
    pushDeepLink: PushDeepLink? = null,
) {
    var selectedTab by rememberSaveable { mutableStateOf(ChatTab.Community) }

    // Friends sub-nav: the open DM thread's target, or null to show the inbox.
    var dmOtherUid by rememberSaveable { mutableStateOf<String?>(null) }
    var dmOtherName by rememberSaveable { mutableStateOf<String?>(null) }
    // Convoys sub-nav: the open convoy, or null to show the convoy list.
    var openConvoyId by rememberSaveable { mutableStateOf<String?>(null) }
    var openConvoyTitle by rememberSaveable { mutableStateOf<String?>(null) }

    // A push tap picks the landing tab. Keyed on the link and applied once, so
    // navigating away inside the hub (or backing out of a channel) is not undone
    // on the next recomposition. The convoy TITLE is deliberately left null —
    // it is display-only and the channel resolves it itself.
    LaunchedEffect(pushDeepLink) {
        when (pushDeepLink?.target) {
            PushTarget.COMMUNITY_CHAT -> selectedTab = ChatTab.Community
            PushTarget.CONVOY_CHAT -> {
                selectedTab = ChatTab.Convoys
                openConvoyId = pushDeepLink.entityId
            }
            PushTarget.FRIENDS -> selectedTab = ChatTab.Friends
            PushTarget.NOTIFICATIONS -> selectedTab = ChatTab.Notifications
            else -> Unit
        }
    }

    // Tell the messaging service which conversation is on screen so it does not
    // post a banner for the messages the member is watching arrive. Cleared on
    // dispose, so backgrounding the app re-enables notifications.
    val activeChat: ActiveChat? =
        when {
            selectedTab == ChatTab.Friends && dmOtherUid != null -> ActiveChat.Dm(dmOtherUid!!)
            selectedTab == ChatTab.Convoys && openConvoyId != null ->
                ActiveChat.Convoy(openConvoyId!!)
            selectedTab == ChatTab.Community -> ActiveChat.Community
            else -> null
        }
    DisposableEffect(activeChat) {
        activeChat?.let(ActiveChatRegistry::set)
        onDispose { activeChat?.let(ActiveChatRegistry::clear) }
    }

    // The one place the POST_NOTIFICATIONS ask makes sense: the member is
    // looking at their messages, so "tell me when new ones arrive" needs no
    // explanation. Asks at most once ever, and a denial changes nothing here —
    // the hub and the in-app inbox work exactly the same either way.
    RequestPushPermissionEffect()

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
                            CommunityChannelRoute(
                                repository = communityChatRepository,
                                uid = uid,
                                friendsRepository = friendsRepository,
                                onViewProfile = onViewProfile,
                                blockingRepository = blockingRepository,
                            )
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
                                    onViewProfile = onViewProfile,
                                    blockingRepository = blockingRepository,
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
                                    onViewProfile = onViewProfile,
                                    blockingRepository = blockingRepository,
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

/**
 * One chat-hub tab: the section's icon above its label.
 *
 * Built from the generic content-slot [Tab] rather than the text+icon overload
 * because that overload wraps the label in its own fixed horizontal padding,
 * spending ~16dp of an already-tight quarter-width tab on whitespace. Laying the
 * column out here gives the label the tab's full width; combined with the
 * auto-shrink (see [TAB_LABEL_MIN_SP]) the label always fits whole.
 *
 * `softWrap = false` keeps each label on one line: these are single words in both
 * locales, so wrapping could only break mid-word, and letting the text auto-shrink
 * to fit one line reads better on a tab than two cramped lines.
 */
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
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = KccSpacing.s2),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s1),
        ) {
            if (showDot) {
                androidx.compose.material3.BadgedBox(badge = { Badge() }) {
                    Icon(imageVector = icon, contentDescription = null)
                }
            } else {
                Icon(imageVector = icon, contentDescription = null)
            }
            Text(
                text = label,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                maxLines = 1,
                softWrap = false,
                // A last resort only: a label that will not fit even at the floor
                // ellipsizes rather than shrinking into illegibility. No shipped
                // label in either locale reaches the floor at any width/font scale
                // the app targets — ChatHubTabLabelTest pins exactly that.
                overflow = TextOverflow.Ellipsis,
                autoSize =
                    TextAutoSize.StepBased(
                        minFontSize = TAB_LABEL_MIN_SP,
                        maxFontSize = TAB_LABEL_MAX_SP,
                        stepSize = TAB_LABEL_STEP_SP,
                    ),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
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
