/**
 * Kronjakt AUTO-SPAWN marked-AREAS — admin data layer + pure shape helpers.
 *
 * The WIDER half of the auto-spawn safety model (the sibling of spawn-cells.ts):
 * an admin draws a polygon / circle / rectangle (`crownSpawnAreas/{areaId}`) the
 * scheduled spawner may place crowns INSIDE. Every mutation goes through the
 * audited, admin-gated, App Check-enforced crownHunt.* area callables:
 *   - crownHunt-createSpawnArea / updateSpawnArea / deleteSpawnArea (mutations)
 *   - crownHunt-listSpawnAreas (read; there is no direct Firestore fallback —
 *     the read is a callable so it is a single admin-verified surface).
 *
 * SAFETY GATE (mirrors activatePoint and setSpawnCellApproval): an area only
 * spawns while `active == true` AND `safeAreaConfirmed == true`, and setting
 * `active: true` REQUIRES the literal `safeAreaConfirmed: true` in the SAME
 * request. The gate is expressed here as {@link buildActivateAreaRequest}, which
 * returns `null` (refusing to activate) unless the confirmation is present, so a
 * default or truthy accident can never open an area.
 *
 * The shape conversion + client-side validation below MIRRORS the backend
 * `functions/src/crownHunt/crown-area-core.ts` (closed ring / >=3 distinct /
 * 4..500 vertices; radius 10..50000 m; rectangle north>south, east>west, no
 * antimeridian wrap; bounding box <= 20000 grid cells). It runs BEFORE the
 * callable so an operator gets an instant, localised error instead of a round
 * trip — the backend re-validates and remains the authority.
 */

import type {
  AdminCrownSpawnArea,
  AdminCrownSpawnAreaMutationResponse,
  AdminCreateCrownSpawnAreaRequest,
  AdminUpdateCrownSpawnAreaRequest,
  AdminDeleteCrownSpawnAreaRequest,
  AdminListCrownSpawnAreasRequest,
  AdminListCrownSpawnAreasResponse,
  AdminReingestSpawnAreaPoisResponse,
  CrownSpawnAreaShape,
  CrownSpawnAreaVertex,
} from '@carcommunity/shared/crown-hunt';

import { ApiError } from '../../lib/errors';
import { callAdmin } from '../../lib/callables';

export { ApiError };
export type {
  AdminCrownSpawnArea,
  AdminCrownSpawnAreaMutationResponse,
  AdminCreateCrownSpawnAreaRequest,
  AdminUpdateCrownSpawnAreaRequest,
  AdminDeleteCrownSpawnAreaRequest,
  AdminReingestSpawnAreaPoisResponse,
  CrownSpawnAreaShape,
  CrownSpawnAreaVertex,
};

// ---------------------------------------------------------------------------
// Bounds (mirror of crown-area-core.ts) — keep in sync with the backend
// ---------------------------------------------------------------------------

/** A closed polygon needs the 3 distinct vertices of a triangle plus the repeat. */
export const MIN_POLYGON_VERTICES = 4;
/** Hard ceiling on polygon complexity (per point-in-polygon cost). */
export const MAX_POLYGON_VERTICES = 500;
/** A closed ring must enclose an area — at least three DISTINCT vertices. */
export const MIN_DISTINCT_VERTICES = 3;
/** Circle radius floor: below this an "area" is really a single cell. */
export const MIN_AREA_RADIUS_METERS = 10;
/** Circle radius ceiling: 50 km (bounds the enclosing box's cell count). */
export const MAX_AREA_RADIUS_METERS = 50_000;
/** Largest number of grid cells an area's bounding box may span. */
export const MAX_AREA_CELLS = 20_000;
/** The Kronjakt grid cell size in degrees (mirrors CROWN_HUNT_CELL_DEGREES). */
export const CROWN_AREA_CELL_DEGREES = 0.01;
/** Longest area name the backend accepts. */
export const AREA_NAME_MAX_LENGTH = 120;

const METERS_PER_DEGREE_LAT = 111_320;

