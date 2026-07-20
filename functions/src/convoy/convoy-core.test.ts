import { describe, expect, it } from 'vitest';
import {
  CONVOY_DESTINATION_LABEL_MAX_LENGTH,
  CONVOY_TITLE_MAX_LENGTH,
  MAX_CONVOY_INVITEES,
  MAX_CONVOY_INVITE_BATCH,
  acceptedMemberUids,
  buildConvoyDocument,
  buildLeaveConvoyUpdate,
  buildMemberEntry,
  computeConvoySummary,
  isAcceptedConvoyMember,
  isConvoyMember,
  liveLocationLatestPath,
  memberEntry,
  parseConvoyIdInput,
  parseCreateConvoyInput,
  parseInviteToConvoyInput,
  parseListConvoysInput,
  parseRespondConvoyInput,
  parseSetConvoyDestinationInput,
  BLOCK_LOOKUP_CHUNK_SIZE,
  MAX_CONVOY_SIZE,
  blockPairKey,
  chunkUids,
  isBlockedAgainstAnyPeer,
  resolvePeerBlockPairs,
  toConvoyDestination,
  toConvoySummary,
  toProfileProjection,
} from './convoy-core';

describe('convoy-core parsing', () => {
  it('parses create strictly (trims title, bounds invitees)', () => {
    expect(parseCreateConvoyInput({ inviteeUids: ['a', 'b'], title: '  Trip  ' })).toEqual({
      ok: true,
      input: { inviteeUids: ['a', 'b'], title: 'Trip' },
    });
    // title is optional.
    expect(parseCreateConvoyInput({ inviteeUids: ['a'] }).ok).toBe(true);
    // must invite at least one.
    expect(parseCreateConvoyInput({ inviteeUids: [] }).ok).toBe(false);
    // too many invitees.
    expect(
      parseCreateConvoyInput({ inviteeUids: Array.from({ length: MAX_CONVOY_INVITEES + 1 }, (_, i) => `u${i}`) }).ok,
    ).toBe(false);
    // title too long.
    expect(parseCreateConvoyInput({ inviteeUids: ['a'], title: 'x'.repeat(CONVOY_TITLE_MAX_LENGTH + 1) }).ok).toBe(false);
    // empty uid.
    expect(parseCreateConvoyInput({ inviteeUids: [''] }).ok).toBe(false);
    // unknown key rejected.
    expect(parseCreateConvoyInput({ inviteeUids: ['a'], extra: 1 }).ok).toBe(false);
    expect(parseCreateConvoyInput(null).ok).toBe(false);
  });

  it('parses respond strictly', () => {
    expect(parseRespondConvoyInput({ convoyId: 'c1', action: 'accept' }).ok).toBe(true);
    expect(parseRespondConvoyInput({ convoyId: 'c1', action: 'decline' }).ok).toBe(true);
    expect(parseRespondConvoyInput({ convoyId: 'c1', action: 'maybe' }).ok).toBe(false);
    expect(parseRespondConvoyInput({ convoyId: '', action: 'accept' }).ok).toBe(false);
    expect(parseRespondConvoyInput({ convoyId: '..', action: 'accept' }).ok).toBe(false);
    expect(parseRespondConvoyInput({ convoyId: 'c1' }).ok).toBe(false);
  });

  it('parses convoyId (start/end) + list strictly', () => {
    expect(parseConvoyIdInput({ convoyId: 'c1' }).ok).toBe(true);
    expect(parseConvoyIdInput({}).ok).toBe(false);
    expect(parseListConvoysInput({}).ok).toBe(true);
    expect(parseListConvoysInput(undefined).ok).toBe(true);
    expect(parseListConvoysInput({ foo: 1 }).ok).toBe(false);
  });
});

describe('convoy-core projections + paths', () => {
  it('projects a profile, coalescing missing/non-string to null', () => {
    expect(toProfileProjection({ displayName: 'Bob', avatarPath: 'p' })).toEqual({
      displayName: 'Bob',
      avatarPath: 'p',
    });
    expect(toProfileProjection(undefined)).toEqual({ displayName: null, avatarPath: null });
    expect(toProfileProjection({ displayName: 42 })).toEqual({ displayName: null, avatarPath: null });
  });

  it('exposes the live-location marker path (reused, not duplicated)', () => {
    expect(liveLocationLatestPath('u-1')).toBe('liveLocation/u-1/latest');
  });
});

