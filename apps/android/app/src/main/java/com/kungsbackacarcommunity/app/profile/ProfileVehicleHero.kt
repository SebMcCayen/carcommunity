package com.kungsbackacarcommunity.app.profile

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.garage.VehiclePhoto
import com.kungsbackacarcommunity.app.garage.VehiclePhotoStyle

internal const val PROFILE_VEHICLE_HERO_TAG = "profile_vehicle_hero"

/** Real permitted vehicle cover only. Uses the existing cached Storage/Coil adapter and fallback. */
@Composable
internal fun ProfileVehicleHero(coverImagePath: String?, avatar: @Composable () -> Unit) {
    if (coverImagePath.isNullOrBlank()) {
        avatar()
        return
    }
    Box(Modifier.fillMaxWidth().testTag(PROFILE_VEHICLE_HERO_TAG)) {
        VehiclePhoto(
            imagePath = coverImagePath,
            style = VehiclePhotoStyle.FullWidth(16f / 9f, KccRadius.md),
            // The cover is decorative; identity and Supporter semantics stay on the avatar.
            modifier = Modifier.clearAndSetSemantics {},
        )
        Box(Modifier.align(Alignment.BottomStart).padding(KccSpacing.s3)) { avatar() }
    }
}
