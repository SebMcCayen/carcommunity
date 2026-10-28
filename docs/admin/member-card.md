# Member card

The approved member overview is implemented using existing admin tokens and real backend projections. Overview, Profile, Warnings, Points and History are separate tasks. Access is managed in a reviewed dialog; suspension and identity blacklist remain independent. The common KP/XP balance is authoritative, never a sum of the visible history page.

Overview shows available latest-login data and explicitly says that login frequency is unavailable. Warning and points previews are bounded; a partial warning page is never described as a complete absence of active warnings. Warning preview expiry uses the server's remaining duration against a monotonic browser clock. No polling reads are added.

The access badge requires both completed product initialization and rollout readiness before displaying Active. An admin read never initializes the target account. Missing metadata is shown as unverified rather than an inferred absence of restrictions.

The Points tab reads the authoritative balance from the same first ledger page, avoiding a second wallet read and any cache fallback. Successful adjustments immediately use the backend-confirmed balance and refresh the ledger; earlier in-flight balance reads cannot overwrite that result.

Reviewed dialogs center within the viewport, constrain and scroll their contents, keep field labels on separate rows, and return keyboard focus to the triggering control after cancellation.

On narrow viewports the existing navigation becomes a bounded disclosure above the content. It closes after choosing a destination. The broader navigation regrouping remains a later phase.

Profile separates name changes from advanced account/role controls. Account deletion still affects the shared Firebase identity and therefore explicitly explains its possible Driver impact. Access decisions remain CarCommunity-only. Existing typed-name/reason confirmation and backend deletion protections are preserved.

Warning lifecycle decisions have their own reason/duration controls in the review dialog; they do not depend on filling the new-warning form. Revoked warnings cannot be reactivated. Reviewed versions, actor binding and durable retry receipts retain their existing backend contracts.

`admin-listMemberHistory` pages the existing member-targeted audit index. It returns action, reason, actor display name and timestamp only. Raw audit details, original removed-message bodies and internal evidence are excluded. Cursor scope includes the target UID, timestamps retain nanosecond precision, and every request requires current administrator authority. Points remain in their separate ledger history. Missing actor profiles have an explicit historical-administrator fallback.

The approved delivery plan places member public-message search and the unified moderation queue in stage4, after these member tools. No placeholder message controls are introduced here. Shared navigation regrouping remains the later navigation stage.

All verification uses the no-cloud test guard and disposable demo emulators. Compatible backend/index rollout, staging/device checks and production deployment are separate release gates; no live project writes or billable test resources are authorized.
