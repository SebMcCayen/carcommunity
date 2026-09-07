/**
 * The free lane must survive a future re-lock of unrelated legacy domains.
 * Exercise the real callable handlers and requireActiveActor with the legacy
 * gate forced closed. Only datastore work after the actor read is stubbed;
 * full successful workflows are covered by the domain emulator suites.
 */
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import type { CallableRequest } from 'firebase-functions/v2/https';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import * as friends from '../friends/manageFriends';
import * as dm from '../dm/manageDirectMessages';
import * as dmReports from '../dm/reportMessage';
import * as community from '../chatchannels/communityChat';
import * as convoyChat from '../chatchannels/convoyChat';
import * as chatReports from '../chatchannels/reportMessage';
import * as convoy from '../convoy/manageConvoy';
import * as reactions from '../convoy/reactions';
import * as followMe from '../convoy/setFollowMe';
import * as groupDrive from '../groupDrive/participants';
import * as incidentReport from '../incidents/report';
import * as incidentList from '../incidents/listNearby';
import * as incidentRemove from '../incidents/remove';
import * as incidentConfirm from '../incidents/confirm';
import * as incidentCleared from '../incidents/reportCleared';
import * as policeReport from '../police/report';
import * as policeList from '../police/listNearby';
import * as policeRemove from '../police/remove';
import * as policeVerify from '../police/verify';
import { saveDrive } from '../drives/saveDrive';

const fixture = vi.hoisted(() => ({
  state: { role: 'user', activeMember: false, suspended: false, deleted: false },
  actorRead: false,
}));

vi.mock('../shared/memberGating', () => ({
  memberGateAllows: vi.fn(() => false),
  backendGateAllows: vi.fn(() => false),
  crownHuntGateAllows: vi.fn(() => false),
}));

vi.mock('../firebase', async () => {
  const { HttpsError } = await import('firebase-functions/v2/https');
  return {
    db: {
      collection: (name: string) => {
        if (fixture.actorRead || name !== 'users') {
          throw new HttpsError('aborted', 'Reached domain datastore work.');
        }
        return {
          doc: () => ({
            get: async () => {
              fixture.actorRead = true;
              return { exists: true, data: () => fixture.state };
            },
          }),
        };
      },
    },
    adminAuth: {},
    adminStorage: {},
    adminRtdb: {},
  };
});

// Enumerate exports rather than just source text: all owned user-facing
// handlers must authenticate and reject restrictions before domain work.
const modules = [
  ['friend', friends],
  ['dm', dm],
  ['dm', dmReports],
  ['communityChat', community],
  ['convoyChat', convoyChat],
  ['chatchannels', chatReports],
  ['convoy', convoy],
  ['convoy', reactions],
  ['convoy', followMe],
  ['groupDrive', groupDrive],
  ['incidents', incidentReport],
  ['incidents', incidentList],
  ['incidents', incidentRemove],
  ['incidents', incidentConfirm],
  ['incidents', incidentCleared],
  ['police', policeReport],
  ['police', policeList],
  ['police', policeRemove],
  ['police', policeVerify],
  ['drives', { save: saveDrive }],
] as const;

type Handler = { run: (request: CallableRequest) => Promise<unknown> };
const endpoints = modules.flatMap(([domain, exports]) =>
  Object.entries(exports)
    .filter(([, handler]) => typeof handler === 'function' && 'run' in handler)
    .map(([name, handler]) => ({ name: `${domain}.${name}`, handler: handler as Handler })),
);
const registry = JSON.parse(
  readFileSync(resolve(__dirname, '../../../contracts/functions/functions.json'), 'utf8'),
) as { functions: Array<{ name: string; access: string; appCheck: boolean }> };

beforeEach(() => {
  fixture.actorRead = false;
  fixture.state = { role: 'user', activeMember: false, suspended: false, deleted: false };
});

describe.each(endpoints)('$name permanent free access', ({ name, handler }) => {
  const request = (uid?: string): CallableRequest =>
    ({
      data: null,
      ...(uid ? { auth: { uid, token: { uid, sub: uid } } } : {}),
    }) as CallableRequest;

  it('passes the free actor gate even when legacy membership is denied', async () => {
    // Invalid input or the datastore sentinel proves we passed authorization.
    // A regression to requireMemberActor instead returns permission-denied.
    await expect(handler.run(request('free-social'))).rejects.toMatchObject({
      code: expect.stringMatching(/^(invalid-argument|aborted)$/),
    });
    expect(fixture.actorRead).toBe(true);
  });

  it.each(['suspended', 'deleted'] as const)('denies a %s free account', async (restriction) => {
    fixture.state[restriction] = true;
    await expect(handler.run(request('free-social'))).rejects.toMatchObject({
      code: 'permission-denied',
    });
  });

  it('denies an anonymous caller', async () => {
    await expect(handler.run(request())).rejects.toMatchObject({ code: 'unauthenticated' });
    expect(fixture.actorRead).toBe(false);
  });

  it('declares authenticated access and retains App Check', () => {
    expect(registry.functions.find((entry) => entry.name === name)).toMatchObject({
      access: 'authenticated',
      appCheck: true,
    });
  });
});
