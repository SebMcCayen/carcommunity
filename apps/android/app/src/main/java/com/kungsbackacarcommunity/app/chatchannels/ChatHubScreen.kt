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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.blocking.BlockingRepository
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.design.KccTypeScale
import com.kungsbackacarcommunity.app.dm.ChatRoute
import com.kungsbackacarcommunity.app.dm.ConversationListRoute
import com.kungsbackacarcommunity.app.dm.DmRepository
import com.kungsbackacarcommunity.app.friends.FriendsRepository
import com.kungsbackacarcommunity.app.notifications.ConvoyNotificationLink
import com.kungsbackacarcommunity.app.notifications.NotificationsCoordinator
import com.kungsbackacarcommunity.app.notifications.NotificationsRepository
import com.kungsbackacarcommunity.app.notifications.NotificationsRoute
import com.kungsbackacarcommunity.app.push.ActiveChat
import com.kungsbackacarcommunity.app.push.ActiveChatRegistry
import com.kungsbackacarcommunity.app.push.PushDeepLink
import com.kungsbackacarcommunity.app.push.PushTarget
import com.kungsbackacarcommunity.app.push.RequestPushPermissionEffect
import com.kungsbackacarcommunity.app.shell.TranslucentShellPanel

/** Test tag on the chat-hub root, so UI tests can assert it renders. */
const val CHAT_HUB_TEST_TAG = "chat_hub"

/**
 * The four sections of the chat hub, in the order they appear in the tab row AND
 * in the swipe pager. The pager addresses pages by [ChatTab.ordinal], so this
 * declaration order IS the left-to-right swipe order — reordering these reorders
 * both the tabs and the swipe. Pinned by `ChatTabOrderTest`.
 */
enum class ChatTab { Community, Convoys, Friends, Notifications }

/**
 * The hub tab a push [target] should land on, or [ChatTab.Community] for anything
 * that does not name one of the four sections (including a null link and targets
 * like DM/EVENT that the hub does not host). Pure so the deep-link → tab mapping
 * is unit-testable without composing the hub; used both to seed the initial tab
 * and by the landing effect. Community is the hub's default, so an unmapped
 * target is a no-op rather than a jump.
 */
internal fun chatHubLandingTab(target: PushTarget?): ChatTab =
    when (target) {
        PushTarget.COMMUNITY_CHAT -> ChatTab.Community
        PushTarget.CONVOY_CHAT -> ChatTab.Convoys
        PushTarget.FRIENDS -> ChatTab.Friends
        PushTarget.NOTIFICATIONS -> ChatTab.Notifications
        else -> ChatTab.Community
    }

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
    // Tapping a shared `geo:` location link in a channel message moves the app's
    // OWN map to that point, in-app. Forwarded down to the channel routes; null
    // leaves such links as plain text. Same as [ChatHubPopup].
    onShowLocationOnMap: ((latitude: Double, longitude: Double) -> Unit)? = null,
    blockingRepository: BlockingRepository? = null,
    // Set when the hub was opened by tapping a push, so it lands on the tab (and
    // convoy channel) the notification was about instead of the default
    // Community tab. Consumed once — backing out must not re-apply it.
    // (The popup form takes the same parameter; see [ChatHubPopup].)
    pushDeepLink: PushDeepLink? = null,
    // Lets the Notifications tab's convoy rows resolve their convoy's state and
    // open it. Forwarded untouched; see [NotificationsRoute].
    convoyLink: ConvoyNotificationLink? = null,
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
            onShowLocationOnMap = onShowLocationOnMap,
            blockingRepository = blockingRepository,
            pushDeepLink = pushDeepLink,
            convoyLink = convoyLink,
        )
    }
}

