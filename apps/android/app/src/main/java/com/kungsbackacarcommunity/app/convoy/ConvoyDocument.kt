package com.kungsbackacarcommunity.app.convoy

import com.kungsbackacarcommunity.app.profile.LiveProfile
import com.kungsbackacarcommunity.app.profile.LiveProfiles

/**
 * Pure mapping of a RAW `convoys/{convoyId}` Firestore document into the wire
 * [ConvoySummary], plus the merge that folds a live doc update back into a
 * loaded snapshot.
 *
 * ## Why this exists (a second parser, deliberately)
 * The convoy management surface reads convoys through the `convoy-list` CALLABLE,
 * which returns an already-shaped [ConvoySummary] JSON that [ConvoyResponseParser]
 * decodes. That path is POLLED — re-fetched after each mutation, never live (see
 * [ConvoyCoordinator]). A shared destination, a member accepting, or a member
 * leaving therefore only appears on the next refresh.
 *
 * To make those changes propagate LIVE, [FirebaseConvoyRepository.observeConvoy]
 * attaches a Firestore `addSnapshotListener` straight to the convoy document —
 * which the firestore.rules already let any member in `memberUids` read. But the
 * RAW document is shaped differently from the callable response:
 *  - `members` is a MAP keyed by uid (not a list), and the profile
 *    (displayName/avatarPath) lives in a SEPARATE `memberProfiles` map;
 *  - `viewer` and `livePositionUids` are NOT stored — the callable derives them
 *    per-caller, so this mapper derives them here too;
 *  - timestamps are Firestore `Timestamp` objects, not ISO strings.
 *
 * So this is the CLIENT mirror of the backend `toConvoySummary`
 * (functions/src/convoy/convoy-core.ts). It is kept Firebase-free — the
 * `Timestamp`→ISO conversion is INJECTED as [toIso] — so every branch (owner
 * sort, accepted-only live set, viewer derivation, a malformed member dropped)
 * is JVM-unit-testable without the emulator, exactly like the backend core.
 */
object ConvoyDocument {

    /**
     * Maps a raw convoy document into [ConvoySummary], or null when the document
     * is structurally unusable (missing id or owner) — mirroring
     * [ConvoyResponseParser.parseConvoy], which drops such a row rather than
     * surfacing a blank one.
     *
     * @param convoyId the document id (not stored in the doc body).
     * @param data the raw `getData()` map, or null for a missing document.
     * @param callerUid the viewer, used to derive [ConvoySummary.viewer] the same
     *   way the callable does from `context.auth.uid`. A null/blank caller yields
     *   a null viewer (the bar then treats the convoy as not-joined and hides).
     * @param toIso converts a stored timestamp value (a Firebase `Timestamp`, or
     *   an already-ISO string) to an ISO-8601 string, or null. Injected so this
     *   object never imports Firebase.
     */
    fun toSummary(
        convoyId: String,
        data: Map<String, Any?>?,
        callerUid: String?,
        toIso: (Any?) -> String?,
    ): ConvoySummary? {
        if (data == null) return null
        if (convoyId.isBlank()) return null
        val ownerUid = (data["ownerUid"] as? String)?.takeIf { it.isNotBlank() } ?: return null

        val profiles = (data["memberProfiles"] as? Map<*, *>).orEmptyMap()
        val membersMap = (data["members"] as? Map<*, *>).orEmptyMap()
        val members =
            membersMap.values
                .mapNotNull { parseMember(it, profiles, toIso) }
                // Owner first, then by uid — a stable roster order matching the
                // backend's toConvoySummary sort so the client and callable paths
                // agree and a live update never re-orders the roster.
                .sortedWith(
                    compareBy({ if (it.role == ConvoyRole.Owner) 0 else 1 }, { it.uid }),
                )

        val memberUids =
            (data["memberUids"] as? List<*>).orEmpty().mapNotNull { it as? String }

        val viewer =
            callerUid
                ?.takeIf { it.isNotBlank() }
                ?.let { uid -> membersMap[uid] as? Map<*, *> }
                ?.let { entry ->
                    ConvoyViewer(
                        role = parseRole(entry["role"]),
                        inviteStatus = parseInviteStatus(entry["inviteStatus"]),
                    )
                }

        return ConvoySummary(
            convoyId = convoyId,
            ownerUid = ownerUid,
            title = data["title"] as? String,
            status = parseStatus(data["status"]),
            members = members,
            memberUids = memberUids,
            viewer = viewer,
            // Accepted members (owner included) — the live-position subscription
            // set, derived exactly like the backend rather than stored.
            livePositionUids =
                members.filter { it.inviteStatus == ConvoyInviteStatus.Accepted }.map { it.uid },
            summary = parseSummary(data["summary"]),
            createdAt = toIso(data["createdAt"]),
            startedAt = toIso(data["startedAt"]),
            endedAt = toIso(data["endedAt"]),
            destination = parseDestination(data["destination"], toIso),
        )
    }

