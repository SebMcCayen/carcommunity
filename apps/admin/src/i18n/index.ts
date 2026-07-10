import sv from './sv.json';

type Locale = 'en' | 'sv';
type TranslationDictionary = Record<string, unknown>;

/**
 * Swedish is the default (and currently only) active locale, so it is bundled
 * eagerly — every translated string is available on first paint with no
 * language flash. English is only ever consulted for explicit `en` lookups, so
 * it is code-split and loaded on demand (see loadEnglishDictionary below).
 */
const dictionaries: { sv: TranslationDictionary; en?: TranslationDictionary } = { sv };

let englishDictionaryLoad: Promise<TranslationDictionary> | undefined;

/**
 * Lazily loads the English dictionary into the fallback slot.
 *
 * Triggered automatically the first time `translate` is asked for the `en`
 * locale; exported so callers (and tests) can await deterministic loading.
 * Until it resolves, lookups fall back to Swedish, whose key set is a strict
 * superset of the English one.
 */
export const loadEnglishDictionary = (): Promise<TranslationDictionary> => {
  englishDictionaryLoad ??= import('./en.json').then(
    (module) => {
      const dictionary = module.default as TranslationDictionary;
      dictionaries.en = dictionary;
      return dictionary;
    },
    (error: unknown) => {
      // Drop the memoized promise so a later call can retry after a
      // transient failure (e.g. a network blip on the chunk fetch).
      englishDictionaryLoad = undefined;
      throw error;
    },
  );

  return englishDictionaryLoad;
};

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
  // Swedish is the default and only eager dictionary: `sv` lookups resolve
  // against it alone and never consult English. English is consulted only for
  // `en` lookups, with `sv` as the fallback for any key `en` is missing (its
  // key set is a strict subset of `sv`).
  if (locale === 'en') {
    if (!dictionaries.en) {
      // Kick off the fetch; this render falls back to Swedish below. Handle
      // rejection here so a failed chunk fetch cannot surface as an unhandled
      // promise rejection — the Swedish fallback already covers this render,
      // and loadEnglishDictionary clears its memo so a later lookup retries.
      loadEnglishDictionary().catch((error: unknown) => {
        console.warn('[i18n] Failed to load English dictionary; using Swedish fallback.', error);
      });
    }

    const english = dictionaries.en ? getNestedValue(dictionaries.en, key) : undefined;

    return english ?? getNestedValue(dictionaries.sv, key) ?? key;
  }

  return getNestedValue(dictionaries.sv, key) ?? key;
};