describe('convoy-core builders', () => {
  it('builds a member entry (joined sets joinedAt)', () => {
    expect(buildMemberEntry('a', 'owner', 'accepted', () => 'TS', true)).toEqual({
      uid: 'a',
      role: 'owner',
      inviteStatus: 'accepted',
      invitedAt: 'TS',
      joinedAt: 'TS',
    });
    expect(buildMemberEntry('b', 'member', 'invited', () => 'TS', false)).toEqual({
      uid: 'b',
      role: 'member',
      inviteStatus: 'invited',
      invitedAt: 'TS',
      joinedAt: null,
    });
  });

  it('builds a new convoy doc: owner accepted, invitees invited, memberUids covers all', () => {
    const doc = buildConvoyDocument(
      {
        ownerUid: 'owner',
        title: 'Sunday Run',
        ownerProfile: { displayName: 'Owner', avatarPath: 'po' },
        invitees: [
          { uid: 'i1', profile: { displayName: 'One', avatarPath: null } },
          { uid: 'i2', profile: { displayName: 'Two', avatarPath: 'p2' } },
        ],
      },
      () => 'TS',
    );
    expect(doc.ownerUid).toBe('owner');
    expect(doc.title).toBe('Sunday Run');
    expect(doc.status).toBe('forming');
    expect(doc.memberUids).toEqual(['owner', 'i1', 'i2']);
    expect(doc.summary).toBeNull();
    expect(doc.startedAt).toBeNull();
    expect(doc.endedAt).toBeNull();
    const members = doc.members as Record<string, Record<string, unknown>>;
    expect(members.owner).toMatchObject({ role: 'owner', inviteStatus: 'accepted', joinedAt: 'TS' });
    expect(members.i1).toMatchObject({ role: 'member', inviteStatus: 'invited', joinedAt: null });
    const profiles = doc.memberProfiles as Record<string, unknown>;
    expect(profiles.owner).toEqual({ displayName: 'Owner', avatarPath: 'po' });
    expect(profiles.i2).toEqual({ displayName: 'Two', avatarPath: 'p2' });
  });
});

describe('convoy-core membership + summary', () => {
  const doc = {
    ownerUid: 'owner',
    status: 'active',
    memberUids: ['owner', 'i1', 'i2'],
    members: {
      owner: { uid: 'owner', role: 'owner', inviteStatus: 'accepted', joinedAt: null },
      i1: { uid: 'i1', role: 'member', inviteStatus: 'accepted', joinedAt: null },
      i2: { uid: 'i2', role: 'member', inviteStatus: 'declined', joinedAt: null },
    },
  };

  it('checks convoy membership via memberUids', () => {
    expect(isConvoyMember(doc, 'i2')).toBe(true);
    expect(isConvoyMember(doc, 'stranger')).toBe(false);
    expect(isConvoyMember(undefined, 'owner')).toBe(false);
  });

  it('reads a member entry', () => {
    expect(memberEntry(doc, 'i1')).toMatchObject({ role: 'member', inviteStatus: 'accepted' });
    expect(memberEntry(doc, 'stranger')).toBeUndefined();
  });

  it('collects only accepted members (owner + accepted invitees)', () => {
    expect(acceptedMemberUids(doc).sort()).toEqual(['i1', 'owner']);
  });

  it('computes the end summary (duration from startedAt, accepted participants)', () => {
    const started = new Date('2026-07-12T10:00:00.000Z');
    const ended = new Date('2026-07-12T11:30:00.000Z');
    const summary = computeConvoySummary(
      { ...doc, startedAt: started, createdAt: new Date('2026-07-12T09:00:00.000Z') },
      ended,
      (v) => (v instanceof Date ? v : null),
    );
    expect(summary.durationSeconds).toBe(90 * 60);
    expect(summary.participantUids.sort()).toEqual(['i1', 'owner']);
    expect(summary.participantCount).toBe(2);
    expect(summary.distanceMeters).toBeNull();
  });

  it('falls back to createdAt when the convoy was ended before starting', () => {
    const created = new Date('2026-07-12T09:00:00.000Z');
    const ended = new Date('2026-07-12T09:10:00.000Z');
    const summary = computeConvoySummary(
      { ...doc, startedAt: null, createdAt: created },
      ended,
      (v) => (v instanceof Date ? v : null),
    );
    expect(summary.durationSeconds).toBe(10 * 60);
  });
});

