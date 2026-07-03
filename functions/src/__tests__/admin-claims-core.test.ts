/**
 * Unit tests for functions/src/admin/claims-core.ts — pure guard logic,
 * input parsing, custom-claim computation, and audit/moderation record
 * builders for the admin domain callables.
 */

import { describe, expect, it } from 'vitest';
import {
  buildAdminAuditEvent,
  buildModerationAction,
  computeUpdatedClaims,
  guardActorIsActiveAdmin,
  guardModerationTarget,
  guardSetAdminRole,
  parseModerationInput,
  parseSetAdminRoleInput,
  MODERATION_REASON_MAX_LENGTH,
} from '../admin/claims-core';
import type { UserAccessState } from '../shared/access';

const SERVER_TS = Symbol('serverTimestamp');
const serverTimestamp = () => SERVER_TS;

function state(overrides: Partial<UserAccessState> = {}): UserAccessState {
  return { role: 'user', activeMember: false, suspended: false, deleted: false, ...overrides };
}

describe('parseSetAdminRoleInput', () => {
  it('accepts a valid grant and revoke payload', () => {
    for (const admin of [true, false]) {
      const result = parseSetAdminRoleInput({ targetUid: 'uid-1', admin, reason: 'Trusted mod' });
      expect(result.ok).toBe(true);
      if (result.ok) expect(result.input.admin).toBe(admin);
    }
  });

  it('rejects missing or invalid fields', () => {
    expect(parseSetAdminRoleInput(undefined).ok).toBe(false);
    expect(parseSetAdminRoleInput({}).ok).toBe(false);
    expect(parseSetAdminRoleInput({ targetUid: 'uid-1', admin: true }).ok).toBe(false); // no reason
    expect(parseSetAdminRoleInput({ targetUid: '', admin: true, reason: 'r' }).ok).toBe(false);
    expect(parseSetAdminRoleInput({ targetUid: 'u', admin: 'yes', reason: 'r' }).ok).toBe(false);
  });

  it('rejects unknown fields (strict contract)', () => {
    expect(
      parseSetAdminRoleInput({ targetUid: 'u', admin: true, reason: 'r', role: 'owner' }).ok,
    ).toBe(false);
  });

  it('rejects an over-long reason', () => {
    const reason = 'x'.repeat(MODERATION_REASON_MAX_LENGTH + 1);
    expect(parseSetAdminRoleInput({ targetUid: 'u', admin: true, reason }).ok).toBe(false);
  });
});

describe('parseModerationInput', () => {
  it('accepts a valid payload and trims the reason', () => {
    const result = parseModerationInput({ targetUid: 'uid-1', reason: '  spam  ' });
    expect(result.ok).toBe(true);
    if (result.ok) expect(result.input.reason).toBe('spam');
  });

  it('requires a non-empty reason', () => {
    expect(parseModerationInput({ targetUid: 'uid-1' }).ok).toBe(false);
    expect(parseModerationInput({ targetUid: 'uid-1', reason: '' }).ok).toBe(false);
    expect(parseModerationInput({ targetUid: 'uid-1', reason: '   ' }).ok).toBe(false);
  });

  it('rejects unknown fields', () => {
    expect(parseModerationInput({ targetUid: 'u', reason: 'r', suspended: false }).ok).toBe(false);
  });
});

describe('guardActorIsActiveAdmin', () => {
  it('allows non-suspended admins and owners', () => {
    expect(guardActorIsActiveAdmin(state({ role: 'admin' })).ok).toBe(true);
    expect(guardActorIsActiveAdmin(state({ role: 'owner' })).ok).toBe(true);
  });

  it('denies plain users with permission-denied', () => {
    const result = guardActorIsActiveAdmin(state());
    expect(result).toMatchObject({ ok: false, code: 'permission-denied' });
  });

  it('denies suspended or deleted admins (suspension overrides admin access)', () => {
    expect(guardActorIsActiveAdmin(state({ role: 'admin', suspended: true })).ok).toBe(false);
    expect(guardActorIsActiveAdmin(state({ role: 'owner', deleted: true })).ok).toBe(false);
  });
});

