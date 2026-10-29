import { randomBytes } from 'node:crypto';
import { FieldPath, Timestamp, type Firestore, type Transaction } from 'firebase-admin/firestore';
import type { UserRecord } from 'firebase-admin/auth';
import { HttpsError, type CallableRequest } from 'firebase-functions/v2/https';
import {
  identityKeys,
  providerIdentityKeys,
  isCarCommunityRestricted,
} from '../access/carCommunity-core';
import {
  LIMITS,
  RETENTION_MS,
  SUBSCRIPTIONS,
  TOKENS,
  hash,
  pairRevocationPath,
  id,
  millis,
  epochTime,
  deny,
  parse,
  canonicalEvent,
  journal,
  manageInput,
  subscriptionUrl,
  type CalendarRow,
} from './contract';
import { serializeCalendar } from './ics';

type Data = Record<string, unknown>;
type Identity = Pick<UserRecord, 'disabled' | 'tokensValidAfterTime' | 'providerData'>;
type Request = Pick<CallableRequest<unknown>, 'auth' | 'data'>;
type Subscription = {
  uid: string;
  clubId: string;
  epoch: string;
  authEpoch: string;
  version: number;
  tokenHash: string | null;
  createdAtMillis: number;
  sequenceOriginMillis: number;
  rows: CalendarRow[];
};
const hex = /^[a-f0-9]{64}$/;
const restrictedStatuses = new Set([
  'deleted',
  'suspended',
  'deletionPending',
  'banned',
  'temporarily_suspended',
  'permanently_suspended',
]);
const unchanged = (a: unknown, b: unknown) => JSON.stringify(a) === JSON.stringify(b);
function unavailable(): never {
  throw new HttpsError('unavailable', 'Calendar temporarily unavailable.');
}
function capacity(): never {
  throw new HttpsError('resource-exhausted', 'Calendar capacity exceeded.');
}

/** Serialized membership and token state; never cache an authorization decision. */
export class ClubCalendarService {
  constructor(
    private readonly db: Firestore,
    private readonly getIdentity: (uid: string) => Promise<Identity>,
    private readonly project: string | undefined,
    private readonly now = Date.now,
  ) {}

  private async identity(uid: string): Promise<Identity> {
    try {
      const identity = await this.getIdentity(uid);
      if (
        identity.disabled ||
        !identity.tokensValidAfterTime ||
        !Number.isFinite(Date.parse(identity.tokensValidAfterTime))
      )
        deny();
      return identity;
    } catch (error) {
      if (error instanceof HttpsError) throw error;
      if ((error as { code?: unknown })?.code === 'auth/user-not-found') deny();
      return unavailable();
    }
  }