describe('convoy-core toConvoySummary', () => {
  const iso = (v: unknown): string | null => (typeof v === 'string' ? v : null);
  const data = {
    ownerUid: 'owner',
    title: 'Trip',
    status: 'forming',
    memberUids: ['owner', 'i1', 'i2'],
    members: {
      owner: { uid: 'owner', role: 'owner', inviteStatus: 'accepted', joinedAt: 'T0' },
      i1: { uid: 'i1', role: 'member', inviteStatus: 'accepted', joinedAt: 'T1' },
      i2: { uid: 'i2', role: 'member', inviteStatus: 'invited', joinedAt: null },
    },
    memberProfiles: {
      owner: { displayName: 'Owner', avatarPath: 'po' },
      i1: { displayName: 'One', avatarPath: null },
      i2: { displayName: 'Two', avatarPath: null },
    },
    summary: null,
    createdAt: 'TC',
    startedAt: null,
    endedAt: null,
  };

  it('projects the roster (owner first), viewer membership, and live-position set', () => {
    const summary = toConvoySummary('c1', data, 'i2', iso);
    expect(summary.convoyId).toBe('c1');
    expect(summary.ownerUid).toBe('owner');
    expect(summary.title).toBe('Trip');
    expect(summary.members[0]!.role).toBe('owner');
    expect(summary.members.map((m) => m.uid)).toEqual(['owner', 'i1', 'i2']);
    // The invitee viewer sees their own pending status (the green dot source).
    expect(summary.viewer).toEqual({ role: 'member', inviteStatus: 'invited' });
    // Only accepted members feed the live-position subscription set.
    expect(summary.livePositionUids.sort()).toEqual(['i1', 'owner']);
    expect(summary.createdAt).toBe('TC');
  });

  it('viewer is null for a non-member and summary maps when present', () => {
    const ended = {
      ...data,
      status: 'ended',
      summary: { durationSeconds: 120, participantUids: ['owner', 'i1'], participantCount: 2, distanceMeters: null },
    };
    const summary = toConvoySummary('c1', ended, 'stranger', iso);
    expect(summary.viewer).toBeNull();
    expect(summary.summary).toEqual({
      durationSeconds: 120,
      participantUids: ['owner', 'i1'],
      participantCount: 2,
      distanceMeters: null,
    });
  });
});

describe('convoy-core leave/invite/destination parsing', () => {
  it('parses invite strictly (bounded batch, non-empty)', () => {
    expect(parseInviteToConvoyInput({ convoyId: 'c1', inviteeUids: ['a', 'b'] })).toEqual({
      ok: true,
      input: { convoyId: 'c1', inviteeUids: ['a', 'b'] },
    });
    expect(parseInviteToConvoyInput({ convoyId: 'c1', inviteeUids: [] }).ok).toBe(false);
    expect(
      parseInviteToConvoyInput({
        convoyId: 'c1',
        inviteeUids: Array.from({ length: MAX_CONVOY_INVITE_BATCH + 1 }, (_, i) => `u${i}`),
      }).ok,
    ).toBe(false);
    expect(parseInviteToConvoyInput({ inviteeUids: ['a'] }).ok).toBe(false);
    expect(parseInviteToConvoyInput({ convoyId: 'c1', inviteeUids: ['a'], extra: 1 }).ok).toBe(false);
  });

  it('parses setDestination: bounds, finiteness, and label normalization', () => {
    expect(parseSetConvoyDestinationInput({ convoyId: 'c1', latitude: 57.4879, longitude: 12.076 })).toEqual({
      ok: true,
      input: { convoyId: 'c1', latitude: 57.4879, longitude: 12.076 },
    });
    // Extremes are inclusive.
    expect(parseSetConvoyDestinationInput({ convoyId: 'c1', latitude: -90, longitude: 180 }).ok).toBe(true);
    // Out of range.
    expect(parseSetConvoyDestinationInput({ convoyId: 'c1', latitude: 90.1, longitude: 0 }).ok).toBe(false);
    expect(parseSetConvoyDestinationInput({ convoyId: 'c1', latitude: 0, longitude: -180.1 }).ok).toBe(false);
    // NaN and Infinity are BOTH rejected — Infinity survives a JSON round-trip
    // in some clients and would poison downstream distance math.
    expect(parseSetConvoyDestinationInput({ convoyId: 'c1', latitude: Number.NaN, longitude: 0 }).ok).toBe(false);
    expect(
      parseSetConvoyDestinationInput({ convoyId: 'c1', latitude: Number.POSITIVE_INFINITY, longitude: 0 }).ok,
    ).toBe(false);
    expect(
      parseSetConvoyDestinationInput({ convoyId: 'c1', latitude: 0, longitude: Number.NEGATIVE_INFINITY }).ok,
    ).toBe(false);
    // Coordinates are required.
    expect(parseSetConvoyDestinationInput({ convoyId: 'c1', latitude: 10 }).ok).toBe(false);
    // Label: trimmed, blank collapses to ABSENT (never stored as '').
    const trimmed = parseSetConvoyDestinationInput({
      convoyId: 'c1',
      latitude: 0,
      longitude: 0,
      label: '  Kungsbacka torg  ',
    });
    expect(trimmed).toEqual({
      ok: true,
      input: { convoyId: 'c1', latitude: 0, longitude: 0, label: 'Kungsbacka torg' },
    });
    const blank = parseSetConvoyDestinationInput({ convoyId: 'c1', latitude: 0, longitude: 0, label: '   ' });
    expect(blank.ok).toBe(true);
    expect(blank.ok && blank.input.label).toBeUndefined();
    // Over-length is REJECTED, never truncated (a shortened address is a wrong
    // address).
    expect(
      parseSetConvoyDestinationInput({
        convoyId: 'c1',
        latitude: 0,
        longitude: 0,
        label: 'x'.repeat(CONVOY_DESTINATION_LABEL_MAX_LENGTH + 1),
      }).ok,
    ).toBe(false);
    expect(
      parseSetConvoyDestinationInput({
        convoyId: 'c1',
        latitude: 0,
        longitude: 0,
        label: 'x'.repeat(CONVOY_DESTINATION_LABEL_MAX_LENGTH),
      }).ok,
    ).toBe(true);
    expect(parseSetConvoyDestinationInput({ convoyId: 'c1', latitude: 0, longitude: 0, extra: 1 }).ok).toBe(false);
  });
});

