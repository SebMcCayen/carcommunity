# Language-neutral contracts

This directory is the canonical, language-neutral definition of the shapes, codes, and names that all Kungsbacka Car Community platforms share: **iOS (Swift)**, **Android (Kotlin)**, **backend (Cloud Functions, TypeScript)**, and **admin web (TypeScript)**.

It exists because the TypeScript `packages/shared` workspace cannot be consumed by native Swift/Kotlin apps. `packages/shared` remains in place for backend and admin web; `contracts/` adds the native-consumable equivalent and is the cross-platform source of truth. When the two disagree, `contracts/` wins and `packages/shared` must be updated to match.

See [docs/migration/native-firebase-migration-plan.md](../docs/migration/native-firebase-migration-plan.md), Phase 2, for the full rationale.

## Structure

| Path                              | Contents                                                       | Status                                                                                                   |
| --------------------------------- | -------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------- |
| `schemas/`                        | JSON Schema (draft 2020-12) for shared request/response shapes | 🟡 auth, user profile (PR 2a); live location, events/RSVP (PR 2b); further domains added as they migrate |
| `errors/errors.json`              | Canonical machine-readable error codes                         | ✅ Seeded (PR 2a)                                                                                        |
| `functions/functions.json`        | Callable function names, access levels, and status             | ✅ Seeded (PR 2b)                                                                                        |
| `features/feature-flags.json`     | Feature flag key names and defaults                            | ✅ Seeded (PR 2b)                                                                                        |
| `localization/sv.json`, `en.json` | Source localization strings                                    | 🔲 Planned (PR 2c)                                                                                       |
| `design-tokens/tokens.json`       | Design token values                                            | 🔲 Planned (PR 2d)                                                                                       |

## Conventions

- **JSON Schema draft 2020-12.** Every file in `schemas/` declares `"$schema": "https://json-schema.org/draft/2020-12/schema"`.
- **Shared enums live in `schemas/common.schema.json`** (`userRole`, `userStatus`, `subscriptionEntitlement`, `authProvider`, timestamp primitives) and are referenced with relative `$ref`s. Do not redeclare them per-schema.
- **Timestamps are ISO 8601 strings** in JSON representations. Firestore stores native `Timestamp` values; the contract describes the serialized form. Timestamps are backend-written (`FieldValue.serverTimestamp()`) — never trust client clocks.
- **Backend is the source of truth.** Nothing in these contracts permits a client to assert roles, subscription status, moderation state, or access decisions.
- **Error codes** use the Firebase `HttpsError` vocabulary (see `errors/errors.json`). Clients branch on `code`, never on the human-readable message.

## How platforms consume this

- **iOS / Android:** generate or hand-write model types from `schemas/`, and reference `errors/errors.json` for error handling. Enum values must match exactly (string case included).
- **Cloud Functions / admin web:** continue to import from `@carcommunity/shared`; when contracts change, update `packages/shared` in the same PR.
- **CI** validates every schema compiles and that registry files validate against their meta-schemas (see `.github/workflows/ci.yml`, `contracts` job).

## Validating locally

```bash
npm install --no-save ajv-cli@5 ajv-formats@3
npx ajv compile --spec=draft2020 -c ajv-formats -s contracts/schemas/common.schema.json
npx ajv compile --spec=draft2020 -c ajv-formats \
  -r contracts/schemas/common.schema.json \
  -s contracts/schemas/auth.schema.json \
  -s contracts/schemas/user-profile.schema.json \
  -s contracts/schemas/live-location.schema.json \
  -s contracts/schemas/events.schema.json
npx ajv validate --spec=draft2020 -c ajv-formats \
  -s contracts/errors/errors.schema.json -d contracts/errors/errors.json
npx ajv validate --spec=draft2020 -c ajv-formats \
  -s contracts/functions/functions.schema.json -d contracts/functions/functions.json
npx ajv validate --spec=draft2020 -c ajv-formats \
  -s contracts/features/feature-flags.schema.json -d contracts/features/feature-flags.json
jq -r '.errorCodes[].code' contracts/errors/errors.json | sort | uniq -d
jq -r '.functions[].name' contracts/functions/functions.json | sort | uniq -d
jq -r '.flags[].key' contracts/features/feature-flags.json | sort | uniq -d
# (the three jq commands must print nothing — duplicates fail CI)
```

## Changing a contract

1. Open a PR that changes the contract file(s).
2. Update `packages/shared` in the same PR if the change affects TypeScript consumers.
3. Contract changes are breaking by default — call out compatibility implications in the PR description.
