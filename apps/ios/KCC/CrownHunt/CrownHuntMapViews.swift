import SwiftUI

struct CrownHuntMapOverlay: View {
    @Bindable var coordinator: CrownHuntMapCoordinator
    let projection: any MapProjection

    var body: some View {
        GeometryReader { geometry in
            ForEach(coordinator.markers, id: \.id) { marker in
                if let point = projection.screenPositionFor(
                    latitude: marker.latitude,
                    longitude: marker.longitude
                ), point.trustworthy,
                   point.x >= -32, point.y >= -32,
                   point.x <= Double(geometry.size.width + 32),
                   point.y <= Double(geometry.size.height + 32) {
                    Button {
                        coordinator.select(markerId: marker.id)
                    } label: {
                        crownMarker(marker)
                    }
                    .buttonStyle(.plain)
                    .frame(minWidth: 44, minHeight: 44)
                    .contentShape(Rectangle())
                    .accessibilityLabel(Text(marker.id.hasPrefix("spawn:")
                        ? "crownHunt.spawnMarkerLabel" : "crownHunt.pointMarkerLabel"))
                    .position(x: CGFloat(point.x), y: CGFloat(point.y))
                }
            }
        }
    }

    private func crownMarker(_ marker: MapCrownMarker) -> some View {
        ZStack {
            if let glow = marker.glowColorArgb {
                Circle()
                    .fill(Color(argb: glow))
                    .frame(width: 50, height: 50)
                    .blur(radius: 4)
            }
            Circle()
                .fill(Color(argb: marker.discColorArgb))
                .frame(width: 40, height: 40)
                .overlay(Circle().stroke(.white.opacity(0.9), lineWidth: 2))
                .shadow(radius: 3, y: 1)
            Image(systemName: marker.iconName)
                .font(.system(size: 19, weight: .bold))
                .foregroundStyle(Color(argb: marker.glyphColorArgb))
            if marker.collectedByYou {
                Image(systemName: "checkmark.circle.fill")
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(.white, .green)
                    .offset(x: 15, y: -15)
            }
        }
    }
}

private extension Color {
    init(argb: UInt32) {
        self.init(
            red: Double((argb >> 16) & 0xFF) / 255,
            green: Double((argb >> 8) & 0xFF) / 255,
            blue: Double(argb & 0xFF) / 255,
            opacity: Double((argb >> 24) & 0xFF) / 255
        )
    }
}

struct CrownHuntCollectSheet: View {
    @Environment(\.dismiss) private var dismiss
    @Bindable var coordinator: CrownHuntMapCoordinator
    @State private var now = Date()

