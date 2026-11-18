# Partner portal v1

Implementation contract for the approved partner proposal (visual direction 1).
No payment or appointment booking. Existing customer membership requirements remain.
All new operations use the callable **`partners-portal`** (europe-west1), with
`{ action: <name>, ...fields }`. Responses below are the direct callable data,
not an `ok/data` envelope. Auth + App Check, current account status and scoped roles
are checked by the server. Admin-only operations require the existing admin guard.

## Common models

`Company`: `{ companyId, name, description, category, address, latitude, longitude,
phone: string|null, website: string|null, logoPath: string|null, status, role }`.
Role is `manager` or `redeemer`; only managers get business statistics. Admin may
use administrative operations, never customer reservation bypasses.

`ManagedOffer`: `{ offerId, companyId, title, teaserText, description, terms,
offerType, percentageDiscount: number|null, availableFrom: ISO|null,
availableUntil: ISO|null, capacity: number|null, reservationDays: number,
reservedCount: number, redeemedCount: number, status, version: number }`.
New offers are draft and require existing admin activation. Terms cannot be edited
once reservations have been issued; create a new campaign instead. Null capacity
means unlimited. reservationDays defaults to 7 (1..30). New managed offers use
member_benefit or percentage_discount and are redeemable through personal QR.

`Reservation`: `{ reservationId, offerId, companyId, companyName, title, terms,
status: reserved|redeemed|cancelled|expired|revoked, expiresAt: ISO,
redeemedAt: ISO|null }`. No customer UID is ever returned to staff or statistics.
The private server record binds a customer UID for ownership and one use/campaign.

`Statistics`: `{ updatedAt: ISO, period: 'all'|YYYY-MM, totalRedeemed: number,
partners: [{ companyId, companyName, totalRedeemed,
offers: [{ offerId, title, totalRedeemed }] }] }`.
Only aggregate counts, no unique-person metric, no user references or individual
event times. Use server-confirmed redemption, not client-reported analytics.
Monthly statistics use closed calendar months in UTC; the current month is not
offered as a filter. All-time figures are published with delay, never a live feed.

## Customer and partner actions

- `submitApplication`: `{ companyName, category, organizationNumber?, contactName,
contactEmail, contactPhone?, website?, description?, address?, publicPhone?,
selfServiceRequested: boolean, billboardRequested: boolean,
billboardPlacementRequest?: string, message?, logoBase64?: string, logoRightsConfirmed?: boolean }`
  -> `{ applicationId, status }`. PNG logo base64 (without data prefix), <=5MB,
  recommended 1024x1024, <=16MP. Server decodes/re-encodes, strips metadata.
  `submitApplication` resubmits a changes_requested application owned by the actor.
- `workspace`: `{}` -> `{ companies: Company[], application: Application|null }`.
  Application includes applicant fields above (no logoBase64), `applicationId`,
  `status`, `reviewReason: string|null`, `logoPreviewBase64: string|null`.
- `uploadLogo`: `{ companyId, logoBase64, logoRightsConfirmed: true }` -> `{ logoPath }`; manager-only,
  server validates/re-encodes and publishes own-company PNG. Existing logo remains
  until replacement succeeds. Admin can also call this with companyId.
- `updateProfile`: `{ companyId, description, phone?, website? }` -> `{ companyId }`.
  Manager may update description/contact; name, location and status stay admin-owned.
- `listOffers`: `{ companyId }` -> `{ offers: ManagedOffer[] }` (scoped manager).
- `submitOffer`: `{ companyId, offerId?, title, teaserText, description, terms,
percentageDiscount?: number|null, availableFrom?: ISO|null,
availableUntil?: ISO|null, capacity?: number|null, reservationDays?: number }`
  -> `{ offerId, status: 'draft' }`. manager or admin; current offer ownership is
  checked, active/previously reserved offers cannot be edited.
- `pauseReservations`: `{ companyId, offerId, paused: boolean }` -> `{ offerId }`.
  Manager-only. Stops new reservations; existing valid reservations remain usable.
  Existing company/offer status pause remains a fail-closed security suspension.
- `offer`: `{ offerId }` -> `{ offer: ManagedOffer,
reservation: Reservation|null, alreadyUsed: boolean }`. The last field prevents
  presenting a new reservation after the operational record has been removed
  while its protected campaign anti-reuse tombstone remains. Active, in-date company+offer and existing
  membership checks required. Used before reserve; full terms shown here.
- `reserve`: `{ offerId, requestId, expectedVersion }` -> `Reservation`.
- `reservations`: `{}` -> `{ reservations: Reservation[] }` (own recent, max100).
- `cancel`: `{ reservationId }` -> `Reservation` (only own, atomic quota release).
- `token`: `{ reservationId }` -> `{ token, expiresAt: ISO }`.
  Token opaque, 60-second TTL, previous generation revoked. QR encodes this token
  verbatim, without URL. Never log token. Own reserved record only.
