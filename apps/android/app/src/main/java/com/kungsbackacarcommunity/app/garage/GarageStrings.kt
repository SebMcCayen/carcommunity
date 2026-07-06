package com.kungsbackacarcommunity.app.garage

import androidx.annotation.StringRes
import com.kungsbackacarcommunity.app.R

/** Localized-label lookups for the garage enums/errors (Phase 12 slice 13). */

@StringRes
fun VehiclePowertrain.labelRes(): Int =
    when (this) {
        VehiclePowertrain.PETROL -> R.string.garage_powertrain_petrol
        VehiclePowertrain.DIESEL -> R.string.garage_powertrain_diesel
        VehiclePowertrain.HYBRID -> R.string.garage_powertrain_hybrid
        VehiclePowertrain.PLUG_IN_HYBRID -> R.string.garage_powertrain_plug_in_hybrid
        VehiclePowertrain.ELECTRIC -> R.string.garage_powertrain_electric
        VehiclePowertrain.OTHER -> R.string.garage_powertrain_other
    }

@StringRes
fun VehicleFieldError.messageRes(): Int =
    when (this) {
        VehicleFieldError.MAKE_REQUIRED -> R.string.garage_validationMakeRequired
        VehicleFieldError.MAKE_TOO_LONG -> R.string.garage_validationMakeTooLong
        VehicleFieldError.MODEL_REQUIRED -> R.string.garage_validationModelRequired
        VehicleFieldError.MODEL_TOO_LONG -> R.string.garage_validationModelTooLong
        VehicleFieldError.MODEL_YEAR_REQUIRED -> R.string.garage_validationModelYearRequired
        VehicleFieldError.MODEL_YEAR_INVALID -> R.string.garage_validationModelYearInvalid
        VehicleFieldError.POWERTRAIN_REQUIRED -> R.string.garage_validationPowertrainRequired
        VehicleFieldError.ENGINE_DESCRIPTION_TOO_LONG -> R.string.garage_validationEngineDescriptionTooLong
    }
