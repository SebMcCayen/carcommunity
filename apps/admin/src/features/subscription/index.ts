/**
 * Admin subscription feature — placeholder.
 *
 * Prepares the admin portal to display user subscription information
 * fetched from GET /v1/admin/users/:userId/subscription.
 *
 * Fields to display when implemented:
 *  - Current entitlement (none | member_monthly)
 *  - Subscription status (inactive | active | grace_period | expired | revoked | cancelled)
 *  - Platform (apple | google | manual)
 *  - Expiry date (if available)
 *  - Warning if a suspended user still has an active subscription
 *    (isSuspendedWithActiveSubscription flag)
 *
 * TODO: Implement subscription revoke/cancel admin action.
 *   - MUST follow Apple and Google refund/revoke rules.
 *   - Apple: refunds are initiated via App Store — backend cannot unilaterally refund.
 *   - Google: refunds via Google Play refund API; cancel via purchases.subscriptions.cancel.
 *   - Both require human review, documented reason, and audit logging before execution.
 *
 * TODO: Add audit logging for all subscription-related admin actions.
 *   - Every admin action that modifies subscription state must be logged.
 *   - Log actor, target user, action type, and reason.
 *   - Audit log must be append-only and tamper-evident.
 *
 * TODO: Add support workflow for suspended users with active subscriptions.
 *   - When isSuspendedWithActiveSubscription is true, display a prominent warning.
 *   - Define a clear process for communicating with the subscriber before or after action.
 *   - Do not automatically cancel subscriptions on suspension without human review.
 *
 * Security requirements:
 *  - All data comes from backend — never trust client-side subscription claims.
 *  - Never display or store raw provider tokens in the admin UI.
 *  - Admin access must be verified by the backend on every request.
 */

export {};
