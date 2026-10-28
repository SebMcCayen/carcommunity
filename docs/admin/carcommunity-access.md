# CarCommunity access and retained identity blacklist

This change implements product-only suspension and blacklisting. It never disables shared Firebase Auth, revokes refresh tokens, or writes `users.suspended`/`users.deleted`. Driver keeps its existing authority and shared profile/garage media contracts. Explicit account deletion continues to erase the shared account as before; this tool does not redefine deletion.

## Decisions and data

- Suspension stops the current CarCommunity account. Restore clears suspension only.
- Blacklist records the Google/Apple subjects verified by Firebase Admin providerData at decision time. SHA256 keys include the provider namespace; email/name supplied by clients are never identity selectors. The case and private identity records survive account erasure. Same-provider re-registration is denied in CarCommunity until explicit audited unlisting. A different, unlinked identity is outside this guarantee.
- Unlist clears the reviewed identity decision and does not restore a separate suspension. One case represents a decision, even when both providers were linked. The active-case list supports unlisting after the original Auth/profile was erased, without recreating that account's access metadata.
- Decisions require current admin authority inside the canonical transaction (including retries; an admitted request cannot commit after role or product authority is revoked), owner/self guards, actor UID, reviewed version/blacklist IDs, user-visible reason and a retry key. Audit, receipt and canonical changes commit atomically. Delivery/mirror retries do not repeat a committed decision. Old suspend/restore endpoints retain their payload/response shape with the new product-only semantics.
- `carCommunityAccess/{uid}` is an own-read, server-write projection. Private identity records and cases cannot be read directly by apps. Existing warning notes remain in the separate admin-only warning store. Own notices, account deletion, privacy cleanup, receipt settlement and support/diagnostics remain reachable while restricted.

Firestore checks the product record and current identity decisions. RTDB uses private versioned mirrors and checks the identity keys dynamically. Storage's two-document lookup limit requires a product projection plus canonical route/admin authority; every matching UID projection and RTDB mirror is synchronized before acknowledging a blacklist or unlist. Retryable Firestore triggers repair delivery failures. A failed decision acknowledgement must be retried with its original key. Existing live-location discovery is removed before acknowledgement.

Android and iOS gate the authenticated shell from own metadata, refresh on foreground, and fence all responses to the active UID. Offline presentation may retain the last trusted cached status, preserving existing offline driving; every online operation still passes server/rule authority. No background polling is added. Restricted views keep own warnings, support, privacy cleanup where applicable, account deletion and explicit sign-out reachable.

## Accepted residual identity-link risk (2026-10-28)

The repository owner has explicitly accepted the reported post-initialization
provider-link risk for the present delivery. A previously initialized account
can link a retained blocked Google/Apple identity and avoid the access-refresh
callables; direct SDK rules can continue trusting its old projection. Linking
then unlinking before server observation is also outside the retained-history
boundary. This is an accepted residual risk, not a completed security fix.

A complete SDK-preserving lifecycle solution needs an authoritative link-time
server boundary. Firebase's beforeUserSignedIn blocking hook is re-triggered by
provider linking and requires Authentication with Identity Platform. No platform
upgrade, blocking hook activation or change to shared Driver authentication is
authorized or performed here. See the official Firebase blocking-events
requirements: https://firebase.google.com/docs/functions/auth-blocking-events.

This acceptance does not waive the migration/compatible-app gates below or grant
approval for potentially billable deployments. Existing main-branch Functions
and Hosting workflows automatically deploy changes in this lane, so production
merge remains a controlled rollout action.

The CodeQL js/file-access-to-http report on the emulator setup was separately
inspected: it sends the checked-in public database rules to a fixed 127.0.0.1
endpoint using a validated numeric emulator port and fixed demo-test namespace.
The literal owner bearer value is emulator administration, not a production
credential. This intentional local setup is not a remote data-disclosure path;
production/remote test execution remains prohibited by the existing guards.

