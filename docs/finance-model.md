# Production cost model

The admin Finance page estimates future spend. It does not read invoices or
force its result to match the latest billing forecast. Usage assumptions and
dated infrastructure counts live in `functions/src/finance/assumptions.ts`;
unit prices and their sources live in `pricing.ts`.

## Configuration verified on 2026-11-12

The shared `kungsbacka-car-community` project has 34 enabled Scheduler jobs in
`europe-west1`: 28 from this repository and six from the separately deployed
Driver codebase. Both sets contribute to Scheduler charges, invocations,
comparison reads and compute. Driver's idle queue baseline does not estimate
rare account erasure, export or club fan-out mutations.

All schedules use generation 2 request-based billing. Most repository jobs
allocate 0.5 vCPU; drive heat aggregation and Driver allocate 1 vCPU. Memory
ranges from 256 MiB to 1 GiB. Each job declares CPU and memory explicitly. A
credential-free unit test compares repository jobs to Firebase's deploy
manifest; Driver's configuration is a dated deployment snapshot. Its duration
estimates come from seven days of Cloud Monitoring billable instance time
divided by request count, including cold starts.

## Rates and allowances

| Resource                                     | Applied price / allowance                                                               |
| -------------------------------------------- | --------------------------------------------------------------------------------------- |
| Gen2 CPU                                     | $0.000024 per allocated vCPU-second; 180,000 seconds/month free                         |
| Gen2 memory                                  | $0.0000025 per GiB-second; 360,000 GiB-seconds/month free                               |
| Gen2 requests                                | $0.40/million; 2 million/month free                                                     |
| Functions internet transfer from Belgium     | $0.12/GiB to Europe; no North America free transfer allowance                           |
| Scheduler                                    | $0.10 per 31 job-days; 93 job-days/month free                                           |
| Firestore Standard, default Belgium database | $0.03/100k reads, $0.09/100k writes, $0.01/100k deletes; existing daily quotas retained |
| Automatic Firestore TTL deletes              | $0.01/100k; no free quota, separate from manual cleanup deletes                         |
| RTDB downloads                               | $1/GiB after 360 MiB **per day**, without carry-over                                    |
| RTDB stored data                             | $5/GiB-month after 1 GiB                                                                |
| Secret Manager                               | $0.06 per active version-replica/month; first six free                                  |

