import Foundation

enum WelcomeStep: Int, CaseIterable, Sendable {
    case welcome, map, membership, profile
    var position: Int { rawValue + 1 }
    var isLast: Bool { self == .profile }
    var next: WelcomeStep { WelcomeStep(rawValue: min(rawValue + 1, Self.allCases.count - 1)) ?? self }
}

protocol WelcomeStoring: Sendable {
    func hasSeenWelcome(uid: String) -> Bool
    func markSeen(uid: String)
}

struct WelcomeStore: WelcomeStoring, @unchecked Sendable {
    private let defaults: UserDefaults
    init(defaults: UserDefaults = .standard) { self.defaults = defaults }
    func hasSeenWelcome(uid: String) -> Bool { defaults.bool(forKey: key(uid)) }
    func markSeen(uid: String) { defaults.set(true, forKey: key(uid)) }
    private func key(_ uid: String) -> String { "firstLoginWelcome.seen.\(uid)" }
}
