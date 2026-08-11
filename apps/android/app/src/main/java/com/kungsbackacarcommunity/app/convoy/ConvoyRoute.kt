package com.kungsbackacarcommunity.app.convoy

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import com.kungsbackacarcommunity.app.friends.FriendActionError
import com.kungsbackacarcommunity.app.friends.FriendsCoordinator
import com.kungsbackacarcommunity.app.friends.FriendsRepository
import com.kungsbackacarcommunity.app.friends.FriendsStatus
import com.kungsbackacarcommunity.app.live.LiveShareStart
import com.kungsbackacarcommunity.app.profile.FirebaseLiveProfileRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Which convoy sub-screen the route is currently showing. */
private enum class ConvoyView { List, Create, Detail }

/**
 * Convoy management integration route. Builds the coordinator, loads the
 * snapshot on entry (no live listener — the callable set is re-fetched after
 * each mutation), and hosts the internal list → create → detail navigation as
 * a single [com.kungsbackacarcommunity.app.shell.ShellRoute]. A nested
 * [BackHandler] pops the internal stack (create/detail → list) BEFORE the
 * shell's central handler closes the whole route, so system-Back walks back one
 * sub-screen at a time until the list, then exits to the Social hub.
 *
 * The friend invite-picker reads the SHARED [FriendsRepository] via a
 * [FriendsCoordinator] (friend-list → friends), mirroring the Friends screen —
 * only the caller's friends can be invited (the backend also enforces this).
 *
 * The convoy live-map is a separate surface (built elsewhere) and is
 * deliberately not linked from here; see the driving-mode follow-up.
 *
 * [openCreateOnEntry] deep-links straight into the create-convoy sub-screen on
 * first entry (used by the map "+" Create-tab chooser's "Convoy" option). It is
 * a one-shot: consumed once so that backing out to the list — or reopening the
 * route from the Social hub — behaves normally and never re-forces Create.
 *
 * [onViewMember] opens a detail-roster member's read-only profile (null leaves
 * the rows inert); [viewerUid] is the caller, whose own row never navigates.
 *
 * [onConvoyCreated] is invoked once a convoy is successfully created. The create
 * flow no longer shows a "Convoy created" confirmation page: on success the host
 * dismisses this whole surface and lands on the MAP tab, where the convoy bar
 * shows the new (active) convoy. A FAILURE does NOT call it — the error stays on
 * the create screen. Null (a config-less/test host with no map to land on) falls
 * back to opening the new convoy's detail so the flow never dead-ends.
 *
 * INVITE DEEP LINK. [inviteDeepLinkConvoyId] is the convoy a tapped convoy-invite
 * notification was about. The invite is answered on the LIST that is already
 * here (see ConvoyListScreen's pending-invite section) — no second invite screen
 * exists or should — so the deep link does two small things: it pulls that
 * invite to the top of the section, and it re-checks what actually became of it
 * against THIS route's freshly loaded list. That second part is the race: the
 * inbox decided the row looked actionable from a snapshot that could be minutes
 * old, and by the time the tap lands the convoy may have ended or the invite may
 * have been answered on another device. Rather than dropping the member on a
 * list with no matching invite and no explanation — "tapping does nothing", in a
 * new costume — [ConvoyInviteDeepLink] names the outcome and the list shows it.
 *
 * The id is consumed once ([onInviteDeepLinkConsumed]) so a later plain visit to
 * the convoy route cannot re-raise a notice about an invite the member has since
 * dealt with. Answering the invite HERE also silences that re-check
 * ([ConvoyInviteDeepLink.outcome]'s `answeredHere`) — otherwise the member's own
 * successful Accept is the very thing that makes the list stop showing the
 * invite as pending, and the notice fires "You've already answered that invite"
 * at somebody in the act of answering it.
 *
 * ACCEPT HAND-OFF. [onConvoyJoined] is invoked, with the convoy's id, once an
 * Accept has landed — the accept-side twin of [onConvoyCreated]. The host closes
 * this surface and lands on the MAP, where the convoy bar shows the convoy just
 * joined and the camera frames its members. The route dissolves out first (see
 * [ConvoyAcceptHandoff]) so the trip reads as an arrival rather than a cut. A
 * FAILED accept does not call it — the error stays on this list — and a null
 * host (config-less/test surface with no map) keeps the pre-existing behaviour
 * of simply staying on the refreshed list.
 *
 * [liveShareEnabled] mirrors the shell's "this caller may share live" gate and
 * exists only for the OPTIMISTIC live-start overlay below: creating a convoy,
 * accepting into an already-active one and starting a forming one all begin a
 * live session for the CALLER server-side, and the shell's stop control would
 * otherwise sit on "+" until that session echoed back down the RTDB listener.
 */