  /** Port of Driver requireSharedClubMember, with strict membership epoch binding.
   * See docs/contracts/shared-clubs-v1.md. CC access is read-only here: never
   * initialize identities/mirrors from an unauthenticated calendar-provider GET.
   */
  private async guard(tx: Transaction, uid: string, clubId: string, identity: Identity) {
    const [userDoc, configDoc, clubDoc, accountDoc, memberDoc, policyDoc, accessDoc] =
      await tx.getAll(
        ...[
          `users/${uid}`,
          'config/sharedClubs',
          `driverClubs/${clubId}`,
          `driverClubAccounts/${uid}`,
          `driverClubs/${clubId}/members/${uid}`,
          'carCommunityPolicy/access',
          `carCommunityAccess/${uid}`,
        ].map((path) => this.db.doc(path)),
      );
    const user = userDoc!.data(),
      config = configDoc!.data(),
      club = clubDoc!.data();
    const account = accountDoc!.data(),
      member = memberDoc!.data();
    if (
      !(user?.onboardingCompletedAt instanceof Timestamp) ||
      user.suspended === true ||
      user.deleted === true ||
      user.banned === true ||
      restrictedStatuses.has(String(user.status)) ||
      ['suspended', 'deleted', 'banned'].some(
        (key) => user[key] != null && typeof user[key] !== 'boolean',
      ) ||
      (user.status != null && !['active', 'warned'].includes(String(user.status)))
    )
      deny();
    if (
      config?.enabled !== true ||
      !(config.activatedAt instanceof Timestamp) ||
      config.calendarSubscriptionsReady !== true
    )
      throw new HttpsError('failed-precondition', 'Shared clubs are unavailable.');
    if (
      club?.status !== 'active' ||
      club.schemaVersion !== 2 ||
      !id.safeParse(club.ownerUid).success ||
      !member ||
      account?.activeClubId !== clubId ||
      account.deletionRevoked === true ||
      (account.deletionRevoked != null && typeof account.deletionRevoked !== 'boolean') ||
      !['owner', 'moderator', 'member'].includes(String(member.role)) ||
      account.role !== member.role ||
      (member.role === 'owner') !== (club.ownerUid === uid)
    )
      deny();
    const joined = epochTime(member.joinedAt),
      locked = epochTime(account.joinedAt);
    if (
      !joined ||
      joined !== locked ||
      typeof account.intervalId !== 'string' ||
      !/^[A-Za-z0-9._-]{1,200}$/.test(account.intervalId) ||
      ['.', '..'].includes(account.intervalId)
    )
      deny();
    const history = (
      await tx.get(this.db.doc(`driverClubAccounts/${uid}/history/${account.intervalId}`))
    ).data();
    if (
      history?.clubId !== clubId ||
      epochTime(history.joinedAt) !== joined ||
      history.leftAt !== null
    )
      deny();
    if (policyDoc!.data()?.enforceInitialized !== false) {
      const access = accessDoc!.data();
      if (
        access &&
        (['suspended', 'inheritedBlocked'].some(
          (key) => access[key] != null && typeof access[key] !== 'boolean',
        ) ||
          ['googleKey', 'appleKey'].some(
            (key) =>
              access[key] != null &&
              (typeof access[key] !== 'string' || !hex.test(access[key] as string)),
          ) ||
          (access.explicitBlacklistId != null && typeof access.explicitBlacklistId !== 'string'))
      )
        deny();
      const keys = [
        ...new Set([
          ...identityKeys(access),
          ...identityKeys(providerIdentityKeys(identity.providerData)),
        ]),
      ];
      const identities = keys.length
        ? await tx.getAll(...keys.map((key) => this.db.doc(`carCommunityIdentityBlocks/${key}`)))
        : [];
      const blocks = identities.map((snapshot) => snapshot.data() ?? {});
      if (
        blocks.some((block) => block.blocked != null && typeof block.blocked !== 'boolean') ||
        isCarCommunityRestricted(access, blocks)
      )
        deny();
    }
    return {
      epoch: hash(`${uid}\0${clubId}\0${account.intervalId}\0${joined}`),
      authEpoch: identity.tokensValidAfterTime!,
    };
  }

  private stored(value: Data | undefined): Subscription | null {
    if (!value) return null;
    if (
      !id.safeParse(value.uid).success ||
      !id.safeParse(value.clubId).success ||
      typeof value.epoch !== 'string' ||
      !hex.test(value.epoch) ||
      typeof value.authEpoch !== 'string' ||
      !Number.isSafeInteger(value.version) ||
      Number(value.version) < 1 ||
      !(
        value.tokenHash === null ||
        (typeof value.tokenHash === 'string' && hex.test(value.tokenHash))
      ) ||
      typeof value.createdAtMillis !== 'number' ||
      !Number.isFinite(value.createdAtMillis) ||
      typeof value.sequenceOriginMillis !== 'number' ||
      !Number.isFinite(value.sequenceOriginMillis)
    )
      unavailable();
    return { ...value, rows: journal(value.rows) } as Subscription;
  }

