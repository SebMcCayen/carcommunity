/**
 * Unit tests for the pure block-visibility helpers (no emulator).
 *
 * The emulator suite (__tests__/block-invisibility.emulator.test.ts) proves the
 * end-to-end bidirectional behaviour; this file pins the pieces the callables
 * lean on, including the ones whose failure modes are silent (a malformed
 * document quietly hiding nothing, an unblock clearing a still-blocked pair).
 */

import { describe, expect, it } from 'vitest';
import {
  filterHiddenAuthors,
  isHiddenUid,
  shouldHidePair,
  toHiddenUidSet,
  MAX_HIDDEN_UIDS,
  HIDDEN_UIDS_FIELD,
} from './block-visibility';

describe('toHiddenUidSet', () => {
  it('reads the stored array into a set', () => {
    expect([...toHiddenUidSet({ [HIDDEN_UIDS_FIELD]: ['a', 'b'] })].sort()).toEqual(['a', 'b']);
  });

  it('returns an empty set for a missing document or field', () => {
    expect(toHiddenUidSet(undefined).size).toBe(0);
    expect(toHiddenUidSet({}).size).toBe(0);
  });

  it('ignores malformed entries rather than throwing', () => {
    // A non-array field, or non-string members, must degrade to "hide what we
    // can parse" — never to an exception inside a chat read path.
    expect(toHiddenUidSet({ [HIDDEN_UIDS_FIELD]: 'not-an-array' }).size).toBe(0);
    const mixed = toHiddenUidSet({ [HIDDEN_UIDS_FIELD]: ['ok', 42, null, '', { a: 1 }] });
    expect([...mixed]).toEqual(['ok']);
  });

  it('dedupes repeated entries', () => {
    expect(toHiddenUidSet({ [HIDDEN_UIDS_FIELD]: ['a', 'a', 'a'] }).size).toBe(1);
  });
});

describe('isHiddenUid', () => {
  const hidden = new Set(['blocked']);

  it('is true only for a member of the set', () => {
    expect(isHiddenUid('blocked', hidden)).toBe(true);
    expect(isHiddenUid('someone-else', hidden)).toBe(false);
  });

  it('is false for an absent author', () => {
    expect(isHiddenUid(null, hidden)).toBe(false);
    expect(isHiddenUid(undefined, hidden)).toBe(false);
  });
});

describe('filterHiddenAuthors', () => {
  interface Msg {
    id: string;
    senderUid: string | null;
  }
  const uidOf = (m: Msg) => m.senderUid;

  it('drops messages authored by a hidden uid and keeps the rest', () => {
    const messages: Msg[] = [
      { id: '1', senderUid: 'alice' },
      { id: '2', senderUid: 'bob' },
      { id: '3', senderUid: 'alice' },
    ];
    const kept = filterHiddenAuthors(messages, uidOf, new Set(['bob']));
    expect(kept.map((m) => m.id)).toEqual(['1', '3']);
  });

  it('returns the SAME array instance when nothing is hidden', () => {
    // The empty set is the hot path (most viewers have blocked nobody), so this
    // must not allocate a copy of every page. Identity, not equality, is the
    // assertion — `toEqual` would pass against a copy too.
    const messages: Msg[] = [{ id: '1', senderUid: 'alice' }];
    expect(filterHiddenAuthors(messages, uidOf, new Set())).toBe(messages);
  });

  it('keeps an item with no resolvable author', () => {
    // These collections take no client writes, so a null author is a malformed
    // backend document — a rendering problem, not a block-evasion route.
    const messages: Msg[] = [{ id: '1', senderUid: null }];
    expect(filterHiddenAuthors(messages, uidOf, new Set(['bob']))).toHaveLength(1);
  });

  it('does not mutate the input', () => {
    const messages: Msg[] = [{ id: '1', senderUid: 'bob' }];
    filterHiddenAuthors(messages, uidOf, new Set(['bob']));
    expect(messages).toHaveLength(1);
  });

  it('performs no per-item lookup — the set is consulted, nothing else', () => {
    // Guards the cost contract the callables depend on: the filter must not be
    // able to grow into a per-message read. A getter that counts calls proves
    // the only work per item is one accessor + one set lookup.
    let calls = 0;
    const counted = (m: Msg) => {
      calls += 1;
      return m.senderUid;
    };
    const messages: Msg[] = Array.from({ length: 50 }, (_, i) => ({
      id: String(i),
      senderUid: i % 2 === 0 ? 'bob' : 'alice',
    }));
    const kept = filterHiddenAuthors(messages, counted, new Set(['bob']));
    expect(kept).toHaveLength(25);
    expect(calls).toBe(50);
  });
});

describe('shouldHidePair', () => {
  it('hides while the edge that changed exists', () => {
    expect(shouldHidePair(true, false)).toBe(true);
    expect(shouldHidePair(true, true)).toBe(true);
  });

  it('KEEPS the pair hidden when the opposite direction is still blocked', () => {
    // The regression this guards: A unblocks B while B still blocks A. Clearing
    // the mirror here would make A visible to B again against B's wishes.
    expect(shouldHidePair(false, true)).toBe(true);
  });

  it('clears only when neither direction is blocked', () => {
    expect(shouldHidePair(false, false)).toBe(false);
  });
});

describe('MAX_HIDDEN_UIDS', () => {
  it('is a bound the mirror document can actually hold', () => {
    // ~28-char uids: 1000 entries ≈ 30 KB, far below Firestore's 1 MiB limit,
    // and small enough that a client listener re-reading it stays cheap.
    expect(MAX_HIDDEN_UIDS).toBe(1000);
    expect(MAX_HIDDEN_UIDS * 32).toBeLessThan(1024 * 1024);
  });
});
