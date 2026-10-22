import Foundation

protocol GroupDriveRepository: AnyObject, Sendable {
    func participants(eventId: String) -> AsyncStream<GroupDriveRosterSnapshot>
    func myStatus(eventId: String, uid: String) -> AsyncStream<GroupDriveOwnStatusSnapshot>
    func join(eventId: String) async throws
    func updateStatus(eventId: String, status: GroupDriveStatus) async throws
    func leave(eventId: String) async throws
}
