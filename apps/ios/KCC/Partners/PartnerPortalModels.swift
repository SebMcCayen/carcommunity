import Foundation
import CryptoKit

/// Responses are projections from partners-portal, never raw customer/staff documents.
struct PortalCompany: Decodable, Identifiable, Sendable {
    let companyId: String
    let name: String
    let description: String?
    let phone: String?
    let website: String?
    let logoPath: String?
    let status: String
    let role: String
    var id: String { companyId }
    var canManage: Bool { role == "manager" }
}

struct PortalApplication: Decodable, Sendable {
    let applicationId: String
    let companyName: String
    let category: String
    let organizationNumber: String?
    let contactName: String
    let contactEmail: String
    let contactPhone: String?
    let website: String?
    let description: String?
    let address: String?
    let publicPhone: String?
    let selfServiceRequested: Bool
    let billboardRequested: Bool
    let billboardPlacementRequest: String?
    let message: String?
    let status: String
    let reviewReason: String?
    let logoPreviewBase64: String?
}

struct PortalWorkspace: Decodable, Sendable {
    let companies: [PortalCompany]
    let application: PortalApplication?
}

struct PortalOffer: Decodable, Identifiable, Sendable {
    let offerId: String
    let companyId: String
    let title: String
    let teaserText: String
    let description: String
    let terms: String
    let percentageDiscount: Double?
    let availableFrom: String?
    let availableUntil: String?
    let capacity: Int?
    let reservationDays: Int
    let reservedCount: Int?
    let redeemedCount: Int?
    let remainingCount: Int?
    let status: String
    let version: Int
    var reservationsPaused: Bool?
    var id: String { offerId }
    var canEdit: Bool { status == "draft" && (reservedCount ?? 0) == 0 && (redeemedCount ?? 0) == 0 }
}

struct PortalReservation: Decodable, Identifiable, Equatable, Sendable {
    let reservationId: String
    let offerId: String
    let companyId: String
    let companyName: String
    let title: String
    let terms: String
    let status: String
    let expiresAt: String
    let redeemedAt: String?
    var id: String { reservationId }
    var expiry: Date? { PortalDate.parse(expiresAt) }
    var canPresent: Bool { status == "reserved" && (expiry.map { $0 > Date() } ?? false) }
}

struct PortalOfferResponse: Decodable, Sendable {
    let offer: PortalOffer
    let reservation: PortalReservation?
    let alreadyUsed: Bool?
}
struct PortalOffersResponse: Decodable, Sendable { let offers: [PortalOffer] }
struct PortalReservationsResponse: Decodable, Sendable { let reservations: [PortalReservation] }
struct PortalToken: Decodable, Sendable {
    let token: String
    let expiresAt: String
    var expiry: Date? { PortalDate.parse(expiresAt) }
}
struct PortalInspection: Decodable, Sendable { let reservation: PortalReservation }
struct PortalReceipt: Decodable, Sendable {
    let reservation: PortalReservation
    let receiptId: String
}
struct PortalStatistics: Decodable, Sendable {
    struct Offer: Decodable, Identifiable, Sendable {
        let offerId: String
        let title: String
        let totalRedeemed: Int
        var id: String { offerId }
    }
    struct Partner: Decodable, Identifiable, Sendable {
        let companyId: String
        let companyName: String
        let totalRedeemed: Int
        let offers: [Offer]
        var id: String { companyId }
    }
    let updatedAt: String
    let period: String
    let totalRedeemed: Int
    let partners: [Partner]
}

enum PortalDate {
    static func parse(_ value: String) -> Date? {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.date(from: value) ?? ISO8601DateFormatter().date(from: value)
    }
    static func wire(_ date: Date) -> String { ISO8601DateFormatter().string(from: date) }
    static func closedMonths(now: Date = Date()) -> [String] {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.calendar = calendar
        formatter.timeZone = calendar.timeZone
        formatter.dateFormat = "yyyy-MM"
        return (1...12).compactMap { offset in
            calendar.date(byAdding: .month, value: -offset, to: now).map(formatter.string(from:))
        }
    }
}

struct PortalOfferForm {
    var title = ""
    var teaserText = ""
    var description = ""
    var terms = ""
    var percentage = ""
    var limited = false
    var capacity = 3
    var reservationDays = 7
    var hasStart = false
    var availableFrom = Date()
    var hasEnd = false
    var availableUntil = Date().addingTimeInterval(30 * 86_400)

