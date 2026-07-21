package com.kungsbackacarcommunity.app.garage

/**
 * Garage / vehicles domain + validation (Phase 12 slice 13).
 *
 * Mirrors the backend garage-core contract: the powertrain vocabulary, the
 * field bounds (make/model 1..N, modelYear 1886..currentYear+2), and the
 * privacy stance — no registration numbers/VIN are ever represented. Pure
 * Kotlin — JVM-testable.
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
    val make: String,
    val model: String,
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
     * Cloud Storage path of the COVER photo
     * (vehicleImages/{uid}/{vehicleId}/{imageId}), or null when unset. Kept as a
     * denormalised mirror of [photoPaths]`[0]` for the profile card and legacy
     * clients. The path is stored; a URL is resolved lazily for rendering.
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

/** Editable form state (modelYear is text while typing). */
data class VehicleForm(
    val make: String = "",
    val model: String = "",
    val modelYear: String = "",
    val powertrain: VehiclePowertrain? = null,
    val engineDescription: String = "",
    val modifications: String = "",
)

/** The validated add/update payload. */
data class VehicleInput(
    val make: String,
    val model: String,
    val modelYear: Int,
    val powertrain: VehiclePowertrain,
    val engineDescription: String?,
    val modifications: String?,
)

/** First validation problem, or null when valid. */
enum class VehicleFieldError {
    MAKE_REQUIRED,
    MAKE_TOO_LONG,
    MODEL_REQUIRED,
    MODEL_TOO_LONG,
    MODEL_YEAR_REQUIRED,
    MODEL_YEAR_INVALID,
    POWERTRAIN_REQUIRED,
    ENGINE_DESCRIPTION_TOO_LONG,
    MODIFICATIONS_TOO_LONG,
}

object VehicleValidation {
    const val MIN_MODEL_YEAR = 1886

    /** Backend cap (garage-core MAX_VEHICLE_PHOTOS): photos per vehicle. */
    const val MAX_VEHICLE_PHOTOS = 10

    fun maxModelYear(currentYear: Int): Int = currentYear + 2

    /** Backend bounds (garage-core): make/model ≤80, engineDescription ≤120. */
    const val MAKE_MODEL_MAX_LENGTH = 80
    const val ENGINE_DESCRIPTION_MAX_LENGTH = 120

    /** Backend bound (garage-core VEHICLE_DESCRIPTION_MAX_LENGTH) for modifications. */
    const val MODIFICATIONS_MAX_LENGTH = 500

    /** Returns the first validation error, or null when the form is valid. */
    fun validate(form: VehicleForm, currentYear: Int): VehicleFieldError? {
        val make = form.make.trim()
        if (make.isEmpty()) return VehicleFieldError.MAKE_REQUIRED
        if (make.length > MAKE_MODEL_MAX_LENGTH) return VehicleFieldError.MAKE_TOO_LONG
        val model = form.model.trim()
        if (model.isEmpty()) return VehicleFieldError.MODEL_REQUIRED
        if (model.length > MAKE_MODEL_MAX_LENGTH) return VehicleFieldError.MODEL_TOO_LONG
        val yearText = form.modelYear.trim()
        if (yearText.isEmpty()) return VehicleFieldError.MODEL_YEAR_REQUIRED
        val year = yearText.toIntOrNull() ?: return VehicleFieldError.MODEL_YEAR_INVALID
        if (year < MIN_MODEL_YEAR || year > maxModelYear(currentYear)) {
            return VehicleFieldError.MODEL_YEAR_INVALID
        }
        if (form.powertrain == null) return VehicleFieldError.POWERTRAIN_REQUIRED
        if (form.engineDescription.trim().length > ENGINE_DESCRIPTION_MAX_LENGTH) {
            return VehicleFieldError.ENGINE_DESCRIPTION_TOO_LONG
        }
        if (form.modifications.trim().length > MODIFICATIONS_MAX_LENGTH) {
            return VehicleFieldError.MODIFICATIONS_TOO_LONG
        }
        return null
    }

    /** Builds the payload from a valid form; null if the form is invalid. */
    fun toInput(form: VehicleForm, currentYear: Int): VehicleInput? {
        if (validate(form, currentYear) != null) return null
        return VehicleInput(
            make = form.make.trim(),
            model = form.model.trim(),
            modelYear = form.modelYear.trim().toInt(),
            powertrain = form.powertrain!!,
            engineDescription = form.engineDescription.trim().takeIf { it.isNotEmpty() },
            modifications = form.modifications.trim().takeIf { it.isNotEmpty() },
        )
    }
}
