import FirebaseFirestore
import ImageIO
import PhotosUI
import SwiftUI
import UIKit

struct ProfileScreen: View {
    let uid: String?
    let displayName: String?
    let onSignOut: () -> Bool
    let onBack: () -> Void
    let onOpenPoints: (() -> Void)?
    let onOpenDrives: (() -> Void)?
    let onOpenWhatsNew: (() -> Void)?
    let onOpenAccountDeletion: (() -> Void)?
    @State private var coordinator: ProfileCoordinator
    @State private var editor: ProfileEditCoordinator
    @State private var pointsCoordinator: PointsCoordinator
    @State private var badgesCoordinator: BadgesCoordinator
    @State private var editing = false
    @State private var draft = ProfileDraft(profile: nil)
    @State private var validationError: ProfileValidationError?
    @State private var pickedPhoto: PhotosPickerItem?
    @State private var showsBlockedUsers = false
    @State private var signOutFailed = false

    init(
        uid: String?, displayName: String?, onSignOut: @escaping () -> Bool,
        onBack: @escaping () -> Void, onOpenPoints: @escaping () -> Void,
        onOpenDrives: (() -> Void)? = nil,
        onOpenWhatsNew: (() -> Void)? = nil,
        onOpenAccountDeletion: @escaping () -> Void
    ) {
        let repository = FirebaseUserProfileRepository.createIfAvailable()
        let pointsRepository = FirebasePointsRepository.createIfAvailable()
        self.init(
            uid: uid,
            displayName: displayName,
            onSignOut: onSignOut,
            onBack: onBack,
            onOpenPoints: pointsRepository == nil ? nil : onOpenPoints,
            onOpenDrives: onOpenDrives,
            onOpenWhatsNew: onOpenWhatsNew,
            onOpenAccountDeletion: FirebaseAccountDeletionRepository.createIfAvailable() == nil
                ? nil : onOpenAccountDeletion,
            coordinator: ProfileCoordinator(repository: repository, uid: uid),
            editor: ProfileEditCoordinator(repository: repository, uid: uid),
            pointsCoordinator: PointsCoordinator(repository: pointsRepository, uid: uid),
            badgesCoordinator: BadgesCoordinator(
                repository: FirebaseBadgesRepository.createIfAvailable(), uid: uid
            )
        )
    }

