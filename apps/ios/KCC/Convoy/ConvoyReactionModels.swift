import Foundation

/// The transient convoy reactions accepted by `convoy-sendReaction`.
enum ConvoyReactionKind: String, CaseIterable, Hashable, Identifiable, Sendable {
    case police
    case hello
    case followMe = "follow_me"

    var id: String { rawValue }

    /// Client-side mirror of the server cooldown. The callable remains authoritative.
    var cooldownMilliseconds: Int64 {
        switch self {
        case .police: 60_000
        case .hello: 15_000
        case .followMe: 30_000
        }
    }
}

struct ConvoyReactionEvent: Equatable, Identifiable, Sendable {
    let id: String
    let kind: ConvoyReactionKind
    let senderUid: String
    let senderName: String?
    let createdAt: Date
}

enum ConvoyReactionSendResult: Equatable, Sendable {
    case sent
    case rateLimited(retryAfterMilliseconds: Int64)
    case failed
}

/// Immutable, per-kind cooldown state used by the reaction controls.
struct ConvoyReactionCooldownState: Equatable, Sendable {
    private var readyAtMilliseconds: [ConvoyReactionKind: Int64] = [:]

    func remainingMilliseconds(
        for kind: ConvoyReactionKind,
        nowMilliseconds: Int64
    ) -> Int64 {
        max((readyAtMilliseconds[kind] ?? 0) - nowMilliseconds, 0)
    }

    func isReady(_ kind: ConvoyReactionKind, nowMilliseconds: Int64) -> Bool {
        remainingMilliseconds(for: kind, nowMilliseconds: nowMilliseconds) == 0
    }

    func recordingSend(
        _ kind: ConvoyReactionKind,
        atMilliseconds: Int64
    ) -> Self {
        var next = self
        next.readyAtMilliseconds[kind] = atMilliseconds + kind.cooldownMilliseconds
        return next
    }

    func applyingServerCooldown(
        _ kind: ConvoyReactionKind,
        retryAfterMilliseconds: Int64,
        nowMilliseconds: Int64
    ) -> Self {
        guard retryAfterMilliseconds > 0 else { return clearing(kind) }
        var next = self
        next.readyAtMilliseconds[kind] = nowMilliseconds + retryAfterMilliseconds
        return next
    }

    func clearing(_ kind: ConvoyReactionKind) -> Self {
        var next = self
        next.readyAtMilliseconds.removeValue(forKey: kind)
        return next
    }
}

enum ConvoyReactionWire {
    static func sendPayload(
        convoyId: String,
        kind: ConvoyReactionKind,
        clientId: String
    ) -> [String: String] {
        [
            "convoyId": convoyId,
            "kind": kind.rawValue,
            "clientId": clientId,
        ]
    }

    static func retryAfterMilliseconds(from details: Any?) -> Int64 {
        guard let raw = (details as? [String: Any])?["retryAfterMs"] as? NSNumber else {
            return 0
        }
        return max(raw.int64Value, 0)
    }
}
