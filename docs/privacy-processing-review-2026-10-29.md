# Privacy processing review — 2026-10-29

Status: evidence and proposed decisions for draft PR #1248. Not publication,
production verification or approval of the legal bases for historical processing.
No Android/iOS implementation, data migration, cloud configuration or release is changed.

## Owner-confirmed information

- Operator/controller: **Sebastian Stegrin**, acting as an individual, operating
  **Kungsbacka Car Community**. No company or organisation number is invented.
- Contact for support, privacy and moderation review:
  **kontakt@kungsbackacarcommunity.se** (spelling explicitly reconfirmed).
- Minimum service age: **18**. This is a service rule, not a claim of verified birth
  dates: current Android onboarding confirms a driving licence, terms and privacy,
  and does not ask a separate age question.
- App administrators handle moderation. Review requests go to the contact email.
- Owner wants the terms to apply from Android version 1.0. The in-app release
  catalogue records 1.0.0 / versionCode 51 on **2026-09-02**, but actual Play public
  availability is not established by that catalogue or a build/merge timestamp.
  The revised policy has its own draft revision date, **2026-10-29**. Its publication
  and effective dates remain to be recorded when approved. Do not backdate new
  consent or claim this new information was delivered to earlier users.
- The response to the contact-address question supplied an email address. No
  postal address was supplied or added. GDPR Article 13 requires controller
  identity/contact details; storefront trader/contact obligations are a separate
  check. Being an individual does not itself mean non-trader. Apple lists revenue
  and in-app purchases among the assessment factors and requires individual traders
  to supply an address/P.O. box, phone and email for the product page. Do not infer
  trader status or invent an address/phone from this discussion.

## Proposed purpose/basis allocation

A proposal for the revised text, not a legal sign-off or a retroactive basis switch:

| Purpose                                                                                                        | Proposed basis and boundary                                                                                                                                                                                                                                                                                           |
| -------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Account/sign-in, requested community functions, membership verification, purchase handling and related support | Contract, Article 6(1)(b), only for processing objectively necessary to provide the requested service. A contract cannot make unrelated analytics necessary.                                                                                                                                                          |
| Optional partner interactions/pass-bys and saved-route analysis                                                | Separate affirmative consent, Article 6(1)(a), withdrawn in Privacy. Current Android/main defaults are not affirmative consent. PR #1245 must land and its rules/backend/clients be coordinated before publishing the new default-off statement.                                                                      |
| Requested map, drive recording/sharing and Crown Hunt                                                          | Document purpose and necessity per feature before finalising the basis. Device permission, a session start and acceptance of general terms must not be treated as automatic consent to unrelated route analytics. Review earlier location-consent disclosures rather than silently relabelling historical processing. |
| Necessary security, abuse/fraud prevention, administrator moderation and limited operational diagnostics       | Article 6(1)(f) only after a documented necessity/balancing assessment, with concrete safeguards, retention limits and an objection route. This does not justify indefinite retained evidence or unrelated marketing.                                                                                                 |
| Mandatory records                                                                                              | Article 6(1)(c) only for an identified applicable obligation. No generic seven-year or other accounting duty is assumed for this individual operator or all technical purchase records.                                                                                                                               |

The old Android draft (`docs/play/privacy-policy.md` on merged main) contains useful
categories but also obsolete claims about tokens, deletion and collection defaults.
Android Settings opens the external policy; it does not contain an authoritative,
current, owner-approved legal-basis register. `privacyPolicyAcceptedAt` is a server
timestamp, not an accepted-policy revision identifier. Do not infer renewed consent
from continued use, publishing this page or the existing acceptance timestamp.

Firebase and Mapbox publish processing/transfer terms. Code cannot establish their
acceptance for the operator's account, actual backup settings, provider roles for
every SDK activity, or operational TTL configuration. Record those checks before
removing the draft banner. Do not infer that an EU Functions region keeps all
provider processing in the EEA.

## Retention: what the merged source establishes

Audited merged main: `5ecb415a4d1fd2811b86fd4b2b4106b337813ee0`. This is repository
evidence; deployed revisions, external TTL policies and provider contracts were
not queried. Sources below refer to that snapshot, not the older legal PR checkout.

