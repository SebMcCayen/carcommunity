/**
 * Trafikverket (Swedish Transport Administration) open-data mapping — pure
 * request builder + response parser for the roadwork/incident importer.
 *
 * The Trafikverket open API (https://api.trafikinfo.trafikverket.se/v2/data.json)
 * takes an XML request with a `<LOGIN authenticationkey="…"/>` element and a
 * `<QUERY objecttype="Situation">`, returning JSON. A Situation carries one or
 * more Deviations, each with a `MessageType` and a WGS84 point geometry
 * ("POINT (lng lat)"). A FREE authentication key is required — see the sync
 * runner, which no-ops without it, and the PR notes for enabling it.
 *
 * Pure module — no Firebase Admin SDK, no network. The runner injects the HTTP
 * call so this stays unit-testable with mocked responses (tests never hit the
 * live API).
 */

import type { IncidentType } from './incidents-core';

export const TRAFIKVERKET_ENDPOINT = 'https://api.trafikinfo.trafikverket.se/v2/data.json';

/** Max situations pulled per sync (bounded write volume). */
export const TRAFIKVERKET_QUERY_LIMIT = 500;

/**
 * Builds the XML request body. Queries active road `Situation`s and includes
 * only the deviation fields the importer maps, keeping the payload small.
 */
export function buildTrafikverketRequestBody(
  authenticationKey: string,
  limit: number = TRAFIKVERKET_QUERY_LIMIT,
): string {
  return [
    '<REQUEST>',
    `<LOGIN authenticationkey="${escapeXml(authenticationKey)}" />`,
    // namespace + schemaversion 1.6 are REQUIRED for Situation objects — without
    // them the API errors and imports nothing. The FILTER element is mandatory
    // but MUST stay empty: any Deviation.ManagedCause filter wrongly drops most
    // real incidents (lane/road closures, speed restrictions carry ManagedCause=None).
    `<QUERY objecttype="Situation" namespace="road.trafficinfo" schemaversion="1.6" limit="${limit}">`,
    '<FILTER></FILTER>',
    '<INCLUDE>Deviation.Id</INCLUDE>',
    // MessageCodeValue is the stable English machine code used for classification.
    '<INCLUDE>Deviation.MessageCodeValue</INCLUDE>',
    '<INCLUDE>Deviation.MessageType</INCLUDE>',
    '<INCLUDE>Deviation.Header</INCLUDE>',
    '<INCLUDE>Deviation.Message</INCLUDE>',
    '<INCLUDE>Deviation.IconId</INCLUDE>',
    '<INCLUDE>Deviation.Geometry.WGS84</INCLUDE>',
    '</QUERY>',
    '</REQUEST>',
  ].join('');
}

