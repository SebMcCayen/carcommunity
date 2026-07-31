package com.kungsbackacarcommunity.app.garage

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing

/**
 * The manufacturer / model / year selectors for the add-edit vehicle form.
 *
 * Make, model and year are SELECTIONS, never typed (2026-07): the community can
 * only count cars per manufacturer if every vehicle names the same `volvo`, so
 * the form offers a catalogue and stores its ids.
 *
 * WHY A SHEET AND NOT A DROPDOWN MENU
 * ----------------------------------
 * There are ~100 manufacturers and up to ~40 models each. A plain
 * `DropdownMenu` of 100 rows is unusable — it opens over the field, scrolls in a
 * cramped popup and cannot be searched. Each selector therefore opens a
 * [ModalBottomSheet] with a search box and a full-height list.
 *
 * The search box filters the list; it is NOT an input for the stored value. Its
 * text is thrown away when the sheet closes, and nothing but a tapped row can
 * ever set a selection, so "no free typing of make/model" holds exactly.
 */

/**
 * A read-only, tappable field showing the current selection.
 *
 * Implemented as an `OutlinedTextField` under a transparent tap overlay so it
 * matches the real text fields around it (engine, modifications, plate) instead
 * of inventing a second look for "a field you tap". Accessibility is put on the
 * overlay — the text field's own semantics are cleared, or TalkBack would
 * announce an editable text box that cannot be edited.
 */
@Composable
fun CatalogueSelectorField(
    label: String,
    /** The selected value's display text, or null when nothing is selected yet. */
    value: String?,
    /** Shown in place of a value when nothing is selected ("Select manufacturer"). */
    placeholder: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
) {
    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value ?: placeholder,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(text = label) },
            supportingText = supportingText?.let { { Text(text = it) } },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().clearAndSetSemantics {},
        )
        // The overlay carries the whole control's accessibility: one focusable
        // button announcing "<label>, <value>". It is sized to the field (56dp
        // tall), comfortably above the 48dp touch-target minimum.
        Box(
            modifier =
                Modifier
                    .matchParentSize()
                    .semantics {
                        role = Role.Button
                        contentDescription = "$label, ${value ?: placeholder}"
                    }
                    .clickable(enabled = enabled, onClick = onClick),
        )
    }
}

/** Picks a manufacturer. Common Swedish brands are surfaced above the rest. */
@Composable
fun MakePickerSheet(onPick: (CatalogueOption) -> Unit, onDismiss: () -> Unit) {
    val options = remember { VehicleCatalogue.makeOptions() }
    val otherLabel = stringResource(R.string.garage_catalogueOther)
    CatalogueOptionSheet(
        title = stringResource(R.string.garage_make),
        options = options,
        otherLabel = otherLabel,
        // Only the manufacturer list is grouped: "common in Sweden" first makes
        // the opening screenful useful without typing anything.
        sectioned = true,
        onPick = onPick,
        onDismiss = onDismiss,
    )
}

/**
 * Picks a model WITHIN [makeId] — the cascade. Never call this before a
 * manufacturer is chosen; model ids repeat across brands, so a model list that
 * ignored the manufacturer would allow impossible pairs.
 */
@Composable
fun ModelPickerSheet(makeId: String?, onPick: (CatalogueOption) -> Unit, onDismiss: () -> Unit) {
    val options = remember(makeId) { VehicleCatalogue.modelOptions(makeId) }
    CatalogueOptionSheet(
        title = stringResource(R.string.garage_model),
        options = options,
        otherLabel = stringResource(R.string.garage_catalogueOther),
        sectioned = false,
        onPick = onPick,
        onDismiss = onDismiss,
    )
}

/** Picks a model year, newest first (the common case is a recent car). */
@Composable
fun ModelYearPickerSheet(currentYear: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    val years = remember(currentYear) { VehicleCatalogue.modelYears(currentYear) }
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(years, query) {
        val needle = query.trim()
        if (needle.isEmpty()) years else years.filter { it.toString().contains(needle) }
    }
    PickerSheet(
        title = stringResource(R.string.garage_modelYear),
        query = query,
        onQueryChange = { query = it.filter(Char::isDigit) },
        numericQuery = true,
        onDismiss = onDismiss,
    ) {
        if (filtered.isEmpty()) {
            item { PickerEmptyState() }
        }
        items(filtered, key = { it }) { year ->
            PickerRow(label = year.toString(), onClick = { onPick(year) })
        }
    }
}

@Composable
private fun CatalogueOptionSheet(
    title: String,
    options: List<CatalogueOption>,
    otherLabel: String,
    sectioned: Boolean,
    onPick: (CatalogueOption) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(options, query) { VehicleCatalogue.filter(options, query) }
    // "Other / not listed" is pinned to the bottom and survives every filter, so
    // the escape hatch is reachable at the exact moment it is needed most: when a
    // search for the member's brand returned nothing.
    val (pinned, listed) = filtered.partition { it.isOther }
    val searching = query.isNotBlank()

    PickerSheet(
        title = title,
        query = query,
        onQueryChange = { query = it },
        numericQuery = false,
        onDismiss = onDismiss,
    ) {
        if (listed.isEmpty()) {
            item { PickerEmptyState() }
        }
        if (sectioned && !searching) {
            val common = listed.filter { VehicleCatalogue.make(it.id)?.common == true }
            val rest = listed - common.toSet()
            if (common.isNotEmpty()) {
                item { PickerSectionHeader(stringResource(R.string.garage_commonMakes)) }
                items(common, key = { "common-${it.id}" }) { option ->
                    PickerRow(label = option.name, onClick = { onPick(option) })
                }
            }
            if (rest.isNotEmpty()) {
                item { PickerSectionHeader(stringResource(R.string.garage_allMakes)) }
                items(rest, key = { "all-${it.id}" }) { option ->
                    PickerRow(label = option.name, onClick = { onPick(option) })
                }
            }
        } else {
            items(listed, key = { it.id }) { option ->
                PickerRow(label = option.name, onClick = { onPick(option) })
            }
        }
        items(pinned, key = { "other-${it.id}" }) { option ->
            HorizontalDivider()
            PickerRow(label = otherLabel, onClick = { onPick(option) })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerSheet(
    title: String,
    query: String,
    onQueryChange: (String) -> Unit,
    numericQuery: Boolean,
    onDismiss: () -> Unit,
    body: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = KccSpacing.s4),
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s3),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                label = { Text(text = stringResource(R.string.garage_pickerSearch)) },
                singleLine = true,
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = if (numericQuery) KeyboardType.Number else KeyboardType.Text,
                    ),
                modifier = Modifier.fillMaxWidth(),
            )
            LazyColumn(
                // Bounded so the sheet keeps its search box on screen with the
                // keyboard up, instead of the list pushing it away.
                modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
                content = body,
            )
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.garage_pickerClose))
            }
        }
    }
}

@Composable
private fun PickerSectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = KccSpacing.s2, bottom = KccSpacing.s1),
    )
}

@Composable
private fun PickerRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodyLarge,
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                // 48dp minimum touch target, and the vertical padding keeps the
                // text centred inside it.
                .defaultMinSize(minHeight = 48.dp)
                .background(MaterialTheme.colorScheme.surface)
                .padding(vertical = KccSpacing.s3),
    )
}

@Composable
private fun PickerEmptyState() {
    Text(
        text = stringResource(R.string.garage_pickerNoMatches),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(vertical = KccSpacing.s4),
    )
}
