import Foundation

protocol PartnersRepository: Sendable {
    func observeActiveCompanies() -> AsyncStream<PartnersCollectionSnapshot>
    func observeActiveOffers() -> AsyncStream<PartnerOffersSnapshot>
    func observeOfferDetail(offerId: String) -> AsyncStream<PartnerOfferDetailSnapshot>
    func observeSavedOfferIds(uid: String) -> AsyncStream<SavedOffersSnapshot>
    func showOfferCode(offerId: String) async throws -> String?
    func setSaved(uid: String, offerId: String, saved: Bool) async throws
}
