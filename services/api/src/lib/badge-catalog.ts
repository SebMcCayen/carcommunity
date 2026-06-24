/**
 * Static badge catalog.
 *
 * This is the single source of truth for badge definitions on the backend.
 * Use this catalog to look up display names, descriptions, and icon identifiers
 * when constructing badge responses.
 *
 * Design rules:
 *  - Wording must stay positive and non-competitive.
 *  - No speed, distance, driving-at-night, or racing-related badges.
 *  - No badge may encourage unsafe driving.
 *  - All user-facing text is in Swedish.
 *  - Icon identifiers are opaque references for the client UI — they do not
 *    expose backend logic.
 */

import type { BadgeKey, BadgeSummary } from '@carcommunity/shared/badges';

export interface BadgeDefinition extends BadgeSummary {
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

/**
 * Sorted catalog order for presenting badges to users.
 * Matches the product-defined display order.
 */
export const BADGE_CATALOG_ORDER: readonly BadgeKey[] = [
  'first_event',
  'five_events',
  'helpful_member',
  'early_member',
  'garage_created',
];
