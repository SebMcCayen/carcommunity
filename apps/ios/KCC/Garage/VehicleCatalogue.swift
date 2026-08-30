import Foundation

/// The static manufacturer/model catalogue behind the garage's make/model/year
/// selectors — the iOS port of Android's `garage/VehicleCatalogue.kt`.
///
/// Members SELECT make, model and year; they never type them, so the community
/// can count cars per manufacturer. The data is the canonical contract
/// `contracts/vehicles/vehicle-catalogue.json`, mirrored into the packed
/// ``VehicleCatalogueData`` by `scripts/generate-vehicle-catalogue.mjs` and
/// drift-checked by CI, so this app, Android, and the backend validate against
/// exactly the same ids. What the client offers is UX only — the backend
/// re-validates every id (functions/src/garage/vehicle-catalogue.ts).
///
/// Parsing is LAZY (a `static let` initialises on first touch): nothing is
/// parsed until the add-vehicle form (or a label lookup) first needs it, so an
/// app start that never opens the garage does not pay for ~1300 models. Pure
/// Swift — unit-testable, no SwiftUI, no Firebase.
///
/// THE "OTHER / NOT LISTED" BUCKET
/// ------------------------------
/// ``otherId`` is offered as an extra row at BOTH levels and is not part of
/// the contract data. A rare import, a kit car or a brand nobody listed must
/// still be addable: in an enthusiast community the unusual cars are largely
/// the point, and a strict list with no alternative would lock out exactly the
/// most engaged members. It stays a SELECTION (there is deliberately no
/// free-text field), so the no-typing rule holds and the data keeps an
/// explicit, countable bucket instead of prose nobody can group.

/// One model within a manufacturer.
struct CatalogueModel: Equatable, Sendable {
    let id: String
    let name: String
}

/// One manufacturer plus its models.
struct CatalogueMake: Equatable, Sendable {
    let id: String
    let name: String
    /// True for brands common on Swedish roads — surfaced above the rest.
    let common: Bool
    let models: [CatalogueModel]
}

/// A selectable row in a picker.
///
/// `isOther` rows carry no catalogue `name` (it is empty): the "Other / not
/// listed" label is a translated UI string, unlike every real make/model,
/// which is a proper noun and is never translated.
struct CatalogueOption: Equatable, Sendable, Identifiable {
    let id: String
    let name: String
    var isOther: Bool = false
}

enum VehicleCatalogue {
    /// The reserved id for "Other / not listed", valid as both a make and a model.
    static let otherId: String = VehicleCatalogueData.otherId

    /// The catalogue release this build was generated from (diagnostics only).
    static let version: String = VehicleCatalogueData.version

    /// First model year offered by the year selector (contract `minModelYear`).
    static let minModelYear: Int = VehicleCatalogueData.minModelYear

    /// Years past the current year the selector offers (contract `maxModelYearOffset`).
    static let maxModelYearOffset: Int = VehicleCatalogueData.maxModelYearOffset

    /// Every manufacturer in contract order: `common` brands first (rough
    /// prevalence order), then the remainder alphabetically. The order is part
    /// of the contract, so the first screenful is useful without searching.
    /// `static let` gives the lazy, once-only parse (with Swift's built-in
    /// thread-safe initialisation).
    static let makes: [CatalogueMake] = parseEncoded()

    private static let makesById: [String: CatalogueMake] =
        Dictionary(uniqueKeysWithValues: makes.map { ($0.id, $0) })

    /// The catalogue entry for `makeId`, or nil for nil / ``otherId`` / unknown.
    static func make(_ makeId: String?) -> CatalogueMake? {
        makeId.flatMap { makesById[$0] }
    }

    /// Display name for a manufacturer, or nil for ``otherId`` / an id this
    /// build does not know.
    static func makeName(_ makeId: String?) -> String? {
        make(makeId)?.name
    }

    /// Display name for a model within its manufacturer, or nil for
    /// ``otherId`` / unknown.
    static func modelName(makeId: String?, modelId: String?) -> String? {
        guard let modelId else { return nil }
        return make(makeId)?.models.first { $0.id == modelId }?.name
    }

    /// True when `makeId` is a real catalogue manufacturer (NOT ``otherId``).
    static func isKnownMake(_ makeId: String?) -> Bool {
        make(makeId) != nil
    }

    /// True when `modelId` is offered by `makeId` (NOT ``otherId``).
    static func isKnownModel(makeId: String?, modelId: String?) -> Bool {
        modelName(makeId: makeId, modelId: modelId) != nil
    }

    /// The manufacturer rows to offer, with the "Other / not listed" row LAST.
    ///
    /// The escape hatch is deliberately always present and always in the same
    /// place: a member whose brand is missing must not have to guess whether
    /// the list simply failed to load.
    static func makeOptions() -> [CatalogueOption] {
        makes.map { CatalogueOption(id: $0.id, name: $0.name) } + [otherOption()]
    }

    /// The model rows for `makeId`, with "Other / not listed" last.
    ///
    /// Empty (apart from that row) when no manufacturer is chosen yet or when
    /// the chosen one is itself ``otherId`` — an unknown brand has no model
    /// list, so the only honest model is "other" too. This is what makes the
    /// two selectors CASCADE: the model list is derived from the manufacturer,
    /// never independent of it (model ids are unique only within a
    /// manufacturer — `3` exists under both Mazda and MG — so an independent
    /// model list would allow a "Mazda MGB").
    static func modelOptions(makeId: String?) -> [CatalogueOption] {
        guard let models = make(makeId)?.models else { return [otherOption()] }
        return models.map { CatalogueOption(id: $0.id, name: $0.name) } + [otherOption()]
    }

