import en from './en.json';
import sv from './sv.json';

const dictionaries = {
  en,
  sv
} as const;

export type Locale = keyof typeof dictionaries;

type TranslationNode = string | Record<string, TranslationNode>;

const getNestedValue = (obj: TranslationNode, path: string): string | undefined => {
  if (typeof obj === 'string') {
    return obj;
  }

  return path.split('.').reduce<TranslationNode | undefined>((current, key) => {
    if (!current || typeof current === 'string') {
      return undefined;
    }

    return current[key];
  }, obj) as string | undefined;
};

export const translate = (locale: Locale, key: string): string => {
  const currentDictionary = dictionaries[locale] ?? dictionaries.sv;
  const fallbackDictionary = dictionaries.en;

  return getNestedValue(currentDictionary, key) ?? getNestedValue(fallbackDictionary, key) ?? key;
};