  async manage(request: Request) {
    const uid = request.auth?.uid;
    if (!uid) throw new HttpsError('unauthenticated', 'Sign in to continue.');
    if (
      !id.safeParse(uid).success ||
      request.auth?.token.suspended === true ||
      request.auth?.token.banned === true
    )
      deny();
    const input = parse(manageInput, request.data);
    const identity = await this.identity(uid);
    // Callable SDK authentication alone does not check revoked ID-token sessions.
    // Compare the trusted decoded auth_time with the current Admin Auth epoch.
    const authTime = request.auth?.token.auth_time;
    if (
      typeof authTime !== 'number' ||
      !Number.isFinite(authTime) ||
      authTime * 1000 < Date.parse(identity.tokensValidAfterTime!)
    )
      deny();
    // Generate once OUTSIDE transaction retries; raw tokens never enter Firestore.
    const raw = input.action === 'issue' ? randomBytes(32).toString('base64url') : null;
    const url = raw ? subscriptionUrl(this.project, raw) : null;
    const now = this.now(),
      ref = this.db.doc(`${SUBSCRIPTIONS}/${hash(uid)}`);
    return this.db.runTransaction(
      async (tx) => {
        const current = this.stored((await tx.get(ref)).data());
        if (current && current.uid !== uid) deny();
        const version = current?.version ?? 0;
        if (input.action === 'revoke') {
          // Owner cleanup remains possible after leaving/suspension/shared feature closure.
          // Auth is still required; no membership privilege or calendar data is returned.
          if (current && current.clubId !== input.clubId) deny();
          if (input.expectedVersion !== undefined && input.expectedVersion !== version)
            throw new HttpsError('aborted', 'Calendar changed. Refresh status and retry.');
          if (current?.tokenHash) {
            tx.delete(this.db.doc(`${TOKENS}/${current.tokenHash}`));
            tx.set(ref, { ...current, tokenHash: null, version: version + 1 });
            return { active: false as const, version: version + 1 };
          }
          return { active: false as const, version };
        }
        const membership = await this.guard(tx, uid, input.clubId, identity);
        const sameEpoch =
          current?.clubId === input.clubId &&
          current.epoch === membership.epoch &&
          current.authEpoch === membership.authEpoch;
        if (input.action === 'status')
          return { active: Boolean(sameEpoch && current?.tokenHash), version, limits: LIMITS };
        if (input.expectedVersion !== version)
          throw new HttpsError('aborted', 'Calendar changed. Refresh status and retry.');
        // Concurrent issue/rotate/revoke conflicts serialize on this one owner record.
        // Token rotations retain history and never alter another member's link.
        if (current?.tokenHash) tx.delete(this.db.doc(`${TOKENS}/${current.tokenHash}`));
        const tokenHash = hash(raw!);
        const next: Subscription = {
          uid,
          clubId: input.clubId,
          ...membership,
          version: version + 1,
          tokenHash,
          createdAtMillis: now,
          sequenceOriginMillis: sameEpoch ? current!.sequenceOriginMillis : now,
          rows: sameEpoch ? current!.rows : [],
        };
        tx.set(ref, next);
        tx.create(this.db.doc(`${TOKENS}/${tokenHash}`), { uid, subscriptionId: ref.id });
        return {
          active: true as const,
          url: url!,
          webcalUrl: url!.replace(/^https:/, 'webcal:'),
          version: next.version,
          createdAt: new Date(now).toISOString(),
          limits: LIMITS,
        };
      },
      { maxAttempts: 3 },
    );
  }

