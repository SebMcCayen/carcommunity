package com.kungsbackacarcommunity.app.convoy

import com.kungsbackacarcommunity.app.navigation.LatLng

/**
 * The convoy SHARED DESTINATION: one member picks a place, and every other
 * member sees it in their convoy bar and can start turn-by-turn navigation to it
 * with a single tap.
 *
 * Everything in this file is pure Kotlin — the state machine (no destination /
 * mine / someone else's / cleared while I'm driving to it), the overwrite rule
 * and the availability gate — so the behaviour is JVM-unit-testable instead of
 * living inside a Composable.
 *
 * ## BACKEND GAP — a shared destination CANNOT be faked client-side
 * The deployed convoy surface is exactly five callables (`convoy-create`,
 * `convoy-respond`, `convoy-start`, `convoy-end`, `convoy-list`). There is no
 * destination or waypoint concept anywhere: not in the callables, not on the
 * `convoys/{convoyId}` document, not in `contracts/functions/functions.json`.
 *
 * A destination is *inherently shared state* — its entire purpose is that OTHER
 * phones see it. So unlike a purely local preference it cannot be shimmed with a
 * client-side store: a "shared" destination that exists only on the phone that
 * set it is a lie told to the person who set it, and invisible to everyone it was
 * meant for. It is also not smuggled through an existing field (the convoy title,
 * or a chat message), because a coordinate parsed back out of a free-text field
 * is unvalidated, unbounded, and would corrupt a field members can already edit.
 *
 * Hence [ConvoyDestinations.availability]: the set and start-navigation controls
 * are RENDERED, disabled, and explained in one honest line, exactly as PR #481
 * did for convoy leave/invite and PR #433 for the missing report callables.
 *
 * ## Callables needed to finish this
 *
 * ### `convoy.setDestination` (grouped export `convoy-setDestination`)
 * europe-west1, auth + App Check, same as the rest of the convoy domain.
 *
 * Payload `{ convoyId: string, latitude: number, longitude: number,
 * label?: string }` → `{ convoy: ConvoySummary }` (the FULL refreshed summary,
 * matching `respond`/`start`/`end`, so the client re-uses
 * [ConvoyResponseParser.parseMutation] and needs no second shape).
 *
 * **Who may set: ANY accepted member**, not owner-only. A convoy is a peer group
 * of people who are already friends (create is friend-gated), and the person who
 * knows where the meet actually is frequently is not the person who happened to
 * press "create". Owner-only would mean a convoy whose owner is driving — and so
 * should not be typing an address — cannot retarget at all. The safeguards are
 * social rather than hierarchical: the client confirms before overwriting a
 * destination someone else set, and the destination records *who* set it so the
 * change is attributable in the bar.
 *
 * **Setting REPLACES any existing destination** (last write wins, no queue, no
 * multi-waypoint). A convoy has exactly one "where we are all going"; merging or
 * stacking destinations would mean members silently navigating to different
 * places. The server should write the whole destination object atomically rather
 * than patching fields, so a half-updated destination is not observable.
 *
 * **Server-side validation (the client's checks are convenience, never trust):**
 *  - membership: caller must have `inviteStatus === 'accepted'` in
 *    `members.{uid}`; a non-member or unknown convoy gets `not-found` (never
 *    `permission-denied`), matching respond/start/end so a convoy cannot be
 *    probed for existence.
 *  - `latitude` finite and in [-90, 90]; `longitude` finite and in [-180, 180];
 *    both required and numeric → `invalid-argument` otherwise. Rejecting
 *    NaN/Infinity explicitly matters: they survive JSON round-trips in some
 *    clients and would poison every distance computation downstream.
 *  - `label` optional, trimmed, max 120 characters (see
 *    [ConvoyDestinations.MAX_LABEL_LENGTH]) — long enough for a full formatted
 *    street address, short enough not to become a chat channel. Over-length →
 *    `invalid-argument`. Blank after trim is stored as absent, not as an empty
 *    string.
 *
 *    Note the client and the server deliberately differ here: the CLIENT
 *    truncates an over-long label to 120 characters before it reaches the wire
 *    (see [ConvoyDestinations.normalizeLabel]), so in practice this server check
 *    only fires for a caller that is not this picker. That split is intentional
 *    — truncation needs the user's intent to be the right answer, and only the
 *    client has it; the server, facing an unknown caller, rejects rather than
 *    silently reshaping data it cannot interpret.
 *  - status: a convoy with `status === 'ended'` → `failed-precondition`. A
 *    `forming` convoy MAY have a destination set — agreeing where to go is
 *    exactly what happens before a convoy starts rolling.
 *  - the server stamps `setByUid` from `context.auth.uid` and `setAt` from the
 *    server clock. Neither is client-supplied: a client-chosen setter uid would
 *    let someone attribute a destination to another member.
 *
 * ### `convoy.clearDestination` (grouped export `convoy-clearDestination`)
 * Payload `{ convoyId: string }` → `{ convoy: ConvoySummary }`.
 *
 * **Who may clear: the member who SET it, or the convoy OWNER.** Clearing is
 * strictly more destructive than setting — it can leave members mid-drive with
 * nothing to navigate to — and unlike an overwrite it leaves no replacement
 * behind to explain itself. Setter-or-owner keeps the person who made the mess
 * able to undo it, and gives the owner a moderation path, without letting any
 * member wipe a plan the group is following. Anyone else → `permission-denied`
 * (the convoy's existence is already known to them, so `not-found` would be
 * misleading here). Clearing when there is no destination is a no-op, not an
 * error — idempotency matters when two people tap at once.
 *
 * ### How members READ it — a field on the convoy document
 * `destination` becomes an optional field on `convoys/{convoyId}`, serialized
 * into the existing `ConvoySummary` shape that `convoy-list` and every convoy
 * mutation already return:
 *
 * ```json
 * "destination": {
 *   "latitude": 57.4879,
 *   "longitude": 12.0760,
 *   "label": "Kungsbacka torg",
 *   "setByUid": "abc123",
 *   "setByDisplayName": "Anna",
 *   "setAt": "2026-07-19T18:04:11.000Z"
 * }
 * ```
 *
 * Deliberately NOT a separate read or a new listener. Members already receive the
 * convoy summary through the one convoy read path, so hanging the destination off
 * it means it arrives with everything else and no client grows a second source of
 * truth to keep in sync. `setByDisplayName` is denormalized by the server the
 * same way `members[].displayName` already is, so the bar can say who set it
 * without a profile fetch per render.
 *
 * One caveat the backend lane must take seriously: today's convoy read path is
 * *polled* (`convoy-list` re-fetched after each mutation — see
 * [ConvoyCoordinator]), not live. A destination set by another member therefore
 * appears on the next refresh, not instantly. For the destination to feel shared
 * it wants a push: either the existing convoy push-notification path fanned out
 * on set/clear, or promoting the convoy read to a Firestore listener. That is a
 * backend decision; the client here is written against the summary field and
 * works either way, just faster with the push.
 *
 * ### Does a destination survive `convoy.end`?
 * Yes — it is left on the document, untouched, as a record of where the convoy
 * was headed. `convoy.end` already computes and stores a summary every member
 * reads; wiping the destination at the same moment would delete a fact from that
 * record for no benefit. The client simply never renders destination controls for
 * an ended convoy (the bar does not render at all — see [ConvoyBar.activeConvoy]),
 * so a surviving destination is inert, not confusing. A future
 * `distanceMeters`/route summary would want it present.
 *
 * ### Should ARRIVING at the destination do anything?
 * It should **not auto-end the convoy**, and the server should not track arrival
 * at all. Two reasons. First, "reached" is unknowable server-side without a
 * geofence over continuously-streamed positions — a materially bigger, more
 * privacy-invasive and more battery-expensive feature than a shared pin, and one
 * that would fire on the first member to arrive while the rest are still driving.
 * Second, ending a convoy is a deliberate, group-wide, summary-writing act the
 * owner performs; inferring it from a GPS fix would end other people's drive on a
 * guess — precisely the class of bug the owner-only End control exists to avoid.
 * The honest behaviour is what turn-by-turn already does: the navigating member
 * arrives, their own navigation completes, and the convoy stays up until someone
 * ends it. A *notification* ("Anna reached the destination") is a reasonable
 * later feature; an automatic end is not.
 */

