/**
 * Community-chat DIGEST sweep emulator integration test (the QUERY path).
 *
 * Drives runCommunityChatDigest against emulator-seeded state to cover what the
 * pure unit test (communityDigest-core.test.ts) cannot: the last-read range
 * candidate query, the per-member count() aggregation, delivery through
 * writeInAppNotification (opt-out inherited), and the communityChatDigestedUpTo
 * marker preventing a second notice.
 *
 * CI-ONLY: needs the Firestore emulator (no local JVM). Run via:
 *   pnpm emulators:test
 *
 * The community channel (communityChat/global/messages) and userPrivate are shared
 * across the whole emulator run, so this file seeds its messages FAR in the future
 * (year 2099) with a per-run random offset: its "newest message" and every unread
 * count are then computed only against its own messages, immune to real-now data
 * other test files leave behind. Seeded uids carry the same unique suffix.
 */

process.env.FIRESTORE_EMULATOR_HOST ??= '127.0.0.1:8080';
process.env.GCLOUD_PROJECT ??= 'demo-test';

import { getApps as getAdminApps, initializeApp as initializeAdminApp } from 'firebase-admin/app';
import { getFirestore as getAdminFirestore, Timestamp } from 'firebase-admin/firestore';
import { beforeAll, describe, expect, it } from 'vitest';
import { runCommunityChatDigest } from '../chatchannels/communityDigest';
import { COMMUNITY_CHANNEL_ID } from '../chatchannels/chat-core';
import {
  COMMUNITY_DIGEST_MIN_UNREAD,
  communityDigestNotificationId,
} from '../chatchannels/communityDigest-core';

const PROJECT_ID = 'demo-test';

const adminApp =
  getAdminApps()[0] ?? initializeAdminApp({ projectId: PROJECT_ID }, 'communitydigest-emulator-tests');
const adminDb = getAdminFirestore(adminApp);

