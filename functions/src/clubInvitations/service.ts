/** Canonical Driver invitations -> the existing CarCommunity inbox writer. */
import { createHash } from 'node:crypto';
import { FieldValue, Timestamp, type Firestore, type Transaction } from 'firebase-admin/firestore';
import type { UserRecord } from 'firebase-admin/auth';
import {
  identityKeys,
  isCarCommunityRestricted,
  providerIdentityKeys,
} from '../access/carCommunity-core';
import type { writeInAppNotification } from '../notifications/deliver';

type Data = Record<string, unknown>;
type Identity = Pick<UserRecord, 'disabled' | 'providerData'>;
type Invite = {
  clubId: string;
  recipientUid: string;
  inviterUid: string;
  createdAt: Timestamp;
  expiresAt: Timestamp;
};
const validId = (value: unknown): value is string =>
  typeof value === 'string' &&
  /^[A-Za-z0-9._-]{1,160}$/.test(value) &&
  !['.', '..'].includes(value);
const hash = (parts: unknown[]) => createHash('sha256').update(JSON.stringify(parts)).digest('hex');
const compare = (a: Timestamp, b: Timestamp) =>
  a.seconds - b.seconds || a.nanoseconds - b.nanoseconds;
const same = (a: unknown, b: unknown) =>
  a instanceof Timestamp && b instanceof Timestamp && a.isEqual(b);
const WEEK_MS = 7 * 86400000;

export function invitationGeneration(
  clubId: string,
  uid: string,
  data: Data | undefined,
): Invite | null {
  if (
    !validId(clubId) ||
    !validId(uid) ||
    !data ||
    data.status !== 'pending' ||
    data.clubId !== clubId ||
    data.recipientUid !== uid ||
    !validId(data.inviterUid) ||
    data.inviterUid === uid ||
    !(data.createdAt instanceof Timestamp) ||
    !(data.expiresAt instanceof Timestamp) ||
    compare(data.expiresAt, data.createdAt) <= 0 ||
    data.expiresAt.toMillis() - data.createdAt.toMillis() > WEEK_MS
  )
    return null;
  return {
    clubId,
    recipientUid: uid,
    inviterUid: data.inviterUid,
    createdAt: data.createdAt,
    expiresAt: data.expiresAt,
  };
}

/** Timestamp nanoseconds are significant; millisecond-only IDs merge distinct generations. */
export function invitationNotificationId(invite: Invite): string {
  return `club_invite_${hash([invite.clubId, invite.recipientUid, invite.inviterUid, invite.createdAt.seconds, invite.createdAt.nanoseconds])}`;
}
export function invitationReceiptPath(clubId: string, uid: string): string {
  return `clubInvitationDeliveries/${hash([clubId, uid])}`;
}

/** Status edits, retries, and extending a live pending invitation are not new invitations. */
export function newInvitationGeneration(
  clubId: string,
  uid: string,
  before: Data | undefined,
  after: Data | undefined,
): Invite | null {
  const invite = invitationGeneration(clubId, uid, after);
  if (!invite) return null;
  if (before) {
    if (
      !(before.createdAt instanceof Timestamp) ||
      compare(invite.createdAt, before.createdAt) <= 0
    )
      return null;
    if (
      before.status === 'pending' &&
      before.expiresAt instanceof Timestamp &&
      compare(before.expiresAt, invite.createdAt) > 0
    )
      return null;
  }
  return invite;
}

function activeUser(user: Data | undefined): boolean {
  return (
    !!user &&
    user.onboardingCompletedAt instanceof Timestamp &&
    ['suspended', 'deleted', 'banned'].every((key) => user[key] == null || user[key] === false) &&
    (user.status == null || ['active', 'warned'].includes(String(user.status)))
  );
}
function activeAccount(account: Data | undefined): boolean {
  return !account || account.deletionRevoked == null || account.deletionRevoked === false;
}

export class ClubInvitationNotifications {
  constructor(
    private readonly db: Firestore,
    private readonly getIdentity: (uid: string) => Promise<Identity>,
    private readonly write: typeof writeInAppNotification,
    private readonly now = Date.now,
  ) {}

