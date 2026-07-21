package com.kungsbackacarcommunity.app.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.shell.AeroPage

/** Test tag on the whole Saved-places management screen. */
const val SAVED_PLACES_SCREEN_TEST_TAG = "saved_places_screen"

/** Test tag on the empty-state "add your first place" prompt. */
const val SAVED_PLACES_EMPTY_TEST_TAG = "saved_places_empty"

/** Test tag on the list card holding the saved-place rows. */
const val SAVED_PLACES_LIST_TEST_TAG = "saved_places_list"

/**
 * The standalone **Saved places** management screen, reached from Settings.
 *
 * PR #435 let a user CREATE Home / Work / favourite shortcuts inline while
 * searching, but there was no single place to see, rename or delete them — this
 * is that surface. It reads and writes the SAME [store] the search bar's inline
 * flow uses (production: the per-uid [PrefsSavedPlacesStore]), through the thin
 * [SavedPlacesManager], so there is exactly one source of truth for saved places.
 *
 * Operations, matched to the model's shape ([SavedPlace] / [SavedPlaceKind]):
 * - **Rename** — favourites only. Home and Work render a localized name and
 *   ignore their stored label ([SavedPlace.displayLabel]), so a rename field there
 *   would edit text that is never shown; the row instead offers only address /
 *   delete for the singletons.
 * - **Change address** — every kind. Re-pointing a shortcut at a new location
 *   reuses the existing address search / place picker rather than a second one:
 *   [onChangeLocation] hands control back to [NavigationSearchScreen], where the
 *   user searches the new address and saves it under the same kind. That path
 *   ([NavigationController.savePlace]) already sweeps the old entry, so a Home
 *   moved to a new address stays a single Home.
 * - **Delete** — every kind, behind a confirmation dialog.
 *
 * With nothing saved yet the screen shows a clean prompt (not a bare empty list)
 * whose call to action opens the same picker via [onAddPlace].
 *
 * Saved places are DEVICE-LOCAL (per uid, SharedPreferences); the footer says so.
 * Cloud-syncing them is a separate, larger feature (a backend + rules change) and
 * is deliberately not attempted here.
 *
 * @param store the saved-places store to manage — the caller passes the same
 *   instance the navigation search uses.
 * @param onAddPlace open the address picker to save a brand-new place.
 * @param onChangeLocation open the address picker to re-point [SavedPlace] at a
 *   new address (carried so the host can pre-frame the picker if it chooses).
 */
@Composable
fun SavedPlacesScreen(
    store: SavedPlacesStore,
    onAddPlace: () -> Unit,
    onChangeLocation: (SavedPlace) -> Unit,
    modifier: Modifier = Modifier,
) {
    val manager = remember(store) { SavedPlacesManager(store) }
    // Local snapshot of the (synchronous, local) store; re-read after every edit
    // so the list reflects a rename/delete on the same frame.
    var places by remember(manager) { mutableStateOf(manager.places()) }
    val refresh = { places = manager.places() }

    // The place pending a rename / a delete confirmation, null when none is open.
    var renameTarget by remember { mutableStateOf<SavedPlace?>(null) }
    var deleteTarget by remember { mutableStateOf<SavedPlace?>(null) }

    AeroPage(
        title = stringResource(R.string.savedPlaces_title),
        modifier = modifier.testTag(SAVED_PLACES_SCREEN_TEST_TAG),
    ) {
        Text(
            text = stringResource(R.string.savedPlaces_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (places.isEmpty()) {
            EmptyState(onAddPlace = onAddPlace)
        } else {
            SavedPlacesList(
                places = places,
                onRename = { renameTarget = it },
                onChangeAddress = onChangeLocation,
                onDelete = { deleteTarget = it },
            )
            OutlinedButton(
                onClick = onAddPlace,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.savedPlaces_addAction),
                    modifier = Modifier.padding(start = KccSpacing.s2),
                )
            }
        }

        Text(
            text = stringResource(R.string.savedPlaces_deviceLocalNote),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    renameTarget?.let { target ->
        RenameDialog(
            place = target,
            onDismiss = { renameTarget = null },
            onConfirm = { newLabel ->
                manager.rename(target, newLabel)
                refresh()
                renameTarget = null
            },
        )
    }

    deleteTarget?.let { target ->
        DeleteDialog(
            place = target,
            onDismiss = { deleteTarget = null },
            onConfirm = {
                manager.delete(target.id)
                refresh()
                deleteTarget = null
            },
        )
    }
}

/**
 * Localized display name for a saved place — the singletons read "Home"/"Work"
 * from resources (their stored label is the raw street name and never shown),
 * favourites read the user's own label. Mirrors the same rule in the search
 * bar's saved-places card so a place reads identically in both surfaces.
 */
@Composable
private fun SavedPlace.displayLabel(): String =
    when (kind) {
        SavedPlaceKind.Home -> stringResource(R.string.addressSearch_savedHome)
        SavedPlaceKind.Work -> stringResource(R.string.addressSearch_savedWork)
        SavedPlaceKind.Favourite -> label
    }

private fun SavedPlaceKind.icon(): ImageVector =
    when (this) {
        SavedPlaceKind.Home -> Icons.Filled.Home
        SavedPlaceKind.Work -> Icons.Filled.Work
        SavedPlaceKind.Favourite -> Icons.Filled.Star
    }

@Composable
private fun SavedPlacesList(
    places: List<SavedPlace>,
    onRename: (SavedPlace) -> Unit,
    onChangeAddress: (SavedPlace) -> Unit,
    onDelete: (SavedPlace) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(KccRadius.lg),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().testTag(SAVED_PLACES_LIST_TEST_TAG),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            places.forEachIndexed { index, place ->
                if (index > 0) HorizontalDivider()
                SavedPlaceRow(
                    place = place,
                    onRename = { onRename(place) },
                    onChangeAddress = { onChangeAddress(place) },
                    onDelete = { onDelete(place) },
                )
            }
        }
    }
}

