/** Pure mappings for Trafikverket's Road.Infrastructure camera catalogues.
 * Schemas verified 2026-11-12: Camera 1.1 and TrafficSafetyCamera 1.0.
 * https://data.trafikverket.se/documentation/datacache/data-model
 */
import { createHash } from 'node:crypto';

export const CAMERA_QUERY_LIMIT = 10_000;
export const SPEED_CAMERA_QUERY_LIMIT = 10_000;
export const MAX_PHOTO_BYTES = 128 * 1024;

export type CameraType = 'speed' | 'traffic' | 'road_condition';

export interface PublicCamera {
  id: string;
  type: CameraType;
  name: string;
  latitude: number;
  longitude: number;
  /** Degrees clockwise from north, or null when upstream has no valid bearing. */
  direction: number | null;
}

/** Image endpoints remain server-only; clients request photos by the opaque id. */
export interface StoredCamera extends PublicCamera {
  photoUrl: string | null;
  photoTime: string | null;
}

function escapeXml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;');
}

function buildRequest(
  key: string,
  type: 'Camera' | 'TrafficSafetyCamera',
  version: string,
  fields: readonly string[],
  limit: number,
): string {
  if (!Number.isInteger(limit) || limit < 1 || limit > 10_000) {
    throw new Error('Invalid camera query limit');
  }
  return (
    '<REQUEST>' +
    `<LOGIN authenticationkey="${escapeXml(key)}" />` +
    `<QUERY objecttype="${type}" namespace="Road.Infrastructure" schemaversion="${version}" limit="${limit}">` +
    '<FILTER>' +
    (type === 'Camera' ? '<AND>' : '') +
    '<EQ name="Deleted" value="false" />' +
    (type === 'Camera' ? '<EQ name="Active" value="true" /></AND>' : '') +
    '</FILTER>' +
    fields.map((field) => `<INCLUDE>${field}</INCLUDE>`).join('') +
    '</QUERY></REQUEST>'
  );
}

export function buildCameraRequest(key: string, limit = CAMERA_QUERY_LIMIT): string {
  return buildRequest(
    key,
    'Camera',
    '1.1',
    [
      'Id',
      'Name',
      'Type',
      'Active',
      'Deleted',
      'Geometry.WGS84',
      'Direction',
      'PhotoUrl',
      'PhotoUrlThumbnail',
      'PhotoTime',
    ],
    limit,
  );
}

export function buildSpeedCameraRequest(key: string, limit = SPEED_CAMERA_QUERY_LIMIT): string {
  // TrafficSafetyCamera has Bearing, not Direction, and has no Active field.
  return buildRequest(
    key,
    'TrafficSafetyCamera',
    '1.0',
    ['Id', 'Name', 'Deleted', 'Geometry.WGS84', 'Bearing'],
    limit,
  );
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value);
}

/** A complete, non-empty national snapshot is necessary before replacement. */
function sourceRows(response: unknown, type: string, limit: number): unknown[] {
  if (
    !isRecord(response) ||
    !isRecord(response.RESPONSE) ||
    !Array.isArray(response.RESPONSE.RESULT) ||
    response.RESPONSE.RESULT.length === 0
  ) {
    throw new Error(`Invalid ${type} response`);
  }
  const rows: unknown[] = [];
  for (const result of response.RESPONSE.RESULT) {
    if (!isRecord(result) || result.ERROR || !Array.isArray(result[type])) {
      throw new Error(`Invalid ${type} result`);
    }
    rows.push(...result[type]);
  }
  if (rows.length === 0 || rows.length >= limit) {
    throw new Error(`Incomplete ${type} national snapshot`);
  }
  return rows;
}

/** Stable Firestore-safe key; identical upstream ids in separate types cannot collide. */
export function cameraDocumentId(type: CameraType, sourceId: string): string {
  return `${type}_${createHash('sha256').update(sourceId).digest('hex').slice(0, 32)}`;
}

/** Only documented Trafikverket image endpoints, with no credentials or arbitrary query. */
export function sanitizePhotoUrl(value: unknown): string | null {
  if (
    typeof value !== 'string' ||
    value.length > 2048 ||
    !/^https:\/\/api\.trafikinfo\.trafikverket\.se\//i.test(value)
  )
    return null;
  try {
    const url = new URL(value);
    if (
      url.username ||
      url.password ||
      url.port ||
      url.hash ||
      !/^\/v\d+(?:\.\d+)?\/Images\/[A-Za-z0-9_.-]+\.(?:jpe?g|png)$/i.test(url.pathname)
    )
      return null;
    for (const key of url.searchParams.keys()) {
      if (key !== 'type' && key !== 'maxage') return null;
    }
    // Standard 385x290 image is sufficient for the detail sheet. Disallow fullsize
    // and sketches, and reject old imagery through the documented maxage option.
    url.search = '?maxage=15';
    return url.toString();
  } catch {
    return null;
  }
}

