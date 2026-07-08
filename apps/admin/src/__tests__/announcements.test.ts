/**
 * Unit tests for the Phase 13p admin announcements feature module:
 * write-side validation (rules have no field validation, so the module is the
 * shape gatekeeper), document mapping, and the create/update/toggle/delete
 * flows over a mocked Firestore client.
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';

const addDocMock = vi.fn();
const updateDocMock = vi.fn();
const deleteDocMock = vi.fn();
const getDocsMock = vi.fn();

vi.mock('../lib/firestore', () => ({ getAdminFirestore: () => ({}) }));
vi.mock('firebase/firestore', () => ({
  doc: (...segments: unknown[]) => ({ segments }),
  collection: (...segments: unknown[]) => ({ segments }),
  query: (target: unknown) => target,
  orderBy: () => undefined,
  serverTimestamp: () => ({ __serverTimestamp: true }),
  addDoc: (...args: unknown[]) => addDocMock(...args),
  updateDoc: (...args: unknown[]) => updateDocMock(...args),
  deleteDoc: (...args: unknown[]) => deleteDocMock(...args),
  getDocs: (...args: unknown[]) => getDocsMock(...args),
}));

import {
  adminCreateAnnouncement,
  adminDeleteAnnouncement,
  adminListAnnouncements,
  adminSetAnnouncementActive,
  adminUpdateAnnouncement,
  ANNOUNCEMENT_BODY_MAX_LENGTH,
  ANNOUNCEMENT_TITLE_MAX_LENGTH,
  ApiError,
  validateAnnouncementContent,
} from '../features/announcements';

const SERVER_TIMESTAMP = { __serverTimestamp: true };

/** Runs fn, asserts it throws an ApiError, and returns it for inspection. */
function captureApiError(fn: () => unknown): ApiError {
  try {
    fn();
  } catch (err) {
    expect(err).toBeInstanceOf(ApiError);
    return err as ApiError;
  }
  throw new Error('Expected the call to throw an ApiError.');
}

beforeEach(() => {
  addDocMock.mockReset();
  updateDocMock.mockReset();
  deleteDocMock.mockReset();
  getDocsMock.mockReset();
});

describe('announcement content validation', () => {
  it('rejects an empty title', () => {
    expect(captureApiError(() => validateAnnouncementContent('', 'Body')).code).toBe(
      'announcement/title-required',
    );
    expect(captureApiError(() => validateAnnouncementContent('   ', 'Body')).code).toBe(
      'announcement/title-required',
    );
  });

  it('rejects an empty body', () => {
    expect(captureApiError(() => validateAnnouncementContent('Titel', '')).code).toBe(
      'announcement/body-required',
    );
    expect(captureApiError(() => validateAnnouncementContent('Titel', '  \n ')).code).toBe(
      'announcement/body-required',
    );
  });

  it('rejects an overlong title but accepts one at the cap', () => {
    const atCap = 'a'.repeat(ANNOUNCEMENT_TITLE_MAX_LENGTH);
    expect(validateAnnouncementContent(atCap, 'Body').title).toBe(atCap);
    const error = captureApiError(() => validateAnnouncementContent(`${atCap}b`, 'Body'));
    expect(error.code).toBe('announcement/title-too-long');
    expect(error.statusCode).toBe(400);
  });

  it('rejects an overlong body but accepts one at the cap', () => {
    const atCap = 'b'.repeat(ANNOUNCEMENT_BODY_MAX_LENGTH);
    expect(validateAnnouncementContent('Titel', atCap).body).toBe(atCap);
    const error = captureApiError(() => validateAnnouncementContent('Titel', `${atCap}c`));
    expect(error.code).toBe('announcement/body-too-long');
    expect(error.statusCode).toBe(400);
  });

  it('trims surrounding whitespace before measuring', () => {
    const padded = `  ${'a'.repeat(ANNOUNCEMENT_TITLE_MAX_LENGTH)}  `;
    expect(validateAnnouncementContent(padded, '  Body  ')).toEqual({
      title: 'a'.repeat(ANNOUNCEMENT_TITLE_MAX_LENGTH),
      body: 'Body',
    });
  });
});