/** Whether the convoy-destination controls can actually reach a backend today. */
enum class ConvoyDestinationAvailability {
    /** The callables exist and are wired — the controls run for real. */
    Wired,

    /**
     * No `convoy-setDestination` / `convoy-clearDestination` callable exists yet.
     * The controls are still RENDERED, disabled, and paired with a short honest
     * explanation — the same choice PR #481 made for leave/invite: a shared
     * destination is something people sitting in a convoy actively look for, and
     * an absent button reads as "this app can't do that" rather than "not yet".
     */
    BackendMissing,
}

/**
 * The convoy's shared destination, as carried on the convoy document.
 *
 * @param label the human-readable place name, when the setter's pick had one. A
 *   long-press on open map may not, which is why this is nullable and the UI
 *   falls back to a generic "shared destination" label rather than printing raw
 *   coordinates at a driver.
 * @param setByUid who set it — server-stamped, never client-supplied. Drives the
 *   mine-vs-theirs split and the clear permission.
 * @param setByDisplayName denormalized by the server (like `members[].displayName`)
 *   so the bar can attribute it without a profile fetch.
 */
data class ConvoyDestination(
    val latitude: Double,
    val longitude: Double,
    val label: String?,
    val setByUid: String,
    val setByDisplayName: String?,
    val setAt: String?,
) {
    /** The coordinate in the navigation feature's own (lng-first) type. */
    val point: LatLng get() = LatLng(longitude = longitude, latitude = latitude)
}

