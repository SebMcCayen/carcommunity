import { createContext, ReactNode, useContext, useMemo, useState } from 'react';
import { useColorScheme } from 'react-native';

import { AppTheme, darkTheme, lightTheme } from '../design/theme';

export type ThemeMode = 'system' | 'light' | 'dark';

type ThemeContextValue = {
  mode: ThemeMode;
  setMode: (mode: ThemeMode) => void;
  resolvedMode: Exclude<ThemeMode, 'system'>;
  theme: AppTheme;
};

const ThemeContext = createContext<ThemeContextValue | undefined>(undefined);

type AppThemeProviderProps = {
  children: ReactNode;
};

export const AppThemeProvider = ({ children }: AppThemeProviderProps) => {
  const systemColorScheme = useColorScheme();
  const [mode, setMode] = useState<ThemeMode>('system');

  const resolvedMode: Exclude<ThemeMode, 'system'> =
    mode === 'system' ? (systemColorScheme === 'dark' ? 'dark' : 'light') : mode;

  const theme = resolvedMode === 'dark' ? darkTheme : lightTheme;

  const value = useMemo(
    () => ({
      mode,
      setMode,
      resolvedMode,
      theme
    }),
    [mode, resolvedMode, theme]
  );

  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>;
};

export const useAppTheme = (): ThemeContextValue => {
  const context = useContext(ThemeContext);

  if (!context) {
    throw new Error('useAppTheme must be used inside AppThemeProvider');
  }

  return context;
};
