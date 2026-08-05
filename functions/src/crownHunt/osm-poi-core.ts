/**
 * Kronjakt AUTO-SPAWN — OpenStreetMap SAFE-STOP placement, pure core.
 *
 * The marked-area engine (crown-area-core.ts / spawnScheduled.ts) originally
 * placed crowns at UNIFORM-RANDOM points inside a drawn shape. That is fine for
 * "somewhere in this town centre", but the whole safety story of the feature is
 * that a crown must sit at a place a member can safely STOP at — and a random
 * point in a polygon is as likely to land on a through-road or a front garden as
 * on a car park. This module snaps placement to REAL safe-stop points taken from
 * OpenStreetMap: parking lots, fuel (petrol) stations, and EV charging stations.
 *
 * The data comes from the free Overpass API (no key). It is licensed ODbL, so
 * "© OpenStreetMap contributors" ({@link OSM_ATTRIBUTION}) MUST be shown wherever
 * this data is surfaced (the admin area map's "N safe spots found", and any app
 * surface that names a crown's source) — the same obligation the Trafikverket
 * import carries with its "Källa: Trafikverket" credit.
 *
 * Pure module — no Firebase Admin SDK, no network, no clock. The query builder,
 * the response parser/normaliser, the in-shape filter, and the POI-anchored
 * sampler are all unit-tested in the colocated sibling ./osm-poi-core.test.ts.
 * The I/O half (the Overpass HTTP call, the Firestore cache, the ingestion
 * trigger and the weekly refresh) lives in ./poiIngestion.ts and injects a
 * fetcher so tests never hit the network.
 */

import { createHash } from 'node:crypto';
import {
  MIN_CROWN_SEPARATION_METERS,
  crownCellKey,
  isFarEnoughFromAll,
  type CrownPosition,
} from './crown-spawn-core';
import { isPointInShape, type CrownSpawnAreaShape, type LatLonBox } from './crown-area-core';

// ---------------------------------------------------------------------------
// Attribution + endpoint
// ---------------------------------------------------------------------------

/**
 * ODbL attribution string. OpenStreetMap requires this credit wherever its data
 * is shown; it is conventionally kept in English in every locale. Mirrored into
 * contracts/localization (crownHunt.safeSpotAttribution) for the app surfaces.
 */
export const OSM_ATTRIBUTION = '© OpenStreetMap contributors';

/**
 * The public Overpass endpoint. No API key. Overridable at deploy time via the
 * `OVERPASS_ENDPOINT` environment variable (poiIngestion.ts) so a self-hosted or
 * mirror instance can be pointed at without a code change; this is the default.
 */
export const OVERPASS_ENDPOINT_DEFAULT = 'https://overpass-api.de/api/interpreter';

/**
 * User-Agent sent with every Overpass request.
 *
 * REQUIRED, not cosmetic: overpass-api.de answers `406 Not Acceptable` (with an
 * HTML error page) to requests it cannot attribute to a client — bots, or the
 * empty/default UA that Node's `fetch` (undici) sends. That 406 is exactly why
 * the first cut of this importer failed. A descriptive UA with a contact URL is
 * also the courtesy the Overpass usage policy asks for.
 */
export const OSM_USER_AGENT =
  'KungsbackaCarCommunity/1.0 (Kronjakt safe-stop POI ingestion; +https://github.com/SebMcCayen/carcommunity)';

// ---------------------------------------------------------------------------
// POI categories
// ---------------------------------------------------------------------------

/** The safe-stop POI kinds the spawner anchors to. */
export const POI_CATEGORIES = ['parking', 'fuel', 'charging'] as const;
export type PoiCategory = (typeof POI_CATEGORIES)[number];

/** A normalised safe-stop POI: a coordinate and what kind of stop it is. */
export interface NormalizedPoi {
  lat: number;
  lon: number;
  category: PoiCategory;
}

/**
 * Classifies an OSM element's tags into a safe-stop {@link PoiCategory}, or null
 * when the element is not one we place at.
 *
 * `amenity=parking|fuel|charging_station` are the primary tags. Chargers are
 * ALSO tagged `man_made=charge_point` on a large minority of nodes, so that
 * variant is included — a charging bay is a charging bay however it is tagged.
 */
export function classifyPoiCategory(
  tags: Record<string, string> | undefined | null,
): PoiCategory | null {
  if (!tags) return null;
  switch (tags.amenity) {
    case 'parking':
      return 'parking';
    case 'fuel':
      return 'fuel';
    case 'charging_station':
      return 'charging';
    default:
      break;
  }
  if (tags.man_made === 'charge_point') return 'charging';
  return null;
}

// ---------------------------------------------------------------------------
// Overpass query builder
// ---------------------------------------------------------------------------

