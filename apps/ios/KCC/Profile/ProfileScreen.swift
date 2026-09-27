import ImageIO
import PhotosUI
import SwiftUI
import UIKit

struct ProfileScreen: View {
    let displayName: String?
    let onSignOut: () -> Void
    let onBack: () -> Void
    @State private var coordinator: ProfileCoordinator
    @State private var editor: ProfileEditCoordinator
    @State private var editing = false
    @State private var draft = ProfileDraft(profile: nil)
    @State private var validationError: ProfileValidationError?
    @State private var pickedPhoto: PhotosPickerItem?

    init(displayName: String?, onSignOut: @escaping () -> Void, onBack: @escaping () -> Void) {
        let repository = FirebaseUserProfileRepository.createIfAvailable()
        let uid = Self.signedInUid()
        self.init(
            displayName: displayName,
            onSignOut: onSignOut,
            onBack: onBack,
            coordinator: ProfileCoordinator(repository: repository, uid: uid),
            editor: ProfileEditCoordinator(repository: repository, uid: uid)
        )
    }

    init(
        displayName: String?, onSignOut: @escaping () -> Void, onBack: @escaping () -> Void,
        coordinator: ProfileCoordinator,
        editor: ProfileEditCoordinator? = nil
    ) {
        self.displayName = displayName
        self.onSignOut = onSignOut
        self.onBack = onBack
        _coordinator = State(initialValue: coordinator)
        _editor = State(initialValue: editor ?? ProfileEditCoordinator(repository: nil, uid: nil))
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: KccSpacing.s4) {
                HStack {
                    Button(action: onBack) { Label("profile.back", systemImage: "chevron.backward") }
                    Spacer()
                    if !editing {
                        Button("profile.editButton") { beginEditing() }
                            .disabled(!canEdit)
                    }
                }
                Text("profile.title")
                    .font(.system(size: KccTypeScale.headingLg, weight: .semibold))

                VStack(spacing: KccSpacing.s2) {
                    avatar
                    if editing {
                        PhotosPicker(selection: $pickedPhoto, matching: .images) {
                            Text(editor.status == .uploading ? "profile.avatarUploading" : "profile.avatarChange")
                        }
                        .disabled(editor.status == .uploading || editor.status == .saving)
                    }
                    nameText
                }
                .frame(maxWidth: .infinity)

                if editing { editForm } else { profileDetails }

                if editor.status == .failed {
                    Text("profile.saveError").foregroundStyle(.red)
                } else if editor.status == .tooLarge {
                    Text("profile.avatarTooLarge").foregroundStyle(.red)
                } else if editor.status == .saved {
                    Text("profile.saved").foregroundStyle(.secondary)
                }

                Button(action: onSignOut) {
                    Text("auth.signOut").frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.bordered)
                .padding(.top, KccSpacing.s6)
            }
            .padding(KccSpacing.s6)
        }
        .background(.background)
        .task { coordinator.start() }
        .onChange(of: pickedPhoto) { _, item in
            guard let item else { return }
            Task { await upload(item) }
        }
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
            Circle().fill(Color(.secondarySystemBackground))
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
        Text(verbatim: "?").font(.system(size: KccTypeScale.headingLg)).foregroundStyle(.secondary)
    }

    @ViewBuilder private var nameText: some View {
        if let resolvedDisplayName { Text(resolvedDisplayName).font(.system(size: KccTypeScale.titleMd, weight: .medium)) }
        else { Text("profile.emptyDisplayName").foregroundStyle(.secondary) }
    }

    @ViewBuilder private var profileDetails: some View {
        switch coordinator.state {
        case .loading: ProgressView().frame(maxWidth: .infinity)
        case .loaded(let profile):
            if let bio = profile?.bio?.trimmingCharacters(in: .whitespacesAndNewlines), !bio.isEmpty {
                Text(bio)
            } else { Text("profile.emptyBio").foregroundStyle(.secondary) }
        case .failed: Text("profile.loadError").foregroundStyle(.secondary)
        case .unavailable: EmptyView()
        }
    }

    private var editForm: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            TextField("profile.displayNameLabel", text: $draft.displayName)
                .textFieldStyle(.roundedBorder).textContentType(.nickname)
            TextField("profile.bioLabel", text: $draft.bio, axis: .vertical)
                .textFieldStyle(.roundedBorder).lineLimit(3...6)
            Text("profile.social.sectionTitle").font(.headline)
            Text("profile.social.publicNotice").font(.footnote).foregroundStyle(.secondary)
            socialField("profile.social.facebookLabel", text: $draft.facebook)
            socialField("profile.social.instagramLabel", text: $draft.instagram)
            socialField("profile.social.youtubeLabel", text: $draft.youtube)
            if let validationError {
                Text(validationKey(validationError)).foregroundStyle(.red)
            }
            HStack {
                Button("profile.cancelButton") { editing = false; validationError = nil; editor.reset() }
                    .buttonStyle(.bordered)
                Button("profile.saveButton") {
                    Task {
                        validationError = await editor.save(draft)
                        if validationError == nil, editor.status == .saved { editing = false }
                    }
                }
                .buttonStyle(.borderedProminent)
                .disabled(editor.status == .saving || editor.status == .uploading)
            }
        }
    }

    private func socialField(_ title: LocalizedStringKey, text: Binding<String>) -> some View {
        TextField(title, text: text, prompt: Text("profile.social.hint"))
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

    private static func signedInUid() -> String? {
        if case .signedIn(let uid, _)? = FirebaseAuthRepository.createIfAvailable()?.authState { return uid }
        return nil
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
