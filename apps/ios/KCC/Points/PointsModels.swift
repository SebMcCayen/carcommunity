import Foundation

/// One immutable row in `pointsLedger/{uid}/entries`.
struct PointsEntry: Equatable, Sendable {
    let id: String
    /// Signed: positive is a credit and negative is a debit.
    let amount: Int64
    let balanceAfter: Int64?
    let description: String
    let createdAt: Date?

    static func fromMap(id: String, map: [String: Any]) -> PointsEntry? {
        guard let amount = integer(map["amount"]) else { return nil }
        return PointsEntry(
            id: id,
            amount: amount,
            balanceAfter: integer(map["balanceAfter"]),
            description: map["description"] as? String ?? "",
            createdAt: map["createdAt"] as? Date
        )
    }

    private static func integer(_ value: Any?) -> Int64? {
        guard let number = value as? NSNumber,
              CFGetTypeID(number) != CFBooleanGetTypeID()
        else { return nil }
        return number.int64Value
    }
}

enum Points {
    static let profileHighlightCount = 4

    /// Newest first, with undated rows last. Equal dates retain server order.
    static func sortedForList(_ entries: [PointsEntry]) -> [PointsEntry] {
        entries.enumerated().sorted { lhs, rhs in
            switch (lhs.element.createdAt, rhs.element.createdAt) {
            case let (left?, right?):
                return left == right ? lhs.offset < rhs.offset : left > right
            case (_?, nil): return true
            case (nil, _?): return false
            case (nil, nil): return lhs.offset < rhs.offset
            }
        }.map(\.element)
    }

    /// The newest credits explaining the profile balance. Debits and zero-value
    /// corrections belong only in the complete statement.
    static func recentEarnings(
        _ entries: [PointsEntry],
        limit: Int = profileHighlightCount
    ) -> [PointsEntry] {
        guard limit > 0 else { return [] }
        return Array(sortedForList(entries.filter { $0.amount > 0 }).prefix(limit))
    }
}

enum PointsEntriesSnapshot: Equatable, Sendable {
    case loaded([PointsEntry])
    case failed(code: String?)
}

enum PointsEntriesUiState: Equatable, Sendable {
    case loading
    case unavailable
    case loaded([PointsEntry])
    case failed(code: String?)
}