    private fun parseMember(
        raw: Any?,
        profiles: Map<*, *>,
        toIso: (Any?) -> String?,
    ): ConvoyMember? {
        val map = raw as? Map<*, *> ?: return null
        val uid = (map["uid"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val profile = profiles[uid] as? Map<*, *>
        return ConvoyMember(
            uid = uid,
            role = parseRole(map["role"]),
            inviteStatus = parseInviteStatus(map["inviteStatus"]),
            joinedAt = toIso(map["joinedAt"]),
            displayName = profile?.get("displayName") as? String,
            avatarPath = profile?.get("avatarPath") as? String,
        )
    }

    private fun parseSummary(raw: Any?): ConvoySummaryStats? {
        val map = raw as? Map<*, *> ?: return null
        val participantUids =
            (map["participantUids"] as? List<*>).orEmpty().mapNotNull { it as? String }
        return ConvoySummaryStats(
            durationSeconds = (map["durationSeconds"] as? Number)?.toLong() ?: 0L,
            participantUids = participantUids,
            participantCount = (map["participantCount"] as? Number)?.toInt() ?: participantUids.size,
            distanceMeters = (map["distanceMeters"] as? Number)?.toDouble(),
        )
    }

    /**
     * Parses the raw stored `destination` map. Mirrors
     * [ConvoyResponseParser.parseDestination]: a destination without a usable,
     * in-bounds coordinate — or without a `setByUid` to attribute it — is dropped
     * rather than surfaced, because a bar offering "start navigation" to a corrupt
     * coordinate is worse than a bar offering none.
     */
    private fun parseDestination(raw: Any?, toIso: (Any?) -> String?): ConvoyDestination? {
        val map = raw as? Map<*, *> ?: return null
        val latitude = (map["latitude"] as? Number)?.toDouble() ?: return null
        val longitude = (map["longitude"] as? Number)?.toDouble() ?: return null
        if (!ConvoyDestinations.isValidCoordinate(latitude, longitude)) return null
        val setByUid = (map["setByUid"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        return ConvoyDestination(
            latitude = latitude,
            longitude = longitude,
            label = (map["label"] as? String)?.takeIf { it.isNotBlank() },
            setByUid = setByUid,
            setByDisplayName = (map["setByDisplayName"] as? String)?.takeIf { it.isNotBlank() },
            setAt = toIso(map["setAt"]),
        )
    }

    private fun parseStatus(raw: Any?): ConvoyStatus =
        when (raw) {
            "active" -> ConvoyStatus.Active
            "ended" -> ConvoyStatus.Ended
            else -> ConvoyStatus.Forming
        }

    private fun parseRole(raw: Any?): ConvoyRole =
        if (raw == "owner") ConvoyRole.Owner else ConvoyRole.Member

    private fun parseInviteStatus(raw: Any?): ConvoyInviteStatus =
        when (raw) {
            "accepted" -> ConvoyInviteStatus.Accepted
            "declined" -> ConvoyInviteStatus.Declined
            else -> ConvoyInviteStatus.Invited
        }

    private fun List<*>?.orEmpty(): List<*> = this ?: emptyList<Any?>()

    private fun Map<*, *>?.orEmptyMap(): Map<*, *> = this ?: emptyMap<Any?, Any?>()
}

/**
 * Folds a live [fresh] convoy read into an existing list [status], replacing the
 * convoy that shares its id wherever it appears (the caller's convoys and, if it
 * is still a pending invite, that list too). Everything else — ordering, the
 * other convoys, the [ConvoyListStatus] variant — is preserved, so a snapshot
 * update reaches the bar/detail as fresher data for ONE convoy without disturbing
 * the rest of the loaded snapshot.
 *
 * Pure (no coroutines, no Firebase) so the merge is unit-testable. When [status]
 * is not [ConvoyListStatus.Loaded], or [fresh]'s id is not present in it, the
 * status is returned unchanged: a live doc for a convoy that is not in the loaded
 * list must not be injected into it (it would appear from nowhere and, lacking
 * the list's context, could misrender).
 */
fun mergeConvoyUpdate(
    status: ConvoyListStatus,
    fresh: ConvoySummary,
): ConvoyListStatus {
    if (status !is ConvoyListStatus.Loaded) return status
    val present =
        status.convoys.any { it.convoyId == fresh.convoyId } ||
            status.pendingInvites.any { it.convoyId == fresh.convoyId }
    if (!present) return status
    return ConvoyListStatus.Loaded(
        convoys = status.convoys.map { if (it.convoyId == fresh.convoyId) fresh else it },
        pendingInvites =
            status.pendingInvites.map { if (it.convoyId == fresh.convoyId) fresh else it },
    )
}

/**
 * Marks the convoy [convoyId] as [ConvoyStatus.Ended] in an existing list
 * [status], wherever it appears. Everything else — ordering, the other convoys,
 * the rest of each summary — is preserved.
 *
 * This is the OPTIMISTIC half of the leader's "end convoy": an ended convoy is
 * filtered out of [ConvoyBar.activeConvoy] (it requires `status != Ended`), so
 * folding this in the instant the leader confirms clears the convoy bar/list at
 * once, without waiting for the `convoy-end` callable and the follow-up
 * re-fetch to round-trip. The real end still runs behind it and the resync
 * reconciles the true state (and revives the convoy if the call actually
 * failed) — see [ConvoyCoordinator.end].
 *
 * Pure (no coroutines, no Firebase) so it is unit-testable. A non-[Loaded]
 * status, or a [convoyId] not present, is returned unchanged.
 */
fun endConvoyLocally(
    status: ConvoyListStatus,
    convoyId: String,
): ConvoyListStatus {
    if (status !is ConvoyListStatus.Loaded) return status
    fun end(convoy: ConvoySummary): ConvoySummary =
        if (convoy.convoyId == convoyId && convoy.status != ConvoyStatus.Ended) {
            convoy.copy(status = ConvoyStatus.Ended)
        } else {
            convoy
        }
    return ConvoyListStatus.Loaded(
        convoys = status.convoys.map(::end),
        pendingInvites = status.pendingInvites.map(::end),
    )
}

/**
 * Replaces each roster entry's DENORMALIZED profile with that member's current
 * one, where a live profile was loaded.
 *
 * `convoys/{id}.memberProfiles` is captured at create/invite time and never
 * refreshed — `convoy.respond` writes invite status only — so a member who
 * changes their avatar after being invited keeps the old one on the roster for
 * the convoy's whole life. [LiveProfiles.resolve] carries the fallback rules.
 *
 * Applied on BOTH convoy read paths (the `convoy-list` callable and the live
 * document listener) before they meet in [mergeConvoyUpdate]. That is not
 * belt-and-braces: hydrating only the callable would be undone the moment the
 * listener delivered its next snapshot of the same convoy.
 *
 * Only [ConvoyMember.displayName] / [ConvoyMember.avatarPath] change. Membership
 * and authorization are derived from `memberUids` / `members[uid].inviteStatus`,
 * never from the profile map, so this cannot alter who is in a convoy or what
 * they may do.
 */
fun hydrateConvoy(convoy: ConvoySummary, live: Map<String, LiveProfile>): ConvoySummary {
    if (live.isEmpty()) return convoy
    return convoy.copy(
        members =
            convoy.members.map { member ->
                val resolved =
                    LiveProfiles.resolve(
                        member.uid,
                        LiveProfile(member.displayName, member.avatarPath),
                        live,
                    )
                member.copy(
                    displayName = resolved.displayName,
                    avatarPath = resolved.avatarPath,
                )
            },
    )
}

/**
 * Upper bound on how many member profiles one convoy snapshot will refresh.
 *
 * `convoy.list` is capped at MAX_CONVOYS_RETURNED (200) and does NOT filter by
 * status — a long-standing member's list includes every convoy they have ever
 * been in, ended ones included — at up to MAX_CONVOY_SIZE (25) members each. So
 * the uid set is bounded by 5000, not by what is on screen, and hydrating it
 * whole would turn opening the convoy screen into thousands of document reads
 * for rosters nobody scrolls to.
 *
 * This cap keeps the refresh proportional to what a member actually looks at.
 * [convoyProfileUids] fills it in the order it is given the convoys, so the
 * caller decides who matters (active and pending convoys before ended ones), and
 * anything past the cap simply keeps its stored copy — the same graceful
 * degradation as a failed read.
 */
const val MAX_HYDRATED_CONVOY_PROFILES = 120

/**
 * The distinct member uids named by [convoys], for one batched profile read,
 * capped at [limit] and filled in the order [convoys] is given.
 */
fun convoyProfileUids(
    convoys: List<ConvoySummary>,
    limit: Int = MAX_HYDRATED_CONVOY_PROFILES,
): Set<String> {
    val uids = LinkedHashSet<String>()
    for (convoy in convoys) {
        for (member in convoy.members) {
            if (uids.size >= limit) return uids
            if (member.uid.isNotBlank()) uids += member.uid
        }
    }
    return uids
}
