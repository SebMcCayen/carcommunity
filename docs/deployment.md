# Deployment Readiness

This document describes the planned production deployment setup for the car community platform.

## Target Platform

**Firebase** — Production only. No staging or development cloud environments are planned for MVP.

See [ADR-001](adr/001-firebase-platform.md) for the decision to migrate from Azure to Firebase.

## Service Overview

> **Note:** The table below lists _planned_ post-migration deployment targets. The current implementation uses `services/api` (Node.js container), `apps/mobile` (React Native / Expo), and `apps/admin` (React + Vite). The migration to Firebase and separate native mobile apps is in progress.

| Service                                    | Hosting                                                            |
| ------------------------------------------ | ------------------------------------------------------------------ |
| Backend functions (`functions/`) — planned | Cloud Functions for Firebase (2nd gen, Firebase-supported Node.js) |
| Admin web (`apps/admin`)                   | Firebase Hosting                                                   |
| iOS app (`apps/ios`) — planned             | App Store                                                          |
| Android app (`apps/android`) — planned     | Google Play                                                        |

## Local Development

Firebase Emulator Suite provides a full local environment:

- **Functions Emulator**: runs Cloud Functions locally.
- **Firestore Emulator**: local Firestore instance.
- **Realtime Database Emulator**: local Realtime Database instance.
- **Authentication Emulator**: local Firebase Auth.
- **Storage Emulator**: local Cloud Storage for Firebase.
- **Hosting Emulator**: local Firebase Hosting preview.

Run `firebase emulators:start` to start all configured emulators.

## Security

- **No secrets are stored in this repository.**
- Firebase service account credentials and configuration must be provided through CI secrets and environment configuration at deployment time.
- Firebase App Check is required in production to protect Cloud Functions from unauthorized callers.
- Firebase Security Rules enforce data access controls on Firestore and Realtime Database.
- Admin authorization uses server-managed Firebase custom claims set exclusively by Cloud Functions.
- No credentials appear in function code or CI configuration.

## Production-Only Note

MVP infrastructure targets a single **Production** Firebase project. Separate staging, preview, or development Firebase projects are not planned at this stage. Keep changes conservative and production-safe.

Use Firebase Emulator Suite for all local and CI testing.

## CI Validation

CI runs on every push and pull request targeting `main`. Path filters ensure that unrelated changes do not trigger every workflow.

| Workflow                  | Trigger paths                                                                | What it validates                                                    |
| ------------------------- | ---------------------------------------------------------------------------- | -------------------------------------------------------------------- |
| `ci.yml`                  | All paths                                                                    | API, mobile, admin, shared lint/typecheck/test/build                 |
| `validate-functions.yml`  | `functions/**`, `firebase.json`                                              | Functions lint, typecheck, unit tests, build                         |
| `test-firebase-rules.yml` | `firebase/*.rules`, `firebase/*.json`, `functions/src/**/*.emulator.test.ts` | Firebase Emulator integration tests (Firestore, RTDB, Storage rules) |
| `validate-admin-web.yml`  | `apps/admin/**`, `packages/shared/**`                                        | Admin web lint, typecheck, tests, build                              |
| `codeql.yml`              | All paths                                                                    | CodeQL security analysis (JS/TS)                                     |
| `dependency-review.yml`   | Pull requests only                                                           | Dependency vulnerability review                                      |

Functions are **not deployed** from validation workflows. Deployments are intentional, require GitHub environment protection, and are triggered separately.

## Firestore Index Drift

Firestore composite indexes are the one part of the deploy that fails **silently in CI and loudly in production**:

- A query with no matching index fails with `9 FAILED_PRECONDITION: The query requires an index` — it does **not** return an empty result.
- The Firestore emulator auto-creates whatever index a query asks for, so the rules and functions test suites pass whether or not `firebase deploy --only firestore:indexes` was ever run.

On 2026-07-19 this took `friend-list` down for every caller: nine indexes declared in `firebase/firestore.indexes.json` had never reached the production project.

`scripts/check-index-drift.mjs` closes that gap by diffing the deployed index list against the repo file, normalising away the implicit trailing `__name__` field that the Admin API returns and the repo file omits. It reports both directions:

| Direction              | Severity           | Meaning                                                                              |
| ---------------------- | ------------------ | ------------------------------------------------------------------------------------ |
| Declared, not deployed | **fatal** (exit 1) | Queries needing the index are failing in production right now                        |
| Deployed, not declared | warning            | Leftover from a field rename or a hand-created index — costs storage, breaks nothing |

