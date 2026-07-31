/**
 * Unit tests for the admin users feature module.
 *
 * Focus: the pure `hasMemberSetNickname` decision that the Users list uses to
 * choose between showing a member's chosen nickname and the "No nickname
 * registered" placeholder label. Onboarding stamps `onboardingCompletedAt` and
 * requires a valid nickname, so a set marker is the authoritative "has a
 * nickname" signal; empty / provisioning-placeholder displayNames are the
 * belt-and-suspenders fallback for documents lacking the marker.
 *
 * The Firebase-dependent siblings in the module (callables / firestore) are
 * mocked so importing the module does not require runtime Firebase env vars —
 * these tests exercise only the pure helper.
 */

import { describe, expect, it, vi } from 'vitest';

vi.mock('../lib/firestore', () => ({ getAdminFirestore: () => ({}) }));
vi.mock('../lib/callables', () => ({ callAdmin: vi.fn() }));
vi.mock('firebase/firestore', () => ({
  doc: (...segments: unknown[]) => ({ segments }),
  collection: (...segments: unknown[]) => ({ segments }),
  query: (target: unknown) => target,
  orderBy: () => undefined,
  limit: () => undefined,
  getDoc: vi.fn(),
  getDocs: vi.fn(),
}));

import { hasMemberSetNickname, PROVISIONING_PLACEHOLDER_NAME } from '../features/users';

describe('hasMemberSetNickname', () => {
  it('treats a completed onboarding as a real nickname (authoritative signal)', () => {
    expect(
      hasMemberSetNickname({
        displayName: 'Speedy',
        onboardingCompletedAt: '2026-07-30T10:00:00.000Z',
      }),
    ).toBe(true);
  });

  it('shows the nickname even when it happens to equal the placeholder, once onboarded', () => {
    // A present onboarding marker alone is enough — a genuinely-onboarded member
    // who chose this exact string is never mislabeled.
    expect(
      hasMemberSetNickname({
        displayName: PROVISIONING_PLACEHOLDER_NAME,
        onboardingCompletedAt: '2026-07-30T10:00:00.000Z',
      }),
    ).toBe(true);
  });

  it('flags the raw provisioning placeholder with no onboarding marker as not set', () => {
    expect(
      hasMemberSetNickname({
        displayName: PROVISIONING_PLACEHOLDER_NAME,
        onboardingCompletedAt: null,
      }),
    ).toBe(false);
  });

  it('flags an empty or whitespace-only displayName as not set', () => {
    expect(hasMemberSetNickname({ displayName: '', onboardingCompletedAt: null })).toBe(false);
    expect(hasMemberSetNickname({ displayName: '   ', onboardingCompletedAt: null })).toBe(false);
  });

  it('accepts a real nickname on a document lacking the onboarding marker (fallback)', () => {
    // Old/partial docs may miss the marker; a non-placeholder name still counts.
    expect(
      hasMemberSetNickname({ displayName: 'Rustbucket', onboardingCompletedAt: null }),
    ).toBe(true);
  });
});
