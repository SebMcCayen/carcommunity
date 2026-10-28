import { beforeEach, describe, expect, it } from 'vitest';
import { pendingOperation } from '@/lib/pendingOperation';

const scope = 'admin:member:points';
beforeEach(() => sessionStorage.clear());

describe('durable uncertain member decisions', () => {
  it('retains A when another reviewed payload B is submitted before retrying A', () => {
    const a = pendingOperation(scope, { amount: 10 });
    const b = pendingOperation(scope, { amount: 20 });
    expect(b.key).not.toBe(a.key);
    expect(pendingOperation(scope, { amount: 10 }).key).toBe(a.key);
  });
  it('clears only the confirmed intent and permits a new intentional repetition', () => {
    const a = pendingOperation(scope, { amount: 10 });
    const b = pendingOperation(scope, { amount: 20 });
    b.clear();
    expect(pendingOperation(scope, { amount: 10 }).key).toBe(a.key);
    a.clear();
    const repeat = pendingOperation(scope, { amount: 10 });
    expect(repeat.key).not.toBe(a.key);
    a.clear();
    expect(pendingOperation(scope, { amount: 10 }).key).toBe(repeat.key);
  });
  it('preserves a pending identity from the previous storage format', () => {
    sessionStorage.setItem(
      `kcc-admin-pending:${scope}`,
      JSON.stringify({
        fingerprint: JSON.stringify({ amount: 10 }),
        key: 'existing-receipt',
      }),
    );
    pendingOperation(scope, { amount: 20 }).clear();
    expect(pendingOperation(scope, { amount: 10 }).key).toBe('existing-receipt');
  });
  it('keeps actor, member and action scopes independent', () => {
    const a = pendingOperation(scope, { amount: 10 });
    for (const other of ['other:member:points', 'admin:other:points', 'admin:member:warning']) {
      expect(pendingOperation(other, { amount: 10 }).key).not.toBe(a.key);
    }
  });
  it('fails before a new decision when outstanding receipt storage is corrupt', () => {
    for (const value of [
      '{broken',
      'null',
      '{"fingerprint":"A"}',
      '[{"key":"","fingerprint":"A"}]',
    ]) {
      sessionStorage.setItem(`kcc-admin-pending:${scope}`, value);
      expect(() => pendingOperation(scope, { amount: 10 })).toThrow();
      expect(sessionStorage.getItem(`kcc-admin-pending:${scope}`)).toBe(value);
    }
  });
});
