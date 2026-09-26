import Foundation

struct RestoredDriveRecording: Sendable {
    let startedAt: Date
    let context: DriveRecordingContext
    let points: [RecordedDrivePoint]
    let stoppedAt: Date?
}

protocol DriveRecordingJournal: AnyObject {
    func restore(sessionId: String?) -> RestoredDriveRecording?
    func begin(context: DriveRecordingContext, startedAt: Date)
    func updateContext(_ context: DriveRecordingContext)
    func markStopped(at date: Date)
    func append(_ point: RecordedDrivePoint)
    func clear()
}

/// Crash-resilient, app-private route journal. The first line is a tiny
/// versioned header; each accepted point is appended independently so a process
/// kill can at most leave one partial line, which restore drops. The file uses
/// complete-until-first-authentication protection and is removed after the
/// saved summary closes, on discard/sign-out, or for a different live session.
final class FileDriveRecordingJournal: DriveRecordingJournal {
    private let fileURL: URL

    init(fileURL: URL) {
        self.fileURL = fileURL
    }

    convenience init?(ownerId: String, fileManager: FileManager = .default) {
        guard !ownerId.isEmpty else { return nil }
        let key = Data(ownerId.utf8).base64EncodedString()
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "=", with: "")
        self.init(fileManager: fileManager, fileName: "active-\(key)-v1.routejournal")
    }

    private convenience init?(fileManager: FileManager, fileName: String) {
        guard let root = try? fileManager.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: true
        ) else { return nil }
        let directory = root.appendingPathComponent("DriveRecording", isDirectory: true)
        do {
            try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
            self.init(fileURL: directory.appendingPathComponent(fileName))
        } catch {
            return nil
        }
    }

    func restore(sessionId: String?) -> RestoredDriveRecording? {
        guard let data = try? Data(contentsOf: fileURL),
              let text = String(data: data, encoding: .utf8) else { return nil }
        let lines = text.split(separator: "\n", omittingEmptySubsequences: true)
        guard let headerLine = lines.first,
              let headerData = String(headerLine).data(using: .utf8),
              let header = try? JSONDecoder().decode(Header.self, from: headerData),
              header.version == 1,
              sessionId == nil || header.sessionId == sessionId
        else { return nil }
        let points = lines.dropFirst().prefix(DriveRecorder.maximumRoutePoints).compactMap {
            line -> RecordedDrivePoint? in
            let parts = line.split(separator: ",", omittingEmptySubsequences: false)
            guard parts.count == 3,
                  let latitude = Double(parts[0]),
                  let longitude = Double(parts[1]),
                  let timestamp = Int64(parts[2]),
                  latitude.isFinite, (-90...90).contains(latitude),
                  longitude.isFinite, (-180...180).contains(longitude)
            else { return nil }
            return RecordedDrivePoint(
                latitude: latitude,
                longitude: longitude,
                timestampMilliseconds: timestamp
            )
        }
        return RestoredDriveRecording(
            startedAt: Date(timeIntervalSince1970: Double(header.startedAtMilliseconds) / 1_000),
            context: DriveRecordingContext(
                sourceSessionId: header.sessionId,
                vehicleId: header.vehicleId,
                carImagePath: header.carImagePath,
                convoyMembers: header.convoyMembers.map {
                    ConvoyDriveMember(uid: $0.uid, displayName: $0.displayName, avatarPath: $0.avatarPath)
                },
                expiresAt: header.expiresAtMilliseconds.map {
                    Date(timeIntervalSince1970: Double($0) / 1_000)
                }
            ),
            points: points,
            stoppedAt: header.stoppedAtMilliseconds.map {
                Date(timeIntervalSince1970: Double($0) / 1_000)
            }
        )
    }

    func begin(context: DriveRecordingContext, startedAt: Date) {
        guard let data = encodedHeader(context: context, startedAt: startedAt, stoppedAt: nil)
        else { return }
        writeAtomically(data + Data([0x0a]))
    }

    func updateContext(_ context: DriveRecordingContext) {
        guard let restored = restore(sessionId: context.sourceSessionId),
              let old = try? Data(contentsOf: fileURL),
              let newline = old.firstIndex(of: 0x0a),
              let header = encodedHeader(
                context: context,
                startedAt: restored.startedAt,
                stoppedAt: restored.stoppedAt
              )
        else { return }
        let points = old[old.index(after: newline)...]
        writeAtomically(header + Data([0x0a]) + points)
    }

    func markStopped(at date: Date) {
        guard let restored = restore(sessionId: nil),
              let old = try? Data(contentsOf: fileURL),
              let newline = old.firstIndex(of: 0x0a),
              let header = encodedHeader(
                context: restored.context,
                startedAt: restored.startedAt,
                stoppedAt: date
              )
        else { return }
        let points = old[old.index(after: newline)...]
        writeAtomically(header + Data([0x0a]) + points)
    }

    private func encodedHeader(
        context: DriveRecordingContext,
        startedAt: Date,
        stoppedAt: Date?
    ) -> Data? {
        let header = Header(
            version: 1,
            sessionId: context.sourceSessionId,
            startedAtMilliseconds: Int64((startedAt.timeIntervalSince1970 * 1_000).rounded()),
            vehicleId: context.vehicleId,
            carImagePath: context.carImagePath,
            convoyMembers: context.convoyMembers.map {
                Member(uid: $0.uid, displayName: $0.displayName, avatarPath: $0.avatarPath)
            },
            expiresAtMilliseconds: context.expiresAt.map {
                Int64(($0.timeIntervalSince1970 * 1_000).rounded())
            },
            stoppedAtMilliseconds: stoppedAt.map {
                Int64(($0.timeIntervalSince1970 * 1_000).rounded())
            }
        )
        return try? JSONEncoder().encode(header)
    }

    private func writeAtomically(_ data: Data) {
        do {
            try data.write(
                to: fileURL,
                options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]
            )
        } catch {
            clear()
        }
    }

    func append(_ point: RecordedDrivePoint) {
        let line = "\(point.latitude),\(point.longitude),\(point.timestampMilliseconds)\n"
        guard let data = line.data(using: .utf8),
              let handle = try? FileHandle(forWritingTo: fileURL) else { return }
        do {
            try handle.seekToEnd()
            try handle.write(contentsOf: data)
            try handle.close()
        } catch {
            try? handle.close()
        }
    }

    func clear() {
        try? FileManager.default.removeItem(at: fileURL)
    }

    private struct Header: Codable {
        let version: Int
        let sessionId: String
        let startedAtMilliseconds: Int64
        let vehicleId: String?
        let carImagePath: String?
        let convoyMembers: [Member]
        let expiresAtMilliseconds: Int64?
        let stoppedAtMilliseconds: Int64?
    }

    private struct Member: Codable {
        let uid: String
        let displayName: String?
        let avatarPath: String?
    }
}
