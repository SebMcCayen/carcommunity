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
    `<QUERY objecttype="Situation" schemaversion="1.5" limit="${limit}">`,
    '<FILTER>',
    '<EQ name="Deviation.ManagedCause" value="true" />',
    '</FILTER>',
    '<INCLUDE>Deviation.Id</INCLUDE>',
    '<INCLUDE>Deviation.MessageType</INCLUDE>',
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
  MessageType?: string;
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
 * Maps a Trafikverket `MessageType` (Swedish) to an incident type. Defaults to
 * `roadwork` — the API's managed situations are predominantly planned works.
 */
export function classifyIncidentType(messageType: string | undefined): IncidentType {
  const t = (messageType ?? '').toLowerCase();
  if (t.includes('olycka')) return 'accident';
  if (t.includes('vägarbete') || t.includes('vagarbete')) return 'roadwork';
  if (t.includes('avstäng') || t.includes('avstang') || t.includes('stängd') || t.includes('stangd')) {
    return 'road_closed';
  }
  if (t.includes('hinder') || t.includes('föremål') || t.includes('foremal') || t.includes('halka') || t.includes('viltstängsel')) {
    return 'hazard';
  }
  if (t.includes('restriktion')) return 'road_closed';
  return 'roadwork';
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
 * that has a stable id and a parseable WGS84 point. Deviations without either
 * are skipped. Notes are trimmed/bounded.
 */
export function parseTrafikverketResponse(response: TrafikverketResponse): ImportedIncident[] {
  const results: ImportedIncident[] = [];
  const seen = new Set<string>();
  const situations = response.RESPONSE?.RESULT?.flatMap((r) => r.Situation ?? []) ?? [];
  for (const situation of situations) {
    for (const deviation of situation.Deviation ?? []) {
      const point = parseWgs84Point(deviation.Geometry?.WGS84);
      if (!point) continue;
      const sourceId = deviation.Id ?? situation.Id;
      if (!sourceId || seen.has(sourceId)) continue;
      seen.add(sourceId);
      const message = deviation.Message?.trim();
      results.push({
        sourceId,
        type: classifyIncidentType(deviation.MessageType),
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
