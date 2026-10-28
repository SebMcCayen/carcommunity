# Public moderation: admin contract and rollout

The new `/moderation` queue and the member card's **Meddelanden** tab cover `events/{eventId}/messages/{messageId}` and `communityChat/global/messages/{messageId}`. Event context is explicitly labelled. No conversation, convoy or private club chat is included. Known private report IDs are rejected rather than joined to another message.

## Read contract

- `admin.listPublicReports`: server-side status/source filters and two timestamp+nanos+document-reference cursor frontiers. It does not scan a latest-200 window. Counts shown in the UI are explicitly **inlästa**, never an invented total. Search applies to the loaded rows and is labelled accordingly.
- `admin.listMemberMessages`: exact author UID, two bounded sources, including unreported messages. Event collection-group results must have the exact canonical event path; a future collection reusing its author field fails closed before projection. No generic collection-group message contents are projected.
- `admin.getPublicModerationCase`: current canonical message, a report bound to that exact source/message, one strictly earlier message in the same chat, and up to ten scoped audit records. No raw report snapshot from another surface is returned. Timestamp and document update versions bind the decision evidence.
- Every read requires fresh authoritative admin and CarCommunity access. Missing/deleted text is labelled as unavailable/removed; there is no restoration control.

## Decision contract

`admin.decidePublicModeration` requires a reason (1–500 characters), current actor UID, reviewed version and durable retry key. Canonical role, product access and verified identity blocks are checked inside the transaction, including receipt replay. Message/report/audit/receipt writes commit together. Changed evidence fails with `failed-precondition` and requires review again. Reusing a receipt for a different payload fails.

Actions: take under review, resolve, dismiss, allow an event message, remove, and resume quote cleanup. Only the selected report transitions. Other reports remain visible until reviewed; removing text is not falsely described as reviewing every reporter's case. Event removal blanks the member-readable body and marks `removed`. Public community removal hard deletes the canonical message for existing native listeners. The original removed text remains in the server-written, admin-readable audit evidence. Member history still projects only its existing safe fields.

Legacy `/event-chat` redirects to the new queue. Public community reports in the older general-report page link into the reviewed flow; they no longer offer a reason-less direct deletion or direct status write. Existing non-public report workflows are unchanged.

## Quotes and retries

New public replies read and snapshot their parent inside the same transaction as their own creation. A concurrent parent deletion invalidates/retries that read, so the removed quote cannot be recreated by the posting race. Both the new action and the legacy deletion callable redact stored public reply snapshots in a bounded page (200 replies per invocation, 50 writes per transaction). Reply bodies and all private channels remain untouched. A backend-only deletion marker prevents delayed keyed sends from recreating a moderated message ID; keyed posts pay one marker read. Each cleanup transaction also fences a recreated canonical parent. Deletion markers contain only the removed message ID and server removal time.

`cleanup.hasMore` is an explicit partial-completion result. Retrying the same reviewed decision resumes redaction without duplicating the audit decision. After reloading, an admin can use **Rensa kvarvarande citat** against freshly reviewed, already removed public text. Cleanup refuses a still-visible parent. The legacy endpoint also returns the cleanup result; new admin flows use the resumable reviewed endpoint.

No server can erase text from an offline device that has not connected again, and old screenshots or human copies are outside this contract. Native online SDK reads/listeners must receive the removed parent and updated reply documents. Already-deleted historical parents are not automatically scanned/backfilled in production: any historical cleanup requires a separate bounded read-only inventory, explicit cost/retention decision and owner-approved write. No new scheduled job or per-message trigger is introduced.

## Release gates

Deploy the added indexes and wait for readiness before deploying the four new admin callables. Deploy the public reply posting correction with the deletion handlers, then the web UI. Keep the existing callables for older clients. No shared Firebase Auth configuration changes are needed.

The code, isolated emulators, browser acceptance, and native SDK CI are separate evidence layers. Production deployment, production-data inventory/backfill, physical device/offline acceptance and store release are not implied by local tests. Use the owner-approved admin rollout document and the stage-7 decision before production changes.
