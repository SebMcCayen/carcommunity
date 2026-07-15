package com.kungsbackacarcommunity.app.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kungsbackacarcommunity.app.design.KccSpacing

/**
 * The Garage tab landing, led by the member's MAIN CAR photo: a circular image
 * centred at the top, then their cars (My garage) as a prominent button.
 * Friends, Badges, Points and Membership have moved elsewhere (Social tab /
 * profile menu / Settings), so this screen now holds only the car header and the
 * Cars action. The user's profile picture is deliberately NOT shown here.
 *
 * Shares the [AeroPage] chrome with every other sub-route; Back is handled by
 * the shell's system-Back handler, so this renders no Back affordance.
 *
 * @param avatarUrl resolved Storage URL for the main car's photo (see the call
 *   site in AuthenticatedApp), or null to show the fallback car icon (also used
 *   when no main car is set / a config-less build).
 * @param onVehicles opens the vehicles (My garage) screen, or null when the
 *   destination is unavailable (e.g. non-member) — the button is then hidden,
 *   preserving the previous membership gating.
 */
@Composable
fun GarageHubScreen(
    title: String,
    avatarUrl: String?,
    avatarContentDescription: String,
    vehiclesLabel: String,
    onVehicles: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    AeroPage(title = title, modifier = modifier) {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            GarageAvatar(avatarUrl = avatarUrl, contentDescription = avatarContentDescription)
        }

        if (onVehicles != null) {
            PrimaryGarageButton(
                label = vehiclesLabel,
                icon = Icons.Filled.DirectionsCar,
                onClick = onVehicles,
            )
        }
    }
}

@Composable
private fun GarageAvatar(avatarUrl: String?, contentDescription: String) {
    Box(
        modifier =
            Modifier
                .size(112.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (avatarUrl != null) {
            // Coil renders nothing (keeps the placeholder tint) when no URL
            // resolves — a config-less build never crashes on rendering.
            AsyncImage(
                model = avatarUrl,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Car placeholder (NOT a person/profile icon): the garage header
            // shows the main car's photo, so its empty state is a car, never a
            // profile picture.
            Icon(
                imageVector = Icons.Filled.DirectionsCar,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(64.dp),
            )
        }
    }
}

@Composable
private fun PrimaryGarageButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
        Text(
            text = label,
            modifier = Modifier.padding(start = KccSpacing.s3),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
