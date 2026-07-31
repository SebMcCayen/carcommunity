package com.kungsbackacarcommunity.app.garage

import java.text.Normalizer

/**
 * The static manufacturer/model catalogue behind the garage's make/model/year
 * selectors (Phase: structured vehicle catalogue, 2026-07).
 *
 * Members SELECT make, model and year; they never type them, so the community
 * can count cars per manufacturer. The data is the canonical contract
 * `contracts/vehicles/vehicle-catalogue.json`, mirrored into the packed
 * [VehicleCatalogueData] by `scripts/generate-vehicle-catalogue.mjs` and
 * re-checked by CI, so this app and the backend validate against exactly the
 * same ids. What the client offers is UX only — the backend re-validates every
 * id (functions/src/garage/vehicle-catalogue.ts).
 *
 * Parsing is LAZY: nothing is touched until the add/edit-vehicle form (or a
 * label lookup) first needs it, so an app start that never opens the garage does
 * not pay for ~1300 models. Pure Kotlin — JVM-testable, no Compose, no Android.
 *
 * THE "OTHER / NOT LISTED" BUCKET
 * ------------------------------
 * [OTHER_ID] is offered as an extra row at BOTH levels and is not part of the
 * contract data. A rare import, a kit car or a brand nobody listed must still be
 * addable: in an enthusiast community the unusual cars are largely the point, and
 * a strict list with no alternative would lock out exactly the most engaged
 * members. It stays a SELECTION (there is deliberately no free-text field), so
 * the no-typing rule holds and the data keeps an explicit, countable bucket
 * instead of prose nobody can group.
 */

/** One model within a manufacturer. */
data class CatalogueModel(val id: String, val name: String)

/** One manufacturer plus its models. */
data class CatalogueMake(
    val id: String,
    val name: String,
    /** True for brands common on Swedish roads — surfaced above the rest. */
    val common: Boolean,
    val models: List<CatalogueModel>,
)

/**
 * A selectable row in a picker.
 *
 * [isOther] rows carry no catalogue [name] (it is empty): the "Other / not
 * listed" label is a translated UI string, unlike every real make/model, which
 * is a proper noun and is never translated.
 */
data class CatalogueOption(val id: String, val name: String, val isOther: Boolean = false)

object VehicleCatalogue {
    /** The reserved id for "Other / not listed", valid as both a make and a model. */
    const val OTHER_ID: String = VehicleCatalogueData.OTHER_ID

    /** The catalogue release this build was generated from (diagnostics only). */
    const val VERSION: String = VehicleCatalogueData.VERSION

    /** First model year offered by the year selector (contract `minModelYear`). */
    const val MIN_MODEL_YEAR: Int = VehicleCatalogueData.MIN_MODEL_YEAR

    /** Years past the current year the selector offers (contract `maxModelYearOffset`). */
    const val MAX_MODEL_YEAR_OFFSET: Int = VehicleCatalogueData.MAX_MODEL_YEAR_OFFSET

    /**
     * Every manufacturer in contract order: `common` brands first (rough
     * prevalence order), then the remainder alphabetically. The order is part of
     * the contract, so the first screenful is useful without searching.
     */
    val makes: List<CatalogueMake> by lazy { parseEncoded() }

    private val makesById: Map<String, CatalogueMake> by lazy { makes.associateBy { it.id } }

    /** The catalogue entry for [makeId], or null for null / [OTHER_ID] / unknown. */
    fun make(makeId: String?): CatalogueMake? = makeId?.let { makesById[it] }

    /** Display name for a manufacturer, or null for [OTHER_ID] / an id this build does not know. */
    fun makeName(makeId: String?): String? = make(makeId)?.name

    /** Display name for a model within its manufacturer, or null for [OTHER_ID] / unknown. */
    fun modelName(makeId: String?, modelId: String?): String? {
        if (modelId == null) return null
        return make(makeId)?.models?.firstOrNull { it.id == modelId }?.name
    }

    /** True when [makeId] is a real catalogue manufacturer (NOT [OTHER_ID]). */
    fun isKnownMake(makeId: String?): Boolean = make(makeId) != null

    /** True when [modelId] is offered by [makeId] (NOT [OTHER_ID]). */
    fun isKnownModel(makeId: String?, modelId: String?): Boolean = modelName(makeId, modelId) != null

    /**
     * The manufacturer rows to offer, with the "Other / not listed" row LAST.
     *
     * The escape hatch is deliberately always present and always in the same
     * place: a member whose brand is missing must not have to guess whether the
     * list simply failed to load.
     */
    fun makeOptions(): List<CatalogueOption> =
        makes.map { CatalogueOption(it.id, it.name) } + otherOption()

    /**
     * The model rows for [makeId], with "Other / not listed" last.
     *
     * Empty (apart from that row) when no manufacturer is chosen yet or when the
     * chosen one is itself [OTHER_ID] — an unknown brand has no model list, so
     * the only honest model is "other" too. This is what makes the two selectors
     * CASCADE: the model list is derived from the manufacturer, never independent
     * of it (model ids are unique only within a manufacturer — `3` exists under
     * both Mazda and MG — so an independent model list would allow a "Mazda MGB").
     */
    fun modelOptions(makeId: String?): List<CatalogueOption> {
        val models = make(makeId)?.models ?: return listOf(otherOption())
        return models.map { CatalogueOption(it.id, it.name) } + otherOption()
    }