    init(offer: PortalOffer? = nil) {
        guard let offer else { return }
        title = offer.title; teaserText = offer.teaserText
        description = offer.description; terms = offer.terms
        percentage = offer.percentageDiscount.map { String($0) } ?? ""
        limited = offer.capacity != nil; capacity = offer.capacity ?? 3
        reservationDays = offer.reservationDays
        hasStart = offer.availableFrom != nil
        availableFrom = offer.availableFrom.flatMap(PortalDate.parse) ?? availableFrom
        hasEnd = offer.availableUntil != nil
        availableUntil = offer.availableUntil.flatMap(PortalDate.parse) ?? availableUntil
    }
    var isValid: Bool {
        title.trimmingCharacters(in: .whitespacesAndNewlines).count >= 2 && title.utf16.count <= 120
            && teaserText.trimmingCharacters(in: .whitespacesAndNewlines).count >= 2 && teaserText.utf16.count <= 300
            && description.trimmingCharacters(in: .whitespacesAndNewlines).count >= 2 && description.utf16.count <= 6_000
            && terms.trimmingCharacters(in: .whitespacesAndNewlines).count >= 2 && terms.utf16.count <= 4_000
            && (percentage.isEmpty || discount.map { $0 >= 1 && $0 <= 100 && $0.rounded() == $0 } == true)
            && (!hasEnd || availableUntil > Date())
            && (!hasStart || !hasEnd || availableUntil > availableFrom)
    }
    var discount: Double? { Double(percentage.replacingOccurrences(of: ",", with: ".")) }
    func payload(companyId: String, offerId: String?) -> [String: Any] {
        var value: [String: Any] = [
            "companyId": companyId, "title": title.trimmingCharacters(in: .whitespacesAndNewlines),
            "teaserText": teaserText.trimmingCharacters(in: .whitespacesAndNewlines),
            "description": description.trimmingCharacters(in: .whitespacesAndNewlines),
            "terms": terms.trimmingCharacters(in: .whitespacesAndNewlines),
            "percentageDiscount": discount.map { $0 as Any } ?? NSNull(),
            "capacity": limited ? capacity as Any : NSNull(), "reservationDays": reservationDays,
            "availableFrom": hasStart ? PortalDate.wire(availableFrom) as Any : NSNull(),
            "availableUntil": hasEnd ? PortalDate.wire(availableUntil) as Any : NSNull(),
        ]
        if let offerId { value["offerId"] = offerId }
        return value
    }
}

/// Explicit, account-scoped device drafts. Logos and QR material are never saved.
struct PortalApplicationDraft: Codable {
    let savedAt: Date
    let values: [String: String]
    let selfServiceRequested: Bool
    let billboardRequested: Bool
    init(form: PartnerApplicationForm, now: Date = Date()) {
        savedAt = now
        values = ["companyName": form.companyName, "category": form.category?.rawValue ?? "",
            "contactName": form.contactName, "contactEmail": form.contactEmail,
            "contactPhone": form.contactPhone, "websiteURL": form.websiteURL, "message": form.message,
            "organizationNumber": form.organizationNumber, "proposedDescription": form.proposedDescription,
            "proposedAddress": form.proposedAddress, "publicPhone": form.publicPhone,
            "billboardPlacementRequest": form.billboardPlacementRequest]
        selfServiceRequested = form.selfServiceRequested; billboardRequested = form.billboardRequested
    }
    var form: PartnerApplicationForm {
        var value = PartnerApplicationForm()
        value.companyName = values["companyName"] ?? ""
        value.category = values["category"].flatMap(PartnerCategory.init(rawValue:))
        value.contactName = values["contactName"] ?? ""; value.contactEmail = values["contactEmail"] ?? ""
        value.contactPhone = values["contactPhone"] ?? ""; value.websiteURL = values["websiteURL"] ?? ""
        value.message = values["message"] ?? ""; value.organizationNumber = values["organizationNumber"] ?? ""
        value.proposedDescription = values["proposedDescription"] ?? ""; value.proposedAddress = values["proposedAddress"] ?? ""
        value.publicPhone = values["publicPhone"] ?? ""; value.billboardPlacementRequest = values["billboardPlacementRequest"] ?? ""
        value.selfServiceRequested = selfServiceRequested; value.billboardRequested = billboardRequested
        return value
    }
}

enum PortalApplicationDraftStore {
    static let lifetime: TimeInterval = 7 * 86_400
    static func load(uid: String, now: Date = Date()) -> PartnerApplicationForm? {
        guard let url = try? location(uid: uid), let data = try? Data(contentsOf: url),
              data.count < 100_000,
              let draft = try? JSONDecoder().decode(PortalApplicationDraft.self, from: data) else { return nil }
        guard draft.savedAt <= now, now.timeIntervalSince(draft.savedAt) <= lifetime else {
            try? FileManager.default.removeItem(at: url); return nil
        }
        return draft.form
    }
    static func save(form: PartnerApplicationForm, uid: String) throws {
        let url = try location(uid: uid)
        let data = try JSONEncoder().encode(PortalApplicationDraft(form: form))
        guard data.count < 100_000 else { throw KccFunctionsError(code: .invalidArgument) }
        try data.write(to: url, options: [.atomic, .completeFileProtection])
        try FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        var mutable = url; try mutable.setResourceValues(values)
    }
    static func remove(uid: String) {
        guard let url = try? location(uid: uid) else { return }
        try? FileManager.default.removeItem(at: url)
    }
    private static func location(uid: String) throws -> URL {
        guard !uid.isEmpty else { throw KccFunctionsError(code: .unauthenticated) }
        let directory = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            .appendingPathComponent("PartnerApplicationDrafts", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true,
            attributes: [.posixPermissions: 0o700, .protectionKey: FileProtectionType.complete])
        var values = URLResourceValues(); values.isExcludedFromBackup = true
        var mutable = directory; try mutable.setResourceValues(values)
        // OS execution is not guaranteed while the app is closed; expired drafts
        // are unusable at read time and stale files are removed on next access.
        if let files = try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: [.contentModificationDateKey, .isRegularFileKey]) {
            let cutoff = Date().addingTimeInterval(-lifetime)
            for file in files where file.pathExtension == "json" {
                if let metadata = try? file.resourceValues(forKeys: [.contentModificationDateKey, .isRegularFileKey]),
                   metadata.isRegularFile == true, let modified = metadata.contentModificationDate, modified < cutoff {
                    try? FileManager.default.removeItem(at: file)
                }
            }
        }
        let digest = SHA256.hash(data: Data(uid.utf8)).map { String(format: "%02x", $0) }.joined()
        return directory.appendingPathComponent(digest + ".json")
    }
}
