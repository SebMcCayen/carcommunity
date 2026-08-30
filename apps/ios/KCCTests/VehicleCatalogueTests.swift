import XCTest

@testable import KCC

/// Sanity tests for the generated catalogue mirror and its parser: the packed
/// data parses, known makes/models are present, the "Other / not listed"
/// escape hatch is pinned last and unfilterable, the selectors cascade, and
/// the display resolution keeps legacy vehicles rendering verbatim — the same
/// guarantees Android pins over the identical encoding.
final class VehicleCatalogueTests: XCTestCase {

    // MARK: - parsing sanity

    func testCatalogueIsNonEmptyAndParses() {
        XCTAssertFalse(VehicleCatalogue.makes.isEmpty)
        // Every manufacturer has at least one model and non-blank labels.
        for make in VehicleCatalogue.makes {
            XCTAssertFalse(make.id.isEmpty)
            XCTAssertFalse(make.name.isEmpty)
            XCTAssertFalse(make.models.isEmpty, "make \(make.id) has no models")
        }
    }

    func testAKnownMakeAndModelArePresent() {
        XCTAssertTrue(VehicleCatalogue.isKnownMake("volvo"))
        XCTAssertEqual(VehicleCatalogue.makeName("volvo"), "Volvo")
        XCTAssertEqual(VehicleCatalogue.modelName(makeId: "volvo", modelId: "240"), "240")
        XCTAssertTrue(VehicleCatalogue.make("volvo")?.common == true)
    }

    func testUnknownAndOtherIdsAreNotKnown() {
        XCTAssertFalse(VehicleCatalogue.isKnownMake(nil))
        XCTAssertFalse(VehicleCatalogue.isKnownMake(VehicleCatalogue.otherId))
        XCTAssertFalse(VehicleCatalogue.isKnownMake("not-a-brand"))
        // Model ids are meaningful only under their own manufacturer.
        XCTAssertFalse(VehicleCatalogue.isKnownModel(makeId: "saab", modelId: "240"))
    }

    // MARK: - the Other escape hatch

    func testOtherRowIsAlwaysLastAtBothLevels() {
        let makeRows = VehicleCatalogue.makeOptions()
        XCTAssertEqual(makeRows.last?.id, VehicleCatalogue.otherId)
        XCTAssertEqual(makeRows.last?.isOther, true)
        XCTAssertEqual(makeRows.count, VehicleCatalogue.makes.count + 1)

        let modelRows = VehicleCatalogue.modelOptions(makeId: "volvo")
        XCTAssertEqual(modelRows.last?.id, VehicleCatalogue.otherId)
        XCTAssertEqual(
            modelRows.count, (VehicleCatalogue.make("volvo")?.models.count ?? 0) + 1
        )
    }

    /// No manufacturer chosen — or the Other manufacturer — has no model
    /// list, so the only honest model is "other" too (the cascade rule).
    func testModelOptionsCascadeFromTheManufacturer() {
        XCTAssertEqual(
            VehicleCatalogue.modelOptions(makeId: nil).map(\.id), [VehicleCatalogue.otherId]
        )
        XCTAssertEqual(
            VehicleCatalogue.modelOptions(makeId: VehicleCatalogue.otherId).map(\.id),
            [VehicleCatalogue.otherId]
        )
    }

    func testStaleModelSelectionIsNotSelectableUnderANewMake() {
        XCTAssertTrue(VehicleCatalogue.isSelectableModel(makeId: "volvo", modelId: "240"))
        XCTAssertFalse(VehicleCatalogue.isSelectableModel(makeId: "saab", modelId: "240"))
        XCTAssertTrue(
            VehicleCatalogue.isSelectableModel(
                makeId: "saab", modelId: VehicleCatalogue.otherId
            )
        )
        XCTAssertFalse(VehicleCatalogue.isSelectableModel(makeId: "volvo", modelId: nil))
    }

    // MARK: - years

