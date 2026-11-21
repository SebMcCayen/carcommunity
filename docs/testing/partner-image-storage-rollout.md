# Partner image administration during access preparation

This temporary release updates authoritative administration of company, billboard
and offer images while preserving the existing preparation policy. It does not
activate the separate strict CarCommunity access migration or publish a mobile app.

`firebase/storage.partner-images-preparing.rules` starts with the production Storage
source inspected on 2026-11-21 (SHA256
`ed288a02b7f74777e5d4dc18f840a8bf81b2b1fbe4020c5d5c622f06a83ccaeb`). Only
the three business-image write predicates and their scoped helpers differ. A current
admin claim, unrestricted current admin/owner profile and any existing unrestricted
product projection are required. Missing/uninitialized product projections remain
compatible with preparation. Authenticated image reads remain unchanged. These are
direct administrator writes; partner managers continue to use the validated portal.

All other profile, garage, route, club and Driver rules are byte-preserved. The
canonical `firebase/storage.rules` and normal `firebase.json` remain the source for
the separately gated strict rollout. Do not deploy that complete strict file as a
shortcut for this release: see [access rollout gates](../admin/carcommunity-access.md)
and [mobile activation gates](../admin/manual-android-and-activation.md).

## Reviewed release window

1. Merge and verify the source PR. Keep the broad automatic deployment workflows
   paused, coordinate an exclusive rules-release window and use an immutable clean
   checkout of reviewed main. No other rules release may overlap the preflight and
   deploy; Firebase CLI does not provide an atomic release compare-and-set.
2. With existing operator credentials and local dependencies installed, run
   `node scripts/partner-image-storage-deploy.mjs verify`. Set `KCC_GCLOUD_BIN` to the
   existing gcloud executable if it is absent from PATH. It reads the current release
   and requires either the exact inspected baseline or the exact reviewed artifact.
   Unknown drift fails closed. It also requires the existing Firestore policy
   `enforceInitialized:false, rolloutState:'preparing'`. No token is printed.
3. After deployment authorization, run
   `node scripts/partner-image-storage-deploy.mjs deploy`. Its committed config
   selects **Storage only**, preserving Functions, Firestore, RTDB, Hosting and both
   access policies. It verifies the final released source byte-for-byte and the
   still-preparing policy. An already-current artifact is a no-op. Never use
   `--force`, replace the baseline hash, or accept unknown live drift without review.
4. Verify a real administrator's authenticated logo preview/byte read from the admin
   origin. The focused emulator suite verifies writes and revocation with disposable
   identities; production customer accounts or administrator roles are not changed
   for negative tests. Keep physical native acceptance OPEN for the next app build.

## Tests and retirement

`scripts/partner-image-storage-deploy.test.mjs` proves byte preservation outside the
scoped patch and rejection of live drift/incorrect access policy. The grouped CI
suite `partner-image-storage.emulator.test.ts` covers current administrator and owner
create/update/delete, retained claims after account changes, product restrictions,
ordinary and anonymous callers, authenticated reads, preparing routes/profile media
and Driver media. The normal rules workflow explicitly watches this artifact/config.

When the separate access migration, verification and compatible Production app gates
are complete, release canonical strict Storage rules through that rollout and remove
this temporary artifact, config, release script and associated preparation-only tests
in the same reviewed PR. The script intentionally refuses to reinstall the temporary
policy over a strict or otherwise changed live release.
