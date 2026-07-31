/**
 * KCC Crown UI design tokens — TypeScript reference.
 *
 * These values mirror the CSS custom properties defined in globals.css.
 * Use the CSS variables in stylesheets; use this file for type-safe
 * references in TypeScript logic (e.g. inline styles, canvas rendering).
 *
 * Every colour pairing in the theme maps below was measured with a WCAG 2.1
 * relative-luminance calculator. The ratios quoted in the comments are the
 * measured values, not estimates.
 */

export const colors = {
  gold: '#eab54b',
  goldDeep: '#8a6410',
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
  xs: '3px',
  sm: '4px',
  md: '8px',
  lg: '12px',
  xl: '16px',
  full: '9999px',
} as const;

/**
 * Transition durations. Held inside the 150-250 ms band so motion reads as
 * responsive rather than decorative. `globals.css` collapses all three to 1 ms
 * under `prefers-reduced-motion: reduce`.
 */
export const motion = {
  fast: '150ms',
  base: '200ms',
  slow: '250ms',
  easeOut: 'cubic-bezier(0.22, 0.75, 0.36, 1)',
  easeInOut: 'cubic-bezier(0.65, 0, 0.35, 1)',
} as const;

/**
 * Accent glow. Reserved for signalling *state* — focus, active navigation,
 * primary action, hover on an interactive surface. Never decorative.
 */
export const glow = {
  accentSoft: '0 0 0 3px rgba(234, 181, 75, 0.18)',
  accent: '0 0 0 3px rgba(234, 181, 75, 0.3)',
  accentStrong: '0 0 0 3px rgba(234, 181, 75, 0.35), 0 0 18px -2px rgba(234, 181, 75, 0.45)',
  error: '0 0 0 3px rgba(179, 50, 46, 0.28)',
} as const;

export const lightTheme = {
  pageBackground: '#f8f6ef',
  surfaceBackground: '#ffffff',
  surfaceHover: '#f0ebdc',
  insetBackground: '#f2eee3',
  sidebarBackground: '#3f3e3b',

  /** Light leans on the border, not the fill, to identify a field. */
  inputBackground: '#ffffff',
  inputBackgroundHover: '#fffdf7',
  inputBackgroundDisabled: '#ece8dc',
  inputBorder: '#807b6e', // 4.22:1 vs input fill, 3.90:1 vs page
  inputBorderHover: '#5f5b50',
  inputBorderDisabled: '#b9b3a3',
  inputText: '#2b2a27', // 14.35:1 vs input fill
  placeholderText: '#67635a', // 5.98:1 vs input fill
  disabledText: '#74705f', // 4.06:1 vs disabled fill

  textPrimary: '#3f3e3b',
  textSecondary: '#5f5c53', // 6.18:1 vs page
  borderDefault: '#c9c3b4',
  borderStrong: '#a29c8c',

  accent: '#eab54b',
  accentStrong: '#f2c468',
  accentText: '#8a6410', // 5.37:1 vs surface — gold itself is only 1.87:1
  accentContrast: '#040211', // 10.97:1 on gold

  statusSuccess: '#2a6b33', // 6.41:1 vs surface
  statusError: '#b3322e', // 6.10:1 vs surface
  statusWarning: '#8a5a00', // 5.88:1 vs surface
  statusInfo: '#215d9c', // 6.75:1 vs surface
} as const;

export const darkTheme = {
  pageBackground: '#08061a',
  surfaceBackground: '#1a1836', // 1.17:1 lift over page
  surfaceHover: '#252242',
  insetBackground: '#131126',
  sidebarBackground: '#0d0b22',

  inputBackground: '#2e2b48', // 1.48:1 vs page, 1.27:1 vs card surface
  inputBackgroundHover: '#373454',
  inputBackgroundDisabled: '#131126',
  inputBorder: '#85819c', // 3.61:1 vs fill, 4.58:1 vs card, 5.34:1 vs page
  inputBorderHover: '#a29eb8',
  inputBorderDisabled: '#3a3752',
  inputText: '#f8f6ef', // 12.49:1 vs input fill
  placeholderText: '#a8a4bd', // 5.60:1 vs input fill
  disabledText: '#6d6a85', // 3.57:1 vs disabled fill

  textPrimary: '#f8f6ef',
  textSecondary: '#b4b1ad', // 8.42:1 vs surface
  borderDefault: '#33304d',
  borderStrong: '#4c4870',

  accent: '#eab54b',
  accentStrong: '#f4cd80',
  accentText: '#eab54b', // 9.60:1 vs surface
  accentContrast: '#040211', // 10.97:1 on gold

  statusSuccess: '#5fd08a', // 8.86:1 vs surface
  statusError: '#ff7b74', // 6.79:1 vs surface
  statusWarning: '#f5b93a', // 9.68:1 vs surface
} as const;
