package com.kungsbackacarcommunity.app.live

import com.kungsbackacarcommunity.app.profile.ClubBadge
import com.kungsbackacarcommunity.app.profile.MembershipMapRing
import kotlinx.coroutines.flow.Flow

/** The small public profile subset watched only for already-drawn live markers. */
data class LiveMarkerProfile(
    val membershipMapRing: MembershipMapRing = MembershipMapRing(),
    val clubBadge: ClubBadge? = null,
) {
    fun appearance() = LiveMarkerAppearance(membershipMapRing.visible, clubBadge)

    companion object {
        fun fromFields(fields: Map<String, Any?>): LiveMarkerProfile = LiveMarkerProfile(
            MembershipMapRing.fromFields(fields["membershipMapRingEligible"], fields["showMembershipMapRing"]),
            ClubBadge.fromField(fields["clubBadge"]),
        )
    }
}

data class LiveMarkerAppearance(val showMembershipMapRing: Boolean = false, val clubBadge: ClubBadge? = null)

interface LiveMarkerAppearanceRepository {
    /** Null on missing/unreadable public profile; never retain a stale badge on failure. */
    fun observeProfile(uid: String): Flow<LiveMarkerProfile?>
}

/** Cosmetics are omitted above this limit, never locations added or access expanded. */
const val MAX_LIVE_MARKER_APPEARANCE_UIDS = 100