Sources: [Cloud Run](https://cloud.google.com/run/pricing),
[Scheduler](https://cloud.google.com/scheduler/pricing),
[Firestore](https://cloud.google.com/firestore/pricing),
[Firebase RTDB](https://firebase.google.com/pricing),
[Secret Manager](https://cloud.google.com/secret-manager/pricing).
Billing-account allowances are applied once across both codebases. Other
projects consuming the same billing-account allowances would require updating
their allocation in this model.

Secret Manager metadata showed nine enabled and one disabled version, with
one billable replica each. Disabled versions are also active for billing;
destroyed versions are not. No secret values were needed for this inventory.

## Storage SKU exception

The image bucket is `*.firebasestorage.app` in Belgium with the legacy
`REGIONAL` storage class. Its actual billed SKUs were checked against the
[Cloud Billing Catalog](https://cloud.google.com/billing/v1/how-tos/catalog-api),
service `95FF-2EF5-5EA1`, on 2026-11-12:

| SKU                                             | Price tiers used                                                                               |
| ----------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| A703-5CB6-E0BF, Standard Storage Belgium        | $0.02/GiB-month from the first byte                                                            |
| 22EB-AAE8-FBCD, Download Worldwide Destinations | First 100 GiB/month free; then $0.12 up to 1,024 GiB, $0.11 up to 10,240 GiB, $0.08 thereafter |
| 4DBF-185F-A415, Regional Standard Class A       | First 5,000/month free; $0.005/1,000 thereafter                                                |
| 7870-010B-2763, Regional Standard Class B       | First 50,000/month free; $0.0004/1,000 thereafter                                              |

Do not substitute the US-only Standard bucket Always Free program for these
legacy SKU tiers. The 5 GiB storage allowance does not apply to this Belgium
storage SKU, but its actual download/operation zero-price tiers remain.
Storage and RTDB byte estimates are converted using binary GiB, matching their
billing SKUs.

The SEK conversion is the Catalog's `currencyConversionRate` for
A703-5CB6-E0BF with `currencyCode=SEK`, effective 2026-11-12T08:00:00Z:
9.93095 SEK/USD. It is a dated model input, not a live bank exchange quote.

## Usage estimates remain estimates

The Trafikverket cap is 5,000 Situations; the read/storage workload remains the
measured 5,310 importable deviations, with estimated change/deletion shares.
This update retains the existing member-usage estimates rather than tuning
them to force agreement with an invoice. Automatic TTL deletes (2,000/day) and
function internet transfer (1.2 GiB/month) are rounded from billing observations
for November 1–12. They should be refreshed as production usage changes.

Modelled Google Cloud, Mapbox and operator-entered recurring costs stay
separate. Prices exclude tax. Deployment/build artefacts, unusually heavy
queued work and services without a workload driver can still create costs
outside these estimates.

## Workload coverage and reported costs (2026-11-15)

The Finance page now has two independent views:

- The **model** forecasts Google Cloud, Mapbox and recurring costs. Eight
  previously uncosted callables have explicit planning profiles in
  `functions/src/finance/workloads.ts`: camera photo/catalog, Driver feed
  list/detail, chat reaction set/getMine, account access and warning reads.
  Active reaction cleanup and Driver queue database work also have labelled
  fallback profiles. These round figures are assumptions, not measurements,
  worst-case bounds or guarantees that all work is covered.
- **Reported Google Cloud costs** come from the configured billing export for
  the shared project. Every returned service is displayed, including services
  missing from the model (for example builds, artefact storage and logging).
  Amounts include credits/adjustments and exclude tax/rounding. They are usage-
  period costs in SEK, not an invoice-period total. They are never added to the
  model, Mapbox or recurring-cost totals.

### Resource pooling and attribution

Explicit workloads first occupy the broad legacy member envelope. For each
resource, usage is `max(legacy member estimate, sum(explicit workloads))`, not
an addition of overlapping guesses. Scheduled baseline usage is then added.
Function transfer uses the greater of the old 1.2 GiB/month floor and explicit
traffic. This deliberately retains a conservative floor: lowering one profile
may not lower total cost until that floor is exceeded. Remaining activity is
shown as **other modelled costs and residual assumptions**. A floor can still
miss unrelated concurrent growth; it is not a complete traffic measurement.

Free quotas and price tiers are applied once to the resource pool. Workload SEK
is a proportional attribution of those resulting service costs, not a second
charge. Workload lines plus the residual equal the modelled Google Cloud total.
Firestore daily quotas use average daily activity, so bursts can differ from
this forecast. Storage also budgets private/public camera catalog documents
and the two finance snapshots. No camera images are durably stored.

The standard billing export does not identify Firestore work per application
feature. Accordingly all reported spend remains **unallocated to functions**;
the page never subtracts model allocations from actuals to claim an exact
reconciliation. The service breakdown is factual; the feature breakdown is
estimated. The difference card compares model and report for the same elapsed
UTC days. A preliminary billing forecast extrapolates reported cost across the
calendar month; late exports/credits can change it. It is not an invoice forecast
provided by Google. First-of-month empty results are missing evidence, not zero.

### Evidence capture and fallback

`finance-captureDaily` runs at 05:30 Europe/Stockholm, min instances 0, max 1,
0.5 vCPU and 256 MiB. It overwrites two fixed, backend-only documents:
`financeSnapshots/usage` and `/billing`. No per-user/request ledger, photo IDs,
source URLs or locations are collected. The schedule itself is costed in the
inventory. No Monitoring or BigQuery requests run when an admin opens the page;
`finance-estimate` reads these two documents and one settings document instead.

Monitoring captures a 30-complete-UTC-day window for the eight callables:
request counts (all response codes), network bytes and aggregated billable
instance seconds across revisions. An absent series remains missing, never
zero. A measured zero is respected. Network bytes include HTTP response/base64
traffic and other socket traffic; this is an egress proxy, not exact internet-
transfer billing. Billable instance seconds avoid multiplying request latency
by concurrent requests; CPU/memory use the current 1-vCPU/256-MiB callable
profile. Recheck that profile when deployment configuration changes.

Database operations remain estimated per request, including authorization,
quota/transaction work and expected retries. Missing counters use their visible
profile assumptions. Scheduled queue compute stays in its existing baseline;
active queue mutations remain assumptions until task-level aggregate counters
exist. Failures/denials count as requests but can do less database work than the
average profile. The growth curve uses assumptions rather than scaling absolute
historical counts by a hypothetical population.

Evidence older than three days is labelled stale; stale usage falls back to
assumptions. Old-month or old-export billing disables forecasts/comparisons.
A failed refresh preserves the last successful snapshot and exposes the failure.
Invalid evidence, provider errors, empty billing results, oversized/paginated
billing results and unsupported billing currency never become fabricated zeros.

Admins can adjust fallback event frequencies (0–1,000 events/member/day) or reset
all to code defaults. `finance.updateWorkloadAssumptions` checks the live admin
actor and App Check, validates known unique IDs, and commits settings plus an
immutable audit event atomically. Settings are backend-only under the existing
deny-all rules. Measured request counts take precedence over those overrides.
Unit profiles/rates remain reviewed code. Settings changes have a server timestamp.

### Connect during a separately reviewed backend release

This PR provides the integration; it does not enable APIs, alter IAM, create a
dataset, deploy functions or publish the admin site. Until connected, the page
shows missing/unconfigured evidence and continues using the model.

1. Enable the standard Cloud Billing usage-cost export to a BigQuery dataset
   whose location is known. Use a dataset dedicated to the billing export;
   consider that shared-project costs include Driver. Historical coverage and
   export latency depend on Google's export configuration. Use the existing
   export if one is already available.
2. Enable Cloud Monitoring and BigQuery APIs if needed. Prefer a dedicated
   runtime service account for `finance-captureDaily`: Monitoring Viewer on the
   application project, BigQuery Job User on the query/billing-export project,
   BigQuery Data Viewer on that export dataset, and a custom Firestore role
   limited to the required document get/create/update operations. The runtime
   still needs the normal function logging/runtime permissions. Firestore IAM
   does not restrict collection paths, so keep the service identity private.
   The deploying identity needs permission to act as that runtime account.
3. Set the **server-side**, git-ignored `functions/.env` configuration described
   in `functions/.env.example`. Never send table names, credentials or provider
   access to clients. No service-account key is required: Application Default
   Credentials use the runtime identity. `FINANCE_CAPTURE_SERVICE_ACCOUNT`
   selects the runtime identity at deployment time.
4. Deploy the reviewed finance backend exports and admin web using the normal
   release process. No security-rule or native-client change is necessary.
5. Let the first scheduled capture finish. Verify both evidence dates, the
   project filter and currency, the eight service-name mappings, visible
   credits, and reported totals against Billing for the same UTC usage period.
   Verify member/admin access and one audited assumption update. Do not mark
   reported costs connected until this production check succeeds.

The daily query is parameterized by project/date and scans only the configured,
validated table for the current usage month. It has a hard maximum of 100 MiB
billed per query, uses the query cache, returns at most 200 services and polls an
existing job rather than resubmitting. Larger exports fail visibly; review an
aggregated billing view or the scan budget before changing the cap. The query
location must match the dataset. Dataset storage/query costs, if any, also appear
in reported billing. Monitoring is bounded to three queries with at most ten
pages each. Neither integration runs from emulators or CI.

References: [Billing export](https://docs.cloud.google.com/billing/docs/how-to/export-data-bigquery),
[Monitoring metrics](https://docs.cloud.google.com/monitoring/api/metrics_gcp_p_z),
[bounded BigQuery queries](https://docs.cloud.google.com/bigquery/docs/reference/rest/v2/jobs/query).
