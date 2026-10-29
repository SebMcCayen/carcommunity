import { describe, expect, it } from 'vitest';
import { providerIdentityKeys, identityKeys, isCarCommunityRestricted } from './carCommunity-core';
describe('product-only identity authority', () => {
  it('binds stable provider subject independently of Firebase UID/email/name', () => {
    const first = providerIdentityKeys([{ providerId: 'google.com', uid: 'verified-subject' }]);
    expect(first).toEqual(
      providerIdentityKeys([{ providerId: 'google.com', uid: 'verified-subject' }]),
    );
    expect(first.googleKey).toMatch(/^[0-9a-f]{64}$/);
    expect(first.googleKey).not.toContain('verified-subject');
    expect(first.googleKey).not.toBe(
      providerIdentityKeys([{ providerId: 'apple.com', uid: 'verified-subject' }]).appleKey,
    );
  });
  it('cannot turn an email/password account or a claimed unknown provider into a persistent identity', () => {
    expect(
      identityKeys(
        providerIdentityKeys([
          { providerId: 'password', uid: 'same@example.com' },
          { providerId: 'custom', uid: 'verified-subject' },
        ]),
      ),
    ).toEqual([]);
  });
  it('fails closed before initialization and keeps suspension separate from identity blacklist', () => {
    expect(isCarCommunityRestricted(undefined, [])).toBe(true);
    expect(isCarCommunityRestricted({ initialized: true }, [])).toBe(false);
    expect(isCarCommunityRestricted({ initialized: true, suspended: true }, [])).toBe(true);
    expect(
      isCarCommunityRestricted({ initialized: true, suspended: false }, [{ blocked: true }]),
    ).toBe(true);
    expect(
      isCarCommunityRestricted({ initialized: true, explicitBlacklistId: 'case' }, [
        { blocked: false },
      ]),
    ).toBe(true);
    expect(isCarCommunityRestricted({ initialized: true, inheritedBlocked: true }, [])).toBe(true);
  });
});