/** Default Overpass server-side query timeout, in seconds. Kept modest — Overpass
 * bills against a shared public pool, and one area's bounding box is a small ask. */
export const OVERPASS_QUERY_TIMEOUT_SECONDS = 25;

/**
 * Builds an Overpass QL query for every safe-stop POI inside a bounding box.
 *
 * Overpass bbox order is `(south, west, north, east)`. Both `node` and `way`
 * variants are requested for parking/fuel/charging (a big car park or petrol
 * forecourt is usually a `way`, a single charging bay usually a `node`); `out
 * center;` returns way/relation centroids in a `center: { lat, lon }` field so
 * the parser can treat everything as a point. `man_made=charge_point` picks up
 * the charger nodes tagged that way instead of `amenity=charging_station`.
 */
export function buildOverpassQuery(
  box: LatLonBox,
  timeoutSeconds: number = OVERPASS_QUERY_TIMEOUT_SECONDS,
): string {
  const bbox = `${box.south},${box.west},${box.north},${box.east}`;
  return [
    `[out:json][timeout:${Math.max(1, Math.floor(timeoutSeconds))}];`,
    '(',
    `node["amenity"="parking"](${bbox});`,
    `way["amenity"="parking"](${bbox});`,
    `node["amenity"="fuel"](${bbox});`,
    `way["amenity"="fuel"](${bbox});`,
    `node["amenity"="charging_station"](${bbox});`,
    `way["amenity"="charging_station"](${bbox});`,
    `node["man_made"="charge_point"](${bbox});`,
    ');',
    'out center;',
  ].join('');
}

/**
 * Builds the `fetch` RequestInit for an Overpass QL query — the DOCUMENTED POST
 * form, and the shape a unit test can assert without any network.
 *
 * POST `application/x-www-form-urlencoded` with the query URL-encoded under the
 * `data` key (the Overpass interpreter's documented POST body), an explicit
 * `Accept: application/json` (the query already asks for JSON via `[out:json]`),
 * and the required {@link OSM_USER_AGENT} — without which overpass-api.de returns
 * 406. Pure: no `fetch`, no clock; `signal` is attached by the caller.
 */
export function buildOverpassRequestInit(query: string): {
  method: 'POST';
  headers: Record<string, string>;
  body: string;
} {
  return {
    method: 'POST',
    headers: {
      'Content-Type': 'application/x-www-form-urlencoded',
      Accept: 'application/json',
      'User-Agent': OSM_USER_AGENT,
    },
    body: `data=${encodeURIComponent(query)}`,
  };
}

// ---------------------------------------------------------------------------
// Overpass response parsing / normalisation
// ---------------------------------------------------------------------------

/** The subset of an Overpass element the parser reads. */
export interface OverpassElement {
  type?: string;
  id?: number;
  lat?: number;
  lon?: number;
  /** Present on `way`/`relation` elements when the query used `out center;`. */
  center?: { lat?: number; lon?: number };
  tags?: Record<string, string>;
}

export interface OverpassResponse {
  elements?: OverpassElement[];
}

/**
 * Flattens an Overpass response into normalised POIs: one per element that has a
 * classifiable safe-stop tag and a valid WGS-84 coordinate (its own for a node,
 * its `center` for a way/relation). Elements with an unrecognised tag or an
 * out-of-range / missing coordinate are dropped. De-duplicated on
 * (lat, lon, category) so the same physical stop returned twice collapses to one.
 */
export function parseOverpassResponse(response: OverpassResponse): NormalizedPoi[] {
  const out: NormalizedPoi[] = [];
  const seen = new Set<string>();
  for (const el of response.elements ?? []) {
    const category = classifyPoiCategory(el.tags);
    if (!category) continue;
    const lat = typeof el.lat === 'number' ? el.lat : el.center?.lat;
    const lon = typeof el.lon === 'number' ? el.lon : el.center?.lon;
    if (typeof lat !== 'number' || typeof lon !== 'number') continue;
    if (!Number.isFinite(lat) || !Number.isFinite(lon)) continue;
    if (lat < -90 || lat > 90 || lon < -180 || lon > 180) continue;
    const key = `${lat},${lon},${category}`;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push({ lat, lon, category });
  }
  return out;
}

/**
 * Keeps only the POIs that fall inside the drawn SHAPE, not merely its bounding
 * box. Overpass is queried by bounding box (it has no polygon-in-a-node filter
 * we want to rely on), so a circle/polygon area returns corner POIs that are in
 * the box but outside the actual shape — those must not become spawn anchors.
 */
export function filterPoisInShape(
  pois: readonly NormalizedPoi[],
  shape: CrownSpawnAreaShape,
): NormalizedPoi[] {
  return pois.filter((p) => isPointInShape(p.lat, p.lon, shape));
}