const clampLat = (lat: number) => Math.min(90, Math.max(-90, lat));
const clampLon = (lon: number) => Math.min(180, Math.max(-180, lon));

function isFiniteNumber(n: unknown): n is number {
  return typeof n === 'number' && Number.isFinite(n);
}

function isValidVertex(v: CrownSpawnAreaVertex | undefined | null): v is CrownSpawnAreaVertex {
  return (
    !!v &&
    isFiniteNumber(v.lat) &&
    isFiniteNumber(v.lon) &&
    v.lat >= -90 &&
    v.lat <= 90 &&
    v.lon >= -180 &&
    v.lon <= 180
  );
}

// ---------------------------------------------------------------------------
// Validation
// ---------------------------------------------------------------------------

/**
 * Machine-readable validation failure codes. Each maps to a localised message in
 * the admin i18n dictionary (`crownHunt.areaError.<code>`), so the UI never
 * shows a raw backend string. `ok` shapes carry no code.
 */
export type AreaValidationCode =
  | 'polygon_too_few'
  | 'polygon_too_many'
  | 'polygon_not_closed'
  | 'polygon_too_few_distinct'
  | 'circle_radius_range'
  | 'rectangle_lat_order'
  | 'rectangle_lng_order'
  | 'vertex_out_of_range'
  | 'area_too_large';

export interface AreaValidationResult {
  ok: boolean;
  /** Present iff `ok` is false. */
  code?: AreaValidationCode;
}

const OK: AreaValidationResult = { ok: true };
const fail = (code: AreaValidationCode): AreaValidationResult => ({ ok: false, code });

/** The axis-aligned lat/lon box enclosing a shape (clamped to the globe). */
export function shapeBoundingBox(shape: CrownSpawnAreaShape): {
  north: number;
  south: number;
  east: number;
  west: number;
} {
  switch (shape.type) {
    case 'rectangle':
      return {
        north: clampLat(shape.bounds.north),
        south: clampLat(shape.bounds.south),
        east: clampLon(shape.bounds.east),
        west: clampLon(shape.bounds.west),
      };
    case 'polygon': {
      let north = -90;
      let south = 90;
      let east = -180;
      let west = 180;
      for (const v of shape.vertices) {
        if (v.lat > north) north = v.lat;
        if (v.lat < south) south = v.lat;
        if (v.lon > east) east = v.lon;
        if (v.lon < west) west = v.lon;
      }
      return { north: clampLat(north), south: clampLat(south), east: clampLon(east), west: clampLon(west) };
    }
    case 'circle': {
      const latSpanDeg = shape.radiusMeters / METERS_PER_DEGREE_LAT;
      const cosLat = Math.max(0.01, Math.cos((shape.center.lat * Math.PI) / 180));
      const lonSpanDeg = shape.radiusMeters / (METERS_PER_DEGREE_LAT * cosLat);
      return {
        north: clampLat(shape.center.lat + latSpanDeg),
        south: clampLat(shape.center.lat - latSpanDeg),
        east: clampLon(shape.center.lon + lonSpanDeg),
        west: clampLon(shape.center.lon - lonSpanDeg),
      };
    }
  }
}

/** Number of 0.01° grid cells the bounding box spans (mirror of the backend). */
export function boundingBoxCellSpan(box: {
  north: number;
  south: number;
  east: number;
  west: number;
}): number {
  const latMin = Math.floor(clampLat(box.south) / CROWN_AREA_CELL_DEGREES);
  const latMax = Math.floor(clampLat(box.north) / CROWN_AREA_CELL_DEGREES);
  const lonMin = Math.floor(clampLon(box.west) / CROWN_AREA_CELL_DEGREES);
  const lonMax = Math.floor(clampLon(box.east) / CROWN_AREA_CELL_DEGREES);
  const latCells = Math.max(0, latMax - latMin) + 1;
  const lonCells = Math.max(0, lonMax - lonMin) + 1;
  return latCells * lonCells;
}

/**
 * Client-side pre-flight validation of a drawn shape, mirroring the backend
 * callable rules. Returns the FIRST failure (so the message is deterministic)
 * or `{ ok: true }`. The backend re-validates and is the final authority.
 */
