/**
 * Kronjakt AUTO-SPAWN — MARKED-AREA geometry and admin-input core (pure).
 *
 * The single-cell allow-list (`crownSpawnCells`, spawnCells.ts) lets an admin
 * approve ONE ~1.1 km grid cell at a time. That is the right unit for the
 * engine's density budget, but the wrong unit for a human drawing on a map: an
 * admin wants to sweep out a whole town centre, a lake shore, an industrial
 * estate to EXCLUDE by omission — a BIG area — not tick 400 cells by hand.
 *
 * This module is the maths for that: a marked area is a POLYGON, a CIRCLE, or a
 * RECTANGLE an admin drew, and the spawner places crowns at random points that
 * are actually INSIDE the drawn shape, density-weighted per grid cell exactly
 * as the single-cell path is. Everything here is pure — no Firebase Admin SDK,
 * no I/O, no clock — so the point-in-shape tests and the area→cells enumeration
 * run without the emulator, in the COLOCATED sibling ./crown-area-core.test.ts.
 *
 * SAFETY: the area model PRESERVES the existing gate, one level wider. A marked
 * area does nothing until an admin sets `active: true` AND, in the same call,
 * `safeAreaConfirmed: true` (a literal, so a default or a truthy accident can
 * never satisfy it) — the same shape as `activatePoint` and
 * `setSpawnCellApproval`. The in-shape filter never LOOSENS a placement: a
 * candidate must clear the shape test, the cell-membership re-key, AND the
 * 150 m separation rule to be written. The `A < 1` activity floor and the
 * slow-sighting filter still run underneath, so an approved area that happens
 * to contain a through-road still spawns nothing beside it.
 *
 * Geometry is planar (lat/lon treated as y/x for polygon ray-casting) but every
 * DISTANCE is Haversine in metres, reused from crown-hunt-geo — a circle's
 * radius and the crown separation are true ground distances, not degree boxes.
 * At Swedish latitudes over an area a human can draw, the planar approximation
 * for the point-in-polygon crossing test is well within a metre of the geodesic
 * answer and costs nothing.
 */

import { z } from 'zod';
import { haversineDistanceMeters } from './crown-hunt-geo';
import { CROWN_CELL_DEGREES, type CrownPosition } from './crown-spawn-core';

// ---------------------------------------------------------------------------
// Shape model
// ---------------------------------------------------------------------------

export const CROWN_SPAWN_AREA_SHAPE_TYPES = ['polygon', 'circle', 'rectangle'] as const;
export type CrownSpawnAreaShapeType = (typeof CROWN_SPAWN_AREA_SHAPE_TYPES)[number];

/** A WGS-84 vertex. `lon` chosen over `lng` to match the rest of the codebase's split. */
export interface GeoVertex {
  lat: number;
  lon: number;
}

/** A closed GeoJSON-style ring: the first and last vertex are equal. */
export interface CrownSpawnPolygonShape {
  type: 'polygon';
  vertices: GeoVertex[];
}

export interface CrownSpawnCircleShape {
  type: 'circle';
  center: GeoVertex;
  radiusMeters: number;
}

/** Axis-aligned bounds. `east > west` and `north > south`; no antimeridian wrap. */
export interface CrownSpawnRectangleBounds {
  north: number;
  south: number;
  east: number;
  west: number;
}

export interface CrownSpawnRectangleShape {
  type: 'rectangle';
  bounds: CrownSpawnRectangleBounds;
}

export type CrownSpawnAreaShape =
  CrownSpawnPolygonShape | CrownSpawnCircleShape | CrownSpawnRectangleShape;

// ---------------------------------------------------------------------------
// Bounds
// ---------------------------------------------------------------------------

/** Smallest polygon that can enclose an area (a closed triangle: 3 distinct + repeat). */
export const MIN_POLYGON_VERTICES = 4;

/**
 * Hard ceiling on polygon complexity. A hand-drawn area needs far fewer; the
 * bound exists so a single document cannot carry an unbounded ring that would
 * make every point-in-polygon test (run once per sampled candidate) expensive.
 */
