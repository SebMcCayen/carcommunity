import Foundation

protocol DriveRecordingRepository: AnyObject, Sendable {
    func save(_ request: DriveSaveRequest) async throws -> DriveSaveResult
    func uploadRoute(_ points: [RecordedDrivePoint], to path: String) async throws
}

enum DriveRecordingRepositoryError: Error, Equatable, Sendable {
    case malformedResponse
}
