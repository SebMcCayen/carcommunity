import { createHash } from 'node:crypto';

export const COMMUNITY_SEND_GUARD_COLLECTION = 'communityChatSendGuards';
export const COMMUNITY_REFILL_MS = 5_000;
export const COMMUNITY_CAPACITY = 2;
export const COMMUNITY_REPEAT_WINDOW_MS = 30_000;
// At most seven sends fit in a rolling 30s window with a two-token bucket.
const RECENT_LIMIT = 8;

interface RecentSend {
  fingerprint: string;
  atMillis: number;
}
export interface CommunitySendState {
  policyVersion: 1;
  creditMs: number;
  lastRefillMillis: number;
  recent: RecentSend[];
}
export interface CommunitySendDecision {
  allowed: boolean;
  next: CommunitySendState;
}
const integer = (value: unknown): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;

function valid(value: unknown, now: number): value is CommunitySendState {
  if (!value || typeof value !== 'object') return false;
  const s = value as CommunitySendState;
  return (
    s.policyVersion === 1 &&
    integer(s.creditMs) &&
    s.creditMs <= COMMUNITY_CAPACITY * COMMUNITY_REFILL_MS &&
    integer(s.lastRefillMillis) &&
    s.lastRefillMillis <= now &&
    Array.isArray(s.recent) &&
    s.recent.length <= RECENT_LIMIT &&
    s.recent.every(
      (entry) =>
        entry &&
        typeof entry.fingerprint === 'string' &&
        /^[a-f0-9]{64}$/.test(entry.fingerprint) &&
        integer(entry.atMillis) &&
        entry.atMillis <= s.lastRefillMillis,
    )
  );
}

/** Community-only overlay; the shared cross-app policy remains byte-identical.
 * Hashes are bounded, retained logically for 30s, never logged, and erased with
 * the account. Normalization only affects comparison, never the stored message.
 */
export function evaluateCommunitySend(
  value: unknown,
  text: string,
  now: number,
): CommunitySendDecision {
  if (!integer(now)) throw new RangeError('Invalid server clock');
  const fresh: CommunitySendState = {
    policyVersion: 1,
    creditMs: COMMUNITY_CAPACITY * COMMUNITY_REFILL_MS,
    lastRefillMillis: now,
    recent: [],
  };
  if (value !== undefined && !valid(value, now)) {
    // Repair once without granting a fresh burst; the shared guard commits the pause.
    return { allowed: false, next: { ...fresh, creditMs: 0 } };
  }
  const state = value === undefined ? fresh : (value as CommunitySendState);
  const creditMs = Math.min(
    COMMUNITY_CAPACITY * COMMUNITY_REFILL_MS,
    state.creditMs + now - state.lastRefillMillis,
  );
  const recent = state.recent.filter((entry) => entry.atMillis > now - COMMUNITY_REPEAT_WINDOW_MS);
  const fingerprint = createHash('sha256')
    .update(text.trim().replace(/\r\n?/g, '\n').normalize('NFC'))
    .digest('hex');
  const next: CommunitySendState = { ...fresh, creditMs, recent };
  if (
    creditMs < COMMUNITY_REFILL_MS ||
    recent.filter((entry) => entry.fingerprint === fingerprint).length >= 2
  )
    return { allowed: false, next };
  return {
    allowed: true,
    next: {
      ...next,
      creditMs: creditMs - COMMUNITY_REFILL_MS,
      recent: [...recent, { fingerprint, atMillis: now }].slice(-RECENT_LIMIT),
    },
  };
}
