/**
 * Unit tests for the admin managed-credentials feature module (Token /
 * credential renewal tracker): the renewal-status computation (including the
 * exact 30-day boundary), write-side validation (rules do no field validation,
 * so the module is the shape gatekeeper), and the create/update/delete flows
 * over a mocked Firestore client.
 */

import { beforeEach, describe, expect, it, vi } from 'vitest';

const addDocMock = vi.fn();
const updateDocMock = vi.fn();
const deleteDocMock = vi.fn();
const getDocsMock = vi.fn();
const limitMock = vi.fn();

vi.mock('../lib/firestore', () => ({ getAdminFirestore: () => ({}) }));
vi.mock('firebase/firestore', () => ({
  Timestamp: {
    fromDate: (date: Date) => ({ __timestamp: date.toISOString() }),
  },
  doc: (...segments: unknown[]) => ({ segments }),
  collection: (...segments: unknown[]) => ({ segments }),
  query: (target: unknown) => target,
  orderBy: () => undefined,
  limit: (...args: unknown[]) => limitMock(...args),
  serverTimestamp: () => ({ __serverTimestamp: true }),
  addDoc: (...args: unknown[]) => addDocMock(...args),
  updateDoc: (...args: unknown[]) => updateDocMock(...args),
  deleteDoc: (...args: unknown[]) => deleteDocMock(...args),
  getDocs: (...args: unknown[]) => getDocsMock(...args),
}));

import {
  adminCreateManagedCredential,
  adminDeleteManagedCredential,
  adminListManagedCredentials,
  adminUpdateManagedCredential,
  ApiError,
  computeCredentialStatus,
  CREDENTIAL_NAME_MAX_LENGTH,
  EXPIRING_SOON_DAYS,
  INVALID_EXPIRY,
  isCredentialCategory,
  validateCredentialInput,
  type ManagedCredentialInput,
} from '../features/credentials';

const SERVER_TIMESTAMP = { __serverTimestamp: true };
const DAY_MS = 24 * 60 * 60 * 1000;

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

function baseInput(overrides: Partial<ManagedCredentialInput> = {}): ManagedCredentialInput {
  return {
    name: 'Some token',
    description: '',
    category: 'api-key',
    expiresAt: null,
    lastRotatedAt: null,
    notes: '',
    ...overrides,
  };
}

beforeEach(() => {
  addDocMock.mockReset();
  updateDocMock.mockReset();
  deleteDocMock.mockReset();
  getDocsMock.mockReset();
  limitMock.mockReset();
});

describe('computeCredentialStatus', () => {
  const now = new Date('2026-07-09T12:00:00Z');

  it('returns no-expiry when expiresAt is null', () => {
    expect(computeCredentialStatus(null, now)).toBe('no-expiry');
  });

  it('returns invalid (not no-expiry) when a present expiresAt is unparseable', () => {
    expect(computeCredentialStatus('not-a-date', now)).toBe('invalid');
  });

  it('returns expired when expiry is strictly before now', () => {
    const past = new Date(now.getTime() - DAY_MS).toISOString();
    expect(computeCredentialStatus(past, now)).toBe('expired');
  });

  it('returns expiring-soon within the 30-day window', () => {
    const soon = new Date(now.getTime() + 10 * DAY_MS).toISOString();
    expect(computeCredentialStatus(soon, now)).toBe('expiring-soon');
  });

  it('treats the exact 30-day boundary as expiring-soon (inclusive)', () => {
    const exactly30 = new Date(now.getTime() + EXPIRING_SOON_DAYS * DAY_MS).toISOString();
    expect(computeCredentialStatus(exactly30, now)).toBe('expiring-soon');
  });

  it('returns ok one full day past the 30-day boundary', () => {
    // Calendar-day semantics: 31 whole days out lands on the next calendar day,
    // outside the 30-day window. (A sub-day nudge would stay on day 30 and thus
    // still be expiring-soon, so advance by a full day here.)
    const justOver = new Date(now.getTime() + (EXPIRING_SOON_DAYS + 1) * DAY_MS).toISOString();
    expect(computeCredentialStatus(justOver, now)).toBe('ok');
  });

  it('returns ok for a far-future expiry', () => {
    expect(computeCredentialStatus('2053-11-24T00:00:00Z', now)).toBe('ok');
  });

  it('classifies by calendar day, independent of now hour-of-day', () => {
    // The 30-day boundary is a calendar-day count, so the same expiry must land
    // in the same bucket whether "now" is early or late in its local day. Build
    // the reference days from LOCAL date parts to stay timezone-independent.
    const y = 2026;
    const m = 5; // June (0-based)
    const d = 15;
    const at = (hour: number) => new Date(y, m, d, hour, 0, 0);
    const onDay = (dayOffset: number) => new Date(y, m, d + dayOffset).toISOString();

    // Exactly 30 calendar days out → expiring-soon regardless of now's hour.
    expect(computeCredentialStatus(onDay(30), at(0))).toBe('expiring-soon');
    expect(computeCredentialStatus(onDay(30), at(23))).toBe('expiring-soon');
    // 31 calendar days out → ok regardless of now's hour.
    expect(computeCredentialStatus(onDay(31), at(0))).toBe('ok');
    expect(computeCredentialStatus(onDay(31), at(23))).toBe('ok');
  });
});

