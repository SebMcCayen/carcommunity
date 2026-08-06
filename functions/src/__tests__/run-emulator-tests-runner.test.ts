/**
 * Unit tests for the OUTER decision logic of the emulator-suite runner
 * (functions/scripts/run-emulator-tests.mjs).
 *
 * The whole point of that runner is that the CI job's success comes from
 * vitest's REAL recorded result, not from emulator-shutdown noise. These tests
 * pin the fail-closed contract: the job exits 0 ONLY for a sentinel carrying a
 * validated integer `vitestCode === 0`. A missing, unreadable, non-JSON, or
 * malformed sentinel must NEVER false-pass — that would defeat the entire fix.
 */

import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
// The runner is an ESM .mjs; it only drives the emulators when run as main, so
// importing it here just pulls in the pure decision functions.
import { resolveOuterExitCode, resolveTestFileArgs } from '../../scripts/run-emulator-tests.mjs';
import { assignGroups, discoverTestFiles, GROUPS } from '../../scripts/emulator-test-groups.mjs';

let dir: string;
let sentinel: string;

beforeEach(() => {
  dir = mkdtempSync(join(tmpdir(), 'kcc-emu-test-'));
  sentinel = join(dir, 'result.json');
});

afterEach(() => {
  rmSync(dir, { recursive: true, force: true });
});

describe('resolveOuterExitCode', () => {
  it('passes (0) ONLY when the sentinel has a validated integer vitestCode 0', () => {
    writeFileSync(sentinel, JSON.stringify({ vitestCode: 0 }));
    // Even if emulators:exec exited non-zero (teardown noise), a green vitest passes.
    expect(resolveOuterExitCode(sentinel, 0)).toBe(0);
    expect(resolveOuterExitCode(sentinel, 1)).toBe(0);
  });

  it('fails with vitest code when the suite really failed', () => {
    writeFileSync(sentinel, JSON.stringify({ vitestCode: 1 }));
    expect(resolveOuterExitCode(sentinel, 1)).toBe(1);
    // Never mask a real failure, even if emulators:exec somehow exited 0.
    expect(resolveOuterExitCode(sentinel, 0)).toBe(1);
  });

  it('fails (never false-passes) when the sentinel file is missing', () => {
    // sentinel not written
    expect(resolveOuterExitCode(sentinel, 0)).toBe(1);
    expect(resolveOuterExitCode(sentinel, 137)).toBe(137);
  });

  it('fails when the sentinel is not valid JSON', () => {
    writeFileSync(sentinel, 'not json at all');
    expect(resolveOuterExitCode(sentinel, 0)).toBe(1);
    expect(resolveOuterExitCode(sentinel, 2)).toBe(2);
  });

  it.each([
    ['missing vitestCode', {}],
    ['undefined vitestCode', { vitestCode: undefined }],
    ['string vitestCode', { vitestCode: '0' }],
    ['float vitestCode', { vitestCode: 0.5 }],
    ['null vitestCode', { vitestCode: null }],
    ['NaN-ish', { vitestCode: 'NaN' }],
  ])('fails when %s (no false green from a malformed sentinel)', (_label, payload) => {
    writeFileSync(sentinel, JSON.stringify(payload));
    expect(resolveOuterExitCode(sentinel, 0)).toBe(1);
  });
});

describe('resolveTestFileArgs', () => {
  it('runs the whole suite (no file args) when KCC_TEST_FILES is unset', () => {
    expect(resolveTestFileArgs({})).toEqual({ args: [] });
  });

  it('forwards a group file list as positional vitest args', () => {
    expect(
      resolveTestFileArgs({ KCC_TEST_FILES: 'src/__tests__/a.emulator.test.ts src/__tests__/b.emulator.test.ts' }),
    ).toEqual({ args: ['src/__tests__/a.emulator.test.ts', 'src/__tests__/b.emulator.test.ts'] });
  });

  it('tolerates arbitrary whitespace (newlines/tabs) between files', () => {
    expect(resolveTestFileArgs({ KCC_TEST_FILES: '  a.ts\n\tb.ts   c.ts ' })).toEqual({
      args: ['a.ts', 'b.ts', 'c.ts'],
    });
  });

  it.each([
    ['empty string', ''],
    ['only whitespace', '   \n\t '],
  ])('fails closed when KCC_TEST_FILES is set but names no files: %s', (_label, value) => {
    const result = resolveTestFileArgs({ KCC_TEST_FILES: value });
    expect(result).toHaveProperty('error');
    expect('args' in result).toBe(false);
  });
});

describe('emulator test-file group partition (coverage guard)', () => {
  const files = discoverTestFiles();
  const { groups, errors } = assignGroups(files);

  it('assigns EVERY emulator test file to exactly one group (no unassigned/duplicate/stale)', () => {
    // If this fails, the message names the offending file(s) — add a new
    // emulator test to a group in scripts/emulator-test-groups.mjs.
    expect(errors).toEqual([]);
  });

  it('covers all files exactly once across groups (partition, no overlap)', () => {
    const assigned = groups.flatMap((g) => g.files);
    expect(assigned.slice().sort()).toEqual(files.slice().sort());
    expect(new Set(assigned).size).toBe(assigned.length);
    expect(assigned.length).toBe(files.length);
  });

  it('has a non-empty, named group for every entry', () => {
    for (const g of groups) {
      expect(g.name.length).toBeGreaterThan(0);
      expect(g.files.length).toBeGreaterThan(0);
    }
    expect(groups.length).toBe(GROUPS.length);
  });
});
