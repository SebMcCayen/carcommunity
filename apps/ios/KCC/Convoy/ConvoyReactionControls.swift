import SwiftUI

/// Map overlay for the transient reaction controls and incoming reaction pop.
struct ConvoyReactionControls: View {
    static let buttonHeight: CGFloat = 56
    static var controlsClearance: CGFloat { buttonHeight + KccSpacing.s3 }

    @Bindable var coordinator: ConvoyReactionCoordinator
    let followMeCoordinator: ConvoyFollowMeCoordinator?
    let bottomClearance: CGFloat

    init(
        coordinator: ConvoyReactionCoordinator,
        followMeCoordinator: ConvoyFollowMeCoordinator? = nil,
        bottomClearance: CGFloat = 0
    ) {
        self.coordinator = coordinator
        self.followMeCoordinator = followMeCoordinator
        self.bottomClearance = bottomClearance
    }

    var body: some View {
        ZStack {
            if let event = coordinator.incomingReaction {
                MapReactionPop(
                    symbol: event.kind.systemImage, tint: event.kind.color, caption: event.caption
                )
                .accessibilityIdentifier("convoy_reaction_overlay")
                .id(event.id)
                .transition(.scale(scale: 0.35).combined(with: .opacity))
                .allowsHitTesting(false)
            }

            VStack {
                Spacer()
                TimelineView(.periodic(from: .now, by: 0.5)) { context in
                    HStack(spacing: KccSpacing.s2) {
                        ForEach(ConvoyReactionKind.allCases) { kind in
                            reactionButton(kind, now: context.date)
                        }
                    }
                    .frame(maxWidth: 456)
                    .padding(.horizontal, KccSpacing.s3)
                }
                .padding(.bottom, bottomClearance + KccSpacing.s3)
            }
        }
        .animation(.spring(response: 0.3, dampingFraction: 0.7), value: coordinator.incomingReaction?.id)
    }

    private func reactionButton(_ kind: ConvoyReactionKind, now: Date) -> some View {
        let nowMilliseconds = Int64(now.timeIntervalSince1970 * 1_000)
        let remaining = coordinator.remainingMilliseconds(
            for: kind,
            nowMilliseconds: nowMilliseconds
        )
        let persistentFollowMe = kind == .followMe ? followMeCoordinator : nil
        let active = persistentFollowMe?.isLeading == true
        let ready = persistentFollowMe.map { !$0.isToggling }
            ?? (remaining == 0 && !coordinator.sendingKinds.contains(kind))
        let seconds = max(Int(ceil(Double(remaining) / 1_000)), 1)

        return Button {
            if let persistentFollowMe {
                let activate = !persistentFollowMe.isLeading
                Task {
                    _ = await persistentFollowMe.setLeading(activate) {
                        await coordinator.send(.followMe)
                    }
                }
            } else {
                Task { await coordinator.send(kind) }
            }
        } label: {
            ConvoyReactionButtonFace(
                kind: kind,
                ready: ready,
                active: active,
                pending: persistentFollowMe?.isToggling == true || coordinator.sendingKinds.contains(kind),
                remainingFraction: persistentFollowMe == nil
                    ? min(max(Double(remaining) / Double(kind.cooldownMilliseconds), 0), 1) : 0
            )
        }
        .buttonStyle(.plain)
        .disabled(!ready)
        .accessibilityLabel(Text(active ? "convoyFollowTrail.activeLabel" : kind.labelKey))
        .accessibilityValue(
            active
                ? Text("convoyFollowTrail.activeContentDescription")
                : (remaining > 0 && persistentFollowMe == nil
                    ? Text(String(format: String(localized: "convoyReaction.cooldown"), seconds)) : Text(""))
        )
        .accessibilityAddTraits(active ? [.isSelected] : [])
        .accessibilityIdentifier("convoy_reaction_\(kind.rawValue)")
    }
}

/// Shared map material and geometry; no visible labels or countdown numerals.
struct ConvoyReactionButtonFace: View {
    @Environment(\.colorScheme) private var scheme
    let kind: ConvoyReactionKind
    let ready: Bool
    let active: Bool
    let pending: Bool
    let remainingFraction: Double