describe('guardSetAdminRole', () => {
  it('allows changing another plain or admin user', () => {
    expect(guardSetAdminRole({ actorUid: 'a', targetUid: 'b', targetRole: 'user' }).ok).toBe(true);
    expect(guardSetAdminRole({ actorUid: 'a', targetUid: 'b', targetRole: 'admin' }).ok).toBe(true);
  });

  it('blocks self-elevation and self-demotion with failed-precondition', () => {
    const result = guardSetAdminRole({ actorUid: 'a', targetUid: 'a', targetRole: 'admin' });
    expect(result).toMatchObject({ ok: false, code: 'failed-precondition' });
  });

  it('never modifies owner accounts', () => {
    const result = guardSetAdminRole({ actorUid: 'a', targetUid: 'b', targetRole: 'owner' });
    expect(result).toMatchObject({ ok: false, code: 'failed-precondition' });
  });
});

describe('guardModerationTarget', () => {
  it('allows an admin to moderate a plain user', () => {
    expect(
      guardModerationTarget({ actorUid: 'a', actorRole: 'admin', targetUid: 'b', targetRole: 'user' })
        .ok,
    ).toBe(true);
  });

  it('blocks self-moderation', () => {
    const result = guardModerationTarget({
      actorUid: 'a',
      actorRole: 'admin',
      targetUid: 'a',
      targetRole: 'admin',
    });
    expect(result).toMatchObject({ ok: false, code: 'failed-precondition' });
  });

  it('blocks admins from moderating owners (legacy moderation-service parity)', () => {
    const result = guardModerationTarget({
      actorUid: 'a',
      actorRole: 'admin',
      targetUid: 'b',
      targetRole: 'owner',
    });
    expect(result).toMatchObject({ ok: false, code: 'permission-denied' });
  });

  it('allows owners to moderate owners', () => {
    expect(
      guardModerationTarget({ actorUid: 'a', actorRole: 'owner', targetUid: 'b', targetRole: 'owner' })
        .ok,
    ).toBe(true);
  });
});

describe('computeUpdatedClaims', () => {
  it('sets true claims and preserves unrelated existing claims', () => {
    expect(computeUpdatedClaims({ activeMember: true, foo: 'bar' }, { admin: true })).toEqual({
      activeMember: true,
      foo: 'bar',
      admin: true,
    });
  });

  it('removes claims set to false instead of writing false (keeps tokens small)', () => {
    expect(computeUpdatedClaims({ admin: true, suspended: true }, { suspended: false })).toEqual({
      admin: true,
    });
  });

  it('leaves claims untouched when no update is provided for them', () => {
    expect(computeUpdatedClaims({ admin: true }, {})).toEqual({ admin: true });
  });

  it('handles a missing existing-claims object', () => {
    expect(computeUpdatedClaims(undefined, { suspended: true })).toEqual({ suspended: true });
  });
});

describe('record builders', () => {
  it('buildAdminAuditEvent includes actor, target, action, reason, and server timestamp', () => {
    const event = buildAdminAuditEvent(
      {
        adminId: 'admin-1',
        action: 'user.suspend',
        targetType: 'user',
        targetId: 'user-2',
        reason: 'ToS violation',
      },
      serverTimestamp,
    );
    expect(event).toEqual({
      adminId: 'admin-1',
      action: 'user.suspend',
      targetType: 'user',
      targetId: 'user-2',
      reason: 'ToS violation',
      createdAt: SERVER_TS,
    });
  });

  it('buildAdminAuditEvent includes optional details when provided', () => {
    const event = buildAdminAuditEvent(
      {
        adminId: 'admin-1',
        action: 'user.setAdminRole',
        targetType: 'user',
        targetId: 'user-2',
        reason: 'Promotion',
        details: { admin: true, role: 'admin' },
      },
      serverTimestamp,
    );
    expect(event.details).toEqual({ admin: true, role: 'admin' });
  });

  it('buildModerationAction produces the moderationActions record shape', () => {
    const action = buildModerationAction(
      {
        targetUserId: 'user-2',
        actorUserId: 'admin-1',
        actionType: 'permanent_suspension',
        reason: 'Spam',
      },
      serverTimestamp,
    );
    expect(action).toEqual({
      targetUserId: 'user-2',
      actorUserId: 'admin-1',
      actionType: 'permanent_suspension',
      reason: 'Spam',
      expiresAt: null,
      createdAt: SERVER_TS,
    });
  });
});
