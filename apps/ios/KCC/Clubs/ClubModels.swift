import Foundation

struct CommunityClub: Identifiable, Equatable, Sendable {
    let id: String
    let name: String
    let description: String
    let location: String
    let visibility: String
    let ownerUid: String
    let memberCount: Int
    let logoURL: URL?
    let driveCount: Int
    let distanceMeters: Double
    let durationSeconds: Double

    static func decode(_ raw: [String: Any]) -> Self? {
        guard let id = raw["id"] as? String, !id.isEmpty,
              let name = raw["name"] as? String, !name.isEmpty,
              (raw["status"] as? String ?? "active") == "active" else { return nil }
        let stats = raw["stats"] as? [String: Any] ?? [:]
        let logo = (raw["logoUrl"] as? String).flatMap(URL.init(string:))
        return Self(id: id, name: name, description: raw["description"] as? String ?? "",
            location: raw["location"] as? String ?? "", visibility: raw["visibility"] as? String ?? "open",
            ownerUid: raw["ownerUid"] as? String ?? "", memberCount: max(0, raw["memberCount"] as? Int ?? 0),
            logoURL: logo.flatMap { $0.scheme == "https" && $0.host != nil && $0.user == nil && $0.password == nil ? $0 : nil },
            driveCount: max(0, stats["driveCount"] as? Int ?? 0),
            distanceMeters: nonnegative(stats["distanceMeters"]), durationSeconds: nonnegative(stats["durationSeconds"]))
    }
    private static func nonnegative(_ value: Any?) -> Double {
        let number = (value as? NSNumber)?.doubleValue ?? 0
        return number.isFinite ? max(0, number) : 0
    }
}

struct ClubInvitation: Identifiable, Equatable, Sendable {
    var id: String { clubId }
    let clubId: String
    let clubName: String
    let expiresAt: Date
}
struct SentClubInvitation: Identifiable, Equatable, Sendable {
    var id: String { recipientUid }
    let recipientUid: String
    let displayName: String
    let createdAt: String
    let sentAt: Date
    let expiresAt: Date

    static func decode(_ raw: [String: Any]) -> Self? {
        guard let uid = raw["recipientUid"] as? String, !uid.isEmpty,
              let createdAt = raw["createdAt"] as? String,
              let sentAt = CommunityClubState.date(createdAt),
              let expiresAt = CommunityClubState.date(raw["expiresAt"]), expiresAt > sentAt else { return nil }
        return Self(recipientUid: uid, displayName: raw["displayName"] as? String ?? "",
                    createdAt: createdAt, sentAt: sentAt, expiresAt: expiresAt)
    }
}
struct SentClubInvitationsPage: Sendable { let items: [SentClubInvitation]; let nextCursor: String? }
struct CommunityClubState: Equatable, Sendable {
    let club: CommunityClub?
    let role: String?
    let joinAllowedAt: Date?
    let invitations: [ClubInvitation]
    let legacyConflict: Bool

    static func decode(_ raw: [String: Any]) -> Self {
        let club = (raw["club"] as? [String: Any]).flatMap(CommunityClub.decode)
        let membership = raw["membership"] as? [String: Any]
        let role = membership?["role"] as? String
        let validMembership = club != nil && membership?["clubId"] as? String == club?.id
            && ["owner", "moderator", "member"].contains(role ?? "")
            && date(membership?["joinedAt"]) != nil
        let invitations = (raw["invitations"] as? [[String: Any]] ?? []).prefix(25).compactMap { row -> ClubInvitation? in
            guard let id = row["clubId"] as? String, !id.isEmpty,
                  let name = row["clubName"] as? String, let expiry = date(row["expiresAt"]) else { return nil }
            return ClubInvitation(clubId: id, clubName: name, expiresAt: expiry)
        }
        return Self(club: validMembership ? club : nil, role: validMembership ? role : nil,
            joinAllowedAt: date(raw["joinAllowedAt"]), invitations: invitations,
            legacyConflict: raw["legacyConflict"] as? Bool == true)
    }
    func canJoin(at now: Date) -> Bool {
        club == nil && !legacyConflict && (joinAllowedAt.map { $0 <= now } ?? true)
    }
    static func date(_ value: Any?) -> Date? {
        guard let string = value as? String else { return nil }
        let format = ISO8601DateFormatter()
        format.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return format.date(from: string) ?? ISO8601DateFormatter().date(from: string)
    }
}

struct ClubDirectoryPage: Sendable { let clubs: [CommunityClub]; let nextCursor: String? }

struct ClubForm: Equatable, Sendable {
    var name = ""
    var description = ""
    var location = ""
    var closed = false
    var fields: [String: String] {
        ["name": name.trimmingCharacters(in: .whitespacesAndNewlines),
         "description": description.trimmingCharacters(in: .whitespacesAndNewlines),
         "location": location.trimmingCharacters(in: .whitespacesAndNewlines),
         "visibility": closed ? "closed" : "open"]
    }
    var isValid: Bool {
        (3...60).contains(fields["name"]!.unicodeScalars.count)
            && (10...500).contains(fields["description"]!.unicodeScalars.count)
            && (2...80).contains(fields["location"]!.unicodeScalars.count)
    }
}
