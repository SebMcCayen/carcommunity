import Foundation

/// Saved-drives domain — the iOS port of Android's `drives/SavedDrive.kt`,
/// restricted to the HISTORY read slice: the backend computes all stats
/// server-side (the `drives-save` callable) and the client only reads
/// owner-scoped `rides/{rideId}` documents
/// (contracts/schemas/saved-drives.schema.json `ride`). Recording, save,
/// delete, share, and the route replay arrive with the recording slice.
/// Pure Swift so it is unit-testable and Firebase-free.
struct SavedDrive: Equatable, Sendable, Identifiable {
    /// The `rides/{rideId}` document id.
    let id: String
    let title: String?
    /// Server-computed; nil for summary-only saves (no route points).
    let distanceMeters: Double?
    let durationSeconds: Int
    let averageSpeedMetersPerSecond: Double?
    let startedAt: Date?
    let endedAt: Date?
    let createdAt: Date?
    /// The drive's highest plausible speed (m/s), server-derived at save time
    /// with the same >200 km/h GPS-glitch filter distance uses.
    ///
    /// Nil for drives saved before the field existed (there is no backfill)
    /// and for summary-only saves — i.e. "unknown", NOT "zero". Render via
    /// ``DriveFormatters/formatSpeed(_:)``, which turns nil into the
    /// missing-value dash; a 0 here would be a claim the car never moved.
    ///
    /// Presentation rule, and it is a rule rather than a preference: this is
    /// a neutral fact, shown at the same visual weight as distance and
    /// duration. No record, no personal best, no comparison between drives,
    /// no colour or emphasis that rewards a bigger number
    /// (docs/gamification-system.md C1; Android `SavedDrive` carries the
    /// same rule).
    let maxSpeedMetersPerSecond: Double?
    /// Storage path of the car this drive was driven in (the denormalized
    /// cover photo), so the History card can draw a round photo of the car
    /// with no extra vehicle read. Nil for drives saved before the field
    /// existed, and for drives with no car — the card then shows no photo.
    let carImagePath: String?
    /// The OTHER members of the convoy this drive was part of, denormalized
    /// onto the ride document at save time so the History card can show who
    /// you drove with — no extra reads. Empty for a solo drive, for drives
    /// saved before the field existed (no backfill), and for the server-side
    /// convoy finalize baseline. The card shows the row only when non-empty.
    let convoyMembers: [ConvoyDriveMember]

    /// Decodes one `rides/{rideId}` document's fields, tolerantly: a doc
    /// without the required `durationSeconds` is dropped (Android's
    /// `toSavedDrive` posture — a ride with no duration cannot render a
    /// card), every optional field degrades to nil independently, and a
    /// malformed `convoyMembers` array degrades to the members it could
    /// read. Absent and null read identically (both are the placeholder
    /// path — the schema documents pre-2026-07 drives simply lack several
    /// keys, with no backfill).
    ///
    /// - Parameter date: coerces a raw Firestore value to a `Date` — the
    ///   caller-supplied seam that keeps this file free of the Firestore
    ///   `Timestamp` type (the repository passes
    ///   `{ ($0 as? Timestamp)?.dateValue() }`; tests pass `{ $0 as? Date }`).
    static func fromMap(
        id: String,
        map: [String: Any],
        date: (Any?) -> Date?
    ) -> SavedDrive? {
        guard let duration = (map["durationSeconds"] as? NSNumber)?.intValue else {
            return nil
        }
        return SavedDrive(
            id: id,
            title: map["title"] as? String,
            distanceMeters: (map["distanceMeters"] as? NSNumber)?.doubleValue,
            durationSeconds: duration,
            averageSpeedMetersPerSecond:
                (map["averageSpeedMetersPerSecond"] as? NSNumber)?.doubleValue,
            startedAt: date(map["startedAt"]),
            endedAt: date(map["endedAt"]),
            createdAt: date(map["createdAt"]),
            maxSpeedMetersPerSecond: (map["maxSpeedMetersPerSecond"] as? NSNumber)?.doubleValue,
            carImagePath: map["carImagePath"] as? String,
            convoyMembers: ConvoyDriveMembers.parse(map["convoyMembers"])
        )
    }
}

/// One other member of the convoy a saved drive belonged to, as denormalized
/// onto the ride document — Android's `ConvoyDriveMember`. `avatarPath` is
/// the member's profile-avatar Storage path (what the convoy screens render),
/// NOT their car photo; nullable name/avatar mirror the roster, and the row
/// falls back to the neutral "member" label.
struct ConvoyDriveMember: Equatable, Sendable {
    let uid: String
    let displayName: String?
    let avatarPath: String?
}

/// The read half of Android's `ConvoyDriveMembers` wire-shape helper: parses
/// the stored `convoyMembers` array back into the domain. (The write half —
/// `toRequestList` for the `drives-save` payload — arrives with the
/// recording slice.) Pure so the tolerance rules are unit-testable without
/// Firebase and match the backend
/// (functions/src/drives/drives-core.ts convoyMembers schema).
enum ConvoyDriveMembers {
    /// Backend CONVOY_MEMBERS_MAX parity — a hard cap so a corrupt or
    /// oversized array can never bloat the UI.
    static let maxMembers = 24

