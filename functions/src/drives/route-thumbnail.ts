/**
 * Route thumbnails for the History list — a tiny, self-contained shape of the
 * drive that the list can draw with ZERO extra work.
 *
 * ## Why the geometry is stored on the ride document
 * The History list already reads `rides/{rideId}`. Anything the card needs that
 * is NOT on that document costs a per-card fetch: the full route lives in
 * member-gated Cloud Storage (`rideRoutes/{uid}/{rideId}/route.bin`) and can be
 * ~20 000 points, so drawing a shape per card from it would mean one Storage
 * download + decode per visible row in a scrolling list. Instead, drives.save
 * derives a ~64-point simplification ONCE, at save time, and stores it as an
 * encoded polyline (a few hundred bytes) on the document the list already has.
 * The card then costs zero reads, zero Storage fetches and zero map instances.
 *
 * ## This is coarse route geometry in Firestore — deliberately
 * The data model's rule that route GPS points live only in Cloud Storage still
 * governs the FULL track. This overview is a deliberate, bounded exception:
 * ~64 points at ~1 m encoding precision, enough to recognise the shape of a
 * drive and useless as a track. `rides/{rideId}` is owner-only read (plus
 * admin), exactly like every other field on it, so the thumbnail is visible to
 * nobody the distance and duration were not already visible to.
 *
 * ## Why Ramer–Douglas–Peucker and not every Nth point
 * Stride sampling keeps a fixed number of points but no particular ones: a
 * route whose only distinguishing feature is one sharp turn can lose the turn
 * entirely and render as a straight line. RDP keeps the points that carry the
 * SHAPE — it recursively retains whichever point is furthest from the chord
 * through a span and discards everything within a tolerance of it, so corners
 * survive and long straights collapse.
 *
 * Pure module — no Firebase Admin SDK imports.
 */

import type { TimedPoint } from './drive-calculations';

/**
 * Points kept in a stored thumbnail. Enough to keep a winding route legible at
 * the ~64x48 dp the card draws it at (well under a point per pixel of width),
 * small enough that the encoded string stays a few hundred bytes.
 */
export const THUMBNAIL_MAX_POINTS = 64;

/**
 * Coordinate precision of the stored polyline: 1e5, the classic Google
 * encoded-polyline scale (~1.1 m at the equator). Android's existing
 * `PolylineCodec` decodes with an explicit precision, so it reads this back
 * with no second decoder. 1e6 would double nothing useful here — the
 * simplification tolerance already discards far more than a metre.
 */
export const THUMBNAIL_POLYLINE_PRECISION = 1e5;

/** Metres per degree of latitude (mean spherical Earth) — the projection scale. */
const METRES_PER_DEGREE_LATITUDE = 111_320;

/**
 * Smallest simplification tolerance tried, in metres. Below this the encoded
 * string only grows: at the size the card draws, a metre is far under a pixel.
 */
const MIN_TOLERANCE_METRES = 5;

/**
 * Ceiling on the tolerance doubling. 5 m doubled 24 times is ~84 000 km — more
 * than twice the Earth's circumference, so the loop always terminates on the
 * point budget long before this. It exists only so a pathological input cannot
 * spin.
 */
const MAX_TOLERANCE_DOUBLINGS = 24;

/** A point projected to local metres (see {@link projectToMetres}). */
interface PlanarPoint {
  x: number;
  y: number;
}

/**
 * Equirectangular projection to local metres about the route's mean latitude.
 * Longitude degrees shrink by cos(latitude) — at Sweden's ~57-58°N a degree of
 * longitude is barely half a degree of latitude, so simplifying in raw degrees
 * would apply a tolerance twice as strict east-west as north-south and bias
 * which corners survive. Only distance RATIOS matter here, so a plane tangent
 * at the route's mean latitude is exact enough over any single drive.
 */
function projectToMetres(points: readonly TimedPoint[]): PlanarPoint[] {
  let latSum = 0;
  for (const point of points) latSum += point.latitude;
  const meanLatitude = latSum / points.length;
  const lonScale = Math.cos((meanLatitude * Math.PI) / 180);
  return points.map((point) => ({
    x: point.longitude * METRES_PER_DEGREE_LATITUDE * lonScale,
    y: point.latitude * METRES_PER_DEGREE_LATITUDE,
  }));
}

/** Perpendicular distance from `point` to the segment `start`→`end`. */
function perpendicularDistance(
  point: PlanarPoint,
  start: PlanarPoint,
  end: PlanarPoint,
): number {
  const dx = end.x - start.x;
  const dy = end.y - start.y;
  if (dx === 0 && dy === 0) {
    return Math.hypot(point.x - start.x, point.y - start.y);
  }
  // Cross product magnitude / segment length = perpendicular distance to the
  // INFINITE line. RDP's span endpoints are kept regardless, so the line form
  // is the right one: a point beyond an endpoint is still "far from the chord".
  return Math.abs(dy * point.x - dx * point.y + end.x * start.y - end.y * start.x) /
    Math.hypot(dx, dy);
}

/**
 * Indices kept by Ramer–Douglas–Peucker at `tolerance` metres, always including
 * the first and last point. Iterative (an explicit stack, not recursion) so a
 * 20 000-point track cannot blow the call stack.
 */