/**
 * What the convoy bar should show about the shared destination. `null` is not
 * used here — [None] is an explicit state, because "no destination yet" still
 * renders a control (the one that sets it).
 */
sealed interface ConvoyDestinationState {
    /** Nobody has set a destination. Only the "set destination" control shows. */
    data object None : ConvoyDestinationState

    /**
     * The VIEWER set the current destination. They get "change" and "clear"; they
     * do not get a "start navigation" prompt any more urgently than anyone else,
     * but the control is offered all the same — setting a destination and then
     * driving to it is the common case.
     */
    data class SetByMe(val destination: ConvoyDestination) : ConvoyDestinationState

    /**
     * SOMEONE ELSE set the current destination. The bar names them when it can,
     * because "start navigation" to a place you did not choose is only reasonable
     * if you can see who chose it.
     */
    data class SetByOther(val destination: ConvoyDestination) : ConvoyDestinationState
}

/**
 * What happened to the destination the viewer is currently navigating to.
 *
 * This exists because the dangerous case is not "no destination" — it is a
 * destination that changes UNDER someone who is already driving to it.
 */
sealed interface ConvoyDestinationNavigationEvent {
    /** Nothing relevant changed: keep driving, say nothing. */
    data object Unchanged : ConvoyDestinationNavigationEvent

    /**
     * The destination the viewer is navigating to was CLEARED.
     *
     * The navigation is deliberately **left running**. Silently cancelling
     * someone's turn-by-turn mid-road is the worst available outcome: the driver
     * loses their route at speed, and the coordinate they were driving to is
     * exactly the thing that just got deleted, so it cannot be recovered by
     * re-picking it. Instead the route continues and the bar surfaces a
     * dismissible line saying the shared destination was removed — the driver
     * keeps guidance to where they were already headed, and decides for themselves
     * whether to stop. Their turn-by-turn is their own session, targeting a plain
     * coordinate; it never depended on the convoy document staying put.
     */
    data object Cleared : ConvoyDestinationNavigationEvent

    /**
     * The destination was REPLACED with a different one while the viewer was
     * navigating to the old one. Same principle as [Cleared] — the existing route
     * is not hijacked mid-drive — but the bar offers an explicit "navigate to the
     * new destination" action, so switching is one deliberate tap rather than
     * something that happens to them.
     */
    data class Replaced(val destination: ConvoyDestination) : ConvoyDestinationNavigationEvent
}

/** The one-line honest explanation the destination row shows, if any. */
enum class ConvoyDestinationNotice {
    /** The destination callables are wired — no explanation needed. */
    None,

