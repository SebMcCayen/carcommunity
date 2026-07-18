/**
 * Badges domain — catalog and pure logic (Phase 9f).
 *
 * Ports services/api/src/lib/badge-catalog.ts (verbatim catalog — the single
 * source of truth for badge definitions) and the pure parts of
 * badge-service.ts to the Firestore model:
 *
 * - Awards live at `users/{uid}/badges/{badgeKey}` with the definition
 *   denormalized onto the document (backend-domain-mapping.md). The document
 *   ID equals the badge key, which makes awards naturally idempotent.
 * - Attendance for first_event / five_events uses the legacy conservative
 *   proxy: a `going` RSVP on an event that reaches `completed`. The count is
 *   maintained on the backend-only `badgeProgress/{uid}` counter document,
 *   incremented exactly once per completed event — `completed` is terminal, so
 *   whichever path ends the event (the events.complete callable or the
 *   events-autoClose sweep) makes that transition exactly once and credits
 *   exactly once.
 *
 * Design rules (legacy):
 *  - Wording stays positive and non-competitive; no speed/distance/racing
 *    badges; nothing may encourage unsafe driving.
 *  - All user-facing text is in Swedish.
 *
 * Pure module — no Firebase Admin SDK imports.
 */

import { z } from 'zod';

export const BADGE_KEYS = [
  'first_event',
  'five_events',
  'helpful_member',
  'early_member',
  'garage_created',
] as const;
export type BadgeKey = (typeof BADGE_KEYS)[number];

export interface BadgeDefinition {
  key: BadgeKey;
  name: string;
  description: string;
  iconIdentifier: string;
  /** True if the backend awards this badge automatically. False = admin-only. */
  isAutomatic: boolean;
}

export const BADGE_CATALOG: Readonly<Record<BadgeKey, BadgeDefinition>> = {
  first_event: {
    key: 'first_event',
    name: 'Första träffen',
    description: 'Deltog i sitt första community-event.',
    iconIdentifier: 'badge_first_event',
    isAutomatic: true,
  },
  five_events: {
    key: 'five_events',
    name: '5 träffar',
    description: 'Deltog i fem community-event.',
    iconIdentifier: 'badge_five_events',
    isAutomatic: true,
  },
  helpful_member: {
    key: 'helpful_member',
    name: 'Hjälpsam medlem',
    description: 'Har bidragit positivt och hjälpsamt i communityn.',
    iconIdentifier: 'badge_helpful_member',
    isAutomatic: false,
  },
  early_member: {
    key: 'early_member',
    name: 'Tidig medlem',
    description: 'Var med tidigt i communityn.',
    iconIdentifier: 'badge_early_member',
    isAutomatic: true,
  },
  garage_created: {
    key: 'garage_created',
    name: 'Garageprofil skapad',
    description: 'Skapade sin första fordonsprofil i Mitt garage.',
    iconIdentifier: 'badge_garage_created',
    isAutomatic: true,
  },
};

/** Product-defined display order. */
export const BADGE_CATALOG_ORDER: readonly BadgeKey[] = [
  'first_event',
  'five_events',
  'helpful_member',
  'early_member',
  'garage_created',
];

/** Attendance thresholds for the event badges (legacy evaluateEventBadges). */
export const FIRST_EVENT_THRESHOLD = 1;
export const FIVE_EVENTS_THRESHOLD = 5;

// ---------------------------------------------------------------------------
// Inputs
// ---------------------------------------------------------------------------

const awardHelpfulMemberInputSchema = z
  .object({
    targetUid: z.string().trim().min(1).max(128),
    reason: z.string().trim().min(1).max(2000),
  })
  .strict();

export type AwardHelpfulMemberInput = z.infer<typeof awardHelpfulMemberInputSchema>;

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

export function parseAwardHelpfulMemberInput(
  data: unknown,
): ParseResult<AwardHelpfulMemberInput> {
  const result = awardHelpfulMemberInputSchema.safeParse(data ?? {});
  if (!result.success) {
    return {
      ok: false,
      message: 'Expected { targetUid, reason } — reason is required for manual badge awards.',
    };
  }
  return { ok: true, input: result.data };
}

