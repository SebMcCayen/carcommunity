import Foundation

/// Presentation only; source geography and current authorization remain authoritative.
struct MapPresencePolicy {
    struct Frame: Equatable {
        let id: String
        let alpha: Double
        let interactive: Bool
        let placement: Int
    }
    private struct Entry {
        let from: Double
        let target: Double
        let started: TimeInterval
        let placement: Int
    }
    static let duration: TimeInterval = 0.2
    private var entries: [String: Entry] = [:]
    private var clock: TimeInterval = 0
    var trackedIDs: Set<String> { Set(entries.keys) }
    mutating func clear() { entries.removeAll() }

    mutating func sync(visible: Set<String>, allowed: Set<String>, now: TimeInterval,
                       animations: Bool = true, placements: [String: Int] = [:]) {
        clock = max(clock, now)
        let now = clock
        entries = entries.filter { allowed.contains($0.key) }
        let desired = visible.intersection(allowed)
        for id in Set(entries.keys).union(desired) {
            let previous = entries[id]
            let target = desired.contains(id) ? 1.0 : 0.0
            let placement = placements[id] ?? previous?.placement ?? 0
            if !animations {
                entries[id] = target == 0 ? nil : Entry(from: 1, target: 1, started: now, placement: placement)
            } else if let previous {
                if target > 0 && previous.placement != placement {
                    entries[id] = Entry(from: 0, target: 1, started: now, placement: placement)
                } else if previous.target != target {
                    entries[id] = Entry(from: alpha(previous, at: now), target: target, started: now, placement: placement)
                }
            } else if target > 0 {
                entries[id] = Entry(from: 0, target: 1, started: now, placement: placement)
            }
        }
        _ = frames(at: now)
    }

    mutating func frames(at now: TimeInterval) -> [Frame] {
        clock = max(clock, now)
        var result: [Frame] = []
        for id in entries.keys.sorted() {
            guard let entry = entries[id] else { continue }
            let value = alpha(entry, at: clock)
            if entry.target == 0 && value <= 0 { entries[id] = nil }
            else { result.append(Frame(id: id, alpha: value, interactive: entry.target > 0 && value > 0, placement: entry.placement)) }
        }
        return result
    }
    mutating func isAnimating(at now: TimeInterval) -> Bool {
        clock = max(clock, now)
        return entries.values.contains { clock - $0.started < Self.duration && $0.from != $0.target }
    }
    private func alpha(_ entry: Entry, at now: TimeInterval) -> Double {
        let t = max(0, min(1, (now - entry.started) / Self.duration))
        return entry.from + (entry.target - entry.from) * t
    }
}
