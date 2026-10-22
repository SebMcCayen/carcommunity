import Foundation

enum BillboardsSnapshot: Equatable, Sendable {
    case loaded([Billboard])
    case failed(code: String?)
}

protocol BillboardsRepository: AnyObject, Sendable {
    func activeBillboards() -> AsyncStream<BillboardsSnapshot>
    func recordInteraction(billboardId: String, type: BillboardInteractionType) async throws
}
