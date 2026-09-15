import Foundation

final class FirebaseConvoyManagementRepository: ConvoyManagementRepository, @unchecked Sendable {
    private let client: KccFunctionsClient

    private init(client: KccFunctionsClient) {
        self.client = client
    }

    func list() async -> ConvoyManagementListResult {
        switch await call("convoy-list", payload: [:]) {
        case .success(let data): return .loaded(ConvoyManagementParser.parseList(data))
        case .failure(let error):
            return .failed(ConvoyManagementErrorMapper.mapList(error.code))
        }
    }

    func respond(convoyId: String, action: ConvoyAction) async -> ConvoyRespondResult {
        switch await call(
            "convoy-respond",
            payload: ["convoyId": convoyId, "action": action.rawValue]
        ) {
        case .success(let data): return ConvoyManagementParser.parseRespond(data)
        case .failure(let error):
            return .failed(ConvoyManagementErrorMapper.mapRespond(error.code))
        }
    }

    func lifecycle(
        convoyId: String,
        action: ConvoyLifecycleAction
    ) async -> ConvoyLifecycleResult {
        switch await call(
            "convoy-\(action.rawValue)",
            payload: ["convoyId": convoyId]
        ) {
        case .success(let data): return ConvoyManagementParser.parseLifecycle(data, action: action)
        case .failure(let error):
            return .failed(
                ConvoyManagementErrorMapper.mapLifecycle(error.code, action: action)
            )
        }
    }

    func invite(
        convoyId: String,
        inviteeUids: [String]
    ) async -> ConvoyInviteMutationResult {
        switch await call(
            "convoy-invite",
            payload: ["convoyId": convoyId, "inviteeUids": inviteeUids]
        ) {
        case .success(let data): return ConvoyManagementParser.parseInvite(data)
        case .failure(let error):
            return .failed(ConvoyManagementErrorMapper.mapInvite(error.code))
        }
    }

    private func call(
        _ name: String,
        payload: [String: Any]
    ) async -> Result<[String: Any], KccFunctionsError> {
        do {
            guard let data = try await client.call(name, payload: payload) as? [String: Any] else {
                return .failure(KccFunctionsError(code: .unknown))
            }
            return .success(data)
        } catch let error as KccFunctionsError {
            return .failure(error)
        } catch {
            return .failure(KccFunctionsError(code: .unknown))
        }
    }

    static func createIfAvailable() -> ConvoyManagementRepository? {
        KccFunctionsClient.createIfAvailable().map(Self.init(client:))
    }
}
