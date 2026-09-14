import Foundation

final class FirebaseConvoyCreateRepository: ConvoyCreateRepository, @unchecked Sendable {
    private let client: KccFunctionsClient

    private init(client: KccFunctionsClient) {
        self.client = client
    }

    func list() async -> ConvoyCreateListResult {
        switch await call(Self.listCallable, payload: [:]) {
        case .success(let data):
            return .loaded(ConvoyCreateResponseParser.parseList(data))
        case .failure(let error):
            return .failed(ConvoyCreateErrorMapper.mapList(error.code))
        }
    }

    func create(inviteeUids: [String], vehicleId: String?) async -> ConvoyCreateResult {
        var payload: [String: Any] = ["inviteeUids": inviteeUids]
        if let vehicleId = vehicleId?.trimmingCharacters(in: .whitespacesAndNewlines),
           !vehicleId.isEmpty {
            payload["vehicleId"] = vehicleId
        }
        switch await call(Self.createCallable, payload: payload) {
        case .success(let data):
            return ConvoyCreateResponseParser.parseCreate(data)
        case .failure(let error):
            return .failed(ConvoyCreateErrorMapper.mapCreate(error.code))
        }
    }

    private func call(
        _ name: String,
        payload: [String: Any]
    ) async -> Result<[String: Any], KccFunctionsError> {
        do {
            let result = try await client.call(name, payload: payload)
            guard let data = result as? [String: Any] else {
                return .failure(KccFunctionsError(code: .unknown))
            }
            return .success(data)
        } catch let error as KccFunctionsError {
            return .failure(error)
        } catch {
            return .failure(KccFunctionsError(code: .unknown))
        }
    }

    private static let listCallable = "convoy-list"
    private static let createCallable = "convoy-create"

    static func createIfAvailable() -> ConvoyCreateRepository? {
        KccFunctionsClient.createIfAvailable().map(FirebaseConvoyCreateRepository.init(client:))
    }
}
