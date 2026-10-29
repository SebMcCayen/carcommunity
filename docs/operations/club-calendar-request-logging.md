# Personal club calendar request-log prerequisite

**Current result: blocked. No Logging mutation is implemented or executed.**
Cloud Logging v2's `LogSink` and `LogExclusion` have no etag. The sink update
method exposes an update mask, but no documented conditional-write/CAS
precondition. An update mask is not concurrency protection. Reading `updateTime`
twice is not CAS, and synthesizing an `If-Match` header is not a documented fix.
The requested sink-etag preservation guarantee therefore cannot be implemented
with this API. The planner refuses to weaken it or overwrite sink exclusions.

Necessary safe shared-club Firebase operations are already authorized. This is
a technical concurrency blocker, not a request for redundant user approval.
Parent review/PR merge must precede any future operational configuration change.

## Exact proposed exclusion

Function registry: `clubCalendar.feed`; deployed callable/function ID:
`clubCalendar-feed`; expected Cloud Run service: `clubcalendar-feed`, region
`europe-west1`, project `kungsbacka-car-community` (`187456469503`). The HTTP feed
is not a Firebase callable; `clubCalendar-manage` is separate and must remain
logged normally.

```text
logName="projects/kungsbacka-car-community/logs/run.googleapis.com%2Frequests"
AND resource.type="cloud_run_revision"
AND resource.labels.project_id="kungsbacka-car-community"
AND resource.labels.location="europe-west1"
AND resource.labels.service_name="clubcalendar-feed"
```

Proposed exclusion name: `club-calendar-feed-request-url`, enabled. Apply the
same exact predicate to **every non-`_Required` sink**, not just `_Default`.
The predicate never needs a token, token length, URL value, response status,
severity, revision, path, sampling function or request method. It covers future
revisions and DEBUG through ERROR/CRITICAL request records, including rejected
and failed requests, because those URLs can carry the bearer too.

This removes only this service's **request-log stream**. It retains application
stdout/stderr errors and events, system/container logs, Cloud Audit logs and all
other functions, including calendar management. Keeping the feed's ERROR request
records would retain their secret URLs; that exception must not be added.
Application code must independently avoid logging URL/token/error objects.

## Read-only evidence on 2026-10-29

- Project is active, correct number, no organization/folder parent.
- Two sinks: `_Required` and `_Default`; zero exclusions. No other project sinks
  or exports were returned and pagination was complete. Destinations/filters
  were inspected only in memory and are not reproduced here.
- Metadata verifies `driver-clubsManage` → `driver-clubsmanage`,
  `driver-clubsGet` → `driver-clubsget`, and
  `crownHunt-submitClaim` → `crownhunt-submitclaim`. The mixed-case prefix/suffix
  examples support the expected lower-case calendar service mapping. The feed
  itself returns metadata 404 because it is not deployed; verify its actual
  `serviceConfig.service` after deployment before any readiness decision.
- Existing credentials report `logging.sinks.list/get/update` and
  `logging.exclusions.list/create/update`. The blocker is unsupported sink CAS,
  not a missing role. No IAM change or API enablement is needed or attempted.
- Public Logging discovery schema confirms no sink/exclusion etag and no etag
  update parameter. Live sinks have neither etag nor `updateTime`. These dated
  observations must be refreshed; they are not permanent rollout guarantees.
- The real planner dry-run completed with exit 2, the sole blocker
  `sink-etag-cas-unavailable`, zero missing permissions, verified control mappings
  and one uncovered non-required sink (`_Default`). Private backup creation
  succeeded. This expected fail-closed result is not an applied exclusion.

No log entry, request URL, calendar bearer, user image/text, log content or secret
URL was read. Authentication output stayed in process memory.

## Planner and private backup

```sh
node scripts/configure-club-calendar-request-logging.mjs \
  --gcloud=/home/sebmccayen/google-cloud-sdk/bin/gcloud
```

Default mode performs only metadata reads, plus the read-only
`projects:testIamPermissions` query. It paginates all project sinks/exclusions
(bounded at 20 pages), verifies project identity, known deployment mappings,
`_Required`'s documented audit-only predicate and every non-required sink.
An ancestor, unreadable/incomplete inventory, mapping drift, name collision,
disabled/partial/sampled exclusion, inconsistent `_Default` inventory or missing
permission blocks verification. Disabled custom sinks also require coverage, so
simply enabling one later cannot silently bypass this inventory's exclusion.

