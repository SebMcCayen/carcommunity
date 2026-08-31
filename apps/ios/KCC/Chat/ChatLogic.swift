import Foundation

/// Pure chat-channel logic: draft validation, live/older/pending merges, unread
/// derivation, callable-payload parsing, block filtering, and the reply flag —
/// the iOS port of the pure objects in Android's `ChatChannels.kt`
/// (`ChannelThread`, `ChannelResponseParser`, `channelIsoToMillisOrNull`) plus
/// `blocking/BlockVisibility.kt` and the `chatReplies` gate in
/// `config/FeatureFlags.kt`. No Firebase / SwiftUI types, so every rule is
/// unit-testable off-device.

// MARK: - ISO parsing

/// Shared ISO-8601 formatter for callable message rows. Fractional seconds are
/// optional in the backend payload, so both variants are tried. Static so the
/// (relatively expensive) formatter is built once.
enum ChannelTime {
    nonisolated(unsafe) private static let withFractional: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()
    nonisolated(unsafe) private static let withoutFractional: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()

    /// Best-effort ISO-8601 → `Date` for callable message rows (used only for
    /// chronological ordering; a parse failure just sorts the message last).
    /// Android: `channelIsoToMillisOrNull`.
    static func parseIso(_ iso: String?) -> Date? {
        guard let iso, !iso.isEmpty else { return nil }
        return withFractional.date(from: iso) ?? withoutFractional.date(from: iso)
    }

    /// The ISO-8601 spelling of a `Date` (used to derive an older-page cursor
    /// from a live-listener message, which arrives with a `Timestamp` rather
    /// than an ISO string). Always emits fractional seconds so the cursor is
    /// exact.
    static func isoString(_ date: Date) -> String {
        withFractional.string(from: date)
    }
}

// MARK: - Message-thread helpers

/// Pure message-thread helpers (merge of the live window with paged older
/// messages, unread derivation). Android: `ChannelThread`.
enum ChannelThread {
    /// Whether a draft is within 1...``channelMessageMaxLength`` after trimming
    /// whitespace. Android: `ChannelThread.isSendable`.
    static func isSendable(_ text: String) -> Bool {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        return (1...channelMessageMaxLength).contains(trimmed.count)
    }

    /// A total order over messages: by instant (nil last), tie-break by id, so
    /// the sort is stable and deterministic. Mirrors Android's
    /// `compareBy({ createdAtMillis ?: Long.MAX_VALUE }, { id })`.
    private static func isOrderedBefore(_ lhs: ChannelMessage, _ rhs: ChannelMessage) -> Bool {
        switch (lhs.createdAt, rhs.createdAt) {
        case let (l?, r?):
            if l != r { return l < r }
            return lhs.id < rhs.id
        case (nil, nil):
            return lhs.id < rhs.id
        case (nil, .some):
            return false  // nil sorts last
        case (.some, nil):
            return true
        }
    }

    /// Merges the live newest-window with accumulated older pages into a single
    /// chronological (oldest-first) list, de-duplicated by id. Later duplicates
    /// win, so a message present in both the live window and an older page keeps
    /// its live copy. Android: `ChannelThread.merge`.
    static func merge(older: [ChannelMessage], live: [ChannelMessage]) -> [ChannelMessage] {
        var byId: [String: ChannelMessage] = Dictionary(minimumCapacity: older.count + live.count)
        for m in older { byId[m.id] = m }
        for m in live { byId[m.id] = m }
        return byId.values.sorted(by: isOrderedBefore)
    }

    /// The pagination cursor for the next older page: the earliest message's ISO
    /// createdAt. Android: `ChannelThread.oldestCursor`.
    static func oldestCursor(_ messages: [ChannelMessage]) -> String? {
        messages
            .min { lhs, rhs in
                (lhs.createdAt ?? .distantFuture) < (rhs.createdAt ?? .distantFuture)
            }?
            .createdAtIso
    }

    /// Merges the server-sourced messages (``merge(older:live:)``) with the
    /// caller's still-`pending` optimistic bubbles for display. A pending bubble
    /// whose id (its clientId) has ALREADY arrived in the server set is dropped:
    /// the delivered document supersedes it, so an optimistic send and its
    /// snapshot render as exactly ONE message. Android:
    /// `ChannelThread.mergeWithPending`.
    static func mergeWithPending(
        older: [ChannelMessage],
        live: [ChannelMessage],
        pending: [ChannelMessage]
    ) -> [ChannelMessage] {
        let real = merge(older: older, live: live)
        if pending.isEmpty { return real }
        let realIds = Set(real.map(\.id))
        let stillPending = pending.filter { !realIds.contains($0.id) }
        if stillPending.isEmpty { return real }
        return (real + stillPending).sorted(by: isOrderedBefore)
    }