    /** True when [modelId] is still a legal choice under [makeId] (used to reset a stale model). */
    fun isSelectableModel(makeId: String?, modelId: String?): Boolean =
        modelId != null && (modelId == OTHER_ID || isKnownModel(makeId, modelId))

    /**
     * The years the selector offers, NEWEST FIRST — recent cars are the common
     * case, and a 120-row list that starts at 1900 would make everyone scroll.
     */
    fun modelYears(currentYear: Int): List<Int> = (maxModelYear(currentYear) downTo MIN_MODEL_YEAR).toList()

    /** Last offered year: the next model year, so a 2027 car is addable during 2026. */
    fun maxModelYear(currentYear: Int): Int = currentYear + MAX_MODEL_YEAR_OFFSET

    /** True when [year] is inside the offered window (the backend re-checks against ITS clock). */
    fun isOfferedYear(year: Int, currentYear: Int): Boolean =
        year in MIN_MODEL_YEAR..maxModelYear(currentYear)

    /**
     * Filters [options] by a search query, keeping the "Other / not listed" row
     * whatever the query is so the escape hatch can never be filtered away — the
     * one case where a member most needs it is when nothing matches.
     *
     * Matching is diacritic-insensitive ("citroen" finds Citroën, "megane" finds
     * Mégane): a Swedish keyboard reaches ë and é awkwardly, and a search that
     * demands them hides the entry.
     */
    fun filter(options: List<CatalogueOption>, query: String): List<CatalogueOption> {
        val needle = fold(query)
        if (needle.isEmpty()) return options
        return options.filter { it.isOther || fold(it.name).contains(needle) || it.id.contains(needle) }
    }

    private fun otherOption() = CatalogueOption(OTHER_ID, name = "", isOther = true)

    /**
     * Case- and diacritic-insensitive search key. `lowercase()` is already
     * locale-independent in Kotlin (unlike Java's String.toLowerCase()), so no
     * Locale argument is needed or wanted here.
     */
    private fun fold(value: String): String =
        Normalizer.normalize(value.trim().lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")

    /** Parses the packed mirror (see scripts/generate-vehicle-catalogue.mjs). */
    private fun parseEncoded(): List<CatalogueMake> =
        VehicleCatalogueData.ENCODED.map { line ->
            val parts = line.split('|', limit = 4)
            require(parts.size == 4) { "Malformed vehicle-catalogue line: ${line.take(40)}" }
            val models =
                parts[3].split(';').map { chunk ->
                    val split = chunk.indexOf('=')
                    require(split > 0) { "Malformed vehicle-catalogue model chunk: $chunk" }
                    CatalogueModel(chunk.substring(0, split), chunk.substring(split + 1))
                }
            CatalogueMake(
                id = parts[0],
                name = parts[1],
                common = parts[2] == "1",
                models = models,
            )
        }
}

/**
 * Resolves the make/model text to SHOW for a vehicle. Pure, so the legacy and
 * Other cases are unit-testable off Compose.
 *
 * Three cases, in priority order:
 *  1. A known catalogue id -> the catalogue's display name, so renaming a label
 *     in the contract propagates everywhere without touching stored data.
 *  2. [VehicleCatalogue.OTHER_ID] -> the caller's localized "Other / not listed"
 *     label (the stored placeholder text is deliberately not shown).
 *  3. No id, or an id this build does not know (a vehicle written by a NEWER
 *     client against a newer catalogue) -> the stored text verbatim. This is the
 *     branch that keeps every pre-catalogue vehicle rendering exactly as it did
 *     before the catalogue existed.
 */
object VehicleDisplay {
    fun makeLabel(makeId: String?, storedMake: String, otherLabel: String): String =
        when {
            makeId == null -> storedMake
            makeId == VehicleCatalogue.OTHER_ID -> otherLabel
            else -> VehicleCatalogue.makeName(makeId) ?: storedMake
        }

    fun modelLabel(
        makeId: String?,
        modelId: String?,
        storedModel: String,
        otherLabel: String,
    ): String =
        when {
            modelId == null -> storedModel
            modelId == VehicleCatalogue.OTHER_ID -> otherLabel
            else -> VehicleCatalogue.modelName(makeId, modelId) ?: storedModel
        }

    /**
     * The one-line headline used by the garage card and the car profile, e.g.
     * "Volvo 240 (1988)".
     *
     * When BOTH make and model are the Other bucket the label is shown once —
     * "Other / not listed Other / not listed (1998)" would be absurd.
     */
    fun headline(vehicle: Vehicle, otherLabel: String): String {
        val make = makeLabel(vehicle.makeId, vehicle.make, otherLabel)
        val model = modelLabel(vehicle.makeId, vehicle.modelId, vehicle.model, otherLabel)
        val name =
            if (vehicle.makeId == VehicleCatalogue.OTHER_ID &&
                vehicle.modelId == VehicleCatalogue.OTHER_ID
            ) {
                otherLabel
            } else {
                "$make $model"
            }
        return "$name (${vehicle.modelYear})"
    }
}
