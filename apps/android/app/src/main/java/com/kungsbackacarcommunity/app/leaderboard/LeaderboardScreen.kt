package com.kungsbackacarcommunity.app.leaderboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccPalette
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.media.rememberStorageImageUrl
import com.kungsbackacarcommunity.app.shell.AeroPage

/** testTag on the leaderboard scope toggle, for UI tests. */
const val LEADERBOARD_SCOPE_TABS_TAG = "leaderboardScopeTabs"

/**
 * Social leaderboard screen. Stateless.
 *
 * A read-only view of the precomputed board: a three-scope selector at the
 * top, then, per competitive category (in the server's render order), a compact
 * ranking table. Everything shown — ranks, names,
 * avatars, ordering — is resolved server-side; this screen only formats each raw
 * value for its category (KP/CP, km, counts, days) and lays it out.
 *
 * The `streak` category exists only on the all-time board (a daily-collection
 * streak spans months), which [LeaderboardBoard.categoriesFor] already enforces —
 * so each monthly view carries five categories, not the all-time board's six.
 *
 * Back is handled centrally by the shell (the pinned AeroPage arrow +
 * system-Back dispatcher), so this screen takes no back callback of its own.
 *
 * @param state Loading (skeleton), Error (a soft notice) or Loaded (the boards).
 */
@Composable
fun LeaderboardScreen(
    scope: LeaderboardScope,
    onScopeChange: (LeaderboardScope) -> Unit,
    state: LeaderboardUiState,
) {
    var selectedCategory by rememberSaveable { mutableStateOf(LeaderboardCategory.CROWN_POINTS) }
    AeroPage(title = stringResource(R.string.leaderboard_title)) {
        val boards = (state as? LeaderboardUiState.Loaded)?.categories.orEmpty()
        val activeBoard = boards.firstOrNull { it.category == selectedCategory } ?: boards.firstOrNull()
        if (activeBoard != null) {
            ScrollableTabRow(selectedTabIndex = boards.indexOf(activeBoard), edgePadding = 0.dp,
                containerColor = MaterialTheme.colorScheme.surfaceVariant, indicator = {}, divider = {}) {
                boards.forEach { board ->
                    val selected = board == activeBoard
                    Tab(selected = selected, onClick = { selectedCategory = board.category },
                        modifier = Modifier.testTag("leaderboardCategory_${board.category.name}").background(if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            MaterialTheme.shapes.medium),
                        selectedContentColor = MaterialTheme.colorScheme.onPrimary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = { Text(stringResource(categoryTitleRes(board.category))) })
                }
            }
        }
        ScopeToggle(scope = scope, onScopeChange = onScopeChange)

        when (state) {
            LeaderboardUiState.Loading -> LeaderboardSkeleton()
            LeaderboardUiState.Error ->
                InfoNoticeCard(stringResource(R.string.leaderboard_error))
            is LeaderboardUiState.Loaded -> activeBoard?.let { CategorySection(it) }
        }
    }
}

