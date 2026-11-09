import SwiftUI

struct CrownHuntMapOverlay: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Bindable var coordinator: CrownHuntMapCoordinator
    let projection: any MapProjection
    let mapMode: MapMode

    var body: some View {
        GeometryReader { geometry in
            TimelineView(.periodic(from: .now, by: 1)) { context in
                // Shared crowns stay on the server for other members. Only this
                // reader's already-collected marker is hidden by presentation.
                let markers = coordinator.markers(at: context.date).filter { !$0.collectedByYou }
                let labels = markerLabels()
                ZStack {
                    ForEach(markers, id: \.id) { marker in
                        if let point = projection.screenPositionFor(latitude: marker.latitude, longitude: marker.longitude),
                           point.trustworthy, point.x >= -32, point.y >= -32,
                           point.x <= Double(geometry.size.width + 32), point.y <= Double(geometry.size.height + 32) {
                            Button { coordinator.select(markerId: marker.id) } label: {
                                CrownMarkerTile(rank: marker.visualRank, inRange: marker.isInRange,
                                    mapMode: mapMode, isCuratedPoint: marker.id.hasPrefix("point:"))
                                    .frame(width: 48, height: 48).contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(labels[marker.id] ?? String(localized: "crownHunt.popupTitle"))
                            .position(x: CGFloat(point.x), y: CGFloat(point.y))
                            .transition(.opacity)
                        }
                    }
                }
                .animation(reduceMotion ? nil : .easeOut(duration: 0.32), value: markers.map(\.id))
            }
        }
    }

    private func markerLabels() -> [String: String] {
        var labels: [String: String] = [:]
        for point in coordinator.points {
            labels["point:\(point.id)"] = String(localized: "crownHunt.pointMarkerLabel") + " " + point.title
        }
        for spawn in coordinator.spawns {
            labels["spawn:\(spawn.id)"] = String(localized: "crownHunt.spawnMarkerLabel") + " " + CrownHuntStrings.rarityName(spawn.rarity)
        }
        return labels
    }

}

/// One crisp crown glyph, one outline and 1–4 bars; no idle pulse or blur.
/// Exact points belong to the server-backed detail, not tiny text on the map.
struct CrownMarkerTile: View {
    let rank: Int
    let inRange: Bool
    let mapMode: MapMode
    var isCuratedPoint = false
    private var band: Int { min(max(rank, 0), 3) }
    private var side: CGFloat { CGFloat(26 + 2 * band) }
    private var accent: Color {
        switch band {
        case 0: KccPalette.crownCommon
        case 1: KccPalette.crownUncommon
        case 2: KccPalette.crownRare
        default: KccPalette.crownGold
        }
    }
    private var ink: Color { KccDarkColors.overlayBackground }
    private var dayPair: CrownMapTileLightPalette.Colors? {
        CrownMapTileLightPalette.colors(rank: band, mapMode: mapMode, inRange: inRange)
    }
    private var foreground: Color {
        inRange ? ink : dayPair.map { color($0.foreground) } ?? accent
    }
    private var surface: Color { inRange ? accent : dayPair.map { color($0.surface) } ?? ink }
    private var glyphAsset: String {
        if isCuratedPoint { return "crown-royal" }
        return ["crown-band", "crown-jewelled_band", "crown-five_point", "crown-royal"][band]
    }
    private func color(_ argb: UInt32) -> Color {
        Color(red: Double((argb >> 16) & 255) / 255,
              green: Double((argb >> 8) & 255) / 255, blue: Double(argb & 255) / 255)
    }

    var body: some View {
        ZStack(alignment: .bottom) {
            RoundedRectangle(cornerRadius: KccRadius.sm).fill(surface)
            Image(glyphAsset).renderingMode(.template).resizable().scaledToFit()
                .frame(width: CGFloat(17 + band), height: CGFloat(17 + band))
                .foregroundStyle(foreground)
                .frame(maxHeight: .infinity).offset(y: -2)
            HStack(spacing: 2) {
                ForEach(0...band, id: \.self) { _ in
                    RoundedRectangle(cornerRadius: 1).fill(foreground).frame(width: 3, height: 2)
                }
            }.padding(.bottom, 4)
        }
        .frame(width: side, height: side)
        .overlay { RoundedRectangle(cornerRadius: KccRadius.sm)
            .strokeBorder(inRange ? KccPalette.warmIvory : foreground, lineWidth: inRange ? 1.5 : 1).allowsHitTesting(false) }
        .accessibilityHidden(true)
    }
}