function rdpKeptIndices(points: readonly PlanarPoint[], tolerance: number): number[] {
  const keep = new Array<boolean>(points.length).fill(false);
  keep[0] = true;
  keep[points.length - 1] = true;

  const stack: Array<[number, number]> = [[0, points.length - 1]];
  while (stack.length > 0) {
    const [first, last] = stack.pop() as [number, number];
    let furthest = -1;
    let furthestDistance = tolerance;
    for (let i = first + 1; i < last; i += 1) {
      const distance = perpendicularDistance(
        points[i] as PlanarPoint,
        points[first] as PlanarPoint,
        points[last] as PlanarPoint,
      );
      if (distance > furthestDistance) {
        furthestDistance = distance;
        furthest = i;
      }
    }
    if (furthest !== -1) {
      keep[furthest] = true;
      stack.push([first, furthest], [furthest, last]);
    }
  }

  const kept: number[] = [];
  for (let i = 0; i < points.length; i += 1) {
    if (keep[i]) kept.push(i);
  }
  return kept;
}

/**
 * The route simplified to at most [maxPoints] points, preserving shape.
 *
 * RDP takes a tolerance, not a point count, so the tolerance is doubled from
 * [MIN_TOLERANCE_METRES] until the result fits the budget. Doubling (rather
 * than a binary search for the tightest fitting tolerance) is deliberate: it is
 * a handful of passes, it is deterministic, and the result is always a genuine
 * RDP simplification — never a truncation, which would drop the END of the
 * drive rather than its least interesting middle.
 *
 * Returns the input unchanged when it already fits.
 */
export function simplifyRoute(
  points: readonly TimedPoint[],
  maxPoints: number = THUMBNAIL_MAX_POINTS,
): TimedPoint[] {
  if (points.length <= maxPoints) return [...points];

  const planar = projectToMetres(points);
  let tolerance = MIN_TOLERANCE_METRES;
  let kept = rdpKeptIndices(planar, tolerance);
  for (let i = 0; i < MAX_TOLERANCE_DOUBLINGS && kept.length > maxPoints; i += 1) {
    tolerance *= 2;
    kept = rdpKeptIndices(planar, tolerance);
  }

  // A degenerate route (thousands of points at one spot) collapses to its two
  // endpoints and can never exceed the budget, so this is only a backstop.
  if (kept.length > maxPoints) {
    kept = kept.slice(0, maxPoints);
  }
  return kept.map((index) => points[index] as TimedPoint);
}

/** Encodes one signed, scaled coordinate delta in the polyline alphabet. */
function encodeSignedValue(value: number, out: string[]): void {
  let v = value < 0 ? ~(value << 1) : value << 1;
  while (v >= 0x20) {
    out.push(String.fromCharCode((0x20 | (v & 0x1f)) + 63));
    v >>= 5;
  }
  out.push(String.fromCharCode(v + 63));
}

/**
 * Encodes points as an encoded polyline at [THUMBNAIL_POLYLINE_PRECISION].
 *
 * This is the standard algorithm (zig-zag varint deltas, 5-bit chunks, +63 into
 * printable ASCII) — the same format Android's `PolylineCodec` already decodes
 * for Mapbox route geometry, so the app reads thumbnails back with the decoder
 * it has rather than a second, drifting one.
 */
export function encodePolyline(
  points: readonly { latitude: number; longitude: number }[],
  precision: number = THUMBNAIL_POLYLINE_PRECISION,
): string {
  const out: string[] = [];
  let previousLat = 0;
  let previousLon = 0;
  for (const point of points) {
    const lat = Math.round(point.latitude * precision);
    const lon = Math.round(point.longitude * precision);
    encodeSignedValue(lat - previousLat, out);
    encodeSignedValue(lon - previousLon, out);
    previousLat = lat;
    previousLon = lon;
  }
  return out.join('');
}

/**
 * The stored `routeThumbnail` for a submitted recording, or null when there is
 * no shape to draw.
 *
 * Null (not an empty string, and not a one-point polyline) for anything the
 * card cannot draw as a route: no points, a single fix, or a recording whose
 * fixes are all effectively the same place — a phone that sat parked with GPS
 * on. The card renders its placeholder for null, which is also what every drive
 * saved before this field existed gets, so the two paths are the same path.
 */
export function buildRouteThumbnail(points: readonly TimedPoint[] | undefined): string | null {
  if (!points || points.length < 2) return null;
  const simplified = simplifyRoute(points);
  if (simplified.length < 2) return null;
  // All points on one spot: after rounding to the encoding precision every
  // delta is zero, so the decoded shape is a dot. Say "no thumbnail" instead of
  // storing a polyline the card would have to special-case anyway.
  const first = simplified[0] as TimedPoint;
  const scale = THUMBNAIL_POLYLINE_PRECISION;
  const allSame = simplified.every(
    (point) =>
      Math.round(point.latitude * scale) === Math.round(first.latitude * scale) &&
      Math.round(point.longitude * scale) === Math.round(first.longitude * scale),
  );
  if (allSame) return null;
  return encodePolyline(simplified);
}