## Controlled rollout — manual release gates

Nothing in this PR deploys or changes production data. Missing policy defaults to strict initialization. Do not deploy new strict rules before migration and compatible app releases.

1. Deploy backend initialization, mirrors and own-access callable in an explicit preparation window. A trusted operator may set Firestore `carCommunityPolicy/access` to `enforceInitialized:false, rolloutState:'preparing'` and RTDB `carCommunityPolicy/enforceInitialized:false` during preparation. Admin clients cannot set these flags, and access mutations remain unavailable during preparation. Keep the previous rules during this step.
2. Publish and verify compatible Android and iOS releases. Confirm onboarding, ordinary access, restricted own-warning/deletion/support flows and Driver using a dedicated test account. Decide the legacy-client release policy before cutover; old clients do not have the new restricted screen.
3. Build Functions. Run `node functions/scripts/backfill-carcommunity-access.mjs --project=<explicit-project> --database-url=<deployed-database-url>` for read-only counts. Copy the exact database URL from the deployed Firebase configuration, including its regional host; do not infer it from the project ID. Review the counts and missing Auth accounts. With separate owner approval, run the same command with `--apply` and `KCC_ALLOW_ACCESS_BACKFILL=<explicit-project>`. The script changes product records/mirrors only. Deleted profiles and missing Auth accounts are skipped. Do not run this from an incomplete/mixed emulator environment.
4. Run the command with `--verify`; missing initialized records or mismatched UID mirrors fail the check. Review any retained blacklist identity mirrors and Storage projections, observe retry queues and verify indexes are ready. Reconcile existing legacy shared restrictions explicitly; these tools intentionally do not clear shared Auth claims/canonical flags.
5. Deploy/verify the new rules, set RTDB initialization enforcement true, then set Firestore policy `enforceInitialized:true, rolloutState:'ready'`. Only this state enables access decisions. Verify ordinary accounts, suspended accounts, Google/Apple same-identity re-registration and Driver in staging before production release approval.
6. If delivery fails after a committed decision, keep strict authority and repair mirrors/retry the original operation. Never roll back to a permissive policy as a way to undo a blacklist. Product restore/unlist are the audited recovery actions; preserve identity records/cases/receipts.

## Verification layers

The local emulator integration uses real Firebase Auth/Functions/Firestore SDKs, exercises Google and Apple, erases the original account, re-registers the same identity, proves CarCommunity denial and Driver/shared-auth continuity, and tests independent suspension/unlisting and stale reviewed decisions. Dedicated native SDK CI tests instantiate the production Android and iOS access repositories, observe suspension/restoration, retry decisions, and verify unchanged canonical shared restriction fields. Rule regression suites also cover the explicit pre-cutover phase; those are separate from strict access tests.

Passing source, emulator, native SDK and build checks does not constitute deployed staging, physical-device or store acceptance. The rollout above remains an explicit release gate.

## No Google Cloud or Firebase test costs

All automated test commands run through `scripts/run-no-cloud.mjs`. It requires a disposable `demo-*` project, removes cloud credential environment variables, supplies empty cloud/CLI config directories, and preloads a loopback-only Node transport guard into child test and Functions-emulator processes. Remote TCP/HTTPS and UDP are blocked; production Firebase configuration is rejected. Four independent guard tests verify remote/metadata denial, local connectivity, credential removal and project rejection.

Emulator binary and dependency preparation downloads public development tooling before guarded execution; it does not provision a Firebase or Google Cloud project. Native SDK acceptance uses fixed demo apps and emulator endpoints, with no production app configuration or service-account credentials. Test execution must not deploy, upgrade Authentication/Identity Platform, enable APIs, create billable resources or read/write the live project. Local integration acceptance additionally runs in an isolated Linux network namespace with only loopback and no external route. GitHub Actions compute is a separate service from Google Cloud/Firebase.
