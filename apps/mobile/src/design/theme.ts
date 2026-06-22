import { palette, radius, spacing, typography } from './tokens';

export type AppTheme = {
  colors: {
    pageBackground: string;
    surfaceBackground: string;
    subtleBackground: string;
    textPrimary: string;
    textSecondary: string;
    borderDefault: string;
    brandPrimary: string;
    statusSuccess: string;
    statusError: string;
    statusWarning: string;
  };
  spacing: typeof spacing;
  radius: typeof radius;
  typography: typeof typography;
};

export const lightTheme: AppTheme = {
  colors: {
    pageBackground: palette.warmIvory,
    surfaceBackground: '#FFFFFF',
    subtleBackground: palette.softSand,
    textPrimary: palette.inkBlack,
    textSecondary: palette.mutedGrey,
    borderDefault: palette.silverGrey,
    brandPrimary: palette.crownGold,
    statusSuccess: palette.successGreen,
    statusError: palette.errorRed,
    statusWarning: palette.warningAmber,
  },
  spacing,
  radius,
  typography,
};

export const darkTheme: AppTheme = {
  colors: {
    pageBackground: palette.inkBlack,
    surfaceBackground: palette.darkCharcoal,
    subtleBackground: '#2A2927',
    textPrimary: palette.warmIvory,
    textSecondary: palette.silverGrey,
    borderDefault: palette.mutedGrey,
    brandPrimary: palette.crownGold,
    statusSuccess: palette.successGreen,
    statusError: palette.errorRed,
    statusWarning: palette.warningAmber,
  },
  spacing,
  radius,
  typography,
};