    /// True when the newest message is unread for the caller: it exists, was NOT
    /// sent by the caller, and is newer than the caller's `lastReadAt` marker
    /// (nil marker = never read → any message from someone else is unread).
    /// Android: `ChannelThread.hasUnread`.
    static func hasUnread(
        newest: ChannelMessage?,
        callerUid: String,
        lastReadAt: Date?
    ) -> Bool {
        guard let newest else { return false }
        if newest.senderUid == callerUid { return false }
        guard let createdAt = newest.createdAt else { return false }
        return lastReadAt == nil || createdAt > lastReadAt!
    }

    /// How many of `window` are unread for the caller — the counting form of
    /// ``hasUnread(newest:callerUid:lastReadAt:)`` for surfaces that show a
    /// number (the convoy bar badge). A message with no parseable createdAt is
    /// not counted. Android: `ChannelThread.unreadCount`.
    static func unreadCount(
        window: [ChannelMessage],
        callerUid: String,
        lastReadAt: Date?
    ) -> Int {
        window.reduce(0) { count, message in
            guard message.senderUid != callerUid, let createdAt = message.createdAt else {
                return count
            }
            let unread = lastReadAt == nil || createdAt > lastReadAt!
            return count + (unread ? 1 : 0)
        }
    }

    /// True when ANY convoy has an unread message: some convoy whose newest
    /// delivered-message time (`latestByConvoy`, maintained server-side by the
    /// `convoyChat-post` fan-out) is later than the caller's last-read marker
    /// for that same convoy (`lastReadByConvoy`), or that has no marker at all.
    /// Android: `ChannelThread.anyConvoyUnread`.
    static func anyConvoyUnread(
        latestByConvoy: [String: Date],
        lastReadByConvoy: [String: Date]
    ) -> Bool {
        latestByConvoy.contains { convoyId, latest in
            guard let lastRead = lastReadByConvoy[convoyId] else { return true }
            return latest > lastRead
        }
    }
}

// MARK: - Blocking

/// Client-side blocking filter for the channel LIVE windows. A Firestore rule
/// cannot filter a list query per-document, so the community/convoy live
/// listeners are filtered against the caller's mutual-hidden set — the union of
/// "uids the caller blocked" and "uids that blocked the caller", read once per
/// session from the owner-only mirror `blockVisibility/{uid}.hiddenUids`
/// (maintained by the `blocking-onBlockWrite` trigger). Older pages are
/// filtered SERVER-side by the `*-list` callables. Android:
/// `blocking/BlockVisibility.kt`.
enum BlockVisibility {
    /// Drops messages authored by a hidden uid. A message with an EMPTY author
    /// cannot occur (the parsers drop such docs), but were one to, it would be
    /// kept — a malformed doc is not a block-evasion route. Android:
    /// `BlockVisibility.filterHiddenAuthors`.
    static func filterHiddenAuthors(
        _ messages: [ChannelMessage],
        hidden: Set<String>
    ) -> [ChannelMessage] {
        if hidden.isEmpty { return messages }
        return messages.filter { !hidden.contains($0.senderUid) }
    }

    /// The newest message whose author is NOT hidden, from a newest-first
    /// window — so the unread dot never lights for a message the user will
    /// never be shown. Android: `BlockVisibility.newestVisible`.
    static func newestVisible(
        _ newestFirstWindow: [ChannelMessage],
        hidden: Set<String>
    ) -> ChannelMessage? {
        newestFirstWindow.first { !hidden.contains($0.senderUid) }
    }
}

// MARK: - Feature flags

/// The chat feature flags the channels gate on, mirrored from
/// contracts/features/feature-flags.json (Android: `config/FeatureFlags.kt`).
/// The value's source of truth is the backend `config/featureFlags` document; a
/// live flag-fetch seam is a later slice, so — exactly like Android's enum
/// default — the value falls back to the contract default until then.
enum ChatFeatureFlags {
    /// `chatReplies` (default OFF). While off, the reply affordance stays hidden
    /// and no `replyToMessageId` is sent (the backend also ignores it), so the
    /// feature is dark end-to-end. Gates only reply PROCESSING — ordinary
    /// messages are unaffected. contracts/features/feature-flags.json.
    static let chatRepliesDefault = false
}

// MARK: - Callable payload parsing