  async fetch(raw: string): Promise<{ body: string; etag: string }> {
    if (
      !/^[A-Za-z0-9_-]{43}$/.test(raw) ||
      Buffer.from(raw, 'base64url').toString('base64url') !== raw
    )
      deny();
    const tokenHash = hash(raw),
      tokenRef = this.db.doc(`${TOKENS}/${tokenHash}`);
    const locator = (await tokenRef.get()).data();
    if (
      !locator ||
      !id.safeParse(locator.uid).success ||
      locator.subscriptionId !== hash(String(locator.uid))
    )
      deny();
    const uid = locator.uid as string,
      identity = await this.identity(uid);
    const ref = this.db.doc(`${SUBSCRIPTIONS}/${hash(uid)}`),
      now = this.now();
    const result = await this.db.runTransaction(
      async (tx) => {
        const [tokenDoc, subscriptionDoc] = await tx.getAll(tokenRef, ref);
        const current = this.stored(subscriptionDoc!.data()),
          token = tokenDoc!.data();
        if (
          !current ||
          current.uid !== uid ||
          current.tokenHash !== tokenHash ||
          token?.uid !== uid ||
          token.subscriptionId !== ref.id
        )
          deny();
        const membership = await this.guard(tx, uid, current.clubId, identity);
        if (membership.epoch !== current.epoch || membership.authEpoch !== current.authEpoch)
          deny();
        const rows = await this.synchronize(tx, current, now);
        if (!unchanged(rows, current.rows)) tx.set(ref, { ...current, rows });
        const body = serializeCalendar(current.epoch, rows);
        return { body, etag: `"${hash(body)}"` };
      },
      { maxAttempts: 3 },
    );
    // Auth-only disabling/revocation is not a Firestore transaction participant.
    // Recheck after building; never turn an Auth read failure into a cached response.
    const latestIdentity = await this.identity(uid);
    if (latestIdentity.tokensValidAfterTime !== identity.tokensValidAfterTime) deny();
    return result;
  }

