import SwiftUI

/// Live-location session control surface — the iOS port of Android's
/// `live/LiveLocationScreen.kt`, for `ShellRoute.liveLocation` (the wiring PR
/// adds the shell route/toggle; this screen is exported ready).
///
/// Drives the caller's own session: start sharing immediately (no
/// time/duration is chosen — the fixed 6h window auto-stops), stop sharing,
/// or "Hide me now" (a privacy stop that is ALWAYS available). Whether the
/// user is currently sharing is derived from the observed session via the
/// coordinator; this screen is otherwise stateless.
///
/// Gating mirrors the ported ``LiveManageSheet`` semantics: starting is
/// gated on ``LiveLocationCoordinator/canShare`` (the LIVE_LOCATION feature
/// flag — NOT a membership gate, sharing your own position is free), and the
/// blocked state renders the flag-off notice (`liveLocation.shareUnavailable`).
/// Deliberate deviation from the Android screen, which still shows its older
/// member-required copy there: the ported `LiveManageRows` documentation is
/// explicit that this notice "must not claim a membership is required", so
/// the iOS screen uses the flag-accurate string from the start.
///
/// The audience section is informational (Android parity): who can see the
/// marker is decided by the backend (RTDB read rule: any signed-in,
/// non-suspended viewer not in a block relationship), so the screen explains
/// rather than configures.
struct LiveLocationScreen: View {
    @State private var coordinator: LiveLocationCoordinator
    private let onBack: (() -> Void)?

    /// The screen owns no provider/repository choices: the caller (the
    /// wiring PR's shell, previews, tests) supplies the coordinator —
    /// typically ``LiveLocationCoordinator/live(provider:)`` with the
    /// shell's shared ``LocationProvider``.
    init(coordinator: LiveLocationCoordinator, onBack: (() -> Void)? = nil) {
        _coordinator = State(initialValue: coordinator)
        self.onBack = onBack
    }

    var body: some View {
        let sharing = coordinator.isSharing
        let busy = coordinator.actionStatus == .working

        ScrollView {
            VStack(alignment: .leading, spacing: KccSpacing.s4) {
                if let onBack {
                    Button(action: onBack) {
                        Label("shell.back", systemImage: "chevron.backward")
                            .font(.system(size: KccTypeScale.bodyMd))
                    }
                }

                Text("liveLocation.screenTitle")
                    .font(.system(size: KccTypeScale.headingLg, weight: .semibold))
                    .padding(.top, KccSpacing.s2)

                // Current sharing status.
                Text(sharing ? "liveLocation.statusSharing" : "liveLocation.statusNotSharing")
                    .font(.system(size: KccTypeScale.titleMd, weight: .medium))
                if sharing {
                    Text("liveLocation.sessionAutoExpires")
                        .font(.system(size: KccTypeScale.bodySm))
                        .foregroundStyle(.secondary)
                }

                if sharing {
                    // Stopping an active session is authenticated-gated (not
                    // member/flag-gated) on the backend, so ALWAYS offer Stop
                    // while sharing — even if the flag state has lapsed since
                    // the session started. The flag only gates STARTING.
                    Button {
                        Task { await coordinator.stopSharing() }
                    } label: {
                        Text("liveLocation.stop")
                            .font(.system(size: KccTypeScale.bodyMd, weight: .semibold))
                            .frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(busy)
                } else if coordinator.canShare && coordinator.wired {
                    // Starting is IMMEDIATE — no time/duration is chosen. The
                    // fixed 6h default window auto-stops with no prompt to
                    // prolong; the user can Stop anytime.
                    Button {
                        Task { await coordinator.startSharing() }
                    } label: {
                        Text("liveLocation.start")
                            .font(.system(size: KccTypeScale.bodyMd, weight: .semibold))
                            .frame(maxWidth: .infinity, minHeight: 44)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(busy)
                } else {
                    // Start is blocked: the LIVE_LOCATION flag is off, or
                    // this is a config-less/signed-out build. Explain instead
                    // of offering a dead button; "Hide me now" below stays.
                    Text("liveLocation.shareUnavailable")
                        .font(.system(size: KccTypeScale.bodyMd))
                        .foregroundStyle(.secondary)
                        .padding(KccSpacing.s4)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(
                            RoundedRectangle(cornerRadius: KccRadius.md)
                                .fill(Color(.secondarySystemBackground))
                        )
                }

                // Privacy action — never gated, always offered.
                Button {
                    Task { await coordinator.hideMeNow() }
                } label: {
                    Text("liveLocation.hideNow")
                        .font(.system(size: KccTypeScale.bodyMd, weight: .semibold))
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.bordered)
                .disabled(busy)

                if coordinator.actionStatus == .failed {
                    Text("liveLocation.error")
                        .font(.system(size: KccTypeScale.bodyMd))
                        .foregroundStyle(KccPalette.errorRed)
                }

                // Audience — informational, per Android's screen: the
                // backend decides who can see live locations.
                infoCard(
                    title: "liveLocation.whoCanSeeTitle",
                    body: "liveLocation.whoCanSeeBody"
                )
                VStack(alignment: .leading, spacing: KccSpacing.s1) {
                    privacyLine("liveLocation.privacyOptional")
                    privacyLine("liveLocation.privacyTimeLimited")
                    privacyLine("liveLocation.privacyStopAnytime")
                }
            }
            .padding(KccSpacing.s6)
            .frame(maxWidth: .infinity, alignment: .topLeading)
        }
        // Renders as a full-screen route above the tab shell, so it paints
        // an opaque, scheme-aware background of its own.
        .background(.background, ignoresSafeAreaEdges: .all)
        .task { coordinator.start() }
    }

    private func infoCard(title: LocalizedStringKey, body: LocalizedStringKey) -> some View {
        VStack(alignment: .leading, spacing: KccSpacing.s1) {
            Text(title)
                .font(.system(size: KccTypeScale.bodyMd, weight: .semibold))
            Text(body)
                .font(.system(size: KccTypeScale.bodyMd))
                .foregroundStyle(.secondary)
        }
        .padding(KccSpacing.s4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(
            RoundedRectangle(cornerRadius: KccRadius.md)
                .fill(Color(.secondarySystemBackground))
        )
    }

    private func privacyLine(_ key: LocalizedStringKey) -> some View {
        Text(key)
            .font(.system(size: KccTypeScale.bodySm))
            .foregroundStyle(.secondary)
    }
}

#Preview("Not sharing (unwired build)") {
    LiveLocationScreen(
        coordinator: LiveLocationCoordinator(
            repository: nil,
            provider: StubLocationProvider()
        ),
        onBack: {}
    )
}
