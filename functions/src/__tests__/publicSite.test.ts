/** Homepage regeneration with Firestore and GitHub mocked: no cloud I/O. */
import { beforeEach, describe, expect, it, vi } from 'vitest';

const mocks = vi.hoisted(() => ({
  collection: vi.fn(),
  where: vi.fn(),
  get: vi.fn(),
  getAll: vi.fn(),
  sync: vi.fn(),
}));

vi.mock('../firebase', () => ({
  db: { collection: mocks.collection, getAll: mocks.getAll },
}));
vi.mock('../events/homepageRepo', () => ({ syncHomepageEventsFile: mocks.sync }));

import { regenerateHomepageEvents } from '../events/publicSite';

const NOW = new Date('2026-08-20T19:00:00Z');
const ts = (iso: string) => ({ toDate: () => new Date(iso) });
const base = {
  status: 'published',
  title: 'Träff',
  startsAt: ts('2026-08-20T17:00:00Z'),
  summary: 'Samling.',
  locationName: 'Stationen',
  // Extra raw fields must not appear in the generated feed.
  address: 'Hemlig adress',
  createdByUserId: 'private-creator',
  attendees: ['private-attendee'],
};

beforeEach(() => {
  vi.resetAllMocks();
  const query = { where: mocks.where, get: mocks.get };
  mocks.where.mockReturnValue(query);
  mocks.collection.mockReturnValue(query);
  mocks.sync.mockResolvedValue('unchanged');
});

describe('regenerateHomepageEvents', () => {
  it.each([
    ['explicit', ts('2026-08-21T00:00:00Z'), '2026-08-21T00:00:00.000Z'],
    ['null', null, '2026-08-20T21:59:59.999Z'],
    ['missing', undefined, '2026-08-20T21:59:59.999Z'],
  ])('maps the stored %s end into the canonical public feed', async (_label, endsAt, expected) => {
    const data = { ...base, ...(endsAt === undefined ? {} : { endsAt }) };
    mocks.get.mockResolvedValue({ docs: [{ id: 'e1', data: () => data }] });
    expect(await regenerateHomepageEvents('test-token', NOW)).toEqual({
      status: 'unchanged', eventCount: 1,
    });
    expect(mocks.collection).toHaveBeenCalledExactlyOnceWith('events');
    expect(mocks.where.mock.calls).toEqual([
      ['publicSiteEnabled', '==', true],
      ['status', '==', 'published'],
    ]);
    const [content, token, metadata] = mocks.sync.mock.calls[0]!;
    expect(token).toBe('test-token');
    expect(metadata).toEqual({ eventCount: 1 });
    expect(JSON.parse(content).events).toEqual([{
      title: 'Träff',
      startsAt: '2026-08-20T17:00:00.000Z',
      endsAt: expected,
      date: '2026-08-20',
      time: '19:00',
      place: 'Stationen',
      desc: 'Samling.',
      url: 'https://kcc-events.web.app/e/e1',
      source: 'app',
    }]);
    expect(mocks.getAll).not.toHaveBeenCalled();
  });

  it('reflects an end-only extension when regenerating a running event', async () => {
    mocks.get
      .mockResolvedValueOnce({
        docs: [{ id: 'e1', data: () => ({ ...base, endsAt: ts('2026-08-20T19:00:00Z') }) }],
      })
      .mockResolvedValueOnce({
        docs: [{ id: 'e1', data: () => ({ ...base, endsAt: ts('2026-08-20T20:00:00Z') }) }],
      });
    expect((await regenerateHomepageEvents('test-token', NOW)).eventCount).toBe(0);
    expect((await regenerateHomepageEvents('test-token', NOW)).eventCount).toBe(1);
    expect(JSON.parse(mocks.sync.mock.calls[0]![0]).events).toEqual([]);
    expect(JSON.parse(mocks.sync.mock.calls[1]![0]).events[0].endsAt).toBe('2026-08-20T20:00:00.000Z');
  });
});
