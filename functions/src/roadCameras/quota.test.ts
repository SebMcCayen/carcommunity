import { describe, expect, it } from 'vitest';
import {
  CATALOG_LIMITS,
  GLOBAL_CATALOGS_PER_DAY,
  GLOBAL_PHOTO_BYTES_PER_DAY,
  PHOTO_RESERVED_BYTES,
  consumeCameraQuota,
  type QuotaState,
} from './quota';

const now = Date.UTC(2026, 10, 12, 12);
const minute = Math.floor(now / 60_000);
const hour = Math.floor(now / 3_600_000);
const day = Math.floor(now / 86_400_000);
const active = { minute, hour, day };

describe('camera photo budgets', () => {
  it('allows six photo requests per minute and rejects the seventh', () => {
    let user: QuotaState = {};
    let global: QuotaState = {};
    for (let i = 0; i < 6; i++) {
      const next = consumeCameraQuota('photo', user, global, now);
      expect(next).not.toBeNull();
      user = next!.user;
      global = next!.global;
    }
    expect(consumeCameraQuota('photo', user, global, now)).toBeNull();
    expect(consumeCameraQuota('photo', user, global, now + 60_000)).not.toBeNull();
  });

  it('keeps the hourly budget across minute resets', () => {
    const user = { ...active, photosMinute: 6, photosHour: 30, photosDay: 30 };
    expect(consumeCameraQuota('photo', user, {}, now + 60_000)).toBeNull();
    const nextHour = consumeCameraQuota('photo', user, {}, now + 3_600_000);
    expect(nextHour!.user).toMatchObject({ photosMinute: 1, photosHour: 1, photosDay: 31 });
  });

  it('keeps the daily budget across hour resets and restores it at UTC midnight', () => {
    const user = { ...active, photosMinute: 0, photosHour: 0, photosDay: 100 };
    expect(consumeCameraQuota('photo', user, {}, now + 3_600_000)).toBeNull();
    const midnight = (day + 1) * 86_400_000;
    expect(consumeCameraQuota('photo', user, {}, midnight)!.user).toMatchObject({
      photosMinute: 1,
      photosHour: 1,
      photosDay: 1,
    });
  });

  it('permits exactly one remaining response reservation, then blocks all users', () => {
    const global = { day, photoBytes: GLOBAL_PHOTO_BYTES_PER_DAY - PHOTO_RESERVED_BYTES };
    const next = consumeCameraQuota('photo', {}, global, now);
    expect(next!.global.photoBytes).toBe(GLOBAL_PHOTO_BYTES_PER_DAY);
    expect(consumeCameraQuota('photo', {}, next!.global, now)).toBeNull();
    expect(consumeCameraQuota('photo', {}, next!.global, (day + 1) * 86_400_000)).not.toBeNull();
  });

  it('accounts for base64 expansion and response overhead in the shared bandwidth reservation', () => {
    expect(PHOTO_RESERVED_BYTES).toBeGreaterThan((128 * 1024 * 4) / 3);
    expect(
      consumeCameraQuota(
        'photo',
        {},
        {
          day,
          photoBytes: GLOBAL_PHOTO_BYTES_PER_DAY - PHOTO_RESERVED_BYTES + 1,
        },
        now,
      ),
    ).toBeNull();
  });
});

describe('camera catalog budgets', () => {
  it('enforces the per-user hourly and daily limits independently', () => {
    const fullHour = { ...active, catalogsHour: CATALOG_LIMITS.hour, catalogsDay: 3 };
    expect(consumeCameraQuota('catalog', fullHour, {}, now)).toBeNull();
    const renewed = consumeCameraQuota('catalog', fullHour, {}, now + 3_600_000);
    expect(renewed!.user).toMatchObject({ catalogsHour: 1, catalogsDay: 4 });
    expect(
      consumeCameraQuota(
        'catalog',
        { ...active, catalogsDay: CATALOG_LIMITS.day },
        {},
        now + 3_600_000,
      ),
    ).toBeNull();
  });

  it('accepts the hundredth global catalog response and rejects the next', () => {
    const next = consumeCameraQuota(
      'catalog',
      {},
      { day, catalogsDay: GLOBAL_CATALOGS_PER_DAY - 1 },
      now,
    );
    expect(next!.global.catalogsDay).toBe(100);
    expect(consumeCameraQuota('catalog', {}, next!.global, now)).toBeNull();
  });

  it('does not spend photo budget on catalog requests, or catalog budget on photos', () => {
    const catalog = consumeCameraQuota(
      'catalog',
      {
        ...active,
        photosDay: 7,
        photosHour: 5,
        photosMinute: 3,
      },
      { day, photoBytes: PHOTO_RESERVED_BYTES },
      now,
    )!;
    expect(catalog.user).toMatchObject({ photosDay: 7, photosHour: 5, photosMinute: 3 });
    expect(catalog.global.photoBytes).toBe(PHOTO_RESERVED_BYTES);
    const photo = consumeCameraQuota('photo', catalog.user, catalog.global, now)!;
    expect(photo.user.catalogsDay).toBe(1);
    expect(photo.user.catalogsHour).toBe(1);
    expect(photo.global.catalogsDay).toBe(1);
  });
});

describe('quota decision integrity', () => {
  it('returns one new state pair without partially mutating either input', () => {
    const user = Object.freeze({ ...active, photosDay: 99, photosHour: 1, photosMinute: 1 });
    const global = Object.freeze({ day, photoBytes: 0 });
    const next = consumeCameraQuota('photo', user, global, now)!;
    expect(next.user.photosDay).toBe(100);
    expect(next.global.photoBytes).toBe(PHOTO_RESERVED_BYTES);
    expect(user.photosDay).toBe(99);
    expect(global.photoBytes).toBe(0);
    expect(consumeCameraQuota('photo', next.user, next.global, now)).toBeNull();
  });

  it.each([-1, 0.5, Number.NaN, Infinity, Number.MAX_SAFE_INTEGER + 1])(
    'fails closed for malformed active counters (%s)',
    (bad) => {
      expect(consumeCameraQuota('photo', { ...active, photosMinute: bad }, {}, now)).toBeNull();
      expect(consumeCameraQuota('photo', { ...active, photosHour: bad }, {}, now)).toBeNull();
      expect(consumeCameraQuota('photo', { ...active, photosDay: bad }, {}, now)).toBeNull();
      expect(consumeCameraQuota('photo', {}, { day, photoBytes: bad }, now)).toBeNull();
      expect(consumeCameraQuota('catalog', { ...active, catalogsHour: bad }, {}, now)).toBeNull();
      expect(consumeCameraQuota('catalog', { ...active, catalogsDay: bad }, {}, now)).toBeNull();
      expect(consumeCameraQuota('catalog', {}, { day, catalogsDay: bad }, now)).toBeNull();
    },
  );

  it('ignores stale counters only after their real window expires', () => {
    const next = consumeCameraQuota(
      'photo',
      {
        minute: minute - 1,
        hour: hour - 1,
        day: day - 1,
        photosMinute: Infinity,
        photosHour: Infinity,
        photosDay: Infinity,
      },
      { day: day - 1, photoBytes: Infinity },
      now,
    );
    expect(next!.user).toMatchObject({ photosMinute: 1, photosHour: 1, photosDay: 1 });
  });

  it.each([Number.NaN, Infinity, -1])('rejects invalid server times (%s)', (time) => {
    expect(consumeCameraQuota('photo', {}, {}, time)).toBeNull();
  });
});