describe('convoy-core accepted membership + leave', () => {
  const doc = {
    ownerUid: 'owner',
    status: 'active',
    memberUids: ['owner', 'i1', 'i2', 'i3'],
    members: {
      owner: { uid: 'owner', role: 'owner', inviteStatus: 'accepted' },
      i1: { uid: 'i1', role: 'member', inviteStatus: 'accepted' },
      i2: { uid: 'i2', role: 'member', inviteStatus: 'invited' },
      i3: { uid: 'i3', role: 'member', inviteStatus: 'declined' },
    },
    memberProfiles: {
      owner: { displayName: 'Owner', avatarPath: null },
      i1: { displayName: 'One', avatarPath: null },
      i2: { displayName: 'Two', avatarPath: null },
      i3: { displayName: 'Three', avatarPath: null },
    },
  };

  it('distinguishes ACCEPTED membership from bare membership', () => {
    expect(isConvoyMember(doc, 'i2')).toBe(true);
    // ...but a still-invited member has not joined, so the action gates refuse.
    expect(isAcceptedConvoyMember(doc, 'i2')).toBe(false);
    expect(isAcceptedConvoyMember(doc, 'i3')).toBe(false);
    expect(isAcceptedConvoyMember(doc, 'i1')).toBe(true);
    expect(isAcceptedConvoyMember(doc, 'owner')).toBe(true);
    expect(isAcceptedConvoyMember(doc, 'stranger')).toBe(false);
    expect(isAcceptedConvoyMember(undefined, 'owner')).toBe(false);
  });

  it('removes the leaver from ALL THREE membership collections at once', () => {
    const update = buildLeaveConvoyUpdate(doc, 'i1');
    // memberUids is what the rules read gate, the convoy-chat gate, and the
    // list query all use — the leaver must drop out of it or nothing is revoked.
    expect(update.memberUids).toEqual(['owner', 'i2', 'i3']);
    expect(Object.keys(update.members).sort()).toEqual(['i2', 'i3', 'owner']);
    expect(Object.keys(update.memberProfiles).sort()).toEqual(['i2', 'i3', 'owner']);
    // Only the OWNER is still accepted.
    expect(update.remainingAcceptedCount).toBe(1);
    // The source document is not mutated (the callable writes the copy).
    expect(doc.memberUids).toEqual(['owner', 'i1', 'i2', 'i3']);
    expect(Object.keys(doc.members).length).toBe(4);
  });

  it('leaves the convoy alive when the LAST non-owner leaves (owner alone, count 1)', () => {
    const twoPerson = {
      ownerUid: 'owner',
      memberUids: ['owner', 'i1'],
      members: {
        owner: { uid: 'owner', role: 'owner', inviteStatus: 'accepted' },
        i1: { uid: 'i1', role: 'member', inviteStatus: 'accepted' },
      },
      memberProfiles: { owner: {}, i1: {} },
    };
    const update = buildLeaveConvoyUpdate(twoPerson, 'i1');
    expect(update.memberUids).toEqual(['owner']);
    // Not zero, and not a signal to auto-end: the owner is still in their convoy.
    expect(update.remainingAcceptedCount).toBe(1);
  });
});