  /** Only fixed document gets, no membership scans, collection groups, or audience fan-out. */
  private async current(
    tx: Transaction,
    clubId: string,
    uid: string,
    identity: () => Promise<Identity | null>,
    expected: Invite | string,
  ) {
    const [configSnap, inviteSnap, clubSnap] = await tx.getAll(
      ...[
        'config/sharedClubs',
        `driverClubs/${clubId}/invitations/${uid}`,
        `driverClubs/${clubId}`,
      ].map((path) => this.db.doc(path)),
    );
    const config = configSnap!.data(),
      club = clubSnap!.data();
    const invite = invitationGeneration(clubId, uid, inviteSnap!.data());
    const now = Timestamp.fromMillis(this.now());
    if (
      config?.enabled !== true ||
      config.invitationNotificationsReady !== true ||
      !(config.activatedAt instanceof Timestamp) ||
      compare(config.activatedAt, now) > 0 ||
      !invite ||
      compare(invite.createdAt, now) > 0 ||
      compare(invite.expiresAt, now) <= 0 ||
      club?.schemaVersion !== 2 ||
      club.status !== 'active' ||
      club.visibility !== 'closed' ||
      !validId(club.ownerUid)
    )
      return null;
    if (
      typeof expected === 'string'
        ? invitationNotificationId(invite) !== expected
        : !same(invite.createdAt, expected.createdAt) ||
          !same(invite.expiresAt, expected.expiresAt) ||
          invite.inviterUid !== expected.inviterUid
    )
      return null;
    const sender = invite.inviterUid;
    // Driver checks both canonical and legacy block stores. Match invite and
    // accept: sender/recipient, sender/owner, and recipient/owner. Deduplicate
    // the pairs when the sender is the owner; never scan a block collection.
    const pairs = new Map<string, [string, string]>();
    for (const pair of [
      [uid, sender],
      [uid, club.ownerUid],
      [sender, club.ownerUid],
    ] as [string, string][]) {
      if (pair[0] !== pair[1]) pairs.set(JSON.stringify([...pair].sort()), pair);
    }
    const blockPaths = [...pairs.values()].flatMap(([a, b]) => [
      `userBlocks/${a}/blocked/${b}`,
      `userBlocks/${b}/blocked/${a}`,
      `driverUsers/${a}/blockedUsers/${b}`,
      `driverUsers/${b}/blockedUsers/${a}`,
    ]);
    const [
      userSnap,
      senderSnap,
      recipientAccountSnap,
      senderAccountSnap,
      memberSnap,
      recipientMemberSnap,
      banSnap,
      policySnap,
      accessSnap,
    ] = await tx.getAll(
      ...[
        `users/${uid}`,
        `users/${sender}`,
        `driverClubAccounts/${uid}`,
        `driverClubAccounts/${sender}`,
        `driverClubs/${clubId}/members/${sender}`,
        `driverClubs/${clubId}/members/${uid}`,
        `driverClubs/${clubId}/bans/${uid}`,
        'carCommunityPolicy/access',
        `carCommunityAccess/${uid}`,
      ].map((path) => this.db.doc(path)),
    );
    const account = senderAccountSnap!.data(),
      member = memberSnap!.data();
    if (
      !activeUser(userSnap!.data()) ||
      !activeUser(senderSnap!.data()) ||
      !activeAccount(recipientAccountSnap!.data()) ||
      !activeAccount(account) ||
      recipientMemberSnap!.exists ||
      banSnap!.exists ||
      account?.activeClubId !== clubId ||
      !member ||
      account.role !== member.role ||
      !same(account.joinedAt, member.joinedAt) ||
      !(
        (club.ownerUid === sender && member.role === 'owner') ||
        (club.ownerUid !== sender && member.role === 'moderator')
      )
    )
      return null;
    if (
      (await tx.getAll(...blockPaths.map((path) => this.db.doc(path)))).some((snap) => snap.exists)
    )
      return null;
    const auth = await identity();
    if (!auth || auth.disabled) return null;
    if (policySnap!.data()?.enforceInitialized !== false) {
      const access = accessSnap!.data();
      if (
        access &&
        (['suspended', 'inheritedBlocked'].some(
          (key) => access[key] != null && typeof access[key] !== 'boolean',
        ) ||
          ['googleKey', 'appleKey'].some(
            (key) =>
              access[key] != null &&
              (typeof access[key] !== 'string' || !/^[a-f0-9]{64}$/.test(access[key] as string)),
          ) ||
          (access.explicitBlacklistId != null && typeof access.explicitBlacklistId !== 'string'))
      )
        return null;
      const keys = [
        ...new Set([
          ...identityKeys(access),
          ...identityKeys(providerIdentityKeys(auth.providerData)),
        ]),
      ];
      const blocks = keys.length
        ? (
            await tx.getAll(...keys.map((key) => this.db.doc(`carCommunityIdentityBlocks/${key}`)))
          ).map((snap) => snap.data() ?? {})
        : [];
      if (
        blocks.some((block) => block.blocked != null && typeof block.blocked !== 'boolean') ||
        isCarCommunityRestricted(access, blocks)
      )
        return null;
    }
    // Auth/identity reads can be slow; expiry must still hold after those reads.
    return compare(invite.expiresAt, Timestamp.fromMillis(this.now())) > 0 ? invite : null;
  }

