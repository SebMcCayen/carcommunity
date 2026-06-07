import { createContext, ReactNode, useContext } from 'react';

import { Locale, translate } from '../i18n';

type I18nContextValue = {
  locale: Locale;
  t: (key: string) => string;
};

const I18nContext = createContext<I18nContextValue | undefined>(undefined);

type I18nProviderProps = {
  children: ReactNode;
  locale?: Locale;
};

export const I18nProvider = ({ children, locale = 'sv' }: I18nProviderProps) => {
  const value: I18nContextValue = {
    locale,
    t: (key) => translate(locale, key)
  };

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
};

export const useI18n = (): I18nContextValue => {
  const context = useContext(I18nContext);

  if (!context) {
    throw new Error('useI18n must be used inside I18nProvider');
  }

  return context;
};
