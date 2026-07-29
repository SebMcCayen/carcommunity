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
 * The published config, or null when nothing has been published yet.
 *
 * A document with an unusable `latestVersionCode` reads as null for the same
 * reason the app treats it as "no prompt": there is no sensible number to
 * show, and inventing one would misreport what devices are acting on.
 */
export async function loadAppVersionConfig(): Promise<AppVersionConfig | null> {
  const snapshot = await getDoc(doc(getAdminFirestore(), 'config', 'appVersion'));
  const stored = snapshot.data() as Record<string, unknown> | undefined;
  if (!stored) return null;
  const latestVersionCode = stored.latestVersionCode;
  if (typeof latestVersionCode !== 'number' || !Number.isInteger(latestVersionCode)) return null;
  const name = stored.latestVersionName;
  const minimum = stored.minimumSupportedVersionCode;
  return {
    latestVersionCode,
    latestVersionName: typeof name === 'string' && name.trim() !== '' ? name : null,
    minimumSupportedVersionCode:
      typeof minimum === 'number' && Number.isInteger(minimum) ? minimum : 0,
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
