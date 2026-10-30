# Personal club calendar request-log prerequisite

The helper defaults to a read-only metadata plan. After PR review/merge, the
parent can use explicit `--apply` to create **one independent named exclusion**
on project `_Default`, provided the entire owner/sink inventory is safe. No live
apply is performed by this implementation task.

`projects.exclusions.create` requires a name not already used in its parent.
Creating this independent resource does not read/replace a sink's exclusions
array or modify any existing exclusion. The server arbitrates name uniqueness;
HTTP 409 leads to exact named-resource verification, never update or overwrite.
Sink etag CAS is unnecessary for this additive operation. This supersedes the
initial planner's overly broad `sink-etag-cas-unavailable` blocker. Existing
resources still have no documented etag update protection, so updating/deleting
an exclusion or rewriting a sink remains forbidden here.

Necessary safe shared-club Firebase operations remain authorized. Parent
review/merge precedes production execution; this procedure adds no redundant
approval request. The worker must not execute live `--apply`.

## Exact exclusion and scope

- Project: `kungsbacka-car-community`, number `187456469503`.
- Region: `europe-west1`.
- Function registry: `clubCalendar.feed`; deployed function ID `clubCalendar-feed`.
- Expected Cloud Run service: `clubcalendar-feed`.
- New exclusion name: `club-calendar-feed-request-url`.

The HTTP feed is separate from the `clubCalendar-manage` callable. Its enabled
exclusion uses this fixed predicate:

```text
logName="projects/kungsbacka-car-community/logs/run.googleapis.com%2Frequests"
AND resource.type="cloud_run_revision"
AND resource.labels.project_id="kungsbacka-car-community"
AND resource.labels.location="europe-west1"
AND resource.labels.service_name="clubcalendar-feed"
```

No token, URL, path, status, method, severity or sampling condition is needed.
All future revisions and all statuses/severities of this feed's request stream
are covered. Application stdout/stderr errors/events, audit/system logs, calendar
management and other functions are unaffected. Keeping ERROR request records
would keep their secret URLs. Application code must independently avoid logging
request/token/error objects.

## Observations and current acceptance limits

Read-only inspection on 2026-10-29 found the correct active project, no parent,
only `_Required` and `_Default`, and zero exclusions. No other project exports
were returned; pagination completed. Existing control metadata verified
`driver-clubsManage` → `driver-clubsmanage`, `driver-clubsGet` → `driver-clubsget`,
and `crownHunt-submitClaim` → `crownhunt-submitclaim`. These establish the mixed-case
mapping pattern; the feed itself was not deployed. Verify its actual
`serviceConfig.service` after deployment before activation.

Those dated facts are not future guarantees: the helper rereads all metadata.
Initial checks found relevant Logging permissions, but create-only execution
specifically requires `logging.exclusions.create`, plus metadata permissions
`logging.sinks.list`, `logging.exclusions.list` and `logging.exclusions.get`.
It never needs sink update, exclusion update/delete, IAM modification or API
enablement. Missing permissions are reported, not repaired.

No log entries, request URLs, user content or calendar bearer are read by this
procedure. No real feed tokens were active in the inspected rollout; there is no
history deletion/cleanup step. Exclusions do not erase historical entries.

On 2026-10-30 local time, the create-only helper's real **read-only** dry-run
exited 0 with `would-create`, `canCreate:true`, no blockers or missing permissions,
verified control mappings and an undeployed feed. A private backup was created;
no exclusion was applied. Mock-based apply/conflict/preservation tests are a
separate evidence layer and do not claim live create or provider acceptance.

## Plan, verify, and parent-only apply

From the reviewed checkout:

```sh
node scripts/configure-club-calendar-request-logging.mjs \
  --gcloud=/home/sebmccayen/google-cloud-sdk/bin/gcloud
```

A safe missing exclusion reports `would-create`, exit 0, with
`loggingCoverageVerified:false`. `--verify` requires complete existing coverage
and returns exit 2 if missing or blocked. Exit 1 indicates an operational or
verification failure. Full configuration, export destinations, identities and
API error bodies are never echoed. Custom sinks receive ordinal labels.

Only the parent, after reviewed PR merge, may execute:

```sh
node scripts/configure-club-calendar-request-logging.mjs \
  --gcloud=/home/sebmccayen/google-cloud-sdk/bin/gcloud --apply
```

The only possible configuration write is:

```text
POST https://logging.googleapis.com/v2/projects/kungsbacka-car-community/exclusions
{"name":"club-calendar-feed-request-url","filter":"<the exact fixed predicate above>","disabled":false}
```

The wrapper constructs the actual full predicate; it accepts no project, sink,
name, predicate, body or destination override. Use the wrapper, not a manually
constructed request containing credentials. Access tokens remain in memory.
No sink PATCH/PUT, exclusion update/delete, bulk policy write, rollback or
automatic POST retry is exposed.

Apply requires all of the following:

1. Exact active project ID/number and no organization/folder parent. Parent
   presence is blocked because ancestor aggregate exports need separate review.
2. A complete paginated inventory containing only `_Required` and `_Default`,
   including `_Required`'s documented audit-only predicate. **Any extra sink,
   including disabled sinks or apparently covered exports, blocks automatic
   create**. Creating a project exclusion cannot protect another sink.
3. Matching project exclusion-list, `_Default` exclusion-list and named-resource
   GET inventories, and the existing metadata/create permissions. The documented
   exclusion capacity must permit addition.
4. An exclusive private backup, a fresh pre-create inventory, unchanged project
   ownership/sink settings and preservation of every old exclusion. If another
   actor already created the exact named exclusion, return a verified no-op.
5. At most one independent create. A same-name disabled/different exclusion
   blocks without modification. On 409, GET must show the exact enabled name and
   predicate; existence alone is never success.
6. Fresh named GET and complete post-create inventory. Every prior sink setting,
   destination, writer identity and old exclusion must survive. `_Default`'s
   output-only update time may change because its child exclusion was added.
   Independent concurrent exclusion additions are retained. New exports, changed
   parents/settings or edited/removed old exclusions cause verification failure.

If create commits but a concurrent hierarchy/config change is observed, stop
without claiming verified coverage and **do not roll back**. The new exclusion
may remain; nothing is deleted. If POST times out, errors or returns unexpected
JSON, do not retry automatically: run `--verify` and inspect metadata privately.
Re-running apply is idempotent when the exact resource already exists.

## Private backup and readiness

Metadata backups are stored outside repositories under
`/tmp/cc-club-calendar-logging-*`: directory `0700`, `metadata.json` `0600`.
`--backup-dir=/absolute/existing/private/directory` selects another location.
Git checkouts and symlink paths resolving inside them are rejected. The original
and immediate pre-create snapshots each receive a private backup before POST.
Never attach raw backups to a PR, print them or restore a whole saved config.
They contain private export configuration but no access token or log entries.

`loggingCoverageVerified` confirms only that metadata snapshot and narrow Logging
route coverage. `feedMappingVerified` requires the deployed feed's actual service
mapping. `activationReady` remains false: the parent/Zeno own
`config/sharedClubs.calendarSubscriptionsReady` and must complete upstream
proxy/load-balancer and disposable-token acceptance before activating it.
No automatic readiness/config write is part of this helper.

## Focused no-cloud validation

```sh
node scripts/run-no-cloud.mjs -- node --test scripts/configure-club-calendar-request-logging.test.mjs
```

Synthetic tests cover stream boundaries across statuses/severities, the exact
POST/body/explicit-apply boundary, 409 match and conflict, preservation of existing
exclusions and unrelated settings, concurrent additions/parents/exports, missing
permissions, pagination, private backups, redaction and uncertain-outcome handling.
No production apply, emulator, Gradle, new paid infrastructure, broad logging
change or history deletion is part of validation.

## Official references

- [Independent project exclusion creation in Default](https://docs.cloud.google.com/logging/docs/reference/v2/rest/v2/projects.exclusions/create)
- [Create request requires a name unused in its parent](https://docs.cloud.google.com/java/docs/reference/google-cloud-logging/latest/com.google.cloud.logging.v2.ConfigClient)
- [Named exclusion GET](https://docs.cloud.google.com/logging/docs/reference/v2/rest/v2/projects.exclusions/get)
- [LogExclusion schema](https://docs.cloud.google.com/logging/docs/reference/v2/rest/v2/projects.exclusions)
- [Cloud Run request stream and service labels](https://docs.cloud.google.com/run/docs/logging)
- [Independent sink routing and Required audit predicate](https://docs.cloud.google.com/logging/docs/routing/overview)
- [Sink inventory scopes/pagination](https://docs.cloud.google.com/logging/docs/reference/v2/rest/v2/projects.sinks/list)
