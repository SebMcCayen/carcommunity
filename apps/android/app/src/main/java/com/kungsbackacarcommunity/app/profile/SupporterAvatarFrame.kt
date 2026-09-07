package com.kungsbackacarcommunity.app.profile

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccPalette

/** Wrap the circular photo so its clip never clips the crown. No data reads. */
@Composable
fun SupporterAvatarFrame(visible: Boolean, content: @Composable BoxScope.() -> Unit) {
    Box {
        content()
        if (visible) {
            val description = stringResource(R.string.supporterBadge_accessibility)
            Canvas(
                Modifier.align(Alignment.BottomEnd).size(28.dp)
                    .background(MaterialTheme.colorScheme.surface, CircleShape)
                    .padding(4.dp)
                    .semantics { contentDescription = description },
            ) {
                val crown = Path().apply {
                    moveTo(0f, size.height * .2f)
                    lineTo(size.width * .25f, size.height * .45f)
                    lineTo(size.width * .5f, 0f)
                    lineTo(size.width * .75f, size.height * .45f)
                    lineTo(size.width, size.height * .2f)
                    lineTo(size.width * .85f, size.height * .85f)
                    lineTo(size.width * .15f, size.height * .85f)
                    close()
                }
                drawPath(crown, KccPalette.crownGold)
            }
        }
    }
}
