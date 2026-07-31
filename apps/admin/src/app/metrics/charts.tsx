/**
 * Hand-rolled SVG charts for the metrics page.
 *
 * Deliberately dependency-free: a line chart and two bar charts are a few dozen
 * lines of SVG and keep the admin bundle small (no chart.js / recharts). They
 * read in the dark theme by drawing entirely with the app's CSS variables
 * (--accent, --text-secondary, --border-color, …) rather than hardcoded hues,
 * and each uses a `viewBox` with `width={'100%'}` so it scales to its column.
 *
 * These are presentational only — they receive already-aggregated points and
 * never fetch or hold PII.
 */
import type { SeriesPoint } from '@/features/metrics';

/** Formats a date id as a short, screenshot-friendly axis label (DD/MM). */
function shortDate(date: string): string {
  const [, m, d] = date.split('-');
  return d && m ? `${d}/${m}` : date;
}

function niceCeil(value: number): number {
  if (value <= 0) return 1;
  const magnitude = 10 ** Math.floor(Math.log10(value));
  const normalized = value / magnitude;
  const step = normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10;
  return step * magnitude;
}

interface LineChartProps {
  points: SeriesPoint[];
  /** Formats the value shown in the point tooltip. */
  format?: (value: number) => string;
}

/**
 * A single-series line chart with an area fill. Needs at least two points to
 * draw a line; callers render an empty state below that threshold.
 */
export function LineChart({ points, format = (v) => v.toLocaleString('sv-SE') }: LineChartProps) {
  const width = 640;
  const height = 220;
  const padL = 44;
  const padR = 12;
  const padT = 12;
  const padB = 28;
  const plotW = width - padL - padR;
  const plotH = height - padT - padB;

  const maxValue = niceCeil(Math.max(...points.map((p) => p.value), 1));
  const n = points.length;
  const x = (i: number) => padL + (n === 1 ? plotW / 2 : (i / (n - 1)) * plotW);
  const y = (v: number) => padT + plotH - (v / maxValue) * plotH;

  const linePath = points.map((p, i) => `${i === 0 ? 'M' : 'L'}${x(i)},${y(p.value)}`).join(' ');
  const areaPath =
    `M${x(0)},${padT + plotH} ` +
    points.map((p, i) => `L${x(i)},${y(p.value)}`).join(' ') +
    ` L${x(n - 1)},${padT + plotH} Z`;

  const gridLines = [0, 0.25, 0.5, 0.75, 1];

  return (
    <svg
      viewBox={`0 0 ${width} ${height}`}
      width="100%"
      role="img"
      preserveAspectRatio="xMidYMid meet"
      style={{ display: 'block' }}
    >
      {gridLines.map((g) => {
        const gy = padT + plotH - g * plotH;
        return (
          <g key={g}>
            <line
              x1={padL}
              x2={width - padR}
              y1={gy}
              y2={gy}
              stroke="var(--border-color)"
              strokeWidth={1}
            />
            <text
              x={padL - 8}
              y={gy + 4}
              textAnchor="end"
              fontSize="11"
              fill="var(--text-secondary)"
            >
              {Math.round(g * maxValue).toLocaleString('sv-SE')}
            </text>
          </g>
        );
      })}
      <path d={areaPath} fill="var(--accent)" opacity={0.12} />
      <path d={linePath} fill="none" stroke="var(--accent)" strokeWidth={2.5} />
      {points.map((p, i) => (
        <g key={p.date}>
          <circle cx={x(i)} cy={y(p.value)} r={3.5} fill="var(--accent)" />
          <title>{`${p.date}: ${format(p.value)}`}</title>
        </g>
      ))}
      {points.map((p, i) =>
        // Thin the labels when crowded: show first, last, and roughly six spread across.
        n <= 8 || i === 0 || i === n - 1 || i % Math.ceil(n / 6) === 0 ? (
          <text
            key={`lbl-${p.date}`}
            x={x(i)}
            y={height - 8}
            textAnchor="middle"
            fontSize="11"
            fill="var(--text-secondary)"
          >
            {shortDate(p.date)}
          </text>
        ) : null,
      )}
    </svg>
  );
}

interface BarChartProps {
  points: SeriesPoint[];
  format?: (value: number) => string;
}

/** A vertical bar chart (used for new-users-per-day). */
export function BarChart({ points, format = (v) => v.toLocaleString('sv-SE') }: BarChartProps) {
  const width = 640;
  const height = 220;
  const padL = 44;
  const padR = 12;
  const padT = 12;
  const padB = 28;
  const plotW = width - padL - padR;
  const plotH = height - padT - padB;

  const maxValue = niceCeil(Math.max(...points.map((p) => p.value), 1));
  const n = points.length;
  const slot = plotW / n;
  const barW = Math.min(28, slot * 0.7);

  return (
    <svg
      viewBox={`0 0 ${width} ${height}`}
      width="100%"
      role="img"
      preserveAspectRatio="xMidYMid meet"
      style={{ display: 'block' }}
    >
      {[0, 0.5, 1].map((g) => {
        const gy = padT + plotH - g * plotH;
        return (
          <g key={g}>
            <line
              x1={padL}
              x2={width - padR}
              y1={gy}
              y2={gy}
              stroke="var(--border-color)"
              strokeWidth={1}
            />
            <text
              x={padL - 8}
              y={gy + 4}
              textAnchor="end"
              fontSize="11"
              fill="var(--text-secondary)"
            >
              {Math.round(g * maxValue).toLocaleString('sv-SE')}
            </text>
          </g>
        );
      })}
      {points.map((p, i) => {
        const barH = (p.value / maxValue) * plotH;
        const cx = padL + slot * i + slot / 2;
        return (
          <g key={p.date}>
            <rect
              x={cx - barW / 2}
              y={padT + plotH - barH}
              width={barW}
              height={barH}
              rx={2}
              fill="var(--accent)"
            />
            <title>{`${p.date}: ${format(p.value)}`}</title>
          </g>
        );
      })}
      {points.map((p, i) =>
        n <= 8 || i === 0 || i === n - 1 || i % Math.ceil(n / 6) === 0 ? (
          <text
            key={`lbl-${p.date}`}
            x={padL + slot * i + slot / 2}
            y={height - 8}
            textAnchor="middle"
            fontSize="11"
            fill="var(--text-secondary)"
          >
            {shortDate(p.date)}
          </text>
        ) : null,
      )}
    </svg>
  );
}

interface HBarProps {
  label: string;
  count: number;
  max: number;
}

/** One horizontal bar in the brand-distribution list. */
export function HBar({ label, count, max }: HBarProps) {
  const pct = max > 0 ? (count / max) * 100 : 0;
  return (
    <div style={{ display: 'grid', gridTemplateColumns: '140px 1fr 48px', alignItems: 'center', gap: 'var(--space-3)' }}>
      <span
        style={{
          fontSize: 'var(--text-sm)',
          color: 'var(--text-secondary)',
          overflow: 'hidden',
          textOverflow: 'ellipsis',
          whiteSpace: 'nowrap',
        }}
        title={label}
      >
        {label}
      </span>
      <span
        style={{
          display: 'block',
          height: 16,
          borderRadius: 'var(--radius-sm)',
          background: 'var(--accent)',
          width: `${Math.max(pct, 2)}%`,
        }}
      />
      <span
        style={{
          fontSize: 'var(--text-sm)',
          fontWeight: 'var(--fw-semibold)',
          color: 'var(--text-primary)',
          textAlign: 'right',
        }}
      >
        {count.toLocaleString('sv-SE')}
      </span>
    </div>
  );
}
