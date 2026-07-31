/**
 * Hand-rolled SVG charts for the finance board — same dependency-free approach
 * as the metrics page (no chart.js / recharts). Dark-theme aware: every colour
 * is a CSS variable, never a hardcoded hue. Presentational only.
 */

import { formatSek } from '@/features/finance';

interface ProjPoint {
  members: number;
  total: number;
  mapbox: number;
}

function niceCeil(value: number): number {
  if (value <= 0) return 1;
  const magnitude = 10 ** Math.floor(Math.log10(value));
  const normalized = value / magnitude;
  const step = normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 5 ? 5 : 10;
  return step * magnitude;
}

/**
 * Projection line chart — modelled total SEK/month (and the Mapbox share) as
 * the community grows. A PROJECTION from the current model, not historical spend.
 */
export function ProjectionChart({ points }: { points: ProjPoint[] }) {
  const width = 640;
  const height = 240;
  const padL = 56;
  const padR = 12;
  const padT = 12;
  const padB = 34;
  const plotW = width - padL - padR;
  const plotH = height - padT - padB;

  const maxValue = niceCeil(Math.max(...points.map((p) => p.total), 1));
  const n = points.length;
  const x = (i: number) => padL + (n === 1 ? plotW / 2 : (i / (n - 1)) * plotW);
  const y = (v: number) => padT + plotH - (v / maxValue) * plotH;

  const path = (key: 'total' | 'mapbox') =>
    points.map((p, i) => `${i === 0 ? 'M' : 'L'}${x(i)},${y(p[key])}`).join(' ');

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
            <line x1={padL} x2={width - padR} y1={gy} y2={gy} stroke="var(--border-color)" strokeWidth={1} />
            <text x={padL - 8} y={gy + 4} textAnchor="end" fontSize="11" fill="var(--text-secondary)">
              {formatSek(Math.round(g * maxValue))}
            </text>
          </g>
        );
      })}
      {/* Mapbox share (secondary line) */}
      <path d={path('mapbox')} fill="none" stroke="var(--text-secondary)" strokeWidth={1.5} strokeDasharray="4 3" />
      {/* Grand total (primary) */}
      <path d={path('total')} fill="none" stroke="var(--accent)" strokeWidth={2.5} />
      {points.map((p, i) => (
        <g key={p.members}>
          <circle cx={x(i)} cy={y(p.total)} r={3.5} fill="var(--accent)" />
          <title>{`${p.members.toLocaleString('sv-SE')} members: ${formatSek(p.total)}/mo (Mapbox ${formatSek(p.mapbox)})`}</title>
        </g>
      ))}
      {points.map((p, i) => (
        <text
          key={`lbl-${p.members}`}
          x={x(i)}
          y={height - 10}
          textAnchor="middle"
          fontSize="11"
          fill="var(--text-secondary)"
        >
          {p.members.toLocaleString('sv-SE')}
        </text>
      ))}
      <text x={padL} y={height - 22} fontSize="10" fill="var(--text-secondary)">
        members →
      </text>
    </svg>
  );
}

interface CompositionSlice {
  label: string;
  sek: number;
}

/** Horizontal bars showing where the monthly money goes (largest first). */
export function CompositionBars({ slices }: { slices: CompositionSlice[] }) {
  const max = Math.max(...slices.map((s) => s.sek), 1);
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-4)' }}>
      {slices.map((s) => {
        const pct = (s.sek / max) * 100;
        return (
          <div
            key={s.label}
            style={{ display: 'grid', gridTemplateColumns: '200px 1fr 96px', alignItems: 'center', gap: 'var(--space-3)' }}
          >
            <span
              style={{
                fontSize: 'var(--text-sm)',
                color: 'var(--text-secondary)',
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
              }}
              title={s.label}
            >
              {s.label}
            </span>
            <span
              style={{
                display: 'block',
                height: 16,
                borderRadius: 'var(--radius-sm)',
                background: 'var(--accent)',
                width: `${Math.max(pct, s.sek > 0 ? 2 : 0)}%`,
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
              {formatSek(s.sek)}
            </span>
          </div>
        );
      })}
    </div>
  );
}