// ---------------------------------------------------------------------------
// Deterministic POI document id
// ---------------------------------------------------------------------------

/**
 * Length-prefixed SHA-256 over a tuple → an injective, Firestore-safe (hex)
 * document ID. Same construction as crown-spawn-core.hashDocId, duplicated
 * rather than shared so the two callers may change their tuple shapes
 * independently. (The CodeQL `js/insufficient-password-hash` alert on this shape
 * is a known false positive — this hashes public coordinates into a doc id, it
 * is not a password hash.)
 */
function hashDocId(namespace: string, parts: readonly string[]): string {
  const hash = createHash('sha256');
  hash.update(`${namespace.length}:${namespace}`);
  for (const part of parts) {
    hash.update(`${part.length}:${part}`);
  }
  return hash.digest('hex');
}

/**
 * Deterministic `crownSpawnAreaPois/{areaId}/pois/{poiId}` document ID for a POI
 * at (lat, lon) inside `areaId`. Deterministic on the coordinate so a weekly
 * refresh overwrites the same document rather than duplicating a stop that has
 * not moved, and so a stop that HAS moved/disappeared upstream leaves an id the
 * refresh can diff against and delete.
 */
export function crownPoiDocId(areaId: string, lat: number, lon: number): string {
  return hashDocId('crownpoi', [areaId, String(lat), String(lon)]);
}

// ---------------------------------------------------------------------------
// POI-anchored placement
// ---------------------------------------------------------------------------

/** WGS-84 mean metres per degree of latitude; longitude scales by cos(lat). */
const METERS_PER_DEGREE_LAT = 111_320;

/**
 * Maximum jitter applied to a POI when placing a crown on it — ~5 m.
 *
 * A crown pinned to the exact OSM coordinate looks unnervingly precise and, on a
 * large car park mapped as a single centroid, always lands dead-centre. A few
 * metres of jitter reads as natural without ever moving the crown off the stop.
 * The jittered point is re-checked against the shape and cell; if the jitter
 * would push it out, the exact POI point (known-good) is used instead.
 */
export const POI_JITTER_METERS = 5;

/**
 * Displaces a base position by a uniform random offset within a disc of radius
 * `jitterMeters`. Longitude degrees are scaled by cos(lat) so the offset is a
 * true ground distance at any latitude.
 */
export function jitterPosition(
  base: CrownPosition,
  rng: () => number,
  jitterMeters: number = POI_JITTER_METERS,
): CrownPosition {
  if (!(jitterMeters > 0)) return base;
  const angle = rng() * 2 * Math.PI;
  // sqrt(u) for a uniform distribution over the disc rather than clustered at
  // the centre.
  const distance = jitterMeters * Math.sqrt(rng());
  const cosLat = Math.max(0.01, Math.cos((base.latitude * Math.PI) / 180));
  const dLat = (distance * Math.cos(angle)) / METERS_PER_DEGREE_LAT;
  const dLon = (distance * Math.sin(angle)) / (METERS_PER_DEGREE_LAT * cosLat);
  return { latitude: base.latitude + dLat, longitude: base.longitude + dLon };
}

// ---------------------------------------------------------------------------
// Ingestion trigger guard
// ---------------------------------------------------------------------------

/**
 * Whether an area write should trigger POI ingestion — PURE so the guard is
 * unit-testable, and colocated here (not in the I/O module) so it never pulls in
 * the Admin SDK.
 *
 * Ingests on exactly TWO events, and nothing else: the area was just ACTIVATED
 * (created active, or flipped active/safe on), or its SHAPE changed while active.
 * This is deliberately NARROW to solve two failures at once:
 *
 *  - RE-ENTRANCY: ingestion writes `poiCount`/`poisRefreshedAt` back onto the
 *    area doc, which re-fires the `onDocumentWritten` trigger. That write leaves
 *    `active`/`safeAreaConfirmed`/`shape` untouched, so it is neither an
 *    activation nor a reshape → this returns false → no loop.
 *  - RUNAWAY RETRIES: the scheduled spawn pass advances a round-robin cursor
 *    (`lastSpawnPassAt`/`nextCellOffset`) on the area doc every run. Those writes
 *    also leave active/safe/shape untouched → false. (An earlier version keyed
 *    off "never ingested", which re-fired a live Overpass call on EVERY cursor
 *    advance whenever ingestion had failed — the cause of a 54-minute CI run.)
 *
 * A create-time ingestion that fails (Overpass down) is NOT retried here; the
 * weekly {@link runAreaPoiRefresh} picks it up, and re-saving the area
 * (updateSpawnArea) re-activates/reshapes and so re-ingests.
 */