/// Reuses the shell's dock: closing/navigation never depends on collection
/// readiness. Even an exclusive award which removes its target keeps its result.
struct CrownHuntCollectContent: View {
    @Bindable var coordinator: CrownHuntMapCoordinator
    @State private var reportingTarget: CrownMapTarget?

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            let collected = selectedSpawnWasCollected
            let expired = selectedSpawnExpired(at: context.date)
            VStack(alignment: .leading, spacing: KccSpacing.s4) {
                if let target = coordinator.selectedTarget { CrownTargetCard(target: target) }
                Text("crownHunt.safetyStop").font(.subheadline).foregroundStyle(KccTheme.secondary)
                CrownCollectFeedback(state: coordinator.collectState(now: context.date), status: coordinator.claimStatus, collected: collected, expired: expired)
                if let target = coordinator.selectedTarget {
                    Button {
                        Task { await coordinator.collect(now: Date()) }
                    } label: {
                        Text("crownHunt.collectButton").frame(maxWidth: .infinity, minHeight: 48).contentShape(Rectangle())
                    }
                    .kccPrimaryAction()
                    .disabled(expired || !CrownCollectPresentation.canSubmit(coordinator.collectState(now: context.date), status: coordinator.claimStatus, collected: collected))
                    .accessibilityIdentifier("crown.collect")

                    if coordinator.reportedPlacements.contains(target.placementReportKey) {
                        Label("crownHunt.crownPlacementReported", systemImage: "checkmark.circle")
                            .font(.subheadline)
                            .foregroundStyle(KccTheme.secondary)
                            .frame(maxWidth: .infinity, minHeight: 44)
                    } else if !expired {
                        Button {
                            reportingTarget = target
                        } label: {
                            Label("crownHunt.crownPlacementReport", systemImage: "flag")
                                .font(.subheadline)
                                .frame(maxWidth: .infinity, minHeight: 44)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("crown.reportPlacement")
                    }
                }
            }
        }
        .sheet(item: $reportingTarget) { target in
            CrownPlacementReportSheet(coordinator: coordinator, target: target)
        }
    }

    private func selectedSpawnExpired(at now: Date) -> Bool {
        if case .spawn(let spawn) = coordinator.selectedTarget {
            return spawn.expiresAt.map { $0 <= now } ?? false
        }
        return false
    }

    private var selectedSpawnWasCollected: Bool {
        if case .spawn(let spawn) = coordinator.selectedTarget { coordinator.collectedSpawnIds.contains(spawn.id) }
        else { false }
    }
}

private struct CrownPlacementReportSheet: View {
    @Environment(\.dismiss) private var dismiss
    let coordinator: CrownHuntMapCoordinator
    let target: CrownMapTarget
    @State private var reason: CrownPlacementReportReason?
    @State private var comment = ""
    @State private var submitting = false
    @State private var error: LocalizedStringKey?

    var body: some View {
        NavigationStack {
            Form {
                Section("crownHunt.crownPlacementReason") {
                    ForEach(CrownPlacementReportReason.allCases, id: \.self) { option in
                        Button {
                            reason = option
                        } label: {
                            HStack {
                                Text(label(for: option))
                                    .foregroundStyle(KccTheme.text)
                                Spacer()
                                if reason == option {
                                    Image(systemName: "checkmark").accessibilityHidden(true)
                                }
                            }
                            .frame(minHeight: 44)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .accessibilityAddTraits(reason == option ? .isSelected : [])
                    }
                }
                TextField("crownHunt.crownPlacementComment", text: $comment, axis: .vertical)
                    .lineLimit(2...4)
                    .onChange(of: comment) { previous, value in
                        if value.utf16.count > 400 { comment = previous }
                    }
                if let error {
                    Text(error).foregroundStyle(KccPalette.errorRed)
                }
            }
            .disabled(submitting)
            .navigationTitle("crownHunt.crownPlacementReport")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("crownHunt.crownPlacementCancel") { dismiss() }.disabled(submitting)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button {
                        guard let reason else { return }
                        submitting = true
                        error = nil
                        Task {
                            defer { submitting = false }
                            do {
                                try await coordinator.reportPlacement(target: target, reason: reason, comment: comment)
                                dismiss()
                            } catch let failure {
                                switch (failure as? KccFunctionsError)?.code {
                                case .failedPrecondition: error = "crownHunt.crownPlacementUnavailable"
                                case .resourceExhausted: error = "crownHunt.crownPlacementLimit"
                                default: error = "crownHunt.crownPlacementError"
                                }
                            }
                        }
                    } label: {
                        Text(submitting ? "crownHunt.crownPlacementSending" : "crownHunt.crownPlacementSend")
                    }
                    .disabled(submitting || reason == nil || comment.utf16.count > 400)
                }
            }
        }
        .interactiveDismissDisabled(submitting)
    }

    private func label(for reason: CrownPlacementReportReason) -> LocalizedStringKey {
        switch reason {
        case .unsafe: "crownHunt.crownPlacementUnsafe"
        case .hardToReach: "crownHunt.crownPlacementHardToReach"
        case .privateOrClosed: "crownHunt.crownPlacementPrivateOrClosed"
        case .other: "crownHunt.crownPlacementOther"
        }
    }
}