export const MAX_POLYGON_VERTICES = 500;

/** Circle radius floor — below this an "area" is a point, use a single cell. */
export const MIN_AREA_RADIUS_METERS = 10;

/**
 * Circle radius ceiling: 50 km. Generous enough for "the whole city" while
 * bounding the enclosing box's cell count (a 50 km radius is ~1e4 cells, under
 * {@link MAX_AREA_CELLS}). A bigger region should be drawn as several areas.
 */
export const MAX_AREA_RADIUS_METERS = 50_000;

/** Longest side an admin's on-screen area label may carry; null means unnamed. */
export const AREA_NAME_MAX_LENGTH = 120;

/** Metres per degree of latitude (WGS-84 mean). Longitude scales by cos(lat). */
const METERS_PER_DEGREE_LAT = 111_320;

/**
 * The largest number of grid cells an area's bounding box may span.
 *
 * Two jobs. It BOUNDS validation (an area whose box would enumerate more cells
 * than this is rejected at the input boundary, so a globe-spanning rectangle
 * can never be stored), and it BOUNDS the spawn pass (`cellKeysForBoundingBox`
 * stops enumerating here). 20 000 cells ≈ 12 800 km² — larger than most Swedish
 * municipalities, and a region past that belongs in several drawn areas rather
 * than one, both for this ceiling and because the density budget is per-cell.
 */
export const MAX_AREA_CELLS = 20_000;

const clampLat = (lat: number) => Math.min(90, Math.max(-90, lat));
const clampLon = (lon: number) => Math.min(180, Math.max(-180, lon));

/** The axis-aligned lat/lon box that encloses a shape. */
export type LatLonBox = CrownSpawnRectangleBounds;

/**
 * The enclosing box of a shape, clamped to the globe.
 *
 * Circle: the centre displaced by the radius, converting metres to degrees
 * (latitude by a fixed scale, longitude by that scale times `cos(lat)` so the
 * box is wide enough at the equator and correctly narrow near the poles). The
 * `cos` is floored so a near-polar circle cannot produce an infinite longitude
 * span; the result is clamped to [-180, 180] regardless.
 */
