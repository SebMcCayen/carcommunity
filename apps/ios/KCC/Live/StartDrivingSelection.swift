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
}