describe('convoy-core destination projection', () => {
  const iso = (v: unknown): string | null => (typeof v === 'string' ? v : null);

  it('maps a stored destination onto the summary the client already reads', () => {
    const withDestination = {
      ownerUid: 'owner',
      status: 'active',
      memberUids: ['owner'],
      members: { owner: { uid: 'owner', role: 'owner', inviteStatus: 'accepted' } },
      memberProfiles: { owner: { displayName: 'Owner', avatarPath: null } },
      destination: {
        latitude: 57.4879,
        longitude: 12.076,
        label: 'Kungsbacka torg',
        setByUid: 'owner',
        setByDisplayName: 'Owner',
        setAt: 'TS',
      },
    };
    expect(toConvoySummary('c1', withDestination, 'owner', iso).destination).toEqual({
      latitude: 57.4879,
      longitude: 12.076,
      label: 'Kungsbacka torg',
      setByUid: 'owner',
      setByDisplayName: 'Owner',
      setAt: 'TS',
    });
  });

  it('is null when absent', () => {
    expect(toConvoyDestination(undefined, iso)).toBeNull();
    expect(toConvoyDestination(null, iso)).toBeNull();
    expect(toConvoyDestination('somewhere', iso)).toBeNull();
  });

  it('treats a structurally unusable destination as ABSENT, never as 0/0', () => {
    // Handing a driver the Gulf of Guinea is worse than showing nothing.
    expect(toConvoyDestination({ longitude: 12 }, iso)).toBeNull();
    expect(toConvoyDestination({ latitude: 57 }, iso)).toBeNull();
    expect(toConvoyDestination({ latitude: '57', longitude: 12 }, iso)).toBeNull();
    expect(toConvoyDestination({ latitude: Number.NaN, longitude: 12 }, iso)).toBeNull();
    expect(toConvoyDestination({ latitude: 57, longitude: Number.POSITIVE_INFINITY }, iso)).toBeNull();
  });

  it('coalesces an optional label / attribution to null rather than dropping the pin', () => {
    // A map long-press has no place name, and an old destination may predate
    // the denormalized display name — neither is a reason to lose the coordinate.
    expect(toConvoyDestination({ latitude: 57, longitude: 12, setByUid: 'u1' }, iso)).toEqual({
      latitude: 57,
      longitude: 12,
      label: null,
      setByUid: 'u1',
      setByDisplayName: null,
      setAt: null,
    });
    expect(toConvoyDestination({ latitude: 57, longitude: 12, label: '' }, iso)?.label).toBeNull();
    // An unattributable destination keeps setByUid '' — the client treats that
    // as "someone else's", so it never offers a clear the server would refuse.
    expect(toConvoyDestination({ latitude: 57, longitude: 12 }, iso)?.setByUid).toBe('');
  });
});


