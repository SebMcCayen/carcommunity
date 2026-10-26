package com.kungsbackacarcommunity.app.moderation

import androidx.compose.runtime.Composable

/** S39 local screenshot input; the actual production reading host provides title/scroll/dismiss. */
@Composable
fun MessageActionsCataloguePreview() {
    MessageActionsSheet(
        memberName = "Testmedlem", canBlock = true, reportAvailability = ReportAvailability.Wired,
        onBlock = {}, onReport = {}, onDismiss = {}, canReply = true, onReply = {},
        preview = MessageActionsPreview("Testmedlem", "Ska vi ses vid stationen ikväll?"),
    )
}