    func testModelYearsRunNewestFirstAcrossTheOfferedWindow() {
        let years = VehicleCatalogue.modelYears(currentYear: 2026)
        XCTAssertEqual(years.first, 2026 + VehicleCatalogue.maxModelYearOffset)
        XCTAssertEqual(years.last, VehicleCatalogue.minModelYear)
        XCTAssertTrue(VehicleCatalogue.isOfferedYear(2027, currentYear: 2026))
        XCTAssertFalse(VehicleCatalogue.isOfferedYear(2028, currentYear: 2026))
        XCTAssertFalse(
            VehicleCatalogue.isOfferedYear(
                VehicleCatalogue.minModelYear - 1, currentYear: 2026
            )
        )
    }

    // MARK: - search

    func testFilterIsDiacriticAndCaseInsensitive() {
        // "citroen" must find Citroën without the ë…
        let makes = VehicleCatalogue.filter(VehicleCatalogue.makeOptions(), query: "citroen")
        XCTAssertTrue(makes.contains { $0.id == "citroen" })
        // …and "megane" must find Mégane.
        let models = VehicleCatalogue.filter(
            VehicleCatalogue.modelOptions(makeId: "renault"), query: "MEGANE"
        )
        XCTAssertTrue(models.contains { $0.id == "megane" })
    }

    /// The escape hatch can never be filtered away — the one case where a
    /// member most needs it is when nothing matches.
    func testFilterKeepsTheOtherRowForAnyQuery() {
        let filtered = VehicleCatalogue.filter(
            VehicleCatalogue.makeOptions(), query: "zzzz-no-such-brand"
        )
        XCTAssertEqual(filtered.map(\.id), [VehicleCatalogue.otherId])
    }

    func testEmptyQueryReturnsEverything() {
        let options = VehicleCatalogue.makeOptions()
        XCTAssertEqual(VehicleCatalogue.filter(options, query: "   "), options)
    }

    // MARK: - display resolution

    private func vehicle(
        make: String, model: String, makeId: String?, modelId: String?
    ) -> Vehicle {
        Vehicle(
            id: "v1",
            make: make,
            model: model,
            makeId: makeId,
            modelId: modelId,
            modelYear: 1988,
            powertrain: .petrol,
            engineDescription: nil,
            modifications: nil,
            registrationPlate: nil,
            imagePath: nil,
            photoPaths: [],
            isMainCar: false
        )
    }

    func testHeadlineUsesCatalogueNamesForKnownIds() {
        let headline = VehicleDisplay.headline(
            vehicle(make: "Volvo", model: "240", makeId: "volvo", modelId: "240"),
            otherLabel: "Övrig"
        )
        XCTAssertEqual(headline, "Volvo 240 (1988)")
    }

    /// A pre-catalogue vehicle renders its stored free text verbatim — the
    /// branch that keeps legacy cars rendering exactly as they always did.
    func testHeadlineKeepsLegacyFreeTextVerbatim() {
        let headline = VehicleDisplay.headline(
            vehicle(make: "Wolwo", model: "245", makeId: nil, modelId: nil),
            otherLabel: "Övrig"
        )
        XCTAssertEqual(headline, "Wolwo 245 (1988)")
    }

    /// Other at BOTH levels shows the label once — never "Övrig Övrig".
    func testHeadlineCollapsesTheDoubleOtherBucket() {
        let headline = VehicleDisplay.headline(
            vehicle(
                make: "x",
                model: "x",
                makeId: VehicleCatalogue.otherId,
                modelId: VehicleCatalogue.otherId
            ),
            otherLabel: "Övrig"
        )
        XCTAssertEqual(headline, "Övrig (1988)")
    }

    func testModelLabelUsesOtherLabelForTheOtherBucketOnly() {
        XCTAssertEqual(
            VehicleDisplay.modelLabel(
                makeId: "volvo",
                modelId: VehicleCatalogue.otherId,
                storedModel: "whatever",
                otherLabel: "Övrig"
            ),
            "Övrig"
        )
        // An id this build does not know (written by a NEWER client) falls
        // back to the stored text rather than rendering nothing.
        XCTAssertEqual(
            VehicleDisplay.modelLabel(
                makeId: "volvo",
                modelId: "future-model",
                storedModel: "Framtida",
                otherLabel: "Övrig"
            ),
            "Framtida"
        )
    }
}
