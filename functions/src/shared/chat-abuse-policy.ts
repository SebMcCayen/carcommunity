/** Shared chat flood policy v1. Byte-identical in CarCommunity and Driver.
 * No content, channel, role or subscription inputs. Integer refill credit in ms:
 * 2,000 credit = one send, preserving partial refills without float drift.
 */
export const CHAT_POLICY_VERSION = 1;
export const CHAT_GUARD_COLLECTION = 'chatSendGuards';
export const CHAT_REFILL_MS = 2_000;
export const CHAT_CAPACITY = 6;
export const CHAT_RESET_MS = 30 * 60_000;
export const CHAT_PENALTIES_MS = [5_000, 30_000, 120_000, 600_000] as const;
export const CHAT_MAX_BLOCK_MS = 600_000;

export interface ChatGuardState {
  policyVersion: 1;
  creditMs: number;
  lastRefillMillis: number;
  violationLevel: number;
  lastViolationMillis: number;
  blockedUntilMillis: number;
}
export interface ChatRateLimitDetails {
  reason: 'chat-rate-limited';
  retryAfterMs: number;
  blockedUntilMillis: number;
  serverNowMillis: number;
}
export type ChatGuardDecision =
  | { allowed: true; next: ChatGuardState }
  | { allowed: false; next: ChatGuardState | null; details: ChatRateLimitDetails };
const integer = (value: unknown): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value >= 0;

function valid(value: unknown, now: number): value is ChatGuardState {
  if (!value || typeof value !== 'object' || Object.keys(value).length !== 6) return false;
  const s = value as ChatGuardState;
  return (
    s.policyVersion === CHAT_POLICY_VERSION &&
    integer(s.creditMs) &&
    s.creditMs <= CHAT_CAPACITY * CHAT_REFILL_MS &&
    integer(s.lastRefillMillis) &&
    s.lastRefillMillis <= now &&
    integer(s.violationLevel) &&
    s.violationLevel <= 4 &&
    integer(s.lastViolationMillis) &&
    s.lastViolationMillis <= s.lastRefillMillis &&
    integer(s.blockedUntilMillis) &&
    (s.violationLevel === 0
      ? s.lastViolationMillis === 0 && s.blockedUntilMillis === 0
      : s.blockedUntilMillis === s.lastViolationMillis + CHAT_PENALTIES_MS[s.violationLevel - 1]!)
  );
}
function denied(now: number, until: number, next: ChatGuardState | null): ChatGuardDecision {
  return {
    allowed: false,
    next,
    details: {
      reason: 'chat-rate-limited',
      retryAfterMs: Math.max(1, Math.min(CHAT_MAX_BLOCK_MS, until - now)),
      blockedUntilMillis: until,
      serverNowMillis: now,
    },
  };
}

/** Missing state starts full. Malformed/version-unknown/future state is repaired
 * once to a bounded ten-minute pause, never to a fresh burst or an infinite ban.
 * Logical expiry needs no TTL job. Active pauses cause no write or extension.
 */
export function evaluateChatSend(
  value: unknown,
  now: number,
  forceViolation: boolean = false,
): ChatGuardDecision {
  if (!integer(now) || now > Number.MAX_SAFE_INTEGER - CHAT_RESET_MS)
    throw new RangeError('Invalid server clock');
  if (value !== undefined && !valid(value, now)) {
    const next: ChatGuardState = {
      policyVersion: 1,
      creditMs: 0,
      lastRefillMillis: now,
      violationLevel: 4,
      lastViolationMillis: now,
      blockedUntilMillis: now + CHAT_MAX_BLOCK_MS,
    };
    return denied(now, next.blockedUntilMillis, next);
  }
  const s: ChatGuardState =
    value === undefined
      ? {
          policyVersion: 1,
          creditMs: CHAT_CAPACITY * CHAT_REFILL_MS,
          lastRefillMillis: now,
          violationLevel: 0,
          lastViolationMillis: 0,
          blockedUntilMillis: 0,
        }
      : (value as ChatGuardState);
  if (s.blockedUntilMillis > now) return denied(now, s.blockedUntilMillis, null);
  const reset = now - s.lastViolationMillis >= CHAT_RESET_MS;
  const creditMs = Math.min(CHAT_CAPACITY * CHAT_REFILL_MS, s.creditMs + now - s.lastRefillMillis);
  const next: ChatGuardState = {
    policyVersion: 1,
    creditMs,
    lastRefillMillis: now,
    violationLevel: reset ? 0 : s.violationLevel,
    lastViolationMillis: reset ? 0 : s.lastViolationMillis,
    blockedUntilMillis: reset ? 0 : s.blockedUntilMillis,
  };
  // A stricter server quota overrun is a violation even with available credit.
  // Active pauses returned above: retries never extend them or write state.
  if (!forceViolation && creditMs >= CHAT_REFILL_MS)
    return { allowed: true, next: { ...next, creditMs: creditMs - CHAT_REFILL_MS } };
  next.violationLevel = Math.min(4, next.violationLevel + 1);
  next.lastViolationMillis = now;
  next.blockedUntilMillis = now + CHAT_PENALTIES_MS[next.violationLevel - 1]!;
  return denied(now, next.blockedUntilMillis, next);
}
