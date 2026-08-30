import SwiftUI

/// Panel CONTENT for the History tab — the read-only drives list, the iOS
/// slice of Android's `DrivesListScreen` (Phase 12 slice 12's read side).
/// Rendered inside the shell's `TranslucentShellPanel` like the other panel
/// tabs.
///
/// This slice: the list of saved drives (title, the neutral stats line, the
/// round photo of the driven car, and who the drive was driven with).
/// Recording, delete, share, the search/filter/sort bar, the personal stats
/// page, the drive detail, and the route-shape thumbnail all arrive with
/// later slices — the cards are display-only for now.
struct DrivesPanel: View {
    @State private var coordinator: DrivesCoordinator

    /// Production wiring: builds the coordinator from the feature-level
    /// factories (the same construction pattern as `ProfileScreen`'s
    /// wiring). In a config-less build both factories return nil
    /// and the coordinator settles on ``DrivesUiState/unavailable``.
    init() {
        self.init(
            coordinator: DrivesCoordinator(
                repository: FirebaseDrivesRepository.createIfAvailable(),
                uid: Self.signedInUid()
            )
        )
    }

    /// Preview/test seam: inject a coordinator (typically fed by a fake
    /// repository).
    init(coordinator: DrivesCoordinator) {
        _coordinator = State(initialValue: coordinator)
    }

    var body: some View {
        ScrollView {
            // Lazy: a drive history grows without bound (Android renders it
            // in a LazyColumn for the same reason), so only visible cards
            // are built.
            LazyVStack(alignment: .leading, spacing: KccSpacing.s4) {
                Text("savedDrives.screenTitle")
                    .font(.system(size: KccTypeScale.headingLg, weight: KccTypeScale.semibold))

                content
            }
            .padding(KccSpacing.s6)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .task { coordinator.start() }
    }

    @ViewBuilder
    private var content: some View {
        switch coordinator.state {
        case .loading:
            Text("savedDrives.loading")
                .font(.system(size: KccTypeScale.bodyMd))
                .foregroundStyle(.secondary)
        case .unavailable:
            // The config-less build. There is no dedicated "unavailable" key
            // in the savedDrives contract strings (Android has no such
            // state), so the generic load-error copy is the closest honest
            // message — the same reuse posture as EventsScreen's placeholder.
            Text("savedDrives.error")
                .font(.system(size: KccTypeScale.bodyMd))
                .foregroundStyle(.secondary)
        case .failed:
            Text("savedDrives.error")
                .font(.system(size: KccTypeScale.bodyMd))
                .foregroundStyle(KccPalette.errorRed)
            Button(action: { coordinator.reload() }) {
                Text("savedDrives.retry")
                    .font(.system(size: KccTypeScale.bodyMd, weight: KccTypeScale.semibold))
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.bordered)
        case .empty:
            Text("savedDrives.empty")
                .font(.system(size: KccTypeScale.bodyMd))
                .foregroundStyle(.secondary)
        case .loaded(let drives):
            ForEach(drives) { drive in
                DriveHistoryCard(
                    drive: drive,
                    carImageURL: drive.carImagePath.flatMap { coordinator.imageURLs[$0] }
                )
            }
        }
    }

    /// The signed-in uid from the process-wide auth repository, nil when
    /// Firebase is unconfigured or no session exists — read here (feature
    /// level) so the shell keeps constructing this panel argument-free, the
    /// same seam as `ProfileScreen`.
    private static func signedInUid() -> String? {
        if case .signedIn(let uid, _)? = FirebaseAuthRepository.createIfAvailable()?.authState {
            return uid
        }
        return nil
    }
}

/// One saved drive: the headline (title, or the save date), the neutral
/// stats line, the round photo of the driven car, and the "drove with" row
/// for convoy drives — the display half of Android's `DriveCard` (its
/// share/delete actions and the route-shape thumbnail arrive with later
/// slices).
struct DriveHistoryCard: View {
    let drive: SavedDrive
    /// The resolved car-photo URL; nil keeps the placeholder (a missing
    /// picture is cosmetic, never an error state).
    let carImageURL: URL?