    /// Parses the stored `convoyMembers` array, dropping any malformed entry
    /// (missing/blank uid, non-map) and de-duplicating by uid so a corrupt or
    /// legacy document never crashes the History list — it just shows the
    /// members it could read. Blank name/avatar normalize to nil (the row's
    /// fallback), never the empty string. Anything that is not an array
    /// (absent, null, scalar) parses as no members.
    static func parse(_ raw: Any?) -> [ConvoyDriveMember] {
        guard let list = raw as? [Any] else { return [] }
        var seen = Set<String>()
        var members: [ConvoyDriveMember] = []
        for entry in list {
            guard members.count < maxMembers else { break }
            guard let map = entry as? [String: Any],
                let uid = nonBlank(map["uid"] as? String),
                seen.insert(uid).inserted
            else { continue }
            members.append(
                ConvoyDriveMember(
                    uid: uid,
                    displayName: nonBlank(map["displayName"] as? String),
                    avatarPath: nonBlank(map["avatarPath"] as? String)
                )
            )
        }
        return members
    }

    /// The members' names, comma-joined, for the History card's "drove with"
    /// line — each member's display name or `unknownLabel` when it has none,
    /// so a missing name reads as the neutral "member" rather than a blank.
    /// Empty string for no members (the caller then renders nothing).
    static func joinedNames(_ members: [ConvoyDriveMember], unknownLabel: String) -> String {
        members
            .map { member in
                if let name = nonBlank(member.displayName) { return name }
                return unknownLabel
            }
            .joined(separator: ", ")
    }

    private static func nonBlank(_ value: String?) -> String? {
        guard let value, !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        else { return nil }
        return value
    }
}

/// Pure saved-drives list logic shared by the repository, coordinator, and
/// panel — Android's `SavedDrives`.
enum SavedDrives {
    /// Newest saved first; undated drives sort last, original order preserved
    /// among ties (Android's `sortedForList`, with the explicit index
    /// tie-break the events port established so stability never leans on an
    /// undocumented stdlib property).
    static func sortedForList(_ drives: [SavedDrive]) -> [SavedDrive] {
        drives.enumerated()
            .sorted { lhs, rhs in
                switch (lhs.element.createdAt, rhs.element.createdAt) {
                case let (left?, right?):
                    if left != right { return left > right }
                    return lhs.offset < rhs.offset
                case (nil, nil):
                    return lhs.offset < rhs.offset
                case (nil, .some):
                    return false
                case (.some, nil):
                    return true
                }
            }
            .map(\.element)
    }
}

/// Pure, locale-stable display formatters for drive stats — Android's
/// `DriveFormatters`. Unit labels (km, m, h, min, s, km/h) are
/// numeric-adjacent and identical in sv/en, so they live here rather than in
/// the string catalog — a deliberate cross-platform decision documented in
/// Android's `DriveFormatters` (SavedDrive.kt), which both clients follow so
/// the same drive renders the same on both. The field LABELS come from the
/// savedDrives.* keys.
///
/// The catalog's only unit keys (`addressSearch.unit*`) are deliberately NOT
/// borrowed: they belong to the navigation vocabulary, cover neither seconds
/// nor km/h, and render Swedish hours as "tim" where the drive cards render
/// "h" on both platforms — using them would diverge from Android's cards.
/// Localizing these units properly would need new shared contract unit keys
/// adopted by BOTH clients (a contracts + Android change, not an iOS one).
enum DriveFormatters {
    /// What every readout here renders when the value is genuinely absent,
    /// as opposed to zero — so "no value" never gets confused with a real 0.
    static let missingValue = "—"

    /// Metres → "820 m" under 1 km, otherwise "12.3 km" (one decimal).
    /// Nil, negative, or non-finite (a corrupted stored value must never
    /// reach the integer conversion, where it would trap) → the dash.
    static func formatDistance(_ distanceMeters: Double?) -> String {
        guard let distanceMeters, distanceMeters.isFinite, distanceMeters >= 0 else {
            return missingValue
        }
        if distanceMeters < 1000 {
            return "\(Int(distanceMeters.rounded())) m"
        }
        return String(format: "%.1f km", locale: Locale(identifier: "en_US_POSIX"),
                      distanceMeters / 1000)
    }

    /// Seconds → "1 h 5 min", "5 min", or "45 s" (drops zero leading units).
    static func formatDuration(_ durationSeconds: Int) -> String {
        guard durationSeconds > 0 else { return "0 min" }
        let hours = durationSeconds / 3600
        let minutes = (durationSeconds % 3600) / 60
        let seconds = durationSeconds % 60
        if hours > 0 { return "\(hours) h \(minutes) min" }
        if minutes > 0 { return "\(minutes) min" }
        return "\(seconds) s"
    }

    /// m/s → "45 km/h" (whole km/h). Nil, negative, or non-finite → the
    /// missing-value dash, never "0 km/h" — see
    /// ``SavedDrive/maxSpeedMetersPerSecond``.
    static func formatSpeed(_ metersPerSecond: Double?) -> String {
        guard let metersPerSecond, metersPerSecond.isFinite, metersPerSecond >= 0 else {
            return missingValue
        }
        return "\(Int((metersPerSecond * 3.6).rounded())) km/h"
    }
}
