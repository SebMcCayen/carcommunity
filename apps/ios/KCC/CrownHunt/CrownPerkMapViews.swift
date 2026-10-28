import SwiftUI

struct CrownPerkMapControl: View {
    @Bindable var coordinator: CrownPerkMapCoordinator

    var body: some View {
        Button {
            coordinator.menuPresented = true
            Task { await coordinator.refreshEffects() }
        } label: {
            Image(systemName: active ? "bolt.shield.fill" : "bolt.shield")
                .font(.title3.weight(.semibold))
                .frame(width: 48, height: 48)
                .kccMapControl()
                .foregroundStyle(active ? KccTheme.driver : KccTheme.text)
        }
        .accessibilityLabel(Text("crownHunt.deployTitle"))
    }

    private var active: Bool {
        let now = Date()
        return coordinator.effects.shieldUntil.map { $0 > now } == true
            || coordinator.effects.boostUntil.map { $0 > now } == true
            || coordinator.effects.traps.contains { $0.expiresAt > now }
    }
}

struct CrownPerkMapOverlay: View {
    @Bindable var coordinator: CrownPerkMapCoordinator
    let projection: any MapProjection
    let ownFix: LocationFix?

    var body: some View {
        GeometryReader { geometry in
            let now = Date()
            ZStack {
                if let fix = ownFix,
                   coordinator.effects.shieldUntil.map({ $0 > now }) == true,
                   let point = projection.screenPositionFor(
                    latitude: fix.latitude, longitude: fix.longitude
                   ), point.trustworthy {
                    Circle()
                        .stroke(Color.cyan.opacity(0.75), lineWidth: 4)
                        .frame(width: 58, height: 58)
                        .position(x: point.x, y: point.y)
                        .allowsHitTesting(false)
                }
                if let fix = ownFix,
                   coordinator.effects.boostUntil.map({ $0 > now }) == true,
                   let point = projection.screenPositionFor(
                    latitude: fix.latitude, longitude: fix.longitude
                   ), point.trustworthy {
                    Circle()
                        .stroke(Color.orange.opacity(0.75), style: StrokeStyle(lineWidth: 3, dash: [5, 4]))
                        .frame(width: 68, height: 68)
                        .position(x: point.x, y: point.y)
                        .allowsHitTesting(false)
                }
                ForEach(coordinator.effects.traps.filter { $0.expiresAt > now }) { trap in
                    if let point = projection.screenPositionFor(
                        latitude: trap.latitude, longitude: trap.longitude
                    ), point.trustworthy,
                       point.x >= -30, point.y >= -30,
                       point.x <= Double(geometry.size.width + 30),
                       point.y <= Double(geometry.size.height + 30) {
                        Image(systemName: "exclamationmark.triangle.fill")
                            .font(.system(size: 18, weight: .bold))
                            .foregroundStyle(.white)
                            .frame(width: 38, height: 38)
                            .background(Color.purple, in: Circle())
                            .overlay(Circle().stroke(.white.opacity(0.9), lineWidth: 2))
                            .shadow(radius: 2, y: 1)
                            .accessibilityLabel(Text("crownHunt.perkTrapMapTapLabel"))
                            .position(x: point.x, y: point.y)
                    }
                }
            }
        }
        .allowsHitTesting(false)
    }
}

struct CrownPerkDeploySheet: View {
    @Environment(\.dismiss) private var dismiss
    @Bindable var coordinator: CrownPerkMapCoordinator

    private let rows: [(String, PerkKind, String, String)] = [
        ("spike_strip", .trap, "crownHunt.perkNameSpikeStrip", "exclamationmark.triangle.fill"),
        ("shield", .shield, "crownHunt.perkNameShield", "shield.fill"),
        ("boost", .boost, "crownHunt.perkNameBoost", "bolt.fill")
    ]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: KccSpacing.s4) {
                    if coordinator.isAvailable {
                        ForEach(rows, id: \.0) { row in
                            perkRow(perkId: row.0, kind: row.1, title: row.2, symbol: row.3)
                        }
                        statusView
                    } else {
                        CrownHuntMessageState(title: "crownHunt.deployTitle", message: "crownHunt.resultFeatureDisabled")
                    }
                }.padding(KccSpacing.s4)
            }
            .kccReadingSurface(pageBackground: true)
            .navigationTitle("crownHunt.deployTitle")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("crownHunt.spawnClose") {
                        coordinator.menuPresented = false
                        dismiss()
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
    }

    private func perkRow(perkId: String, kind: PerkKind, title: String, symbol: String) -> some View {
        CrownHuntCard {
            Label(LocalizedStringKey(title), systemImage: symbol).font(.headline)
            Text(format("crownHunt.deployOwnedLabel", coordinator.inventory[perkId] ?? 0))
                .font(.subheadline).foregroundStyle(KccTheme.secondary)
            if isActive(kind) {
                Label("crownHunt.deployActiveFor", systemImage: "clock.fill").font(.subheadline)
            }
            Button {
                Task { await coordinator.deploy(perkId: perkId, kind: kind) }
            } label: {
                Text("crownHunt.deployActivateButton").frame(maxWidth: .infinity, minHeight: 48).contentShape(Rectangle())
            }
            .kccPrimaryAction()
            .disabled((coordinator.inventory[perkId] ?? 0) <= 0 || isDeploying)
        }
    }

    @ViewBuilder
    private var statusView: some View {
        switch coordinator.status {
        case .idle:
            EmptyView()
        case .deploying:
            ProgressView("crownHunt.deployLoading")
        case .deployed(let result):
            Text(LocalizedStringKey(result.alreadyDeployed
                ? "crownHunt.deployAlreadyMessage" : successKey(result.kind)))
                .foregroundStyle(.green)
        case .failed(_, let failure):
            Text(LocalizedStringKey(errorKey(failure))).foregroundStyle(.red)
        }
    }

    private var isDeploying: Bool {
        if case .deploying = coordinator.status { return true }
        return false
    }

    private func isActive(_ kind: PerkKind) -> Bool {
        let now = Date()
        switch kind {
        case .trap: return coordinator.effects.traps.contains { $0.expiresAt > now }
        case .shield: return coordinator.effects.shieldUntil.map { $0 > now } == true
        case .boost: return coordinator.effects.boostUntil.map { $0 > now } == true
        }
    }

    private func successKey(_ kind: PerkKind) -> String {
        switch kind {
        case .trap: "crownHunt.deploySuccessTrap"
        case .shield: "crownHunt.deploySuccessShield"
        case .boost: "crownHunt.deploySuccessBoost"
        }
    }

    private func errorKey(_ failure: CrownPerkDeployFailure) -> String {
        switch failure {
        case .noLocation: "crownHunt.deployErrorNoLocation"
        case .activationLimit: "crownHunt.deployErrorActivationLimit"
        case .eventTooClose: "crownHunt.deployErrorEventTooClose"
        case .unavailable: "crownHunt.deployErrorUnavailable"
        case .unknown: "crownHunt.deployErrorUnknown"
        }
    }

    private func format(_ key: String, _ value: CVarArg) -> String {
        String.localizedStringWithFormat(NSLocalizedString(key, comment: ""), value)
    }
}