    init(
        uid: String? = nil, displayName: String?, onSignOut: @escaping () -> Bool,
        onBack: @escaping () -> Void,
        onOpenPoints: (() -> Void)? = nil,
        onOpenDrives: (() -> Void)? = nil,
        onOpenWhatsNew: (() -> Void)? = nil,
        onOpenAccountDeletion: (() -> Void)? = nil,
        coordinator: ProfileCoordinator,
        editor: ProfileEditCoordinator? = nil,
        pointsCoordinator: PointsCoordinator? = nil,
        badgesCoordinator: BadgesCoordinator? = nil
    ) {
        self.uid = uid
        self.displayName = displayName
        self.onSignOut = onSignOut
        self.onBack = onBack
        self.onOpenPoints = onOpenPoints
        self.onOpenDrives = onOpenDrives
        self.onOpenWhatsNew = onOpenWhatsNew
        self.onOpenAccountDeletion = onOpenAccountDeletion
        _coordinator = State(initialValue: coordinator)
        _editor = State(initialValue: editor ?? ProfileEditCoordinator(repository: nil, uid: nil))
        _pointsCoordinator = State(initialValue: pointsCoordinator ?? PointsCoordinator(
            repository: nil, uid: nil
        ))
        _badgesCoordinator = State(initialValue: badgesCoordinator ?? BadgesCoordinator(
            repository: nil, uid: nil
        ))
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Button(action: onBack) {
                    Label("profile.back", systemImage: "chevron.backward")
                        .frame(minHeight: 48).contentShape(Rectangle())
                }
                    .accessibilityIdentifier("profile.back")
                Spacer()
            }
            .padding(.horizontal, KccSpacing.s4)
            ScrollView {
                VStack(alignment: .leading, spacing: KccSpacing.s4) {
                    Text("profile.title")
                        .font(.title2.bold())

                    VStack(spacing: KccSpacing.s2) {
                        avatar
                        if editing {
                            PhotosPicker(selection: $pickedPhoto, matching: .images) {
                                Text(editor.status == .uploading ? "profile.avatarUploading" : "profile.avatarChange")
                                .frame(minHeight: 48).contentShape(Rectangle())
                            }
                            .disabled(editor.status == .uploading || editor.status == .saving)
                        }
                        nameText
                        if !editing { profileDetails }
                    }
                    .frame(maxWidth: .infinity)
                    .kccContentCard()
                    .accessibilityIdentifier("profile.identityCard")

                    if !editing {
                        Button { beginEditing() } label: {
                            Text("profile.editButton").frame(maxWidth: .infinity, minHeight: 48)
                        }
                            .kccPrimaryAction()
                            .disabled(!canEdit)
                            .accessibilityIdentifier("profile.edit")
                    }
                    if editing {
                        editForm
                    } else {
                        if let onOpenDrives {
                            Button(action: onOpenDrives) {
                                HStack {
                                    Label("shell.moreDrives", systemImage: "chart.bar.xaxis")
                                    Spacer()
                                    Image(systemName: "chevron.forward").accessibilityHidden(true)
                                }
                                .frame(minHeight: 48)
                                .padding(KccSpacing.s3)
                                .background(KccTheme.subtle, in: RoundedRectangle(cornerRadius: KccRadius.md))
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("profile.myDrives")
                        }
                        if let uid { AccountWarningsSection(uid: uid).id(uid) }
                        ProfilePointsSection(
                            balance: pointsCoordinator.balance,
                            recentEarnings: pointsCoordinator.recentEarnings,
                            onOpenLedger: onOpenPoints
                        )
                        BadgesWall(coordinator: badgesCoordinator, isEmbedded: true)
                    }

                    if editor.status == .failed {
                        Text("profile.saveError").foregroundStyle(.red)
                    } else if editor.status == .tooLarge {
                        Text("profile.avatarTooLarge").foregroundStyle(.red)
                    } else if editor.status == .saved {
                        Text("profile.saved").foregroundStyle(.secondary)
                    }

                    Button {
                        showsBlockedUsers = true
                    } label: {
                        HStack {
                            Label("settings.blockedUsers", systemImage: "person.crop.circle.badge.xmark")
                            Spacer()
                            Image(systemName: "chevron.forward")
                                .accessibilityHidden(true)
                        }
                        .frame(minHeight: 48)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("profile.blockedUsers")

                    if let onOpenWhatsNew {
                        Button(action: onOpenWhatsNew) {
                            HStack {
                                Label("whatsNew.title", systemImage: "sparkles")
                                Spacer()
                                Image(systemName: "chevron.forward")
                                    .accessibilityHidden(true)
                            }
                            .frame(minHeight: 48)
                        .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("profile.whatsNew")
                    }

                    if let onOpenAccountDeletion {
                        Button(role: .destructive, action: onOpenAccountDeletion) {
                            HStack {
                                Label("settings.accountDeletion", systemImage: "trash")
                                Spacer()
                                Image(systemName: "chevron.forward")
                                    .accessibilityHidden(true)
                            }
                            .frame(minHeight: 48)
                        .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("profile.accountDeletion")
                    }

                    if signOutFailed {
                        Text("auth.signOutError")
                            .foregroundStyle(KccTheme.error)
                    }

                    Button(action: attemptSignOut) {
                        Text("auth.signOut").frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .buttonStyle(.bordered)
                    .padding(.top, KccSpacing.s6)
                }
                .padding(KccSpacing.s4)
            }
        }
        .kccReadingSurface(pageBackground: true)
        .accessibilityIdentifier("profile.screen")
        .task {
            coordinator.start()
            pointsCoordinator.start()
        }
        .onChange(of: pickedPhoto) { _, item in
            guard let item else { return }
            Task { await upload(item) }
        }
        .fullScreenCover(isPresented: $showsBlockedUsers) {
            BlockedUsersScreen(uid: uid) { showsBlockedUsers = false }
        }
    }

    private func attemptSignOut() {
        signOutFailed = !onSignOut()
    }

    private var canEdit: Bool {
        if case .loaded = coordinator.state { return true }
        return false
    }

    private var loadedProfile: UserProfile? {
        if case .loaded(let profile) = coordinator.state { return profile }
        return nil
    }

    private var resolvedDisplayName: String? {
        let profileName = loadedProfile?.displayName?.trimmingCharacters(in: .whitespacesAndNewlines)
        if let profileName, !profileName.isEmpty { return profileName }
        let authName = displayName?.trimmingCharacters(in: .whitespacesAndNewlines)
        return authName?.isEmpty == false ? authName : nil
    }

    private var avatar: some View {
        ZStack {
            Circle().fill(KccTheme.subtle)
            if let url = coordinator.avatarURL {
                AsyncImage(url: url) { image in image.resizable().scaledToFill() }
                    placeholder: { avatarPlaceholder }
            } else { avatarPlaceholder }
        }
        .frame(width: 96, height: 96)
        .clipShape(Circle())
        .accessibilityLabel(Text("profile.avatarAlt"))
    }

    private var avatarPlaceholder: some View {
        Image(systemName: "person.fill").font(.largeTitle).foregroundStyle(KccTheme.secondary)
    }

    @ViewBuilder private var nameText: some View {
        if let resolvedDisplayName { Text(resolvedDisplayName).font(.title2.weight(.semibold)).multilineTextAlignment(.center) }
        else { Text("profile.emptyDisplayName").foregroundStyle(.secondary) }
    }

    @ViewBuilder private var profileDetails: some View {
        switch coordinator.state {
        case .loading: ProgressView().frame(maxWidth: .infinity)
        case .loaded(let profile):
            if let bio = profile?.bio?.trimmingCharacters(in: .whitespacesAndNewlines), !bio.isEmpty {
                Text(bio).font(.body).frame(maxWidth: .infinity, alignment: .leading)
            } else { Text("profile.emptyBio").foregroundStyle(.secondary) }
        case .failed: Text("profile.loadError").foregroundStyle(.secondary)
        case .unavailable: EmptyView()
        }
    }

    private var editForm: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            EditorTextField(title: "profile.displayNameLabel", text: $draft.displayName)
                .textFieldStyle(.roundedBorder).textContentType(.nickname)
            EditorTextField(title: "profile.bioLabel", text: $draft.bio, multiline: true)
            Text("profile.social.sectionTitle").font(.headline)
            Text("profile.social.publicNotice").font(.footnote).foregroundStyle(.secondary)
            Text("profile.social.hint").font(.footnote).foregroundStyle(KccTheme.secondary)
            socialField("profile.social.facebookLabel", text: $draft.facebook)
            socialField("profile.social.instagramLabel", text: $draft.instagram)
            socialField("profile.social.youtubeLabel", text: $draft.youtube)
            if let validationError {
                Text(validationKey(validationError)).foregroundStyle(.red)
            }
            SocialFlow {
                Button { editing = false; validationError = nil; editor.reset() } label: { Text("profile.cancelButton").frame(minHeight: 48) }
                    .buttonStyle(.bordered)
                Button {
                    Task {
                        validationError = await editor.save(draft)
                        if validationError == nil, editor.status == .saved { editing = false }
                    }
                } label: { EditorSubmitLabel(title: "profile.saveButton", saving: editor.status == .saving) }
                .kccPrimaryAction()
                .disabled(editor.status == .saving || editor.status == .uploading)
            }
        }.kccContentCard().disabled(editor.status == .saving || editor.status == .uploading)
    }

    private func socialField(_ title: LocalizedStringKey, text: Binding<String>) -> some View {
        EditorTextField(title: title, text: text)
            .textFieldStyle(.roundedBorder).textInputAutocapitalization(.never).autocorrectionDisabled()
    }

    private func validationKey(_ error: ProfileValidationError) -> LocalizedStringKey {
        switch error {
        case .nameRequired: "profile.errorNameRequired"
        case .tooLong: "profile.errorTooLong"
        case .invalidSocial: "profile.social.errorMalformed"
        }
    }

    private func beginEditing() {
        draft = ProfileDraft(profile: loadedProfile)
        validationError = nil
        editor.reset()
        editing = true
    }

    private func upload(_ item: PhotosPickerItem) async {
        defer { pickedPhoto = nil }
        guard let raw = try? await item.loadTransferable(type: Data.self),
              let jpeg = AvatarImageProcessor.jpegData(from: raw)
        else { editor.markUploadFailed(); return }
        await editor.uploadAvatar(jpegData: jpeg)
    }

}

enum AvatarImageProcessor {
    static func jpegData(from data: Data, maxDimension: CGFloat = 2048) -> Data? {
        // Bound the encoded input before ImageIO sees it, then request a thumbnail
        // so a huge-pixel image is never fully decompressed into app memory.
        guard !data.isEmpty, data.count <= 50 * 1024 * 1024,
              let source = CGImageSourceCreateWithData(data as CFData, nil),
              let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [
                kCGImageSourceCreateThumbnailFromImageAlways: true,
                kCGImageSourceCreateThumbnailWithTransform: true,
                kCGImageSourceThumbnailMaxPixelSize: Int(maxDimension),
              ] as CFDictionary)
        else { return nil }
        let normalized = UIImage(cgImage: image)
        for quality in stride(from: CGFloat(0.85), through: CGFloat(0.35), by: -0.1) {
            if let encoded = normalized.jpegData(compressionQuality: quality),
               encoded.count <= 5 * 1024 * 1024 {
                return encoded
            }
        }
        return nil
    }
}

