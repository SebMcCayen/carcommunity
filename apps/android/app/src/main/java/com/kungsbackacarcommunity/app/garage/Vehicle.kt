package com.kungsbackacarcommunity.app.garage

/**
 * Garage / vehicles domain + validation (Phase 12 slice 13).
 *
 * Mirrors the backend garage-core contract: the powertrain vocabulary, the
 * field bounds (make/model 1..N, modelYear 1886..currentYear+2), and the
 * privacy stance — no registration numbers/VIN are ever represented. Pure
 * Kotlin — JVM-testable.
 */

/** Vehicle powertrain (vehicles/{id}.powertrain). */
enum class VehiclePowertrain(val wire: String) {
    PETROL("petrol"),
    DIESEL("diesel"),
    HYBRID("hybrid"),
    PLUG_IN_HYBRID("plug_in_hybrid"),
    ELECTRIC("electric"),
    OTHER("other"),
    ;

    companion object {
        fun fromWire(value: String?): VehiclePowertrain? = values().firstOrNull { it.wire == value }
    }
}

/** A garage vehicle (vehicles/{id}). */
data class Vehicle(
    val id: String,
    val make: String,
    val model: String,
    val modelYear: Int,
    val powertrain: VehiclePowertrain,
    val engineDescription: String?,
)

/** Editable form state (modelYear is text while typing). */
data class VehicleForm(
    val make: String = "",
    val model: String = "",
    val modelYear: String = "",
    val powertrain: VehiclePowertrain? = null,
    val engineDescription: String = "",
)

/** The validated add/update payload. */
data class VehicleInput(
    val make: String,
    val model: String,
    val modelYear: Int,
    val powertrain: VehiclePowertrain,
    val engineDescription: String?,
)

/** First validation problem, or null when valid. */
enum class VehicleFieldError {
    MAKE_REQUIRED,
    MODEL_REQUIRED,
    MODEL_YEAR_REQUIRED,
    MODEL_YEAR_INVALID,
    POWERTRAIN_REQUIRED,
}

object VehicleValidation {
    const val MIN_MODEL_YEAR = 1886

    fun maxModelYear(currentYear: Int): Int = currentYear + 2

    const val MAKE_MODEL_MAX_LENGTH = 80

    /** Returns the first validation error, or null when the form is valid. */
    fun validate(form: VehicleForm, currentYear: Int): VehicleFieldError? {
        if (form.make.trim().isEmpty()) return VehicleFieldError.MAKE_REQUIRED
        if (form.model.trim().isEmpty()) return VehicleFieldError.MODEL_REQUIRED
        val yearText = form.modelYear.trim()
        if (yearText.isEmpty()) return VehicleFieldError.MODEL_YEAR_REQUIRED
        val year = yearText.toIntOrNull() ?: return VehicleFieldError.MODEL_YEAR_INVALID
        if (year < MIN_MODEL_YEAR || year > maxModelYear(currentYear)) {
            return VehicleFieldError.MODEL_YEAR_INVALID
        }
        if (form.powertrain == null) return VehicleFieldError.POWERTRAIN_REQUIRED
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
        )
    }
}
