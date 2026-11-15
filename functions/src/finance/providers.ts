import { GoogleAuth } from 'google-auth-library';
import {
  DAY_MS,
  billingSchema,
  usageSchema,
  type BillingEvidence,
  type UsageEvidence,
} from './evidence';
import { WORKLOADS } from './workloads';

/** Fixed Google endpoints + server configuration only; never callable input. */
export type CloudRequest = (url: string, body?: unknown) => Promise<unknown>;
const auth = new GoogleAuth({ scopes: ['https://www.googleapis.com/auth/cloud-platform'] });
export const cloudRequest: CloudRequest = async (url, body) => {
  const result = await auth.request({
    url,
    method: body ? 'POST' : 'GET',
    data: body,
    timeout: 20_000,
    retry: false,
  });
  return result.data;
};

function object(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== 'object') throw new Error('Invalid provider response');
  return value as Record<string, unknown>;
}

export const observedFunctionIds = WORKLOADS.filter((w) => !w.id.endsWith('activeWork')).map(
  (w) => w.id,
);
const serviceName = (id: string) => id.replace('.', '-').toLowerCase();

/** DELTA counters; aggregate revisions/status codes without losing failed calls. */
export async function collectUsage(
  projectId: string,
  now: Date,
  request = cloudRequest,
): Promise<UsageEvidence> {
  const endMs = Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate());
  const startMs = endMs - 30 * DAY_MS;
  const metrics = [
    'request_count',
    'container/network/sent_bytes_count',
    'container/billable_instance_time',
  ];
  const results = await Promise.all(
    metrics.map(async (metric) => {
      const totals = new Map<string, number>();
      const url = new URL(
        `https://monitoring.googleapis.com/v3/projects/${encodeURIComponent(projectId)}/timeSeries`,
      );
      const names = observedFunctionIds
        .map((id) => `resource.labels.service_name="${serviceName(id)}"`)
        .join(' OR ');
      url.searchParams.set(
        'filter',
        `metric.type="run.googleapis.com/${metric}" AND resource.type="cloud_run_revision" AND (${names})`,
      );
      url.searchParams.set('interval.startTime', new Date(startMs).toISOString());
      url.searchParams.set('interval.endTime', new Date(endMs).toISOString());
      url.searchParams.set('aggregation.alignmentPeriod', '86400s');
      url.searchParams.set('aggregation.perSeriesAligner', 'ALIGN_SUM');
      url.searchParams.set('aggregation.crossSeriesReducer', 'REDUCE_SUM');
      url.searchParams.set('aggregation.groupByFields', 'resource.labels.service_name');
      url.searchParams.set('view', 'FULL');
      url.searchParams.set('pageSize', '1000');
      for (let page = 0; page < 10; page++) {
        const response = object(await request(url.toString()));
        if (response.timeSeries !== undefined && !Array.isArray(response.timeSeries))
          throw new Error('Invalid time series');
        for (const value of (response.timeSeries ?? []) as unknown[]) {
          const series = object(value);
          const name = object(object(series.resource).labels).service_name;
          if (typeof name !== 'string' || !Array.isArray(series.points))
            throw new Error('Invalid time series');
          for (const pointValue of series.points) {
            const point = object(pointValue);
            const counter = object(point.value);
            const raw = counter.int64Value ?? counter.doubleValue;
            if (typeof raw !== 'number' && (typeof raw !== 'string' || !/^\d+$/.test(raw)))
              throw new Error('Invalid metric value');
            const number = Number(raw);
            if (!Number.isFinite(number) || number < 0) throw new Error('Invalid metric value');
            totals.set(name, (totals.get(name) ?? 0) + number);
          }
        }
        if (!response.nextPageToken) return totals;
        if (typeof response.nextPageToken !== 'string') throw new Error('Invalid page token');
        url.searchParams.set('pageToken', response.nextPageToken);
      }
      throw new Error('Monitoring page budget exceeded');
    }),
  );
  return usageSchema.parse({
    capturedAtMs: now.getTime(),
    startMs,
    endMs,
    functions: observedFunctionIds.map((id) => ({
      id,
      requests: results[0]!.get(serviceName(id)) ?? null,
      sentBytes: results[1]!.get(serviceName(id)) ?? null,
      billableSeconds: results[2]!.get(serviceName(id)) ?? null,
    })),
  });
}

