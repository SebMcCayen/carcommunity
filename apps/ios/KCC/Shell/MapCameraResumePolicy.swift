import Foundation

/// Camera-only lifecycle and course policy. Recording and shared positions are untouched.
struct MapCameraResumePolicy {
    static let duration: TimeInterval = 0.65
    static let maximumFixAge: TimeInterval = 5
    static let minimumCourseSpeed = 1.4
    private var visible = false
    private var lastFixAt: Date?
    private(set) var pending = false

    mutating func visibilityChanged(visible: Bool, following: Bool) {
        guard self.visible != visible else { return }
        self.visible = visible
        pending = visible && following
    }

    mutating func requestReturn() { if visible { pending = true } }
    mutating func cancel() { pending = false }

    /// A cached pre-background sample cannot consume the one return transition.
    mutating func takeReturn(fixAt: Date, now: Date) -> Bool {
        guard visible, pending, Self.isFresh(fixAt: fixAt, now: now),
              lastFixAt.map({ fixAt > $0 }) ?? true else { return false }
        pending = false
        lastFixAt = fixAt
        return true
    }

    mutating func recordFix(at date: Date) {
        if lastFixAt.map({ date > $0 }) ?? true { lastFixAt = date }
    }

    static func isFresh(fixAt: Date, now: Date) -> Bool {
        let age = now.timeIntervalSince(fixAt)
        return age >= 0 && age <= maximumFixAge
    }

    static func course(heading: Double?, speed: Double?, speedAccuracy: Double? = nil, holding current: Double) -> Double {
        guard let heading, heading.isFinite,
              let speed, speed.isFinite, speed >= minimumCourseSpeed,
              speedAccuracy.map({ $0.isFinite && $0 >= 0 && speed - $0 >= minimumCourseSpeed }) ?? true else { return current }
        return (heading.truncatingRemainder(dividingBy: 360) + 360).truncatingRemainder(dividingBy: 360)
    }

    static func shortestBearing(from: Double, to: Double) -> Double {
        let delta = (to - from + 540).truncatingRemainder(dividingBy: 360) - 180
        return from + delta
    }
}