export function validateAreaShape(shape: CrownSpawnAreaShape): AreaValidationResult {
  switch (shape.type) {
    case 'polygon': {
      const v = shape.vertices;
      if (!Array.isArray(v) || v.length < MIN_POLYGON_VERTICES) return fail('polygon_too_few');
      if (v.length > MAX_POLYGON_VERTICES) return fail('polygon_too_many');
      if (!v.every(isValidVertex)) return fail('vertex_out_of_range');
      const first = v[0];
      const last = v[v.length - 1];
      if (!first || !last) return fail('polygon_too_few');
      if (first.lat !== last.lat || first.lon !== last.lon) return fail('polygon_not_closed');
      const distinct = new Set(v.map((p) => `${p.lat},${p.lon}`));
      if (distinct.size < MIN_DISTINCT_VERTICES) return fail('polygon_too_few_distinct');
      break;
    }
    case 'circle': {
      if (!isValidVertex(shape.center)) return fail('vertex_out_of_range');
      if (
        !isFiniteNumber(shape.radiusMeters) ||
        shape.radiusMeters < MIN_AREA_RADIUS_METERS ||
        shape.radiusMeters > MAX_AREA_RADIUS_METERS
      ) {
        return fail('circle_radius_range');
      }
      break;
    }
    case 'rectangle': {
      const b = shape.bounds;
      if (
        !isFiniteNumber(b.north) ||
        !isFiniteNumber(b.south) ||
        !isFiniteNumber(b.east) ||
        !isFiniteNumber(b.west) ||
        b.north > 90 ||
        b.south < -90 ||
        b.east > 180 ||
        b.west < -180
      ) {
        return fail('vertex_out_of_range');
      }
      if (b.north <= b.south) return fail('rectangle_lat_order');
      if (b.east <= b.west) return fail('rectangle_lng_order');
      break;
    }
    default:
      return fail('vertex_out_of_range');
  }
  if (boundingBoxCellSpan(shapeBoundingBox(shape)) > MAX_AREA_CELLS) return fail('area_too_large');
  return OK;
}

// ---------------------------------------------------------------------------
// Draw geometry -> contract shape (pure)
// ---------------------------------------------------------------------------

/** A GeoJSON ring position is `[lon, lat]` — the opposite order of our vertex. */
export type GeoJsonPosition = [number, number];

/**
 * Convert a mapbox-gl-draw polygon ring (`[lon, lat]` positions, already closed)
 * to the contract polygon shape (`{lat, lon}` vertices). The ring is closed if
 * it is not already, so the result satisfies first==last.
 */
export function ringToPolygonShape(ring: readonly GeoJsonPosition[]): CrownSpawnAreaShape {
  const vertices: CrownSpawnAreaVertex[] = ring.map(([lon, lat]) => ({ lat, lon }));
  const first = vertices[0];
  const last = vertices[vertices.length - 1];
  if (first && last && (first.lat !== last.lat || first.lon !== last.lon)) {
    vertices.push({ lat: first.lat, lon: first.lon });
  }
  return { type: 'polygon', vertices };
}

/**
 * Convert a drawn rectangle's ring to the contract rectangle bounds. mapbox-gl-
 * draw has no rectangle primitive, so a rectangle is drawn as a 4-corner polygon
 * and reduced to its axis-aligned bounds here.
 */
export function ringToRectangleShape(ring: readonly GeoJsonPosition[]): CrownSpawnAreaShape {
  let north = -90;
  let south = 90;
  let east = -180;
  let west = 180;
  for (const [lon, lat] of ring) {
    if (lat > north) north = lat;
    if (lat < south) south = lat;
    if (lon > east) east = lon;
    if (lon < west) west = lon;
  }
  return { type: 'rectangle', bounds: { north, south, east, west } };
}

/** Build the contract circle shape from a picked centre + radius. */
export function circleToShape(
  center: { lat: number; lon: number },
  radiusMeters: number,
): CrownSpawnAreaShape {
  return { type: 'circle', center: { lat: center.lat, lon: center.lon }, radiusMeters };
}

