package com.kungsbackacarcommunity.app.garage

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import coil.network.HttpException
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.media.rememberStorageImage

/**
 * How a car photo is sized, shaped and aligned at a given call site.
 *
 * The two variants are DELIBERATELY different looks, not drift: the owner's own
 * garage shows a compact circular badge of a car they already know, so the list
 * stays skimmable, while a stranger's profile shows a wide postcard of a car
 * they don't. Both go through the same [VehiclePhoto], so the resolution,
 * cropping and no-photo behaviour can no longer diverge between the two screens.
 *
 * [alignment] is part of the style rather than a separate parameter because it
 * follows from the sizing: a fixed-diameter circle has to be told where to sit in
 * a full-width column, whereas a full-width photo already occupies the whole
 * column and its alignment is inert.
 */
@Immutable
internal sealed interface VehiclePhotoStyle {
    /** Where the photo sits in the card's column. */
    val alignment: Alignment.Horizontal

    /** A fixed-diameter circle, centred in the card. */
    data class Circle(val diameter: Dp) : VehiclePhotoStyle {
        override val alignment: Alignment.Horizontal = Alignment.CenterHorizontally
    }

    /** A full-width rounded rectangle at a fixed [aspectRatio] (width / height). */
    data class FullWidth(val aspectRatio: Float, val cornerRadius: Dp) : VehiclePhotoStyle {
        override val alignment: Alignment.Horizontal = Alignment.Start
    }
}

/**
 * The car's photo, resolved from its Storage path at render time and shaped by
 * [style]. `ContentScale.Crop` centre-crops whatever ratio the source was into
 * the target shape — no stretching, just a centred cut.
 *
 * Renders NOTHING when the car has no photo — the card simply starts at its
 * title, exactly as it did before photos existed. When the car DOES have one,
 * the shaped box is laid out immediately, filled with a neutral surface tint,
 * and the image fades into it. That is a deliberate change from "render nothing
 * until the URL resolves": the photo is the tallest thing in the card, so
 * appearing late shoved the whole list down under the reader. Reserving the
 * space costs a grey circle for a moment and buys a layout that never jumps.
 * (The same neutral-circle-then-image treatment the member avatars use.)
 *
 * The box is sized BEFORE the image is requested, so Coil's size resolver reads
 * exact constraints and decodes a bitmap scaled to the target (180dp circle in
 * My Garage) rather than the full-resolution upload.
 *
 * @param contentDescription accessibility label; null marks the photo decorative
 *   (the surrounding card already names the car).
 */
