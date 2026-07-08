#!/usr/bin/env bash
# One-time provisioning of Workload Identity Federation for the GitHub Actions
# production deploys (docs/deployment.md — "Setting up Workload Identity
# Federation"). Without this, deploy-firebase-functions.yml and
# deploy-firebase-hosting.yml fail at the auth step because the WIF_PROVIDER /
# WIF_SERVICE_ACCOUNT secrets have nothing to point at.
#
# Run with gcloud authenticated as an owner / IAM admin of the project —
# easiest is Google Cloud Shell (https://shell.cloud.google.com), which has
# gcloud preinstalled and authenticated. Idempotent: safe to re-run; existing
# resources are left as-is and role bindings are additive.
set -euo pipefail

PROJECT_ID="kungsbacka-car-community"
PROJECT_NUMBER="187456469503"
REPO="SebMcCayen/carcommunity"
POOL_ID="github-actions"
PROVIDER_ID="github"
SA_NAME="github-deploy"
SA_EMAIL="${SA_NAME}@${PROJECT_ID}.iam.gserviceaccount.com"

gcloud config set project "${PROJECT_ID}" >/dev/null

echo "==> 1/5 Workload Identity Pool: ${POOL_ID}"
if ! gcloud iam workload-identity-pools describe "${POOL_ID}" --location=global >/dev/null 2>&1; then
  gcloud iam workload-identity-pools create "${POOL_ID}" \
    --location=global \
    --display-name="GitHub Actions"
else
  echo "    already exists"
fi

echo "==> 2/5 GitHub OIDC provider: ${PROVIDER_ID} (repo + main-branch restricted)"
# The attribute condition limits token exchange to this repository AND the
# main branch (docs/deployment.md step 3) — workflow_dispatch runs from main
# carry the same ref, so manual re-runs still work.
if ! gcloud iam workload-identity-pools providers describe "${PROVIDER_ID}" \
      --location=global --workload-identity-pool="${POOL_ID}" >/dev/null 2>&1; then
  gcloud iam workload-identity-pools providers create-oidc "${PROVIDER_ID}" \
    --location=global \
    --workload-identity-pool="${POOL_ID}" \
    --display-name="GitHub OIDC" \
    --issuer-uri="https://token.actions.githubusercontent.com" \
    --attribute-mapping="google.subject=assertion.sub,attribute.repository=assertion.repository,attribute.ref=assertion.ref" \
    --attribute-condition="assertion.repository == '${REPO}' && assertion.ref == 'refs/heads/main'"
else
  echo "    already exists"
fi

echo "==> 3/5 Deploy service account: ${SA_EMAIL}"
if ! gcloud iam service-accounts describe "${SA_EMAIL}" >/dev/null 2>&1; then
  gcloud iam service-accounts create "${SA_NAME}" \
    --display-name="GitHub Actions Firebase deploy"
else
  echo "    already exists"
fi

echo "==> 4/5 Least-privilege deploy roles (docs/deployment.md)"
# roles/datastore.viewer: firebase-tools' functions-deploy preflight reads the
# Firestore database metadata (the codebase has Firestore-triggered functions),
# so a read-only Datastore role is required or deploy fails with a 403 on
# GET .../databases/(default).
# roles/cloudscheduler.admin: the codebase has scheduled (cron) functions;
# deploying them upserts Cloud Scheduler jobs (cloudscheduler.jobs.update),
# which cloudfunctions.developer does not grant — deploy 403s on the
# firebase-schedule-* jobs without this.
for role in roles/cloudfunctions.developer roles/firebasehosting.admin roles/datastore.viewer roles/cloudscheduler.admin; do
  gcloud projects add-iam-policy-binding "${PROJECT_ID}" \
    --member="serviceAccount:${SA_EMAIL}" \
    --role="${role}" \
    --condition=None >/dev/null
  echo "    ${role}"
done
# Act-as on the Cloud Functions runtime service account — gen2 functions run
# as the default compute service account.
gcloud iam service-accounts add-iam-policy-binding \
  "${PROJECT_NUMBER}-compute@developer.gserviceaccount.com" \
  --member="serviceAccount:${SA_EMAIL}" \
  --role="roles/iam.serviceAccountUser" >/dev/null
echo "    roles/iam.serviceAccountUser on ${PROJECT_NUMBER}-compute@developer.gserviceaccount.com"
# Act-as on the App Engine default SA too — firebase-tools' functions-deploy
# preflight requires iam.serviceAccounts.ActAs on {project}@appspot regardless
# of the gen2 runtime SA (deploy fails otherwise: "Missing permissions … ActAs
# on kungsbacka-car-community@appspot.gserviceaccount.com"). The appspot SA is
# created lazily on first App Engine / Cloud Functions use, so it may not exist
# on a brand-new project yet — guard so this idempotent script doesn't abort.
APPSPOT_SA="${PROJECT_ID}@appspot.gserviceaccount.com"
if gcloud iam service-accounts describe "${APPSPOT_SA}" >/dev/null 2>&1; then
  gcloud iam service-accounts add-iam-policy-binding \
    "${APPSPOT_SA}" \
    --member="serviceAccount:${SA_EMAIL}" \
    --role="roles/iam.serviceAccountUser" >/dev/null
  echo "    roles/iam.serviceAccountUser on ${APPSPOT_SA}"
else
  echo "    WARNING: App Engine default SA ${APPSPOT_SA} does not exist yet."
  echo "             It is provisioned lazily on the first App Engine / Cloud"
  echo "             Functions deploy. firebase-tools' functions-deploy preflight"
  echo "             requires roles/iam.serviceAccountUser (ActAs) on it, so the"
  echo "             first functions deploy will still fail until this binding is"
  echo "             in place. Re-run this script (it is idempotent) after the"
  echo "             first deploy provisions ${APPSPOT_SA}, or enable App Engine"
  echo "             first, then re-run — this step will then apply the binding."
fi
# If a deploy later fails with a 403 despite the roles above, the usual
# additions are roles/serviceusage.serviceUsageConsumer and roles/firebase.viewer
# (firebase-tools preflight checks) — add only what the error asks for.

echo "==> 5/5 Allow this repo's workflows to impersonate the deploy SA"
gcloud iam service-accounts add-iam-policy-binding "${SA_EMAIL}" \
  --member="principalSet://iam.googleapis.com/projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/${POOL_ID}/attribute.repository/${REPO}" \
  --role="roles/iam.workloadIdentityUser" >/dev/null

WIF_PROVIDER_RESOURCE="projects/${PROJECT_NUMBER}/locations/global/workloadIdentityPools/${POOL_ID}/providers/${PROVIDER_ID}"

cat <<EOF

Done. Finish by storing the two secrets in the GitHub 'production' environment:

  gh secret set WIF_PROVIDER --env production --repo ${REPO} \\
    --body "${WIF_PROVIDER_RESOURCE}"
  gh secret set WIF_SERVICE_ACCOUNT --env production --repo ${REPO} \\
    --body "${SA_EMAIL}"

Then re-run the failed deploys (or push to main):

  gh workflow run deploy-firebase-functions.yml --repo ${REPO}
  gh workflow run deploy-firebase-hosting.yml --repo ${REPO}
EOF
