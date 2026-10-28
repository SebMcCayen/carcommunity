import SwiftUI

struct WelcomeScreen: View {
    @Environment(\.locale) private var locale
    let onSeeMembership: () -> Void
    let onCompleteProfile: () -> Void
    let onAddCar: () -> Void
    let onFinish: () -> Void
    @State private var step: WelcomeStep

    init(initialStep: WelcomeStep = .welcome, onSeeMembership: @escaping () -> Void,
        onCompleteProfile: @escaping () -> Void, onAddCar: @escaping () -> Void, onFinish: @escaping () -> Void) {
        _step = State(initialValue: initialStep)
        self.onSeeMembership = onSeeMembership
        self.onCompleteProfile = onCompleteProfile
        self.onAddCar = onAddCar
        self.onFinish = onFinish
    }

    var body: some View {
        VStack(spacing: KccSpacing.s4) {
            SocialFlow {
                Text(String(
                    format: String(localized: "welcome.progress", locale: locale),
                    locale: locale,
                    step.position,
                    WelcomeStep.allCases.count
                ))
                .foregroundStyle(KccTheme.secondary)
                SocialFlowSpacer()
                Button(action: onFinish) { Text("welcome.skip").frame(minHeight: 48) }
            }

            ScrollView {
                VStack(spacing: KccSpacing.s4) {
                    Image(systemName: icon)
                        .font(.system(size: 40, weight: .semibold))
                        .frame(width: 72, height: 72)
                        .background(KccTheme.subtle, in: Circle())
                        .accessibilityHidden(true)
                    Text(titleKey)
                        .font(.title2.bold())
                        .multilineTextAlignment(.center)
                    Text(bodyKey)
                        .font(.body)
                        .foregroundStyle(KccTheme.secondary)
                        .multilineTextAlignment(.center)

                    if step == .membership {
                        Button(action: onSeeMembership) { Text("welcome.seeMembership").frame(maxWidth: .infinity, minHeight: 48) }
                            .buttonStyle(.bordered)
                    }
                    if step == .profile {
                        Button(action: onCompleteProfile) { Text("welcome.completeProfile").frame(maxWidth: .infinity, minHeight: 48) }
                            .buttonStyle(.bordered)
                        Button(action: onAddCar) { Text("welcome.addCar").frame(maxWidth: .infinity, minHeight: 48) }
                            .buttonStyle(.bordered)
                    }
                }
                .frame(maxWidth: .infinity)
                .kccContentCard()
            }

            Button {
                if step.isLast { onFinish() } else { step = step.next }
            } label: {
                Text(LocalizedStringKey(step.isLast ? "welcome.getStarted" : "welcome.next"))
                    .frame(maxWidth: .infinity, minHeight: 48)
            }
            .kccPrimaryAction()
        }
        .padding(KccSpacing.s4)
        .kccReadingSurface(pageBackground: true).tint(KccTheme.driver)
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
