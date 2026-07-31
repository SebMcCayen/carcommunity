package com.kungsbackacarcommunity.app.garage

import java.util.Locale

/**
 * Garage / vehicles domain + validation (Phase 12 slice 13).
 *
 * Mirrors the backend garage-core contract: the powertrain vocabulary, the
 * field bounds, and the plate normalisation. `registrationPlate` is a
 * DELIBERATELY PUBLIC, user-entered field (Seb product decision) shown on the
 * car profile; VIN and other private data are still never represented. Pure
 * Kotlin — JVM-testable.
 *
 * Make, model and year are SELECTED from [VehicleCatalogue], never typed
 * (2026-07), so the community can count cars per manufacturer. The form
 * therefore carries catalogue ids ([VehicleForm.makeId] / [VehicleForm.modelId])
 * and sends only those; the backend derives the display text. Vehicles created
 * before the catalogue hold free text and no ids — see [VehicleForm.legacyMake]
 * for how editing one of those works without losing anything.
 */

/**
 * Vehicle powertrain (vehicles/{id}.powertrain).
 *
 * Mirrors garage-core.ts. The vocabulary is deliberately WIDER than what the
 * form offers: [selectable] is the product-facing set (Petrol / Diesel / Hybrid
 * / Electric, in that order), while [PLUG_IN_HYBRID] and [OTHER] are RETIRED —
 * no longer offered, but still parsed, stored and rendered.
 *
 * Keeping the retired constants is load-bearing, not tidiness: the Firestore
 * read path drops a whole vehicle when [fromWire] returns null
 * (FirebaseGarageRepository.toVehicle), so deleting them would make every
 * pre-existing plug-in-hybrid / other car silently VANISH from its owner's
 * garage and public profile. Do not remove them while any vehicle still holds
 * the value.
 */
enum class VehiclePowertrain(val wire: String, val isSelectable: Boolean) {
    PETROL("petrol", isSelectable = true),
    DIESEL("diesel", isSelectable = true),
    HYBRID("hybrid", isSelectable = true),
    ELECTRIC("electric", isSelectable = true),

    /** Retired (see the enum KDoc): still parsed/rendered, never offered. */
    PLUG_IN_HYBRID("plug_in_hybrid", isSelectable = false),

    /** Retired (see the enum KDoc): still parsed/rendered, never offered. */
    OTHER("other", isSelectable = false),
    ;

    companion object {
        fun fromWire(value: String?): VehiclePowertrain? = values().firstOrNull { it.wire == value }

        /**
         * The powertrains the add/edit form offers, in render order: exactly
         * Petrol, Diesel, Hybrid, Electric. Retired values are excluded, so a
         * NEW vehicle can only ever be created with one of these four.
         */
        fun selectable(): List<VehiclePowertrain> = values().filter { it.isSelectable }
    }
}

