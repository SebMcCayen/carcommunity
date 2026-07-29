/**
 * userSearch-onUserProfileWrite — keeps `users/{uid}.displayNameLower` in sync
 * with `displayName`, whatever wrote it.
 *
 * WHY THIS EXISTS (the gap it closes)
 * ----------------------------------
 * Every member lookup in the app — friend nickname resolution
 * (friends/manageFriends.ts resolveTarget) and now the member typeahead
 * (users/searchMembers.ts) — queries `displayNameLower` and NEVER `displayName`,
 * because Firestore has no case-insensitive operator. The BACKEND write paths
 * populate the key in lockstep (auth/provisioning.ts buildUserProfileDocument,
 * auth/onboarding-core.ts computeOnboardingWrites), but they are not the only
 * writers:
 *
 *   firebase/firestore.rules validUserProfileUpdate() lets the OWNER update
 *   `['displayName', 'avatarPath', 'bio', 'updatedAt']` directly, and the
 *   Android profile screen does exactly that
 *   (profile/FirebaseProfileRepository.kt updateProfile). `displayNameLower` is
 *   NOT in that whitelist, so the client cannot write it even if it wanted to.
 *
 * The consequence before this trigger: a member who renamed themselves stayed
 * findable ONLY under their OLD nickname, silently and forever — the query
 * matches the stale key, so they appear under a name they no longer use and are
 * invisible under the one they do. Admin edits via the Admin SDK have the same
 * hazard. Rather than chase every present and future writer, the key is derived
 * from the authoritative field HERE, once, for all of them.
 *
 * WHY NOT WIDEN THE SECURITY RULE INSTEAD: rules could allow the client to write
 * `displayNameLower` and require it to equal `displayName.lower()`, but rules
 * have no `trim()` and their `lower()` is not guaranteed to agree with
 * `String.prototype.toLowerCase()` on non-ASCII — so the stored key could
 * disagree with the query key derived by toSearchKey(). Deriving server-side
 * with the one shared function is the only way both sides provably match.
 *
 * TERMINATION: the write below re-enters this trigger exactly once; that second
 * invocation finds the key already equal to the derived value and returns
 * without writing. The equality check is the loop guard — do not remove it, and
 * do not make the written value depend on anything that changes between
 * invocations.
 *
 * COST: this fires on every `users/{uid}` write, including frequent
 * backend-managed ones (lastLoginAt, subscription state). Those invocations do
 * no Firestore work at all — the changed document is delivered in the event
 * payload, so the no-op path is a comparison and a return, with no read.
 *
 * SCOPE: this repairs writes made from NOW ON. Documents that predate the
 * `displayNameLower` key are untouched until they are next written; the
 * idempotent one-off backfill (functions/scripts/backfill-display-name-lower.mjs)
 * is what covers those, and it must be run once after deploy.
 */

import { onDocumentWritten } from 'firebase-functions/v2/firestore';
import { logger } from 'firebase-functions';
import { toSearchKey } from './user-search-core';
import { MAX_INSTANCES_TRIGGER } from '../shared/instanceLimits';

export const onUserProfileWrite = onDocumentWritten(
  {
    region: 'europe-west1',
    maxInstances: MAX_INSTANCES_TRIGGER,
    document: 'users/{uid}',
    memory: '256MiB',
    timeoutSeconds: 30,
  },
  async (event) => {
    const after = event.data?.after;
    // Deletion (or a malformed event): nothing to derive a key from, and no
    // document left to write it to.
    if (!after?.exists) {
      return;
    }

    const data = after.data();
    const displayName = data?.displayName;
    // A document with no string displayName has nothing to derive from. We
    // deliberately do NOT clear an existing key here: the only way to reach this
    // state is a partial/corrupt document, and blanking the key would make the
    // member unfindable on top of whatever else is wrong.
    if (typeof displayName !== 'string') {
      return;
    }

    const expected = toSearchKey(displayName);
    // Loop guard AND the no-op fast path for unrelated field writes.
    if (data?.displayNameLower === expected) {
      return;
    }

    try {
      await after.ref.set({ displayNameLower: expected }, { merge: true });
    } catch (error) {
      // Rethrow so the platform retries: leaving the key stale is precisely the
      // silent failure this trigger exists to prevent, so it must be loud.
      logger.error('displayNameLower sync failed', {
        uid: event.params.uid,
        error: String(error),
      });
      throw error;
    }
  },
);