@Composable
fun ConvoyRoute(
    repository: ConvoyRepository,
    friendsRepository: FriendsRepository?,
    openCreateOnEntry: Boolean = false,
    onViewMember: ((String) -> Unit)? = null,
    viewerUid: String? = null,
    onConvoyCreated: (() -> Unit)? = null,
    onConvoyJoined: ((String) -> Unit)? = null,
    liveShareEnabled: Boolean = false,
    inviteDeepLinkConvoyId: String? = null,
    onInviteDeepLinkConsumed: () -> Unit = {},
    // The car the owner picked in the "Start driving" popup before choosing
    // Convoy. Sent with convoy-create so the owner's auto-started convoy session
    // denormalizes it; null lets the server fall back to their main car.
    createVehicleId: String? = null,
) {
    val scope = rememberCoroutineScope()
    // Refreshes the roster's denormalized member profiles from live users/{uid}
    // (ConvoyCoordinator.liveProfiles). Not remembered: sharedOrEmpty returns the
    // process-wide instance (or the EMPTY singleton), so it is already a stable
    // reference and cannot churn the coordinator's remember key.
    val liveProfiles = FirebaseLiveProfileRepository.sharedOrEmpty(LocalContext.current)
    val coordinator =
        remember(repository, liveProfiles) {
            ConvoyCoordinator(repository, liveProfiles = liveProfiles)
        }
    val status by coordinator.status.collectAsState()
    val actionError by coordinator.actionError.collectAsState()
    val busyConvoys by coordinator.busyConvoys.collectAsState()
    val createState by coordinator.createState.collectAsState()

    // Friends snapshot for the invite-picker (shared FriendsRepository). Loaded
    // lazily via its own coordinator. A null repository (config-less build) has
    // no snapshot to load, so it resolves to a terminal Error state — which the
    // picker renders as an "unavailable" notice — instead of a permanent Loading
    // spinner that would never resolve.
    val friendsCoordinator =
        remember(friendsRepository) { friendsRepository?.let { FriendsCoordinator(it) } }
    val friendsStatus: FriendsStatus =
        friendsCoordinator?.status?.collectAsState()?.value
            ?: FriendsStatus.Error(FriendActionError.Generic)

    var view by rememberSaveable { mutableStateOf(ConvoyView.List) }
    var detailConvoyId by rememberSaveable { mutableStateOf<String?>(null) }

    // Create-flow local form state (reset each time the picker is opened).
    var selectedUids by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }

    // --- Optimistic live-share start ------------------------------------
    // A convoy live session is started SERVER-side: convoy-create auto-starts the
    // owner's, convoy-respond auto-starts a late joiner accepting into an
    // already-ACTIVE convoy, and convoy-start auto-starts every accepted member
    // of a still-forming one (functions/src/convoy/manageConvoy.ts). For the
    // member who TAPPED, a live session is beginning right now, so the shell's
    // centre control should show the STOP sign immediately rather than after the
    // callable + RTDB echo.
    //
    // Only the tapper is ever marked: a member auto-started by SOMEONE ELSE's
    // convoy-start — or while the app is backgrounded — is untouched here and
    // keeps the pure observer path, so the overlay can never invent sharing for a
    // tap that did not happen.
    //
    // Tracks an outstanding mark for the dispose safety net below.
    var liveStartMarked by remember { mutableStateOf(false) }

    fun markLiveStarting(): Boolean {
        val marked =
            liveShareEnabled &&
                LiveShareStart.request(
                    nowMillis = System.currentTimeMillis(),
                    // Not observable from this route; the shell reconciles an
                    // attempt made while a session is already running and drops it.
                    observedSharing = false,
                )
        if (marked) liveStartMarked = true
        return marked
    }

    // Every mark is resolved by the very call that made it — settled when the
    // callable succeeded (the session is then a moment away), dropped when it did
    // not, so a failed accept/start never leaves a STOP sign with no session. A
    // call that succeeds without producing a session for this caller (the
    // server-side live-share flag is off) expires on its own after
    // OptimisticLiveStart.ECHO_GRACE_MS.
    fun resolveLiveStarting(marked: Boolean, succeeded: Boolean) {
        if (!marked) return
        liveStartMarked = false
        if (succeeded) {
            LiveShareStart.settled(System.currentTimeMillis())
        } else {
            LiveShareStart.failed()
        }
    }

    // Safety net for the one flow that dismisses ITSELF on success: a successful
    // create navigates away (see the createState effect below), which cancels the
    // coroutine that would otherwise have resolved the mark — leaving it in
    // flight for the full timeout with a STOP sign up. Settling on dispose caps
    // that at the short echo window instead, which is the right window anyway:
    // we only leave this route on a create that SUCCEEDED, so the session really
    // is on its way, and if it never arrives the grace takes the sign back.
    DisposableEffect(Unit) {
        onDispose { if (liveStartMarked) LiveShareStart.settled(System.currentTimeMillis()) }
    }

    LaunchedEffect(coordinator) { coordinator.load() }

    // The invite deep link, held locally for as long as this route is showing
    // its notice. Latched on entry and the shell's copy cleared immediately, so
    // re-entering the route later starts clean; the notice itself is dismissed
    // by the member (or by the invite appearing, which needs no notice).
    var inviteLinkConvoyId by rememberSaveable { mutableStateOf<String?>(null) }
    var inviteNoticeDismissed by rememberSaveable { mutableStateOf(false) }
    // Set the moment the member answers the deep-linked invite from THIS screen.
    // See the ACCEPT/ANSWER note in the KDoc: without it, a successful Accept is
    // itself what flips the re-check to ANSWERED, so the reward for accepting an
    // invite was being told you had already answered it.
    var inviteAnsweredHere by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(inviteDeepLinkConvoyId) {
        val id = inviteDeepLinkConvoyId?.takeIf { it.isNotBlank() }
        if (id != null) {
            inviteLinkConvoyId = id
            inviteNoticeDismissed = false
            inviteAnsweredHere = false
            // A deep link always lands on the list — a tap on an invite must not
            // be swallowed by whatever sub-screen the route was last left in.
            view = ConvoyView.List
            onInviteDeepLinkConsumed()
        }
    }
    val inviteOutcome =
        ConvoyInviteDeepLink.outcome(inviteLinkConvoyId, status, answeredHere = inviteAnsweredHere)
    // Records that the deep-linked invite was answered here, whichever way. Also
    // covers Decline: declining likewise removes the invite from the pending list
    // and would raise the same notice about the member's own action.
    fun noteAnsweredHere(convoyId: String) {
        if (convoyId == inviteLinkConvoyId) inviteAnsweredHere = true
    }

    // --- Post-accept hand-off to the map --------------------------------
    // Non-null while the surface is dissolving out towards the map; carries the
    // convoy that was just joined, which the host needs to frame its members.
    var joinedConvoyId by remember { mutableStateOf<String?>(null) }
    val handingOffAfterAccept = joinedConvoyId != null
    val handoffAlpha by animateFloatAsState(
        targetValue = ConvoyAcceptHandoff.contentAlpha(handingOffAfterAccept),
        animationSpec =
            tween(durationMillis = ConvoyAcceptHandoff.FADE_MILLIS, easing = FastOutSlowInEasing),
        label = "convoyAcceptHandoff",
    )
    // Hand over once the dissolve has run. Keyed on the id, so it fires exactly
    // once per accept; the host tears this route down in response, which is what
    // ends the effect. NOT rememberSaveable: a hand-off interrupted by process
    // death should not resume into a navigation the member never sees start.
    LaunchedEffect(joinedConvoyId) {
        val convoyId = joinedConvoyId ?: return@LaunchedEffect
        delay(ConvoyAcceptHandoff.FADE_MILLIS.toLong())
        onConvoyJoined?.invoke(convoyId)
    }

    // Load the friends snapshot whenever the invite-picker is shown. Declarative
    // (keyed on the sub-view) so it fires on EVERY entry into Create — the list
    // "Create" button, the map "+" deep-link, and a process-death restoration
    // straight back into the Create sub-screen — rather than relying on the
    // imperative navigation handlers, one of which could be skipped. Re-running
    // on each entry also re-attempts a previously failed load, so a transient
    // read hiccup no longer leaves a permanent "friends unavailable" notice.
    // A null repository (config-less build) has no coordinator, so this is a
    // no-op and the picker keeps its terminal Error/unavailable fallback.
    LaunchedEffect(view, friendsCoordinator) {
        if (view == ConvoyView.Create) friendsCoordinator?.load()
    }

    // Deep-link: when entered from the map "+" chooser's "Convoy" option, jump
    // straight to the create-convoy sub-screen. One-shot (a saveable guard) so
    // that once consumed, backing out to the list — or reopening the route from
    // the Social hub — is never re-forced back into Create.
    var createDeepLinkConsumed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(openCreateOnEntry) {
        if (openCreateOnEntry && !createDeepLinkConsumed) {
            createDeepLinkConsumed = true
            selectedUids = emptySet()
            coordinator.resetCreate()
            // Friends load is driven declaratively by the LaunchedEffect(view)
            // above once the sub-view flips to Create.
            view = ConvoyView.Create
        }
    }

    // Once a convoy is created, do NOT show a confirmation page: dismiss the whole
    // convoy surface and land on the map, where the convoy bar now shows the new
    // (active) convoy's info (member count, invite, leave, focus). The map's own
    // bar coordinator re-loads on return and picks the new convoy up as active, so
    // no state is threaded across — closing to the map is enough.
    //
    // Only a SUCCESS navigates (see postCreateNav): a create FAILURE keeps the
    // create screen up with its inline error, never dropping to the map as if it
    // had worked. A null host (config-less/test) has no map to land on, so it falls
    // back to the new convoy's detail rather than dead-ending.
    //
    // Navigate FIRST, then resetCreate(). resetCreate flips createState back to Idle,
    // which would make the create screen's `handingOff` guard false and RE-ENABLE the
    // picker/submit. Tearing the route/view down before the reset means the create
    // screen is already gone by the time Idle lands, so its controls can never become
    // interactive again in the window between Created and the route dismissing — no
    // second convoy.create is possible. The reset still runs, so a later re-entry into
    // the route starts from a clean Idle sub-state.
    LaunchedEffect(createState) {
        if (postCreateNav(createState) == PostCreateNav.GoToMap) {
            val newConvoyId = (createState as CreateConvoyState.Created).convoyId
            if (onConvoyCreated != null) {
                onConvoyCreated()
            } else {
                detailConvoyId = newConvoyId
                view = ConvoyView.Detail
            }
            coordinator.resetCreate()
        }
    }

    // Pop internal navigation before the shell closes the route. Disabled on the
    // list root so the shell's handler then returns to the Social hub.
    BackHandler(enabled = view != ConvoyView.List) { view = ConvoyView.List }

    when (view) {
        ConvoyView.List ->
            ConvoyListScreen(
                // The post-accept dissolve. Only the LIST needs it: an accept can
                // only be made from here, so this is the only sub-screen a
                // hand-off can ever start from — and scoping it here keeps the
                // other two on the plain, un-layered draw path they had before.
                modifier = Modifier.alpha(handoffAlpha),
                status = status,
                actionError = actionError,
                busyConvoys = busyConvoys,
                inviteDeepLinkConvoyId = inviteLinkConvoyId,
                inviteDeepLinkOutcome =
                    inviteOutcome.takeIf {
                        !inviteNoticeDismissed && ConvoyInviteDeepLink.needsNotice(it)
                    },
                onDismissInviteDeepLinkNotice = { inviteNoticeDismissed = true },
                onCreate = {
                    // Fresh form each time the picker opens; the friends snapshot
                    // is (re)loaded by the LaunchedEffect(view) once view == Create.
                    selectedUids = emptySet()
                    coordinator.resetCreate()
                    view = ConvoyView.Create
                },
                onOpenConvoy = { convoyId ->
                    detailConvoyId = convoyId
                    view = ConvoyView.Detail
                },
                onAccept = { convoyId ->
                    // Inert once the hand-off has started: the surface is fading
                    // out and its rows are on their way off screen, so a tap
                    // landing in that window must not start a second respond.
                    // (The coordinator's in-flight guard already covers the
                    // rapid double-tap DURING the call — see runRowAction — and
                    // the row's own button is disabled while busy; this closes
                    // the window AFTER it settles.)
                    if (!handingOffAfterAccept) {
                        noteAnsweredHere(convoyId)
                        // Accepting only auto-starts a session when the convoy is
                        // ALREADY active; accepting into a still-forming one starts
                        // nothing yet (convoy-start does that later), so nothing is
                        // claimed optimistically for it.
                        val marked =
                            (status as? ConvoyListStatus.Loaded)?.convoy(convoyId)?.status ==
                                ConvoyStatus.Active &&
                                markLiveStarting()
                        scope.launch {
                            coordinator.accept(convoyId)
                            // runRowAction clears the row error before each attempt and
                            // sets it on failure, so this reads THIS call's outcome.
                            val succeeded = coordinator.actionError.value == null
                            // Resolve the optimistic mark BEFORE the hand-off: the
                            // dissolve ends with the host tearing this route down,
                            // which cancels this coroutine, and an unresolved mark
                            // would leave a STOP sign up for the full grace window.
                            // The overlay stays RENDER-ONLY either way — nothing
                            // here touches recording or the foreground service —
                            // and it is marked exactly once, at the tap.
                            resolveLiveStarting(marked, succeeded)
                            if (ConvoyAcceptHandoff.navFor(
                                    succeeded = succeeded,
                                    hasMapHost = onConvoyJoined != null,
                                ) == ConvoyAcceptNav.FadeToMap
                            ) {
                                joinedConvoyId = convoyId
                            }
                        }
                    }
                },
                onDecline = { convoyId ->
                    noteAnsweredHere(convoyId)
                    scope.launch { coordinator.decline(convoyId) }
                },
                onClearActionError = { coordinator.clearActionError() },
            )

        ConvoyView.Create ->
            CreateConvoyScreen(
                friendsStatus = friendsStatus,
                createState = createState,
                selectedUids = selectedUids,
                onToggleFriend = { uid ->
                    selectedUids =
                        if (uid in selectedUids) selectedUids - uid else selectedUids + uid
                },
                // Only offer retry when there is a coordinator to retry with; a
                // null repo (config-less build) gets no button (it could never
                // succeed) — just the terminal unavailable notice.
                onRetryFriends =
                    friendsCoordinator?.let { c -> { scope.launch { c.load() } } },
                onSubmit = {
                    // Convoys are born ACTIVE, so creating one starts the owner's
                    // own live session server-side — mark it optimistically.
                    val marked = markLiveStarting()
                    // Convoys are unnamed — the title is always absent and the
                    // list/detail fall back to the neutral "untitled" label.
                    scope.launch {
                        coordinator.create(selectedUids.toList(), null, createVehicleId)
                        resolveLiveStarting(
                            marked,
                            coordinator.createState.value is CreateConvoyState.Created,
                        )
                    }
                },
                // No onDone: a successful create is handled by the
                // LaunchedEffect(createState) above, which dismisses to the map.
            )

        ConvoyView.Detail -> {
            val convoy = (status as? ConvoyListStatus.Loaded)?.convoy(detailConvoyId ?: "")
            if (convoy != null) {
                ConvoyDetailScreen(
                    convoy = convoy,
                    working = convoy.convoyId in busyConvoys,
                    actionError = actionError,
                    onStart = {
                        // Activating a forming convoy auto-starts a live session
                        // for every accepted member, the owner tapping here
                        // included.
                        val marked = markLiveStarting()
                        scope.launch {
                            coordinator.start(convoy.convoyId)
                            resolveLiveStarting(marked, coordinator.actionError.value == null)
                        }
                    },
                    onEnd = { scope.launch { coordinator.end(convoy.convoyId) } },
                    // Leave — the other exit. Any accepted member, the leader
                    // included; the screen decides whether to offer it.
                    onLeave = { scope.launch { coordinator.leave(convoy.convoyId) } },
                    onClearActionError = { coordinator.clearActionError() },
                    onViewMember = onViewMember,
                    viewerUid = viewerUid,
                )
            } else {
                // The convoy fell out of the snapshot (e.g. a concurrent change)
                // or the list is still loading — return to the list rather than
                // render an empty detail. This is a transient state, so show a
                // neutral loading placeholder instead of a fully-wired-looking
                // list whose buttons would be dead until the pop lands.
                LaunchedEffect(detailConvoyId, status) {
                    if (status is ConvoyListStatus.Loaded) view = ConvoyView.List
                }
                ConvoyLoadingScreen()
            }
        }
    }
}
