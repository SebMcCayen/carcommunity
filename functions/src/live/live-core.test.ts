import { describe, expect, it } from 'vitest';
import {
  CONVOY_RIDE_FINALIZE_GRACE_MS,
  LIVE_SESSION_EXTEND_PROMPT_MS,
  LIVE_SESSION_MAX_MS,
  buildSession,
  clampExpiryToCap,
  decideConvoyRideFinalize,
  extendedExpiryIso,
  isSessionActive,
  parseExtendSessionInput,
  type LiveSession,
} from './live-core';

const HOUR_MS = 60 * 60 * 1000;

describe('live-session cost/data-control constants', () => {
  it('caps a session at 6 hours and prompts to extend 15 min before expiry', () => {
    expect(LIVE_SESSION_MAX_MS).toBe(6 * HOUR_MS);
    expect(LIVE_SESSION_EXTEND_PROMPT_MS).toBe(15 * 60 * 1000);
    // The prompt must fire strictly before expiry with room to answer.
    expect(LIVE_SESSION_EXTEND_PROMPT_MS).toBeLessThan(LIVE_SESSION_MAX_MS);
  });

  it('is the SERVER copy of the client cap (Kotlin LiveLocation.LIVE_SESSION_MAX_MS must match)', () => {
    // Cross-boundary agreement is asserted on the client side too
    // (LiveLocationTest). This documents the shared value in one glance.
    expect(LIVE_SESSION_MAX_MS).toBe(21_600_000);
  });
});

describe('clampExpiryToCap', () => {
  const now = Date.parse('2026-07-21T10:00:00.000Z');

  it('passes an in-cap expiry straight through', () => {
    const requested = now + 2 * HOUR_MS;
    expect(clampExpiryToCap(now, requested)).toBe(requested);
  });

  it('clamps an over-cap expiry to now + 6h', () => {
    const requested = now + 100 * HOUR_MS;
    expect(clampExpiryToCap(now, requested)).toBe(now + LIVE_SESSION_MAX_MS);
  });

  it('clamps exactly at the boundary to the boundary', () => {
    expect(clampExpiryToCap(now, now + LIVE_SESSION_MAX_MS)).toBe(now + LIVE_SESSION_MAX_MS);
  });
});

describe('buildSession expiry clamping', () => {
  const now = new Date('2026-07-21T10:00:00.000Z');

  it('sets a 4h session to exactly 4h (under the cap, unchanged)', () => {
    const session = buildSession('id1', '4h', now, 'Sam', null);
    expect(Date.parse(session.expiresAt) - now.getTime()).toBe(4 * HOUR_MS);
  });

  it('sets a 6h session to exactly the 6h cap (the current client default)', () => {
    const session = buildSession('id1b', '6h', now, 'Sam', null);
    expect(Date.parse(session.expiresAt) - now.getTime()).toBe(6 * HOUR_MS);
    expect(Date.parse(session.expiresAt) - now.getTime()).toBe(LIVE_SESSION_MAX_MS);
  });

  it('never mints an expiry beyond the 6h cap', () => {
    const session = buildSession('id2', '1h', now, 'Sam', null);
    expect(Date.parse(session.expiresAt) - now.getTime()).toBeLessThanOrEqual(LIVE_SESSION_MAX_MS);
  });
});

describe('extendedExpiryIso', () => {
  const now = new Date('2026-07-21T10:00:00.000Z');

  it('grants a fresh full 6h window from now', () => {
    const iso = extendedExpiryIso(now);
    expect(Date.parse(iso) - now.getTime()).toBe(LIVE_SESSION_MAX_MS);
  });

  it('is never past the cap regardless of when it is called', () => {
    const later = new Date('2026-07-21T15:45:00.000Z');
    const iso = extendedExpiryIso(later);
    expect(Date.parse(iso)).toBe(later.getTime() + LIVE_SESSION_MAX_MS);
    // An extended session re-prompts 15 min before ITS own new end — the
    // "5h45 checkpoint" repeats every window rather than accumulating.
    expect(Date.parse(iso) - later.getTime() - LIVE_SESSION_EXTEND_PROMPT_MS).toBe(
      LIVE_SESSION_MAX_MS - LIVE_SESSION_EXTEND_PROMPT_MS,
    );
  });
});

