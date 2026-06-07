/**
 * KCC Crown UI design tokens — TypeScript reference.
 *
 * These values mirror the CSS custom properties defined in globals.css.
 * Use the CSS variables in stylesheets; use this file for type-safe
 * references in TypeScript logic (e.g. inline styles, canvas rendering).
 */

export const colors = {
  gold: '#eab54b',
  charcoal: '#3f3e3b',
  ink: '#040211',
  ivory: '#f8f6ef',
  sand: '#f0ebdc',
  greyMid: '#6d6c6d',
  greyBorder: '#b4b1ad',
  error: '#d9534f',
  success: '#3a7d44',
  warning: '#f0a500',
} as const;

export const spacing = {
  1: '4px',
  2: '8px',
  3: '12px',
  4: '16px',
  5: '20px',
  6: '24px',
  8: '32px',
  10: '40px',
  12: '48px',
} as const;

export const radius = {
  sm: '4px',
  md: '8px',
  lg: '12px',
} as const;