describe('isCredentialCategory', () => {
  it('accepts known categories and rejects unknown values', () => {
    expect(isCredentialCategory('github-pat')).toBe(true);
    expect(isCredentialCategory('signing-keystore')).toBe(true);
    expect(isCredentialCategory('nope')).toBe(false);
    expect(isCredentialCategory(42)).toBe(false);
  });
});

describe('validateCredentialInput', () => {
  it('rejects an empty name', () => {
    expect(captureApiError(() => validateCredentialInput(baseInput({ name: '   ' }))).code).toBe(
      'credential/name-required',
    );
  });

  it('rejects an overlong name but accepts one at the cap', () => {
    const atCap = 'n'.repeat(CREDENTIAL_NAME_MAX_LENGTH);
    expect(validateCredentialInput(baseInput({ name: atCap })).name).toBe(atCap);
    expect(
      captureApiError(() => validateCredentialInput(baseInput({ name: `${atCap}x` }))).code,
    ).toBe('credential/name-too-long');
  });

  it('rejects an unknown category', () => {
    const bad = baseInput({ category: 'bogus' as never });
    expect(captureApiError(() => validateCredentialInput(bad)).code).toBe(
      'credential/category-invalid',
    );
  });

  it('rejects an unparseable expiry date', () => {
    expect(
      captureApiError(() => validateCredentialInput(baseInput({ expiresAt: 'nope' }))).code,
    ).toBe('credential/expires-invalid');
  });

  it('converts date strings to Timestamps and trims text fields', () => {
    const result = validateCredentialInput(
      baseInput({
        name: '  Upload keystore  ',
        description: '  desc  ',
        notes: '  note  ',
        category: 'signing-keystore',
        expiresAt: '2053-11-24',
        lastRotatedAt: '2026-07-01',
      }),
    );
    expect(result.name).toBe('Upload keystore');
    expect(result.description).toBe('desc');
    expect(result.notes).toBe('note');
    expect(result.category).toBe('signing-keystore');
    // Date-only strings are stored at LOCAL midnight (not UTC), so assert
    // against a locally-constructed Date to stay timezone-independent.
    expect(result.expiresAt).toEqual({ __timestamp: new Date(2053, 10, 24).toISOString() });
    expect(result.lastRotatedAt).toEqual({ __timestamp: new Date(2026, 6, 1).toISOString() });
  });

  it('parses a date-only string at local midnight, not UTC midnight', () => {
    const result = validateCredentialInput(baseInput({ expiresAt: '2026-03-15' }));
    const stored = new Date((result.expiresAt as unknown as { __timestamp: string }).__timestamp);
    // Local calendar components must match the chosen day regardless of the
    // runner's timezone (a UTC-midnight parse could shift the day by one).
    expect(stored.getFullYear()).toBe(2026);
    expect(stored.getMonth()).toBe(2);
    expect(stored.getDate()).toBe(15);
    expect(stored.getHours()).toBe(0);
    expect(stored.getMinutes()).toBe(0);
  });

  it('rejects an impossible date-only string (calendar rollover)', () => {
    expect(
      captureApiError(() => validateCredentialInput(baseInput({ expiresAt: '2026-02-31' }))).code,
    ).toBe('credential/expires-invalid');
  });

  it('keeps null date fields as null', () => {
    const result = validateCredentialInput(baseInput({ expiresAt: null, lastRotatedAt: null }));
    expect(result.expiresAt).toBeNull();
    expect(result.lastRotatedAt).toBeNull();
  });
});

