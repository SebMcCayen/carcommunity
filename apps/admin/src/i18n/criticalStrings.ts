/**
 * Critical UI strings rendered from the eager entry graph — before any
 * lazily-loaded route chunk (and with it the full i18n dictionaries in
 * `./index`) has been fetched.
 *
 * These deliberately live in a standalone module that imports NEITHER
 * `./index` NOR the JSON dictionaries. The components that use them (e.g.
 * RouteFallback, the Suspense fallback for lazy routes) sit in the initial
 * bundle, so going through the `translate` helper here would pull the
 * dictionaries back into the entry chunk and defeat route-level code splitting
 * — the very thing the fallback exists to support.
 *
 * Keep each value in sync with its mirrored i18n key (noted inline).
 */
export const criticalStrings = {
  /** Route chunk loading. Mirrors the `*.loading` keys ("Laddar...") in sv.json. */
  routeLoading: 'Laddar...',
} as const;
