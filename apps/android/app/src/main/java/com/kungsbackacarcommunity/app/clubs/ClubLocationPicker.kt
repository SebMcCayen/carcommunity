package com.kungsbackacarcommunity.app.clubs

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal sealed interface ClubCatalogLoad {
    data object Loading : ClubCatalogLoad
    data object Failed : ClubCatalogLoad
    data class Ready(val catalog: ClubLocationCatalog) : ClubCatalogLoad
}

@Composable
internal fun rememberClubLocationCatalog(attempt: Int, loader: (suspend () -> ClubLocationCatalog)? = null): ClubCatalogLoad {
    val context = LocalContext.current.applicationContext
    val state by produceState<ClubCatalogLoad>(ClubCatalogLoad.Loading, context, attempt, loader) {
        value = ClubCatalogLoad.Loading
        value = try {
            ClubCatalogLoad.Ready(withContext(Dispatchers.IO) {
                loader?.invoke() ?: context.assets.open(CLUB_LOCATION_ASSET).bufferedReader(Charsets.UTF_8).use {
                    ClubLocationCatalog.parse(it.readText())
                }
            })
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ClubCatalogLoad.Failed
        }
    }
    return state
}

@Composable
internal fun ClubLocationPicker(
    catalog: ClubLocationCatalog,
    selection: ClubLocationSelection,
    busy: Boolean,
    onSelectionChanged: (ClubLocationSelection) -> Unit,
) {
    var chooseCounty by remember { mutableStateOf(false) }
    var chooseLocality by remember(selection.countyCode) { mutableStateOf(false) }
    LaunchedEffect(busy) {
        if (busy) { chooseCounty = false; chooseLocality = false }
    }
    val county = catalog.county(selection.countyCode)
    val locality = catalog.locality(selection.countyCode, selection.localityCode)
    OutlinedButton(
        onClick = { chooseCounty = true }, enabled = !busy,
        modifier = Modifier.fillMaxWidth().testTag("club-county-field"),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
            Text(stringResource(R.string.clubs_county), style = MaterialTheme.typography.labelMedium)
            Text(county?.name ?: stringResource(R.string.clubs_chooseCounty))
        }
    }
    OutlinedButton(
        onClick = { chooseLocality = true }, enabled = !busy && county != null,
        modifier = Modifier.fillMaxWidth().testTag("club-locality-field"),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
            Text(stringResource(R.string.clubs_location), style = MaterialTheme.typography.labelMedium)
            Text(locality?.name ?: stringResource(if (county == null) R.string.clubs_chooseCountyFirst else R.string.clubs_chooseLocality))
            locality?.partOfCode?.let { code ->
                Text(stringResource(R.string.clubs_localityPartOf, catalog.locality(county?.code, code)!!.name),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    if (chooseCounty && !busy) ClubCountyDialog(catalog, onDismiss = { chooseCounty = false }) { code ->
        onSelectionChanged(selection.chooseCounty(catalog, code))
        chooseCounty = false
    }
    if (chooseLocality && county != null && !busy) key(county.code) {
        ClubLocalityDialog(catalog, county, onDismiss = { chooseLocality = false }) { place ->
            onSelectionChanged(selection.chooseLocality(catalog, place.countyCode, place.code))
            chooseLocality = false
        }
    }
}

@Composable
private fun ClubCountyDialog(catalog: ClubLocationCatalog, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.clubs_chooseCounty)) },
        text = {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).testTag("club-county-list")) {
                items(catalog.counties, key = { it.code }) { county ->
                    TextButton(onClick = { onPick(county.code) },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                        modifier = Modifier.fillMaxWidth().testTag("club-county-${county.code}")) {
                        Text(county.name, Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.clubs_cancel)) } },
    )
}

@Composable
private fun ClubLocalityDialog(catalog: ClubLocationCatalog, county: ClubCounty, onDismiss: () -> Unit, onPick: (ClubLocality) -> Unit) {
    var query by remember { mutableStateOf("") }
    val results = remember(catalog, county.code, query) { catalog.localities(county.code, query) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Column { Text(stringResource(R.string.clubs_chooseLocality)); Text(county.name, style = MaterialTheme.typography.bodyMedium) } },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, singleLine = true,
                label = { Text(stringResource(R.string.garage_pickerSearch)) },
                modifier = Modifier.fillMaxWidth().testTag("club-locality-search"))
            if (results.isEmpty()) Text(stringResource(R.string.addressSearch_noResults))
            key(query) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp).testTag("club-locality-list")) {
                    items(results, key = { it.code }) { place ->
                        TextButton(onClick = { onPick(place) },
                            colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
                            modifier = Modifier.fillMaxWidth().testTag("club-locality-${place.code}")) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(place.name)
                                Text(place.municipalities, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                place.partOfCode?.let { code ->
                                    Text(stringResource(R.string.clubs_localityPartOf, catalog.locality(county.code, code)!!.name),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        } },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.clubs_cancel)) } },
    )
}