Run it locally against production. The Firebase CLI comes from the `functions` pnpm workspace, which a root `npm install` does not cover, so install that first on a fresh checkout:

```bash
pnpm -C functions install          # provides functions/node_modules/.bin/firebase
node scripts/check-index-drift.mjs --project kungsbacka-car-community
```

To diff against a captured index dump without credentials (useful when debugging the check itself):

```bash
node scripts/check-index-drift.mjs --deployed prod-indexes.json
```

The fix for fatal drift is always `firebase deploy --only firestore:indexes --project kungsbacka-car-community`. The script itself never deploys.

In CI the check is split in two, deliberately:

- `check-index-drift.yml` runs **daily on a schedule** (and on demand) in the `production` environment, because listing deployed indexes needs WIF credentials. It is not a PR check: a credentialed check on every PR would fail on fork PRs and fail closed on secret rotation, blocking merges for changes unrelated to indexes. It skips with a notice rather than failing when the WIF secrets are absent.
- The normalisation and diff logic is unit-tested in `scripts/check-index-drift.test.mjs` and runs on **every PR** through `ci.yml` (`npm run test:scripts`), with no credentials.

## Production Deployment

Production deployment uses GitHub OIDC and Google Workload Identity Federation. No long-lived Google service account keys are stored as GitHub secrets.

| Workflow                        | What it deploys              | Requirement                                       |
| ------------------------------- | ---------------------------- | ------------------------------------------------- |
| `deploy-firebase-functions.yml` | Cloud Functions              | Push to `main`, `production` environment approval |
| `deploy-firebase-hosting.yml`   | Admin web (Firebase Hosting) | Push to `main`, `production` environment approval |

### Required secrets

Configure the following secrets in the GitHub repository `production` environment:

| Secret                | Description                                                                                                                                         |
| --------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| `WIF_PROVIDER`        | Workload Identity Federation provider resource name (e.g. `projects/PROJECT_NUMBER/locations/global/workloadIdentityPools/POOL/providers/PROVIDER`) |
| `WIF_SERVICE_ACCOUNT` | Service account email used for Firebase deployment (e.g. `github-deploy@PROJECT_ID.iam.gserviceaccount.com`)                                        |

The service account must be granted only the permissions required for deployment:

- `roles/cloudfunctions.developer` — for Functions deploy
- `roles/firebasehosting.admin` — for Hosting deploy
- `roles/datastore.viewer` (read-only) — `firebase-tools`' functions-deploy
  preflight reads the Firestore database metadata (`GET .../databases/(default)`)
  because the codebase has Firestore-triggered functions, so the deploy SA needs
  Datastore read access or the deploy fails with a 403
- `roles/cloudscheduler.admin` — the codebase has scheduled (cron / `onSchedule`)
  functions; deploying them upserts Cloud Scheduler jobs
  (`cloudscheduler.jobs.update`), which `roles/cloudfunctions.developer` does not
  grant, so the deploy SA needs this or the deploy fails with a 403 on the
  `firebase-schedule-*` jobs. This role is broader than strictly needed, but
  Cloud Scheduler has no narrower predefined role that can create/update jobs
  (`roles/cloudscheduler.jobRunner` only runs existing jobs); a custom role
  limited to the `cloudscheduler.jobs.*` permissions is the stricter
  least-privilege alternative if you want to avoid granting admin
- `roles/secretmanager.admin` — some functions declare a Secret Manager secret
  via `defineSecret` (`GITHUB_ISSUE_TOKEN` in
  `functions/src/feedback/reportIssue.ts`, listed in the function's
  `secrets: [...]`). `firebase deploy --only functions` must read that secret
  (`secretmanager.secrets.get`) and set the secret's IAM policy
  (`secretmanager.secrets.setIamPolicy`) so the function's runtime SA gets
  `secretAccessor`, or the deploy fails with a 403 on `secretmanager.secrets.get`.
  Among predefined roles only `secretmanager.admin` covers both get +
  setIamPolicy. This is broader than strictly needed; granting the role (or a
  custom role limited to `secretmanager.secrets.get` + `secretmanager.secrets.setIamPolicy`) on the
  **specific** secret resource rather than project-wide is the stricter
  least-privilege alternative
