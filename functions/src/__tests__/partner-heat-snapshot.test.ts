import { describe, expect, it } from 'vitest';
import { Timestamp } from 'firebase-admin/firestore';
import { gridDisk, latLngToCell } from 'h3-js';
import {
  projectDriveHeatSnapshot,
  projectPartnerDriveHeat,
  PARTNER_HEAT_MAX_CELLS,
} from '../partnerInsights/drive-heat-snapshot';
import { DRIVE_HEAT_MAX_AGE_MS } from '../partnerInsights/drive-heat-policy';

const now = Date.UTC(2026, 10, 21, 12);
const cell = latLngToCell(57.49, 12.07, 10);
function snapshot() {
  return {
    consentVersion: 1,
    generatedAt: Timestamp.fromMillis(now - 1000),
    resolution: 10,
    windowDays: 90,
    cells: [{ h3Index: cell, contributorCount: 10, weight: 25 }],
  };
}
describe('shared consented traffic snapshot projection', () => {
  it.each([
    undefined,
    {},
    { ...snapshot(), consentVersion: 0 },
    { ...snapshot(), generatedAt: Timestamp.fromMillis(now + 1) },
    { ...snapshot(), generatedAt: Timestamp.fromMillis(now - DRIVE_HEAT_MAX_AGE_MS - 1) },
    { ...snapshot(), generatedAt: new Date(now) },
    { ...snapshot(), resolution: 9 },
    { ...snapshot(), windowDays: 7 },
    { ...snapshot(), cells: new Array(20001).fill(snapshot().cells[0]) },
  ])('suppresses invalid provenance/age/shape: %#', (data) => {
    expect(projectDriveHeatSnapshot(data, now).cells).toEqual([]);
    expect(projectPartnerDriveHeat(data, now).cells).toEqual([]);
  });
  it('uses the same contributor floor and strips every unprojected field', () => {
    const data = snapshot();
    const out = projectPartnerDriveHeat(
      {
        ...data,
        userIds: ['private'],
        cells: [
          { ...data.cells[0], userId: 'private', route: 'private' },
          { h3Index: latLngToCell(58, 12, 10), contributorCount: 9, weight: 900 },
          { h3Index: latLngToCell(59, 12, 10), contributorCount: 10, weight: NaN },
        ],
      },
      now,
    );
    expect(out.cells).toHaveLength(1);
    expect(Object.keys(out.cells[0]!).sort()).toEqual([
      'band',
      'boundary',
      'cellId',
      'latitude',
      'longitude',
    ]);
    expect(out.cells[0]!.boundary).toHaveLength(6);
    expect(out.cells[0]!.band).toBe(5);
    expect(JSON.stringify(out)).not.toContain('private');
    expect(out.expiresInSeconds).toBe(172799);
  });
  it('caps unique areas and supplies no client-controlled cohort or time slices', () => {
    const cells = gridDisk(cell, 20).map((h3Index) => ({
      h3Index,
      contributorCount: 10,
      weight: 10,
    }));
    const out = projectPartnerDriveHeat({ ...snapshot(), cells: [...cells, cells[0]] }, now);
    expect(out.cells).toHaveLength(PARTNER_HEAT_MAX_CELLS);
    expect(new Set(out.cells.map((c) => c.cellId)).size).toBe(PARTNER_HEAT_MAX_CELLS);
    expect(out.truncated).toBe(true);
    expect(out.windowDays).toBe(90);
  });
  it('ranks the full eligible snapshot deterministically into relative activity bands', () => {
    const rows = gridDisk(cell, 1)
      .slice(0, 5)
      .map((h3Index, i) => ({ h3Index, contributorCount: 10, weight: 10 + i * 10 }));
    const out = projectPartnerDriveHeat({ ...snapshot(), cells: rows.reverse() }, now);
    expect(out.cells.map((c) => c.band)).toEqual([5, 4, 3, 2, 1]);
    expect(out.cells.map((c) => c.cellId)).toEqual(rows.map((c) => c.h3Index));
    const wrongResolution = {
      h3Index: latLngToCell(57, 12, 9),
      contributorCount: 20,
      weight: 9999,
    };
    const invalid = [
      wrongResolution,
      { ...rows[0], contributorCount: 10.5 },
      { ...rows[0], weight: 9 },
    ];
    expect(
      projectPartnerDriveHeat({ ...snapshot(), cells: [...invalid, ...rows] }, now).cells,
    ).toEqual(out.cells);
  });
  it('suppresses invalid H3 cells and expires conservatively at the last second', () => {
    expect(
      projectPartnerDriveHeat(
        {
          ...snapshot(),
          cells: [{ h3Index: 'aaaaaaaaaaaaaaa', contributorCount: 20, weight: 20 }],
        },
        now,
      ).cells,
    ).toEqual([]);
    const data = {
      ...snapshot(),
      generatedAt: Timestamp.fromMillis(now - DRIVE_HEAT_MAX_AGE_MS + 999),
    };
    expect(projectPartnerDriveHeat(data, now).cells).toEqual([]);
  });
});
