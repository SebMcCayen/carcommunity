import { describe, expect, it } from 'vitest';
import type { ProfileProjection } from '../convoy/convoy-core';
import {
  assembleRoster,
  isRsvpStatus,
  parseListAttendeesInput,
  type RsvpEntry,
} from './listAttendees-core';

const profile = (displayName: string | null, avatarPath: string | null = null): ProfileProjection => ({
  displayName,
  avatarPath,
});

describe('parseListAttendeesInput', () => {
  it('accepts a non-empty eventId', () => {
    expect(parseListAttendeesInput({ eventId: 'e1' })).toEqual({ ok: true, input: { eventId: 'e1' } });
  });

  it('trims the eventId', () => {
    const parsed = parseListAttendeesInput({ eventId: '  e1  ' });
    expect(parsed.ok && parsed.input.eventId).toBe('e1');
  });

  it('rejects a missing, empty, or over-long eventId', () => {
    expect(parseListAttendeesInput({}).ok).toBe(false);
    expect(parseListAttendeesInput({ eventId: '' }).ok).toBe(false);
    expect(parseListAttendeesInput({ eventId: '   ' }).ok).toBe(false);
    expect(parseListAttendeesInput({ eventId: 'x'.repeat(129) }).ok).toBe(false);
  });

  it('rejects unknown extra fields', () => {
    expect(parseListAttendeesInput({ eventId: 'e1', extra: 1 }).ok).toBe(false);
  });
});

describe('isRsvpStatus', () => {
  it('accepts the three canonical statuses', () => {
    expect(isRsvpStatus('going')).toBe(true);
    expect(isRsvpStatus('maybe')).toBe(true);
    expect(isRsvpStatus('not_going')).toBe(true);
  });

  it('rejects anything else', () => {
    expect(isRsvpStatus('yes')).toBe(false);
    expect(isRsvpStatus(undefined)).toBe(false);
    expect(isRsvpStatus(null)).toBe(false);
    expect(isRsvpStatus(1)).toBe(false);
  });
});

describe('assembleRoster', () => {
  const noBlocks = () => false;

  it('joins each RSVP with its user projection', () => {
    const entries: RsvpEntry[] = [{ userId: 'u1', status: 'going' }];
    const profiles = new Map([['u1', profile('Alice', 'avatars/u1.jpg')]]);
    expect(assembleRoster(entries, profiles, noBlocks)).toEqual([
      { userId: 'u1', displayName: 'Alice', avatarPath: 'avatars/u1.jpg', status: 'going' },
    ]);
  });

  it('groups by status rank: going, then maybe, then not_going', () => {
    const entries: RsvpEntry[] = [
      { userId: 'u3', status: 'not_going' },
      { userId: 'u1', status: 'going' },
      { userId: 'u2', status: 'maybe' },
    ];
    const profiles = new Map([
      ['u1', profile('A')],
      ['u2', profile('B')],
      ['u3', profile('C')],
    ]);
    expect(assembleRoster(entries, profiles, noBlocks).map((a) => a.status)).toEqual([
      'going',
      'maybe',
      'not_going',
    ]);
  });

  it('sorts by displayName then userId within a status group', () => {
    const entries: RsvpEntry[] = [
      { userId: 'u2', status: 'going' },
      { userId: 'u1', status: 'going' },
      { userId: 'u3', status: 'going' },
    ];
    const profiles = new Map([
      ['u1', profile('Bob')],
      ['u2', profile('Anna')],
      ['u3', profile('Anna')], // tie on name → userId breaks it (u2 before u3)
    ]);
    expect(assembleRoster(entries, profiles, noBlocks).map((a) => a.userId)).toEqual([
      'u2',
      'u3',
      'u1',
    ]);
  });

  it('drops blocked users (either direction is resolved by the caller predicate)', () => {
    const entries: RsvpEntry[] = [
      { userId: 'u1', status: 'going' },
      { userId: 'u2', status: 'going' },
    ];
    const profiles = new Map([
      ['u1', profile('Alice')],
      ['u2', profile('Blocked Bob')],
    ]);
    const isBlocked = (uid: string) => uid === 'u2';
    expect(assembleRoster(entries, profiles, isBlocked).map((a) => a.userId)).toEqual(['u1']);
  });

  it('skips a deleted/missing user (no users doc → no identity to attribute)', () => {
    const entries: RsvpEntry[] = [
      { userId: 'u1', status: 'going' },
      { userId: 'ghost', status: 'maybe' },
    ];
    const profiles = new Map<string, ProfileProjection | undefined | null>([
      ['u1', profile('Alice')],
      ['ghost', undefined], // present-but-undefined is treated the same as absent
    ]);
    expect(assembleRoster(entries, profiles, noBlocks).map((a) => a.userId)).toEqual(['u1']);
  });

  it('keeps an existing account with a blank name, exposing displayName: null', () => {
    const entries: RsvpEntry[] = [{ userId: 'u1', status: 'going' }];
    const profiles = new Map([['u1', profile(null, null)]]);
    expect(assembleRoster(entries, profiles, noBlocks)).toEqual([
      { userId: 'u1', displayName: null, avatarPath: null, status: 'going' },
    ]);
  });

  it('de-duplicates a repeated userId, keeping the first entry', () => {
    const entries: RsvpEntry[] = [
      { userId: 'u1', status: 'going' },
      { userId: 'u1', status: 'not_going' },
    ];
    const profiles = new Map([['u1', profile('Alice')]]);
    const roster = assembleRoster(entries, profiles, noBlocks);
    expect(roster).toHaveLength(1);
    expect(roster[0]?.status).toBe('going');
  });

  it('returns an empty roster when everyone is blocked or deleted', () => {
    const entries: RsvpEntry[] = [
      { userId: 'blocked', status: 'going' },
      { userId: 'ghost', status: 'maybe' },
    ];
    const profiles = new Map<string, ProfileProjection | undefined | null>([
      ['blocked', profile('X')],
    ]);
    expect(assembleRoster(entries, profiles, (uid) => uid === 'blocked')).toEqual([]);
  });

  it('never exposes fields beyond userId, displayName, avatarPath, status', () => {
    const entries: RsvpEntry[] = [{ userId: 'u1', status: 'going' }];
    const profiles = new Map([['u1', profile('Alice', 'a.jpg')]]);
    const roster = assembleRoster(entries, profiles, noBlocks);
    expect(Object.keys(roster[0] ?? {}).sort()).toEqual([
      'avatarPath',
      'displayName',
      'status',
      'userId',
    ]);
  });
});