- `roles/run.admin` — gen2 callable / HTTPS functions are backed by a Cloud Run
  service, and `firebase-tools` sets the invoker (`allUsers`) on that service at
  deploy so the function is reachable (auth is enforced inside the function).
  Setting the invoker requires `run.services.setIamPolicy`, which
  `roles/cloudfunctions.developer` does not grant, so the deploy fails with
  "Unable to set the invoker for the IAM policy" (surfaced by the new
  `feedback.reportIssue` `onCall` function — deployed as, and named in the
  error, `feedback-reportIssue`). This is broader than strictly
  needed; a custom role limited to `run.services.setIamPolicy` +
  `run.services.getIamPolicy` — or granting it on the **specific** Cloud Run service backing
  the function rather than project-wide `run.admin` — is the stricter
  least-privilege alternative
- `roles/iam.serviceAccountUser` (ActAs) on the gen2 Cloud Functions runtime SA
  (`{PROJECT_NUMBER}-compute@developer.gserviceaccount.com`) — to act as the
  Functions runtime service account
- `roles/iam.serviceAccountUser` (ActAs) on the App Engine default SA
  (`{PROJECT_ID}@appspot.gserviceaccount.com`) — `firebase-tools`' functions-deploy
  preflight requires `iam.serviceAccounts.ActAs` on the appspot SA in addition to
  the compute runtime SA, or the deploy fails. This SA is created lazily on first
  App Engine / Cloud Functions use, so the binding is existence-guarded.

[`scripts/setup-wif.sh`](../scripts/setup-wif.sh) is the source of truth for the
exact bindings and applies them idempotently.

### Google Cloud Secret Manager secrets

Some functions bind runtime secrets via `defineSecret` — this reads a **Google
Cloud Secret Manager** secret, not a GitHub Actions secret of the same name.
For the functions deploy to succeed, the secret must already exist in Secret
Manager, or `firebase deploy --only functions` fails when it tries to read and
bind it.

- **`GITHUB_ISSUE_TOKEN`** — used by `feedback.reportIssue` to file GitHub
  issues. Create it once with:

  ```
  firebase functions:secrets:set GITHUB_ISSUE_TOKEN --project PROJECT_ID
  ```

  and paste the fine-grained GitHub PAT (with the **Issues: Read and write**
  repository permission on `SebMcCayen/carcommunity`) when prompted. This is a GCP Secret Manager secret
  bound by `defineSecret`; it is separate from — and unrelated to — any GitHub
  Actions secret named `GITHUB_ISSUE_TOKEN`.

### Setting up Workload Identity Federation

> **This is a required one-time step — deploys fail until it is done.** Both
> deploy workflows guard for it and fail with a pointer here if the secrets
> are missing.

Run [`scripts/setup-wif.sh`](../scripts/setup-wif.sh) with gcloud authenticated
as an owner/IAM admin of the project (easiest: paste it into
[Google Cloud Shell](https://shell.cloud.google.com)). It provisions, idempotently:

1. A Workload Identity Pool (`github-actions`) in Google Cloud IAM.
2. A GitHub Actions OIDC provider (`github`) on the pool, restricted to this
   repository **and** the `main` branch via an attribute condition.
3. The least-privilege `github-deploy` service account with the roles above,
   bound to the pool so the repo's workflows can impersonate it.

The script prints the exact `gh secret set` commands for step 4: storing
`WIF_PROVIDER` and `WIF_SERVICE_ACCOUNT` as secrets in the `production`
GitHub environment.

See the [Google Cloud documentation](https://cloud.google.com/iam/docs/workload-identity-federation-with-deployment-pipelines) for background.

## Branch Protection

The `main` branch must be protected with the following rules configured in **GitHub repository Settings → Branches**:

- **Require a pull request before merging** — direct pushes to `main` are not allowed.
- **Require status checks to pass before merging** — require `ci`, `validate-functions`, `validate-admin-web`, `test-firebase-rules`, and `codeql` to pass.
- **Require branches to be up to date before merging** — prevents merging stale branches.
- **Require signed commits** — all commits must be GPG or SSH signed.
- **Do not allow bypassing the above settings** — applies to administrators as well.

## Dependency Security

| Feature                     | Configuration                                                                       |
| --------------------------- | ----------------------------------------------------------------------------------- |
| Dependabot version updates  | `.github/dependabot.yml` — weekly updates for all npm workspaces and GitHub Actions |
| Dependabot security updates | Enabled in repository settings                                                      |
| CodeQL                      | `.github/workflows/codeql.yml` — JS/TS analysis on push, PR, and weekly schedule    |
| Secret scanning             | Enabled in repository settings (GitHub Advanced Security)                           |
| Dependency review           | `.github/workflows/dependency-review.yml` — reviews dependency changes on every PR  |

Major dependency updates are not automatically merged. Dependabot groups minor and patch updates and submits major updates as individual pull requests for manual review.
