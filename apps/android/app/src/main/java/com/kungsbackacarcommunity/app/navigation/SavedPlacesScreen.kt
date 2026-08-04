package com.kungsbackacarcommunity.app.navigation

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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

/** Test tag on the long-press actions bottom sheet (Rename / Share / Delete). */
const val SAVED_PLACES_ACTIONS_SHEET_TEST_TAG = "saved_places_actions_sheet"

/** Test-tag prefix for one saved-place row (suffixed with the place id). */
fun savedPlaceRowTestTag(id: String): String = "saved_places_row_$id"

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
 * @param onShare share a saved place with a friend — the host raises the friend
 *   picker for the resolved name + coordinate. Reached from a row's overflow menu
 *   and from long-pressing a row (the Rename / Share / Delete sheet).
 */
@Composable
fun SavedPlacesScreen(
    store: SavedPlacesStore,
    onAddPlace: () -> Unit,
    onChangeLocation: (SavedPlace) -> Unit,
    onShare: (name: String, point: LatLng) -> Unit,
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
    // The place whose long-press Rename / Share / Delete sheet is open, null when none.
    var actionsTarget by remember { mutableStateOf<SavedPlace?>(null) }

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
                onShare = onShare,
                onLongPress = { actionsTarget = it },
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

    actionsTarget?.let { target ->
        SavedPlaceActionsSheet(
            place = target,
            onRename = {
                actionsTarget = null
                renameTarget = target
            },
            onShare = { name ->
                actionsTarget = null
                onShare(name, target.place.point)
            },
            onDelete = {
                actionsTarget = null
                deleteTarget = target
            },
            onDismiss = { actionsTarget = null },
        )
    }
}

@Composable
private fun SavedPlacesList(
    places: List<SavedPlace>,
    onRename: (SavedPlace) -> Unit,
    onChangeAddress: (SavedPlace) -> Unit,
    onDelete: (SavedPlace) -> Unit,
    onShare: (name: String, point: LatLng) -> Unit,
    onLongPress: (SavedPlace) -> Unit,
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
                // Key each row by its stable place id: RowActions holds per-row
                // remember state (the overflow-menu `expanded` flag), so without a
                // key a deletion would shift composition slots and leave that menu
                // state attached to the wrong row.
                key(place.id) {
                    if (index > 0) HorizontalDivider()
                    SavedPlaceRow(
                        place = place,
                        onRename = { onRename(place) },
                        onChangeAddress = { onChangeAddress(place) },
                        onDelete = { onDelete(place) },
                        onShare = onShare,
                        onLongPress = { onLongPress(place) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SavedPlaceRow(
    place: SavedPlace,
    onRename: () -> Unit,
    onChangeAddress: () -> Unit,
    onDelete: () -> Unit,
    onShare: (name: String, point: LatLng) -> Unit,
    onLongPress: () -> Unit,
) {
    val label = place.displayLabel()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Long-press raises the Rename / Share / Delete sheet. onClick is a
            // deliberate no-op (a row has no tap action) but combinedClickable
            // announces the long-press as an accessibility action.
            .combinedClickable(onClick = {}, onLongClick = onLongPress)
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
            onShare = { onShare(label, place.place.point) },
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
    onShare: () -> Unit,
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
                text = { Text(stringResource(R.string.savedPlaces_share)) },
                leadingIcon = {
                    Icon(imageVector = Icons.Filled.Share, contentDescription = null)
                },
                onClick = {
                    expanded = false
                    onShare()
                },
            )
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

/**
 * The bottom sheet a long-press on a saved place raises: Rename (favourites only,
 * matching the row overflow — Home/Work render a localized name and ignore their
 * stored label, so renaming them would edit hidden text), Share (hand the resolved
 * name + coordinate to the friend picker), and Delete (behind the same confirm the
 * row overflow uses). Mirrors the app's other action sheets (PlaceActionsSheet).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedPlaceActionsSheet(
    place: SavedPlace,
    onRename: () -> Unit,
    onShare: (name: String) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val label = place.displayLabel()
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        modifier = Modifier.testTag(SAVED_PLACES_ACTIONS_SHEET_TEST_TAG),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = KccSpacing.s4, vertical = KccSpacing.s2),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s2),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = KccSpacing.s2),
            )
            if (place.kind == SavedPlaceKind.Favourite) {
                SheetAction(
                    icon = Icons.Filled.Edit,
                    text = stringResource(R.string.savedPlaces_rename),
                    onClick = onRename,
                )
            }
            SheetAction(
                icon = Icons.Filled.Share,
                text = stringResource(R.string.savedPlaces_share),
                onClick = { onShare(label) },
            )
            SheetAction(
                icon = Icons.Filled.DeleteOutline,
                text = stringResource(R.string.savedPlaces_delete),
                tint = MaterialTheme.colorScheme.error,
                onClick = onDelete,
            )
        }
    }
}

@Composable
private fun SheetAction(
    icon: ImageVector,
    text: String,
    onClick: () -> Unit,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(imageVector = icon, contentDescription = null, tint = tint)
        Text(
            text = text,
            color = tint,
            textAlign = TextAlign.Start,
            modifier = Modifier.fillMaxWidth().padding(start = KccSpacing.s3),
        )
    }
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
