import { describe, expect, it } from 'vitest';
import {
  ANR_ISSUE_LABEL,
  CRASH_ISSUE_LABEL,
  CRASHLYTICS_ISSUE_LINKS_COLLECTION,
  REGRESSION_ISSUE_LABEL,
  buildCrashIssueBody,
  buildCrashIssuePayload,
  buildCrashIssueTitle,
  buildNewCrashIssueLink,
  crashIssueLabels,
  crashKindLabel,
  crashlyticsIssueDeepLink,
  normalizeCrashAlert,
  type CrashAlertPayloadLike,
} from './crashReporting-core';
import { AUTO_GENERATED_LABEL } from '../diagnostics/signInIssues-core';

const FIXED_TS = 'TS';
const ts = () => FIXED_TS;
const META = { firstSeenIso: '2026-08-06T00:00:00.000Z', count: 3 };
const APP_ID = '1:1234567890:android:abcdef';

function fatalPayload(overrides: Partial<CrashAlertPayloadLike['issue']> = {}): CrashAlertPayloadLike {
  return {
    issue: {
      id: 'abc123',
      title: 'NullPointerException in FooActivity.onCreate',
      subtitle: 'FooActivity.kt line 42',
      appVersion: '1.2.3 (45)',
      ...overrides,
    },
  };
}

describe('normalizeCrashAlert', () => {
  it('normalizes a fatal payload with all fields', () => {
    const alert = normalizeCrashAlert('fatal', fatalPayload(), APP_ID, 'my-project');
    expect(alert).not.toBeNull();
    expect(alert?.kind).toBe('fatal');
    expect(alert?.issueId).toBe('abc123');
    expect(alert?.title).toBe('NullPointerException in FooActivity.onCreate');
    expect(alert?.subtitle).toBe('FooActivity.kt line 42');
    expect(alert?.appVersion).toBe('1.2.3 (45)');
    expect(alert?.regressionType).toBeNull();
    expect(alert?.resolveTime).toBeNull();
    expect(alert?.deepLink).toBe(
      `https://console.firebase.google.com/project/my-project/crashlytics/app/${APP_ID}/issues/abc123`,
    );
  });

  it('carries regression-only fields for a regression payload', () => {
    const alert = normalizeCrashAlert(
      'regression',
      { issue: { id: 'reg99', title: 'ANR' }, type: 'ANR', resolveTime: '2026-07-01T00:00:00Z' },
      APP_ID,
    );
    expect(alert?.kind).toBe('regression');
    expect(alert?.regressionType).toBe('ANR');
    expect(alert?.resolveTime).toBe('2026-07-01T00:00:00Z');
  });

  it('ignores type/resolveTime on non-regression kinds', () => {
    const alert = normalizeCrashAlert(
      'fatal',
      { issue: { id: 'x' }, type: 'leaked', resolveTime: 'leaked' } as CrashAlertPayloadLike,
      APP_ID,
    );
    expect(alert?.regressionType).toBeNull();
    expect(alert?.resolveTime).toBeNull();
  });

  it('returns null when the issue id is missing/blank (no dedup key)', () => {
    expect(normalizeCrashAlert('fatal', { issue: {} }, APP_ID)).toBeNull();
    expect(normalizeCrashAlert('fatal', { issue: { id: '   ' } }, APP_ID)).toBeNull();
    expect(normalizeCrashAlert('fatal', {}, APP_ID)).toBeNull();
    expect(normalizeCrashAlert('fatal', null, APP_ID)).toBeNull();
    expect(normalizeCrashAlert('fatal', undefined, APP_ID)).toBeNull();
  });

  it('rejects an issue id that is not a safe single-segment doc id', () => {
    expect(normalizeCrashAlert('fatal', { issue: { id: 'a/b' } }, APP_ID)).toBeNull();
    expect(normalizeCrashAlert('fatal', { issue: { id: '.' } }, APP_ID)).toBeNull();
    expect(normalizeCrashAlert('fatal', { issue: { id: '..' } }, APP_ID)).toBeNull();
  });

  it('defensively nulls wrong-typed / missing optional fields without throwing', () => {
    const alert = normalizeCrashAlert(
      'fatal',
      { issue: { id: 'ok', title: 42, subtitle: null, appVersion: {} } as never },
      APP_ID,
    );
    expect(alert?.issueId).toBe('ok');
    expect(alert?.title).toBeNull();
    expect(alert?.subtitle).toBeNull();
    expect(alert?.appVersion).toBeNull();
  });

  it('falls back to `_` project and `unknown` appId when absent', () => {
    const alert = normalizeCrashAlert('fatal', { issue: { id: 'ok' } }, null);
    expect(alert?.deepLink).toBe(
      'https://console.firebase.google.com/project/_/crashlytics/app/unknown/issues/ok',
    );
  });
});

describe('crashlyticsIssueDeepLink', () => {
  it('defaults the project to `_`', () => {
    expect(crashlyticsIssueDeepLink('APP', 'ID')).toBe(
      'https://console.firebase.google.com/project/_/crashlytics/app/APP/issues/ID',
    );
  });
});

