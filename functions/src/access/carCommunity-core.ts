import { createHash } from 'node:crypto';
/** Only trusted Firebase Admin providerData is accepted; never supplied email/name. */
export function providerIdentityKeys(providers: readonly { providerId: string; uid: string }[]) {
  const keys: { googleKey: string | null; appleKey: string | null } = {
    googleKey: null,
    appleKey: null,
  };
  for (const provider of providers) {
    if (!provider.uid || !['google.com', 'apple.com'].includes(provider.providerId)) continue;
    const key = createHash('sha256')
      .update(`${provider.providerId}\0${provider.uid}`)
      .digest('hex');
    if (provider.providerId === 'google.com') keys.googleKey = key;
    if (provider.providerId === 'apple.com') keys.appleKey = key;
  }
  return keys;
}
export function identityKeys(
  value: { googleKey?: unknown; appleKey?: unknown } | undefined,
): string[] {
  return [
    ...new Set(
      [value?.googleKey, value?.appleKey].filter(
        (v): v is string => typeof v === 'string' && /^[0-9a-f]{64}$/.test(v),
      ),
    ),
  ];
}
export function isCarCommunityRestricted(
  access:
    | {
        initialized?: unknown;
        suspended?: unknown;
        inheritedBlocked?: unknown;
        explicitBlacklistId?: unknown;
      }
    | undefined,
  identities: readonly { blocked?: unknown }[],
) {
  return (
    access?.initialized !== true ||
    access.suspended === true ||
    access.inheritedBlocked === true ||
    typeof access.explicitBlacklistId === 'string' ||
    identities.some((value) => value.blocked === true)
  );
}
