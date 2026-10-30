package com.kungsbackacarcommunity.app.clubs

/** UI affordances only. The canonical callable revalidates every role and limit. */
internal enum class ClubMemberAction(val operation: String) {
    PromoteModerator("moderator"), DemoteModerator("moderator"), TransferOwnership("transfer"), RemoveMember("remove");

    fun enabled(moderatorCount: Int, busy: Boolean) = !busy &&
        (this != PromoteModerator || moderatorCount < 3)
}

internal fun clubMemberActions(viewerUid: String, viewerRole: ClubRole, target: ClubMember): List<ClubMemberAction> = buildList {
    if (viewerRole == ClubRole.Owner && target.uid != viewerUid && target.role != ClubRole.Owner) {
        add(if (target.role == ClubRole.Moderator) ClubMemberAction.DemoteModerator else ClubMemberAction.PromoteModerator)
        add(ClubMemberAction.TransferOwnership)
    }
    if (viewerRole.canRemove(viewerUid, target)) add(ClubMemberAction.RemoveMember)
}

internal fun ClubMemberAction.fields(targetUid: String): Map<String, Any?> =
    mapOf("targetUid" to targetUid) + when (this) {
        ClubMemberAction.PromoteModerator -> mapOf("enabled" to true)
        ClubMemberAction.DemoteModerator -> mapOf("enabled" to false)
        else -> emptyMap()
    }
