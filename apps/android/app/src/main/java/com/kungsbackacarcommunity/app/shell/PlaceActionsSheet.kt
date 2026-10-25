package com.kungsbackacarcommunity.app.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing

/** Test tags so UI tests can address the place-actions sheet's rows without matching copy. */
const val PLACE_ACTIONS_SHEET_TEST_TAG = "place_actions_sheet"
const val PLACE_ACTIONS_NAVIGATE_TEST_TAG = "place_actions_navigate"
const val PLACE_ACTIONS_COPY_TEST_TAG = "place_actions_copy"
const val PLACE_ACTIONS_SAVE_TEST_TAG = "place_actions_save"

/**
 * The menu a long-press (or a basemap-POI tap) on the map raises, in front of the
 * old "navigate here?" flow: it names the picked point and offers the three things
 * a member can do with it.
 *
 * - **Navigate here** feeds the EXISTING navigate-here preview/route flow (the one
 *   the long-press used to raise directly), so routing is not duplicated.
 * - **Copy position** writes a shareable `geo:` link to the clipboard, to paste
 *   into an in-app chat where it renders as a tappable "show on map" chip.
 * - **Save this location** saves the point through the existing saved-places store.
 *
 * @param placeName the resolved POI name when the gesture was a basemap-place tap,
 *   else null for a bare long-press (the sheet then shows only the coordinate).
 * @param coordinateText the human-readable "lat, lng" of the picked point, always
 *   shown so the member can see exactly where the menu refers to (it matches the
 *   dropped pin on the map behind the sheet).
 */
@Composable
fun PlaceActionsSheet(
    placeName: String?,
    coordinateText: String,
    onNavigate: () -> Unit,
    onCopy: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val name = placeName?.takeIf { it.isNotBlank() }

    MapDetailSheet(
        title = name ?: stringResource(R.string.shell_placeMenuTitle),
        onDismiss = onDismiss,
        leadingContent = { Icon(Icons.Filled.Place, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary) },
        modifier = Modifier.testTag(PLACE_ACTIONS_SHEET_TEST_TAG),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = KccSpacing.s2),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
        ) {
            Text(
                text = coordinateText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = KccSpacing.s2),
            )

            PlaceAction(
                icon = Icons.Filled.Navigation,
                text = stringResource(R.string.shell_placeMenuNavigate),
                onClick = onNavigate,
                primary = true,
                testTag = PLACE_ACTIONS_NAVIGATE_TEST_TAG,
            )
            PlaceAction(
                icon = Icons.Filled.BookmarkBorder,
                text = stringResource(R.string.shell_placeMenuSave),
                onClick = onSave,
                testTag = PLACE_ACTIONS_SAVE_TEST_TAG,
            )
            PlaceAction(
                icon = Icons.Filled.ContentCopy,
                text = stringResource(R.string.shell_placeMenuCopy),
                onClick = onCopy,
                testTag = PLACE_ACTIONS_COPY_TEST_TAG,
            )
            PlaceAction(
                icon = null,
                text = stringResource(R.string.shell_placeMenuClose),
                onClick = onDismiss,
            )
        }
    }
}

@Composable
private fun PlaceAction(
    icon: ImageVector?,
    text: String,
    onClick: () -> Unit,
    testTag: String? = null,
    primary: Boolean = false,
) {
    val actionModifier = Modifier.fillMaxWidth().heightIn(min = KccSpacing.s12)
        .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
    val label: @Composable () -> Unit = {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null)
        }
        Text(
            text = text,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth().padding(start = if (icon != null) KccSpacing.s3 else 0.dp),
        )
    }
    when {
        primary -> Button(onClick = onClick, modifier = actionModifier, content = { label() })
        icon != null -> OutlinedButton(onClick = onClick, modifier = actionModifier, content = { label() })
        else -> TextButton(onClick = onClick, modifier = actionModifier, content = { label() })
    }
}
