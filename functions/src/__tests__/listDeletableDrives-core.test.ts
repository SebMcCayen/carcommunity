import { describe, expect, it } from 'vitest';
import {
  DELETABLE_DRIVES_PAGE_SIZE_DEFAULT,
  DELETABLE_DRIVES_PAGE_SIZE_MAX,
  deletableDrivesPageSize,
  parseListDeletableDrivesInput,
} from '../drives/listDeletableDrives-core';

describe('list-deletable-drives input', () => {
  it('accepts an empty request and a bounded cursor page', () => {
    expect(parseListDeletableDrivesInput({})).toEqual({ ok: true, input: {} });
    expect(parseListDeletableDrivesInput({ cursorRideId: 'ride-1', pageSize: 50 })).toEqual({
      ok: true,
      input: { cursorRideId: 'ride-1', pageSize: 50 },
    });
  });

  it('rejects unknown fields, blank cursors, and out-of-range pages', () => {
    expect(parseListDeletableDrivesInput({ extra: true }).ok).toBe(false);
    expect(parseListDeletableDrivesInput({ cursorRideId: ' ' }).ok).toBe(false);
    expect(parseListDeletableDrivesInput({ pageSize: 0 }).ok).toBe(false);
    expect(parseListDeletableDrivesInput({ pageSize: 51 }).ok).toBe(false);
    expect(parseListDeletableDrivesInput({ pageSize: 2.5 }).ok).toBe(false);
  });
});

describe('list-deletable-drives page size', () => {
  it('defaults and clamps to the maximum', () => {
    expect(deletableDrivesPageSize()).toBe(DELETABLE_DRIVES_PAGE_SIZE_DEFAULT);
    expect(deletableDrivesPageSize(10)).toBe(10);
    expect(deletableDrivesPageSize(999)).toBe(DELETABLE_DRIVES_PAGE_SIZE_MAX);
  });
});
