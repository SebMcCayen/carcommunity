# Subscription tiers foundation migration

## Scope and safety boundary

This specification prepares existing Firestore subscription records for the
brand-neutral `community | plus | supporter` tier contract. It does **not** activate a paywall,
enable `MemberGating`, create Play Console products, call a billing provider, change purchase UI,
or grant Supporter to anyone.

The legacy fields and runtime remain authoritative during this migration:

- `none` maps to `community`.
- `member_monthly` maps explicitly to `plus`.
- No legacy source value maps automatically to `supporter`; an explicit valid Supporter tier is
  preserved.
- A retained tier on an expired or revoked record is lifecycle data only; `entitlement: none`
  always resolves to Community access.
- `users/{uid}.activeMember` and the `activeMember` custom claim are not changed.
- Existing `entitlement`, status, platform, expiry, and token-hash values are not changed.

## Target document additions

For every existing `subscriptions/{uid}` document, add:

| Field      | Backfill value                                                                        |
| ---------- | ------------------------------------------------------------------------------------- |
| `tier`     | `plus` for `member_monthly`; `community` for `none`                                   |
| `startsAt` | Preserve the existing timestamp; otherwise write `null` rather than inventing history |

New backend writes include both fields, while readers continue to accept records where either is
missing. `startsAt: null` means that the historical start instant is unknown; it does not mean the
subscription started at migration time.

Documents with an unknown entitlement, a tier that conflicts with the mapping, or malformed
timestamps are conflicts and must be reported for manual review. A valid explicit Supporter record
is already migrated and is left unchanged; the migration must never create one. Paid tiers and
`startsAt` are preserved by every later merge-less lifecycle rewrite, including expiry.

## Dry run

The migration tool must default to dry-run and require an explicit `--apply` flag. Before any
write, it must:

1. Read all candidate subscription documents without modifying them.
2. Report counts for Community, Plus, explicit Supporter, already migrated, conflicts, and missing
   `startsAt`.
3. Report document ids for conflicts, but never print purchase-token hashes or other sensitive
   values.
4. Produce a local manifest containing document id, pre-migration field presence, intended tier,
   and intended `startsAt` state. Do not store raw subscription documents in the manifest.
5. Exit non-zero if any conflict exists. Applying a partial plan while conflicts remain is not
   allowed.

The dry-run count and manifest must be reviewed before `--apply`. Run against the Firebase
emulator first, then against production only through the repository's normal reviewed operator
process.

## Apply and idempotency

Apply with bounded batches below Firestore's batch limit. For each document, use a transaction or
equivalent precondition based on the version read during dry-run so concurrent subscription writes
cannot be overwritten.

The operation is idempotent:

- A correctly populated `tier` is left unchanged.
- An existing `startsAt` timestamp or explicit `null` is left unchanged.
- Re-running after success produces zero writes.
- A conflicting existing value is reported, never corrected silently.
- A failed batch can be retried from the same rules because completed documents are no-ops.

After apply, repeat dry-run. Success requires zero pending writes, zero conflicts, no newly created
Supporter assignments, and unchanged counts for legacy entitlements and `activeMember` state.

## Rollback

Keep the reviewed dry-run manifest and a Firestore export from immediately before apply. Rollback
removes only fields that the manifest proves were absent before migration:

- delete `tier` only where this migration added it;
- delete `startsAt` only where this migration added it;
- never alter a field that existed before migration;
- never alter legacy entitlement, status, platform, expiry, `activeMember`, or custom claims.

Rollback uses the same document-version preconditions and bounded batches as apply. If a document
changed after migration, stop and review it instead of overwriting concurrent data. Re-run the
dry-run in rollback mode to prove that the pre-migration field-presence state is restored. The
Firestore export is the recovery path if the manifest or targeted rollback cannot prove safety.

## Later, explicitly separate work

Supporter assignment is never performed by migration. It requires either an explicitly approved,
audited manual operation or, after provider work exists, a verified Supporter purchase. Enforcing
garage/history limits, showing the optional badge, switching Android billing away from the
retained legacy product, and enabling any capability gate are separate reviewed changes.

The backwards-compatible manual callable accepts `tier: plus | supporter` alongside
`entitlement: member_monthly`; omitting tier remains a Plus grant. A revoke omits tier so the
backend can preserve the stored paid tier and `startsAt` in the historical lifecycle record.
