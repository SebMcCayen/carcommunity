import XCTest

@testable import KCC

/// Unit tests for the garage domain: tolerant `vehicles/{id}` decoding
/// (Android's `toVehicle` semantics), the retired-powertrain guarantees,
/// plate normalisation, form validation against the backend bounds, and the
/// list sort. Pure Swift — no Firebase.
final class GarageModelTests: XCTestCase {

    // MARK: - decoding

    private func fullDocument() -> [String: Any] {
        [
            "userId": "uid-1",
            "make": "Volvo",
            "model": "240",
            "makeId": "volvo",
            "modelId": "240",
            "modelYear": 1988,
            "powertrain": "petrol",
            "engineDescription": "B230F",
            "description": "Sänkt, chipp",
            "registrationPlate": "ABC 123",
            "imagePath": "vehicleImages/uid-1/v1/cover.jpg",
            "photoPaths": ["vehicleImages/uid-1/v1/cover.jpg", "vehicleImages/uid-1/v1/two.jpg"],
            "isMainCar": true,
        ]
    }

    func testDecodesAFullDocument() {
        let vehicle = Vehicle.fromMap(id: "v1", map: fullDocument())
        XCTAssertEqual(
            vehicle,
            Vehicle(
                id: "v1",
                make: "Volvo",
                model: "240",
                makeId: "volvo",
                modelId: "240",
                modelYear: 1988,
                powertrain: .petrol,
                engineDescription: "B230F",
                modifications: "Sänkt, chipp",
                registrationPlate: "ABC 123",
                imagePath: "vehicleImages/uid-1/v1/cover.jpg",
                photoPaths: [
                    "vehicleImages/uid-1/v1/cover.jpg", "vehicleImages/uid-1/v1/two.jpg",
                ],
                isMainCar: true
            )
        )
    }

    func testDocumentWithoutRequiredFieldsIsDropped() {
        for missing in ["make", "model", "modelYear", "powertrain"] {
            var map = fullDocument()
            map[missing] = nil
            XCTAssertNil(
                Vehicle.fromMap(id: "v1", map: map),
                "expected a document missing \(missing) to be dropped"
            )
        }
    }

    func testUnknownPowertrainDropsTheDocument() {
        var map = fullDocument()
        map["powertrain"] = "steam"
        XCTAssertNil(Vehicle.fromMap(id: "v1", map: map))
    }

    /// The retired vocabulary must keep parsing — dropping it would make
    /// every pre-existing plug-in-hybrid / other car silently vanish from its
    /// owner's garage (the load-bearing guarantee from Android's
    /// `VehiclePowertrain` KDoc).
    func testRetiredPowertrainsStillParseButAreNotOffered() {
        XCTAssertEqual(VehiclePowertrain.fromWire("plug_in_hybrid"), .plugInHybrid)
        XCTAssertEqual(VehiclePowertrain.fromWire("other"), .other)
        XCTAssertEqual(
            VehiclePowertrain.selectable, [.petrol, .diesel, .hybrid, .electric]
        )
        var map = fullDocument()
        map["powertrain"] = "plug_in_hybrid"
        XCTAssertEqual(Vehicle.fromMap(id: "v1", map: map)?.powertrain, .plugInHybrid)
    }

    /// A pre-catalogue document omits the ids entirely — a supported state,
    /// not a broken document.
    func testLegacyDocumentWithoutCatalogueIdsDecodes() {
        var map = fullDocument()
        map["makeId"] = nil
        map["modelId"] = nil
        let vehicle = Vehicle.fromMap(id: "v1", map: map)
        XCTAssertNil(vehicle?.makeId)
        XCTAssertNil(vehicle?.modelId)
        XCTAssertEqual(vehicle?.make, "Volvo")
    }

    /// A legacy document that predates photoPaths still shows its single
    /// cover — the `[imagePath]` fallback.
    func testMissingPhotoPathsFallBackToTheCover() {
        var map = fullDocument()
        map["photoPaths"] = nil
        XCTAssertEqual(
            Vehicle.fromMap(id: "v1", map: map)?.photoPaths,
            ["vehicleImages/uid-1/v1/cover.jpg"]
        )

        map["imagePath"] = nil
        XCTAssertEqual(Vehicle.fromMap(id: "v1", map: map)?.photoPaths, [])
    }

    func testMalformedPhotoPathEntriesAreFiltered() {
        var map = fullDocument()
        map["photoPaths"] = ["vehicleImages/uid-1/v1/cover.jpg", 7, NSNull()]
        XCTAssertEqual(
            Vehicle.fromMap(id: "v1", map: map)?.photoPaths,
            ["vehicleImages/uid-1/v1/cover.jpg"]
        )
    }

    func testOptionalFieldsDegradeToNilAndFalse() {
        let vehicle = Vehicle.fromMap(
            id: "v1",
            map: ["make": "Saab", "model": "900", "modelYear": 1991, "powertrain": "petrol"]
        )
        XCTAssertNil(vehicle?.engineDescription)
        XCTAssertNil(vehicle?.modifications)
        XCTAssertNil(vehicle?.registrationPlate)
        XCTAssertNil(vehicle?.imagePath)
        XCTAssertEqual(vehicle?.photoPaths, [])
        XCTAssertEqual(vehicle?.isMainCar, false)
    }

    // MARK: - plate normalisation

    func testPlateNormalisationTrimsCollapsesAndUppercases() {
        XCTAssertEqual(
            VehicleValidation.normaliseRegistrationPlate("  abc   123  "), "ABC 123"
        )
        XCTAssertEqual(VehicleValidation.normaliseRegistrationPlate("mln 731"), "MLN 731")
        XCTAssertNil(VehicleValidation.normaliseRegistrationPlate("   "))
        XCTAssertNil(VehicleValidation.normaliseRegistrationPlate(""))
    }

