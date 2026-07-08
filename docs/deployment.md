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
| `validate-functions.yml`  | `functions/**`, `firebase.json`                                     | Functions lint, typecheck, unit tests, build                         |
| `test-firebase-rules.yml` | `firebase/*.rules`, `firebase/*.json`, `functions/src/**/*.emulator.test.ts` | Firebase Emulator integration tests (Firestore, RTDB, Storage rules) |
| `validate-admin-web.yml`  | `apps/admin/**`, `packages/shared/**`                                        | Admin web lint, typecheck, tests, build                              |
| `codeql.yml`              | All paths                                                                    | CodeQL security analysis (JS/TS)                                     |
| `dependency-review.yml`   | Pull requests only                                                           | Dependency vulnerability review                                      |

Functions are **not deployed** from validation workflows. Deployments are intentional, require GitHub environment protection, and are triggered separately.

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