  private identity(uid: string): () => Promise<Identity | null> {
    let pending: Promise<Identity | null> | undefined;
    return () =>
      (pending ??= this.getIdentity(uid).catch((error: unknown) => {
        if ((error as { code?: string })?.code === 'auth/user-not-found') return null;
        throw error; // Retry infrastructure failures; never acknowledge a lost notification.
      }));
  }

  /** Also the backfill seam: a reviewed native-Timestamp candidate, never a force-send. */
  async process(clubId: string, uid: string, candidate: Data | undefined): Promise<string> {
    const generation = invitationGeneration(clubId, uid, candidate);
    if (!generation) return 'invalid';
    const identity = this.identity(uid);
    return this.db.runTransaction(async (tx) => {
      const receiptRef = this.db.doc(invitationReceiptPath(clubId, uid));
      const receipt = (await tx.get(receiptRef)).data();
      if (
        receipt &&
        (!(receipt.generation instanceof Timestamp) ||
          compare(receipt.generation, generation.createdAt) >= 0)
      )
        return 'duplicate';
      const current = await this.current(tx, clubId, uid, identity, generation);
      if (
        !current ||
        !same(current.createdAt, generation.createdAt) ||
        !same(current.expiresAt, generation.expiresAt) ||
        current.inviterUid !== generation.inviterUid
      )
        return 'ineligible';
      const notificationId = invitationNotificationId(current);
      const result = await this.write(
        uid,
        {
          category: 'club_invite',
          title: 'Klubbinbjudan',
          previewText: 'Du har fått en inbjudan till en klubb.',
          actionType: 'open_notifications',
          relatedEntityId: clubId,
        },
        notificationId,
        { transaction: tx, firestore: this.db },
      );
      // Keep one high-water mark per club/recipient independently of inbox retention
      // and deletion. A silenced generation must not be revived by later backfills.
      tx.set(receiptRef, {
        generation: current.createdAt,
        notificationId,
        outcome: result.delivered ? 'delivered' : (result.skippedReason ?? 'skipped'),
        processedAt: FieldValue.serverTimestamp(),
      });
      return result.delivered ? 'delivered' : (result.skippedReason ?? 'skipped');
    });
  }

  /** Push delivery may lag the inbox write. Recheck consent/status/blocks before FCM. */
  async canPush(clubId: string, uid: string, notificationId: string): Promise<boolean> {
    if (!validId(clubId) || !validId(uid)) return false;
    const identity = this.identity(uid);
    return this.db.runTransaction(async (tx) => {
      const receipt = (await tx.get(this.db.doc(invitationReceiptPath(clubId, uid)))).data();
      if (receipt?.notificationId !== notificationId || receipt.outcome !== 'delivered')
        return false;
      const current = await this.current(tx, clubId, uid, identity, notificationId);
      return !!current && same(receipt.generation, current.createdAt);
    });
  }
}
