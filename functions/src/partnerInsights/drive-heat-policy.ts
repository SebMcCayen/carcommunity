/** Shared publication/read limits. No Firebase initialization or I/O. */
/** Rolling window of future, completed, consented saved drives. */
export const DRIVE_HEAT_WINDOW_DAYS = 90;
/** Single aggregate document budget; producer keeps the highest-activity cells. */
export const DRIVE_HEAT_MAX_CELLS = 20_000;
/** A missed daily rebuild may retain a valid aggregate for at most 48 hours. */
export const DRIVE_HEAT_MAX_AGE_MS = 48 * 60 * 60 * 1000;