export function shapeBoundingBox(shape: CrownSpawnAreaShape): LatLonBox {
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
      return {
        north: clampLat(north),
        south: clampLat(south),
        east: clampLon(east),
        west: clampLon(west),
      };
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

// ---------------------------------------------------------------------------
// Point-in-shape
// ---------------------------------------------------------------------------

/**
 * Ray-casting point-in-polygon over a lat/lon ring (lon as x, lat as y).
 *
 * Works on an explicitly closed ring (first == last) or an open one — a closed
 * ring simply adds a zero-length final edge that never crosses the ray. A point
 * exactly on an edge is boundary-ambiguous, which is inherent to the test and
 * harmless here: a spawn candidate landing precisely on a floating-point edge is
 * a measure-zero event, and either answer keeps the crown within a metre of the
 * drawn area.
 */
export function isPointInPolygon(
  lat: number,
  lon: number,
  vertices: readonly GeoVertex[],
): boolean {
  const n = vertices.length;
  if (n < 3) return false;
  let inside = false;
  for (let i = 0, j = n - 1; i < n; j = i, i += 1) {
    const yi = vertices[i]!.lat;
    const xi = vertices[i]!.lon;
    const yj = vertices[j]!.lat;
    const xj = vertices[j]!.lon;
    const crosses = yi > lat !== yj > lat;
    if (crosses && lon < ((xj - xi) * (lat - yi)) / (yj - yi) + xi) {
      inside = !inside;
    }
  }
  return inside;
}

/** True when the point is within `radiusMeters` (Haversine) of the centre — edge inclusive. */
export function isPointInCircle(
  lat: number,
  lon: number,
  center: GeoVertex,
  radiusMeters: number,
): boolean {
  if (!Number.isFinite(radiusMeters) || radiusMeters <= 0) return false;
  return haversineDistanceMeters(lat, lon, center.lat, center.lon) <= radiusMeters;
}

/** True when the point lies within the axis-aligned bounds — all edges inclusive. */
export function isPointInRectangle(
  lat: number,
  lon: number,
  bounds: CrownSpawnRectangleBounds,
): boolean {
  return lat >= bounds.south && lat <= bounds.north && lon >= bounds.west && lon <= bounds.east;
}

/** Dispatches to the shape-specific test. */
export function isPointInShape(lat: number, lon: number, shape: CrownSpawnAreaShape): boolean {
  switch (shape.type) {
    case 'polygon':
      return isPointInPolygon(lat, lon, shape.vertices);
    case 'circle':
      return isPointInCircle(lat, lon, shape.center, shape.radiusMeters);
    case 'rectangle':
      return isPointInRectangle(lat, lon, shape.bounds);
  }
}

/**
 * An accept predicate for `sampleCrownPosition`, in its `{latitude, longitude}`
 * shape. Wrapped here so the spawner stays declarative — it passes
 * `accept: pointInShapeAccept(shape)` and the sampler re-draws until a candidate
 * is inside the drawn area.
 */
export function pointInShapeAccept(
  shape: CrownSpawnAreaShape,
): (position: CrownPosition) => boolean {
  return (position) => isPointInShape(position.latitude, position.longitude, shape);
}

// ---------------------------------------------------------------------------
// Area → grid cells
// ---------------------------------------------------------------------------

/**
 * The count of grid cells an axis-aligned box spans (inclusive on both edges).
 * Cheap arithmetic — used both to reject an over-large area at input time and to
 * decide how the spawner budgets an area across runs.
 */
export function boundingBoxCellSpan(box: LatLonBox): number {
  const latMin = Math.floor(clampLat(box.south) / CROWN_CELL_DEGREES);
  const latMax = Math.floor(clampLat(box.north) / CROWN_CELL_DEGREES);
  const lonMin = Math.floor(clampLon(box.west) / CROWN_CELL_DEGREES);
  const lonMax = Math.floor(clampLon(box.east) / CROWN_CELL_DEGREES);
  return (latMax - latMin + 1) * (lonMax - lonMin + 1);
}

export interface CellEnumeration {
  keys: string[];
  /** True when {@link MAX_AREA_CELLS} (or the caller's cap) stopped enumeration short. */
  truncated: boolean;
}

/**
 * Every grid cell key whose cell box intersects `box`, row-major and stable.
 *
 * Bounded by `maxCells` (default {@link MAX_AREA_CELLS}); enumeration stops and
 * flags `truncated` rather than materialising an unbounded list. The keys are a
 * SUPERSET of the shape's true footprint — a circle inscribed in its box leaves
 * corner cells that touch no part of the circle — and that is correct: those
 * cells simply spawn nothing because every sampled candidate fails the in-shape
 * accept test, so the enumeration can stay a pure bounding-box scan.
 */
export function cellKeysForBoundingBox(
  box: LatLonBox,
  maxCells: number = MAX_AREA_CELLS,
): CellEnumeration {
  const latMin = Math.floor(clampLat(box.south) / CROWN_CELL_DEGREES);
  const latMax = Math.floor(clampLat(box.north) / CROWN_CELL_DEGREES);
  const lonMin = Math.floor(clampLon(box.west) / CROWN_CELL_DEGREES);
  const lonMax = Math.floor(clampLon(box.east) / CROWN_CELL_DEGREES);
  const cap = Math.max(1, maxCells);

  const keys: string[] = [];
  let truncated = false;
  for (let latIdx = latMin; latIdx <= latMax; latIdx += 1) {
    for (let lonIdx = lonMin; lonIdx <= lonMax; lonIdx += 1) {
      if (keys.length >= cap) {
        truncated = true;
        return { keys, truncated };
      }
      keys.push(`${latIdx}_${lonIdx}`);
    }
  }
  return { keys, truncated };
}

/** All grid cells an area covers (bounding-box superset). Convenience over the two above. */
export function cellKeysForShape(
  shape: CrownSpawnAreaShape,
  maxCells: number = MAX_AREA_CELLS,
): CellEnumeration {
  return cellKeysForBoundingBox(shapeBoundingBox(shape), maxCells);
}

// ---------------------------------------------------------------------------
// Admin input validation (zod)
// ---------------------------------------------------------------------------

const vertexSchema = z
  .object({
    lat: z.number().finite().min(-90).max(90),
    lon: z.number().finite().min(-180).max(180),
  })
  .strict();

// Plain object members — no per-member `.refine`, so `z.discriminatedUnion`
// gets ZodObjects (a refined member is a ZodEffects and is rejected). The
// cross-field checks (closed ring, distinct vertices, north>south, east>west)
// are applied ONCE on the union below via `.superRefine`, dispatched by `type`.
const polygonShapeSchema = z
  .object({
    type: z.literal('polygon'),
    vertices: z.array(vertexSchema).min(MIN_POLYGON_VERTICES).max(MAX_POLYGON_VERTICES),
  })
  .strict();

const circleShapeSchema = z
  .object({
    type: z.literal('circle'),
    center: vertexSchema,
    radiusMeters: z.number().finite().min(MIN_AREA_RADIUS_METERS).max(MAX_AREA_RADIUS_METERS),
  })
  .strict();

const rectangleShapeSchema = z
  .object({
    type: z.literal('rectangle'),
    bounds: z
      .object({
        north: z.number().finite().min(-90).max(90),
        south: z.number().finite().min(-90).max(90),
        east: z.number().finite().min(-180).max(180),
        west: z.number().finite().min(-180).max(180),
      })
      .strict(),
  })
  .strict();

/** The discriminated union of the three shapes, each fully validated. */
export const crownSpawnAreaShapeSchema = z
  .discriminatedUnion('type', [polygonShapeSchema, circleShapeSchema, rectangleShapeSchema])
  .superRefine((shape, ctx) => {
    if (shape.type === 'polygon') {
      const first = shape.vertices[0]!;
      const last = shape.vertices[shape.vertices.length - 1]!;
      if (first.lat !== last.lat || first.lon !== last.lon) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: 'polygon ring must be closed: first and last vertex equal.',
        });
      }
      const distinct = new Set(shape.vertices.map((v) => `${v.lat},${v.lon}`));
      if (distinct.size < 3) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: 'polygon needs at least three distinct vertices.',
        });
      }
    } else if (shape.type === 'rectangle') {
      if (shape.bounds.north <= shape.bounds.south) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: 'rectangle bounds require north > south.',
        });
      }
      if (shape.bounds.east <= shape.bounds.west) {
        ctx.addIssue({
          code: z.ZodIssueCode.custom,
          message: 'rectangle bounds require east > west (no antimeridian wrap).',
        });
      }
    }
  });

