import { describe, expect, it, vi } from 'vitest';
import { billingQuery, collectBilling, collectUsage } from './providers';

const now = new Date('2026-11-15T12:00:00Z');
const table = 'demo-test.billing.gcp_billing_export_v1_example';
const cells = (values: unknown[]) => ({ f: values.map((v) => ({ v })) });

describe('billing provider', () => {
  it('bounds scanned bytes and dates, filters the shared project, includes credits, excludes tax', () => {
    const body = billingQuery(table, 'demo-test', now, 'EU');
    expect(body.maximumBytesBilled).toBe('104857600');
    expect(body.query).toContain('UNNEST(credits)');
    expect(body.query).toContain("cost_type IN ('regular', 'adjustment')");
    expect(body.queryParameters.map((p) => p.parameterValue.value)).toEqual([
      'demo-test',
      '2026-11-01',
      '2026-11-15',
    ]);
    expect(() =>
      billingQuery('demo-test.billing.table` UNION SELECT', 'demo-test', now, 'EU'),
    ).toThrow();
  });
  it('polls an existing job without starting another billable query', async () => {
    const request = vi
      .fn()
      .mockResolvedValueOnce({ jobComplete: false, jobReference: { jobId: 'job-123' } })
      .mockResolvedValueOnce({
        jobComplete: true,
        rows: [cells(['id', 'Build', 'SEK', '-2.5', now.toISOString()])],
      });
    const result = await collectBilling('demo-test', table, 'EU', now, request);
    expect(result.services[0]!.netCost).toBe(-2.5);
    expect(result.throughDate).toBe('2026-11-14');
    expect(request.mock.calls.filter((call) => call[1] !== undefined)).toHaveLength(1);
  });
  it('refuses incomplete, empty, malformed and non-SEK results rather than returning zero or partial totals', async () => {
    for (const response of [
      { jobComplete: true, rows: [] },
      { jobComplete: true, rows: [cells(['id', 'Run', 'USD', '5', now.toISOString()])] },
      {
        jobComplete: true,
        pageToken: 'next',
        rows: [cells(['id', 'Run', 'SEK', '5', now.toISOString()])],
      },
      { jobComplete: true, rows: [cells(['id', 'Run', 'SEK', null, now.toISOString()])] },
    ])
      await expect(
        collectBilling('demo-test', table, 'EU', now, vi.fn().mockResolvedValue(response)),
      ).rejects.toThrow();
  });
});

describe('Monitoring provider', () => {
  it('sums all request outcomes and revisions, handles pagination and preserves absent counters as null', async () => {
    let counterPage = 0;
    const request = vi.fn(async (raw: string) => {
      const url = new URL(raw);
      expect(url.searchParams.get('aggregation.crossSeriesReducer')).toBe('REDUCE_SUM');
      expect(url.searchParams.get('filter')).not.toContain('response_code');
      if (!url.searchParams.get('filter')!.includes('request_count')) return {};
      counterPage++;
      return {
        timeSeries: [
          {
            resource: { labels: { service_name: 'roadcameras-photo' } },
            points: [{ value: { int64Value: counterPage === 1 ? '10' : '20' } }],
          },
        ],
        ...(counterPage === 1 ? { nextPageToken: 'next' } : {}),
      };
    });
    const result = await collectUsage('demo-test', now, request);
    expect(result.functions[0]).toEqual({
      id: 'roadCameras.photo',
      requests: 30,
      sentBytes: null,
      billableSeconds: null,
    });
    expect(result.functions[1]!.requests).toBeNull();
    expect(result.endMs - result.startMs).toBe(30 * 86_400_000);
  });
  it('rejects malformed data and exhausted page budgets instead of importing partial measurements', async () => {
    await expect(
      collectUsage('demo-test', now, vi.fn().mockResolvedValue({ timeSeries: 'bad' })),
    ).rejects.toThrow();
    await expect(
      collectUsage('demo-test', now, vi.fn().mockResolvedValue({ nextPageToken: 'forever' })),
    ).rejects.toThrow('budget');
  });
});
