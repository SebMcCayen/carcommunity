import { describe, expect, it } from 'vitest';
import {
  applyReadThreshold,
  coerceResultStatus,
  parseAdminInsightsSummaryInput,
} from './insights-admin-core';

describe('parseAdminInsightsSummaryInput', () => {
  it('accepts companyId with optional period + date', () => {
    expect(parseAdminInsightsSummaryInput({ companyId: 'co1' }).ok).toBe(true);
    expect(
      parseAdminInsightsSummaryInput({ companyId: 'co1', periodType: 'week', date: '2026-07-01T00:00:00.000Z' }).ok,
    ).toBe(true);
  });

  it('rejects a missing companyId, bad period, or extra fields', () => {
    expect(parseAdminInsightsSummaryInput({}).ok).toBe(false);
    expect(parseAdminInsightsSummaryInput({ companyId: 'co1', periodType: 'yearly' }).ok).toBe(false);
    expect(parseAdminInsightsSummaryInput({ companyId: 'co1', date: 'not-a-date' }).ok).toBe(false);
    expect(parseAdminInsightsSummaryInput({ companyId: 'co1', foo: 1 }).ok).toBe(false);
  });

  it('rejects a companyId with path-breaking or unsafe characters', () => {
    expect(parseAdminInsightsSummaryInput({ companyId: 'co/../other' }).ok).toBe(false);
    expect(parseAdminInsightsSummaryInput({ companyId: 'co 1' }).ok).toBe(false);
    expect(parseAdminInsightsSummaryInput({ companyId: 'co.1' }).ok).toBe(false);
    // The safe doc-id charset is still accepted.
    expect(parseAdminInsightsSummaryInput({ companyId: 'Company_ID-123' }).ok).toBe(true);
  });
});

describe('coerceResultStatus', () => {
  it('passes through the known statuses', () => {
    expect(coerceResultStatus('available')).toBe('available');
    expect(coerceResultStatus('insufficient_data')).toBe('insufficient_data');
    expect(coerceResultStatus('no_data')).toBe('no_data');
  });

  it('fails closed to no_data for malformed/missing values', () => {
    expect(coerceResultStatus(undefined)).toBe('no_data');
    expect(coerceResultStatus(null)).toBe('no_data');
    expect(coerceResultStatus('AVAILABLE')).toBe('no_data');
    expect(coerceResultStatus(42)).toBe('no_data');
  });
});

describe('applyReadThreshold', () => {
  it('maps a missing aggregate to no_data', () => {
    expect(applyReadThreshold('map_view', null, 10)).toEqual({
      interactionType: 'map_view',
      totalCount: 0,
      uniqueContributorCount: null,
      status: 'no_data',
    });
  });

  it('re-zeroes an available anonymous_pass_by below the raised threshold', () => {
    const result = applyReadThreshold(
      'anonymous_pass_by',
      { totalCount: 15, uniqueContributorCount: 8, resultStatus: 'available' },
      10,
    );
    expect(result).toEqual({
      interactionType: 'anonymous_pass_by',
      totalCount: 0,
      uniqueContributorCount: null,
      status: 'insufficient_data',
    });
  });

  it('keeps an available anonymous_pass_by at or above the threshold', () => {
    const result = applyReadThreshold(
      'anonymous_pass_by',
      { totalCount: 40, uniqueContributorCount: 12, resultStatus: 'available' },
      10,
    );
    expect(result).toMatchObject({ totalCount: 40, uniqueContributorCount: 12, status: 'available' });
  });

  it('never re-zeroes non-pass-by types or already-safe statuses', () => {
    expect(
      applyReadThreshold('map_view', { totalCount: 3, uniqueContributorCount: 2, resultStatus: 'available' }, 10),
    ).toMatchObject({ totalCount: 3, status: 'available' });
    expect(
      applyReadThreshold('anonymous_pass_by', { totalCount: 0, uniqueContributorCount: null, resultStatus: 'insufficient_data' }, 10),
    ).toMatchObject({ status: 'insufficient_data', totalCount: 0 });
  });
});