| Category                                    | Supported behaviour                                                                                                    | Boundary                                                                                                                                                                 |
| ------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Account purge                               | Eligible strictly after 30 days; daily 03:30 Stockholm queue, at most 25 requests/run, retries and bounded scans       | Not complete within 30 days, not an automatic legal justification for a 30-day delay. Normal callable disables Auth/revokes sessions; direct fallback is not equivalent. |
| Live map markers                            | Stop/hide clears markers; >15-minute stale markers swept every five minutes                                            | Visibility/marker cleanup does not erase all session state; last points-calculation coordinates can persist.                                                             |
| Saved drives and route files                | Individual deletion and account purge                                                                                  | No automatic age deletion established. Community's five-drive limit, Plus's 90-day history and the heatmap's 90-day input window are not storage-retention limits.       |
| Community / convoy chat                     | 120-day / 30-day expiry fields                                                                                         | Actual automatic removal needs an enabled Firestore TTL policy; not verified live.                                                                                       |
| DMs / event chat                            | No general automatic age limit; account purge covers DM conversations and authored chat/event messages and RSVPs       | Another author's copied quote can retain UID/name/preview. Closing an event does not erase chat.                                                                         |
| Raw partner interactions                    | Seven-day expiry and daily 04:00 cleanup                                                                               | Ordinarily seven days plus the next successful sweep; deterministic partner-scoped hashes are pseudonymous, not a guarantee of anonymisation.                            |
| Partner heat                                | Daily replacement using preceding 90 days; endpoint trim and contributor threshold                                     | Analysis window, not deletion of original route files. Other aggregate retention has no implemented bound found.                                                         |
| Diagnostics reports                         | Eligible after 90 days; monthly sweep on first day at 06:00                                                            | 90 days plus time to next successful sweep. This does not cover separate feedback/client/server-error records.                                                           |
| Event check-in evidence                     | Some records have a 90-day expiry refreshed by samples                                                                 | Depends on external TTL; risk records and purge coverage require separate action.                                                                                        |
| Road incidents                              | Type-dependent hour-scale expiry, confirmations capped at three times base lifetime; bounded 15-minute recursive sweep | Separate police reports have a 40-minute visible expiry with external TTL dependence.                                                                                    |
| Proof, moderation, audits and reward claims | Certain records deliberately survive account purge; no complete time-bound cleanup found                               | May retain direct UID, names, text or location-bearing evidence. Hashing the document key is not anonymisation.                                                          |

Successful account purge marks a request processed and logs completion. **No
implemented completion email/push/in-app acknowledgement was found.** Do not promise
one. Administrators need a verified manual status/completion response process.

Material gaps before publication: define justified retention and deletion for
proof/moderation/audits, claims, event check-in/risk records, copied quotes,
client/server error and feedback records, retained convoy references, shared
Driver/club records and aggregates; verify backups/TTL; verify the actual deployed
purge path. A missing code limit is not authorisation to keep data indefinitely.
These findings do not authorise runtime changes in this documentation PR.

## Code evidence

Base URL for every path: [audited source](https://github.com/SebMcCayen/carcommunity/tree/5ecb415a4d1fd2811b86fd4b2b4106b337813ee0).

- `functions/src/account/deleteAccount.ts:63–80`, `deletion-core.ts:37–62`,
  `scheduled.ts:376–581`: queue, erasure coverage, exceptions and completion.
- `functions/src/live/live-core.ts:68–127`, `live/scheduled.ts:125–267`,
  `live/session.ts:550–580`, `points/liveDistance.ts:123–124`: markers vs sessions.
- `functions/src/drives/deleteDrive.ts:44–56`,
  `partnerInsights/driveHeatAggregation.ts:47–109`: drive erasure vs analysis window.
- `functions/src/chatchannels/chat-core.ts:158–177,582–587`,
  `events/chat-core.ts:234–256`: expiry fields and copied quotes.
- `functions/src/diagnostics/scheduled.ts:20–61`,
  `partnerInsights/scheduled.ts:55–184`: actual cleanup cadence.
- `functions/src/events/checkIn.ts:331–350,386–411`,
  `moderation/moderation-core.ts:365–427`: retained sensitive evidence.
- `functions/src/crownHunt/submitClaim.ts:578–616`,
  `crownHunt/claimSpawn.ts:346–357`: raw user IDs in retained claims.
- `functions/src/errors/clientErrors-core.ts:240–260`,
  `feedback/feedback-core.ts:267–283`, `notifications/email.ts:31–44`: support IDs,
  unimplemented email delivery.
- `apps/android/app/src/main/res/raw/changelog.json:178–189`, release PR #1071:
  recorded version date does not prove production publication.
- `apps/android/app/src/main/java/com/kungsbackacarcommunity/app/onboarding/FirebaseOnboardingRepository.kt:27–35`,
  `functions/src/auth/onboarding-core.ts:120–125`: timestamp-only policy acceptance.

## Primary guidance consulted

- [IMY: legal bases](https://www.imy.se/verksamhet/dataskydd/det-har-galler-enligt-gdpr/rattslig-grund/)
- [IMY: contract](https://www.imy.se/verksamhet/dataskydd/det-har-galler-enligt-gdpr/rattslig-grund/avtal-med-den-registrerade/)
- [IMY: consent](https://www.imy.se/verksamhet/dataskydd/det-har-galler-enligt-gdpr/rattslig-grund/samtycke/)
- [IMY: legitimate-interest assessment](https://www.imy.se/verksamhet/dataskydd/det-har-galler-enligt-gdpr/rattslig-grund/intresseavvagning/)
- [GDPR Articles 12–13](https://www.imy.se/verksamhet/dataskydd/det-har-galler-enligt-gdpr/introduktion-till-gdpr/dataskyddsforordningen-i-fulltext/)
- [Firebase privacy/processing terms](https://firebase.google.com/support/privacy)
- [Mapbox processing terms](https://www.mapbox.com/legal/dpa)
- [Apple trader assessment/contact requirements](https://developer.apple.com/help/app-store-connect/manage-compliance-information/manage-european-union-digital-services-act-trader-requirements/)
