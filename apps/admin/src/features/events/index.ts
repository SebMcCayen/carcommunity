/**
 * Events feature module for the admin portal.
 *
 * Provides shared types, helpers, and hooks for the events admin area.
 * Pages in src/app/events/ import from here rather than inlining domain logic.
 *
 * TODO: Implement loadAdminEvents() API call once the admin auth context is wired in.
 * TODO: Add event creation and editing once audit logging is available.
 *   Dangerous actions (cancel, delete) require a mandatory reason and audit log entry.
 */

import type { AdminEventSummary, EventStatus } from '@carcommunity/shared/events';

export type { AdminEventSummary, EventStatus };

/**
 * Returns a human-readable Swedish label for an event status.
 */
export function formatEventStatus(status: EventStatus): string {
  switch (status) {
    case 'draft':
      return 'Utkast';
    case 'published':
      return 'Publicerat';
    case 'cancelled':
      return 'Inställt';
    case 'completed':
      return 'Genomfört';
  }
}

/**
 * Returns true if the event is upcoming (starts in the future and is published).
 */
export function isUpcomingEvent(event: Pick<AdminEventSummary, 'status' | 'startsAt'>): boolean {
  return event.status === 'published' && new Date(event.startsAt) > new Date();
}
