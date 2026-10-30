package com.kungsbackacarcommunity.app.clubs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.kungsbackacarcommunity.app.design.KccButton as Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import com.kungsbackacarcommunity.app.design.KccOutlinedButton as OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R

/** Expired/malformed invitations never remain actionable while the page is open. */
internal fun pendingClubInvitations(invitations: List<ClubInvitation>, now: Long): List<ClubInvitation> =
    invitations.filter { (clubInstant(it.expiresAt) ?: Long.MIN_VALUE) > now }
        .distinctBy { it.clubId }

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ClubInvitations(
    invitations: List<ClubInvitation>, now: Long, busy: Boolean, canAccept: Boolean,
    onRespond: (ClubInvitation, Boolean) -> Unit,
) {
    val pending = pendingClubInvitations(invitations, now)
    if (pending.isEmpty()) return
    Text(stringResource(R.string.clubs_invitations), style = MaterialTheme.typography.titleMedium)
    pending.forEach { invitation ->
        Card(Modifier.fillMaxWidth().testTag("club-invitation-${invitation.clubId}")) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(invitation.clubName, style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.clubs_invitation_expires, clubDate(clubInstant(invitation.expiresAt)!!)),
                    style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onRespond(invitation, true) }, enabled = !busy && canAccept,
                        modifier = Modifier.testTag("club-invitation-accept-${invitation.clubId}")) {
                        Text(stringResource(R.string.clubs_accept))
                    }
                    OutlinedButton(onClick = { onRespond(invitation, false) }, enabled = !busy,
                        modifier = Modifier.testTag("club-invitation-decline-${invitation.clubId}")) {
                        Text(stringResource(R.string.clubs_decline_invitation))
                    }
                }
            }
        }
    }
}
