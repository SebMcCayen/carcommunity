/**
 * Per-function instance ceilings — the cost guardrail.
 *
 * Cloud Functions v2 defaults `maxInstances` to **1000**. Every function in
 * this codebase inherited that default, which means there was no upper bound
 * on spend: a client retry loop shipped in a bad release, a compromised
 * device, or an unusually busy Saturday could scale one callable to a thousand
 * concurrent instances before anyone noticed. App Check is enforced on every
 * callable (see `__tests__/appcheck-guard.test.ts`), so anonymous external
 * spam is hard — but attestation bounds *who* can call, never *how much*.
 *
 * These constants are the single place to tune those ceilings. Each options
 * object in `functions/src` references one of them by name, so the tier a
 * function sits in is visible at its definition and changing a tier changes
 * every member of it at once.
 *
 * ## What being capped feels like
 *
 * `maxInstances` bounds cost AND throughput. When a function is at its
 * ceiling, Cloud Run queues further requests behind the busy instances; if the
 * queue cannot be drained in time the platform sheds the request and the
 * client sees `resource-exhausted` (or a plain timeout). There is no
 * distinctive server-side error — the symptom is latency, then failures. So
 * these numbers are floors on capacity, not just caps on spend, and they
 * should be raised the moment real traffic gets near them rather than left to
 * throttle members.
 *
 * A useful sanity check: v2 callables run with `concurrency: 80` by default,
 * so `maxInstances: 20` is roughly 1600 in-flight requests, not 20.
 * Event-driven functions (Firestore triggers) run one event per instance, so
 * for those the number really is the parallelism.
 *
 * ## Not covered here
 *
 * A GCP **billing budget alert** does not exist for this project and cannot be
 * created from the repo (no `gcloud`, and the Firebase CLI has no command for
 * it). Caps without an alert still leave the invoice as the first signal that
 * something ran hot — see the deployment docs for the console path.
 */

/**
 * Hot paths — 50.
 *
 * Callables a map or a live view drives at a high, *legitimate* rate: the live
 * location session (position updates every few seconds per sharer) and the two
 * viewport queries a moving map re-issues as it pans. These carry the highest
 * honest concurrency in the app, so they get the highest ceiling.
 *
 * Members: `live-startSession` / `live-updatePosition` / `live-extendSession` /
 * `live-stopSession` / `live-hideMeNow` (one shared options object in
 * `live/session.ts` — `updatePosition` dominates the rate and sets the tier for
 * the file), `live-listNearby`, `incidents-listNearby`.
 */
export const MAX_INSTANCES_HOT = 50;

/**
 * Ordinary member callables — 20.
 *
 * The default tier, and the answer for anything uncertain. Chat sends, RSVPs
 * and event lifecycle, garage, saved drives, friends, profile/search,
 * notifications, convoys, incident reporting, Kronjakt claims, blocking,
 * reporting/moderation submissions, account deletion, subscription checks.
 *
 * Also used for files that mix admin and member callables behind one options
 * object (`partners/manageOffer.ts` — `showOfferCode` is member-facing;
 * `partners/applications.ts` — `submitApplication` is member-facing;
 * `billboards/manageBillboard.ts` — `recordInteraction` is member-facing;
 * `subscription/verify.ts` — `verify` is member-facing; `events/manageEvent.ts`
 * and `events/eventLifecycle.ts` — member-or-admin actors). Being generous
 * there is deliberate: starving a member path to tighten an operator path
 * would be the wrong trade.
 */
export const MAX_INSTANCES_MEMBER = 20;

/**
 * Admin / operator callables — 5.
 *
 * Only a handful of operators exist, and they act from the admin web app one
 * click at a time. Moderation queues, points adjustments, badge administration,
 * partner company management, billboards administration, feature flags, app
 * version, suspend/restore/warn, admin notification sends, insight summaries.
 *
 * 5 instances x 80 concurrency is ~400 in-flight admin requests; if that is
 * ever reached, something is wrong rather than busy.
 */
export const MAX_INSTANCES_ADMIN = 5;

/**
 * Ordinary Firestore triggers — 20.
 *
 * One invocation per matching document write, one event per instance. These
 * follow member action rates, not viewport rates: RSVP writes, block writes,
 * profile writes, the points-economy award triggers, sign-in-failure and
 * client-error report ingestion.
 *
 * Note the two report-ingestion triggers (`diagnostics-onSignInFailure`,
 * `errors-onClientErrorReport`) are capped on purpose even though a bad release
 * could burst them: a burst of error reports is exactly the runaway this
 * guardrail exists to bound, and the reports are already written to Firestore —
 * only the derived processing is throttled.
 */
export const MAX_INSTANCES_TRIGGER = 20;

/**
 * Fan-out Firestore triggers — 50.
 *
 * Raised above the ordinary trigger tier because these fan out from a single
 * operator or member action into one invocation per affected member, and
 * throttling them delays something a member is waiting for:
 *
 * - `notifications-onNotificationCreated` — sends the push. One admin broadcast
 *   writes a notification document per member, so this is the highest-volume
 *   trigger in the codebase by a wide margin, and a queued instance is a push
 *   that arrives late.
 * - `badges-*` progress triggers — `onUserLifecycleWritten` fires on every
 *   sign-in (a morning spike across the whole member base) and each counter
 *   bump cascades into `onBadgeProgressWritten`, so the write rate here is a
 *   multiple of the member action rate.
 */
export const MAX_INSTANCES_TRIGGER_FANOUT = 50;

/**
 * Scheduled sweeps — 2.
 *
 * Cloud Scheduler delivers one invocation per tick, so the ceiling is not
 * about steady-state load; it bounds a retry storm and lets a slow run overlap
 * the next tick instead of being dropped. 1 would be a strict singleton, which
 * is only correct where overlapping passes would break an invariant — the
 * Kronjakt spawn/sweep pair sets `maxInstances: 1` locally for exactly that
 * reason and is deliberately not on this tier.
 */
export const MAX_INSTANCES_SCHEDULED = 2;
