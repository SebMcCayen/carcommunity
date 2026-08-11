/**
 * Finance recurring costs — unit tests for the pure parse/validation and
 * monthly/annual normalisation. No emulator, no I/O.
 */

import { describe, expect, it } from 'vitest';
import {
  RECURRING_COST_AMOUNT_MAX,
  RECURRING_COST_DESCRIPTION_MAX_LENGTH,
  RECURRING_COST_LABEL_MAX_LENGTH,
  annualAmountInSourceCurrency,
  monthlyAmountInSourceCurrency,
  parseAddRecurringCostInput,
  parseDeleteRecurringCostInput,
  parseUpdateRecurringCostInput,
  roundAmount,
} from './recurringCosts-core';

const validAdd = {
  label: 'Claude (Anthropic)',
  description: 'Max plan — the assistant that builds the app',
  amount: 200,
  currency: 'USD',
  period: 'monthly',
};

describe('parseAddRecurringCostInput', () => {
  it('accepts a valid entry and trims/bounds text', () => {
    const result = parseAddRecurringCostInput({ ...validAdd, label: '  Claude  ' });
    expect(result.ok).toBe(true);
    if (result.ok) {
      expect(result.input.label).toBe('Claude');
      expect(result.input.amount).toBe(200);
      expect(result.input.currency).toBe('USD');
      expect(result.input.period).toBe('monthly');
    }
  });

  it('accepts an empty description', () => {
    const result = parseAddRecurringCostInput({ ...validAdd, description: '' });
    expect(result.ok).toBe(true);
  });

  it('rejects an empty / whitespace-only label', () => {
    expect(parseAddRecurringCostInput({ ...validAdd, label: '' }).ok).toBe(false);
    expect(parseAddRecurringCostInput({ ...validAdd, label: '   ' }).ok).toBe(false);
  });

  it('rejects a label over the length cap', () => {
    expect(
      parseAddRecurringCostInput({ ...validAdd, label: 'x'.repeat(RECURRING_COST_LABEL_MAX_LENGTH + 1) })
        .ok,
    ).toBe(false);
  });

  it('rejects a description over the length cap', () => {
    expect(
      parseAddRecurringCostInput({
        ...validAdd,
        description: 'x'.repeat(RECURRING_COST_DESCRIPTION_MAX_LENGTH + 1),
      }).ok,
    ).toBe(false);
  });

  it('rejects a non-positive, non-finite, or over-cap amount', () => {
    expect(parseAddRecurringCostInput({ ...validAdd, amount: 0 }).ok).toBe(false);
    expect(parseAddRecurringCostInput({ ...validAdd, amount: -5 }).ok).toBe(false);
    expect(parseAddRecurringCostInput({ ...validAdd, amount: Number.NaN }).ok).toBe(false);
    expect(parseAddRecurringCostInput({ ...validAdd, amount: Number.POSITIVE_INFINITY }).ok).toBe(
      false,
    );
    expect(parseAddRecurringCostInput({ ...validAdd, amount: RECURRING_COST_AMOUNT_MAX + 1 }).ok).toBe(
      false,
    );
  });

  it('rejects an unknown currency or period', () => {
    expect(parseAddRecurringCostInput({ ...validAdd, currency: 'EUR' }).ok).toBe(false);
    expect(parseAddRecurringCostInput({ ...validAdd, period: 'weekly' }).ok).toBe(false);
  });

  it('rejects unknown / extra fields (strict)', () => {
    expect(parseAddRecurringCostInput({ ...validAdd, id: 'x' }).ok).toBe(false);
    expect(parseAddRecurringCostInput({ ...validAdd, extra: 1 }).ok).toBe(false);
  });

  it('rejects missing input entirely', () => {
    expect(parseAddRecurringCostInput(undefined).ok).toBe(false);
    expect(parseAddRecurringCostInput({}).ok).toBe(false);
  });

  it('rounds an over-precise amount to 2 decimals', () => {
    const result = parseAddRecurringCostInput({ ...validAdd, amount: 12.005 });
    expect(result.ok).toBe(true);
    if (result.ok) expect(result.input.amount).toBe(12.01);
  });
});

describe('parseUpdateRecurringCostInput', () => {
  it('requires a non-empty id alongside the fields', () => {
    expect(parseUpdateRecurringCostInput({ ...validAdd }).ok).toBe(false); // no id
    const result = parseUpdateRecurringCostInput({ ...validAdd, id: 'abc123' });
    expect(result.ok).toBe(true);
    if (result.ok) expect(result.input.id).toBe('abc123');
  });

  it('rejects an empty id', () => {
    expect(parseUpdateRecurringCostInput({ ...validAdd, id: '' }).ok).toBe(false);
  });
});

describe('parseDeleteRecurringCostInput', () => {
  it('accepts { id } and rejects anything else', () => {
    expect(parseDeleteRecurringCostInput({ id: 'abc' }).ok).toBe(true);
    expect(parseDeleteRecurringCostInput({ id: '' }).ok).toBe(false);
    expect(parseDeleteRecurringCostInput({}).ok).toBe(false);
    expect(parseDeleteRecurringCostInput({ id: 'abc', extra: 1 }).ok).toBe(false);
  });
});

describe('normalisation helpers', () => {
  it('monthly passes through; yearly is /12', () => {
    expect(
      monthlyAmountInSourceCurrency({ label: '', description: '', amount: 120, currency: 'SEK', period: 'monthly' }),
    ).toBe(120);
    expect(
      monthlyAmountInSourceCurrency({ label: '', description: '', amount: 1200, currency: 'SEK', period: 'yearly' }),
    ).toBe(100);
  });

  it('annual is ×12 for monthly and itself for yearly', () => {
    expect(
      annualAmountInSourceCurrency({ label: '', description: '', amount: 120, currency: 'SEK', period: 'monthly' }),
    ).toBe(1440);
    expect(
      annualAmountInSourceCurrency({ label: '', description: '', amount: 1200, currency: 'SEK', period: 'yearly' }),
    ).toBe(1200);
  });

  it('roundAmount rounds to öre/cents', () => {
    expect(roundAmount(12.005)).toBe(12.01);
    expect(roundAmount(99.999)).toBe(100);
    expect(roundAmount(50)).toBe(50);
  });
});
