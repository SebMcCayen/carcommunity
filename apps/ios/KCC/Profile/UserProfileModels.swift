import Foundation

/// Own-profile domain model — the iOS port of Android's `profile/UserProfile.kt`
/// (Phase 12 slice 2), restricted to the read-only own-profile slice.
///
/// Mirrors the `users/{uid}` document described by
/// `contracts/schemas/user-profile.schema.json` (`userProfile`). Like Android,
/// ONLY the fields this client renders are modelled — the backend-managed
/// protected fields (role, activeMember, suspended, deleted) and the social
/// handles arrive with their own slices. Every field is optional by design:
/// the backend provisions the document, but a partially-written or legacy doc
/// must degrade to placeholders, never crash decoding.
struct UserProfile: Equatable, Sendable {
    /// Visible username (users/{uid}.displayName). Nil when absent or
    /// malformed; the screen falls back to the auth display name.
    let displayName: String?
    /// Short profile description (users/{uid}.bio).
    let bio: String?
    /// Cloud Storage PATH of the avatar (profileImages/{uid}/{imageId}), or
    /// nil when unset. The path — not a URL — is stored (contract
    /// `avatarPath`); a download URL is resolved lazily for rendering, the
    /// same split as Android's `media/StorageImageUrl`.
    let avatarPath: String?
    /// Public affiliation only; never used to authorize club access.
    let clubBadge: ClubBadge?
    /// True once auth.completeOnboarding has stamped the public profile.
    let onboardingComplete: Bool
    /// Canonical public social handles. Nil means the field is absent.
    let facebook: String?
    let instagram: String?
    let youtube: String?

    init(
        displayName: String?,
        bio: String?,
        avatarPath: String?,
        onboardingComplete: Bool = false,
        facebook: String? = nil,
        instagram: String? = nil,
        youtube: String? = nil,
        clubBadge: ClubBadge? = nil
    ) {
        self.displayName = displayName
        self.bio = bio
        self.avatarPath = avatarPath
        self.onboardingComplete = onboardingComplete
        self.facebook = facebook
        self.instagram = instagram
        self.youtube = youtube
        self.clubBadge = clubBadge
    }

    /// Tolerant decoding of a `users/{uid}` document map: a missing or
    /// wrong-typed field degrades to nil (Android's `getString` semantics in
    /// `FirebaseProfileRepository.observeProfile`), never a decode failure —
    /// the contract marks displayName required, but a partially-provisioned
    /// document must still render.
    static func fromMap(_ map: [String: Any]) -> UserProfile {
        // Firestore represents an explicitly-null field as NSNull. The
        // provisioning trigger writes onboardingCompletedAt: null, so field
        // presence alone is not evidence that onboarding completed.
        let onboardingCompletedAt = map["onboardingCompletedAt"]
        return UserProfile(
            displayName: map["displayName"] as? String,
            bio: map["bio"] as? String,
            avatarPath: map["avatarPath"] as? String,
            onboardingComplete: onboardingCompletedAt != nil
                && !(onboardingCompletedAt is NSNull),
            facebook: map["facebook"] as? String,
            instagram: map["instagram"] as? String,
            youtube: map["youtube"] as? String,
            clubBadge: ClubBadge.fromField(map["clubBadge"])
        )
    }
}

/// One emission of the own-profile listener — the iOS port of Android's
/// `ProfileState` minus `Loading`/`Unavailable`: a repository stream only
/// ever emits SETTLED results, and the coordinator supplies loading and
/// unavailable (nil repository / nil uid) itself, the same split as
/// ``EventsListSnapshot``.
enum UserProfileSnapshot: Equatable, Sendable {
    /// The listener failed. `code` is the bare Firestore status name when one
    /// was available (`PERMISSION_DENIED` for an undeployed rule,
    /// `UNAVAILABLE` when offline, …) — a stable, PII-safe diagnosis, never
    /// exception text (which can embed the query path and the project id) —
    /// the same rule as ``EventsListSnapshot/failed(code:)``.
    case failed(code: String?)
    /// The snapshot resolved. `profile` is nil when the document does not
    /// exist yet (the backend provisions it; a brand-new account can race
    /// the trigger) — Android's `ProfileState.Loaded(profile = null)`.
    case loaded(UserProfile?)
}

/// Same read-only public projection and image allowlist as Android's ClubBadge.
struct ClubBadge: Equatable, Sendable {
    let clubId: String
    let clubName: String
    let logoURL: URL?

    var initials: String {
        let letters = clubName.split(whereSeparator: { $0.isWhitespace }).prefix(2)
            .compactMap { $0.unicodeScalars.first }.map(String.init).joined()
            .uppercased(with: Locale(identifier: "en_US_POSIX"))
        return String(String.UnicodeScalarView(letters.unicodeScalars.prefix(2)))
    }

    static func fromField(_ value: Any?) -> Self? {
        guard let fields = value as? [String: Any],
              Set(fields.keys).isSubset(of: ["clubId", "clubName", "logoUrl"]),
              let id = fields["clubId"] as? String, matchesID(id),
              let name = fields["clubName"] as? String,
              name == name.trimmingCharacters(in: .whitespacesAndNewlines),
              (3...60).contains(name.utf16.count),
              !name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
              name.rangeOfCharacter(from: .controlCharacters) == nil,
              fields["logoUrl"] == nil || fields["logoUrl"] is NSNull || fields["logoUrl"] is String
        else { return nil }
        return Self(clubId: id, clubName: name,
                    logoURL: trustedLogoURL(fields["logoUrl"] as? String, clubId: id))
    }

    private static func matchesID(_ value: String) -> Bool {
        value.range(of: "\\A[A-Za-z0-9_-]{1,128}\\z", options: .regularExpression) != nil
    }

    private static func trustedLogoURL(_ value: String?, clubId: String) -> URL? {
        guard let value, !value.isEmpty, value.utf16.count <= 2048,
              let parts = URLComponents(string: value),
              parts.scheme == "https", parts.host == "firebasestorage.googleapis.com",
              parts.user == nil, parts.password == nil, parts.port == nil, parts.fragment == nil,
              let path = parts.percentEncodedPath.removingPercentEncoding
        else { return nil }
        // Decode exactly once, matching java.net.URI.path on Android.
        let segments = path.components(separatedBy: "/")
        guard segments.count == 9, segments[0].isEmpty,
              segments[1] == "v0", segments[2] == "b", !segments[3].isEmpty,
              segments[4] == "o", segments[5] == "clubLogos", segments[6] == clubId,
              matchesID(segments[7]),
              segments[8].range(of: "\\A[A-Za-z0-9._-]{1,180}\\z", options: .regularExpression) != nil,
              segments[8] != ".", segments[8] != ".."
        else { return nil }
        return parts.url
    }
}
