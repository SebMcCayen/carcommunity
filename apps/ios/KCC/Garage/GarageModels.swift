import Foundation

/// Garage / vehicles domain + validation — the iOS port of Android's
/// `garage/Vehicle.kt`, restricted to the list + add slice.
///
/// Mirrors the backend garage-core contract
/// (contracts/schemas/garage.schema.json): the powertrain vocabulary, the
/// field bounds, and the plate normalisation. `registrationPlate` is a
/// DELIBERATELY PUBLIC, user-entered field (Seb product decision) shown on
/// the car profile; VIN and other private data are still never represented.
/// Pure Swift — unit-testable, no Firebase.
///
/// Make, model and year are SELECTED from ``VehicleCatalogue``, never typed,
/// so the community can count cars per manufacturer. The form therefore
/// carries catalogue ids (``VehicleForm/makeId`` / ``VehicleForm/modelId``)
/// and sends only those; the backend derives the display text. Vehicles
/// created before the catalogue hold free text and no ids — the read path
/// keeps them rendering verbatim (``VehicleDisplay``).

/// Vehicle powertrain (vehicles/{id}.powertrain).
///
/// Mirrors garage-core.ts. The vocabulary is deliberately WIDER than what the
/// form offers: ``selectable`` is the product-facing set (Petrol / Diesel /
/// Hybrid / Electric, in that order), while `plugInHybrid` and `other` are
/// RETIRED — no longer offered, but still parsed, stored and rendered.
///
/// Keeping the retired cases is load-bearing, not tidiness: the Firestore
/// read path drops a whole vehicle when ``fromWire(_:)`` returns nil
/// (`FirebaseVehiclesRepository`), so deleting them would make every
/// pre-existing plug-in-hybrid / other car silently VANISH from its owner's
/// garage. Do not remove them while any vehicle still holds the value.
enum VehiclePowertrain: String, Equatable, Sendable, CaseIterable {
    case petrol
    case diesel
    case hybrid
    case electric

    /// Retired (see the type doc): still parsed/rendered, never offered.
    case plugInHybrid = "plug_in_hybrid"

    /// Retired (see the type doc): still parsed/rendered, never offered.
    case other

    /// The Firestore wire value (kept as an explicit accessor so call sites
    /// read like Android's `powertrain.wire`).
    var wire: String { rawValue }

    var isSelectable: Bool {
        switch self {
        case .petrol, .diesel, .hybrid, .electric: true
        case .plugInHybrid, .other: false
        }
    }

    static func fromWire(_ value: String?) -> VehiclePowertrain? {
        guard let value else { return nil }
        return VehiclePowertrain(rawValue: value)
    }

    /// The powertrains the add form offers, in render order: exactly Petrol,
    /// Diesel, Hybrid, Electric. Retired values are excluded, so a NEW
    /// vehicle can only ever be created with one of these four.
    static var selectable: [VehiclePowertrain] {
        allCases.filter(\.isSelectable)
    }

    /// Localization key for the powertrain label — the generated
    /// `garage.powertrain_*` keys (contracts/localization).
    var localizationKey: String { "garage.powertrain_\(wire)" }
}

/// A garage vehicle (vehicles/{id}) — the fields this slice renders.
struct Vehicle: Equatable, Sendable, Identifiable {
    let id: String
    /// Human-readable make. For a catalogue vehicle the backend DERIVED this
    /// from `makeId`; for a pre-catalogue vehicle it is the owner's original
    /// free text, untouched. Render through ``VehicleDisplay`` so the
    /// "Other / not listed" bucket shows a localized label.
    let make: String
    let model: String
    /// Catalogue manufacturer id (`volvo`, or ``VehicleCatalogue/otherId``),
    /// or nil for a vehicle written on the legacy free-text path.
    let makeId: String?
    /// Catalogue model id within `makeId` (or ``VehicleCatalogue/otherId``);
    /// nil on the legacy path.
    let modelId: String?
    let modelYear: Int
    let powertrain: VehiclePowertrain
    let engineDescription: String?
    /// Free-text "modifications" note, backed by the vehicles/{id}.description
    /// field (garage-core) — the same reuse as Android.
    let modifications: String?
    /// Registration plate — a DELIBERATELY PUBLIC, user-entered field (Seb
    /// product decision), normalised by the backend; nil when the owner left
    /// it blank. Readable by ANY signed-in user (the `vehicles` read rule is
    /// `isAuthenticated()`), not just members.
    let registrationPlate: String?
    /// Cloud Storage path of the COVER photo
    /// (vehicleImages/{uid}/{vehicleId}/{imageId}), or nil when unset. The
    /// path is stored; a URL is resolved lazily for rendering.
    let imagePath: String?
    /// Ordered photo gallery paths, cover first. Empty for a vehicle with no
    /// photos and for legacy documents that predate the field — the read path
    /// falls back to `[imagePath]` for those, like Android.
    let photoPaths: [String]
    /// True for the user's single "main car" (at most one per user, enforced
    /// by the garage-setMainVehicle callable).
    let isMainCar: Bool

