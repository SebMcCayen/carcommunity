package com.kungsbackacarcommunity.app.crownhunt

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource

/** Reuse the crown silhouette and native perk icons; adjacent names carry meaning. */
@Composable
internal fun PerkIcon(kind: PerkKind, modifier: Modifier = Modifier) {
    if (kind == PerkKind.TRAP) {
        Icon(painterResource(crownGlyphRes(CrownRarity.RARE)), contentDescription = null,
            modifier = modifier, tint = Color(0xFF9B5CFF))
    } else {
        Icon(if (kind == PerkKind.SHIELD) Icons.Filled.Shield else Icons.Filled.Bolt,
            contentDescription = null, modifier = modifier, tint = MaterialTheme.colorScheme.tertiary)
    }
}
