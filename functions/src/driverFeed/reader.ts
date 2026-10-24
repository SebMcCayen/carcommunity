import {
  FieldPath, Timestamp, type DocumentSnapshot, type Firestore,
  type Query, type Transaction,
} from 'firebase-admin/firestore';
import { HttpsError, type CallableRequest } from 'firebase-functions/v2/https';
import { toHiddenUidSet } from '../blocking/block-visibility';
import {
  deny, detailSchema, documentId, listSchema, nonnegativeCount, parse, profileFailure,
  READS_PER_HOUR, timestampCursor,
  type Comment, type DetailResponse, type ListResponse, type PageInput, type Post,
} from './contract';

type Request = Pick<CallableRequest<unknown>, 'auth' | 'data'>;
type Author = { name: string; handle: string };

/** One bounded page; no writes to Driver content, profiles, consent, or roles. */
export class DriverFeedReader {
  constructor(private readonly db: Firestore, private readonly now = Date.now) {}

  private uid(request: Request): string {
    if (!request.auth) throw new HttpsError('unauthenticated', 'Sign in to continue.');
    if (request.auth.token.suspended === true) deny('account-restricted');
    if (!documentId.safeParse(request.auth.uid).success) deny('account-restricted');
    return request.auth.uid;
  }

  private async guard(tx: Transaction, uid: string): Promise<void> {
    const [canonical, driver] = await tx.getAll(
      this.db.doc(`users/${uid}`), this.db.doc(`driverUsers/${uid}`),
    );
    const failure = profileFailure(canonical!.data(), driver!.data());
    if (failure) deny(failure);
  }

  private async charge(uid: string): Promise<void> {
    // Reuse Driver's backend-only collection and fixed-hour counter pattern.
    // A stable ID bounds retained documents to one per viewer without new TTL setup.
    const now = this.now();
    const bucket = Math.floor(now / 3_600_000);
    const ref = this.db.doc(`driverRateLimits/${uid}_nativeFeedRead`);
    await this.db.runTransaction(async (tx) => {
      await this.guard(tx, uid);
      const snap = await tx.get(ref);
      const data = snap.data();
      const count = data?.bucket === bucket ? data.count : 0;
      if (!Number.isSafeInteger(count) || count < 0 || count >= READS_PER_HOUR) {
        throw new HttpsError('resource-exhausted', 'Driver feed page limit reached.');
      }
      tx.set(ref, {
        uid, action: 'nativeFeedRead', bucket, count: count + 1,
        expiresAt: Timestamp.fromMillis(now + 7_200_000),
      });
    }, { maxAttempts: 3 });
  }

  private async context(tx: Transaction, uid: string) {
    await this.guard(tx, uid);
    const hidden = toHiddenUidSet((await tx.get(this.db.doc(`blockVisibility/${uid}`))).data());
    // Per invocation/snapshot only; never cache access decisions across requests.
    const authors = new Map<string, Promise<Author | null>>();
    const author = (other: unknown): Promise<Author | null> => {
      if (typeof other !== 'string' || !documentId.safeParse(other).success || hidden.has(other)) {
        return Promise.resolve(null);
      }
      let result = authors.get(other);
      if (!result) {
        result = (async () => {
          const paths = [`users/${other}`, `driverUsers/${other}`];
          if (other !== uid) paths.push(
            `driverUsers/${uid}/blockedUsers/${other}`,
            `driverUsers/${other}/blockedUsers/${uid}`,
            `userBlocks/${uid}/blocked/${other}`,
            `userBlocks/${other}/blocked/${uid}`,
          );
          const [canonical, driver, ...blocks] = await tx.getAll(...paths.map((path) => this.db.doc(path)));
          if (profileFailure(canonical!.data(), driver!.data()) || blocks.some((block) => block.exists)) {
            return null;
          }
          const name: unknown = canonical!.data()?.displayName;
          const handle: unknown = driver!.data()?.handle;
          if (typeof name !== 'string' || !name.trim() || name.length > 120 ||
              typeof handle !== 'string' || !/^[a-z0-9_]{3,24}$/.test(handle)) return null;
          return { name, handle };
        })();
        authors.set(other, result);
      }
      return result;
    };
    return { author };
  }

