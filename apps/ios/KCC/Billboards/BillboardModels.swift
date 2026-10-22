import Foundation

enum BillboardInteractionType: String, Sendable {
    case impression
    case open
    case navigate
    case phone
    case website
    case offerView = "offer_view"
}

enum BillboardCallToActionType: String, Sendable {
    case navigate
    case phone
    case website
    case offerView = "offer_view"
    case partnerProfile = "partner_profile"
}

struct Billboard: Equatable, Sendable {
    let id: String
    let headline: String
    let message: String?
    let companyId: String?
    let latitude: Double
    let longitude: Double
    let callToActionType: BillboardCallToActionType?
    let callToActionValue: String?
    let availableFrom: Date?
    let availableUntil: Date?
}

enum BillboardVisibility {
    /// Billboard windows are half-open: `[availableFrom, availableUntil)`.
    static func isVisible(_ billboard: Billboard, at date: Date) -> Bool {
        if let from = billboard.availableFrom, date < from { return false }
        if let until = billboard.availableUntil, date >= until { return false }
        return true
    }

    static func visible(_ billboards: [Billboard], at date: Date) -> [Billboard] {
        billboards.filter { isVisible($0, at: date) }
    }

    static func nextBoundary(after date: Date, in billboards: [Billboard]) -> Date? {
        billboards
            .flatMap { [$0.availableFrom, $0.availableUntil].compactMap { $0 } }
            .filter { $0 > date }
            .min()
    }
}

struct BillboardAction: Equatable, Sendable {
    let labelKey: String
    let url: URL
    let interactionType: BillboardInteractionType
}

enum BillboardCallToAction {
    static func resolve(_ billboard: Billboard) -> BillboardAction? {
        switch billboard.callToActionType {
        case .phone:
            guard let url = PartnerExternalDestination.phone(billboard.callToActionValue) else {
                return nil
            }
            return BillboardAction(
                labelKey: "billboard.call",
                url: url,
                interactionType: .phone
            )
        case .website:
            guard let value = normalizedWebsite(billboard.callToActionValue),
                  let url = PartnerExternalDestination.website(value) else { return nil }
            return BillboardAction(
                labelKey: "billboard.visitWebsite",
                url: url,
                interactionType: .website
            )
        default:
            // The admin form stores no target for navigate, offer or profile.
            return nil
        }
    }

    private static func normalizedWebsite(_ rawValue: String?) -> String? {
        guard let rawValue else { return nil }
        let value = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !value.isEmpty else { return nil }
        return value.contains("://") ? value : "https://\(value)"
    }
}

enum Billboards {
    static let activeQueryLimit = 150
}