// Unique-per-run window far in the future so only THIS file's messages count.
const SUFFIX = `${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
const BASE_MS = Date.UTC(2099, 0, 1) + Math.floor(Math.random() * 1e9);
// Five messages, one second apart. NEWEST = BASE_MS + 5000.
const MESSAGE_MS = [1, 2, 3, 4, 5].map((i) => BASE_MS + i * 1000);
const NEWEST_MS = MESSAGE_MS[MESSAGE_MS.length - 1]!;

const now = new Date('2099-06-01T18:00:00.000Z');

function messagesRef() {
  return adminDb.collection('communityChat').doc(COMMUNITY_CHANNEL_ID).collection('messages');
}

function uid(role: string): string {
  return `digest-${role}-${SUFFIX}`;
}

async function seedUser(
  role: string,
  opts: { lastReadAtMs: number | null; digestedUpToMs?: number | null; optedOut?: boolean },
): Promise<string> {
  const id = uid(role);
  await adminDb
    .collection('users')
    .doc(id)
    .set({ displayName: role, role: 'user', activeMember: true, suspended: false, deleted: false });

  const priv: Record<string, unknown> = {};
  if (opts.lastReadAtMs !== null) priv.communityChatLastReadAt = Timestamp.fromMillis(opts.lastReadAtMs);
  if (opts.digestedUpToMs != null) priv.communityChatDigestedUpTo = Timestamp.fromMillis(opts.digestedUpToMs);
  if (opts.optedOut) priv.notificationPreferences = { community_chat: { inApp: false } };
  await adminDb.collection('userPrivate').doc(id).set(priv);
  return id;
}

async function digestItems(userId: string) {
  const snap = await adminDb.collection('notifications').doc(userId).collection('items').get();
  return snap.docs.filter((d) => d.data().category === 'community_chat');
}

beforeAll(async () => {
  // Seed the channel's messages at the fixed future instants.
  await Promise.all(
    MESSAGE_MS.map((ms, i) =>
      messagesRef()
        .doc(`digest-msg-${SUFFIX}-${i}`)
        .set({ senderUid: uid('sender'), text: `m${i}`, createdAt: Timestamp.fromMillis(ms) }),
    ),
  );
}, 60_000);

describe('runCommunityChatDigest — query path', () => {
  it('notifies a behind member with >= threshold unread and advances the marker', async () => {
    // Read before all five messages → 5 unread (>= 3).
    const behind = await seedUser('behind', { lastReadAtMs: BASE_MS });

    await runCommunityChatDigest(now);

    const items = await digestItems(behind);
    expect(items).toHaveLength(1);
    const data = items[0]!.data();
    expect(data.category).toBe('community_chat');
    expect(data.previewText).toContain(String(MESSAGE_MS.length)); // "5 nya meddelanden ..."
    expect(items[0]!.id).toBe(communityDigestNotificationId(now));

    // PRIMARY guard: digest marker advanced to the newest instant.
    const priv = (await adminDb.collection('userPrivate').doc(behind).get()).data()!;
    expect((priv.communityChatDigestedUpTo as Timestamp).toMillis()).toBe(NEWEST_MS);
  });

  it('does NOT notify a member below the threshold', async () => {
    // Read after message #3 → only 2 unread (< 3).
    const nearlyCaught = await seedUser('below', { lastReadAtMs: MESSAGE_MS[2] });

    await runCommunityChatDigest(now);

    expect(await digestItems(nearlyCaught)).toHaveLength(0);
    const priv = (await adminDb.collection('userPrivate').doc(nearlyCaught).get()).data()!;
    expect(priv.communityChatDigestedUpTo).toBeUndefined(); // marker NOT advanced
  });

  it('does NOT re-notify a member already digested up to the newest message', async () => {
    const already = await seedUser('already', { lastReadAtMs: BASE_MS, digestedUpToMs: NEWEST_MS });

    await runCommunityChatDigest(now);

    expect(await digestItems(already)).toHaveLength(0);
  });

  it('respects the community_chat opt-out (inherited) but still advances the marker', async () => {
    const muted = await seedUser('muted', { lastReadAtMs: BASE_MS, optedOut: true });

    await runCommunityChatDigest(now);

    // Opt-out is enforced by writeInAppNotification — no inbox item written.
    expect(await digestItems(muted)).toHaveLength(0);
    // ...but the run decided to notify, so the marker advances (no re-evaluation
    // of the same backlog next run).
    const priv = (await adminDb.collection('userPrivate').doc(muted).get()).data()!;
    expect((priv.communityChatDigestedUpTo as Timestamp).toMillis()).toBe(NEWEST_MS);
  });

  it('is idempotent across runs — one unread run yields at most one notice', async () => {
    const stable = await seedUser('idem', { lastReadAtMs: BASE_MS });

    await runCommunityChatDigest(now);
    await runCommunityChatDigest(now);

    // First run digested + advanced the marker; second run sees already_digested.
    expect(await digestItems(stable)).toHaveLength(1);
  });

  it('advances the marker BEFORE delivery, so a delivery failure never duplicates', async () => {
    // Failure ordering: the marker + notification writes are not atomic. We advance
    // the marker first, so a transient delivery failure yields a MISSED digest (marker
    // advanced, nothing delivered), never a DUPLICATE on the next run. Inject a
    // throwing deliverer to force that failure and assert both halves.
    const flaky = await seedUser('flaky', { lastReadAtMs: BASE_MS });

    const throwingDeliver = () => {
      throw new Error('simulated transient delivery failure');
    };

    // Run 1: delivery throws AFTER the marker advanced.
    const summary = await runCommunityChatDigest(
      now,
      { threshold: COMMUNITY_DIGEST_MIN_UNREAD, maxCandidates: 20_000, pageSize: 400 },
      { deliver: throwingDeliver as never },
    );
    expect(summary.notified).toBeGreaterThanOrEqual(1);

    // Nothing was delivered (the writer threw)...
    expect(await digestItems(flaky)).toHaveLength(0);
    // ...but the marker DID advance to the newest instant.
    const privAfter = (await adminDb.collection('userPrivate').doc(flaky).get()).data()!;
    expect((privAfter.communityChatDigestedUpTo as Timestamp).toMillis()).toBe(NEWEST_MS);

    // Run 2 with the real deliverer: the advanced marker means already-digested, so
    // NO duplicate is delivered for the same backlog.
    await runCommunityChatDigest(now);
    expect(await digestItems(flaky)).toHaveLength(0);
  });

  it('exposes the documented minimum-unread threshold', () => {
    expect(COMMUNITY_DIGEST_MIN_UNREAD).toBe(3);
  });
});