// ---------------------------------------------------------------------------
// Contract shape -> GeoJSON (for rendering existing areas on the map)
// ---------------------------------------------------------------------------

export interface GeoJsonPolygonFeature {
  type: 'Feature';
  geometry: { type: 'Polygon'; coordinates: GeoJsonPosition[][] };
  properties: Record<string, unknown>;
}

/** Approximate a circle centre+radius as a closed GeoJSON ring (`steps` sides). */
function circleRing(
  center: { lat: number; lon: number },
  radiusMeters: number,
  steps = 64,
): GeoJsonPosition[] {
  const coords: GeoJsonPosition[] = [];
  const earth = 6378137;
  const latRad = (center.lat * Math.PI) / 180;
  const cosLat = Math.max(Math.cos(latRad), 1e-3);
  for (let i = 0; i <= steps; i += 1) {
    const angle = (i / steps) * 2 * Math.PI;
    const dx = (radiusMeters * Math.cos(angle)) / (earth * cosLat);
    const dy = (radiusMeters * Math.sin(angle)) / earth;
    coords.push([center.lon + (dx * 180) / Math.PI, center.lat + (dy * 180) / Math.PI]);
  }
  return coords;
}

/**
 * Convert any stored area shape to a GeoJSON Polygon feature so the admin map
 * can render existing areas uniformly (a circle is approximated as a 64-gon).
 */
export function shapeToGeoJson(
  shape: CrownSpawnAreaShape,
  properties: Record<string, unknown> = {},
): GeoJsonPolygonFeature {
  let ring: GeoJsonPosition[];
  switch (shape.type) {
    case 'polygon':
      ring = shape.vertices.map((v) => [v.lon, v.lat] as GeoJsonPosition);
      break;
    case 'rectangle': {
      const { north, south, east, west } = shape.bounds;
      ring = [
        [west, south],
        [east, south],
        [east, north],
        [west, north],
        [west, south],
      ];
      break;
    }
    case 'circle':
      ring = circleRing(shape.center, shape.radiusMeters);
      break;
  }
  return { type: 'Feature', geometry: { type: 'Polygon', coordinates: [ring] }, properties };
}

/** A human summary of a shape for the list ("Polygon · 6 pts", "Circle · 250 m"). */
export function describeShape(shape: CrownSpawnAreaShape): { type: string; detail: string } {
  switch (shape.type) {
    case 'polygon':
      return { type: 'polygon', detail: `${shape.vertices.length}` };
    case 'circle':
      return { type: 'circle', detail: `${Math.round(shape.radiusMeters)}` };
    case 'rectangle':
      return { type: 'rectangle', detail: '' };
  }
}

/**
 * The approximate centre of a shape, for framing the map on an existing area.
 * Polygon: centroid of the bounding box; circle: its centre; rectangle: mid.
 */
export function shapeCenter(shape: CrownSpawnAreaShape): { lat: number; lon: number } {
  if (shape.type === 'circle') return { lat: shape.center.lat, lon: shape.center.lon };
  const box = shapeBoundingBox(shape);
  return { lat: (box.north + box.south) / 2, lon: (box.east + box.west) / 2 };
}

// ---------------------------------------------------------------------------
// Activation safety gate (pure) — the testable "won't activate without confirm"
// ---------------------------------------------------------------------------

/**
 * Build the update request that ACTIVATES an area. Returns `null` — refusing to
 * activate — unless the operator has ticked the safe-area confirmation, so an
 * area can never go live without the literal `safeAreaConfirmed: true`. This is
 * the client half of the backend's re-confirmation gate.
 */
export function buildActivateAreaRequest(
  areaId: string,
  safeAreaConfirmed: boolean,
): AdminUpdateCrownSpawnAreaRequest | null {
  if (!safeAreaConfirmed) return null;
  return { areaId, active: true, safeAreaConfirmed: true };
}

/** Build the update request that DEACTIVATES an area (drains its live crowns). */
export function buildDeactivateAreaRequest(areaId: string): AdminUpdateCrownSpawnAreaRequest {
  return { areaId, active: false };
}