    /// Diameter of the round driven-car photo — half the 96pt profile
    /// avatar, which uses the same circular treatment, so the photo reads
    /// as a list adornment rather than the row's subject.
    private static let photoDiameter: CGFloat = 48

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s2) {
            HStack(alignment: .center, spacing: KccSpacing.s3) {
                VStack(alignment: .leading, spacing: KccSpacing.s1) {
                    Text(headline)
                        .font(.system(size: KccTypeScale.titleMd, weight: KccTypeScale.medium))

                    // Distance, duration and maximum speed in ONE line, one
                    // style, one colour, in that order. Max speed is a fact
                    // about the drive exactly like the other two and is
                    // rendered exactly like them — no emphasis, no colour
                    // that rewards a bigger number, nothing to compare it
                    // against. See SavedDrive.maxSpeedMetersPerSecond.
                    Text(statsLine)
                        .font(.system(size: KccTypeScale.bodySm))
                        .foregroundStyle(.secondary)
                }

                Spacer(minLength: 0)

                // A round photo of the car this drive was driven in, at the
                // row's trailing edge. Renders nothing when the drive
                // recorded no car (older drives, or a drive with no car), so
                // the layout is unchanged for those.
                if drive.carImagePath != nil {
                    carPhoto
                }
            }

            // Who this drive was driven with, when it was a convoy drive.
            // Renders nothing for a solo drive (empty roster), so those
            // cards are unchanged.
            if !drive.convoyMembers.isEmpty {
                Label {
                    Text(droveWithLine)
                        .font(.system(size: KccTypeScale.bodySm))
                        .foregroundStyle(.secondary)
                } icon: {
                    Image(systemName: "person.2")
                        .font(.system(size: KccTypeScale.bodySm))
                        .foregroundStyle(.secondary)
                }
            }
        }
        .padding(KccSpacing.s4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: KccRadius.md))
    }

    /// The drive's title, or the save date for an untitled drive, or the
    /// neutral detail-title label when even the dates are missing —
    /// Android's `driveTitle`.
    private var headline: String {
        if let title = drive.title?.trimmingCharacters(in: .whitespacesAndNewlines),
            !title.isEmpty {
            return title
        }
        if let date = drive.createdAt ?? drive.startedAt {
            return date.formatted(date: .abbreviated, time: .omitted)
        }
        return String(localized: "savedDrives.detailTitle")
    }

    private var statsLine: String {
        let maxSpeed = String(
            format: String(localized: "savedDrives.maxSpeedShort"),
            // Nil (no stored value) formats as the missing-value dash,
            // never as "0 km/h".
            DriveFormatters.formatSpeed(drive.maxSpeedMetersPerSecond)
        )
        return [
            DriveFormatters.formatDistance(drive.distanceMeters),
            DriveFormatters.formatDuration(drive.durationSeconds),
            maxSpeed,
        ].joined(separator: " · ")
    }

    private var droveWithLine: String {
        String(
            format: String(localized: "savedDrives.convoyDroveWith"),
            ConvoyDriveMembers.joinedNames(
                drive.convoyMembers,
                unknownLabel: String(localized: "savedDrives.convoyMemberUnknown")
            )
        )
    }

    private var carPhoto: some View {
        ZStack {
            Circle()
                .fill(Color(.tertiarySystemBackground))
            if let carImageURL {
                AsyncImage(url: carImageURL) { image in
                    image
                        .resizable()
                        .scaledToFill()
                } placeholder: {
                    carPhotoPlaceholder
                }
            } else {
                carPhotoPlaceholder
            }
        }
        .frame(width: Self.photoDiameter, height: Self.photoDiameter)
        .clipShape(Circle())
        .accessibilityLabel(Text("savedDrives.carPhotoDescription"))
    }

    private var carPhotoPlaceholder: some View {
        Image(systemName: "car")
            .font(.system(size: KccTypeScale.bodyMd))
            .foregroundStyle(.secondary)
    }
}

#Preview("Loaded") {
    DriveHistoryCard(
        drive: SavedDrive(
            id: "ride-1",
            title: "Kvällsrunda",
            distanceMeters: 12_345,
            durationSeconds: 1_845,
            averageSpeedMetersPerSecond: 12.4,
            startedAt: .now,
            endedAt: .now,
            createdAt: .now,
            maxSpeedMetersPerSecond: 24.7,
            carImagePath: nil,
            convoyMembers: [
                ConvoyDriveMember(uid: "u2", displayName: "Alex", avatarPath: nil)
            ]
        ),
        carImageURL: nil
    )
    .padding()
}
