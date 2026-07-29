/**
 * Shared auto-issue dedup link model — pure domain logic.
 *
 * Every auto-filing pipeline in this codebase (client errors, server errors,
 * sign-in failures) needs the SAME thing: a `{collection}/{fingerprint}` link
 * document that (a) records which public GitHub issue a fingerprint maps to and
 * (b) tallies how many times that fingerprint has been seen, so a recurring
 * failure bumps a counter instead of opening hundreds of issues.
 *
 * This module owns that state machine ONCE. It was extracted from
 * errors/clientErrors-core.ts when the server-error pipeline was added, rather
 * than copy-pasting a second identical set of builders:
 *
 *   (absent) --claim--> creating --success--> created --occurrence--> created
 *                          |                                (increment only)
 *                          |--github failed, count == 1--> (deleted; a later
 *                          |                                occurrence retries)
 *                          '--github failed, count > 1 --> failed --claim--> creating
 *
 * `creating` is the in-flight claim: exactly one concurrent occurrence wins the
 * transaction that writes it, so exactly one GitHub issue is filed per
 * fingerprint. `failed` is deliberately RETRIABLE — a transient GitHub outage or
 * an exhausted issue budget must not permanently silence an error.
 *
 * The link documents carry no account identifiers: the fingerprint is a hash and
 * everything else is a server-controlled key, a status, a count or a timestamp.
 *
 * Pure module — no Firebase Admin SDK and no network imports, so every branch is
 * unit-testable without emulators (mirrors the sibling *-core.ts files). The
 * Firestore sentinels (`FieldValue.increment`, `FieldValue.serverTimestamp`) are
 * injected by the caller as opaque values, exactly as the client-error path
 * already did.
 */

/**
 * Link lifecycle. `creating` = an occurrence has claimed the fingerprint and is
 * calling GitHub right now; `created` = the issue exists; `failed` = a create
 * attempt did not produce an issue and may be retried.
 */
export type IssueLinkStatus = 'creating' | 'created' | 'failed';

/** The subset of a link document this state machine reasons about. */
export interface IssueLinkState {
  status: IssueLinkStatus;
  count: number;
}

/**
 * Dedup decision:
 * - no existing link → CREATE the issue;
 * - a `failed` link → CREATE (retry a previously-failed create);
 * - any other link (`creating` in-flight, or `created`) → only INCREMENT the
 *   occurrence tally, so one unique failure is one issue.
 */
export function decideIssueAction(
  existing: IssueLinkState | null | undefined,
): 'create' | 'increment' {
  if (!existing) return 'create';
  if (existing.status === 'failed') return 'create';
  return 'increment';
}

/**
 * Placeholder link written BEFORE the GitHub call (status `creating`).
 *
 * @param identity fingerprint + any pipeline-specific descriptor fields (e.g.
 *                 the client-error `feature` key, or the server-error `source`).
 *                 Callers must only pass server-controlled, non-identifying
 *                 scalars — link docs are keyed by hash and hold no PII.
 */
export function buildNewIssueLink(
  identity: Record<string, unknown>,
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  return {
    ...identity,
    status: 'creating' as IssueLinkStatus,
    issueNumber: null,
    issueUrl: null,
    count: 1,
    firstSeenAt: serverTimestamp(),
    lastSeenAt: serverTimestamp(),
  };
}

/** Patch applied once the issue exists (status `created`). */
export function buildIssueLinkCreated(issue: {
  number: number;
  url: string;
}): Record<string, unknown> {
  return {
    status: 'created' as IssueLinkStatus,
    issueNumber: issue.number,
    issueUrl: issue.url,
  };
}

/** Patch on a repeat occurrence: bump the tally and touch lastSeenAt. */
export function buildIssueLinkIncrement(
  increment: unknown,
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  return {
    count: increment,
    lastSeenAt: serverTimestamp(),
  };
}

/**
 * Patch that RE-CLAIMS a `failed` link for another create attempt: flip status
 * back to `creating`, refresh lastSeenAt, and count this occurrence — without
 * resetting the preserved count/firstSeenAt.
 */
export function buildIssueLinkRetry(
  increment: unknown,
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  return {
    status: 'creating' as IssueLinkStatus,
    count: increment,
    lastSeenAt: serverTimestamp(),
  };
}

/** Patch when a create attempt did not yield an issue; retriable. */
export function buildIssueLinkFailed(): Record<string, unknown> {
  return {
    status: 'failed' as IssueLinkStatus,
  };
}