/**
 * Build a create request, applying the same activation gate: `active: true` is
 * only emitted alongside `safeAreaConfirmed: true`. An unconfirmed create is
 * stored as a draft (inactive) area.
 */
export function buildCreateAreaRequest(
  shape: CrownSpawnAreaShape,
  name: string | null,
  activateNow: boolean,
  safeAreaConfirmed: boolean,
): AdminCreateCrownSpawnAreaRequest {
  const trimmed = name?.trim() ?? '';
  const base: AdminCreateCrownSpawnAreaRequest = { shape };
  if (trimmed) base.name = trimmed;
  if (activateNow && safeAreaConfirmed) {
    base.active = true;
    base.safeAreaConfirmed = true;
  }
  return base;
}

/**
 * `poiCount` — the count of OSM safe stopping spots found in an area — is an
 * OPTIONAL field a parallel backend slice may add to the area document. Read it
 * defensively: render "N safe spots found" when present, omit the line when not.
 */
export function areaPoiCount(area: AdminCrownSpawnArea): number | null {
  const value = (area as AdminCrownSpawnArea & { poiCount?: unknown }).poiCount;
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

// ---------------------------------------------------------------------------
// Callable wrappers (safety-gated crownHunt.* area callables)
// ---------------------------------------------------------------------------

/** Lists marked spawn areas (admin). Read-only callable; returns [] when none. */
export async function adminListSpawnAreas(
  request: AdminListCrownSpawnAreasRequest = {},
): Promise<AdminCrownSpawnArea[]> {
  const res = await callAdmin<AdminListCrownSpawnAreasResponse>('crownHunt-listSpawnAreas', request);
  return Array.isArray(res?.areas) ? res.areas : [];
}

/**
 * Creates a marked spawn area. Validates the shape client-side first and throws
 * an {@link ApiError} (400) carrying the {@link AreaValidationCode} so the UI can
 * localise it, before the backend round trip.
 */
export async function adminCreateSpawnArea(
  request: AdminCreateCrownSpawnAreaRequest,
): Promise<AdminCrownSpawnAreaMutationResponse> {
  const validation = validateAreaShape(request.shape);
  if (!validation.ok) {
    throw new ApiError(400, 'invalid-argument', 'Invalid area shape.', { areaCode: validation.code });
  }
  return callAdmin<AdminCrownSpawnAreaMutationResponse>('crownHunt-createSpawnArea', request);
}

/** Updates a marked spawn area (partial). Re-validates the shape when one is sent. */
export async function adminUpdateSpawnArea(
  request: AdminUpdateCrownSpawnAreaRequest,
): Promise<AdminCrownSpawnAreaMutationResponse> {
  if (request.shape) {
    const validation = validateAreaShape(request.shape);
    if (!validation.ok) {
      throw new ApiError(400, 'invalid-argument', 'Invalid area shape.', { areaCode: validation.code });
    }
  }
  return callAdmin<AdminCrownSpawnAreaMutationResponse>('crownHunt-updateSpawnArea', request);
}

/** Deletes a marked spawn area (drains its live crowns first, backend-side). */
export async function adminDeleteSpawnArea(
  request: AdminDeleteCrownSpawnAreaRequest,
): Promise<AdminCrownSpawnAreaMutationResponse> {
  return callAdmin<AdminCrownSpawnAreaMutationResponse>('crownHunt-deleteSpawnArea', request);
}

/**
 * ON-DEMAND re-run of one area's OpenStreetMap safe-stop POI ingestion — the
 * "Retry POIs" button. Recovers from a transient Overpass timeout without waiting
 * for the weekly refresh or a deactivate+reactivate. Never throws on an Overpass
 * failure: the callable returns `{ ok: false, message }` (the previous cache is
 * kept), which the caller surfaces as a retry prompt rather than an error.
 */
export async function adminReingestSpawnAreaPois(
  areaId: string,
): Promise<AdminReingestSpawnAreaPoisResponse> {
  return callAdmin<AdminReingestSpawnAreaPoisResponse>('crownHunt-reingestSpawnAreaPois', {
    areaId,
  });
}
