import en from './en.json';
import sv from './sv.json';

const dictionaries = {
  en,
  sv
} as const;

export type Locale = keyof typeof dictionaries;

type TranslationDictionary = Record<string, unknown>;

const getNestedValue = (dictionary: TranslationDictionary, path: string): string | undefined => {
  let current: unknown = dictionary;

  for (const key of path.split('.')) {
    if (!current || typeof current !== 'object' || !(key in (current as TranslationDictionary))) {
      return undefined;
    }

    current = (current as TranslationDictionary)[key];
  }

  return typeof current === 'string' ? current : undefined;
};

export const translate = (locale: Locale, key: string): string => {
  const currentDictionary = dictionaries[locale] ?? dictionaries.sv;
  const fallbackDictionary = dictionaries.en;

  return getNestedValue(currentDictionary, key) ?? getNestedValue(fallbackDictionary, key) ?? key;
};