// ---------------------------------------------------------------------------
// Document builder
// ---------------------------------------------------------------------------

/**
 * users/{uid}/badges/{badgeKey} document. The catalog definition is
 * denormalized so clients render without a catalog lookup.
 */
export function buildBadgeDocument(
  badgeKey: BadgeKey,
  context: { source: 'automatic' | 'admin_manual'; awardedByUserId: string | null },
  serverTimestamp: () => unknown,
): Record<string, unknown> {
  const definition = BADGE_CATALOG[badgeKey];
  return {
    badgeKey,
    name: definition.name,
    description: definition.description,
    iconIdentifier: definition.iconIdentifier,
    source: context.source,
    awardedByUserId: context.awardedByUserId,
    awardedAt: serverTimestamp(),
  };
}

// ---------------------------------------------------------------------------
// early_member (legacy evaluateEarlyMember)
// ---------------------------------------------------------------------------

/**
 * Parses the EARLY_MEMBER_CUTOFF_DATE configuration value. Returns null when
 * unset or invalid — the legacy safe default: no cutoff configured means the
 * badge is never awarded.
 */
export function parseEarlyMemberCutoff(raw: string | undefined): Date | null {
  if (!raw || !raw.trim()) {
    return null;
  }
  const parsed = new Date(raw.trim());
  return Number.isNaN(parsed.getTime()) ? null : parsed;
}

/** Accounts created strictly before the cutoff qualify (legacy rule). */
export function qualifiesAsEarlyMember(createdAt: Date, cutoff: Date): boolean {
  return createdAt.getTime() < cutoff.getTime();
}

/** Which event badges an attendance count qualifies for. */
export function qualifiedEventBadges(attendanceCount: number): BadgeKey[] {
  const keys: BadgeKey[] = [];
  if (attendanceCount >= FIRST_EVENT_THRESHOLD) {
    keys.push('first_event');
  }
  if (attendanceCount >= FIVE_EVENTS_THRESHOLD) {
    keys.push('five_events');
  }
  return keys;
}

// ---------------------------------------------------------------------------
// Admin aggregate summary (Phase 18b)
// ---------------------------------------------------------------------------

/** Recent-window for the admin badge summary (legacy RECENT_BADGE_WINDOW_DAYS). */
export const RECENT_BADGE_WINDOW_DAYS = 30;

/** One row of the admin badge summary — aggregate counts only, no user data. */
export interface AdminBadgeAggregateItem {
  key: BadgeKey;
  name: string;
  totalCount: number;
  recentCount: number;
}

/**
 * Pure aggregation of badge awards into per-key totals + recent (last 30 days)
 * counts, ordered by the catalog. Mirrors the legacy SQL groupBy
 * (badge-service.getAdminBadgeSummary) over the Firestore collectionGroup.
 * Unknown/legacy keys are ignored (only catalog keys are reported).
 */
export function buildAdminBadgeSummary(
  awards: ReadonlyArray<{ badgeKey: string; awardedAtMillis: number | null }>,
  nowMillis: number,
): AdminBadgeAggregateItem[] {
  const cutoff = nowMillis - RECENT_BADGE_WINDOW_DAYS * 24 * 60 * 60 * 1000;
  const catalogKeys = new Set<string>(BADGE_CATALOG_ORDER);
  const total = new Map<string, number>();
  const recent = new Map<string, number>();
  for (const award of awards) {
    // Ignore unknown/legacy keys (matches the docstring; keeps the maps small).
    if (!catalogKeys.has(award.badgeKey)) continue;
    total.set(award.badgeKey, (total.get(award.badgeKey) ?? 0) + 1);
    if (award.awardedAtMillis != null && award.awardedAtMillis >= cutoff) {
      recent.set(award.badgeKey, (recent.get(award.badgeKey) ?? 0) + 1);
    }
  }
  return BADGE_CATALOG_ORDER.map((key) => ({
    key,
    name: BADGE_CATALOG[key].name,
    totalCount: total.get(key) ?? 0,
    recentCount: recent.get(key) ?? 0,
  }));
}