    /** No backend for setting/clearing a shared destination yet. */
    BackendMissing,
}

/** Outcome of `convoy-setDestination` / `convoy-clearDestination`. */
sealed interface ConvoyDestinationResult {
    data class Updated(val convoy: ConvoySummary) : ConvoyDestinationResult

    data class Failed(val error: ConvoyActionError) : ConvoyDestinationResult

    /**
     * The callable does not exist in the deployed backend. Distinct from
     * [Failed] so a caller can never render "something went wrong" for a feature
     * that was simply never built — see [ConvoyDestinationAvailability].
     */
    data object Unavailable : ConvoyDestinationResult
}

/**
 * Setting and clearing the convoy's shared destination.
 *
 * A repository seam in its own right, rather than two more methods on
 * [ConvoyRepository], so the "this has no backend" boundary is a single object
 * that can be swapped whole. See the file KDoc for the callable contracts.
 */
interface ConvoyDestinationRepository {
    /**
     * Sets (or replaces) the convoy's shared destination. [label] is optional and
     * is trimmed/length-checked by [ConvoyDestinations.normalizeLabel] before it
     * reaches the wire.
     */
    suspend fun setDestination(
        convoyId: String,
        latitude: Double,
        longitude: Double,
        label: String?,
    ): ConvoyDestinationResult

    /** Clears the convoy's shared destination (setter or owner only). */
    suspend fun clearDestination(convoyId: String): ConvoyDestinationResult
}

/**
 * The repository used while no callable exists: every call returns
 * [ConvoyDestinationResult.Unavailable] without touching the network.
 *
 * This is NOT a client-side store standing in for the backend — it deliberately
 * remembers nothing. It exists so the wiring above it (bar → coordinator →
 * repository) is complete and real today, and so that the disabled controls have
 * something honest to be disabled against.
 */
object UnavailableConvoyDestinationRepository : ConvoyDestinationRepository {
    override suspend fun setDestination(
        convoyId: String,
        latitude: Double,
        longitude: Double,
        label: String?,
    ): ConvoyDestinationResult = ConvoyDestinationResult.Unavailable

    override suspend fun clearDestination(convoyId: String): ConvoyDestinationResult =
        ConvoyDestinationResult.Unavailable
}

/** Pure rules for the shared destination. */
object ConvoyDestinations {
    /**
     * The single flag that gates the whole feature. When
     * `convoy-setDestination` / `convoy-clearDestination` are deployed, flipping
     * this to [ConvoyDestinationAvailability.Wired] and handing the app
     * [FirebaseConvoyDestinationRepository] instead of
     * [UnavailableConvoyDestinationRepository] is the ENTIRE client change — the
     * state machine, the picker, the navigation launch and the strings are all
     * already here and already exercised by tests.
     */
    val availability: ConvoyDestinationAvailability = ConvoyDestinationAvailability.BackendMissing

    /** Max stored label length; over-length is rejected, never truncated. */
    const val MAX_LABEL_LENGTH = 120

    /** Whether the controls can run for real today. */
    val isWired: Boolean get() = availability == ConvoyDestinationAvailability.Wired

    /** The explanation line to render under the destination row, if any. */
    val notice: ConvoyDestinationNotice
        get() =
            if (isWired) ConvoyDestinationNotice.None else ConvoyDestinationNotice.BackendMissing

    /**
     * Which destination state the bar is in for [viewerUid].
     *
     * A destination whose `setByUid` is blank is treated as [SetByOther] rather
     * than as mine: attributing an unattributable destination to the viewer would
     * hand them a clear button the server would then refuse.
     */
    fun stateFor(destination: ConvoyDestination?, viewerUid: String?): ConvoyDestinationState {
        val dest = destination ?: return ConvoyDestinationState.None
        val mine =
            !viewerUid.isNullOrBlank() &&
                dest.setByUid.isNotBlank() &&
                dest.setByUid == viewerUid
        return if (mine) {
            ConvoyDestinationState.SetByMe(dest)
        } else {
            ConvoyDestinationState.SetByOther(dest)
        }
    }