@Composable
private fun SavedPlaceRow(
    place: SavedPlace,
    onRename: () -> Unit,
    onChangeAddress: () -> Unit,
    onDelete: () -> Unit,
) {
    val label = place.displayLabel()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = KccSpacing.s4, top = KccSpacing.s3, bottom = KccSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
    ) {
        Icon(
            imageVector = place.kind.icon(),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // The address the shortcut routes to — otherwise a Home row reads just
            // "Home" with no way to tell WHICH address it points at.
            val subtitle = place.place.address ?: place.place.name
            if (subtitle != label) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        RowActions(
            place = place,
            label = label,
            onRename = onRename,
            onChangeAddress = onChangeAddress,
            onDelete = onDelete,
        )
    }
}

/**
 * The trailing overflow menu for one saved place. Rename is offered only for
 * favourites (see [SavedPlacesScreen]); Change address and Delete apply to every
 * kind.
 */
@Composable
private fun RowActions(
    place: SavedPlace,
    label: String,
    onRename: () -> Unit,
    onChangeAddress: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Filled.MoreVert,
                contentDescription = stringResource(R.string.savedPlaces_moreActions, label),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (place.kind == SavedPlaceKind.Favourite) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.savedPlaces_rename)) },
                    leadingIcon = {
                        Icon(imageVector = Icons.Filled.Edit, contentDescription = null)
                    },
                    onClick = {
                        expanded = false
                        onRename()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.savedPlaces_changeAddress)) },
                leadingIcon = {
                    Icon(imageVector = Icons.Filled.Place, contentDescription = null)
                },
                onClick = {
                    expanded = false
                    onChangeAddress()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(R.string.savedPlaces_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.DeleteOutline,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                    )
                },
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

@Composable
private fun EmptyState(onAddPlace: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(KccRadius.lg),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().testTag(SAVED_PLACES_EMPTY_TEST_TAG),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(KccSpacing.s5),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s3),
        ) {
            Icon(
                imageVector = Icons.Filled.BookmarkBorder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(KccSpacing.s8),
            )
            Text(
                text = stringResource(R.string.savedPlaces_emptyTitle),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.savedPlaces_emptyBody),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onAddPlace, modifier = Modifier.fillMaxWidth()) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = stringResource(R.string.savedPlaces_emptyAction),
                    modifier = Modifier.padding(start = KccSpacing.s2),
                )
            }
        }
    }
}

@Composable
private fun RenameDialog(
    place: SavedPlace,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var label by remember(place) { mutableStateOf(place.label) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.savedPlaces_renameTitle)) },
        text = {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it.take(SavedPlaces.MAX_LABEL) },
                singleLine = true,
                label = { Text(stringResource(R.string.savedPlaces_renameLabel)) },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(label) }) {
                Text(stringResource(R.string.savedPlaces_renameSave))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.savedPlaces_cancel))
            }
        },
    )
}

@Composable
private fun DeleteDialog(
    place: SavedPlace,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val label = place.displayLabel()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.savedPlaces_deleteTitle)) },
        text = { Text(stringResource(R.string.savedPlaces_deleteMessage, label)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.savedPlaces_deleteConfirm),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.savedPlaces_cancel))
            }
        },
    )
}