    var body: some View {
        let accent = kind.color
        let surface = scheme == .dark ? KccDarkColors.overlayBackground : KccLightColors.overlayBackground
        let border = scheme == .dark ? KccDarkColors.overlayBorder : KccLightColors.overlayBorder
        let secondary = scheme == .dark ? KccDarkColors.textSecondary : KccLightColors.textSecondary
        let shape = RoundedRectangle(cornerRadius: KccRadius.md)
        ZStack {
            icon
                .resizable()
                .scaledToFit()
                .frame(width: 28, height: 28)
                .foregroundStyle(ready || active ? accent : secondary.opacity(0.45))
                .accessibilityHidden(true)
        }
        .frame(maxWidth: .infinity)
        .frame(height: ConvoyReactionControls.buttonHeight)
        .background(surface)
        .overlay(alignment: .bottomLeading) {
            GeometryReader { geometry in
                Rectangle()
                    .fill(accent.opacity(ready || active ? 1 : 0.45))
                    .frame(width: geometry.size.width * (remainingFraction > 0 ? remainingFraction : 1))
            }
            .frame(height: active ? 4 : 3)
            .accessibilityHidden(true)
        }
        .overlay(alignment: .topTrailing) {
            if pending {
                ProgressView()
                    .controlSize(.small)
                    // This hidden decorative indicator must not grow over the
                    // reaction glyph at accessibility text sizes. The native
                    // small spinner is 20pt; reserve a 12pt corner footprint.
                    .dynamicTypeSize(.large)
                    .scaleEffect(0.6)
                    .frame(width: 12, height: 12)
                    .tint(accent)
                    .padding(6)
                    .accessibilityHidden(true)
            }
        }
        .clipShape(shape)
        .overlay { shape.strokeBorder(active ? accent : border, lineWidth: active ? 2 : 1) }
        .shadow(color: .black.opacity(scheme == .dark ? 0.3 : 0.18), radius: 3, y: 2)
        .contentShape(shape)
    }

    private var icon: Image {
        switch kind {
        case .police: Image("incident-police")
        case .hello: Image("convoy-hand")
        case .followMe: Image(systemName: kind.systemImage)
        }
    }
}

struct MapReactionPop: View {
    let symbol: String
    let tint: Color
    let caption: String
    var animateWave = false

    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        VStack(spacing: KccSpacing.s2) {
            ZStack {
                Circle().fill(tint)
                if animateWave && !reduceMotion {
                    icon.phaseAnimator([0.0, -16, 16, -10]) { content, degrees in
                        content.rotationEffect(.degrees(degrees))
                    } animation: { _ in
                        .easeInOut(duration: 0.22)
                    }
                } else {
                    icon
                }
            }
            .frame(width: 96, height: 96)
            .shadow(color: .black.opacity(0.22), radius: 6, y: 3)

            Text(caption)
                .font(.body.weight(.semibold))
                .multilineTextAlignment(.center)
                .padding(.horizontal, KccSpacing.s3)
                .padding(.vertical, KccSpacing.s1)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: KccRadius.sm))
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(caption)
        .accessibilityAddTraits(.updatesFrequently)
    }

    private var icon: some View {
        Image(systemName: symbol)
            .font(.system(size: 50, weight: .semibold))
            .foregroundStyle(.white)
    }
}

private extension ConvoyReactionKind {
    var labelKey: LocalizedStringKey {
        switch self {
        case .police: "convoyReaction.police.label"
        case .hello: "convoyReaction.hello.label"
        case .followMe: "convoyReaction.followMe.label"
        }
    }

    var caption: String {
        switch self {
        case .police: String(localized: "convoyReaction.police.caption")
        case .hello: String(localized: "convoyReaction.hello.caption")
        case .followMe: String(localized: "convoyReaction.followMe.caption")
        }
    }

    var systemImage: String {
        switch self {
        case .police: "shield.lefthalf.filled"
        case .hello: "hand.wave.fill"
        case .followMe: "location.north.fill"
        }
    }

    var color: Color {
        switch self {
        case .police: KccPalette.errorRed
        case .hello: KccPalette.successGreen
        case .followMe: KccPalette.crownGold
        }
    }
}

private extension ConvoyReactionEvent {
    var caption: String {
        guard let senderName, !senderName.isEmpty else { return kind.caption }
        return "\(senderName): \(kind.caption)"
    }
}