export function billingQuery(table: string, projectId: string, now: Date, location: string) {
  // BigQuery identifiers cannot be bound parameters. Allow exactly one fully
  // qualified table; all values (including the project filter) ARE parameters.
  if (!/^[a-z][a-z0-9-]{4,61}[a-z0-9]\.[A-Za-z_][A-Za-z0-9_]*\.[A-Za-z_][A-Za-z0-9_]*$/.test(table))
    throw new Error('Invalid billing table');
  if (!/^[A-Za-z0-9-]{2,40}$/.test(location)) throw new Error('Invalid BigQuery location');
  const month = now.toISOString().slice(0, 7);
  const end = now.toISOString().slice(0, 10);
  return {
    query: `SELECT service.id, ANY_VALUE(service.description), currency,
      SUM(cost + IFNULL((SELECT SUM(c.amount) FROM UNNEST(credits) c), 0)) AS net_cost,
      CAST(MAX(export_time) AS STRING) AS latest_export
      FROM \`${table}\`
      WHERE _PARTITIONTIME >= TIMESTAMP(@start)
        AND project.id = @project
        AND usage_start_time >= TIMESTAMP(@start)
        AND usage_start_time < TIMESTAMP(@end)
        AND cost_type IN ('regular', 'adjustment')
      GROUP BY service.id, currency
      ORDER BY service.id LIMIT 201`,
    useLegacySql: false,
    useQueryCache: true,
    parameterMode: 'NAMED',
    queryParameters: [
      { name: 'project', parameterType: { type: 'STRING' }, parameterValue: { value: projectId } },
      {
        name: 'start',
        parameterType: { type: 'STRING' },
        parameterValue: { value: `${month}-01` },
      },
      { name: 'end', parameterType: { type: 'STRING' }, parameterValue: { value: end } },
    ],
    location,
    timeoutMs: 10_000,
    maxResults: 201,
    // Hard cap per daily query, including retries; not a new open-ended scan.
    maximumBytesBilled: String(100 * 1024 * 1024),
  };
}

export async function collectBilling(
  projectId: string,
  table: string,
  location: string,
  now: Date,
  request = cloudRequest,
): Promise<BillingEvidence> {
  const query = billingQuery(table, projectId, now, location);
  const billingProject = table.split('.')[0]!;
  let response = object(
    await request(
      `https://bigquery.googleapis.com/bigquery/v2/projects/${billingProject}/queries`,
      query,
    ),
  );
  for (let attempt = 0; response.jobComplete !== true && attempt < 6; attempt++) {
    const ref = object(response.jobReference);
    if (typeof ref.jobId !== 'string' || !/^[\w-]+$/.test(ref.jobId))
      throw new Error('Invalid billing job');
    const url = `https://bigquery.googleapis.com/bigquery/v2/projects/${billingProject}/queries/${ref.jobId}?location=${encodeURIComponent(location)}&maxResults=201&timeoutMs=10000`;
    response = object(await request(url));
  }
  if (
    response.jobComplete !== true ||
    response.errors ||
    response.pageToken ||
    !Array.isArray(response.rows)
  )
    throw new Error('Incomplete billing result');
  if (response.rows.length === 0 || response.rows.length > 200)
    throw new Error('Missing or oversized billing result');
  let latestExportAt = '';
  const services = response.rows.map((rowValue) => {
    const cells = object(rowValue).f;
    if (!Array.isArray(cells) || cells.length !== 5) throw new Error('Invalid billing row');
    const [id, label, currency, cost, exported] = cells.map((cell) => object(cell).v);
    if (
      currency !== 'SEK' ||
      typeof exported !== 'string' ||
      !Number.isFinite(Date.parse(exported))
    )
      throw new Error('Invalid billing currency or date');
    const iso = new Date(exported).toISOString();
    if (iso > latestExportAt) latestExportAt = iso;
    if (
      typeof cost !== 'number' &&
      (typeof cost !== 'string' || !/^-?\d+(\.\d+)?([eE][+-]?\d+)?$/.test(cost))
    )
      throw new Error('Invalid billing amount');
    return { id, label, netCost: Number(cost) };
  });
  const today = new Date(`${now.toISOString().slice(0, 10)}T00:00:00Z`);
  return billingSchema.parse({
    capturedAtMs: now.getTime(),
    month: now.toISOString().slice(0, 7),
    throughDate: new Date(today.getTime() - DAY_MS).toISOString().slice(0, 10),
    latestExportAt,
    currency: 'SEK',
    services,
  });
}