describe('adminCreateAnnouncement', () => {
  it('writes a trimmed document with server timestamps and returns the id', async () => {
    addDocMock.mockResolvedValue({ id: 'ann-1' });
    const id = await adminCreateAnnouncement({
      title: '  Ny träff  ',
      body: '  Vi ses på lördag.  ',
      active: true,
    });
    expect(id).toBe('ann-1');
    expect(addDocMock).toHaveBeenCalledTimes(1);
    expect(addDocMock).toHaveBeenCalledWith(expect.anything(), {
      title: 'Ny träff',
      body: 'Vi ses på lördag.',
      active: true,
      createdAt: SERVER_TIMESTAMP,
      updatedAt: SERVER_TIMESTAMP,
    });
  });

  it('coerces the active flag to a strict boolean', async () => {
    addDocMock.mockResolvedValue({ id: 'ann-2' });
    await adminCreateAnnouncement({
      title: 'Titel',
      body: 'Body',
      active: undefined as unknown as boolean,
    });
    expect(addDocMock).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({ active: false }),
    );
  });

  it('does not touch Firestore when validation fails', async () => {
    await expect(
      adminCreateAnnouncement({ title: '', body: 'Body', active: true }),
    ).rejects.toMatchObject({ code: 'announcement/title-required' });
    expect(addDocMock).not.toHaveBeenCalled();
  });
});

describe('adminListAnnouncements', () => {
  it('maps documents newest-first with permissive timestamp handling', async () => {
    getDocsMock.mockResolvedValue({
      docs: [
        {
          id: 'a1',
          data: () => ({
            title: 'Nyast',
            body: 'Innehåll',
            active: true,
            createdAt: { toDate: () => new Date('2026-07-08T10:00:00Z') },
            updatedAt: '2026-07-08T11:00:00Z',
          }),
        },
        {
          id: 'a2',
          // Old/partial doc: missing fields fall back to safe defaults.
          data: () => ({ title: 'Gammal' }),
        },
      ],
    });
    const items = await adminListAnnouncements();
    expect(items).toEqual([
      {
        id: 'a1',
        title: 'Nyast',
        body: 'Innehåll',
        active: true,
        createdAt: '2026-07-08T10:00:00.000Z',
        updatedAt: '2026-07-08T11:00:00.000Z',
      },
      {
        id: 'a2',
        title: 'Gammal',
        body: '',
        active: false,
        createdAt: null,
        updatedAt: null,
      },
    ]);
  });
});

describe('adminUpdateAnnouncement', () => {
  it('validates content and refreshes updatedAt only', async () => {
    updateDocMock.mockResolvedValue(undefined);
    await adminUpdateAnnouncement('ann-1', {
      title: ' Uppdaterad titel ',
      body: ' Uppdaterat innehåll ',
      active: false,
    });
    expect(updateDocMock).toHaveBeenCalledTimes(1);
    // Exact payload match — in particular, createdAt is NOT rewritten since
    // it anchors the member-facing ordering.
    expect(updateDocMock).toHaveBeenCalledWith(expect.anything(), {
      title: 'Uppdaterad titel',
      body: 'Uppdaterat innehåll',
      active: false,
      updatedAt: SERVER_TIMESTAMP,
    });
  });

  it('rejects invalid content without writing', async () => {
    await expect(
      adminUpdateAnnouncement('ann-1', {
        title: 'Titel',
        body: 'x'.repeat(ANNOUNCEMENT_BODY_MAX_LENGTH + 1),
        active: true,
      }),
    ).rejects.toMatchObject({ code: 'announcement/body-too-long' });
    expect(updateDocMock).not.toHaveBeenCalled();
  });
});

describe('adminSetAnnouncementActive', () => {
  it('retracts (deactivates) with a fresh updatedAt', async () => {
    updateDocMock.mockResolvedValue(undefined);
    await adminSetAnnouncementActive('ann-1', false);
    expect(updateDocMock).toHaveBeenCalledWith(expect.anything(), {
      active: false,
      updatedAt: SERVER_TIMESTAMP,
    });
  });

  it('re-activates with a fresh updatedAt', async () => {
    updateDocMock.mockResolvedValue(undefined);
    await adminSetAnnouncementActive('ann-1', true);
    expect(updateDocMock).toHaveBeenCalledWith(expect.anything(), {
      active: true,
      updatedAt: SERVER_TIMESTAMP,
    });
  });
});

describe('adminDeleteAnnouncement', () => {
  it('hard-deletes the addressed document', async () => {
    deleteDocMock.mockResolvedValue(undefined);
    await adminDeleteAnnouncement('ann-1');
    expect(deleteDocMock).toHaveBeenCalledTimes(1);
    expect(deleteDocMock).toHaveBeenCalledWith({
      segments: [expect.anything(), 'announcements', 'ann-1'],
    });
  });
});
