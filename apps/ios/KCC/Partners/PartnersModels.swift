import Foundation

enum PartnerCategory: String, CaseIterable, Sendable {
    case workshop, carCare = "car_care", parts, tires, charging, restaurant, retail, other

    init(wireValue: String?) {
        self = wireValue.flatMap(Self.init(rawValue:)) ?? .other
    }

    var localizationKey: String {
        switch self {
        case .workshop: "partners.categoryWorkshop"
        case .carCare: "partners.categoryCarCare"
        case .parts: "partners.categoryParts"
        case .tires: "partners.categoryTires"
        case .charging: "partners.categoryCharging"
        case .restaurant: "partners.categoryRestaurant"
        case .retail: "partners.categoryRetail"
        case .other: "partners.categoryOther"
        }
    }
}

enum PartnerOfferType: String, CaseIterable, Sendable {
    case discountCode = "discount_code"
    case percentageDiscount = "percentage_discount"
    case fixedDiscount = "fixed_discount"
    case memberBenefit = "member_benefit"
    case specialOffer = "special_offer"
    case other

    init(wireValue: String?) {
        self = wireValue.flatMap(Self.init(rawValue:)) ?? .other
    }

    var localizationKey: String {
        switch self {
        case .discountCode: "partnerOffers.offerTypeDiscountCode"
        case .percentageDiscount: "partnerOffers.offerTypePercentageDiscount"
        case .fixedDiscount: "partnerOffers.offerTypeFixedDiscount"
        case .memberBenefit: "partnerOffers.offerTypeMemberBenefit"
        case .specialOffer: "partnerOffers.offerTypeSpecialOffer"
        case .other: "partnerOffers.offerTypeOther"
        }
    }
}

struct PartnerCompany: Equatable, Hashable, Sendable, Identifiable {
    let id: String
    let name: String
    let category: PartnerCategory
    let description: String?
    let website: String?
    let phone: String?
    let address: String?
    let latitude: Double?
    let longitude: Double?

    static func fromMap(id: String, map: [String: Any]) -> PartnerCompany? {
        guard !id.isEmpty,
              let name = (map["name"] as? String)?.trimmedNonempty
        else { return nil }
        return PartnerCompany(
            id: id,
            name: name,
            category: PartnerCategory(wireValue: map["category"] as? String),
            description: (map["description"] as? String)?.trimmedNonempty,
            website: (map["website"] as? String)?.trimmedNonempty,
            phone: (map["phone"] as? String)?.trimmedNonempty,
            address: (map["address"] as? String)?.trimmedNonempty,
            latitude: (map["latitude"] as? NSNumber)?.doubleValue,
            longitude: (map["longitude"] as? NSNumber)?.doubleValue
        )
    }
}

struct PartnerOffer: Equatable, Hashable, Sendable, Identifiable {
    let id: String
    let companyId: String
    let partnerCompanyName: String?
    let title: String
    let teaserText: String
    let offerType: PartnerOfferType

    static func fromMap(id: String, map: [String: Any]) -> PartnerOffer? {
        guard !id.isEmpty,
              let companyId = (map["companyId"] as? String)?.trimmedNonempty,
              let title = (map["title"] as? String)?.trimmedNonempty
        else { return nil }
        return PartnerOffer(
            id: id,
            companyId: companyId,
            partnerCompanyName: (map["partnerCompanyName"] as? String)?.trimmedNonempty,
            title: title,
            teaserText: (map["teaserText"] as? String)?.trimmedNonempty ?? "",
            offerType: PartnerOfferType(wireValue: map["offerType"] as? String)
        )
    }
}

struct PartnerOfferDetail: Equatable, Sendable {
    let description: String?
    let redemptionInstructions: String?
    let terms: String?

    static func fromMap(_ map: [String: Any]) -> PartnerOfferDetail {
        PartnerOfferDetail(
            description: (map["description"] as? String)?.trimmedNonempty,
            redemptionInstructions: (map["redemptionInstructions"] as? String)?.trimmedNonempty,
            terms: (map["terms"] as? String)?.trimmedNonempty
        )
    }
}