    /**
     * True when setting a new destination would overwrite one that SOMEONE ELSE
     * set, and the UI must therefore confirm first.
     *
     * Replacing your OWN destination does not confirm — correcting a place you
     * just picked yourself is not a decision that needs a dialog, and a
     * confirmation there would train people to dismiss the one that matters.
     */
    fun requiresOverwriteConfirmation(
        current: ConvoyDestination?,
        viewerUid: String?,
    ): Boolean = stateFor(current, viewerUid) is ConvoyDestinationState.SetByOther

    /**
     * Whether [viewerUid] may clear [destination] — the setter or the convoy
     * owner (see the file KDoc). Mirrored client-side purely so the control is
     * not offered where the server would refuse it; the server remains the gate.
     */
    fun canClear(
        destination: ConvoyDestination?,
        viewerUid: String?,
        viewerIsOwner: Boolean,
    ): Boolean {
        val dest = destination ?: return false
        if (viewerIsOwner) return true
        return !viewerUid.isNullOrBlank() && dest.setByUid == viewerUid
    }

    /**
     * Trims [label] and TRUNCATES an over-long one to [MAX_LABEL_LENGTH].
     *
     * Blank (or null) comes back as null, so it is stored as absent rather than
     * as an empty string. An over-length label is deliberately truncated rather
     * than rejected: the label is a convenience on top of the coordinate, and
     * failing the whole set-destination action — or silently dropping the label
     * entirely — over a long formatted address would cost the user the thing they
     * actually asked for. Truncating keeps the action working and keeps most of
     * the address.
     *
     * This is why the client never sends an over-length label, and so never
     * provokes the server's `invalid-argument` for one (see the file KDoc). The
     * server keeps rejecting rather than truncating because it cannot know
     * whether an over-length label came from this client's picker or from
     * something malformed; the trimming decision belongs to whoever has the
     * user's intent, which is here.
     */
    fun normalizeLabel(label: String?): String? {
        val trimmed = label?.trim().orEmpty()
        return when {
            trimmed.isEmpty() -> null
            trimmed.length > MAX_LABEL_LENGTH -> trimmed.take(MAX_LABEL_LENGTH).trim()
            else -> trimmed
        }
    }

    /** Whether a coordinate is inside the valid WGS-84 bounds and finite. */
    fun isValidCoordinate(latitude: Double, longitude: Double): Boolean =
        latitude.isFinite() &&
            longitude.isFinite() &&
            latitude in -90.0..90.0 &&
            longitude in -180.0..180.0

    /**
     * What to tell a member who is CURRENTLY navigating to the shared
     * destination, given how it just changed.
     *
     * [navigatingTo] is the coordinate the viewer's turn-by-turn session is
     * actually targeting, or null when they are not navigating. It is compared by
     * coordinate rather than by identity because the navigation session owns a
     * plain [LatLng] — it was handed a place, not a subscription — which is
     * exactly why clearing the destination cannot break it.
     */
    fun navigationEvent(
        previous: ConvoyDestination?,
        current: ConvoyDestination?,
        navigatingTo: LatLng?,
    ): ConvoyDestinationNavigationEvent {
        val target = navigatingTo ?: return ConvoyDestinationNavigationEvent.Unchanged
        // Only speak up if they are driving to the destination that changed.
        val wasDrivingToIt = previous != null && sameCoordinate(previous.point, target)
        if (!wasDrivingToIt) return ConvoyDestinationNavigationEvent.Unchanged
        if (current == null) return ConvoyDestinationNavigationEvent.Cleared
        return if (sameCoordinate(current.point, target)) {
            ConvoyDestinationNavigationEvent.Unchanged
        } else {
            ConvoyDestinationNavigationEvent.Replaced(current)
        }
    }

    /**
     * Coordinate equality with a tolerance of ~1 cm, so a float round-trip
     * through JSON does not read as "the destination moved".
     */
    private fun sameCoordinate(a: LatLng, b: LatLng): Boolean {
        val epsilon = 1e-7
        return kotlin.math.abs(a.latitude - b.latitude) < epsilon &&
            kotlin.math.abs(a.longitude - b.longitude) < epsilon
    }
}