@Composable
private fun VehiclePhoto(
    imagePath: String?,
    style: VehiclePhotoStyle,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    if (imagePath.isNullOrBlank()) return
    val image = rememberStorageImage(LocalContext.current, imagePath)
    val url = image.url
    Box(
        modifier = modifier
            .then(
                when (style) {
                    is VehiclePhotoStyle.Circle ->
                        Modifier
                            .size(style.diameter)
                            .clip(CircleShape)

                    is VehiclePhotoStyle.FullWidth ->
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(style.aspectRatio)
                            .clip(RoundedCornerShape(style.cornerRadius))
                },
            )
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                // A remembered URL can only go bad by having its download token
                // rotated, and that shows up as the SERVER answering with an
                // error status ([HttpException]) — so only that re-resolves.
                // A transport failure (no network) must NOT invalidate the
                // mapping: offline is exactly when the remembered URL earns its
                // keep, because it is what lets Coil serve the photo from disk
                // with no round-trip at all.
                onError = { state ->
                    if (state.result.throwable is HttpException) image.onLoadFailed()
                },
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/**
 * One car, as a card: photo, then the car's details, then whatever [actions] the
 * hosting screen offers on it.
 *
 * ONE implementation shared by the two screens that list cars — the owner's own
 * garage ([GarageScreen]) and another member's public profile
 * (`MemberProfileScreen`) — so the detail lines and the photo handling cannot
 * drift apart again. Everything the two screens genuinely disagree about is a
 * parameter, and each passes what it renders today:
 *
 *  - [photoStyle] — a centred circle in My Garage, a wide postcard on a profile.
 *  - [registrationPlateFormatRes] — the plate is a DETAIL-level field: a public
 *    profile shows it under the make/model line, the owner's own list does not
 *    (their detail page does). Null omits the line entirely.
 *  - [mainCarLabelRes] — both screens badge the main car, in their own words
 *    ("Main car" vs. the profile's phrasing).
 *  - [onOpen] — only the owner's list opens a detail page, so only it wraps the
 *    photo + details in a clickable region. That region deliberately stops short
 *    of [actions]: a tap on Edit/Delete/Set-main must not also be announced as
 *    "open detail".
 *  - [contentSpacing] — the gap between the card's rows, which the two screens
 *    have historically set differently (4dp vs. 8dp). Parameterised rather than
 *    unified so this refactor is invisible on screen; converging the two is a
 *    design decision, not a refactor.
 *
 * @param actions trailing controls (manage buttons); empty for a read-only card.
 */
@Composable
internal fun VehicleCard(
    vehicle: Vehicle,
    photoStyle: VehiclePhotoStyle,
    @StringRes mainCarLabelRes: Int,
    modifier: Modifier = Modifier,
    @StringRes photoContentDescriptionRes: Int? = null,
    @StringRes registrationPlateFormatRes: Int? = null,
    contentSpacing: Dp = KccSpacing.s2,
    onOpen: (() -> Unit)? = null,
    actions: @Composable ColumnScope.() -> Unit = {},
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(contentSpacing),
        ) {
            val summary: @Composable ColumnScope.() -> Unit = {
                VehicleCardSummary(
                    vehicle = vehicle,
                    photoStyle = photoStyle,
                    photoContentDescriptionRes = photoContentDescriptionRes,
                    mainCarLabelRes = mainCarLabelRes,
                    registrationPlateFormatRes = registrationPlateFormatRes,
                )
            }
            if (onOpen == null) {
                summary()
            } else {
                // The photo + details is the tap target that opens the full
                // car-detail page (announced as a button). The manage buttons
                // stay OUTSIDE this clickable region, so a tap on
                // Edit/Delete/Set-main is not also read as "open detail".
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClick = onOpen),
                    verticalArrangement = Arrangement.spacedBy(contentSpacing),
                    content = summary,
                )
            }
            actions()
        }
    }
}

/**
 * The card's photo and detail lines, emitted as siblings into the caller's
 * column so the enclosing column keeps ownership of spacing and of whether the
 * whole block is clickable.
 */
@Composable
private fun ColumnScope.VehicleCardSummary(
    vehicle: Vehicle,
    photoStyle: VehiclePhotoStyle,
    @StringRes photoContentDescriptionRes: Int?,
    @StringRes mainCarLabelRes: Int,
    @StringRes registrationPlateFormatRes: Int?,
) {
    VehiclePhoto(
        imagePath = vehicle.imagePath,
        style = photoStyle,
        contentDescription = photoContentDescriptionRes?.let { stringResource(it) },
        modifier = Modifier.align(photoStyle.alignment),
    )
    Text(
        // Resolved through VehicleDisplay, not read raw: a catalogue vehicle shows
        // the catalogue's current name, an "Other / not listed" one shows the
        // localized label, and a pre-catalogue one shows the owner's own text.
        text = VehicleDisplay.headline(vehicle, stringResource(R.string.garage_catalogueOther)),
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    if (registrationPlateFormatRes != null) {
        // Ordered as on the owner's own detail screen: plate directly under the
        // make/model line. The field is deliberately public — the owner is told
        // so at entry time — so it renders for every viewer, not just the owner.
        // See Vehicle.registrationPlate.
        vehicle.registrationPlate?.takeIf { it.isNotBlank() }?.let { plate ->
            Text(
                text = stringResource(registrationPlateFormatRes, plate),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Text(
        text = stringResource(vehicle.powertrain.labelRes()),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    if (vehicle.isMainCar) {
        Text(
            text = stringResource(mainCarLabelRes),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    vehicle.engineDescription?.takeIf { it.isNotBlank() }?.let { engine ->
        Text(
            text = engine,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    vehicle.modifications?.takeIf { it.isNotBlank() }?.let { mods ->
        Text(
            text = mods,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
