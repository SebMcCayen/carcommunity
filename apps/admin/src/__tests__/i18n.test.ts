import { describe, it, expect } from 'vitest';

import { translate, loadEnglishDictionary } from '@/i18n';
import sv from '@/i18n/sv.json';
import en from '@/i18n/en.json';

// A key that exists in both dictionaries with distinct values.
const SHARED_KEY = 'placeholder.plannedFunctionality';
// A key that exists only in the Swedish dictionary (strict subset invariant).
const SV_ONLY_KEY = 'partners.pageTitle';

describe('translate', () => {
  it('resolves Swedish lookups against the Swedish dictionary', () => {
    expect(translate('sv', SHARED_KEY)).toBe(
      (sv.placeholder as Record<string, string>).plannedFunctionality,
    );
  });

  it('returns Swedish for sv-only keys under the sv locale', () => {
    expect(translate('sv', SV_ONLY_KEY)).toBe((sv.partners as Record<string, string>).pageTitle);
  });

  it('never consults English for Swedish lookups, even after English loads', async () => {
    await loadEnglishDictionary();
    // The English dictionary is now loaded; a Swedish lookup must still return
    // the Swedish value, never the English one.
    expect(translate('sv', SHARED_KEY)).toBe(
      (sv.placeholder as Record<string, string>).plannedFunctionality,
    );
    expect(translate('sv', SHARED_KEY)).not.toBe(
      (en.placeholder as Record<string, string>).plannedFunctionality,
    );
  });

  it('resolves English lookups against English once loaded', async () => {
    await loadEnglishDictionary();
    expect(translate('en', SHARED_KEY)).toBe(
      (en.placeholder as Record<string, string>).plannedFunctionality,
    );
  });

  it('falls back to Swedish for keys missing from the English dictionary', async () => {
    await loadEnglishDictionary();
    // SV_ONLY_KEY has no English entry, so the en lookup falls back to Swedish.
    expect(translate('en', SV_ONLY_KEY)).toBe((sv.partners as Record<string, string>).pageTitle);
  });

  it('returns the key itself when it is absent from every dictionary', () => {
    expect(translate('sv', 'this.key.does.not.exist')).toBe('this.key.does.not.exist');
    expect(translate('en', 'this.key.does.not.exist')).toBe('this.key.does.not.exist');
  });
});

describe('crown-points abbreviation', () => {
  // The unit is "Kronpoäng" (KP) in Swedish, but "Crown Points" (CP) in English.
  // Guard that the two dictionaries never drift back to sharing "KP".
  it('abbreviates the points unit as KP in Swedish and CP in English', () => {
    expect((sv.points as { shortForm: string }).shortForm).toBe('KP');
    expect((en.points as { shortForm: string }).shortForm).toBe('CP');
  });
});