/// Pure parsing of the `*-post` / `*-list` callable response payloads (plain
/// dictionaries/arrays as the Firebase Functions SDK deserializes JSON).
/// Missing/blank required fields drop the row rather than crash, so a partial
/// backend response degrades gracefully. Callable responses carry ISO-8601
/// timestamp strings (the live Firestore listeners, which carry Firebase
/// `Timestamp`s, are parsed in the Firebase repositories). Android:
/// `ChannelResponseParser`.
enum ChannelResponseParser {
    /// Maps a `*-post` success payload. A missing messageId fails the send. A
    /// missing/malformed `mentionedUids` parses as the empty accepted set — the
    /// message itself landed, so a mention-echo we can't read must not fail it.
    /// Android: `parsePostSuccess`.
    static func parsePostSuccess(_ data: Any?) -> ChannelSendResult {
        let map = data as? [String: Any]
        let messageId = (map?["messageId"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        guard let messageId else { return .failed(.generic) }
        return .sent(messageId: messageId, mentionedUids: parseMentionedUids(map?["mentionedUids"]))
    }

    /// `mentionedUids` from a payload/doc: non-blank strings only, deduplicated
    /// (order preserved). Android: `parseMentionedUids`.
    static func parseMentionedUids(_ raw: Any?) -> [String] {
        guard let array = raw as? [Any] else { return [] }
        var seen = Set<String>()
        var result: [String] = []
        for element in array {
            guard let uid = element as? String, !uid.isEmpty, !seen.contains(uid) else { continue }
            seen.insert(uid)
            result.append(uid)
        }
        return result
    }

    /// Maps a `*-list` success payload into an older-page. Android:
    /// `parseMessagesPage`.
    static func parseMessagesPage(_ data: Any?) -> ChannelMessagesPage {
        let map = data as? [String: Any]
        let rawMessages = map?["messages"] as? [Any] ?? []
        let messages = rawMessages.compactMap(parseMessage)
        let nextBefore = (map?["nextBefore"] as? String).flatMap { $0.isEmpty ? nil : $0 }
        let hasMore = map?["hasMore"] as? Bool ?? false
        return ChannelMessagesPage(messages: messages, nextBefore: nextBefore, hasMore: hasMore)
    }

    /// The `lastReadAt` marker from a `communityChat-list` payload (or nil).
    /// Android: `parseLastReadAt`.
    static func parseLastReadAt(_ data: Any?) -> String? {
        ((data as? [String: Any])?["lastReadAt"] as? String).flatMap { $0.isEmpty ? nil : $0 }
    }

    /// Parses one message row from a callable payload (ISO createdAt). A missing
    /// OR blank `id`/`senderUid` drops the whole row; `text` degrades to `""`;
    /// optional profile fields stay nil; an unparseable `createdAt` keeps the
    /// message but with a nil instant. Android: `parseMessage`.
    static func parseMessage(_ raw: Any?) -> ChannelMessage? {
        guard let map = raw as? [String: Any] else { return nil }
        guard let id = (map["id"] as? String), !id.isEmpty else { return nil }
        guard let senderUid = (map["senderUid"] as? String), !senderUid.isEmpty else { return nil }
        let iso = map["createdAt"] as? String
        return ChannelMessage(
            id: id,
            senderUid: senderUid,
            text: map["text"] as? String ?? "",
            senderDisplayName: map["senderDisplayName"] as? String,
            senderAvatarPath: map["senderAvatarPath"] as? String,
            createdAt: ChannelTime.parseIso(iso),
            createdAtIso: iso,
            mentionedUids: parseMentionedUids(map["mentionedUids"]),
            clientId: (map["clientId"] as? String).flatMap { $0.isEmpty ? nil : $0 },
            replyTo: parseReplyTo(map["replyTo"])
        )
    }

    /// Reads a stored/echoed `replyTo` map into ``ChannelReplyTo``, defensively
    /// coalescing missing or non-string fields. A snapshot with no usable
    /// messageId or senderUid is dropped rather than rendered as a half-quote.
    /// Android: `parseReplyTo`.
    static func parseReplyTo(_ raw: Any?) -> ChannelReplyTo? {
        guard let map = raw as? [String: Any] else { return nil }
        guard let messageId = (map["messageId"] as? String), !messageId.isEmpty else { return nil }
        guard let senderUid = (map["senderUid"] as? String), !senderUid.isEmpty else { return nil }
        return ChannelReplyTo(
            messageId: messageId,
            senderUid: senderUid,
            senderDisplayName: map["senderDisplayName"] as? String,
            textPreview: map["textPreview"] as? String ?? ""
        )
    }
}
