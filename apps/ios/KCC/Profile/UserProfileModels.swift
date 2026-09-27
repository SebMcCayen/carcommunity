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
        youtube: String? = nil
    ) {
        self.displayName = displayName
        self.bio = bio
        self.avatarPath = avatarPath
        self.onboardingComplete = onboardingComplete
        self.facebook = facebook
        self.instagram = instagram
        self.youtube = youtube
    }

    /// Tolerant decoding of a `users/{uid}` document map: a missing or
    /// wrong-typed field degrades to nil (Android's `getString` semantics in
    /// `FirebaseProfileRepository.observeProfile`), never a decode failure —
    /// the contract marks displayName required, but a partially-provisioned
    /// document must still render.
    static func fromMap(_ map: [String: Any]) -> UserProfile {
        UserProfile(
            displayName: map["displayName"] as? String,
            bio: map["bio"] as? String,
            avatarPath: map["avatarPath"] as? String,
            onboardingComplete: map["onboardingCompletedAt"] != nil,
            facebook: map["facebook"] as? String,
            instagram: map["instagram"] as? String,
            youtube: map["youtube"] as? String
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