/** The scope switch, rendered in [LeaderboardScope] declaration order. */
@Composable
private fun ScopeToggle(
    scope: LeaderboardScope,
    onScopeChange: (LeaderboardScope) -> Unit,
) {
    val scopes = LeaderboardScope.entries
    val selectedIndex = scopes.indexOf(scope)
    TabRow(
        selectedTabIndex = selectedIndex,
        modifier = Modifier.fillMaxWidth().testTag(LEADERBOARD_SCOPE_TABS_TAG),
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        indicator = {},
        divider = {},
    ) {
        scopes.forEachIndexed { index, tabScope ->
            Tab(
                selected = selectedIndex == index,
                modifier = Modifier.background(
                    if (selectedIndex == index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                    MaterialTheme.shapes.medium,
                ),
                selectedContentColor = MaterialTheme.colorScheme.onPrimary,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = { onScopeChange(tabScope) },
                text = { Text(stringResource(scopeTitleRes(tabScope))) },
            )
        }
    }
}

/** The localized tab label for [scope]. */
private fun scopeTitleRes(scope: LeaderboardScope): Int =
    when (scope) {
        LeaderboardScope.THIS_MONTH -> R.string.leaderboard_scopeThisMonth
        LeaderboardScope.LAST_MONTH -> R.string.leaderboard_scopeLastMonth
        LeaderboardScope.ALL_TIME -> R.string.leaderboard_scopeAllTime
    }

/** One category: a header and ranking table, or the existing empty state. */
@Composable
private fun CategorySection(board: LeaderboardCategoryBoard) {
    Column(verticalArrangement = Arrangement.spacedBy(KccSpacing.s3)) {
        Text(
            text = stringResource(categoryTitleRes(board.category)),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (board.entries.isEmpty()) {
            InfoNoticeCard(stringResource(R.string.leaderboard_categoryEmpty))
        } else {
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Column {
                    board.entries.forEachIndexed { index, entry ->
                        LeaderboardListRow(entry = entry, format = board.category.format)
                        if (index < board.entries.lastIndex) HorizontalDivider()
                    }
                }
            }
        }
    }
}

/** A ranked line, including medal ranks and a separate current-viewer outline. */
@Composable
private fun LeaderboardListRow(
    entry: LeaderboardEntry,
    format: LeaderboardValueFormat,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        border = if (entry.isViewer) BorderStroke(1.dp, MaterialTheme.colorScheme.tertiary) else null,
        modifier = Modifier.testTag("leaderboard_row_${entry.rank}"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = KccSpacing.s4, vertical = KccSpacing.s3),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
        ) {
            if (entry.rank in 1..3) {
                RankBadge(rank = entry.rank, color = medalColor(entry.rank))
            } else {
                Text(
                    text = stringResource(R.string.leaderboard_rank, entry.rank),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(KccSpacing.s6),
                )
            }
            MemberAvatar(avatarPath = entry.avatarPath, size = KccSpacing.s10,
                ringColor = if (entry.isViewer) MaterialTheme.colorScheme.tertiary else null)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.displayName,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (entry.isViewer) FontWeight.Bold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (entry.isViewer) {
                    Text(
                        text = stringResource(R.string.leaderboard_you),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                text = formattedValue(format, entry.value),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** A round rank chip in the medal colour (or the neutral surface for the list). */
@Composable
private fun RankBadge(rank: Int, color: Color) {
    Box(
        modifier = Modifier.size(KccSpacing.s6).clip(CircleShape).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = rank.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = KccPalette.inkBlack,
        )
    }
}

/** A circular member avatar; [ringColor] marks the current viewer. */
@Composable
private fun MemberAvatar(avatarPath: String?, size: Dp, ringColor: Color?) {
    val context = LocalContext.current
    val url = rememberStorageImageUrl(context, avatarPath)
    // Outer disc is the medal ring (or the plain avatar background when null); the
    // inner, padded disc holds the image so the ring shows as a coloured rim.
    Box(
        modifier =
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(ringColor ?: MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(if (ringColor != null) 2.dp else 0.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            if (url != null) {
                AsyncImage(
                    model = url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(size / 2),
                )
            }
        }
    }
}

/**
 * A calm loading skeleton: a couple of category placeholders (a header bar, a
 * podium row and a few list lines) drawn as neutral rounded blocks.
 */
@Composable
private fun LeaderboardSkeleton() {
    Column(verticalArrangement = Arrangement.spacedBy(KccSpacing.s4)) {
        repeat(2) {
            Column(verticalArrangement = Arrangement.spacedBy(KccSpacing.s3)) {
                SkeletonBlock(width = KccSpacing.s12, height = KccSpacing.s5)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
                ) {
                    repeat(3) {
                        SkeletonBlock(modifier = Modifier.weight(1f), height = KccSpacing.s12)
                    }
                }
                repeat(3) {
                    SkeletonBlock(modifier = Modifier.fillMaxWidth(), height = KccSpacing.s8)
                }
            }
        }
    }
}

@Composable
private fun SkeletonBlock(
    modifier: Modifier = Modifier,
    width: Dp? = null,
    height: Dp,
) {
    Box(
        modifier =
            (if (width != null) modifier.width(width) else modifier)
                .height(height)
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant),
    )
}

/**
 * A soft, neutral notice for the load-error and empty states: an info icon plus
 * muted text, styled to sit calmly within the Aero theme rather than shouting in
 * the error colour.
 */
@Composable
private fun InfoNoticeCard(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(KccSpacing.s4),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
        ) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(KccSpacing.s6),
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The localized category header for [category]. */
private fun categoryTitleRes(category: LeaderboardCategory): Int =
    when (category) {
        LeaderboardCategory.CROWN_POINTS -> R.string.leaderboard_categoryCrownPoints
        LeaderboardCategory.DISTANCE -> R.string.leaderboard_categoryDistance
        LeaderboardCategory.EVENTS -> R.string.leaderboard_categoryEvents
        LeaderboardCategory.CONVOYS -> R.string.leaderboard_categoryConvoys
        LeaderboardCategory.WAVES -> R.string.leaderboard_categoryWaves
        LeaderboardCategory.STREAK -> R.string.leaderboard_categoryStreak
    }

/** Formats a raw value for [format] via the pure transform + the localized unit template. */
@Composable
private fun formattedValue(format: LeaderboardValueFormat, value: Double): String {
    val magnitude = LeaderboardBoard.displayValue(format, value)
    val template =
        when (format) {
            LeaderboardValueFormat.CROWN_POINTS -> R.string.leaderboard_valueCrownPoints
            LeaderboardValueFormat.DISTANCE_KM -> R.string.leaderboard_valueDistance
            LeaderboardValueFormat.COUNT -> R.string.leaderboard_valueCount
            LeaderboardValueFormat.WAVES -> R.string.leaderboard_valueWaves
            LeaderboardValueFormat.DAYS -> R.string.leaderboard_valueDays
        }
    return stringResource(template, magnitude)
}

/** Gold / silver / bronze for ranks 1–3; the brand primary as a fallback. */
@Composable
private fun medalColor(rank: Int): Color =
    when (rank) {
        1 -> KccPalette.crownGold
        2 -> KccPalette.silverGrey
        // Bronze has no design token; the medal set is a semantic trio, so the
        // third colour is defined locally rather than inventing a palette entry.
        3 -> Color(0xFFCD7F32)
        else -> MaterialTheme.colorScheme.primary
    }