  private async synchronize(
    tx: Transaction,
    subscription: Subscription,
    now: number,
  ): Promise<CalendarRow[]> {
    const { clubId } = subscription;
    // A bounded retention window prevents a lifetime event counter from freezing
    // old memberships forever. Future cancellations remain through event + window.
    const previous = subscription.rows.filter((row) => {
      const eventEnd = row.endMillis ?? row.startMillis;
      const retainedThrough =
        row.status === 'CANCELLED' ? Math.max(eventEnd, row.stampMillis) : eventEnd;
      return retainedThrough + RETENTION_MS >= now;
    });
    const [intents, clubRevocation] = await Promise.all([
      tx.get(
        this.db
          .collection(`driverClubs/${clubId}/events`)
          .orderBy(FieldPath.documentId())
          .limit(LIMITS.maxCurrentEvents + 1)
          .select('projectionType', 'clubId', 'eventId', 'createdAt'),
      ),
      tx.get(this.db.doc(`driverClubEventRevocations/club_${clubId}`)),
    ]);
    if (intents.size > LIMITS.maxCurrentEvents) capacity();
    const byId = new Map(intents.docs.map((doc) => [doc.id, doc]));
    if ([...byId.keys()].some((key) => !id.safeParse(key).success)) unavailable();
    const ids = [...new Set([...byId.keys(), ...previous.map((row) => row.id)])].sort();
    if (ids.length > LIMITS.maxRememberedEvents) capacity();
    const old = new Map(previous.map((row) => [row.id, row]));
    const snapshots = ids.length
      ? await tx.getAll(
          ...ids.flatMap((eventId) => [
            this.db.doc(`events/${eventId}`),
            this.db.doc(`driverClubEventRevocations/event_${eventId}`),
            this.db.doc(pairRevocationPath(clubId, eventId)),
          ]),
          {
            fieldMask: [
              'status',
              'title',
              'startsAt',
              'endsAt',
              'locationName',
              'invalidatedThrough',
              'schemaVersion',
              'clubId',
              'eventId',
            ],
          },
        )
      : [];
    const cutoff = (data: Data | undefined): Timestamp | null => {
      if (data?.invalidatedThrough == null) return null;
      if (!(data.invalidatedThrough instanceof Timestamp)) unavailable();
      return data.invalidatedThrough;
    };
    const compare = (
      a: { seconds: number; nanoseconds: number },
      b: { seconds: number; nanoseconds: number },
    ) => a.seconds - b.seconds || a.nanoseconds - b.nanoseconds;
    const clubCutoff = cutoff(clubRevocation.data());
    const result: CalendarRow[] = [];
    for (let index = 0; index < ids.length; index++) {
      const eventId = ids[index]!,
        source = snapshots[index * 3]!.data();
      const prior = old.get(eventId),
        intentDoc = byId.get(eventId),
        intent = intentDoc?.data();
      const eventCutoff = cutoff(snapshots[index * 3 + 1]!.data());
      const pair = snapshots[index * 3 + 2]!.data();
      if (
        pair &&
        (pair.schemaVersion !== 1 ||
          pair.clubId !== clubId ||
          pair.eventId !== eventId ||
          !(pair.invalidatedThrough instanceof Timestamp))
      )
        unavailable();
      const written = intent?.createdAt;
      if (intentDoc && !(written instanceof Timestamp)) unavailable();
      const consentGeneration = written
        ? { seconds: written.seconds, nanoseconds: written.nanoseconds }
        : prior?.consentGeneration;
      const through = pair?.invalidatedThrough as Timestamp | undefined;
      // The canonical writer makes reconsent strictly newer than every cutoff,
      // even within the same ms. Reconciliation must preserve this generation.
      const withdrawn = through && consentGeneration && compare(through, consentGeneration) >= 0;
      if (
        source &&
        !['published', 'completed', 'draft', 'cancelled', 'deleted'].includes(String(source.status))
      )
        unavailable();
      const event = canonicalEvent(eventId, source);
      if (source && ['published', 'completed'].includes(String(source.status)) && !event)
        unavailable();
      const created = intent ? millis(intent.createdAt) : (prior?.intentCreatedMillis ?? null);
      if (
        intent &&
        (intent.projectionType !== 'shared-club-event-v1' ||
          intent.clubId !== clubId ||
          intent.eventId !== eventId ||
          created === null)
      )
        unavailable();
      const effective =
        consentGeneration &&
        [clubCutoff, eventCutoff].every(
          (value) => value === null || compare(consentGeneration, value) > 0,
        );
      // Past, previously delivered appointments remain history when routine expiry
      // removes projections. Explicit cancellation/deletion still creates tombstones.
      // An unversioned legacy row cannot prove that an absent intent expired
      // normally rather than being withdrawn. Emit a conservative tombstone.
      const completedHistory =
        prior?.status === 'CONFIRMED' && consentGeneration && event && event.startMillis <= now;
      const visible =
        event &&
        !withdrawn &&
        ((effective && intent && source?.status === 'published' && event.startMillis > now) ||
          (completedHistory && (effective || source?.status === 'completed')));
      let desired: Omit<CalendarRow, 'sequence' | 'stampMillis'> | null = null;
      if (visible)
        desired = {
          ...event,
          status: 'CONFIRMED',
          intentCreatedMillis: created!,
          consentGeneration: consentGeneration!,
        };
      else if (prior)
        desired = {
          id: eventId,
          title: prior.title,
          startMillis: prior.startMillis,
          endMillis: prior.endMillis,
          locationName: null,
          status: 'CANCELLED',
          intentCreatedMillis: prior.intentCreatedMillis,
          ...(prior.consentGeneration ? { consentGeneration: prior.consentGeneration } : {}),
        };
      if (!desired) continue;
      if (prior) {
        if (
          prior.id === desired.id &&
          prior.title === desired.title &&
          prior.startMillis === desired.startMillis &&
          prior.endMillis === desired.endMillis &&
          prior.locationName === desired.locationName &&
          prior.status === desired.status &&
          prior.intentCreatedMillis === desired.intentCreatedMillis &&
          prior.consentGeneration?.seconds === desired.consentGeneration?.seconds &&
          prior.consentGeneration?.nanoseconds === desired.consentGeneration?.nanoseconds
        ) {
          result.push(prior);
          continue;
        }
        if (prior.sequence >= 2147483646) capacity();
      }
      const sequence = Math.max(
        prior ? prior.sequence + 1 : 0,
        Math.floor((now - subscription.sequenceOriginMillis) / 1000),
      );
      if (sequence > 2147483646) capacity();
      result.push({ ...desired, sequence, stampMillis: Math.max(now, prior?.stampMillis ?? 0) });
    }
    return result;
  }
}