/**
 * The chat hub rendered as a TRANSLUCENT overlay *over* the map, via the shared
 * [TranslucentShellPanel] — the very same bottom-sheet-style panel the History,
 * Social and Garage tabs use. So the hub is dismissed the same three ways they
 * are: pulling the drag handle (or the message list, once it is at the top)
 * DOWNWARDS past the threshold, tapping the uncovered strip of live map above the
 * card, or pressing Back ([ChatHubContent]'s own `BackHandler`). There is
 * deliberately NO close (X) button — the drag handle is the affordance, matching
 * every other dismissible panel. The card is a fixed fraction of the SAFE area
 * (the window minus the status-bar inset), anchored to the bottom, so it reaches
 * the bottom edge (keeping the message-input row's navigation-bar / IME inset
 * effective) while leaving a tappable strip of live map above it that always
 * clears system UI. Content/tabs/functionality are identical to the route form
 * ([ChatHubContent]); only the container/presentation differs.
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
    // Tapping a shared `geo:` location link in a channel message moves the app's
    // OWN map to that point, in-app. Same as [ChatHubRoute].
    onShowLocationOnMap: ((latitude: Double, longitude: Double) -> Unit)? = null,
    blockingRepository: BlockingRepository? = null,
    convoyLink: ConvoyNotificationLink? = null,
    // Set when the hub was opened by an affordance that names WHERE it should
    // land, rather than by the plain chat bubble: today the map shell's convoy bar
    // chat icon, which passes a CONVOY_CHAT link for the convoy it badges so the
    // hub opens on that convoy's channel instead of on Community. Same parameter
    // (and same one-shot consumption) as the push taps [ChatHubRoute] forwards.
    pushDeepLink: PushDeepLink? = null,
) {
    // The whole overlay — the bottom-anchored translucent card, the uncovered
    // tappable map strip above it, the drag handle + drag-to-dismiss, the
    // outside-tap dismiss and the accessibility `dismiss` action — is the SHARED
    // shell-panel component, the same one History / Social / Garage use. The chat
    // hub used to hand-roll its own card + outside-tap layer here; reusing the
    // panel means the hub is dismissed by pulling its handle down exactly like
    // every other panel, and the geometry / inset contract can never drift from
    // theirs. The card is in the activity's own window (the panel is a plain Box,
    // not a Popup), so the message input's `ime.union(navigationBars)` padding
    // still resolves to the real insets — the reason the hub left the Popup form
    // in the first place (see the KDoc above).
    TranslucentShellPanel(
        onDismiss = onClose,
        testTag = CHAT_HUB_TEST_TAG,
        modifier = modifier,
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
            // The panel's card already sits below the status bar, so the content
            // must NOT add the inset again.
            applyStatusBarInset = false,
            onViewProfile = onViewProfile,
            onShowLocationOnMap = onShowLocationOnMap,
            blockingRepository = blockingRepository,
            pushDeepLink = pushDeepLink,
            convoyLink = convoyLink,
        )
    }
}

/**
 * Shared hub body: a tab row switching between the
 * COMMUNITY channel, the caller's CONVOY channels, FRIENDS (the existing 1:1 DMs),
 * and the in-app NOTIFICATIONS inbox — plus, inside a sub-screen only, a top bar
 * carrying Back and the conversation's name. Rendered inside either the
 * full-screen [ChatHubRoute] surface or the translucent [ChatHubPopup].
 *
 * TITLE. At hub level there is deliberately no title bar. The four tabs sit at
 * the top of the card and already name the section the member is looking at, so
 * a "Chat" heading above them was pure chrome eating vertical space — the same
 * reasoning (and the same hub) as PR #627, which removed the "Notifications" and
 * "Messages" headings from two of these tabs. The hub keeps an ACCESSIBLE name
 * via a pane title, so a screen reader still hears what opened.
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
    onShowLocationOnMap: ((latitude: Double, longitude: Double) -> Unit)?,
    blockingRepository: BlockingRepository?,
    pushDeepLink: PushDeepLink? = null,
    convoyLink: ConvoyNotificationLink? = null,
) {
    // Seeded from any push deep-link so the hub OPENS on the linked tab (and the
    // pager below opens on the matching page with no scroll animation). Absent a
    // link this is Community, the default. rememberSaveable so a swipe/tap survives
    // recomposition and rotation.
    var selectedTab by rememberSaveable {
        mutableStateOf(chatHubLandingTab(pushDeepLink?.target))
    }

    // The swipe pager over the four sections. Its page index is [ChatTab.ordinal],
    // so page and selected tab are the same value. Seeded to the selected tab so a
    // deep link lands on its page immediately rather than animating in from
    // Community.
    val pagerState =
        rememberPagerState(initialPage = selectedTab.ordinal) { ChatTab.entries.size }
    val scope = rememberCoroutineScope()

    // The pager is the single source of truth for which section is showing:
    // [selectedTab] is a REFLECTION of the pager's CURRENT page, driving the tab-row
    // indicator (and the active-chat registry) from whatever page the pager is on —
    // whether the user swiped there or tapped a tab.
    //
    // `currentPage`, deliberately, NOT `settledPage`: currentPage flips as the swipe
    // crosses the half-way point, so the tab indicator moves with the member's
    // finger and the section they are pulling into view is the one marked active.
    // settledPage would hold the old tab highlighted for the whole gesture and snap
    // only after the animation finished, which reads as lag. Abandoning a swipe
    // simply flips it back, since the pager returns to the page it came from.
    //
    // Deliberately one-directional (pager → tab): a tab TAP scrolls the pager
    // directly (see ChatTabItem's onSelect) and lets this reflect the result. The
    // earlier shape — a LaunchedEffect(selectedTab) that animated the pager — self
    // -cancelled on a multi-page jump, because the intermediate pages this
    // snapshotFlow reports would re-key that effect and abort its own animation
    // half-way. Scrolling from a plain coroutine instead runs to completion.
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            selectedTab = ChatTab.entries[page]
        }
    }

    // Friends sub-nav: the open DM thread's target, or null to show the inbox.
    var dmOtherUid by rememberSaveable { mutableStateOf<String?>(null) }
    var dmOtherName by rememberSaveable { mutableStateOf<String?>(null) }
    // Convoys sub-nav: the open convoy, or null to show the convoy list.
    var openConvoyId by rememberSaveable { mutableStateOf<String?>(null) }
    var openConvoyTitle by rememberSaveable { mutableStateOf<String?>(null) }

    // A deep link — a push tap, or the map shell's convoy-bar chat icon — picks the
    // landing tab (already seeded into selectedTab above) and, for a convoy link,
    // the channel to open inside the Convoys tab. Keyed on the
    // link and applied once, so navigating away inside the hub (or backing out of a
    // channel) is not undone on the next recomposition. The convoy TITLE is
    // deliberately left null — it is display-only and the channel resolves it
    // itself.
    LaunchedEffect(pushDeepLink) {
        if (pushDeepLink != null) {
            val landing = chatHubLandingTab(pushDeepLink.target)
            // Jump the pager to the linked page WITHOUT animating — the hub is
            // opening on this section, so it should already be there, not slide in
            // from Community. On the initial open the pager was seeded to this same
            // page, so this is a no-op; it only bites if a newer link arrives while
            // the hub is already open. selectedTab follows via the snapshotFlow.
            if (pagerState.currentPage != landing.ordinal) {
                pagerState.scrollToPage(landing.ordinal)
            }
            if (pushDeepLink.target == PushTarget.CONVOY_CHAT) {
                openConvoyId = pushDeepLink.entityId
            }
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

    // The SUB-SCREEN title only. At hub level the bar used to read "Chat", which
    // said nothing the four tabs directly beneath it did not already say — the
    // same redundancy PR #627 removed from the Notifications and Messages
    // sections inside this very hub. Inside a thread or a convoy channel the tab
    // row is hidden, so the bar is then the ONLY thing naming the conversation
    // and it stays. Null therefore means "hub level, no bar".
    val subScreenTitle: String? =
        when {
            inConvoyChannel ->
                openConvoyTitle ?: stringResource(R.string.chatHub_convoyUntitled)
            inFriendThread -> dmOtherName ?: stringResource(R.string.dm_unknownMember)
            else -> null
        }

    // The hub's accessible name, with no pixels spent on it. Dropping the visible
    // title must not leave the panel silent: it appears OVER the map, and a
    // TalkBack user who opened it would otherwise get no announcement of what
    // just arrived (the shell panel itself only labels its drag handle). A pane
    // title is announced when the pane appears and is reachable afterwards, which
    // is exactly what the removed heading was doing for screen readers — so
    // `chatHub.title` is still used, just no longer drawn.
    val hubPaneTitle = stringResource(R.string.chatHub_title)

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .semantics { paneTitle = hubPaneTitle }
                .then(if (applyStatusBarInset) Modifier.statusBarsPadding() else Modifier),
    ) {
        // Everything below is identical for the route and popup forms; only the
        // surrounding container (opaque route surface vs translucent popup card)
        // differs.
        run {
            // Top bar: back + the conversation's name, composed ONLY inside a
            // sub-screen. At hub level it carried nothing but the redundant "Chat"
            // label — the Back arrow is already conditional on [inSubScreen] — so
            // there the whole bar is gone and the tabs start at the top of the
            // card. No control is lost: there is no close (X) button in either
            // form; the hub is dismissed by dragging the panel's handle down,
            // tapping the map strip above the card, or system Back (see
            // [ChatHubPopup] / [TranslucentShellPanel]). The legacy full-screen
            // [ChatHubRoute] form is likewise dismissed by system Back, consistent
            // with every other full-screen route.
            if (subScreenTitle != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = KccSpacing.s2),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = popSubScreen) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.chatHub_back),
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        text = subScreenTitle,
                        modifier = Modifier.weight(1f).padding(horizontal = KccSpacing.s2),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
                        onSelect = { scope.launch { pagerState.animateScrollToPage(it.ordinal) } },
                    )
                    ChatTabItem(
                        tab = ChatTab.Convoys,
                        selected = selectedTab,
                        icon = Icons.Filled.Route,
                        label = stringResource(R.string.chatHub_tabConvoys),
                        onSelect = { scope.launch { pagerState.animateScrollToPage(it.ordinal) } },
                    )
                    ChatTabItem(
                        tab = ChatTab.Friends,
                        selected = selectedTab,
                        icon = Icons.Filled.Person,
                        label = stringResource(R.string.chatHub_tabFriends),
                        onSelect = { scope.launch { pagerState.animateScrollToPage(it.ordinal) } },
                    )
                    ChatTabItem(
                        tab = ChatTab.Notifications,
                        selected = selectedTab,
                        icon = Icons.Filled.Notifications,
                        label = stringResource(R.string.chatHub_tabNotifications),
                        onSelect = { scope.launch { pagerState.animateScrollToPage(it.ordinal) } },
                    )
                }
            }

            // The four sections live in a HorizontalPager so the member can SWIPE
            // between them, not only tap the row above. Pages are addressed by
            // [ChatTab.ordinal], the same order the tab row is built in, so the tab
            // indicator and the pager page are the one shared index.
            //
            // Swipe is disabled while a sub-screen (an open DM thread or convoy
            // channel) is showing: that view takes the full height, the tab row is
            // hidden, and Back is the way out — sliding sideways out of a thread
            // would be wrong.
            //
            // Gesture coexistence: this pager claims only HORIZONTAL drags. The
            // hosting TranslucentShellPanel's pull-to-dismiss is vertical (its
            // NestedScrollConnection consumes available.y only; its handle is an
            // Orientation.Vertical draggable), and each section's message list
            // scrolls vertically. Compose disambiguates a drag by its dominant axis
            // at touch-down, so a sideways swipe drives only the pager while an
            // up/down drag drives only the list or the panel — they never fight.
            //
            // beyondViewportPageCount is deliberately left at its default of 0, so
            // ONLY the section(s) actually on screen are composed. Each section
            // subscribes as soon as it composes — CommunityChannelRoute collects
            // observeMessages() (and fires markRead()), NotificationsRoute collects
            // observeNotifications(), ConversationListRoute collects
            // observeConversations(), ConvoyListRoute calls listConvoys() — so
            // prefetching a neighbour would open Firestore listeners and spend a
            // callable for tabs the member never opens, on their battery and our
            // bill. Worse, it would mark the community channel READ behind their
            // back: markRead() keys on composition, not on being looked at.
            //
            // The swipe stays smooth without the prefetch because a page composes
            // as soon as it enters the viewport — i.e. at the very start of the
            // drag, while the neighbour is only a sliver wide — so it is live well
            // before it settles. It renders its own loading state in the meantime,
            // exactly as it does on a tab tap. `key` pins each page's identity to
            // its tab so state is not reshuffled as pages recompose.
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                userScrollEnabled = !inSubScreen,
                key = { ChatTab.entries[it] },
            ) { page ->
                when (ChatTab.entries[page]) {
                    ChatTab.Community ->
                        if (communityChatRepository != null) {
                            CommunityChannelRoute(
                                repository = communityChatRepository,
                                uid = uid,
                                friendsRepository = friendsRepository,
                                onViewProfile = onViewProfile,
                                onShowLocationOnMap = onShowLocationOnMap,
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
                                    onShowLocationOnMap = onShowLocationOnMap,
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
                                    // The Friends TAB above already says where the
                                    // member is; a "Messages" header under it would
                                    // only repeat it.
                                    showTitle = false,
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
                                convoyLink = convoyLink,
                                // The Notifications TAB above already says where
                                // the member is; a "Notifications" header under it
                                // would only repeat it.
                                showTitle = false,
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