/** A garage vehicle / car profile (vehicles/{id}). */
data class Vehicle(
    val id: String,
    /**
     * Human-readable make. For a catalogue vehicle the backend DERIVED this from
     * [makeId]; for a vehicle created before the catalogue existed it is the
     * owner's original free text, untouched. Render it through
     * [VehicleDisplay.makeLabel] rather than raw so the "Other / not listed"
     * bucket shows a localized label — but it is always safe to fall back to,
     * which is what keeps pre-catalogue cars rendering exactly as they did.
     */
    val make: String,
    val model: String,
    /**
     * Catalogue manufacturer id (`volvo`, or [VehicleCatalogue.OTHER_ID]), or
     * null for a vehicle written on the legacy free-text path. This — not
     * [make] — is what community aggregation counts, which is the whole reason
     * the form uses dropdowns.
     */
    val makeId: String? = null,
    /** Catalogue model id within [makeId] (or [VehicleCatalogue.OTHER_ID]); null on the legacy path. */
    val modelId: String? = null,
    val modelYear: Int,
    val powertrain: VehiclePowertrain,
    val engineDescription: String?,
    /**
     * Free-text "modifications" note for the car profile. Backed by the
     * vehicles/{id}.description field (garage-core), which is the existing
     * ≤500-char free-text field — reused here so no new backend field is needed.
     */
    val modifications: String? = null,
    /**
     * Registration plate (vehicles/{id}.registrationPlate). A DELIBERATELY
     * PUBLIC, user-entered field (Seb product decision); normalised
     * (trim/collapse/uppercase, ≤12 chars) by the backend. null when the owner
     * left it blank.
     *
     * Audience: the `vehicles` read rule is `allow read: if isAuthenticated()`,
     * so ANY signed-in user can read this — it is NOT member-gated and is NOT
     * withdrawn from suspended accounts. Do not describe it as "shown to other
     * members"; that understates it. Narrowing the audience means changing that
     * Firestore rule, not just which screens render the field.
     *
     * Rendered on the owner's own [VehicleDetailScreen] AND on another member's
     * profile card
     * ([com.kungsbackacarcommunity.app.memberprofile.MemberProfileScreen]), so
     * every mapper that feeds those screens must carry the field — the
     * member-profile mapper originally dropped it, which made a deliberately
     * public field owner-only in practice.
     */
    val registrationPlate: String? = null,
    /**
     * Cloud Storage path of the COVER photo
     * (vehicleImages/{uid}/{vehicleId}/{imageId}), or null when unset. Kept as a
     * denormalised mirror of the cover (`photoPaths[0]`) for the profile card
     * and legacy clients. The path is stored; a URL is resolved lazily for
     * rendering.
     */
    val imagePath: String? = null,
    /**
     * Ordered photo gallery paths (vehicles/{id}.photoPaths), cover first. The
     * source of truth for the detail-page gallery. Empty for a vehicle with no
     * photos, and for legacy documents that predate the field — the read path
     * ([FirebaseGarageRepository]) falls back to `listOfNotNull(imagePath)` so
     * those still show their single photo. Managed by the garage-addVehiclePhoto
     * / removeVehiclePhoto / reorderVehiclePhotos callables.
     */
    val photoPaths: List<String> = emptyList(),
    /**
     * True for the user's single "main car" (at most one per user, enforced by
     * the garage-setMainVehicle callable). The main car's photo replaces the
     * profile picture at the top of the garage.
     */
    val isMainCar: Boolean = false,
)

/**
 * Editable form state.
 *
 * Make/model/year are SELECTIONS: [makeId], [modelId] and [modelYear] hold
 * catalogue ids / a picked year, and there is deliberately no free-text field
 * for any of them.
 *
 * [legacyMake] / [legacyModel] are read-only carriers for a vehicle created
 * before the catalogue: the form opens with nothing selected (we refuse to guess
 * which catalogue entry "Wolwo 245" meant) and shows the saved text so the owner
 * can see what they are replacing. They are never sent anywhere.
 */
data class VehicleForm(
    val makeId: String? = null,
    val modelId: String? = null,
    val modelYear: Int? = null,
    val powertrain: VehiclePowertrain? = null,
    val engineDescription: String = "",
    val modifications: String = "",
    val registrationPlate: String = "",
    val legacyMake: String = "",
    val legacyModel: String = "",
)

/**
 * The validated add/update payload.
 *
 * Carries the catalogue IDS only — the display strings are derived server-side
 * from the same catalogue, so a client can never store a `volvo` id labelled
 * "Ferrari".
 */
data class VehicleInput(
    val makeId: String,
    val modelId: String,
    val modelYear: Int,
    val powertrain: VehiclePowertrain,
    val engineDescription: String?,
    val modifications: String?,
    /** Normalised plate (trim/collapse/uppercase), or null when blank. */
    val registrationPlate: String? = null,
)

/** First validation problem, or null when valid. */
enum class VehicleFieldError {
    MAKE_REQUIRED,
    MODEL_REQUIRED,
    MODEL_YEAR_REQUIRED,
    MODEL_YEAR_INVALID,
    POWERTRAIN_REQUIRED,
    ENGINE_DESCRIPTION_TOO_LONG,
    MODIFICATIONS_TOO_LONG,
    REGISTRATION_PLATE_TOO_LONG,
}

object VehicleValidation {
    /**
     * First model year the selector OFFERS (catalogue `minModelYear`).
     *
     * Deliberately later than the backend's absolute floor of 1886, which the
     * backend still honours on its legacy free-text path so no pre-existing
     * vehicle became unsaveable. Nothing this form produces can be older than
     * this, because the year is picked from a list.
     */
    const val MIN_MODEL_YEAR = VehicleCatalogue.MIN_MODEL_YEAR