describe('peer block resolution (convoy.invite fan-out cost)', () => {
  /**
   * A recording stand-in for the Firestore lookup. `reads` is the number of
   * billed reads the real implementation would incur: a
   * `documentId() in [...]` query bills per document RETURNED, and one read
   * when it returns nothing.
   */
  function recorder(blocks: Array<[string, string]> = []) {
    const edges = new Set(blocks.map(([a, b]) => blockPairKey(a, b)));
    const calls: Array<{ blocker: string; ids: string[] }> = [];
    const queryBlocked = async (blocker: string, ids: string[]) => {
      calls.push({ blocker, ids });
      return ids.filter((id) => edges.has(blockPairKey(blocker, id)));
    };
    return {
      queryBlocked,
      calls,
      get reads() {
        return calls.reduce((total, call) => {
          const hits = call.ids.filter((id) => edges.has(blockPairKey(call.blocker, id))).length;
          return total + Math.max(hits, 1);
        }, 0);
      },
    };
  }

  const uids = (prefix: string, count: number) =>
    Array.from({ length: count }, (_, i) => `${prefix}${i}`);

  it('costs candidates + peers reads, not 2 x candidates x peers', async () => {
    // The shipped worst case: a full invite batch into a convoy that is one
    // seat short of full.
    const candidates = uids('c', 25);
    const peers = uids('p', MAX_CONVOY_SIZE - 1); // 24 accepted peers
    const rec = recorder();

    const pairs = await resolvePeerBlockPairs(candidates, peers, rec.queryBlocked);

    expect(pairs.size).toBe(0);
    // TEETH: the assertion is the READ COUNT, not the outcome. The per-pair
    // point-read shape this replaced answered the same matrix in
    // 2 * 25 * 24 = 1200 reads and would pass any outcome-only assertion here.
    expect(rec.reads).toBe(candidates.length + peers.length); // 49
    expect(rec.reads).toBeLessThan(2 * candidates.length * peers.length);
    // ...and every lookup is a single chunk at these bounds.
    expect(rec.calls).toHaveLength(candidates.length + peers.length);
    expect(rec.calls.every((c) => c.ids.length <= BLOCK_LOOKUP_CHUNK_SIZE)).toBe(true);
  });

  it('finds a block made in EITHER direction', async () => {
    const rec = recorder([
      ['cand1', 'peerA'], // the candidate blocked a peer
      ['peerB', 'cand2'], // a peer blocked the candidate
    ]);
    const pairs = await resolvePeerBlockPairs(
      ['cand1', 'cand2', 'cand3'],
      ['peerA', 'peerB'],
      rec.queryBlocked,
    );

    expect(isBlockedAgainstAnyPeer('cand1', ['peerA', 'peerB'], pairs)).toBe(true);
    expect(isBlockedAgainstAnyPeer('cand2', ['peerA', 'peerB'], pairs)).toBe(true);
    expect(isBlockedAgainstAnyPeer('cand3', ['peerA', 'peerB'], pairs)).toBe(false);
    // Direction is preserved in the key, so neither hit is mistaken for the other.
    expect(pairs.has(blockPairKey('cand1', 'peerA'))).toBe(true);
    expect(pairs.has(blockPairKey('peerA', 'cand1'))).toBe(false);
  });

  it('still peer-checks a uid that appears on BOTH sides', async () => {
    // A requested uid who is already a member is also an existing PEER. If it
    // were dropped from the peer list, everyone else in the batch would lose
    // their block check against them.
    const rec = recorder([['dup', 'cand']]);
    const pairs = await resolvePeerBlockPairs(['cand', 'dup'], ['dup'], rec.queryBlocked);
    expect(isBlockedAgainstAnyPeer('cand', ['dup'], pairs)).toBe(true);
  });

  it('chunks at the documentId() in [...] limit instead of failing', async () => {
    // Raising MAX_CONVOY_SIZE must degrade into more queries, never into a
    // runtime INVALID_ARGUMENT from an over-long disjunction.
    const peers = uids('p', BLOCK_LOOKUP_CHUNK_SIZE + 5);
    const rec = recorder();
    await resolvePeerBlockPairs(['solo'], peers, rec.queryBlocked);

    expect(rec.calls.every((c) => c.ids.length <= BLOCK_LOOKUP_CHUNK_SIZE)).toBe(true);
    // one candidate x 2 chunks of peers, plus one lookup per peer
    expect(rec.calls).toHaveLength(2 + peers.length);
    expect(chunkUids(peers, BLOCK_LOOKUP_CHUNK_SIZE).map((c) => c.length)).toEqual([30, 5]);
  });

  it('reads NOTHING when there are no peers (the convoy.create path)', async () => {
    const rec = recorder();
    const pairs = await resolvePeerBlockPairs(uids('c', 25), [], rec.queryBlocked);
    expect(pairs.size).toBe(0);
    expect(rec.calls).toHaveLength(0);
  });

  it('de-duplicates repeated uids so a padded request cannot multiply the cost', async () => {
    const rec = recorder();
    await resolvePeerBlockPairs(['a', 'a', 'a', 'b'], ['p', 'p'], rec.queryBlocked);
    expect(rec.calls).toHaveLength(3); // a, b, p
  });
});