// The warning reason is never read from the public profile or Firestore directly.
// Server remaining time is measured against uptime, independent of device clock.
struct AccountWarning: Identifiable, Sendable {
    let id: String
    let reason: String
    let expiresAt: String?
    let deadline: ContinuousClock.Instant?
    func active(at uptime: ContinuousClock.Instant) -> Bool { deadline.map { uptime < $0 } ?? true }
    static func decode(_ data: [String: Any], requestStarted: ContinuousClock.Instant) -> AccountWarning? {
        guard let id = data["id"] as? String, let reason = data["reason"] as? String else { return nil }
        let remaining = data["remainingMillis"] as? NSNumber
        return AccountWarning(id: id, reason: reason, expiresAt: data["expiresAt"] as? String,
            deadline: remaining.map { requestStarted.advanced(by: .milliseconds(max(0, $0.doubleValue))) })
    }
}

struct AccountWarningsSection: View {
    let uid: String
    @State private var warnings: [AccountWarning] = []
    @State private var cursor: String?
    @State private var loading = true
    @State private var failed = false
    @State private var retryAppend = false
    @State private var refreshID = UUID()
    var body: some View {
        Group {
            if KccFunctionsClient.createIfAvailable() != nil {
                VStack(alignment: .leading, spacing: KccSpacing.s2) {
                    Text("accountWarnings.title").font(.headline).accessibilityAddTraits(.isHeader)
                    if loading { Text("accountWarnings.loading") }
                    if failed { Text("accountWarnings.error").foregroundStyle(KccTheme.error) }
                    if !loading && !failed && warnings.isEmpty { Text("accountWarnings.empty") }
                    ForEach(warnings) { warning in
                        Text(verbatim: warning.reason).font(.body).fixedSize(horizontal: false, vertical: true)
                        if let expiry = warning.expiresAt, let date = Self.expiryDate(expiry) {
                            Text("accountWarnings.expires") + Text(" ") + Text(date, style: .date) + Text(" ") + Text(date, style: .time)
                        } else { Text("accountWarnings.untilRevoked") }
                    }
                    if failed { Button { refreshID = UUID() } label: { Text("accountWarnings.retry").frame(minHeight: 48) }.disabled(loading) }
                    if cursor != nil && !failed { Button { retryAppend = true; refreshID = UUID() } label: { Text("accountWarnings.more").frame(minHeight: 48) }.disabled(loading) }
                }
                .kccContentCard()
                .task(id: refreshID) { await load(append: retryAppend) }
                .task(id: uid) {
                    // Refresh while visible even if a signal was missed offline.
                    while !Task.isCancelled {
                        do { try await Task.sleep(for: .seconds(30)) } catch { return }
                        retryAppend = false; refreshID = UUID()
                    }
                }
                .task(id: "signal-\(uid)") {
                    let stream = AsyncStream<Void> { continuation in
                        let registration = Firestore.firestore().collection("accountWarningSignals").document(uid)
                            .addSnapshotListener { snapshot, error in
                                if error == nil && snapshot?.metadata.isFromCache == false { continuation.yield(()) }
                            }
                        let box = WarningSignalListener(registration)
                        continuation.onTermination = { _ in box.registration.remove() }
                    }
                    for await _ in stream {
                        if Task.isCancelled { return }
                        retryAppend = false; refreshID = UUID()
                    }
                }
                .task(id: "expiry-\(uid)") {
                    while !Task.isCancelled {
                        do { try await Task.sleep(for: .seconds(1)) } catch { return }
                        warnings.removeAll { !$0.active(at: ContinuousClock().now) }
                    }
                }
            }
        }
    }
    private static func expiryDate(_ value: String) -> Date? {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter.date(from: value)
    }
    @MainActor private func load(append: Bool) async {
        guard let client = KccFunctionsClient.createIfAvailable() else { return }
        loading = true
        let started = ContinuousClock().now
        do {
            // Keep actor-owned state in a Sendable value before the callable's
            // untyped JSON conversion. An absent cursor requests the first page.
            let requestCursor = append ? cursor : nil
            let payload: [String: String] = requestCursor.map { ["cursor": $0] } ?? [:]
            let response = try await client.call("account-listWarnings", payload: payload)
            try Task.checkCancellation()
            guard let page = response as? [String: Any], page["userId"] as? String == uid,
                let rows = page["warnings"] as? [[String: Any]], let meta = page["meta"] as? [String: Any]
            else { throw CancellationError() }
            var seen = Set<String>()
            warnings = ((append ? warnings : []) + rows.compactMap { AccountWarning.decode($0, requestStarted: started) })
                .filter { $0.active(at: ContinuousClock().now) && seen.insert($0.id).inserted }
            cursor = meta["nextCursor"] as? String
            failed = false; retryAppend = false
        } catch {
            if Task.isCancelled { return }
            if !append { warnings = [] }
            failed = true; retryAppend = append
        }
        loading = false
    }
}

private final class WarningSignalListener: @unchecked Sendable {
    let registration: ListenerRegistration
    init(_ registration: ListenerRegistration) { self.registration = registration }
}
