#!/usr/bin/env node
/** Bounded operator-only canonical invitation backfill. No Firebase bootstrap on import. */
import { constants } from 'node:fs';
import { open, rename, unlink } from 'node:fs/promises';
import { isAbsolute, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { randomUUID } from 'node:crypto';

export const CAPS = Object.freeze({ clubs: 25, invitations: 100, page: 25 });
const id = (value) =>
  typeof value === 'string' &&
  /^[A-Za-z0-9._-]{1,160}$/.test(value) &&
  !['.', '..'].includes(value);
const emptyCursor = () => ({ clubAfter: null, clubId: null, invitationAfter: null });
export function validateCursor(cursor) {
  if (
    !cursor ||
    Object.keys(cursor).sort().join() !== 'clubAfter,clubId,invitationAfter' ||
    !Object.values(cursor).every((value) => value === null || id(value)) ||
    (!cursor.clubId && cursor.invitationAfter) ||
    (cursor.clubId && cursor.clubAfter && cursor.clubId <= cursor.clubAfter)
  )
    throw new Error('Invalid cursor.');
  return { ...cursor };
}

export function parseOptions(args, env = process.env) {
  const options = { apply: false, emulator: false };
  for (let i = 0; i < args.length; i++) {
    const key = args[i];
    if (key === '--apply' || key === '--emulator') {
      const field = key.slice(2);
      if (options[field]) throw new Error('Duplicate flag.');
      options[field] = true;
    } else if (['--project', '--state', '--manifest'].includes(key)) {
      const field = key.slice(2),
        value = args[++i];
      if (options[field] || !value || value.startsWith('--')) throw new Error('Invalid option.');
      options[field] = value;
    } else throw new Error('Unknown option.');
  }
  if (
    !options.project ||
    !/^[a-z][a-z0-9-]{4,62}$/.test(options.project) ||
    !options.state ||
    !isAbsolute(options.state) ||
    (options.manifest &&
      (!isAbsolute(options.manifest) || resolve(options.manifest) === resolve(options.state)))
  )
    throw new Error('Explicit --project and absolute --state are required.');
  for (const key of ['GCLOUD_PROJECT', 'GOOGLE_CLOUD_PROJECT']) {
    if (env[key] && env[key] !== options.project) throw new Error('Project mismatch.');
  }
  if (env.FIREBASE_CONFIG && JSON.parse(env.FIREBASE_CONFIG).projectId !== options.project)
    throw new Error('Project mismatch.');
  const demo = /^demo-[a-z0-9-]+$/.test(options.project);
  if (options.emulator) {
    const match = /^127\.0\.0\.1:([1-9][0-9]{0,4})$/.exec(env.FIRESTORE_EMULATOR_HOST ?? '');
    if (
      !demo ||
      !match ||
      Number(match[1]) > 65535 ||
      env.KCC_TEST_NO_CLOUD !== '1' ||
      env.GOOGLE_APPLICATION_CREDENTIALS ||
      env.FIREBASE_TOKEN
    )
      throw new Error(
        'Emulator mode requires guarded loopback demo transport without cloud credentials.',
      );
  } else if (demo || env.FIRESTORE_EMULATOR_HOST || env.KCC_TEST_NO_CLOUD === '1') {
    throw new Error('Demo/emulator environments require --emulator.');
  }
  return options;
}

/** Injected SDK/service seam; never creates notifications itself. All queries have limits. */
export async function runBackfill({
  db,
  service,
  invitationGeneration,
  apply = false,
  cursor = emptyCursor(),
  checkpoint = async () => {},
  candidate = async () => {},
  now = Date.now,
}) {
  let position = validateCursor(cursor);
  const summary = {
    dryRun: !apply,
    clubsRead: 0,
    invitationDocsRead: 0,
    candidates: 0,
    delivered: 0,
    duplicate: 0,
    optedOut: 0,
    skipped: 0,
    incomplete: false,
  };
  const save = async () => checkpoint({ ...position });
  async function visit(club) {
    const data = club.data();
    if (
      club.exists &&
      data?.schemaVersion === 2 &&
      data.status === 'active' &&
      data.visibility === 'closed'
    ) {
      for (;;) {
        const limit = Math.min(CAPS.page, CAPS.invitations - summary.invitationDocsRead);
        if (!limit) {
          summary.incomplete = true;
          return false;
        }
        let query = club.ref.collection('invitations').orderBy('__name__').limit(limit);
        if (position.invitationAfter) query = query.startAfter(position.invitationAfter);
        const page = await query.get();
        summary.invitationDocsRead += page.size;
        for (const snapshot of page.docs) {
          const invite = invitationGeneration(club.id, snapshot.id, snapshot.data());
          if (
            invite &&
            invite.createdAt.toMillis() <= now() &&
            invite.expiresAt.toMillis() > now()
          ) {
            summary.candidates++;
            const outcome = apply
              ? await service.process(club.id, snapshot.id, snapshot.data())
              : 'candidate';
            if (outcome === 'delivered' || outcome === 'duplicate') summary[outcome]++;
            else if (outcome === 'opted_out') summary.optedOut++;
            else if (apply) summary.skipped++;
            await candidate({
              path: snapshot.ref.path,
              createdAt: {
                seconds: invite.createdAt.seconds,
                nanoseconds: invite.createdAt.nanoseconds,
              },
              outcome,
            });
          }
        }
        if (page.size < limit) break;
        position = {
          clubAfter: position.clubAfter,
          clubId: club.id,
          invitationAfter: page.docs.at(-1).id,
        };
        await save(); // Only after every candidate in this page completed; retries are receipt-safe.
      }
    }
    position = { clubAfter: club.id, clubId: null, invitationAfter: null };
    await save();
    return true;
  }
  if (position.clubId) {
    const club = await db.doc(`driverClubs/${position.clubId}`).get();
    summary.clubsRead++;
    if (!(await visit(club))) return { summary, cursor: position };
  }
  const limit = CAPS.clubs - summary.clubsRead;
  let query = db.collection('driverClubs').orderBy('__name__').limit(limit);
  if (position.clubAfter) query = query.startAfter(position.clubAfter);
  const clubs = await query.get();
  summary.clubsRead += clubs.size;
  for (const club of clubs.docs) {
    if (!(await visit(club))) return { summary, cursor: position };
  }
  summary.incomplete = clubs.size === limit; // No over-cap sentinel read; another bounded pass resolves this.
  return { summary, cursor: position };
}

/** Private files only: refuse links, shared files and existing manifests. */
export async function readState(path, options) {
  let handle;
  try {
    handle = await open(path, constants.O_RDONLY | constants.O_NOFOLLOW);
    const stat = await handle.stat();
    if (
      !stat.isFile() ||
      (stat.mode & 0o777) !== 0o600 ||
      stat.nlink !== 1 ||
      (process.getuid && stat.uid !== process.getuid())
    )
      throw new Error('State must be a private owned file.');
    const state = JSON.parse(await handle.readFile('utf8'));
    if (state.version !== 1 || state.project !== options.project || state.apply !== options.apply)
      throw new Error('State scope mismatch; use separate dry-run/apply state files.');
    return validateCursor(state.cursor);
  } catch (error) {
    if (error.code === 'ENOENT') return emptyCursor();
    throw error;
  } finally {
    await handle?.close();
  }
}
export async function writeState(path, options, cursor) {
  const temporary = `${path}.${randomUUID()}.tmp`;
  try {
    await openPrivate(temporary, async (file) =>
      file.writeFile(
        JSON.stringify({
          version: 1,
          project: options.project,
          apply: options.apply,
          cursor: validateCursor(cursor),
        }) + '\n',
      ),
    );
    await rename(temporary, path);
  } finally {
    await unlink(temporary).catch((error) => {
      if (error.code !== 'ENOENT') throw error;
    });
  }
}
export async function openPrivate(path, use) {
  const file = await open(
    path,
    constants.O_WRONLY | constants.O_CREAT | constants.O_EXCL | constants.O_NOFOLLOW,
    0o600,
  );
  try {
    return await use(file);
  } finally {
    await file.close();
  }
}

async function main() {
  const options = parseOptions(process.argv.slice(2));
  if (options.emulator && globalThis[Symbol.for('kcc.test.noCloud')] !== true)
    throw new Error('No-cloud transport preload required.');
  // Exclusive private state lock avoids two invocations overwriting a continuation.
  await openPrivate(`${options.state}.lock`, async () => {
    try {
      const cursor = await readState(options.state, options);
      const [
        { initializeApp, applicationDefault, deleteApp },
        { getFirestore, Timestamp },
        { getAuth },
      ] = await Promise.all([
        import('firebase-admin/app'),
        import('firebase-admin/firestore'),
        import('firebase-admin/auth'),
      ]);
      const app = initializeApp({
        projectId: options.project,
        credential: applicationDefault(),
        // The existing writer bootstrap constructs RTDB too, but this harness
        // never performs RTDB I/O. Supply its required explicit project URL.
        databaseURL: `https://${options.project}-default-rtdb.firebaseio.com`,
      });
      const db = getFirestore(app),
        auth = getAuth(app);
      try {
        const [{ ClubInvitationNotifications, invitationGeneration }, { writeInAppNotification }] =
          await Promise.all([
            import('../lib/clubInvitations/service.js'),
            import('../lib/notifications/deliver.js'),
          ]);
        const service = new ClubInvitationNotifications(
          db,
          options.emulator
            ? async () => ({ disabled: false, providerData: [] })
            : (uid) => auth.getUser(uid),
          writeInAppNotification,
        );
        if (options.apply) {
          const config = (await db.doc('config/sharedClubs').get()).data();
          if (
            config?.enabled !== true ||
            config.invitationNotificationsReady !== true ||
            !(config.activatedAt instanceof Timestamp) ||
            config.activatedAt.toMillis() > Date.now()
          )
            throw new Error('Notification readiness required for apply.');
        }
        const execute = async (file) => {
          const result = await runBackfill({
            db,
            service,
            invitationGeneration,
            apply: options.apply,
            cursor,
            checkpoint: (next) => writeState(options.state, options, next),
            candidate: file
              ? (row) => file.write(JSON.stringify(row) + '\n').then(() => {})
              : undefined,
          });
          await writeState(options.state, options, result.cursor);
          process.stdout.write(JSON.stringify(result.summary) + '\n'); // No IDs, paths, cursor values or errors.
        };
        if (options.manifest) await openPrivate(options.manifest, execute);
        else await execute();
      } finally {
        await db.terminate();
        await deleteApp(app);
      }
    } finally {
      await unlink(`${options.state}.lock`);
    }
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch(() => {
    process.stderr.write(
      'Club invitation backfill failed; inspect the private checkpoint and validated options.\n',
    );
    process.exitCode = 1;
  });
}
