/** Shared, server-authoritative budgets. UTC windows never trust client clocks. */
export const PHOTO_LIMITS = { minute: 6, hour: 30, day: 100 } as const;
export const CATALOG_LIMITS = { hour: 3, day: 10 } as const;
export const GLOBAL_CATALOGS_PER_DAY = 100;
export const GLOBAL_PHOTO_BYTES_PER_DAY = 128 * 1024 * 1024;
// Reserve the maximum ENCODED callable response, including transport overhead.
export const PHOTO_RESERVED_BYTES = Math.ceil((128 * 1024) / 3) * 4 + 2048;
export const MAX_CATALOG_RESPONSE_BYTES = 2 * 1024 * 1024;

export type CameraOperation = 'photo' | 'catalog';
export interface QuotaState {
  day?: number;
  hour?: number;
  minute?: number;
  photosDay?: number;
  photosHour?: number;
  photosMinute?: number;
  catalogsDay?: number;
  catalogsHour?: number;
  photoBytes?: number;
}

function count(value: number | undefined): number {
  // Malformed stored counters fail closed, rather than resetting a budget.
  return value === undefined ? 0 : Number.isSafeInteger(value) && value >= 0 ? value : Infinity;
}

export function consumeCameraQuota(
  operation: CameraOperation,
  user: QuotaState,
  global: QuotaState,
  nowMs: number,
): { user: QuotaState; global: QuotaState } | null {
  if (!Number.isFinite(nowMs) || nowMs < 0) return null;
  const day = Math.floor(nowMs / 86_400_000);
  const hour = Math.floor(nowMs / 3_600_000);
  const minute = Math.floor(nowMs / 60_000);
  const nextUser: QuotaState = {
    day,
    hour,
    minute,
    photosDay: user.day === day ? count(user.photosDay) : 0,
    photosHour: user.hour === hour ? count(user.photosHour) : 0,
    photosMinute: user.minute === minute ? count(user.photosMinute) : 0,
    catalogsDay: user.day === day ? count(user.catalogsDay) : 0,
    catalogsHour: user.hour === hour ? count(user.catalogsHour) : 0,
  };
  const nextGlobal: QuotaState = {
    day,
    catalogsDay: global.day === day ? count(global.catalogsDay) : 0,
    photoBytes: global.day === day ? count(global.photoBytes) : 0,
  };
  if (
    [...Object.values(nextUser), ...Object.values(nextGlobal)].some(
      (value) => !Number.isFinite(value),
    )
  ) {
    return null;
  }
  if (operation === 'photo') {
    if (
      nextUser.photosMinute! >= PHOTO_LIMITS.minute ||
      nextUser.photosHour! >= PHOTO_LIMITS.hour ||
      nextUser.photosDay! >= PHOTO_LIMITS.day ||
      nextGlobal.photoBytes! + PHOTO_RESERVED_BYTES > GLOBAL_PHOTO_BYTES_PER_DAY
    )
      return null;
    nextUser.photosMinute! += 1;
    nextUser.photosHour! += 1;
    nextUser.photosDay! += 1;
    nextGlobal.photoBytes! += PHOTO_RESERVED_BYTES;
  } else {
    if (
      nextUser.catalogsHour! >= CATALOG_LIMITS.hour ||
      nextUser.catalogsDay! >= CATALOG_LIMITS.day ||
      nextGlobal.catalogsDay! >= GLOBAL_CATALOGS_PER_DAY
    )
      return null;
    nextUser.catalogsHour! += 1;
    nextUser.catalogsDay! += 1;
    nextGlobal.catalogsDay! += 1;
  }
  return { user: nextUser, global: nextGlobal };
}