    /// Tolerant decoding of a `vehicles/{id}` document map — the iOS port of
    /// Android's `DocumentSnapshot.toVehicle()`: a document without the
    /// required make/model/year/powertrain is dropped (nil), everything
    /// optional degrades to nil/empty/false. Catalogue ids are NEVER required:
    /// pre-catalogue documents omit them, and that is a supported state, not
    /// a broken document.
    static func fromMap(id: String, map: [String: Any]) -> Vehicle? {
        guard let make = map["make"] as? String,
            let model = map["model"] as? String,
            let powertrain = VehiclePowertrain.fromWire(map["powertrain"] as? String),
            let modelYear = (map["modelYear"] as? NSNumber)?.intValue
        else { return nil }
        return Vehicle(
            id: id,
            make: make,
            model: model,
            makeId: map["makeId"] as? String,
            modelId: map["modelId"] as? String,
            modelYear: modelYear,
            powertrain: powertrain,
            engineDescription: map["engineDescription"] as? String,
            modifications: map["description"] as? String,
            registrationPlate: map["registrationPlate"] as? String,
            imagePath: map["imagePath"] as? String,
            // Ordered gallery; keep only string entries. Empty for legacy
            // docs that predate the field — fall back to the single cover so
            // those still show their photo (Android's read-path fallback).
            photoPaths: (map["photoPaths"] as? [Any])?.compactMap { $0 as? String }
                ?? (map["imagePath"] as? String).map { [$0] } ?? [],
            isMainCar: map["isMainCar"] as? Bool ?? false
        )
    }
}

/// Editable add-form state. Make/model/year are SELECTIONS: `makeId`,
/// `modelId` and `modelYear` hold catalogue ids / a picked year, and there is
/// deliberately no free-text field for any of them.
struct VehicleForm: Equatable, Sendable {
    var makeId: String?
    var modelId: String?
    var modelYear: Int?
    var powertrain: VehiclePowertrain?
    var engineDescription: String = ""
    var modifications: String = ""
    var registrationPlate: String = ""
}

/// The validated add payload. Carries the catalogue IDS only — the display
/// strings are derived server-side from the same catalogue, so a client can
/// never store a `volvo` id labelled "Ferrari".
struct VehicleInput: Equatable, Sendable {
    let makeId: String
    let modelId: String
    let modelYear: Int
    let powertrain: VehiclePowertrain
    let engineDescription: String?
    let modifications: String?
    /// Normalised plate (trim/collapse/uppercase), or nil when blank.
    let registrationPlate: String?
}

/// First validation problem, or nil when valid.
enum VehicleFieldError: Equatable, Sendable {
    case makeRequired
    case modelRequired
    case modelYearRequired
    case modelYearInvalid
    case powertrainRequired
    case engineDescriptionTooLong
    case modificationsTooLong
    case registrationPlateTooLong

    /// Localization key of the field's validation message — the generated
    /// `garage.validation*` keys (contracts/localization).
    var localizationKey: String {
        switch self {
        case .makeRequired: "garage.validationMakeRequired"
        case .modelRequired: "garage.validationModelRequired"
        case .modelYearRequired: "garage.validationModelYearRequired"
        case .modelYearInvalid: "garage.validationModelYearInvalid"
        case .powertrainRequired: "garage.validationPowertrainRequired"
        case .engineDescriptionTooLong: "garage.validationEngineDescriptionTooLong"
        case .modificationsTooLong: "garage.validationModificationsTooLong"
        case .registrationPlateTooLong: "garage.validationRegistrationPlateTooLong"
        }
    }
}

enum VehicleValidation {
    /// First model year the selector OFFERS (catalogue `minModelYear`).
    /// Deliberately later than the backend's absolute floor of 1886, which
    /// the backend honours only on its legacy free-text path.
    static let minModelYear = VehicleCatalogue.minModelYear

    /// Backend cap (garage-core MAX_VEHICLES_PER_USER), mirrored so the list
    /// can hide the add button at the cap; the backend enforces it
    /// transactionally either way.
    static let maxVehiclesPerUser = 10

    /// Backend bound (garage-core): engineDescription ≤120.
    static let engineDescriptionMaxLength = 120

    /// Backend bound (garage-core VEHICLE_DESCRIPTION_MAX_LENGTH) for
    /// modifications.
    static let modificationsMaxLength = 500

    /// Backend bound (garage-core REGISTRATION_PLATE_MAX_LENGTH), checked
    /// against the NORMALISED plate.
    static let registrationPlateMaxLength = 12

    /// Last offered year: the next model year (catalogue `maxModelYearOffset`).
    static func maxModelYear(currentYear: Int) -> Int {
        VehicleCatalogue.maxModelYear(currentYear: currentYear)
    }

