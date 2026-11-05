# Community chat screen and send protection

The Community overlay supplements, and does not replace or modify, the shared
`chat-flood-v1` account budget. Only `communityChat-post` uses the overlay. Both
native clients and legacy clients receive the existing `resource-exhausted` /
`chat-rate-limited` contract and shared progressive pauses (5s, 30s, 2m, 10m).

- A Community account starts with two send slots, replenishing one every five
  seconds up to two. The third identical trimmed/NFC-normalized message within a
  rolling 30 seconds is refused, including emoji-only messages. CRLF and LF compare
  equally; original stored text is unchanged. There is no emoji prohibition.
- `communityChatSendGuards/{uid}` is backend-only. It stores bounded refill state
  and at most eight SHA-256 fingerprints/timestamps, not message text. Entries
  outside 30 seconds are ignored/pruned on the next evaluation; physical cleanup
  follows canonical Auth deletion. No new index, TTL or rules deployment is needed.
- Account authorization, moderation tombstones and accepted-ID replay precede
  both budgets. All reads precede writes; both guards and the new message are
  transactional. Denials commit the shared pause before throwing, do not charge
  send tokens or create messages/notifications, and active pauses write neither
  guard. Retries of accepted IDs remain free; changed IDs are new sends.
- iOS and Android group only adjacent **delivered** messages from one sender within
  30 seconds of the first message, inside one local calendar day. They display the
  two newest messages, one sender header and a button exposing the earlier
  messages in original order. Other senders, unknown timestamps and pending or
  failed messages break groups. Every expanded message retains its own actions.
- Community message bodies collapse after four rendered text lines, with a manual
  Show more/Show less control. Text, Unicode sequences and links are preserved.
  Dynamic Type/font scaling changes wrapping, never the underlying content.
- Both platforms consume the existing server-relative wait contract. iOS adds a
  shared per-account monotonic cooldown for Community/convoy, disabling send,
  quick emoji and retry during the pause. Expiry never resends automatically.
  Refused typed drafts (including their reply target) recover only while the
  cleared draft is still owned by that send; newer edits/choices win. Retry uses
  the original client ID. Untagged errors retain generic fallback behavior.

Local verification covers the policy, transaction staging, full backend unit
suite and TypeScript. Native regression tests cover grouping/order, four-line
Android rendering, expansion, cooldown metadata and draft recovery. The existing
GitHub Android/iOS workflows run native compilation and native UI tests; Linux
source/unit evidence is not simulator or physical-device acceptance.

Release requires the reviewed Community Functions code and updated iOS/Android
clients. Existing clients get server enforcement but retain their old rendering.
No production deployment, merge, signing or app distribution is part of this PR.