struct CrownTargetCard: View {
    let target: CrownMapTarget
    var body: some View {
        CrownHuntCard {
            switch target {
            case .point(let point):
                Label(point.title, systemImage: "crown.fill").font(.title3.weight(.semibold))
                if let detail = point.detail, !detail.isEmpty { Text(detail).font(.body) }
                Text(CrownHuntStrings.kpValue(point.rewardPoints)).font(.headline)
            case .spawn(let spawn):
                Label(CrownHuntStrings.rarityKey(spawn.rarity), systemImage: "crown.fill").font(.title3.weight(.semibold))
                Text(CrownHuntStrings.kpValue(spawn.rewardPoints)).font(.headline)
            }
        }
    }
}

struct CrownCollectFeedback: View {
    let state: CrownCollectState
    let status: CrownMapClaimStatus
    var collected = false
    var expired = false

    var body: some View {
        VStack(alignment: .leading, spacing: KccSpacing.s3) {
            if expired, !CrownCollectPresentation.isAwarded(status) {
                Label("crownHunt.spawnResultCrownExpired", systemImage: "clock")
            } else if collected, !CrownCollectPresentation.isAwarded(status) {
                Label("crownHunt.spawnResultAlreadyCollected", systemImage: "checkmark.circle")
            } else if showsLocationState { collectState }
            claimResult
        }
        .font(.body).frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityIdentifier("crown.collect.feedback")
    }

    // Once the server has answered, show that decision instead of a local
    // proximity hint which could contradict already-taken/expired/refused.
    private var showsLocationState: Bool {
        switch status {
        case .idle, .collecting, .failed: true
        case .point, .spawn: false
        }
    }

    @ViewBuilder private var collectState: some View {
        switch state {
        case .ready: Label("crownHunt.pointCollectHint", systemImage: "checkmark.circle")
        case .noPosition: Label("crownHunt.spawnNoPositionDetail", systemImage: "location.slash")
        case .tooFar(let distance): Label(format("crownHunt.spawnMoveCloserDetail", Int(distance.rounded())), systemImage: "figure.walk")
        case .waitingForSignal: Label("crownHunt.spawnWaitingForSignal", systemImage: "location.circle")
        case .confirming, .moving: Label("crownHunt.spawnConfirming", systemImage: "hand.raised")
        case .featureOff: Label("crownHunt.spawnResultFeatureDisabled", systemImage: "crown")
        }
    }

    @ViewBuilder private var claimResult: some View {
        switch status {
        case .idle: EmptyView()
        case .collecting: ProgressView("crownHunt.claiming")
        case .failed:
            Label("crownHunt.spawnErrorClaim", systemImage: "exclamationmark.triangle").foregroundStyle(KccPalette.errorRed)
        case .point(let outcome):
            Text(LocalizedStringKey(pointResultKey(outcome.result)))
            if outcome.result == .awarded, let points = outcome.pointsAwarded {
                Text(CrownHuntStrings.kpValue(points)).font(.title2.weight(.bold))
            }
        case .spawn(let outcome):
            if outcome.result == .awarded, let points = outcome.pointsAwarded {
                Text(format("crownHunt.spawnResultAwardedPoints", points)).font(.title2.weight(.bold))
            } else { Text(LocalizedStringKey(spawnResultKey(outcome.result))) }
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