    // MARK: - validation

    private static let currentYear = 2026

    private func validForm() -> VehicleForm {
        VehicleForm(
            makeId: "volvo",
            modelId: "240",
            modelYear: 1988,
            powertrain: .petrol
        )
    }

    func testValidFormPasses() {
        XCTAssertNil(VehicleValidation.validate(validForm(), currentYear: Self.currentYear))
    }

    func testMakeIsRequiredAndMustBeKnownOrOther() {
        var form = validForm()
        form.makeId = nil
        XCTAssertEqual(
            VehicleValidation.validate(form, currentYear: Self.currentYear), .makeRequired
        )
        form.makeId = "not-a-brand"
        XCTAssertEqual(
            VehicleValidation.validate(form, currentYear: Self.currentYear), .makeRequired
        )
    }

    /// A model is only valid under the manufacturer that offers it — a stale
    /// selection left over from switching manufacturer must be caught (model
    /// ids repeat across brands).
    func testStaleModelUnderAnotherMakeIsInvalid() {
        var form = validForm()
        form.makeId = "saab"
        XCTAssertEqual(
            VehicleValidation.validate(form, currentYear: Self.currentYear), .modelRequired
        )
    }

    func testOtherBucketIsAcceptedAtBothLevels() {
        var form = validForm()
        form.makeId = VehicleCatalogue.otherId
        form.modelId = VehicleCatalogue.otherId
        XCTAssertNil(VehicleValidation.validate(form, currentYear: Self.currentYear))
    }

    func testYearMustBeInsideTheOfferedWindow() {
        var form = validForm()
        form.modelYear = nil
        XCTAssertEqual(
            VehicleValidation.validate(form, currentYear: Self.currentYear), .modelYearRequired
        )
        form.modelYear = VehicleCatalogue.minModelYear - 1
        XCTAssertEqual(
            VehicleValidation.validate(form, currentYear: Self.currentYear), .modelYearInvalid
        )
        // The next model year is offered (a 2027 car during 2026)…
        form.modelYear = Self.currentYear + VehicleCatalogue.maxModelYearOffset
        XCTAssertNil(VehicleValidation.validate(form, currentYear: Self.currentYear))
        // …but nothing beyond it.
        form.modelYear = Self.currentYear + VehicleCatalogue.maxModelYearOffset + 1
        XCTAssertEqual(
            VehicleValidation.validate(form, currentYear: Self.currentYear), .modelYearInvalid
        )
    }

    func testPowertrainIsRequired() {
        var form = validForm()
        form.powertrain = nil
        XCTAssertEqual(
            VehicleValidation.validate(form, currentYear: Self.currentYear),
            .powertrainRequired
        )
    }

    func testFieldLengthBounds() {
        var form = validForm()
        form.engineDescription = String(
            repeating: "x", count: VehicleValidation.engineDescriptionMaxLength + 1
        )
        XCTAssertEqual(
            VehicleValidation.validate(form, currentYear: Self.currentYear),
            .engineDescriptionTooLong
        )

        form = validForm()
        form.modifications = String(
            repeating: "x", count: VehicleValidation.modificationsMaxLength + 1
        )
        XCTAssertEqual(
            VehicleValidation.validate(form, currentYear: Self.currentYear),
            .modificationsTooLong
        )

        form = validForm()
        form.registrationPlate = String(
            repeating: "A", count: VehicleValidation.registrationPlateMaxLength + 1
        )
        XCTAssertEqual(
            VehicleValidation.validate(form, currentYear: Self.currentYear),
            .registrationPlateTooLong
        )
        // The cap applies to the NORMALISED plate: padding never pushes a
        // valid plate over it.
        form.registrationPlate =
            "  " + String(repeating: "A", count: VehicleValidation.registrationPlateMaxLength)
        XCTAssertNil(VehicleValidation.validate(form, currentYear: Self.currentYear))
    }

    func testToInputTrimsAndNilsEmptyOptionals() {
        var form = validForm()
        form.engineDescription = "  B230F  "
        form.modifications = "   "
        form.registrationPlate = " abc 123 "
        let input = VehicleValidation.toInput(form, currentYear: Self.currentYear)
        XCTAssertEqual(input?.engineDescription, "B230F")
        XCTAssertNil(input?.modifications)
        XCTAssertEqual(input?.registrationPlate, "ABC 123")
        XCTAssertEqual(input?.makeId, "volvo")
        XCTAssertEqual(input?.modelId, "240")
    }

    func testToInputIsNilForAnInvalidForm() {
        var form = validForm()
        form.modelId = nil
        XCTAssertNil(VehicleValidation.toInput(form, currentYear: Self.currentYear))
    }

    // MARK: - list sort

    func testSortedForListOrdersByMakeThenModelCaseInsensitively() {
        func vehicle(_ id: String, make: String, model: String) -> Vehicle {
            Vehicle(
                id: id,
                make: make,
                model: model,
                makeId: nil,
                modelId: nil,
                modelYear: 2000,
                powertrain: .petrol,
                engineDescription: nil,
                modifications: nil,
                registrationPlate: nil,
                imagePath: nil,
                photoPaths: [],
                isMainCar: false
            )
        }
        let sorted = Garage.sortedForList([
            vehicle("a", make: "volvo", model: "V70"),
            vehicle("b", make: "BMW", model: "M3"),
            vehicle("c", make: "Volvo", model: "240"),
        ])
        XCTAssertEqual(sorted.map(\.id), ["b", "c", "a"])
    }
}
