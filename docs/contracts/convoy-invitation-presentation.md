# Foreground convoy invitation card

Both native apps present the compact identity card above visible session controls
and bottom navigation. The shell owns it across tab changes. It offers **Join**
and **Decline** using the existing convoy response callable and membership rules.
The exact invitation is validated by that callable, independently of the capped
convoy history page; older pending invitations must remain directly actionable.
Accepting hands off to the map and the existing server-started Live session.

The card has a **30-second monotonic deadline from first presentation**. Repeated
snapshots and tab changes do not renew it; Android also saves it across Activity
recreation. One card is visible at a time; new invitations wait in a local queue
and each receives its own deadline. Old items in the initial inbox snapshot do
not create a series of foreground cards. Read/deleted/answered/ended invitations
are removed, with a listener on the displayed convoy validating its membership.

Timeout changes only local presentation. It must not send a decline, delete the
inbox item or mark it read. The invitation remains actionable from Notifications
while the convoy invitation is valid. An action already in flight survives expiry;
completion is keyed by notification ID so it cannot dismiss a later card. Buttons
are single-flight, failure permits retry, and successful answers mark the matching
notification read. Opening Notifications suppresses foreground cards.

## Additive inbox fields

The existing `category: convoy_invite` and `relatedEntityId: convoyId` are retained.
The server's shared create/invite producer additionally writes:

```json
{
  "convoyInvitation": {
    "inviterUid": "authenticated-inviter-uid",
    "inviterDisplayName": "Linnea Andersson",
    "inviterAvatarPath": "profileImages/inviter/avatar"
  }
}
```

The display name and avatar path may be null. The inviter is the authenticated
actor, including when an accepted member other than the owner invites more people.
Only convoy-invite notifications carry this object. Notification preference and
access checks continue to run in the existing notification writer.

Older deployed producers/notifications remain supported: both apps display the
original preview text without parsing it or attributing it to the convoy owner.
Avatar resolution uses the existing platform storage URL mechanism with the usual
person icon fallback. Swedish and English copy comes from localization contracts.
The additive fields require deployment of the updated convoy create/invite
functions; this source change does not itself deploy Functions or release apps.