    var body: some View {
        NavigationStack {
            List {
                if let target = coordinator.selectedTarget {
                    Section {
                        header(for: target)
                        Text("crownHunt.safetyStop")
                            .foregroundStyle(.secondary)
                    }
                    Section {
                        collectState
                        claimResult
                        Button {
                            Task { await coordinator.collect(now: now) }
                        } label: {
                            if coordinator.claimStatus == .collecting {
                                ProgressView().frame(maxWidth: .infinity)
                            } else {
                                Text("crownHunt.collectButton").frame(maxWidth: .infinity)
                            }
                        }
                        .disabled(coordinator.collectState(now: now) != .ready
                            || coordinator.claimStatus == .collecting)
                        .buttonStyle(.borderedProminent)
                    }
                }
            }
            .navigationTitle("crownHunt.popupTitle")
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("crownHunt.spawnClose") {
                        coordinator.dismissSelection()
                        dismiss()
                    }
                }
            }
        }
        .presentationDetents([.medium, .large])
        .task {
            while !Task.isCancelled {
                now = Date()
                do { try await Task.sleep(for: .seconds(1)) } catch { return }
            }
        }
    }

    @ViewBuilder
    private func header(for target: CrownMapTarget) -> some View {
        switch target {
        case .point(let point):
            Label(point.title, systemImage: "crown.fill").font(.headline)
            if let detail = point.detail, !detail.isEmpty { Text(detail) }
            Text(format("crownHunt.kpValue", point.rewardPoints))
        case .spawn(let spawn):
            Label(LocalizedStringKey(rarityKey(spawn.rarity)), systemImage: "crown.fill")
                .font(.headline)
            Text(format("crownHunt.spawnReward", spawn.rewardPoints))
        }
    }

    @ViewBuilder
    private var collectState: some View {
        switch coordinator.collectState(now: now) {
        case .ready:
            Label("crownHunt.pointCollectHint", systemImage: "checkmark.circle.fill")
                .foregroundStyle(.green)
        case .noPosition:
            Label("crownHunt.spawnNoPositionDetail", systemImage: "location.slash")
        case .tooFar(let distance):
            Label(format("crownHunt.spawnMoveCloserDetail", Int(distance.rounded())), systemImage: "figure.walk")
        case .waitingForSignal:
            Label("crownHunt.spawnWaitingForSignal", systemImage: "location.circle")
        case .confirming, .moving:
            Label("crownHunt.spawnConfirming", systemImage: "hand.raised.fill")
        case .featureOff:
            Label("crownHunt.spawnResultFeatureDisabled", systemImage: "crown")
        }
    }

    @ViewBuilder
    private var claimResult: some View {
        switch coordinator.claimStatus {
        case .idle, .collecting:
            EmptyView()
        case .failed:
            Label("crownHunt.spawnErrorClaim", systemImage: "exclamationmark.triangle.fill")
                .foregroundStyle(.red)
        case .point(let outcome):
            Text(LocalizedStringKey(pointResultKey(outcome.result)))
        case .spawn(let outcome):
            if outcome.result == .awarded, let points = outcome.pointsAwarded {
                Text(format("crownHunt.spawnResultAwardedPoints", points))
                    .foregroundStyle(.green)
            } else {
                Text(LocalizedStringKey(spawnResultKey(outcome.result)))
            }
        }
    }

    private func rarityKey(_ rarity: CrownRarity) -> String {
        switch rarity {
        case .common: "crownHunt.rarityCommon"
        case .uncommon: "crownHunt.rarityUncommon"
        case .rare: "crownHunt.rarityRare"
        case .legendary: "crownHunt.rarityLegendary"
        }
    }

    private func pointResultKey(_ result: CrownHuntClaimResult) -> String {
        switch result {
        case .awarded: "crownHunt.resultAwarded"
        case .alreadyClaimed: "crownHunt.resultAlreadyClaimed"
        case .outsideGeofence: "crownHunt.resultOutsideGeofence"
        case .movingTooFast: "crownHunt.resultMovingTooFast"
        case .positionTooOld: "crownHunt.resultPositionTooOld"
        case .pointInactive: "crownHunt.resultPointInactive"
        case .cooldownActive: "crownHunt.resultCooldownActive"
        case .dailyLimitReached: "crownHunt.resultDailyLimit"
        case .riskReview: "crownHunt.resultRiskReview"
        case .featureDisabled: "crownHunt.resultFeatureDisabled"
        case .notEligible: "crownHunt.resultNotEligible"
        }
    }

    private func spawnResultKey(_ result: CrownSpawnClaimResult) -> String {
        switch result {
        case .awarded: "crownHunt.spawnResultAwarded"
        case .alreadyTaken: "crownHunt.spawnResultAlreadyTaken"
        case .alreadyCollected: "crownHunt.spawnResultAlreadyCollected"
        case .outsideRadius: "crownHunt.spawnResultOutsideRadius"
        case .mustBeStationary: "crownHunt.spawnResultMustBeStationary"
        case .positionTooOld: "crownHunt.spawnResultPositionTooOld"
        case .crownExpired: "crownHunt.spawnResultCrownExpired"
        case .dailyLimitReached: "crownHunt.spawnResultDailyLimit"
        case .riskReview: "crownHunt.spawnResultRiskReview"
        case .featureDisabled: "crownHunt.spawnResultFeatureDisabled"
        case .notEligible: "crownHunt.spawnResultNotEligible"
        }
    }

    private func format(_ key: String, _ value: CVarArg) -> String {
        String.localizedStringWithFormat(NSLocalizedString(key, comment: ""), value)
    }
}
