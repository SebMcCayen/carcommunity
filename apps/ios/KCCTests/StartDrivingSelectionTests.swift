import XCTest

@testable import KCC

final class StartDrivingSelectionTests: XCTestCase {
    func testDefaultsToMainVehicle() {
        XCTAssertEqual(
            StartDrivingSelection.effectiveVehicleId(
                selected: nil,
                vehicles: [vehicle(id: "first"), vehicle(id: "main", isMain: true)]
            ),
            "main"
        )
    }

    func testDefaultsToFirstVehicleWhenNoneIsMain() {
        XCTAssertEqual(
            StartDrivingSelection.effectiveVehicleId(
                selected: nil,
                vehicles: [vehicle(id: "first"), vehicle(id: "second")]
            ),
            "first"
        )
    }

    func testKeepsValidExplicitSelection() {
        XCTAssertEqual(
            StartDrivingSelection.effectiveVehicleId(
                selected: "second",
                vehicles: [vehicle(id: "first", isMain: true), vehicle(id: "second")]
            ),
            "second"
        )
    }

    func testStaleSelectionFallsBackAndEmptyGarageUsesNoVehicle() {
        XCTAssertEqual(
            StartDrivingSelection.effectiveVehicleId(
                selected: "deleted",
                vehicles: [vehicle(id: "main", isMain: true)]
            ),
            "main"
        )
        XCTAssertNil(
            StartDrivingSelection.effectiveVehicleId(selected: "deleted", vehicles: [])
        )
    }

    private func vehicle(id: String, isMain: Bool = false) -> Vehicle {
        Vehicle(
            id: id,
            make: "Volvo",
            model: "240",
            makeId: "volvo",
            modelId: "240",
            modelYear: 1988,
            powertrain: .petrol,
            engineDescription: nil,
            modifications: nil,
            registrationPlate: nil,
            imagePath: nil,
            photoPaths: [],
            isMainCar: isMain
        )
    }
}
