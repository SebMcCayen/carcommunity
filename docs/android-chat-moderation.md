# Android public chat moderation

Long-pressing a Community or event message preserves the normal message actions
and adds **Delete message** and **Pause writing…** for an active, server-verified
CarCommunity administrator. Other chat surfaces do not gain moderation rights.

Deleting a message offers two independent, initially unselected additions:
warn the author and pause their writing. Pausing without deletion offers the
optional warning. The administrator supplies a reason and, when applicable, a
positive integer duration in minutes or days. A day is 24 hours; the maximum is
30 days. A review step lists the complete decision before final confirmation.
Cancelling does not apply any part of the decision.

## Scope

A pause prevents new messages in **Community and event chats only**. Reading,
private messages, convoys and the shared Driver club chat keep their existing
permissions. The pause is separate from account suspension and from personal
blocking. Server time determines expiry; deleting an expired record is not
required for writing to resume.

## Authority and consistency

The client hides admin actions until the server confirms the signed-in actor's
capabilities for the selected message. That display is not an authorization
grant: the decision endpoint independently verifies the admin claim, current
active role, product access, target, and expected version in its transaction.
Message locations are restricted to canonical Community/event paths and the
author comes from the stored message, never a client-supplied target UID.

Deletion, optional warning, optional pause, audit events and the operation receipt
commit together. Retries use a stable idempotency key and cannot duplicate a
warning or extend a pause. Conflicting decisions require fresh review. Existing
owner/self-sanction guards remain in force. An administrator can remove their
own public message, but cannot warn or pause themselves through that decision.

Public message creation checks the server-owned restriction in the same
transaction as the new message. This serializes a new send with a concurrent
pause. A message committed before the pause is not retroactively rejected.
Direct client writes to restrictions, moderation state and audit records are
denied. The member can read their own minimal pause status and expiry.

## Validation and delivery

The regression suite covers normal users and forged requests, stale admin
claims after role removal, suspended/deleted actors, product access withdrawal,
target and scope substitution, direct Firestore writes, combined-decision
atomicity, idempotency, stale versions, duration validation, expiry, and actual
PM/convoy sends while public writing is paused. Android tests cover menu
visibility, optional selections, unit conversion, review/cancellation, and
failure handling.

Deploy the server functions and Firestore rules before distributing the new
Android client. Existing clients are subject to the same server-side public
write check. Rolling back the UI must not remove enforcement for active pauses.
No production deployment or store release is part of the implementation task.
