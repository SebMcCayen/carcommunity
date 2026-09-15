/// Pure vehicle-selection rules for the shell's Start driving sheet.
///
/// The default mirrors Android and the backend's `pickSessionVehicleData`:
/// prefer the main car, otherwise the first car, otherwise no car. A prior
/// selection is retained only while that vehicle remains in the latest
/// garage snapshot, so deleting a car while the sheet is open cannot send a
/// stale id to `live-startSession`.
enum StartDrivingSelection {
    static func effectiveVehicleId(selected: String?, vehicles: [Vehicle]) -> String? {
        if let selected, vehicles.contains(where: { $0.id == selected }) {
            return selected
        }
        return vehicles.first(where: \.isMainCar)?.id ?? vehicles.first?.id
    }

    /// The chooser stays available while Firebase is wired because convoy
    /// creation is independent of the LIVE_LOCATION feature flag. Only the
    /// single-session action and location preflight depend on that flag.
    static func actions(wired: Bool, canShareLive: Bool) -> StartDrivingActions {
        StartDrivingActions(
            showChooser: wired,
            canStartSingleSession: wired && canShareLive,
            convoyRequiresLocation: wired && canShareLive
        )
    }
}

struct StartDrivingActions: Equatable, Sendable {
    let showChooser: Bool
    let canStartSingleSession: Bool
    let convoyRequiresLocation: Bool
}

/// Optimistic command state retained until the RTDB own-session listener
/// confirms the callable's effect. This closes the interval between a
/// successful callable response and the observed session echo.
enum SingleSessionCommand: Equatable, Sendable {
    case starting
    case stopping

    func isReconciled(isSharing: Bool) -> Bool {
        switch self {
        case .starting: isSharing
        case .stopping: !isSharing
        }
    }
}

/// A Create-tab tap that occurred while feature composition was still in
/// flight. Capturing the identity prevents a deferred tap from crossing an
/// account boundary when `.task(id:)` rewires the shell.
struct SingleSessionCreateIntent: Equatable, Sendable {
    let identity: String?

    func belongs(to identity: String?) -> Bool {
        self.identity == identity
    }
}