    /** Backend cap (garage-core MAX_VEHICLE_PHOTOS): photos per vehicle. */
    const val MAX_VEHICLE_PHOTOS = 10

    /** Last offered year: the next model year (catalogue `maxModelYearOffset`). */
    fun maxModelYear(currentYear: Int): Int = VehicleCatalogue.maxModelYear(currentYear)

    /** Backend bound (garage-core): engineDescription ≤120. */
    const val ENGINE_DESCRIPTION_MAX_LENGTH = 120

    /** Backend bound (garage-core VEHICLE_DESCRIPTION_MAX_LENGTH) for modifications. */
    const val MODIFICATIONS_MAX_LENGTH = 500

    /** Backend bound (garage-core REGISTRATION_PLATE_MAX_LENGTH), checked against the NORMALISED plate. */
    const val REGISTRATION_PLATE_MAX_LENGTH = 12

    /**
     * Normalises a plate the same way the backend does (garage-core
     * normaliseRegistrationPlate): trim, collapse internal whitespace to a
     * single space, uppercase. Blank -> null (so an empty field clears the
     * plate). Format-agnostic — no country regex, imports/personalised plates
     * pass through.
     *
     * Uppercasing pins [Locale.ROOT] so the canonical plate is stable across
     * device locales (e.g. Turkish 'i' -> 'I', never 'İ'), matching the
     * backend's locale-independent JS String.toUpperCase().
     */
    fun normaliseRegistrationPlate(raw: String): String? {
        val collapsed = raw.trim().replace(Regex("\\s+"), " ").uppercase(Locale.ROOT)
        return collapsed.ifEmpty { null }
    }

    /**
     * Returns the first validation error, or null when the form is valid.
     *
     * Make and model are checked as SELECTIONS against [VehicleCatalogue]: the
     * "Other / not listed" bucket is accepted at both levels, and a model is only
     * valid under the manufacturer that offers it (model ids repeat across
     * brands, so a stale selection left over from switching manufacturer must be
     * caught here rather than sent).
     */
    fun validate(form: VehicleForm, currentYear: Int): VehicleFieldError? {
        val makeId = form.makeId
        if (makeId == null) return VehicleFieldError.MAKE_REQUIRED
        if (makeId != VehicleCatalogue.OTHER_ID && !VehicleCatalogue.isKnownMake(makeId)) {
            return VehicleFieldError.MAKE_REQUIRED
        }
        if (!VehicleCatalogue.isSelectableModel(makeId, form.modelId)) {
            return VehicleFieldError.MODEL_REQUIRED
        }
        val year = form.modelYear ?: return VehicleFieldError.MODEL_YEAR_REQUIRED
        if (!VehicleCatalogue.isOfferedYear(year, currentYear)) {
            return VehicleFieldError.MODEL_YEAR_INVALID
        }
        if (form.powertrain == null) return VehicleFieldError.POWERTRAIN_REQUIRED
        if (form.engineDescription.trim().length > ENGINE_DESCRIPTION_MAX_LENGTH) {
            return VehicleFieldError.ENGINE_DESCRIPTION_TOO_LONG
        }
        if (form.modifications.trim().length > MODIFICATIONS_MAX_LENGTH) {
            return VehicleFieldError.MODIFICATIONS_TOO_LONG
        }
        // Length is checked against the NORMALISED plate, so trailing/duplicate
        // spaces never push a valid plate over the cap.
        val plate = normaliseRegistrationPlate(form.registrationPlate)
        if (plate != null && plate.length > REGISTRATION_PLATE_MAX_LENGTH) {
            return VehicleFieldError.REGISTRATION_PLATE_TOO_LONG
        }
        return null
    }

    /** Builds the payload from a valid form; null if the form is invalid. */
    fun toInput(form: VehicleForm, currentYear: Int): VehicleInput? {
        if (validate(form, currentYear) != null) return null
        return VehicleInput(
            makeId = form.makeId!!,
            modelId = form.modelId!!,
            modelYear = form.modelYear!!,
            powertrain = form.powertrain!!,
            engineDescription = form.engineDescription.trim().takeIf { it.isNotEmpty() },
            modifications = form.modifications.trim().takeIf { it.isNotEmpty() },
            registrationPlate = normaliseRegistrationPlate(form.registrationPlate),
        )
    }
}