The planner checks every export without echoing its name, destination, filter,
writer identity or error response. Custom sinks receive anonymous ordinal labels.
It accepts only the exact proposed predicate as proof of coverage; it does not
attempt to prove arbitrary Logging filter logic safe. Existing configuration is
preserved because the program has no mutation routes.

Each successful inventory produces an exclusive private metadata backup under
`/tmp/cc-club-calendar-logging-*`: directory mode `0700`, `metadata.json` mode
`0600`. `--backup-dir=/absolute/existing/directory` selects another private
location. All Git checkouts and symlink paths resolving inside one are rejected.
The printed path is for the operator; never attach the raw backup to a PR or
commit it. Backups contain private export configuration but no access token or
log entries. They support review, not blind policy restoration.

`--verify` repeats the complete inventory and all-sink coverage check. Exit codes:
0 = covered metadata snapshot; 2 = known safety/coverage blocker; 1 = failed
metadata/argument/backup check. `--apply` currently **refuses** missing coverage
with exit 2 and performs no writes. If all exclusions already match, it is a
verification-only no-op. Even synthetic/newly exposed etag fields cannot unlock
an unimplemented CAS write path.

`loggingCoverageVerified` attests only to that snapshot. `feedMappingVerified`
requires the deployed feed's exact resource mapping. `activationReady` remains
false: upstream proxies/load balancers and disposable-token acceptance are
outside this metadata-only check. Parent/Zeno own
`config/sharedClubs.calendarSubscriptionsReady`; keep it default false.

## Remaining operational design decision

Do not deploy the feed until the URL-retention prerequisite has a reviewed,
supported solution. Adding an exclusion through `gcloud logging sinks update`
rewrites the exclusions field without documented etag protection; a private
backup and readback do not make it race-safe. Creating a project exclusion is a
different, potentially reviewable create-only operation for `_Default`, but it
does **not** cover custom/ancestor exports and must not silently substitute for
the requested all-sink CAS operation. This revision implements neither workaround.

The parent must resolve the concurrency design in the reviewed operational PR,
or retain the deployment/readiness blocker. Do not add IAM, disable Logging
project-wide, change destinations/retention, create paid infrastructure, relax
App Check or replace existing configuration. A future solution must preserve all
existing exclusions, protect every retaining route, verify unchanged unrelated
settings, and re-inventory after application. No exclusion erases old logs.

## Focused no-cloud checks

```sh
node scripts/run-no-cloud.mjs -- node --test scripts/configure-club-calendar-request-logging.test.mjs
```

Synthetic fixtures cover exact stream boundaries at all statuses/severities,
other functions and audit/error streams, all exports including disabled sinks,
pagination, metadata permissions/mapping drift, false CAS evidence, private
backup modes/path guards, redaction and refusal/no-op behavior. No emulator,
Gradle, production write, new service or live log-content test is involved.

## Official references verified 2026-10-29

- [Cloud Run request logs and resource/service labels](https://docs.cloud.google.com/run/docs/logging)
- [LogSink schema: no etag](https://docs.cloud.google.com/logging/docs/reference/v2/rest/v2/projects.sinks)
- [Sink update parameters: updateMask, no CAS precondition](https://docs.cloud.google.com/logging/docs/reference/v2/rest/v2/projects.sinks/update)
- [LogExclusion schema: no etag](https://docs.cloud.google.com/logging/docs/reference/v2/rest/v2/projects.exclusions)
- [Project exclusion creation covers only Default](https://docs.cloud.google.com/logging/docs/reference/v2/rest/v2/projects.exclusions/create)
- [Sink routing and immutable Required audit predicate](https://docs.cloud.google.com/logging/docs/routing/overview)
- [Sink inventory scopes and pagination](https://docs.cloud.google.com/logging/docs/reference/v2/rest/v2/projects.sinks/list)
- [Public API discovery schema](https://logging.googleapis.com/$discovery/rest?version=v2)
