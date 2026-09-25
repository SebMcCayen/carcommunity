import SwiftUI

/// Map overlay for the transient reaction controls and incoming reaction pop.
struct ConvoyReactionControls: View {
    @Bindable var coordinator: ConvoyReactionCoordinator

    var body: some View {
        ZStack {
            if let event = coordinator.incomingReaction {
                ConvoyReactionOverlay(event: event)
                    .id(event.id)
                    .transition(.scale(scale: 0.35).combined(with: .opacity))
                    .allowsHitTesting(false)
            }

            VStack {
                Spacer()
                TimelineView(.periodic(from: .now, by: 0.5)) { context in
                    HStack(alignment: .top, spacing: KccSpacing.s5) {
                        ForEach(ConvoyReactionKind.allCases) { kind in
                            reactionButton(kind, now: context.date)
                        }
                    }
                }
                .padding(.bottom, KccSpacing.s12 + KccSpacing.s10)
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
        let ready = remaining == 0 && !coordinator.sendingKinds.contains(kind)
        let seconds = max(Int(ceil(Double(remaining) / 1_000)), 1)

        return VStack(spacing: KccSpacing.s1) {
            Button {
                Task { await coordinator.send(kind) }
            } label: {
                ZStack {
                    Circle()
                        .fill(ready ? kind.color : Color(.secondarySystemFill))
                        .shadow(color: .black.opacity(ready ? 0.22 : 0), radius: 6, y: 3)
                    Image(systemName: kind.systemImage)
                        .font(.system(size: 27, weight: .semibold))
                        .foregroundStyle(ready ? .white : .secondary)
                        .opacity(ready ? 1 : 0.45)
                    if remaining > 0 {
                        Text("\(seconds)")
                            .font(.caption.bold())
                            .foregroundStyle(.primary)
                            .accessibilityHidden(true)
                    } else if coordinator.sendingKinds.contains(kind) {
                        ProgressView()
                            .controlSize(.small)
                            .tint(.primary)
                            .accessibilityHidden(true)
                    }
                }
                .frame(width: 60, height: 60)
            }
            .buttonStyle(.plain)
            .disabled(!ready)
            .accessibilityLabel(Text(kind.labelKey))
            .accessibilityValue(remaining > 0 ? Text("\(seconds)") : Text(""))
            .accessibilityIdentifier("convoy_reaction_\(kind.rawValue)")

            Text(kind.labelKey)
                .font(.caption.weight(.medium))
                .lineLimit(1)
        }
    }
}

private struct ConvoyReactionOverlay: View {
    let event: ConvoyReactionEvent

    var body: some View {
        VStack(spacing: KccSpacing.s2) {
            ZStack {
                Circle().fill(event.kind.color)
                Image(systemName: event.kind.systemImage)
                    .font(.system(size: 50, weight: .semibold))
                    .foregroundStyle(.white)
            }
            .frame(width: 96, height: 96)
            .shadow(color: .black.opacity(0.22), radius: 6, y: 3)

            Text(event.caption)
                .font(.body.weight(.semibold))
                .multilineTextAlignment(.center)
                .padding(.horizontal, KccSpacing.s3)
                .padding(.vertical, KccSpacing.s1)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: KccRadius.sm))
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(event.caption)
        .accessibilityAddTraits(.updatesFrequently)
        .accessibilityIdentifier("convoy_reaction_overlay")
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
