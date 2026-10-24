# ADR 003: Driver shares the CarCommunity identity and profile

- Status: Accepted
- Date: 2026-10-24

## Context

Driver is a separate mobile community app, but a member should not need a
second account or maintain a second public identity. CarCommunity already owns
Firebase Authentication, the canonical public profile in `users/{uid}`, and
the garage in `vehicles`.

A separate Firebase project or a token-exchange/link table would create two
UIDs, ambiguous deletion semantics, and profile drift. Deploying two unrelated
Firestore or Storage rule sets to one project would also let one repository
silently replace the other app's security policy.

## Decision

Driver uses the existing `kungsbacka-car-community` Firebase project and the
same Firebase Auth providers and UID as CarCommunity:

- Android uses Google sign-in and iOS uses Apple sign-in, matching the
  corresponding CarCommunity clients.
- `users/{uid}` is the canonical display name, biography, avatar, onboarding,
  suspension, and deletion record.
- `vehicles` and `vehicleImages` remain the canonical garage. Driver reads
  them; garage changes continue through CarCommunity.
- Driver-owned data uses `driver*` top-level collection names and
  `driverThreadMedia` Storage paths.
- Driver Functions use Firebase codebase `driver` and grouped `driver-*`
  function names so a Driver deployment cannot delete CarCommunity Functions.
- This repository owns the combined Firestore rules, Storage rules, and indexes
  for the shared project. The Driver repository's production `firebase.json`
  can deploy only its Functions codebase.
- The Driver role uses custom claim `driverRole`; it never reuses
  CarCommunity's `admin` claim.

Driver profile removal and Driver moderation are scoped to Driver. They must
never disable or delete the shared Auth identity, `users/{uid}`,
`userPrivate/{uid}`, or `vehicles`. Full-account deletion remains exclusively
owned by CarCommunity.

## Consequences

- Profile edits made through either app converge on the same canonical record.
  Driver keeps only a searchable projection plus Driver-specific handle and
  counters.
- A canonical CarCommunity suspension or deletion denies Driver callables.
  Driver-specific suspension denies Driver without removing CarCommunity
  access.
- Registering the Driver Android/iOS app IDs, OAuth configuration, App Check,
  signing keys, and deploying the reviewed rules/functions remain explicit
  release operations; this ADR performs no cloud write.
- Cross-platform provider linking is a separate account-lifecycle decision. A
  user receives the same UID where the same provider identity is already used;
  linking Apple and Google identities must not be inferred or automated by
  Driver.
