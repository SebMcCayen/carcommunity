import SwiftUI

struct CrownPerkMapControl: View {
    @Bindable var coordinator: CrownPerkMapCoordinator

    var body: some View {
        Button {
            coordinator.openMenu()
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

    let onOpenShop: () -> Void
    @State private var catalog: PerkCatalogSnapshot?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: KccSpacing.s3) {
                    if coordinator.isAvailable {
                        Text("crownHunt.deployIntro")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                        TimelineView(.periodic(from: .now, by: 1)) { context in
                            rows(at: context.date)
                        }
                        statusView
                        Button(action: onOpenShop) {
                            Text("crownHunt.deployOpenShop")
                                .multilineTextAlignment(.center)
                                .frame(maxWidth: .infinity, minHeight: 48)
                                .contentShape(Rectangle())
                        }
                        .tint(KccTheme.driver)
                        .accessibilityIdentifier("crown.perks.openShop")
                        Text("crownHunt.perksGameOnly")
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    } else {
                        Text("crownHunt.resultFeatureDisabled")
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(KccSpacing.s4)
            }
            .kccReadingSurface(pageBackground: true)
            .navigationTitle("crownHunt.deployTitle")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { dismiss() } label: {
                        Image(systemName: "xmark")
                            .frame(width: 48, height: 48)
                            .contentShape(Rectangle())
                    }
                    .accessibilityLabel(Text("shell.panelDismiss"))
                }
            }
        }
        .presentationDetents([.fraction(0.88), .large])
        .kccDismissibleSheet()
        .task {
            guard let snapshots = coordinator.catalog() else { return }
            for await snapshot in snapshots {
                guard !Task.isCancelled else { return }
                catalog = snapshot
            }
        }
    }

    @ViewBuilder
    private func rows(at now: Date) -> some View {
        if coordinator.effectsLoadFailed {
            Text("crownHunt.deployError").foregroundStyle(KccTheme.error)
        } else if case .failed? = catalog {
            Text("crownHunt.deployError").foregroundStyle(KccTheme.error)
        } else if !coordinator.hasLoadedEffects || !coordinator.hasLoadedInventory || catalog == nil {
            Text("crownHunt.deployLoading").foregroundStyle(.secondary)
        } else if case .loaded(let entries) = catalog {
            if entries.allSatisfy({ entry in
                (coordinator.inventory[entry.perkId] ?? 0) <= 0
                    && coordinator.effects.activity(for: entry.kind, at: now) == .inactive
            }) {
                Text("crownHunt.deployEmpty").foregroundStyle(.secondary)
            } else {
                ForEach(entries) { perk in
                    perkRow(perk, at: now)
                }
            }
        }
    }

    private func perkRow(_ perk: PerkCatalogEntry, at now: Date) -> some View {
        CrownHuntCard {
            HStack(spacing: KccSpacing.s3) {
                CrownPerkIcon(kind: perk.kind, size: 56)
                VStack(alignment: .leading, spacing: KccSpacing.s1) {
                    Text(perkName(perk)).font(.headline)
                    Text(verbatim: format("crownHunt.deployOwnedLabel", coordinator.inventory[perk.perkId] ?? 0))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                    activityLabel(coordinator.effects.activity(for: perk.kind, at: now))
                        .font(.subheadline)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            Button {
                Task { await coordinator.deploy(perkId: perk.perkId, kind: perk.kind) }
            } label: {
                HStack(spacing: KccSpacing.s2) {
                    if coordinator.status == .deploying(perk.perkId) {
                        ProgressView()
                        Text("crownHunt.deployActivating")
                    } else {
                        Text("crownHunt.deployActivateButton")
                    }
                }
                .frame(maxWidth: .infinity, minHeight: 48)
                .contentShape(Rectangle())
            }
            .kccPrimaryAction()
            .disabled(!coordinator.canDeploy(perkId: perk.perkId, kind: perk.kind, now: now))
            .accessibilityIdentifier("crown.perks.activate.\(perk.perkId)")
        }
    }

    @ViewBuilder
    private func activityLabel(_ activity: CrownPerkActivity) -> some View {
        switch activity {
        case .inactive:
            Text("crownHunt.perkInactive").foregroundStyle(.secondary)
        case .traps(let count):
            Text(verbatim: format("crownHunt.deployActiveTraps", count))
        case .timed(let totalSeconds):
            let (minutes, seconds) = totalSeconds.quotientAndRemainder(dividingBy: 60)
            let remaining = if minutes == 0 {
                format("crownHunt.deployRemainingS", seconds)
            } else if seconds == 0 {
                format("crownHunt.deployRemainingM", minutes)
            } else {
                format("crownHunt.deployRemainingMs", minutes, seconds)
            }
            Text(verbatim: format("crownHunt.deployActiveFor", remaining))
                .monospacedDigit()
        }
    }

    @ViewBuilder
    private var statusView: some View {
        switch coordinator.status {
        case .idle, .deploying:
            EmptyView()
        case .deployed(let result):
            if result.alreadyDeployed {
                Text("crownHunt.deployAlreadyMessage")
            } else {
                Text(verbatim: format(successKey(result.kind), deployedPerkName(result.perkId)))
            }
        case .failed(_, let failure):
            Text(LocalizedStringKey(errorKey(failure))).foregroundStyle(KccTheme.error)
        }
    }

    private func perkName(_ perk: PerkCatalogEntry) -> String {
        CrownHuntPerkNames.displayName(perkId: perk.perkId, nameSv: perk.name, nameEn: perk.nameEn)
    }

    private func deployedPerkName(_ perkId: String) -> String {
        if case .loaded(let entries) = catalog, let perk = entries.first(where: { $0.perkId == perkId }) {
            return perkName(perk)
        }
        return CrownHuntPerkNames.displayName(perkId: perkId, nameSv: perkId, nameEn: "")
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

    private func format(_ key: String, _ values: CVarArg...) -> String {
        String(format: NSLocalizedString(key, comment: ""), locale: .current, arguments: values)
    }
}

/// Matches the filled crown, shield and bolt used by Android. The adjacent
/// localized perk name is the accessible label.
struct CrownPerkIcon: View {
    let kind: PerkKind
    let size: CGFloat

    var body: some View {
        Image(asset)
            .resizable()
            .scaledToFit()
            .frame(width: size, height: size)
            .foregroundStyle(kind == .trap ? Color(red: 155 / 255, green: 92 / 255, blue: 1) : KccTheme.driver)
            .accessibilityHidden(true)
    }

    private var asset: String {
        switch kind {
        case .trap: "crown-five_point"
        case .shield: "perk-shield"
        case .boost: "perk-boost"
        }
    }
}
