/**
 * App version domain module for the admin portal.
 *
 * The current value is read directly from the `config/appVersion` Firestore
 * document (authenticated read, rules-gated) — the same pattern as
 * feature-flags. Publishing goes through the audited `admin-setAppVersion`
 * callable, which validates the numbers and writes the complete config.
 *
 * This page is the operator half of the in-app update prompt: the Android
 * app only ever prompts when `latestVersionCode` here is AHEAD of the build
 * on the device, so a release that is not recorded here prompts nobody.
 */

import { doc, getDoc } from 'firebase/firestore';
import { getAdminFirestore } from '../../lib/firestore';
import { callAdmin } from '../../lib/callables';

export interface AppVersionConfig {
  latestVersionCode: number;
  latestVersionName: string | null;
  minimumSupportedVersionCode: number;
}

/**
 * The published config as CLIENTS WILL ACT ON IT, or null when nothing has
 * been published yet.
 *
 * This mirrors the Android parser's rules rather than echoing the raw
 * document, because the page's job is to tell an operator what devices are
 * doing — showing a stored `minimumSupportedVersionCode` of 99 next to a
 * `latestVersionCode` of 23 would be a lie: no build could satisfy it, so
 * the app discards it and blocks nobody. An unusable `latestVersionCode`
 * reads as null for the same reason the app shows no prompt.
 */
export async function loadAppVersionConfig(): Promise<AppVersionConfig | null> {
  const snapshot = await getDoc(doc(getAdminFirestore(), 'config', 'appVersion'));
  const stored = snapshot.data() as Record<string, unknown> | undefined;
  if (!stored) return null;
  const latestVersionCode = stored.latestVersionCode;
  if (typeof latestVersionCode !== 'number' || !Number.isInteger(latestVersionCode)) return null;
  if (latestVersionCode < 0) return null;
  const name = stored.latestVersionName;
  const trimmedName = typeof name === 'string' ? name.trim() : '';
  const minimum = stored.minimumSupportedVersionCode;
  const minimumIsEffective =
    typeof minimum === 'number' &&
    Number.isInteger(minimum) &&
    minimum >= 0 &&
    minimum <= latestVersionCode;
  return {
    latestVersionCode,
    latestVersionName: trimmedName === '' ? null : trimmedName,
    minimumSupportedVersionCode: minimumIsEffective ? minimum : 0,
  };
}

export interface SetAppVersionRequest {
  latestVersionCode: number;
  latestVersionName?: string;
  /** Omitted means 0 — nothing blocked. */
  minimumSupportedVersionCode?: number;
  reason?: string;
}

/**
 * Publishes the version record via the audited admin.setAppVersion callable.
 * Every call is a COMPLETE config: an omitted minimum resets it to 0.
 */
export async function setAppVersion(request: SetAppVersionRequest): Promise<AppVersionConfig> {
  return callAdmin<AppVersionConfig>('admin-setAppVersion', {
    latestVersionCode: request.latestVersionCode,
    ...(request.latestVersionName ? { latestVersionName: request.latestVersionName } : {}),
    ...(request.minimumSupportedVersionCode
      ? { minimumSupportedVersionCode: request.minimumSupportedVersionCode }
      : {}),
    ...(request.reason ? { reason: request.reason } : {}),
  });
}
