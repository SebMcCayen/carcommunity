export const palette = {
  crownGold: '#EAB54B',
  darkCharcoal: '#3F3E3B',
  inkBlack: '#040211',
  warmIvory: '#F8F6EF',
  softSand: '#F0EBDC',
  mutedGrey: '#6D6C6D',
  silverGrey: '#B4B1AD',
  successGreen: '#1E8E3E',
  errorRed: '#C5221F',
  warningAmber: '#E6A817',
} as const;

export const spacing = {
  0: 0,
  1: 4,
  2: 8,
  3: 12,
  4: 16,
  5: 20,
  6: 24,
  8: 32,
  10: 40,
  12: 48,
} as const;

export const radius = {
  sm: 8,
  md: 12,
  lg: 16,
  xl: 24,
  full: 9999,
} as const;

export const typography = {
  family: {
    primary: 'System',
  },
  size: {
    caption: 12,
    bodySm: 14,
    bodyMd: 16,
    titleMd: 18,
    headingLg: 24,
  },
  weight: {
    regular: '400' as const,
    medium: '500' as const,
    semibold: '600' as const,
  },
} as const;
