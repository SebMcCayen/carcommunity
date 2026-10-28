# Admin redesign release candidate

This candidate collects the reviewed member tools/access/member-card work and the new public moderation, Crown Hunt overview and Swedish navigation on the current main branch. It is a reviewable release candidate, not production approval. Use this PR as the atomic integration target; the stacked PRs remain useful for reviewing individual changes. Do not merge the same stack piecemeal during cutover.

## Scope

- Four primary destinations: Översikt, Medlemmar, Kronjakten, Moderering. Existing working tools remain under Innehåll, Partners, Analys och ekonomi, Drift. Badges are discoverable as Utmärkelser. Legacy event-chat links redirect to the reviewed queue. Placeholder pages have no menu entries.
- Member card: warning lifecycle, name correction, one shared XP/Kronpoäng balance, product-only access decisions, bounded history and public messages. Missing login history is labelled as unavailable.
- Public moderation: event chat and global community messages, independent report/message states, mandatory reason and reviewed version, current actor authority, durable retry and scoped context/audit. Private DM/convoy/club chats have no new generic browsing entry point.
- Crown Hunt: one overview with area layers, manual and automatic crowns, server-derived operational diagnostics, explicit snapshot coverage, five tabs and existing management actions. No new spawner, ingestion job or scheduled polling is introduced.
- Main's shared-clubs rules and index changes are retained alongside all admin indexes.

## Evidence and its limits

The test boundary is `scripts/run-no-cloud.mjs`, fixed disposable `demo-*` SDK apps and loopback emulators. Local integration additionally runs without an external network route. No test uses the production Firebase/Google Cloud project, deployment credentials, a platform upgrade or live data. GitHub Actions compute is a separate service. This is a test execution guarantee, not an audit of the billing account.

The candidate must have passing admin build/typecheck/tests, Functions tests/contracts, all rule-emulator groups, Android build/Compose/native SDK acceptance, iOS simulator/native SDK acceptance and security checks on its final revision. Do not treat an old head's checks as final-head evidence. Provisioning failures before a macOS runner starts are infrastructure failures; source compilation failures require a source fix.

Browser QA covers synthetic data at desktop, tablet and mobile widths. Crown map QA uses the actual Mapbox GL renderer with a labelled local geometry-only style and external destinations blocked. Production Mapbox token restrictions, road tiles, App Check and deployed indexes require release-environment acceptance. Passing simulator/native SDK checks does not prove physical-device, offline, store or deployed-production acceptance.

Security: the formal moderation diff scan covers immutable head `7aeafe2e1a008ecd1ea3984e1fcaf2ee1c71f8c7` against `5598542d96dadf3890752119a8ccaab0986d9e95`; it found no introduced confirmed vulnerability. Later test-only Swift actor/cancellation fixes and integration must be reviewed as follow-ups. Do not represent that scan as a scan of the entire final integration diff. Audit evidence is server-written and admin-readable; it is not available to ordinary members.

## Owner-controlled production sequence

**Merging this candidate is a production action.** Main's independent `deploy-firebase-functions.yml` and `deploy-firebase-hosting.yml` workflows automatically deploy matching changes. Hosting does not wait for Functions or indexes. Their `production` environment configuration/reviewer protections have not been verified by a live cloud test. Do not rely on them to supply missing approval or deployment ordering.

1. Obtain the owner's explicit production approval for the exact release revision, resource changes, compatible app releases and migration window. Record the previous workflow states, known-good Hosting release, Functions revision and rule versions. Pause both the Functions and Hosting deployment workflows before merging the candidate: independent main-push deployments must not start either a strict backend before preparation or Hosting before backend/index readiness. This document does not change those controls.
2. Establish the explicitly approved Firestore and RTDB preparation policies described below before starting any new backend; absent policy defaults to strict initialization. Retain previous rules and existing restrictions. Inspect the existing policy first: if it is already strict/ready, verify its migration prerequisites and preserve it; never downgrade an existing ready policy to preparation mode. Prepare indexes from `firebase/firestore.indexes.json` and wait until the new admin/moderation query indexes are ready. Rule/index deployment is a separate operator step; the existing Functions/Hosting workflows do not deploy rules/indexes. After the controlled merge and policy/index readiness, deliberately re-enable and dispatch Functions from main. Keep Hosting paused through the remaining gates.
3. Follow **[CarCommunity access rollout](carcommunity-access.md#controlled-rollout--manual-release-gates)** exactly: preparation backend/policy, compatible Android and iOS releases, read-only backfill inventory, separate approval for apply, verify mirrors and retained identities, then strict rule cutover and ready policy. Keep previous rules until migration and compatibility gates pass. An absent policy must never be treated as permission to bypass initialization.
4. Deploy the moderation callables together with public deletion markers, reply posting checks and quote cleanup. Keep old callable contracts for installed clients. Publish the admin Hosting build only after backend/index readiness. The final navigation/overview needs no additional scheduled cloud job.
5. In the approved release environment, verify normal/restricted accounts, warning expiry/revocation, shared balance/name changes, event soft-removal, public deletion/quote cleanup and the same Google/Apple identity re-registration. Verify Driver/shared Auth continuity. Use dedicated accounts and explicitly approved bounded release checks; these are not part of the cost-free automated test run.
6. Confirm production Mapbox style/token restrictions, App Check, indexes and the actual Hosting URL; verify both supported mobile releases against the deployed contracts. Record the owner go/no-go before general availability.

Provider-link risk remains the explicitly accepted residual described in the access rollout document. A different unlinked identity is not covered by same-provider blacklisting. No shared Auth disabling, Identity Platform upgrade or blocking-hook deployment is part of this release.

## Recovery

- If the new web build fails, restore the recorded previous Hosting release. Keep additive Functions compatible while investigating; do not erase receipts, audit records, retained identity decisions or warning history.
- If mirror delivery fails after a decision commits, retain strict authority and retry the original operation key/repair delivery. Do not switch to permissive access policy to undo a blacklist. Use audited restore/unlist as appropriate.
- Rule rollback must preserve product authority and the shared Driver/club contracts. Stop cutover rather than deploying strict rules before migration or reverting to an unsafe permissive state.
- No production merge, deploy, backfill apply, signed production APK, app publication or store submission is performed by preparing this candidate. CI creates config-less test builds.