enum PartnersCollectionSnapshot: Equatable, Sendable {
    case loaded(companies: [PartnerCompany])
    case failed(code: String?)
}

enum PartnerOffersSnapshot: Equatable, Sendable {
    case loaded(offers: [PartnerOffer])
    case failed(code: String?)
}

enum PartnerOfferDetailSnapshot: Equatable, Sendable {
    case loaded(PartnerOfferDetail?)
    case failed(code: String?)
}

enum SavedOffersSnapshot: Equatable, Sendable {
    case loaded(ids: Set<String>)
    case failed(code: String?)
}

enum PartnersUiState: Equatable, Sendable {
    case unavailable, loading, empty, loaded([PartnerCompany]), failed
}

enum OfferDetailUiState: Equatable, Sendable {
    case idle, loading, loaded(PartnerOfferDetail), missing, failed
}

enum OfferCodeStatus: Equatable, Sendable {
    case idle, loading(offerId: String), shown(offerId: String, code: String?), failed(offerId: String)
}

enum SavedOfferActionStatus: Equatable, Sendable {
    case idle, working(offerId: String), failed(offerId: String)
}

enum PartnerOfferAccess {
    /// Member offer documents and the code callable are both enforced against
    /// the backend-authoritative paid subscription (or an admin claim). The
    /// presentation gate mirrors that authority and never trusts a local purchase.
    static func allows(access: AccountAccess, subscription: StoredSubscription?) -> Bool {
        guard !access.isRestricted else { return false }
        if access.canAccessAdminFeatures { return true }
        guard let tier = subscription?.effectiveTier else { return false }
        return tier == .plus || tier == .supporter
    }
}

enum PartnersPresentation {
    static func offers(
        _ offers: [PartnerOffer],
        forCompany companyId: String
    ) -> [PartnerOffer] {
        offers.filter { $0.companyId == companyId }.sorted {
            $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending
        }
    }

    static func savedOffers(
        _ offers: [PartnerOffer],
        savedIds: Set<String>
    ) -> [PartnerOffer] {
        offers.filter { savedIds.contains($0.id) }.sorted {
            $0.title.localizedCaseInsensitiveCompare($1.title) == .orderedAscending
        }
    }
}

enum PartnerExternalDestination {
    static func website(_ rawValue: String?) -> URL? {
        guard let rawValue = rawValue?.trimmedNonempty,
              var components = URLComponents(string: rawValue),
              let scheme = components.scheme?.lowercased(),
              scheme == "https" || scheme == "http",
              components.host?.isEmpty == false,
              components.user == nil,
              components.password == nil
        else { return nil }
        components.fragment = nil
        return components.url
    }

    static func phone(_ rawValue: String?) -> URL? {
        guard let rawValue = rawValue?.trimmedNonempty else { return nil }
        let allowed = CharacterSet(charactersIn: "+0123456789 ()-.")
        guard rawValue.unicodeScalars.allSatisfy(allowed.contains) else { return nil }
        let hasLeadingPlus = rawValue.hasPrefix("+")
        let digits = rawValue.filter(\.isNumber)
        guard digits.count >= 3 else { return nil }
        return URL(string: "tel:\(hasLeadingPlus ? "+" : "")\(digits)")
    }

    static func maps(latitude: Double?, longitude: Double?) -> URL? {
        guard let latitude, let longitude,
              latitude.isFinite, longitude.isFinite,
              (-90...90).contains(latitude), (-180...180).contains(longitude)
        else { return nil }
        var components = URLComponents()
        components.scheme = "https"
        components.host = "maps.apple.com"
        components.path = "/"
        components.queryItems = [
            URLQueryItem(name: "daddr", value: "\(latitude),\(longitude)"),
            URLQueryItem(name: "dirflg", value: "d"),
        ]
        return components.url
    }
}

private extension String {
    var trimmedNonempty: String? {
        let value = trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }
}
