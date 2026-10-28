# Explicit partner-analysis consent

New accounts and missing/legacy preferences are off. Only an affirmative choice
for version 1 with a server-recorded consent timestamp permits partner insight
collection or route heat aggregation. Historical true values created by the old
provisioning default are not promoted to consent. No bulk data migration is
required or run. Users may explicitly enable the described scope in updated iOS
or Android onboarding/Privacy. Copy explains partner interactions and processing
of the preceding 90 days of saved routes into thresholded, endpoint-trimmed
aggregates. Opting out leaves ordinary partner content/actions available.

Rules require request.time for an enabling write. Older clients can still opt
out, but cannot silently re-enable with only the old boolean. Deploy the backend
and rules together in an approved window; deliver both updated clients before
advertising the ability to opt in again. Historic aggregate/event retention is
separate from enabling new contributions. Do not backfill consent or claim that
existing users accepted version 1.

This changes backend collection and potentially billable Functions deployment on
main merge. Owner approval is required before merging or deploying; this PR does
not run a migration, live write, store release or production query. Validate the
combined rules/indexes/backend and device setting writes before release.
