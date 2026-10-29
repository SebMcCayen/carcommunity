# Shared club logo: Storage cross-service permission

This operational correction supplies the IAM prerequisite for the **existing**
`clubLogos/{clubId}/{ownerUid}/{filename}` Storage rule's Firestore reads. It needs
no Storage/Firestore rules redeployment, backend deployment, app release or data
migration. The Android source-size/crop/error-message correction is separate.

## Reviewed scope

| Field                      | Fixed value                                                                          |
| -------------------------- | ------------------------------------------------------------------------------------ |
| Project                    | `kungsbacka-car-community`                                                           |
| Project number             | `187456469503`                                                                       |
| Bucket                     | `kungsbacka-car-community.firebasestorage.app`                                       |
| IAM member                 | `serviceAccount:service-187456469503@gcp-sa-firebasestorage.iam.gserviceaccount.com` |
| Only role to add           | `roles/firebaserules.firestoreServiceAgent`                                          |
| Role's expected permission | `datastore.entities.get`                                                             |

Read-only inspection on 2026-10-29 found the Storage agent's normal
`roles/firebasestorage.serviceAgent` grant but no cross-service Firestore grant.
The deployed combined Storage ruleset was
`7035c9ce-0a78-4e01-b83a-4d225ca0e9e9`, released 2026-10-28T15:34:24.552689Z,
SHA256 `ed288a02b7f74777e5d4dc18f840a8bf81b2b1fbe4020c5d5c622f06a83ccaeb`.
Its logo block matches base `b109701`. These are dated observations, not a claim
about state when this procedure is run. The script rereads production metadata.

Firebase documents the Storage service agent's **Firebase Rules Firestore Service
Agent** role as the cross-service prerequisite. The normal Storage role alone
does not supply it. Two Firestore document reads are permitted; this logo rule
uses exactly the canonical user and authoritative club. Retain the existing
owner, active-account, active-v2-club, immutable-path, MIME and 256 KiB checks.

## Review and execution

Prepare this as a separate PR. Review and merge through the normal PR checks
before the parent executes the approved operational change. Existing explicit
authorization for this necessary narrow shared-club Firebase correction persists;
this procedure adds no redundant approval gate. This commit does not execute it.

Use Node 24+ and the installed Google Cloud CLI with the operator's already
authorized credentials. Keep Cloud SDK configuration/logs outside the repository.
The script never logs in, changes the selected account, retrieves access tokens,
enables APIs, impersonates an account, writes a policy file or changes SDK config.
It uses the operator's existing CLI authentication context; verify that context
privately before execution. Do not enable HTTP/debug logging or pipe raw IAM
policies into repository files or PR comments.

From the reviewed checkout, run the default **read-only production preflight**:

```sh
node scripts/ensure-club-logo-storage-permission.mjs \
  --gcloud=/home/sebmccayen/google-cloud-sdk/bin/gcloud
```

It reads project metadata, raw bucket metadata, the predefined role and the full
project IAM policy. It requires the exact active project ID/number, exact bucket
and owner project number, exact one-permission role, an existing unconditional
Storage service-agent grant and a policy etag with visible conditions. Output
contains only the fixed scope above, mode and status; full policies and CLI
stderr stay in process memory. A missing prerequisite reports `would-grant`.

The predefined-role metadata read is global: it uses
`gcloud iam roles describe roles/firebaserules.firestoreServiceAgent` with
`--billing-project=kungsbacka-car-community` for quota attribution and **without**
`--project`. That command treats `--project` as the parent of a custom role and
rejects a predefined `roles/...` ID when it is supplied. Project/bucket/IAM
operations retain the fixed `--project=kungsbacka-car-community` flag. No CLI
authentication or configuration is changed to make this distinction.

Failures include a fixed stage label (for example `[predefined-role-metadata]`)
and an allowlisted class such as `predefined-role-scope`, `permission-denied`,
`invalid-argument`, `authentication`, `not-found`, `conflict`, `timeout` or
`invalid-json`. The class is selected from captured output in memory; no raw
excerpt is printed. Plan and readback validation have separate labels. A failure
at `iam-grant` or later explicitly warns that IAM may already have changed.

Compatibility validation on 2026-10-29: project and bucket metadata reads passed;
the original predefined-role read failed with the CLI's argument-scope error
(`predefined-role-scope`), because the shared flags appended `--project`. After
using global role scope with the fixed billing project, one real **read-only**
dry-run exited 0 with `status: "would-grant"` and `apply: false`. No grant was
applied. The updated 22-case no-cloud suite passed, including the real CLI's
predefined-role argument restriction and redacted stage/class reporting.

