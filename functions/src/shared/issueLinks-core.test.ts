/**
 * Unit tests for the shared auto-issue link state machine extracted from
 * errors/clientErrors-core.ts. clientErrors-core.test.ts still covers the
 * domain-named delegations, so this suite focuses on the generic contract:
 * exactly-one-create per fingerprint, and a `failed` link that stays retriable.
 */

import { describe, expect, it } from 'vitest';
import {
  buildIssueLinkCreated,
  buildIssueLinkFailed,
  buildIssueLinkIncrement,
  buildIssueLinkRetry,
  buildNewIssueLink,
  decideIssueAction,
} from './issueLinks-core';

describe('decideIssueAction', () => {
  it('creates when no link exists', () => {
    expect(decideIssueAction(null)).toBe('create');
    expect(decideIssueAction(undefined)).toBe('create');
  });

  it('re-creates a failed link (a transient GitHub outage must not silence an error)', () => {
    expect(decideIssueAction({ status: 'failed', count: 1 })).toBe('create');
    expect(decideIssueAction({ status: 'failed', count: 99 })).toBe('create');
  });

  it('only increments for an in-flight or existing issue', () => {
    expect(decideIssueAction({ status: 'creating', count: 1 })).toBe('increment');
    expect(decideIssueAction({ status: 'created', count: 1 })).toBe('increment');
  });
});

describe('link document builders', () => {
  const ts = () => 'SERVER_TS';

  it('writes a claiming placeholder carrying the caller identity fields', () => {
    expect(buildNewIssueLink({ fingerprint: 'fp', source: 'account.purgeDeleted' }, ts)).toEqual({
      fingerprint: 'fp',
      source: 'account.purgeDeleted',
      status: 'creating',
      issueNumber: null,
      issueUrl: null,
      count: 1,
      firstSeenAt: 'SERVER_TS',
      lastSeenAt: 'SERVER_TS',
    });
  });

  it('records the issue on success', () => {
    expect(buildIssueLinkCreated({ number: 42, url: 'https://github.com/x/y/issues/42' })).toEqual({
      status: 'created',
      issueNumber: 42,
      issueUrl: 'https://github.com/x/y/issues/42',
    });
  });

  it('increments without touching the status or the issue reference', () => {
    const patch = buildIssueLinkIncrement('INCREMENT', ts);
    expect(patch).toEqual({ count: 'INCREMENT', lastSeenAt: 'SERVER_TS' });
    expect(patch).not.toHaveProperty('status');
    expect(patch).not.toHaveProperty('issueNumber');
  });

  it('re-claims without resetting the preserved count/firstSeenAt', () => {
    const patch = buildIssueLinkRetry('INCREMENT', ts);
    expect(patch).toEqual({ status: 'creating', count: 'INCREMENT', lastSeenAt: 'SERVER_TS' });
    expect(patch).not.toHaveProperty('firstSeenAt');
  });

  it('marks a link retriable without discarding its history', () => {
    expect(buildIssueLinkFailed()).toEqual({ status: 'failed' });
  });
});
