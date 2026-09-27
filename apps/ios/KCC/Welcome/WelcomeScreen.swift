import SwiftUI

struct WelcomeScreen: View {
    let onSeeMembership: () -> Void
    let onCompleteProfile: () -> Void
    let onAddCar: () -> Void
    let onFinish: () -> Void
    @State private var step: WelcomeStep = .welcome

    var body: some View {
        VStack(spacing: KccSpacing.s4) {
            HStack {
                Text(String(
                    format: String(localized: "welcome.progress"),
                    locale: .current,
                    step.position,
                    WelcomeStep.allCases.count
                ))
                .foregroundStyle(.secondary)
                Spacer()
                Button("welcome.skip", action: onFinish)
            }

            ScrollView {
                VStack(spacing: KccSpacing.s4) {
                    Image(systemName: icon)
                        .font(.system(size: 40, weight: .semibold))
                        .frame(width: 72, height: 72)
                        .background(Color.accentColor.opacity(0.14), in: Circle())
                        .accessibilityHidden(true)
                    Text(titleKey)
                        .font(.system(size: KccTypeScale.headingLg, weight: .semibold))
                        .multilineTextAlignment(.center)
                    Text(bodyKey)
                        .font(.system(size: KccTypeScale.bodyMd))
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)

                    if step == .membership {
                        Button("welcome.seeMembership", action: onSeeMembership)
                            .buttonStyle(.bordered)
                    }
                    if step == .profile {
                        Button("welcome.completeProfile", action: onCompleteProfile)
                            .buttonStyle(.bordered)
                        Button("welcome.addCar", action: onAddCar)
                            .buttonStyle(.bordered)
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(KccSpacing.s6)
                .background(.regularMaterial, in: RoundedRectangle(cornerRadius: KccRadius.lg))
            }

            Button {
                if step.isLast { onFinish() } else { step = step.next }
            } label: {
                Text(LocalizedStringKey(step.isLast ? "welcome.getStarted" : "welcome.next"))
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.borderedProminent)
        }
        .padding(KccSpacing.s6)
        .background(.background)
    }

    private var icon: String {
        switch step {
        case .welcome: "party.popper.fill"
        case .map: "map.fill"
        case .membership: "crown.fill"
        case .profile: "car.fill"
        }
    }
    private var titleKey: LocalizedStringKey {
        switch step {
        case .welcome: "welcome.step1Title"
        case .map: "welcome.step2Title"
        case .membership: "welcome.step3Title"
        case .profile: "welcome.step4Title"
        }
    }
    private var bodyKey: LocalizedStringKey {
        switch step {
        case .welcome: "welcome.step1Body"
        case .map: "welcome.step2Body"
        case .membership: "welcome.step3Body"
        case .profile: "welcome.step4Body"
        }
    }
}
