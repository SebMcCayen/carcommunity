/**
 * Generated inline-SVG perk logos for the Kronjakt admin dashboard.
 *
 * Perks ship with only a string `iconKey` (perk_spike_strip / perk_shield /
 * perk_boost) and NO image assets, so the admin stats cards draw their own
 * lightweight gold-theme glyphs here — one per perk, keyed off the perk id.
 * These are deliberately simple placeholders (a spike row, a shield outline, a
 * "2×" boost badge) that read at a glance and are trivially swappable for real
 * artwork later. Pure, self-contained SVG: no external assets, CSP-safe.
 *
 * Gold theme: a light fill (#ecb44c) with a darker stroke/detail (#c9922e), the
 * same coin-gold the Kronjakt crown economy uses.
 */

import type { PerkId } from '@/features/crown-hunt';

const GOLD_LIGHT = '#ecb44c';
const GOLD_DARK = '#c9922e';

export interface PerkLogoProps {
  perkId: PerkId;
  /** Rendered pixel size (square). Defaults to 40. */
  size?: number;
  /** Accessible label; when omitted the glyph is decorative (aria-hidden). */
  title?: string;
}

/** Spikmatta — a row of upward spikes on a baseline (the trap perk). */
function SpikeStripGlyph(): React.ReactElement {
  return (
    <>
      <path
        d="M3 30 L9 14 L15 30 L21 14 L27 30 L33 14 L39 30 Z"
        fill={GOLD_LIGHT}
        stroke={GOLD_DARK}
        strokeWidth={2}
        strokeLinejoin="round"
      />
      <rect x="3" y="30" width="36" height="4" rx="1.5" fill={GOLD_DARK} />
    </>
  );
}

/** Sköld — a classic shield outline with a centre seam (the shield perk). */
function ShieldGlyph(): React.ReactElement {
  return (
    <>
      <path
        d="M21 4 L35 9 V20 C35 29 29 34 21 38 C13 34 7 29 7 20 V9 Z"
        fill={GOLD_LIGHT}
        stroke={GOLD_DARK}
        strokeWidth={2}
        strokeLinejoin="round"
      />
      <path d="M21 8 V34" stroke={GOLD_DARK} strokeWidth={1.5} opacity={0.6} />
    </>
  );
}

/** Dubbla Poäng — a "2×" badge with a double up-arrow (the boost perk). */
function BoostGlyph(): React.ReactElement {
  return (
    <>
      <circle cx="21" cy="21" r="17" fill={GOLD_LIGHT} stroke={GOLD_DARK} strokeWidth={2} />
      <text
        x="21"
        y="27"
        textAnchor="middle"
        fontSize="16"
        fontWeight="700"
        fontFamily="system-ui, sans-serif"
        fill={GOLD_DARK}
      >
        2×
      </text>
      <path
        d="M31 11 L34 8 L37 11 M34 8 V15"
        fill="none"
        stroke={GOLD_DARK}
        strokeWidth={2}
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </>
  );
}

const GLYPHS: Record<PerkId, () => React.ReactElement> = {
  spike_strip: SpikeStripGlyph,
  shield: ShieldGlyph,
  boost: BoostGlyph,
};

/** A generated gold-theme perk logo, drawn inline as SVG (no image asset). */
export function PerkLogo({ perkId, size = 40, title }: PerkLogoProps): React.ReactElement {
  const Glyph = GLYPHS[perkId];
  const decorative = title === undefined;
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 42 42"
      role={decorative ? undefined : 'img'}
      aria-hidden={decorative ? true : undefined}
      aria-label={decorative ? undefined : title}
      focusable="false"
    >
      {!decorative && <title>{title}</title>}
      <Glyph />
    </svg>
  );
}

export default PerkLogo;
