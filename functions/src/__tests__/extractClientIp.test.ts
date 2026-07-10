/**
 * Unit tests for extractClientIp (diagnostics-core.ts). No emulator required.
 *
 * These lock in the proxy-safe X-Forwarded-For handling: Google's load
 * balancer / Front End appends `<client-ip>,<load-balancer-ip>` to the RIGHT,
 * so the trusted client IP is the second-to-last entry and any client-supplied
 * (spoofable) entries on the LEFT must be ignored.
 */

import { describe, expect, it } from 'vitest';
import { extractClientIp } from '../diagnostics/diagnostics-core';

describe('extractClientIp', () => {
  it('takes the client IP from the trusted second-to-last XFF position', () => {
    // Normal production shape: <client-ip>,<load-balancer-ip>
    expect(extractClientIp('203.0.113.7, 35.191.0.1', undefined)).toBe('203.0.113.7');
  });

  it('ignores client-prepended spoof entries on the left', () => {
    // Attacker prepends fake IPs; only the trusted suffix is honoured.
    expect(
      extractClientIp('1.1.1.1, 2.2.2.2, 203.0.113.7, 35.191.0.1', undefined),
    ).toBe('203.0.113.7');
  });

  it('varying the spoofed prefix does not change the derived IP (stable bucket)', () => {
    const a = extractClientIp('9.9.9.9, 203.0.113.7, 35.191.0.1', undefined);
    const b = extractClientIp('8.8.8.8, 203.0.113.7, 35.191.0.1', undefined);
    expect(a).toBe(b);
    expect(a).toBe('203.0.113.7');
  });

  it('handles the array header shape (repeated header) with comma-separated elements', () => {
    expect(
      extractClientIp(['1.1.1.1', '203.0.113.7, 35.191.0.1'], undefined),
    ).toBe('203.0.113.7');
  });

  it('drops blank/whitespace entries before selecting position', () => {
    expect(
      extractClientIp('1.1.1.1, ,203.0.113.7 ,  35.191.0.1 ', undefined),
    ).toBe('203.0.113.7');
  });

  it('falls back to the rightmost entry when the chain is shorter than expected', () => {
    expect(extractClientIp('203.0.113.7', undefined)).toBe('203.0.113.7');
  });

  it('falls back to the direct connection IP when XFF is blank', () => {
    expect(extractClientIp('   ', '198.51.100.2')).toBe('198.51.100.2');
    expect(extractClientIp(undefined, '198.51.100.2')).toBe('198.51.100.2');
  });

  it('returns "unknown" (never an empty string) when everything is blank', () => {
    expect(extractClientIp('', '')).toBe('unknown');
    expect(extractClientIp(undefined, undefined)).toBe('unknown');
    expect(extractClientIp('  ,  ', '   ')).toBe('unknown');
  });
});