    /// Normalises a plate the same way the backend does (garage-core
    /// `normaliseRegistrationPlate`): trim, collapse internal whitespace to a
    /// single space, uppercase. Blank → nil. Format-agnostic — no country
    /// regex, imports/personalised plates pass through.
    ///
    /// Uppercasing pins the POSIX locale so the canonical plate is stable
    /// across device locales (e.g. Turkish 'i' → 'I', never 'İ'), matching
    /// the backend's locale-independent JS `String.toUpperCase()`.
    static func normaliseRegistrationPlate(_ raw: String) -> String? {
        let collapsed = raw
            .split(whereSeparator: \.isWhitespace)
            .joined(separator: " ")
            .uppercased(with: Locale(identifier: "en_US_POSIX"))
        return collapsed.isEmpty ? nil : collapsed
    }

    /// Returns the first validation error, or nil when the form is valid.
    ///
    /// Make and model are checked as SELECTIONS against ``VehicleCatalogue``:
    /// the "Other / not listed" bucket is accepted at both levels, and a
    /// model is only valid under the manufacturer that offers it (model ids
    /// repeat across brands, so a stale selection left over from switching
    /// manufacturer must be caught here rather than sent).
    static func validate(_ form: VehicleForm, currentYear: Int) -> VehicleFieldError? {
        guard let makeId = form.makeId else { return .makeRequired }
        if makeId != VehicleCatalogue.otherId, !VehicleCatalogue.isKnownMake(makeId) {
            return .makeRequired
        }
        guard VehicleCatalogue.isSelectableModel(makeId: makeId, modelId: form.modelId) else {
            return .modelRequired
        }
        guard let year = form.modelYear else { return .modelYearRequired }
        guard VehicleCatalogue.isOfferedYear(year, currentYear: currentYear) else {
            return .modelYearInvalid
        }
        guard form.powertrain != nil else { return .powertrainRequired }
        if trimmed(form.engineDescription).count > engineDescriptionMaxLength {
            return .engineDescriptionTooLong
        }
        if trimmed(form.modifications).count > modificationsMaxLength {
            return .modificationsTooLong
        }
        // Length is checked against the NORMALISED plate, so trailing or
        // duplicate spaces never push a valid plate over the cap.
        if let plate = normaliseRegistrationPlate(form.registrationPlate),
            plate.count > registrationPlateMaxLength
        {
            return .registrationPlateTooLong
        }
        return nil
    }

    /// Builds the payload from a valid form; nil if the form is invalid.
    static func toInput(_ form: VehicleForm, currentYear: Int) -> VehicleInput? {
        guard validate(form, currentYear: currentYear) == nil,
            let makeId = form.makeId,
            let modelId = form.modelId,
            let modelYear = form.modelYear,
            let powertrain = form.powertrain
        else { return nil }
        return VehicleInput(
            makeId: makeId,
            modelId: modelId,
            modelYear: modelYear,
            powertrain: powertrain,
            engineDescription: nonEmptyTrimmed(form.engineDescription),
            modifications: nonEmptyTrimmed(form.modifications),
            registrationPlate: normaliseRegistrationPlate(form.registrationPlate)
        )
    }

    private static func trimmed(_ value: String) -> String {
        value.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static func nonEmptyTrimmed(_ value: String) -> String? {
        let result = trimmed(value)
        return result.isEmpty ? nil : result
    }
}

/// Pure garage-list logic shared by the repository, coordinator, and screen.
enum Garage {
    /// The list order: make then model, case-insensitively — Android's
    /// `sortedWith(compareBy(make.lowercase, model.lowercase))`, with the
    /// vehicle id as a final tie-break so the order is deterministic.
    static func sortedForList(_ vehicles: [Vehicle]) -> [Vehicle] {
        vehicles.sorted { lhs, rhs in
            let leftMake = lhs.make.lowercased()
            let rightMake = rhs.make.lowercased()
            if leftMake != rightMake { return leftMake < rightMake }
            let leftModel = lhs.model.lowercased()
            let rightModel = rhs.model.lowercased()
            if leftModel != rightModel { return leftModel < rightModel }
            return lhs.id < rhs.id
        }
    }
}

/// One emission of the own-vehicles listener — the iOS port of Android's
/// `GarageState` minus `Loading`: a repository stream only ever emits SETTLED
/// results (a snapshot or a failure), and the coordinator supplies the
/// loading state before the first emission — the same split as
/// ``EventsListSnapshot``.
enum GarageSnapshot: Equatable, Sendable {
    /// The listener failed. `code` is the bare Firestore status name when one
    /// was available (`PERMISSION_DENIED` for an undeployed rule,
    /// `UNAVAILABLE` when offline, …) — a stable, PII-safe diagnosis, never
    /// exception text — the same rule as ``EventsListSnapshot/failed(code:)``.
    case failed(code: String?)
    /// A fresh snapshot of the user's vehicles, already list-sorted
    /// (``Garage/sortedForList(_:)``).
    case loaded([Vehicle])
}