/**
 * A shape whose enclosing box does not exceed {@link MAX_AREA_CELLS} grid cells.
 * Rejecting here means a runaway box can never be STORED, so the spawn pass
 * never has to defend against one it read back.
 */
const boundedShapeSchema = crownSpawnAreaShapeSchema.refine(
  (shape) => boundingBoxCellSpan(shapeBoundingBox(shape)) <= MAX_AREA_CELLS,
  { message: `area is too large: its bounding box exceeds ${MAX_AREA_CELLS} grid cells.` },
);

const areaNameSchema = z.string().trim().min(1).max(AREA_NAME_MAX_LENGTH).nullable().optional();

const areaIdSchema = z
  .string()
  .trim()
  .min(1)
  .max(64)
  .regex(/^[A-Za-z0-9_-]+$/, { message: 'areaId must be an opaque id.' });

/**
 * Create input. Activating on creation requires the safety literal in the SAME
 * call — you cannot draw an area and switch it on without confirming the whole
 * of it is safe to place in, exactly as `setSpawnCellApproval` requires the
 * confirmation on every approve.
 */
const createSpawnAreaInputSchema = z
  .object({
    shape: boundedShapeSchema,
    name: areaNameSchema,
    active: z.boolean().optional(),
    safeAreaConfirmed: z.boolean().optional(),
  })
  .strict()
  .refine((input) => input.active !== true || input.safeAreaConfirmed === true, {
    message: 'Activating an area requires safeAreaConfirmed: true in the same request.',
  });

