import { createHash } from 'node:crypto';
import { lstat, readFile, writeFile } from 'node:fs/promises';
import { isAbsolute, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

export const APPROVED_BUILD = Object.freeze({
  repository: 'SebMcCayen/carcommunity',
  runId: 40793833224,
  workflowId: 308749238,
  source: '27677adf03c1b41f9f5f51f832fdcf5d6cf02da6',
  versionCode: 60,
  packageName: 'com.kungsbackacarcommunity.app',
});
const API = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${APPROVED_BUILD.packageName}`;
const UPLOAD = `https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/${APPROVED_BUILD.packageName}`;

export function verifyBuildRun(run) {
  if (
    run.id !== APPROVED_BUILD.runId ||
    run.workflow_id !== APPROVED_BUILD.workflowId ||
    run.head_sha !== APPROVED_BUILD.source ||
    run.head_branch !== 'main' ||
    run.repository?.full_name !== APPROVED_BUILD.repository ||
    run.head_repository?.full_name !== APPROVED_BUILD.repository ||
    run.event !== 'workflow_dispatch' ||
    run.status !== 'completed' ||
    run.conclusion !== 'success'
  ) {
    throw new Error('Only the successful, approved signed build may be published.');
  }
  return { buildRunId: run.id, source: run.head_sha, versionCode: 60 };
}
function editId(value) {
  if (typeof value !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/.test(value))
    throw new Error('Invalid Play edit identity.');
  return value;
}
const canonical = (value) =>
  JSON.stringify(value, (_key, v) =>
    v && typeof v === 'object' && !Array.isArray(v)
      ? Object.fromEntries(
          Object.keys(v)
            .sort()
            .map((k) => [k, v[k]]),
        )
      : v,
  );
const codes = (track) =>
  (track.releases ?? [])
    .flatMap((r) => r.versionCodes ?? [])
    .map((v) => {
      if (!/^\d+$/.test(String(v))) throw new Error('Unexpected Play version metadata.');
      return Number(v);
    });

/** Fixed package, one approved bundle, internal only. No purchase/customer APIs. */
export async function releaseInternal({
  bundle,
  expectedSha256,
  token,
  publish = false,
  fetchImpl = fetch,
}) {
  if (process.env.KCC_TEST_NO_CLOUD && fetchImpl === globalThis.fetch)
    throw new Error('Live publishing is forbidden in the no-cloud test context.');
  const sha256 = createHash('sha256').update(bundle).digest('hex');
  if (!/^[a-f0-9]{64}$/.test(expectedSha256 ?? '') || sha256 !== expectedSha256)
    throw new Error('Approved AAB SHA-256 mismatch.');
  if (!token) throw new Error('A short-lived Play publishing token is required.');
  let activeEdit = null;
  let committed = false;
  let commitAttempted = false;
  async function call(method, path, body, upload = false) {
    const response = await fetchImpl((upload ? UPLOAD : API) + path, {
      method,
      redirect: 'error',
      headers: {
        Authorization: `Bearer ${token}`,
        ...(body === undefined
          ? {}
          : { 'content-type': upload ? 'application/octet-stream' : 'application/json' }),
      },
      ...(body === undefined ? {} : { body: upload ? body : JSON.stringify(body) }),
      signal: AbortSignal.timeout(upload ? 600_000 : 180_000),
    });
    if (!response.ok) {
      // Provider errors can contain credentials or request details. Keep only status.
      throw new Error(
        `Play ${upload ? 'bundle upload' : method} failed (HTTP ${response.status}).`,
      );
    }
    const text = await response.text();
    try {
      return text ? JSON.parse(text) : {};
    } catch {
      throw new Error('Play returned invalid metadata.');
    }
  }
  async function begin() {
    activeEdit = editId((await call('POST', '/edits', {})).id);
    return `/edits/${activeEdit}`;
  }
  try {
    let root = await begin();
    const tracks = (await call('GET', `${root}/tracks`)).tracks ?? [];
    // Google documents qa; existing integrations also use internal. Resolve
    // only a single existing well-known alias, never create or guess a track.
    const candidates = tracks.filter((t) => ['internal', 'qa'].includes(t.track));
    const productions = tracks.filter((t) => t.track === 'production');
    if (candidates.length !== 1 || productions.length !== 1)
      throw new Error('Internal track identity is absent or ambiguous; review existing tracks.');
    const internal = candidates[0],
      production = productions[0],
      target = internal.track;
    if (
      production.track !== 'production' ||
      codes(production).length === 0 ||
      Math.max(...codes(production)) !== 54
    )
      throw new Error('Play tracks differ from the owner-reviewed release baseline.');
    const productionBefore = canonical(production);
    const bundles = (await call('GET', `${root}/bundles`)).bundles ?? [];
    const existing = bundles.find((b) => b.versionCode === 60);
    if (existing && existing.sha256?.toLowerCase() !== sha256)
      throw new Error('Version 60 already belongs to another bundle.');
    const current = internal.releases ?? [];
    if (current.length === 1 && codes(internal).length === 1 && codes(internal)[0] === 60) {
      if (!existing || current[0].status !== 'completed' || current[0].inAppUpdatePriority !== 5)
        throw new Error('Existing version 60 does not match the approved release policy.');
      return {
        published: true,
        alreadyPublished: true,
        versionCode: 60,
        track: target,
        sha256,
        priority: 5,
        productionUnchanged: true,
      };
    }
    if (
      current.length !== 1 ||
      current[0].status !== 'completed' ||
      codes(internal).length !== 1 ||
      codes(internal)[0] !== 59
    )
      throw new Error(
        'Internal track has an unexpected or unfinished release; review before replacing it.',
      );
    if (!publish)
      return {
        ready: true,
        published: false,
        versionCode: 60,
        track: target,
        sha256,
        currentInternalVersion: 59,
        productionVersion: 54,
        priority: 5,
      };
    if (!existing) {
      const uploaded = await call('POST', `${root}/bundles?uploadType=media`, bundle, true);
      if (uploaded.versionCode !== 60 || uploaded.sha256?.toLowerCase() !== sha256)
        throw new Error('Play did not confirm the approved version and bundle digest.');
    }
    // Supersede only the reviewed completed internal59 release. Never touch
    // another track, unfinished rollout, tester membership or app configuration.
    const release = {
      name: '1.1.5 (60)',
      versionCodes: ['60'],
      status: 'completed',
      inAppUpdatePriority: 5,
      releaseNotes: [
        {
          language: 'sv-SE',
          text: 'Varningar med giltighetstid visas i din profil\nTydligare information när CarCommunity-åtkomsten är begränsad\nUppdaterad hantering av modererade chattmeddelanden',
        },
      ],
    };
    await call('PUT', `${root}/tracks/${target}`, { track: target, releases: [release] });
    await call('POST', `${root}:validate`, {});
    commitAttempted = true;
    await call('POST', `${root}:commit`, {});
    committed = true;
    activeEdit = null;
    root = await begin();
    const after = await call('GET', `${root}/tracks/${target}`);
    const productionAfter = await call('GET', `${root}/tracks/production`);
    if (
      after.track !== target ||
      after.releases?.length !== 1 ||
      codes(after).length !== 1 ||
      codes(after)[0] !== 60 ||
      after.releases[0].status !== 'completed' ||
      after.releases[0].inAppUpdatePriority !== 5 ||
      canonical(productionAfter) !== productionBefore
    )
      throw new Error('Commit accepted, but release readback requires operator review.');
    return {
      published: true,
      versionCode: 60,
      track: target,
      priority: 5,
      sha256,
      productionUnchanged: true,
    };
  } catch (error) {
    error.publicationCommitted = committed ? true : commitAttempted ? null : false;
    error.publicationState = committed
      ? 'committed'
      : commitAttempted
        ? 'unknown'
        : 'not_committed';
    throw error;
  } finally {
    if (activeEdit) {
      // Discard only our uncommitted inspection/edit. Never delete a bundle/release.
      try {
        await call('DELETE', `/edits/${activeEdit}`);
      } catch {
        /* expires automatically */
      }
    }
  }
}

async function main() {
  if (process.argv[2] === 'verify-build') {
    console.log(
      JSON.stringify(verifyBuildRun(JSON.parse(await readFile(process.argv[3], 'utf8')))),
    );
    return;
  }
  if (
    process.argv[2] !== 'release' ||
    !['inspect', 'publish'].includes(process.env.PLAY_RELEASE_MODE)
  )
    throw new Error('Explicit inspect or publish mode required.');
  if (!process.env.PLAY_RELEASE_REPORT || !isAbsolute(process.env.PLAY_RELEASE_REPORT))
    throw new Error('An absolute receipt path is required before publication.');
  const file = process.argv[3];
  const stat = await lstat(file);
  if (!stat.isFile() || stat.isSymbolicLink() || stat.size < 1024 || stat.size > 300_000_000)
    throw new Error('Expected one regular, bounded signed AAB.');
  const report = await releaseInternal({
    bundle: await readFile(file),
    expectedSha256: process.env.EXPECTED_AAB_SHA256,
    token: process.env.PLAY_PUBLISHING_TOKEN,
    publish: process.env.PLAY_RELEASE_MODE === 'publish',
  });
  const result = { ...report, buildRunId: APPROVED_BUILD.runId, source: APPROVED_BUILD.source };
  try {
    await writeFile(process.env.PLAY_RELEASE_REPORT, JSON.stringify(result, null, 2) + '\n', {
      mode: 0o600,
    });
  } catch {
    throw Object.assign(
      new Error('Release operation finished, but its receipt could not be written.'),
      {
        publicationCommitted: report.published === true,
        publicationState: report.published === true ? 'committed' : 'not_committed',
      },
    );
  }
  console.log(JSON.stringify(result));
}
if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch(async (error) => {
    const result = {
      failed: true,
      publicationCommitted:
        error.publicationCommitted === null ? null : error.publicationCommitted === true,
      publicationState: error.publicationState ?? 'not_committed',
      message: error.message,
      track: 'internal',
      versionCode: 60,
    };
    console.error(JSON.stringify(result));
    if (process.env.PLAY_RELEASE_REPORT) {
      try {
        await writeFile(process.env.PLAY_RELEASE_REPORT, JSON.stringify(result, null, 2) + '\n', {
          mode: 0o600,
        });
      } catch {
        /* stderr retains commit state even if the disk is unavailable */
      }
    }
    process.exitCode = 1;
  });
}