describe('adminCreateManagedCredential', () => {
  it('writes a validated document with server timestamps and returns the id', async () => {
    addDocMock.mockResolvedValue({ id: 'cred-1' });
    const id = await adminCreateManagedCredential(
      baseInput({ name: 'Mapbox token', category: 'mapbox-token' }),
    );
    expect(id).toBe('cred-1');
    expect(addDocMock).toHaveBeenCalledTimes(1);
    expect(addDocMock).toHaveBeenCalledWith(expect.anything(), {
      name: 'Mapbox token',
      description: '',
      category: 'mapbox-token',
      expiresAt: null,
      lastRotatedAt: null,
      notes: '',
      createdAt: SERVER_TIMESTAMP,
      updatedAt: SERVER_TIMESTAMP,
    });
  });

  it('does not touch Firestore when validation fails', async () => {
    await expect(adminCreateManagedCredential(baseInput({ name: '' }))).rejects.toMatchObject({
      code: 'credential/name-required',
    });
    expect(addDocMock).not.toHaveBeenCalled();
  });
});

describe('adminListManagedCredentials', () => {
  it('maps documents and sorts soonest-expiry-first with nulls last', async () => {
    getDocsMock.mockResolvedValue({
      docs: [
        {
          id: 'no-expiry',
          data: () => ({ name: 'No expiry', category: 'mapbox-token', expiresAt: null }),
        },
        {
          id: 'far',
          data: () => ({
            name: 'Far',
            category: 'signing-keystore',
            expiresAt: { toDate: () => new Date('2053-11-24T00:00:00Z') },
          }),
        },
        {
          id: 'soon',
          data: () => ({
            name: 'Soon',
            category: 'github-pat',
            expiresAt: { toDate: () => new Date('2026-08-01T00:00:00Z') },
          }),
        },
      ],
    });
    const items = await adminListManagedCredentials();
    expect(items.map((i) => i.id)).toEqual(['soon', 'far', 'no-expiry']);
    expect(limitMock).toHaveBeenCalledWith(50);
  });

  it('surfaces a corrupt stored expiry as invalid and sorts it first', async () => {
    getDocsMock.mockResolvedValue({
      docs: [
        {
          id: 'no-expiry',
          data: () => ({ name: 'No expiry', category: 'mapbox-token', expiresAt: null }),
        },
        {
          id: 'dated',
          data: () => ({
            name: 'Dated',
            category: 'github-pat',
            expiresAt: { toDate: () => new Date('2026-08-01T00:00:00Z') },
          }),
        },
        {
          id: 'corrupt',
          data: () => ({ name: 'Corrupt', category: 'api-key', expiresAt: 'not-a-date' }),
        },
      ],
    });
    const items = await adminListManagedCredentials();
    // Corrupt data must not masquerade as no-expiry: it maps to the invalid
    // marker and sorts first so an operator sees it.
    const corrupt = items.find((i) => i.id === 'corrupt');
    expect(corrupt?.expiresAt).toBe(INVALID_EXPIRY);
    expect(computeCredentialStatus(corrupt?.expiresAt ?? null)).toBe('invalid');
    expect(items.map((i) => i.id)).toEqual(['corrupt', 'dated', 'no-expiry']);
  });

  it('falls back to a safe category for unknown stored values', async () => {
    getDocsMock.mockResolvedValue({
      docs: [{ id: 'x', data: () => ({ name: 'X', category: 'legacy-thing', expiresAt: null }) }],
    });
    const items = await adminListManagedCredentials();
    expect(items[0]?.category).toBe('other');
  });
});

describe('adminUpdateManagedCredential', () => {
  it('validates content and refreshes updatedAt without touching createdAt', async () => {
    updateDocMock.mockResolvedValue(undefined);
    await adminUpdateManagedCredential('cred-1', baseInput({ name: '  Renamed  ' }));
    expect(updateDocMock).toHaveBeenCalledTimes(1);
    const call = updateDocMock.mock.calls[0];
    expect(call).toBeDefined();
    const payload = (call as unknown[])[1];
    expect(payload).toMatchObject({ name: 'Renamed', updatedAt: SERVER_TIMESTAMP });
    expect(payload).not.toHaveProperty('createdAt');
  });

  it('rejects invalid content without writing', async () => {
    await expect(
      adminUpdateManagedCredential('cred-1', baseInput({ category: 'bad' as never })),
    ).rejects.toMatchObject({ code: 'credential/category-invalid' });
    expect(updateDocMock).not.toHaveBeenCalled();
  });
});

describe('adminDeleteManagedCredential', () => {
  it('hard-deletes the addressed document', async () => {
    deleteDocMock.mockResolvedValue(undefined);
    await adminDeleteManagedCredential('cred-1');
    expect(deleteDocMock).toHaveBeenCalledTimes(1);
    expect(deleteDocMock).toHaveBeenCalledWith({
      segments: [expect.anything(), 'managedCredentials', 'cred-1'],
    });
  });
});