- `inspect`: `{ token }` -> `{ reservation: Reservation }` (scoped staff).
  No state change, no customer identity. Must still confirm `redeem` explicitly.
- `redeem`: `{ token, requestId }` -> `{ reservation: Reservation, receiptId }`.
  Scoped staff, online atomic single redemption; retries return same receipt.
- `statistics`: `{ companyId, period?: 'all'|YYYY-MM }` -> `Statistics`.
  Manager of this company only, or admin. No raw redemption-list endpoint.

## Admin actions

Admin lists may continue direct Firestore reads behind existing admin rules, using
real cursor pagination and server filters. No first-page-only local filtering.

- `reviewApplication`: `{ applicationId, decision: 'publish'|'request_changes',
expectedRevision?: number, reason?: string, grantSelfService?: boolean, latitude?: number,
longitude?: number }` -> `{ applicationId, companyId?: string, status }`.
  Publish requires name, description, address and valid location. When selfservice
  is granted the server links submittedByUserId (never a supplied arbitrary UID).
  Repeated publish returns the same company. Request changes requires reason.
  Existing under_review/reject actions may use partners-reviewApplication.
- `membership`: `{ companyId, uid, role: 'manager'|'redeemer', enabled: boolean }`
  -> `{ companyId }`. Admin-only; target must be existing active verified account.
- `members`: `{ companyId }` -> `{ members: [{ uid, role, enabled }] }` (admin-only,
  operational company staff list, never customer list).
- `adminStatistics`: `{ companyId?: string, period?: 'all'|YYYY-MM }`
  -> `Statistics`. Omit companyId for all partners.
- `billboardLogo`: `{ billboardId }` -> `{ imagePath }`; copies validated company
  logo into its billboardImages path. Admin-only, actual billboard company binding.

## Security and privacy invariants

reserved + redeemed <= capacity. Reserve/cancel/expire/redeem are atomic,
idempotent and based on server time. No offline success. Current role revocation
applies immediately. No customer IDs in aggregate data, URLs, audit metadata or
new analytics. Exact sparse totals do not prove anonymity; no fine-grained filters.
Short-lived private linkage is allowed only for reservation/abuse controls.
Redemption events and dedupe stay backend-only. Closed-period aggregates persist.
New upload endpoints accept only decoded safe PNG, not SVG or arbitrary URLs.
Admin/personnel changes are audited without QR/customer identity.

Billboard public models add nullable `imagePath` (existing field) and company
models expose nullable `logoPath`; use Storage references, not invented URLs.
Internal partner_profile CTA opens companyId on both apps.

## Retention

Local per-account application drafts expire after seven days and exclude logos.
Private terminal reservation records and their claim pointers are deleted after
30 days with a transactional recheck (a re-reserved active record is never deleted).
A redeemed/revoked campaign retains only a backend-only campaign-scoped anti-reuse
tombstone, not a reservation pointer or event timestamp, while the campaign remains
usable; it is retired 30 days after campaign end. This is protected operational
pseudonymous data, never an anonymous analytics identifier. Tokens/limits/requests
are swept by expiry. Aggregate counts and minimal processed outbox IDs persist for
correct lifetime totals and retry deduplication; they carry no customer reference.

## Initial operating limits

A business workspace supports at most **100 stored campaigns**, including legacy
offers, and at most **10 new managed campaigns per UTC calendar day**, shared by
all staff accounts for that business. Editing a draft does not consume the daily
creation allowance. Creation checks and the company/day budget update occur in
one Firestore transaction. The budget record uses a hashed company/day key and
expires after 90 days; it contains no customer identity.

`listOffers` reads one extra document to detect an oversized workspace and returns
`resource-exhausted` instead of a misleading partial list. Existing larger
workspaces require administrator assistance. These are explicit initial resource
limits, not a recommendation for permanent lifetime limits: a future scale-up
should add cursor pagination and an archive policy before raising the workspace
limit. KCC Admin's own lists already use cursor pagination.

Statistics scopes are bounded at 10,000 aggregate offer rows; larger requests fail
explicitly instead of returning incomplete totals. Public daily snapshot cache
generations expire after seven days; durable period totals and processed event
markers remain. Logo processing uses at most four concurrent requests per
instance, and inline PNG preview output is capped at 450 KB.

## Controlled activation

`partnerSelfService` is default OFF. Deploy and review code, rules, indexes and
both native builds first; separately enable it through the existing audited
admin feature-flag control. Off stops new reservations and partner mutations,
while own cancellations and valid pre-existing QR redemption remain available.
No merge, deployment, flag activation or app publication is authorized by this PR.
