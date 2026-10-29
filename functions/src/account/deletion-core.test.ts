import { describe, expect, it } from 'vitest';
import { parseDeleteAccountInput, PURGE_OWNED_COLLECTIONS, PURGE_DOC_TREES } from './deletion-core';
import { blockMirrorRtdbKey } from './deletion-core';

describe('blockMirrorRtdbKey', () => {
  it('derives the liveLocationBlocks key from the row path, blocker first', () => {
    expect(blockMirrorRtdbKey('userBlocks/blocker-a/blocked/blocked-b')).toBe(
      'blocker-a/blocked-b',
    );
  });

  it('takes the blocked half from the DOCUMENT ID, not the swept uid', () => {
    // blocking/onBlockWrite.ts keys liveLocationBlocks/{blockerUid}/{blockedUid}
    // off the document PATH params, so the purge has to key off the same path or
    // it clears a node the trigger never wrote and orphans the one it did.
    //
    // The block callable always writes `blockedUserId` equal to the document id,
    // so a divergent row is not reachable through the API — it is used here
    // precisely because it is the only input that tells the two derivations
    // apart. A regression that rebuilt this key from the queried uid (the
    // `blockedUserId` field the collection-group sweep matches on) would return
    // 'blocker-a/purged-uid' here and leave the real node behind.
    expect(blockMirrorRtdbKey('userBlocks/blocker-a/blocked/legacy-doc-id')).toBe(
      'blocker-a/legacy-doc-id',
    );
  });

  it('rejects a `blocked` subcollection under any other root', () => {
    expect(blockMirrorRtdbKey('convoys/convoy-1/blocked/blocked-b')).toBeNull();
    expect(blockMirrorRtdbKey('events/event-1/blocked/blocked-b')).toBeNull();
  });

  it('rejects a path that is not userBlocks/{uid}/blocked/{uid}', () => {
    // Wrong subcollection, too shallow, and too deep (a nested `blocked`
    // collection further down a userBlocks tree is not a mirror row).
    expect(blockMirrorRtdbKey('userBlocks/blocker-a/friends/blocked-b')).toBeNull();
    expect(blockMirrorRtdbKey('userBlocks/blocker-a')).toBeNull();
    expect(blockMirrorRtdbKey('userBlocks/blocker-a/blocked/blocked-b/blocked/c')).toBeNull();
  });
});

describe('native deletion account binding', () => {
  it('accepts an expected UID without changing the legacy empty request', () => {
    expect(parseDeleteAccountInput({})).toEqual({ ok: true, input: {} });
    expect(parseDeleteAccountInput({ expectedUid: 'member-a' })).toEqual({
      ok: true,
      input: { expectedUid: 'member-a' },
    });
    expect(parseDeleteAccountInput({ expectedUid: '' }).ok).toBe(false);
  });
});

describe('personal calendar canonical erasure plan', () => {
  it('purges both hash-keyed collections by stored owner UID, not UID document paths', () => {
    const roots = ['clubCalendarSubscriptions', 'clubCalendarTokens'];
    expect(PURGE_OWNED_COLLECTIONS.filter((step) => roots.includes(step.collection))).toEqual([
      { collection: 'clubCalendarSubscriptions', userField: 'uid' },
      { collection: 'clubCalendarTokens', userField: 'uid' },
    ]);
    expect(PURGE_DOC_TREES.filter((root) => roots.includes(root))).toEqual([]);
  });
});