describe('extend is only valid on an active, unexpired session', () => {
  const now = new Date('2026-07-21T10:00:00.000Z');

  it('an active unexpired session is extendable', () => {
    const session = buildSession('id3', '1h', now, 'Sam', null);
    expect(isSessionActive(session, now)).toBe(true);
  });

  it('a session past its expiry is NOT extendable (must restart, not resurrect)', () => {
    const session = buildSession('id4', '1h', now, 'Sam', null);
    const afterExpiry = new Date(now.getTime() + HOUR_MS + 1);
    expect(isSessionActive(session, afterExpiry)).toBe(false);
  });
});

describe('parseExtendSessionInput', () => {
  it('accepts an empty object', () => {
    expect(parseExtendSessionInput({})).toEqual({ ok: true, input: {} });
  });

  it('accepts undefined (no payload)', () => {
    expect(parseExtendSessionInput(undefined).ok).toBe(true);
  });

  it('rejects stray fields — the client cannot smuggle a longer window', () => {
    const result = parseExtendSessionInput({ expiresAt: '2099-01-01T00:00:00.000Z' });
    expect(result.ok).toBe(false);
  });
});

describe('decideConvoyRideFinalize (server-side member-run backstop)', () => {
  const start = Date.parse('2026-08-05T10:00:00.000Z');
  // A stopped convoy-auto session ended 30 min into the drive.
  const endedAtIso = new Date(start + 30 * 60 * 1000).toISOString();

  function endedConvoySession(overrides: Partial<LiveSession> = {}): LiveSession {
    return {
      ...buildSession('sess1', '6h', new Date(start), 'Driver', null),
      status: 'stopped',
      stoppedAt: endedAtIso,
      convoyAutoStarted: true,
      convoyId: 'c1',
      ...overrides,
    };
  }

  // Well past the grace window so the client has had its chance to save.
  const afterGrace = new Date(Date.parse(endedAtIso) + CONVOY_RIDE_FINALIZE_GRACE_MS + 1000);

  it('finalizes a stopped convoy-auto session past its grace window', () => {
    const decision = decideConvoyRideFinalize(endedConvoySession(), afterGrace);
    expect(decision.finalize).toBe(true);
    expect(decision.startedAt).toBe(new Date(start).toISOString());
    expect(decision.endedAt).toBe(endedAtIso);
  });

  it('also finalizes an EXPIRED convoy-auto session (6h TTL sweep)', () => {
    const decision = decideConvoyRideFinalize(
      endedConvoySession({ status: 'expired' }),
      afterGrace,
    );
    expect(decision.finalize).toBe(true);
  });

  it('does NOT finalize a manual (non-convoy) session — the user is in-app to save it', () => {
    const decision = decideConvoyRideFinalize(
      endedConvoySession({ convoyAutoStarted: undefined }),
      afterGrace,
    );
    expect(decision).toEqual({ finalize: false, skip: 'not-convoy-auto' });
  });

  it('does NOT finalize twice — the convoyRideFinalized marker gates it', () => {
    const decision = decideConvoyRideFinalize(
      endedConvoySession({ convoyRideFinalized: true }),
      afterGrace,
    );
    expect(decision).toEqual({ finalize: false, skip: 'already-finalized' });
  });

  it('does NOT finalize a still-active session (nothing has ended yet)', () => {
    const active = { ...endedConvoySession(), status: 'active' as const, stoppedAt: null };
    expect(decideConvoyRideFinalize(active, afterGrace)).toEqual({
      finalize: false,
      skip: 'not-ended',
    });
  });

  it('holds off WITHIN the grace window so a live client saves the rich drive first', () => {
    const justEnded = new Date(Date.parse(endedAtIso) + 1000);
    expect(decideConvoyRideFinalize(endedConvoySession(), justEnded)).toEqual({
      finalize: false,
      skip: 'within-grace',
    });
  });

  it('skips a zero/negative-duration session (nothing worth saving)', () => {
    const decision = decideConvoyRideFinalize(
      endedConvoySession({ stoppedAt: new Date(start).toISOString() }),
      afterGrace,
    );
    expect(decision).toEqual({ finalize: false, skip: 'nonpositive-duration' });
  });

  it('skips a session with no stoppedAt to derive an end time from', () => {
    const decision = decideConvoyRideFinalize(
      endedConvoySession({ stoppedAt: null }),
      afterGrace,
    );
    expect(decision).toEqual({ finalize: false, skip: 'missing-times' });
  });
});