export function shouldIngestOnAreaWrite(
  before: Record<string, unknown> | undefined,
  after: Record<string, unknown> | undefined,
): boolean {
  if (!after) return false;
  if (after.active !== true || after.safeAreaConfirmed !== true) return false;
  if (!after.shape) return false;
  const wasActiveAndSafe = !!before && before.active === true && before.safeAreaConfirmed === true;
  const activatedNow = !wasActiveAndSafe;
  const shapeChanged = !!before && JSON.stringify(before.shape) !== JSON.stringify(after.shape);
  return activatedNow || shapeChanged;
}

/** Fisher–Yates shuffle of [0..n) driven by `rng`, so POI selection order is
 * random-looking but reproducible for a seeded generator. */
function shuffledIndices(n: number, rng: () => number): number[] {
  const arr = Array.from({ length: n }, (_, i) => i);
  for (let i = n - 1; i > 0; i -= 1) {
    const j = Math.floor(rng() * (i + 1));
    const tmp = arr[i]!;
    arr[i] = arr[j]!;
    arr[j] = tmp;
  }
  return arr;
}

/**
 * Picks a placement AT one of `pois` (optionally jittered ≤ {@link POI_JITTER_METERS})
 * that clears {@link MIN_CROWN_SEPARATION_METERS} from every position in
 * `occupied`, or null when no such POI exists.
 *
 * The POI-anchored replacement for `sampleCrownPosition`: instead of throwing
 * darts at the cell rectangle until one lands in-shape, it walks the candidate
 * POIs (which are already in-shape and, when `cellKey` is given, in-cell by
 * construction) in a shuffled order and returns the first that is far enough
 * from existing crowns.
 *
 *  - SPREADS across distinct POIs for free: the 150 m separation rule means a
 *    second crown can never be placed on a POI within 150 m of an already-placed
 *    one, so two crowns cannot stack on the same stop (they would be ≤ jitter
 *    metres apart) — the caller just pushes each returned position into
 *    `occupied` before the next call, exactly as the random sampler did.
 *  - Jitter never LOOSENS a guard: a jittered candidate that leaves the cell or
 *    the shape falls back to the exact POI point (guaranteed in-cell/in-shape),
 *    and the separation check is applied to whichever point is used.
 *
 * Returns null (leaving the cell short) rather than looping forever when every
 * POI is too close to an existing crown — the same "saturated is fine, try again
 * next pass" contract as the random sampler.
 */
export function samplePoiPlacement(
  pois: readonly NormalizedPoi[],
  occupied: readonly CrownPosition[],
  rng: () => number,
  options: {
    /** When set, a jittered candidate that leaves this cell falls back to the POI point. */
    cellKey?: string;
    /** In-shape predicate; a jittered candidate that fails it falls back to the POI point. */
    accept?: (position: CrownPosition) => boolean;
    minSeparationMeters?: number;
    jitterMeters?: number;
  } = {},
): CrownPosition | null {
  const minSeparation = options.minSeparationMeters ?? MIN_CROWN_SEPARATION_METERS;
  const jitterMeters = options.jitterMeters ?? POI_JITTER_METERS;

  // Passes BOTH guards: inside the target cell (when one is given) and inside the
  // shape (when an accept predicate is given). Applied to the jittered candidate
  // AND to the raw POI fallback — the caller's "already in-shape/in-cell" promise
  // holds at ingestion time, but a stale cache entry (overlapping ingestions, a
  // partial reconcile) could break it, and a placement must never violate the
  // shape/cell guard on the strength of that promise alone.
  const guardsPass = (p: CrownPosition): boolean => {
    if (
      options.cellKey !== undefined &&
      crownCellKey(p.latitude, p.longitude) !== options.cellKey
    ) {
      return false;
    }
    return !options.accept || options.accept(p);
  };

  for (const idx of shuffledIndices(pois.length, rng)) {
    const poi = pois[idx]!;
    const base: CrownPosition = { latitude: poi.lat, longitude: poi.lon };
    // jitterPosition is called for every POI so the rng stream stays deterministic
    // regardless of which candidate is chosen.
    const jittered = jitterPosition(base, rng, jitterMeters);
    // Prefer the jittered point; fall back to the exact POI point ONLY when it
    // ALSO clears the guards. If neither clears them (a stale/out-of-shape POI),
    // skip this POI entirely rather than place a crown outside the area/cell.
    let candidate: CrownPosition | null = null;
    if (guardsPass(jittered)) candidate = jittered;
    else if (guardsPass(base)) candidate = base;
    else continue;
    if (isFarEnoughFromAll(candidate, occupied, minSeparation)) return candidate;
  }
  return null;
}
