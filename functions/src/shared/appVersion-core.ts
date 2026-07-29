/**
 * App version config — document shape and input validation for the
 * server-held "which build is current" record. Pure module — no Firebase
 * Admin SDK imports.
 *
 * The state lives in ONE flat Firestore document, `config/appVersion`,
 * alongside `config/featureFlags` — the same backend-managed config
 * channel: authenticated clients read it directly (rules-gated), writes
 * go exclusively through the audited `admin.setAppVersion` callable.
 *
 * Why a server document rather than something baked into the app: the
 * whole point of an update prompt is that it must start firing for builds
 * that are ALREADY on users' devices. A value shipped in the APK can only
 * describe the build it shipped in, so it can never announce its own
 * successor.
 *
 * Everything is expressed in `versionCode` (the monotonically increasing
 * Android integer), never in `versionName` strings: comparing "0.9.0" to
 * "0.10.0" as text is wrong, and the client's comparison must be a plain
 * integer `<`. `latestVersionName` exists ONLY as display text for the
 * dialog and is never compared.
 */

import { z } from 'zod';

/** Firestore location of the config document. */
export const APP_VERSION_COLLECTION = 'config';
export const APP_VERSION_DOC = 'appVersion';

/**
 * Play's hard ceiling for `versionCode`. Rejecting anything above it keeps
 * an obviously bogus value (a pasted timestamp, say) from being published
 * as "the latest version", which would prompt every user forever.
 */
export const MAX_VERSION_CODE = 2_100_000_000;

export const MAX_VERSION_NAME_LENGTH = 32;

export const SET_APP_VERSION_REASON_MAX_LENGTH = 500;

export interface SetAppVersionInput {
  /** versionCode of the newest build published to Play. */
  latestVersionCode: number;
  /** Display-only version name for that build (e.g. "0.8.12"). */
  latestVersionName?: string;
  /**
   * Oldest versionCode still supported. 0 (the default when omitted) means
   * NOTHING is unsupported — the blocking path stays inert.
   */
  minimumSupportedVersionCode?: number;
  reason?: string;
}

const versionCodeSchema = z.number().int().min(0).max(MAX_VERSION_CODE);

const setAppVersionInputSchema = z
  .object({
    latestVersionCode: versionCodeSchema.min(1),
    latestVersionName: z.string().trim().min(1).max(MAX_VERSION_NAME_LENGTH).optional(),
    minimumSupportedVersionCode: versionCodeSchema.optional(),
    reason: z.string().trim().min(1).max(SET_APP_VERSION_REASON_MAX_LENGTH).optional(),
  })
  .strict()
  // A minimum ABOVE the latest published build is unsatisfiable: no user
  // could ever install a build that clears it, so accepting it would lock
  // every account out of the app with no way back. Rejected at the door.
  .refine(
    (value) => (value.minimumSupportedVersionCode ?? 0) <= value.latestVersionCode,
    'minimumSupportedVersionCode cannot exceed latestVersionCode — no published build could satisfy it.',
  );

export type ParseResult<T> = { ok: true; input: T } | { ok: false; message: string };

export function parseSetAppVersionInput(data: unknown): ParseResult<SetAppVersionInput> {
  const result = setAppVersionInputSchema.safeParse(data ?? {});
  if (!result.success) {
    return {
      ok: false,
      message:
        `Expected { latestVersionCode: integer 1..${MAX_VERSION_CODE}, ` +
        `latestVersionName?: string, minimumSupportedVersionCode?: integer 0..latestVersionCode, reason? }.`,
    };
  }
  return { ok: true, input: result.data };
}

/** The stored document, with every optional field resolved to its default. */
export interface AppVersionDocument {
  latestVersionCode: number;
  latestVersionName: string | null;
  minimumSupportedVersionCode: number;
}

/**
 * Builds the complete document body for a write. Each call is a FULL
 * statement of the config, not a patch: omitting `minimumSupportedVersionCode`
 * resets it to 0 (nothing blocked). That is deliberate — the safe direction
 * for a forgotten field is "block nobody", never "keep blocking".
 */
export function buildAppVersionDocument(input: SetAppVersionInput): AppVersionDocument {
  return {
    latestVersionCode: input.latestVersionCode,
    latestVersionName: input.latestVersionName ?? null,
    minimumSupportedVersionCode: input.minimumSupportedVersionCode ?? 0,
  };
}
