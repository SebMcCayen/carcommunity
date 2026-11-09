import SwiftUI

struct NearbyWaveButton: View {
    let coordinator: NearbyWaveCoordinator
    let rangeAt: @MainActor (Date) -> NearbyWaveRange?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let range = rangeAt(context.date)
            let visible = range.map { coordinator.canOffer(in: $0) } ?? false
            let enabled = !coordinator.isSending && context.date >= coordinator.cooldownUntil

            ZStack {
                if visible {
                    Button {
                        Task {
                            let now = Date()
                            guard let range = rangeAt(now) else { return }
                            await coordinator.send(in: range, now: now)
                        }
                    } label: {
                        Image(systemName: "hand.wave.fill")
                            .frame(width: 48, height: 48)
                            .kccMapControl(isActive: enabled)
                            .opacity(enabled ? 1 : 0.55)
                    }
                    .buttonStyle(.plain)
                    .disabled(!enabled)
                    .accessibilityLabel(Text("shell.waveButton"))
                    .accessibilityIdentifier("shell.wave")
                    .transition(.scale.combined(with: .opacity))
                }
            }
            .frame(height: visible ? 48 + KccSpacing.s3 : 0, alignment: .top)
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: visible)
            .onChange(of: range, initial: true) { _, range in
                if let range { coordinator.reconcile(range) }
            }
        }
    }
}

struct NearbyWaveOverlay: View {
    let coordinator: NearbyWaveCoordinator
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            if let pop = coordinator.pop {
                MapReactionPop(
                    symbol: "hand.wave.fill",
                    tint: KccPalette.successGreen,
                    caption: caption(for: pop),
                    animateWave: true
                )
                .accessibilityIdentifier("nearby_wave_overlay")
                .id(pop.id)
                .transition(reduceMotion ? .opacity : .scale(scale: 0.35).combined(with: .opacity))
            }
        }
        .allowsHitTesting(false)
        .animation(reduceMotion ? nil : .spring(response: 0.3, dampingFraction: 0.7), value: coordinator.pop?.id)
    }

    private func caption(for pop: NearbyWaveCoordinator.Pop) -> String {
        switch pop {
        case .sent:
            return String(localized: "wave.captionSelf")
        case .received(let event):
            guard let senderName = event.senderName else { return String(localized: "wave.captionAnonymous") }
            return String.localizedStringWithFormat(String(localized: "wave.caption"), senderName)
        }
    }
}
