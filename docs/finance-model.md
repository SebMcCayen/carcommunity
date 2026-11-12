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