    /// True when `modelId` is still a legal choice under `makeId` (used to
    /// reset a stale model when the manufacturer changes).
    static func isSelectableModel(makeId: String?, modelId: String?) -> Bool {
        guard let modelId else { return false }
        return modelId == otherId || isKnownModel(makeId: makeId, modelId: modelId)
    }

    /// The years the selector offers, NEWEST FIRST — recent cars are the
    /// common case, and a 120-row list that starts at 1900 would make everyone
    /// scroll.
    static func modelYears(currentYear: Int) -> [Int] {
        Array(stride(from: maxModelYear(currentYear: currentYear), through: minModelYear, by: -1))
    }

    /// Last offered year: the next model year, so a 2027 car is addable
    /// during 2026.
    static func maxModelYear(currentYear: Int) -> Int {
        currentYear + maxModelYearOffset
    }

    /// True when `year` is inside the offered window (the backend re-checks
    /// against ITS clock).
    static func isOfferedYear(_ year: Int, currentYear: Int) -> Bool {
        (minModelYear...maxModelYear(currentYear: currentYear)).contains(year)
    }

    /// Filters `options` by a search query, keeping the "Other / not listed"
    /// row whatever the query is so the escape hatch can never be filtered
    /// away — the one case where a member most needs it is when nothing
    /// matches.
    ///
    /// Matching is diacritic-insensitive ("citroen" finds Citroën, "megane"
    /// finds Mégane): a Swedish keyboard reaches ë and é awkwardly, and a
    /// search that demands them hides the entry.
    static func filter(_ options: [CatalogueOption], query: String) -> [CatalogueOption] {
        let needle = fold(query)
        guard !needle.isEmpty else { return options }
        return options.filter {
            $0.isOther || fold($0.name).contains(needle) || $0.id.contains(needle)
        }
    }

    private static func otherOption() -> CatalogueOption {
        CatalogueOption(id: otherId, name: "", isOther: true)
    }

    /// Case- and diacritic-insensitive search key. The POSIX locale pins the
    /// folding to be device-locale-independent (e.g. Turkish 'I' never breaks
    /// matching), the same posture as Android's `Normalizer` + locale-free
    /// `lowercase()`.
    private static func fold(_ value: String) -> String {
        value
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .folding(
                options: [.diacriticInsensitive, .caseInsensitive],
                locale: Locale(identifier: "en_US_POSIX")
            )
    }

    /// Parses the packed mirror (see scripts/generate-vehicle-catalogue.mjs).
    /// A malformed line is a build defect (the generator guards the reserved
    /// characters), so parsing traps loudly rather than dropping data.
    private static func parseEncoded() -> [CatalogueMake] {
        VehicleCatalogueData.encoded.map { line in
            let parts = line.split(separator: "|", maxSplits: 3, omittingEmptySubsequences: false)
            precondition(parts.count == 4, "Malformed vehicle-catalogue line: \(line.prefix(40))")
            let models = parts[3].split(separator: ";").map { chunk -> CatalogueModel in
                guard let split = chunk.firstIndex(of: "="), split != chunk.startIndex else {
                    preconditionFailure("Malformed vehicle-catalogue model chunk: \(chunk)")
                }
                return CatalogueModel(
                    id: String(chunk[..<split]),
                    name: String(chunk[chunk.index(after: split)...])
                )
            }
            return CatalogueMake(
                id: String(parts[0]),
                name: String(parts[1]),
                common: parts[2] == "1",
                models: models
            )
        }
    }
}

/// Resolves the make/model text to SHOW for a vehicle — the iOS port of
/// Android's `VehicleDisplay`. Pure, so the legacy and Other cases are
/// unit-testable off SwiftUI.
///
/// Three cases, in priority order:
///  1. A known catalogue id → the catalogue's display name, so renaming a
///     label in the contract propagates everywhere without touching stored
///     data.
///  2. ``VehicleCatalogue/otherId`` → the caller's localized "Other / not
///     listed" label (the stored placeholder text is deliberately not shown).
///  3. No id, or an id this build does not know (a vehicle written by a NEWER
///     client against a newer catalogue) → the stored text verbatim. This is
///     the branch that keeps every pre-catalogue vehicle rendering exactly as
///     it did before the catalogue existed.
enum VehicleDisplay {
    static func makeLabel(makeId: String?, storedMake: String, otherLabel: String) -> String {
        switch makeId {
        case nil: storedMake
        case VehicleCatalogue.otherId: otherLabel
        default: VehicleCatalogue.makeName(makeId) ?? storedMake
        }
    }

    static func modelLabel(
        makeId: String?,
        modelId: String?,
        storedModel: String,
        otherLabel: String
    ) -> String {
        switch modelId {
        case nil: storedModel
        case VehicleCatalogue.otherId: otherLabel
        default: VehicleCatalogue.modelName(makeId: makeId, modelId: modelId) ?? storedModel
        }
    }

    /// The one-line headline used by the garage card, e.g. "Volvo 240 (1988)".
    ///
    /// When BOTH make and model are the Other bucket the label is shown once —
    /// "Other / not listed Other / not listed (1998)" would be absurd.
    static func headline(_ vehicle: Vehicle, otherLabel: String) -> String {
        let make = makeLabel(
            makeId: vehicle.makeId, storedMake: vehicle.make, otherLabel: otherLabel
        )
        let model = modelLabel(
            makeId: vehicle.makeId,
            modelId: vehicle.modelId,
            storedModel: vehicle.model,
            otherLabel: otherLabel
        )
        let name =
            if vehicle.makeId == VehicleCatalogue.otherId,
                vehicle.modelId == VehicleCatalogue.otherId
            {
                otherLabel
            } else {
                "\(make) \(model)"
            }
        return "\(name) (\(vehicle.modelYear))"
    }
}