After PR review/merge, the parent can execute that same audited scope explicitly:

```sh
node scripts/ensure-club-logo-storage-permission.mjs \
  --gcloud=/home/sebmccayen/google-cloud-sdk/bin/gcloud --apply
```

The only mutation delegates to the standard CLI operation:

```sh
gcloud projects add-iam-policy-binding kungsbacka-car-community \
  --project=kungsbacka-car-community \
  --member=serviceAccount:service-187456469503@gcp-sa-firebasestorage.iam.gserviceaccount.com \
  --role=roles/firebaserules.firestoreServiceAgent --condition=None \
  --quiet --verbosity=error --format=json
```

The displayed low-level command explains the operation; use the wrapper so that
its checks and output redaction are retained. The wrapper captures this command's
JSON rather than printing it. Project, member, role and condition have no CLI
override. It never grants Editor/Admin or modifies a user principal.

`add-iam-policy-binding` performs a fresh read, merges the single binding and
submits the policy with its etag. It preserves the latest policy, rather than
replacing it with the wrapper's earlier preflight snapshot. A concurrent change
cannot silently be overwritten by a stale etag. The wrapper never calls
`set-iam-policy` or retries a failed mutation itself.

An already-present unconditional target grant returns `already-present` without
any mutation, including under `--apply`. A conditional-only target grant causes
an error instead of silently widening its scope. After apply, the wrapper checks
the returned policy and a fresh readback: the exact unconditional grant must be
present, all earlier bindings/members/conditions must survive, and existing audit
configuration/other policy metadata must remain unchanged. Additional concurrent
grants can survive. Policy version/etag normalization is allowed. Success reports
`verified-after-apply`, which confirms IAM readback, not upload success.

If a command times out, fails or returns unexpected JSON, the grant may already
have committed. Rerun the default read-only check before any retry. On a
preservation mismatch, stop and review concurrent IAM activity privately; do not
restore a saved policy or automatically remove this grant. Existing club/route
rules may depend on it. Never force an update, broaden roles or relax rules to
make this check pass. IAM propagation can take time after a successful readback.

## Acceptance and limits

No production upload resolution is claimed until a real active club owner
completes **both** Storage upload and the subsequent `clubsManage` logo update.
Record stage and sanitized exception code/HTTP status, not images, user text,
object paths containing user IDs, download tokens or credentials. Verify the
installed app's project/bucket against the fixed values and its fresh `.jpg`
owner path, `image/jpeg` and 1..262144-byte output.

Storage App Check was `UNENFORCED` at inspection; Driver club callables enforce
App Check independently. If upload succeeds but linking fails, inspect callable
attestation and the Driver default-bucket metadata reader. Its generic
`failed-precondition` can hide metadata 403/404 errors. The registered Android
config and Driver function bucket agreed during inspection; installed APK and
real-owner completion remain separate acceptance evidence. Do not insert fake
production data for this procedure.

## Local validation only

```sh
node scripts/run-no-cloud.mjs -- node --test scripts/ensure-club-logo-storage-permission.test.mjs
```

Tests use synthetic in-memory metadata/policies and mock subprocess execution.
They exercise wrong scope/role/identity rejection, malformed/conditional policy
handling, dry-run/no-op behavior, exact apply arguments, preservation/readback,
concurrent additions, conflicts, redaction and the no-cloud guard. They execute
no live IAM operation and require no emulator, Gradle or iOS changes.

## Official references (verified 2026-10-29)

- [Firebase cross-service permissions](https://firebase.google.com/docs/rules/manage-deploy#manage_permissions_for_cross-service_cloud_storage_security_rules)
- [Storage rule Firestore reads and two-document limit](https://firebase.google.com/docs/storage/security/rules-conditions#enhance_with_cloud_firestore)
- [Firebase Rules Firestore Service Agent role](https://docs.cloud.google.com/iam/docs/roles-permissions/firebaserules)
- [Standard project binding command and explicit condition=None](https://docs.cloud.google.com/sdk/gcloud/reference/projects/add-iam-policy-binding)
- [Raw bucket metadata flag](https://docs.cloud.google.com/sdk/gcloud/reference/storage/buckets/describe)
- [Global predefined-role metadata versus custom-role project scope](https://docs.cloud.google.com/sdk/gcloud/reference/iam/roles/describe)
- [IAM policy versions and etags](https://docs.cloud.google.com/iam/docs/allow-policies)

The installed SDK's `googlecloudsdk/api_lib/cloudresourcemanager/projects_api.py`
was also inspected to verify its fresh GetIamPolicy/AddBinding/SetIamPolicy path
and preservation of the etag.