describe('fatal-vs-ANR-vs-regression branching', () => {
  it('labels fatal crashes with android-crash + auto-generated', () => {
    expect(crashIssueLabels('fatal')).toEqual([CRASH_ISSUE_LABEL, AUTO_GENERATED_LABEL]);
  });
  it('labels ANRs with anr + auto-generated', () => {
    expect(crashIssueLabels('anr')).toEqual([ANR_ISSUE_LABEL, AUTO_GENERATED_LABEL]);
  });
  it('labels regressions with android-crash + regression + auto-generated', () => {
    expect(crashIssueLabels('regression')).toEqual([
      CRASH_ISSUE_LABEL,
      REGRESSION_ISSUE_LABEL,
      AUTO_GENERATED_LABEL,
    ]);
  });

  it('title tags differ per kind', () => {
    expect(buildCrashIssueTitle(normalizeCrashAlert('fatal', fatalPayload(), APP_ID)!)).toMatch(
      /^\[Crash\] /,
    );
    expect(buildCrashIssueTitle(normalizeCrashAlert('anr', fatalPayload(), APP_ID)!)).toMatch(
      /^\[ANR\] /,
    );
    expect(
      buildCrashIssueTitle(normalizeCrashAlert('regression', fatalPayload(), APP_ID)!),
    ).toMatch(/^\[Crash regression\] /);
  });

  it('body carries the kind label and regression-only fields only for regression', () => {
    const reg = normalizeCrashAlert(
      'regression',
      { issue: { id: 'r', title: 'X' }, type: 'FATAL', resolveTime: '2026-01-01T00:00:00Z' },
      APP_ID,
    )!;
    const body = buildCrashIssueBody(reg, META);
    expect(body).toContain(crashKindLabel('regression'));
    expect(body).toContain('Last resolved before re-emerging');
    expect(body).toContain('2026-01-01T00:00:00Z');

    const fatalBody = buildCrashIssueBody(normalizeCrashAlert('fatal', fatalPayload(), APP_ID)!, META);
    expect(fatalBody).not.toContain('Last resolved before re-emerging');
  });
});

describe('buildCrashIssueTitle', () => {
  it('uses title, then subtitle, then issue id as the summary', () => {
    expect(
      buildCrashIssueTitle(normalizeCrashAlert('fatal', fatalPayload(), APP_ID)!),
    ).toContain('NullPointerException in FooActivity.onCreate');
    expect(
      buildCrashIssueTitle(
        normalizeCrashAlert('fatal', { issue: { id: 'i', subtitle: 'sub only' } }, APP_ID)!,
      ),
    ).toContain('sub only');
    expect(
      buildCrashIssueTitle(normalizeCrashAlert('fatal', { issue: { id: 'onlyid' } }, APP_ID)!),
    ).toContain('onlyid');
  });

  it('defangs @mentions and #refs in the title', () => {
    const alert = normalizeCrashAlert('fatal', { issue: { id: 'i', title: '@maintainer #1' } }, APP_ID)!;
    const title = buildCrashIssueTitle(alert);
    expect(title).not.toMatch(/@maintainer/);
    expect(title).toContain('​');
  });
});

describe('buildCrashIssueBody', () => {
  const body = buildCrashIssueBody(normalizeCrashAlert('fatal', fatalPayload(), APP_ID, 'p')!, META);

  it('is honest that the full stack trace is not in the payload', () => {
    expect(body).toContain('NOT in the Crashlytics alert payload');
  });
  it('includes the Crashlytics issue id and a deep link', () => {
    expect(body).toContain('abc123');
    expect(body).toContain('https://console.firebase.google.com/project/p/crashlytics/app/');
  });
  it('includes app version, first-seen and occurrence count', () => {
    expect(body).toContain('1.2.3 (45)');
    expect(body).toContain(META.firstSeenIso);
    expect(body).toContain(`Occurrences (seen by this bridge): ${META.count}`);
  });
  it('renders unknown for absent optional fields', () => {
    const sparse = buildCrashIssueBody(normalizeCrashAlert('fatal', { issue: { id: 'i' } }, APP_ID)!, META);
    expect(sparse).toContain('- Subtitle: unknown');
    expect(sparse).toContain('- App version: unknown');
  });
});

describe('buildCrashIssuePayload', () => {
  it('assembles title + body + per-kind labels', () => {
    const payload = buildCrashIssuePayload(normalizeCrashAlert('anr', fatalPayload(), APP_ID)!, META);
    expect(payload.title).toMatch(/^\[ANR\] /);
    expect(payload.labels).toEqual([ANR_ISSUE_LABEL, AUTO_GENERATED_LABEL]);
    expect(payload.body).toContain('abc123');
  });
});

describe('dedup fingerprint = the Crashlytics issue id', () => {
  it('keys the link doc collection and carries the issue id + kind', () => {
    expect(CRASHLYTICS_ISSUE_LINKS_COLLECTION).toBe('crashlyticsIssueLinks');
    const link = buildNewCrashIssueLink(normalizeCrashAlert('fatal', fatalPayload(), APP_ID)!, ts);
    expect(link.issueId).toBe('abc123');
    expect(link.kind).toBe('fatal');
    expect(link.status).toBe('creating');
    expect(link.count).toBe(1);
    expect(link.firstSeenAt).toBe(FIXED_TS);
  });
});
