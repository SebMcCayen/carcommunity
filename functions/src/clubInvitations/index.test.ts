import { Timestamp } from 'firebase-admin/firestore';
import { beforeEach, describe, expect, it, vi } from 'vitest';
vi.mock('../firebase', () => ({ db: {}, adminAuth: { getUser: vi.fn() } }));
vi.mock('../notifications/deliver', () => ({ writeInAppNotification: vi.fn() }));
import { clubInvitationNotifications, onInvitationWritten } from './index';

const now = Timestamp.fromMillis(1800000000000);
const pending = {
  clubId: 'club',
  recipientUid: 'recipient',
  inviterUid: 'sender',
  status: 'pending',
  createdAt: now,
  expiresAt: Timestamp.fromMillis(now.toMillis() + 86400000),
};
const event = (before: unknown, after: unknown) => ({
  params: { clubId: 'club', recipientUid: 'recipient' },
  data: { before: { data: () => before }, after: { data: () => after } },
});
const process = vi.spyOn(clubInvitationNotifications, 'process');
beforeEach(() => process.mockReset().mockResolvedValue('delivered'));

describe('canonical invitation trigger wiring', () => {
  it('exports the exact canonical path, retry policy, region and bounded resource settings', () => {
    expect(onInvitationWritten.__endpoint).toMatchObject({
      region: ['europe-west1'],
      minInstances: 0,
      maxInstances: 20,
      cpu: 0.5,
      concurrency: 1,
      availableMemoryMb: 256,
      timeoutSeconds: 60,
      eventTrigger: {
        retry: true,
        eventFilterPathPatterns: { document: 'driverClubs/{clubId}/invitations/{recipientUid}' },
      },
    });
  });
  it('passes a genuine create candidate to the single-writer service', async () => {
    await onInvitationWritten.run(event(undefined, pending) as never);
    expect(process).toHaveBeenCalledExactlyOnceWith('club', 'recipient', pending);
  });
  it.each([undefined, { ...pending, status: 'declined' }, { ...pending, createdAt: 'malformed' }])(
    'ignores deletes and invalid transitions %j',
    async (after) => {
      await onInvitationWritten.run(event(pending, after) as never);
      expect(process).not.toHaveBeenCalled();
    },
  );
  it('ignores retries/metadata edits of an existing pending generation', async () => {
    await onInvitationWritten.run(event(pending, pending) as never);
    expect(process).not.toHaveBeenCalled();
  });
  it('propagates service failures so configured trigger retries can recover', async () => {
    process.mockRejectedValueOnce(new Error('transient'));
    await expect(onInvitationWritten.run(event(undefined, pending) as never)).rejects.toThrow(
      'transient',
    );
  });
});