function point(value: unknown): { latitude: number; longitude: number } | null {
  if (typeof value !== 'string') return null;
  const match = /^\s*POINT\s*\(\s*(-?\d+(?:\.\d+)?)\s+(-?\d+(?:\.\d+)?)\s*\)\s*$/i.exec(value);
  if (!match) return null;
  const longitude = Number(match[1]);
  const latitude = Number(match[2]);
  if (
    !Number.isFinite(latitude) ||
    !Number.isFinite(longitude) ||
    latitude < -90 ||
    latitude > 90 ||
    longitude < -180 ||
    longitude > 180
  )
    return null;
  return { latitude, longitude };
}

function bearing(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) && value >= 0 && value <= 360
    ? value % 360
    : null;
}

function photoTime(value: unknown): string | null {
  if (
    typeof value !== 'string' ||
    !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?(?:Z|[+-]\d{2}:?\d{2})$/.test(value)
  )
    return null;
  const ms = Date.parse(value);
  return Number.isFinite(ms) ? new Date(ms).toISOString() : null;
}

function camera(row: unknown, source: 'Camera' | 'TrafficSafetyCamera'): StoredCamera | null {
  if (!isRecord(row) || row.Deleted === true || (source === 'Camera' && row.Active === false))
    return null;
  const sourceId = typeof row.Id === 'string' ? row.Id.trim() : '';
  if (!sourceId || sourceId.length > 512 || /[\u0000-\u001f\u007f]/.test(sourceId)) return null;
  const geometry = isRecord(row.Geometry) ? point(row.Geometry.WGS84) : null;
  if (!geometry) return null;
  const type: CameraType | null =
    source === 'TrafficSafetyCamera'
      ? 'speed'
      : row.Type === 'Väglagskamera'
        ? 'road_condition'
        : row.Type === 'Trafikflödeskamera'
          ? 'traffic'
          : null;
  if (!type) return null;
  const rawName = typeof row.Name === 'string' ? row.Name.trim() : '';
  // Missing names remain usable; type labels in the native apps supply context.
  const name = (rawName || sourceId).replace(/[\u0000-\u001f\u007f]/g, ' ').slice(0, 160);
  return {
    id: cameraDocumentId(type, sourceId),
    type,
    name,
    ...geometry,
    direction: bearing(source === 'TrafficSafetyCamera' ? row.Bearing : row.Direction),
    photoUrl:
      type === 'speed'
        ? null
        : (sanitizePhotoUrl(row.PhotoUrl) ?? sanitizePhotoUrl(row.PhotoUrlThumbnail)),
    photoTime: type === 'speed' ? null : photoTime(row.PhotoTime),
  };
}

export interface ParsedCameraCatalog {
  cameras: StoredCamera[];
  sourceCount: number;
  cameraSourceCount: number;
  speedSourceCount: number;
}

export function parseCameraCatalog(
  cameraResponse: unknown,
  speedResponse: unknown,
): ParsedCameraCatalog {
  const cameraRows = sourceRows(cameraResponse, 'Camera', CAMERA_QUERY_LIMIT);
  const speedRows = sourceRows(speedResponse, 'TrafficSafetyCamera', SPEED_CAMERA_QUERY_LIMIT);
  const cameras = new Map<string, StoredCamera>();
  for (const [source, rows] of [
    ['Camera', cameraRows],
    ['TrafficSafetyCamera', speedRows],
  ] as const) {
    let parsedCount = 0;
    for (const row of rows) {
      const parsed = camera(row, source);
      if (parsed) {
        cameras.set(parsed.id, parsed);
        parsedCount++;
      }
    }
    if (parsedCount === 0) throw new Error(`No usable ${source} records`);
  }
  return {
    cameras: [...cameras.values()].sort((a, b) => a.id.localeCompare(b.id)),
    sourceCount: cameraRows.length + speedRows.length,
    cameraSourceCount: cameraRows.length,
    speedSourceCount: speedRows.length,
  };
}

/** Explicit projection ensures photo endpoints and upstream metadata never leak. */
export function toPublicCamera(camera: StoredCamera): PublicCamera {
  return {
    id: camera.id,
    type: camera.type,
    name: camera.name,
    latitude: camera.latitude,
    longitude: camera.longitude,
    direction: camera.direction,
  };
}