function escapeXml(value: string): string {
  return value
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

// ---------------------------------------------------------------------------
// Response shape (only the fields the importer reads)
// ---------------------------------------------------------------------------

export interface TrafikverketDeviation {
  Id?: string;
  /** Stable English machine code (schemaversion 1.6) — the classification key. */
  MessageCodeValue?: string;
  MessageType?: string;
  Header?: string;
  Message?: string;
  IconId?: string;
  Geometry?: { WGS84?: string };
}

export interface TrafikverketSituation {
  Id?: string;
  Deviation?: TrafikverketDeviation[];
}

export interface TrafikverketResponse {
  RESPONSE?: { RESULT?: Array<{ Situation?: TrafikverketSituation[] }> };
}

// ---------------------------------------------------------------------------
// Parsing
// ---------------------------------------------------------------------------

/** Parses a WGS84 `POINT (lng lat)` string into a coordinate, or null. */
export function parseWgs84Point(value: string | undefined): { longitude: number; latitude: number } | null {
  if (!value) return null;
  const match = /POINT\s*\(\s*(-?\d+(?:\.\d+)?)\s+(-?\d+(?:\.\d+)?)\s*\)/i.exec(value);
  if (!match) return null;
  const longitude = Number(match[1]);
  const latitude = Number(match[2]);
  if (!Number.isFinite(longitude) || !Number.isFinite(latitude)) return null;
  if (latitude < -90 || latitude > 90 || longitude < -180 || longitude > 180) return null;
  return { longitude, latitude };
}

/**
 * Classifies a lowercased `MessageCodeValue` (the stable English machine code)
 * into an app IncidentType, `null` when it should be SKIPPED (not imported), or
 * `undefined` when the code is present but unrecognized (caller decides the
 * fallback + logs it for review). Substring rules so new upstream codes still
 * classify robustly.
 *
 * Product scope: roadworks + real incidents only. Ferry schedules and standing
 * regulatory restrictions (speed/overtaking/weight/dimension limits) are SKIPPED.
 */
function classifyMessageCode(code: string): IncidentType | null | undefined {
  // SKIP: ferries + standing regulatory restrictions. Checked first so a
  // restriction never leaks through into another bucket.
  if (
    code.includes('ferry') ||
    code.includes('restriction') ||
    code.includes('overtaking') ||
    code.includes('speedlimit') ||
    code.includes('speedrestriction') ||
    code.includes('weight') ||
    code.includes('height') ||
    code.includes('width') ||
    code.includes('length') ||
    code.includes('axle') ||
    code.includes('dimension')
  ) {
    return null;
  }
  if (
    code.includes('roadwork') ||
    code.includes('resurfac') ||
    code.includes('blasting') ||
    code.includes('construction') ||
    code.includes('paving') ||
    code.includes('maintenance')
  ) {
    return 'roadwork';
  }
  if (code.includes('accident') || code.includes('collision') || code.includes('crash')) {
    return 'accident';
  }
  if (code.includes('closed') || code.includes('closure')) return 'road_closed';
  if (code.includes('police') || code.includes('checkpoint')) return 'police';
  if (
    code.includes('frost') ||
    code.includes('surface') ||
    code.includes('obstruction') ||
    code.includes('obstacle') ||
    code.includes('diversion') ||
    code.includes('damage') ||
    code.includes('flood') ||
    code.includes('ice') ||
    code.includes('slippery') ||
    code.includes('snow') ||
    code.includes('animal') ||
    code.includes('object') ||
    code.includes('debris') ||
    code.includes('fallen') ||
    code.includes('hinder') ||
    code.includes('hazard')
  ) {
    return 'hazard';
  }
  return undefined; // present but unrecognized
}

/** Swedish `MessageType` fallback used only when `MessageCodeValue` is absent. */
function classifyMessageType(messageType: string): IncidentType {
  const t = messageType.toLowerCase();
  if (t.includes('olycka')) return 'accident';
  if (t.includes('vägarbete') || t.includes('vagarbete')) return 'roadwork';
  if (t.includes('avstäng') || t.includes('avstang') || t.includes('stängd') || t.includes('stangd')) {
    return 'road_closed';
  }
  return 'hazard';
}

/**
 * Maps a deviation to an app IncidentType, or `null` to SKIP it (ferry /
 * regulatory restriction). Prefers the stable English `MessageCodeValue`; when
 * that is missing/empty, falls back to the Swedish `MessageType`; an unknown
 * (present-but-unrecognized) code surfaces as a generic `hazard` so genuinely
 * new incident types still reach the map.
 */
export function classifyIncidentType(
  messageCodeValue: string | undefined,
  messageType?: string,
): IncidentType | null {
  const code = (messageCodeValue ?? '').toLowerCase();
  if (code) {
    const classified = classifyMessageCode(code);
    return classified === undefined ? 'hazard' : classified;
  }
  return classifyMessageType(messageType ?? '');
}

/** A normalized importable incident derived from a Trafikverket deviation. */
export interface ImportedIncident {
  /** Stable upstream id (used to build a deterministic Firestore doc id). */
  sourceId: string;
  type: IncidentType;
  latitude: number;
  longitude: number;
  note: string | null;
}

const NOTE_MAX = 200;

/**
 * Flattens a Trafikverket response into importable incidents: one per deviation
 * that has a stable id, a parseable WGS84 point, and a classifiable type.
 * Deviations classified as SKIP (ferries / regulatory restrictions), or missing
 * an id or a valid point, are dropped. Notes are trimmed/bounded.
 *
 * `onUnknownCode` is invoked with the raw `MessageCodeValue` of any deviation
 * whose code is present but unrecognized (still imported as a generic hazard);
 * the runner wires it to `logger.info` so new codes can be reviewed.
 */
export function parseTrafikverketResponse(
  response: TrafikverketResponse,
  onUnknownCode?: (code: string) => void,
): ImportedIncident[] {
  const results: ImportedIncident[] = [];
  const seen = new Set<string>();
  const situations = response.RESPONSE?.RESULT?.flatMap((r) => r.Situation ?? []) ?? [];
  for (const situation of situations) {
    for (const deviation of situation.Deviation ?? []) {
      const type = classifyIncidentType(deviation.MessageCodeValue, deviation.MessageType);
      if (type === null) continue; // SKIP: ferry / regulatory restriction
      const point = parseWgs84Point(deviation.Geometry?.WGS84);
      if (!point) continue;
      const sourceId = deviation.Id ?? situation.Id;
      if (!sourceId || seen.has(sourceId)) continue;
      seen.add(sourceId);
      const rawCode = deviation.MessageCodeValue;
      if (onUnknownCode && rawCode && classifyMessageCode(rawCode.toLowerCase()) === undefined) {
        onUnknownCode(rawCode);
      }
      const message = deviation.Message?.trim();
      results.push({
        sourceId,
        type,
        latitude: point.latitude,
        longitude: point.longitude,
        note: message && message.length > 0 ? message.slice(0, NOTE_MAX) : null,
      });
    }
  }
  return results;
}

/**
 * Deterministic Firestore doc id for an imported incident, so re-syncs
 * overwrite the same document rather than duplicating. Firestore-safe: the
 * upstream id is sanitized to [A-Za-z0-9_-] and prefixed with `tv_`.
 */
export function importedIncidentDocId(sourceId: string): string {
  const safe = sourceId.replace(/[^A-Za-z0-9_-]/g, '_').slice(0, 180);
  return `tv_${safe}`;
}
