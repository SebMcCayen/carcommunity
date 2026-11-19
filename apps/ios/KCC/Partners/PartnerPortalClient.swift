import Foundation
import Observation

@MainActor
final class PartnerPortalClient {
    private let transport: PartnerPortalTransport
    private init(functions: KccFunctionsClient) { transport = PartnerPortalTransport(functions: functions) }
    static func createIfAvailable() -> PartnerPortalClient? {
        KccFunctionsClient.createIfAvailable().map(PartnerPortalClient.init)
    }
    func call<T: Decodable>(_ action: String, _ fields: [String: Any] = [:], as type: T.Type) async throws -> T {
        var payload = fields; payload["action"] = action
        let request = try JSONSerialization.data(withJSONObject: payload)
        let result = try await transport.call(request)
        do { return try JSONDecoder().decode(type, from: result) }
        catch { throw KccFunctionsError(code: .internalError) }
    }
    func command(_ action: String, _ fields: [String: Any]) async throws {
        var payload = fields; payload["action"] = action
        let request = try JSONSerialization.data(withJSONObject: payload)
        _ = try await transport.call(request)
    }
}

/// Firebase's untyped payloads stay on the transport side of the actor boundary.
/// Only Sendable JSON bytes travel to and from the main-actor coordinator.
private final class PartnerPortalTransport: Sendable {
    private let functions: KccFunctionsClient
    init(functions: KccFunctionsClient) { self.functions = functions }
    func call(_ request: Data) async throws -> Data {
        guard let payload = try JSONSerialization.jsonObject(with: request) as? [String: Any] else {
            throw KccFunctionsError(code: .invalidArgument)
        }
        let result = try await functions.call("partners-portal", payload: payload)
        guard let result, JSONSerialization.isValidJSONObject(result) else {
            throw KccFunctionsError(code: .internalError)
        }
        return try JSONSerialization.data(withJSONObject: result)
    }
}

@MainActor
@Observable
final class PartnerPortalCoordinator {
    let client: PartnerPortalClient?
    private(set) var workspace: PortalWorkspace?
    private(set) var offers: [PortalOffer] = []
    private(set) var statistics: PortalStatistics?
    private(set) var busy = false
    private(set) var errorKey: String?
    private var generation = 0

    init(client: PartnerPortalClient? = PartnerPortalClient.createIfAvailable()) { self.client = client }
    func clear() {
        generation += 1; workspace = nil; offers = []; statistics = nil; errorKey = nil; busy = false
    }
    func load() async {
        guard let client, !busy else { return }
        busy = true; errorKey = nil
        let current = generation
        defer { if generation == current { busy = false } }
        do {
            let value = try await client.call("workspace", as: PortalWorkspace.self)
            guard !Task.isCancelled, current == generation else { return }
            workspace = value
        } catch { if current == generation { fail(error) } }
    }
    func loadOffers(companyId: String) async {
        guard let client else { return }
        generation += 1
        let current = generation
        offers = []; busy = true; errorKey = nil
        defer { if generation == current { busy = false } }
        do {
            let response = try await client.call("listOffers", ["companyId": companyId], as: PortalOffersResponse.self)
            guard !Task.isCancelled, generation == current else { return }
            offers = response.offers
        } catch { if generation == current { fail(error) } }
    }
    func loadStatistics(companyId: String, period: String) async {
        // A new filter must supersede an in-flight load, including when SwiftUI
        // cancels the previous .task(id:) before its network call has returned.
        guard let client else { return }
        generation += 1
        let current = generation
        statistics = nil; busy = true; errorKey = nil
        defer { if generation == current { busy = false } }
        do {
            let response = try await client.call("statistics", ["companyId": companyId, "period": period], as: PortalStatistics.self)
            guard !Task.isCancelled, generation == current else { return }
            statistics = response
        } catch { if generation == current { fail(error) } }
    }
    @discardableResult
    func run(_ operation: (PartnerPortalClient) async throws -> Void) async -> Bool {
        guard let client, !busy else { return false }
        let current = generation
        busy = true; errorKey = nil
        defer { if generation == current { busy = false } }
        do {
            try await operation(client)
            return !Task.isCancelled && generation == current
        } catch {
            if generation == current { fail(error) }
            return false
        }
    }
    private func fail(_ error: Error) {
        errorKey = Self.message(error)
        if let error = error as? KccFunctionsError, [.permissionDenied, .unauthenticated].contains(error.code) {
            workspace = nil; offers = []; statistics = nil
        }
    }
    static func message(_ error: Error) -> String {
        switch (error as? KccFunctionsError)?.code {
        case .permissionDenied, .unauthenticated: "portal.accessDenied"
        case .resourceExhausted: "portal.fullOrLimited"
        case .failedPrecondition, .alreadyExists: "portal.stateChanged"
        case .invalidArgument: "portal.invalid"
        case .notFound: "portal.notFound"
        default: "portal.error"
        }
    }
}
