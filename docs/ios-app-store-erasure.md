# App Store account erasure rollout

Deploy the optional expectedUid guard and event-content purge before distributing
the new iOS client. Existing clients retain their request shape. iOS captures the
current Firebase User, asks Apple to authorize that same provider subject again,
reauthenticates it, revokes the Apple authorization code through Firebase, and
then requests backend erasure. No authorization code is persisted or logged.
Cancelled authorization/account replacement does not call deletion. An uncertain
backend response retries the same bound operation without repeating successful
Apple revocation in that route. The server independently rejects a replaced UID.

The shared purge now deletes the person's event messages and RSVP documents,
including orphaned event parents, without deleting other users' content. Existing
RSVP triggers decrement surviving parent counters. Legacy RSVP rows have no UID
field; a backend-only document-name cursor in accountPurgeProgress advances
atomically with each page. A bounded pass reads at most 4,000 RSVP documents;
large backlogs resume on the next existing purge run. Confirm collection size and
completion latency before rollout. Private audit/moderation evidence retention
still requires an operator-approved legal basis and policy; it is not silently
removed. No permissive rule or client read of purge progress is added.

This PR triggers potentially billable Functions deployment when merged. Do not
merge/deploy, run a live purge or create an Apple account without owner approval.
Publish/verify the new message author index before relying on its production
query. Emulator evidence does not verify live Apple token revocation.

Operator actions: complete Apple OAuth Services ID/team/key configuration in
Firebase, test cancellation/wrong Apple identity/reauthentication/revocation and
backend retry on a disposable signed-iPhone account, agree the actual retention
policy and provide a confirmation when erasure completes. The retained private
processed request is the operational proof; no public UID lookup is exposed.
No completion email service is provisioned by this change.
