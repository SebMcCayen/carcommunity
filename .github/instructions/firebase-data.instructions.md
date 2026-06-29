---
applyTo: "functions/**"
---

# Firebase Data Instructions

## Choosing between Firestore and Realtime Database

| Use Firestore for | Use Realtime Database for |
|---|---|
| Durable structured data | Ephemeral realtime state |
| Users, entitlements, moderation, events, chat messages, saved drives, partners | Live location latest-state, active drive session state, presence |
| Complex queries (with indexes) | Simple key-path reads with low-latency fan-out |

Do not store long-term history or user-identifiable location data in Realtime Database.

## Firestore data model rules

- Design collections around read patterns. Firestore does not support joins; denormalize where needed.
- Use subcollections for one-to-many relationships that are always read in context of the parent.
- Use top-level collections for entities queried independently of a parent.
- Define composite indexes in `firestore.indexes.json` for any query using multiple field filters or ordering.
- Use server timestamps (`FieldValue.serverTimestamp()`) for `createdAt` and `updatedAt` fields.
- Never store computed entitlement state in documents that clients can write to.

## Live location (Realtime Database)

- Store only the latest location per user during an active sharing session.
- Apply a short TTL: the record must be removed when the session ends or expires.
- "Hide me now" must immediately remove the location record and close the session.
- Do not create a historical location timeline from live location data.
- Realtime Database Security Rules must restrict writes to the authenticated owner and reads to verified active members only.

## Transactions and consistency

- Use Firestore transactions (`runTransaction`) for operations that must read and conditionally write the same document.
- Use `FieldValue.increment` for counters (Kronpoäng balance, aggregate metrics) to avoid read-modify-write races.
- Cloud Functions triggered by Firestore writes must be idempotent; use idempotency keys for operations that must not run twice.

## Pagination

- All list queries must use Firestore cursor-based pagination (`startAfter`, `limit`).
- Do not return unbounded collections to clients.

## Data minimization

- Store only the fields needed for the feature.
- Do not store raw GPS coordinates in long-term Firestore documents.
- Aggregate partner analytics server-side; never store individual user tracking data in partner-accessible paths.

## Cloud Storage for Firebase

- Use Cloud Storage for user-uploaded files (profile images, drive attachments).
- Apply Storage Security Rules to restrict uploads to authenticated users and downloads to authorized readers.
- Never store credentials, tokens, or private keys in Cloud Storage buckets.