/**
 * Update input. `areaId` names the target; every other field is a partial
 * patch. The same activation gate holds: a request that sets `active: true`
 * must carry `safeAreaConfirmed: true`, so re-confirmation is required on every
 * (re)activation and can never be inherited from a stale stored flag.
 */
const updateSpawnAreaInputSchema = z
  .object({
    areaId: areaIdSchema,
    shape: boundedShapeSchema.optional(),
    name: areaNameSchema,
    active: z.boolean().optional(),
    safeAreaConfirmed: z.boolean().optional(),
  })
  .strict()
  .refine(
    (input) =>
      input.shape !== undefined ||
      input.name !== undefined ||
      input.active !== undefined ||
      input.safeAreaConfirmed !== undefined,
    { message: 'Provide at least one field to update.' },
  )
  .refine((input) => input.active !== true || input.safeAreaConfirmed === true, {
    message: 'Activating an area requires safeAreaConfirmed: true in the same request.',
  });

const deleteSpawnAreaInputSchema = z
  .object({
    areaId: areaIdSchema,
    reason: z.string().trim().max(500).optional(),
  })
  .strict();

const listSpawnAreasInputSchema = z
  .object({
    activeOnly: z.boolean().optional(),
    limit: z.number().int().min(1).max(500).optional(),
  })
  .strict()
  .optional();

export type CrownSpawnAreaShapeInput = z.infer<typeof crownSpawnAreaShapeSchema>;
export type CreateSpawnAreaInput = z.infer<typeof createSpawnAreaInputSchema>;
export type UpdateSpawnAreaInput = z.infer<typeof updateSpawnAreaInputSchema>;
export type DeleteSpawnAreaInput = z.infer<typeof deleteSpawnAreaInputSchema>;
export type ListSpawnAreasInput = z.infer<typeof listSpawnAreasInputSchema>;

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

function firstIssueMessage(error: z.ZodError, fallback: string): string {
  return error.issues[0]?.message ?? fallback;
}

export function parseCreateSpawnAreaInput(data: unknown): ParseResult<CreateSpawnAreaInput> {
  const result = createSpawnAreaInputSchema.safeParse(data ?? {});
  if (!result.success) {
    return {
      ok: false,
      message: firstIssueMessage(
        result.error,
        'Expected { shape: polygon|circle|rectangle, name?, active?, safeAreaConfirmed? }.',
      ),
    };
  }
  return { ok: true, input: result.data };
}

export function parseUpdateSpawnAreaInput(data: unknown): ParseResult<UpdateSpawnAreaInput> {
  const result = updateSpawnAreaInputSchema.safeParse(data ?? {});
  if (!result.success) {
    return {
      ok: false,
      message: firstIssueMessage(
        result.error,
        'Expected { areaId, and at least one of shape/name/active/safeAreaConfirmed }.',
      ),
    };
  }
  return { ok: true, input: result.data };
}

export function parseDeleteSpawnAreaInput(data: unknown): ParseResult<DeleteSpawnAreaInput> {
  const result = deleteSpawnAreaInputSchema.safeParse(data ?? {});
  if (!result.success) {
    return { ok: false, message: firstIssueMessage(result.error, 'Expected { areaId, reason? }.') };
  }
  return { ok: true, input: result.data };
}

export function parseListSpawnAreasInput(data: unknown): ParseResult<ListSpawnAreasInput> {
  const result = listSpawnAreasInputSchema.safeParse(data ?? undefined);
  if (!result.success) {
    return {
      ok: false,
      message: firstIssueMessage(result.error, 'Expected { activeOnly?, limit? }.'),
    };
  }
  return { ok: true, input: result.data };
}