  private query(query: Query, input: PageInput, direction: 'asc' | 'desc'): Query {
    let ordered = query.orderBy('createdAt', direction).orderBy(FieldPath.documentId(), direction);
    if (input.cursor) {
      const { seconds, nanoseconds, id } = input.cursor;
      ordered = ordered.startAfter(new Timestamp(seconds, nanoseconds), id);
    }
    // No status filter/composite index: scan a bounded raw page, then fail closed.
    return ordered.limit(input.limit);
  }

  private next(docs: DocumentSnapshot[], input: PageInput) {
    if (docs.length < input.limit) return null;
    const last = docs.at(-1)!;
    const cursor = timestampCursor(last.id, last.data()?.createdAt);
    // Never repeat a non-advancing page when the stored sort key is malformed.
    if (!cursor) throw new HttpsError('failed-precondition', 'Invalid stored Driver feed cursor.');
    return cursor;
  }

  private async comment(
    doc: DocumentSnapshot, author: (uid: unknown) => Promise<Author | null>,
  ): Promise<Comment | null> {
    const data = doc.data();
    if (!data || data.status !== 'active' || !timestampCursor(doc.id, data.createdAt) ||
        typeof data.text !== 'string' || !data.text.trim() || data.text.length > 1200) return null;
    const identity = await author(data.authorUid);
    if (!identity) return null;
    return {
      id: doc.id, authorUid: data.authorUid as string,
      authorName: identity.name, authorHandle: identity.handle, text: data.text,
      createdAtMillis: Math.floor((data.createdAt as Timestamp).toMillis()),
    };
  }

  private async post(
    doc: DocumentSnapshot, author: (uid: unknown) => Promise<Author | null>,
  ): Promise<Post | null> {
    const base = await this.comment(doc, author);
    if (!base) return null;
    const data = doc.data()!;
    return {
      ...base,
      mediaUrls: Array.isArray(data.mediaUrls) ? data.mediaUrls.slice(0, 4).filter(
        (url): url is string => typeof url === 'string' && url.length <= 2048 &&
          /^https:\/\/firebasestorage\.googleapis\.com\/v0\/b\/[^/]+\/o\/driverThreadMedia%2F/i.test(url),
      ) : [],
      likeCount: nonnegativeCount(data.likeCount), replyCount: nonnegativeCount(data.replyCount),
    };
  }

  async list(request: Request): Promise<ListResponse> {
    const uid = this.uid(request);
    const input = parse(listSchema, request.data);
    await this.charge(uid);
    return this.db.runTransaction(async (tx) => {
      const { author } = await this.context(tx, uid);
      const page = await tx.get(this.query(this.db.collection('driverThreads'), input, 'desc'));
      const posts = await Promise.all(page.docs.map((doc) => this.post(doc, author)));
      return { posts: posts.filter((post): post is Post => post !== null), nextCursor: this.next(page.docs, input) };
    }, { readOnly: true });
  }

  async detail(request: Request): Promise<DetailResponse> {
    const uid = this.uid(request);
    const input = parse(detailSchema, request.data);
    await this.charge(uid);
    return this.db.runTransaction(async (tx) => {
      const { author } = await this.context(tx, uid);
      // The parent is re-read and authorized in the SAME snapshot on EVERY page.
      const ref = this.db.doc(`driverThreads/${input.threadId}`);
      const post = await this.post(await tx.get(ref), author);
      if (!post) deny('thread-unavailable');
      const page = await tx.get(this.query(ref.collection('replies'), input, 'asc'));
      const comments = await Promise.all(page.docs.map((doc) => this.comment(doc, author)));
      return { post, comments: comments.filter((comment): comment is Comment => comment !== null),
        nextCursor: this.next(page.docs, input) };
    }, { readOnly: true });
  }
}
