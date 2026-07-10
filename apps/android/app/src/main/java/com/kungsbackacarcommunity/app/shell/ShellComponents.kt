package com.kungsbackacarcommunity.app.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R

/** One entry in a tab hub or the More menu; a null [onClick] hides it. */
data class HubEntry(
    val label: String,
    val onClick: (() -> Unit)?,
    val testTag: String? = null,
)

/** The five-tab bottom navigation. Map is the map-first home and default tab. */
@Composable
fun ShellBottomNav(
    selected: ShellTab,
    onSelect: (ShellTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationBar(modifier = modifier) {
        ShellTabItem(selected, ShellTab.Map, Icons.Filled.Map, R.string.shell_tabMap, TAG_TAB_MAP, onSelect)
        ShellTabItem(selected, ShellTab.History, Icons.Filled.History, R.string.shell_tabHistory, TAG_TAB_HISTORY, onSelect)
        ShellTabItem(selected, ShellTab.Create, Icons.Filled.Add, R.string.shell_tabCreate, TAG_TAB_CREATE, onSelect)
        ShellTabItem(selected, ShellTab.Social, Icons.Filled.Groups, R.string.shell_tabSocial, TAG_TAB_SOCIAL, onSelect)
        ShellTabItem(selected, ShellTab.Garage, Icons.Filled.DirectionsCar, R.string.shell_tabGarage, TAG_TAB_GARAGE, onSelect)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ShellTabItem(
    selected: ShellTab,
    tab: ShellTab,
    icon: ImageVector,
    labelRes: Int,
    testTag: String,
    onSelect: (ShellTab) -> Unit,
) {
    val label = stringResource(labelRes)
    NavigationBarItem(
        selected = selected == tab,
        onClick = { onSelect(tab) },
        icon = { Icon(imageVector = icon, contentDescription = label) },
        label = { Text(label) },
        modifier = Modifier.testTag(testTag),
    )
}

/** Top bar for the hub tabs (History/Create/Social/Garage) with a More action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShellTopBar(title: String, onOpenMore: () -> Unit, modifier: Modifier = Modifier) {
    TopAppBar(
        title = { Text(title) },
        actions = {
            IconButton(onClick = onOpenMore, modifier = Modifier.testTag(TAG_MENU)) {
                Icon(
                    imageVector = Icons.Filled.AccountCircle,
                    contentDescription = stringResource(R.string.shell_moreTitle),
                )
            }
        },
        modifier = modifier,
    )
}

/**
 * Scrollable list of hub entries (a tab hub body). Entries whose [HubEntry.onClick]
 * is null are omitted; when none remain, an "unavailable" line is shown so the
 * tab is never blank (e.g. a config-less build with no Firebase).
 */
@Composable
fun ShellHubContent(entries: List<HubEntry>, modifier: Modifier = Modifier) {
    val available = entries.filter { it.onClick != null }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (available.isEmpty()) {
                Text(
                    text = stringResource(R.string.shell_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                available.forEach { entry ->
                    Button(
                        onClick = entry.onClick!!,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .let { m -> entry.testTag?.let { m.testTag(it) } ?: m },
                    ) {
                        Text(entry.label)
                    }
                }
            }
        }
    }
}

/**
 * Full-screen "More" hub (opened from the top-bar avatar): profile, settings,
 * moderation, partner and account actions, and Sign out. Its own back
 * affordance returns to the current tab.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoreScreen(
    entries: List<HubEntry>,
    onSignOut: (() -> Unit)?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(stringResource(R.string.shell_moreTitle)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.profile_back),
                        )
                    }
                },
            )
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                entries.filter { it.onClick != null }.forEach { entry ->
                    OutlinedButton(
                        onClick = entry.onClick!!,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .let { m -> entry.testTag?.let { m.testTag(it) } ?: m },
                    ) {
                        Text(entry.label)
                    }
                }
                if (onSignOut != null) {
                    Button(
                        onClick = onSignOut,
                        modifier = Modifier.fillMaxWidth().testTag(TAG_SIGN_OUT),
                    ) {
                        Text(stringResource(R.string.shell_moreSignOut))
                    }
                }
            }
        }
    }
}

const val TAG_TAB_MAP = "shell_tab_map"
const val TAG_TAB_HISTORY = "shell_tab_history"
const val TAG_TAB_CREATE = "shell_tab_create"
const val TAG_TAB_SOCIAL = "shell_tab_social"
const val TAG_TAB_GARAGE = "shell_tab_garage"
const val TAG_MENU = "shell_menu"
const val TAG_SIGN_OUT = "shell_sign_out"
