import Foundation
import Observation

struct ProfileDraft: Equatable, Sendable {
    var displayName: String
    var bio: String
    var facebook: String
    var instagram: String
    var youtube: String

    init(profile: UserProfile?) {
        displayName = profile?.displayName ?? ""
        bio = profile?.bio ?? ""
        facebook = profile?.facebook ?? ""
        instagram = profile?.instagram ?? ""
        youtube = profile?.youtube ?? ""
    }
}

struct ValidatedProfile: Equatable, Sendable {
    let displayName: String
    let bio: String
    let facebook: String?
    let instagram: String?
    let youtube: String?
}

enum ProfileValidationError: Error, Equatable, Sendable { case nameRequired, tooLong, invalidSocial }

enum ProfileValidation {
    static let displayNameMax = 120
    static let bioMax = 500

    static func validate(_ draft: ProfileDraft) -> Result<ValidatedProfile, ProfileValidationError> {
        let name = draft.displayName.trimmingCharacters(in: .whitespacesAndNewlines)
        let bio = draft.bio.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !name.isEmpty else { return .failure(.nameRequired) }
        guard name.count <= displayNameMax, bio.count <= bioMax else { return .failure(.tooLong) }
        guard let facebook = social(draft.facebook, platform: .facebook),
              let instagram = social(draft.instagram, platform: .instagram),
              let youtube = social(draft.youtube, platform: .youtube)
        else { return .failure(.invalidSocial) }
        return .success(ValidatedProfile(
            displayName: name, bio: bio,
            facebook: facebook, instagram: instagram, youtube: youtube
        ))
    }

    private enum Platform { case facebook, instagram, youtube }
    /// Returns outer nil on rejection and an inner nil for an intentionally empty field.
    private static func social(_ raw: String, platform: Platform) -> String?? {
        var value = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if value.isEmpty { return .some(nil) }
        if value.hasPrefix("@") { value.removeFirst() }
        if value.contains("://") {
            guard let components = URLComponents(string: value),
                  components.scheme?.lowercased() == "https",
                  let host = components.host?.lowercased()
            else { return nil }
            let expected: Set<String>
            switch platform {
            case .facebook: expected = ["facebook.com", "www.facebook.com"]
            case .instagram: expected = ["instagram.com", "www.instagram.com"]
            case .youtube: expected = ["youtube.com", "www.youtube.com"]
            }
            guard expected.contains(host), components.query == nil, components.fragment == nil else { return nil }
            let pieces = components.path.split(separator: "/")
            guard pieces.count == 1 else { return nil }
            value = String(pieces[0])
            if platform == .youtube, value.hasPrefix("@") { value.removeFirst() }
        }
        let pattern: String
        switch platform {
        case .facebook:
            value = value.lowercased(); pattern = "^[a-z0-9][a-z0-9.-]{0,49}$"
        case .instagram:
            value = value.lowercased(); pattern = "^[a-z0-9_][a-z0-9._]{0,29}$"
        case .youtube:
            pattern = "^[A-Za-z0-9][A-Za-z0-9._-]{2,29}$"
        }
        guard value.range(of: pattern, options: .regularExpression) != nil else { return nil }
        return .some(value)
    }
}

enum ProfileEditStatus: Equatable, Sendable { case idle, saving, saved, failed, uploading, tooLarge }

@MainActor
@Observable
final class ProfileEditCoordinator {
    private let repository: UserProfileRepository?
    private let uid: String?
    private(set) var status: ProfileEditStatus = .idle
    init(repository: UserProfileRepository?, uid: String?) { self.repository = repository; self.uid = uid }

    func save(_ draft: ProfileDraft) async -> ProfileValidationError? {
        guard status != .saving && status != .uploading else { return nil }
        guard case .success(let validated) = ProfileValidation.validate(draft) else {
            if case .failure(let error) = ProfileValidation.validate(draft) { return error }
            return nil
        }
        guard let repository, let uid else { status = .failed; return nil }
        status = .saving
        do {
            try await repository.updateProfile(uid: uid, profile: validated)
            status = .saved
        } catch is CancellationError { status = .idle }
        catch { status = .failed }
        return nil
    }

    func uploadAvatar(jpegData: Data) async {
        guard status != .saving && status != .uploading else { return }
        guard jpegData.count <= 5 * 1024 * 1024 else { status = .tooLarge; return }
        guard let repository, let uid else { status = .failed; return }
        status = .uploading
        do { try await repository.uploadAvatar(uid: uid, jpegData: jpegData); status = .saved }
        catch is CancellationError { status = .idle }
        catch { status = .failed }
    }

    func markUploadFailed() { if status != .saving && status != .uploading { status = .failed } }
    func reset() { if status != .saving && status != .uploading { status = .idle } }
}
