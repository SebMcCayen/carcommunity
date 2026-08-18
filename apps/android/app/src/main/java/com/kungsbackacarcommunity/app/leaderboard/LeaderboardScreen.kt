package com.kungsbackacarcommunity.app.leaderboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.media.rememberStorageImageUrl
import com.kungsbackacarcommunity.app.shell.AeroPage

/** testTag on the All-time / This-month scope toggle, for UI tests. */
const val LEADERBOARD_SCOPE_TABS_TAG = "leaderboardScopeTabs"

/**
 * Social leaderboard screen. Stateless.
 *
 * A read-only view of the precomputed board: an All-time / This-month toggle at the
 * top, then, per competitive category (in the server's render order), a podium of
 * the top three and a list down to rank ten. Everything shown — ranks, names,
 * avatars, ordering — is resolved server-side; this screen only formats each raw
 * value for its category (KP/CP, km, counts, days) and lays it out.
 *
 * The `streak` category exists only on the all-time board (a daily-collection
 * streak spans months), which [LeaderboardBoard.categoriesFor] already enforces —
 * so the monthly view simply carries four categories, not five.
 *
 * @param state Loading (skeleton), Error (a soft notice) or Loaded (the boards).
 */
@Composable
fun LeaderboardScreen(
    scope: LeaderboardScope,
    onScopeChange: (LeaderboardScope) -> Unit,
    state: LeaderboardUiState,
    onBack: () -> Unit,
) {
    AeroPage(title = stringResource(R.string.leaderboard_title)) {
        ScopeToggle(scope = scope, onScopeChange = onScopeChange)

        when (state) {
            LeaderboardUiState.Loading -> LeaderboardSkeleton()
            LeaderboardUiState.Error ->
                InfoNoticeCard(stringResource(R.string.leaderboard_error))
            is LeaderboardUiState.Loaded ->
                state.categories.forEach { board ->
                    CategorySection(board)
                }
        }
    }
}

/** The two-option scope switch, rendered as a two-tab [TabRow]. */
@Composable
private fun ScopeToggle(
    scope: LeaderboardScope,
    onScopeChange: (LeaderboardScope) -> Unit,
) {
    val selectedIndex = if (scope == LeaderboardScope.ALL_TIME) 0 else 1
    TabRow(
        selectedTabIndex = selectedIndex,
        modifier = Modifier.fillMaxWidth().testTag(LEADERBOARD_SCOPE_TABS_TAG),
    ) {
        Tab(
            selected = selectedIndex == 0,
            onClick = { onScopeChange(LeaderboardScope.ALL_TIME) },
            text = { Text(stringResource(R.string.leaderboard_scopeAllTime)) },
        )
        Tab(
            selected = selectedIndex == 1,
            onClick = { onScopeChange(LeaderboardScope.THIS_MONTH) },
            text = { Text(stringResource(R.string.leaderboard_scopeThisMonth)) },
        )
    }
}

/** One category: a header, then the podium + list, or a friendly empty state. */
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
            val split = LeaderboardBoard.podiumSplit(board.entries)
            Podium(top = split.top, format = board.category.format)
            if (split.rest.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        split.rest.forEach { entry ->
                            LeaderboardListRow(entry = entry, format = board.category.format)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The top-three podium. Rendered in rank order (1, 2, 3) with distinct medal
 * colours; the first-place tile is emphasised with a larger avatar. Fewer than
 * three entries simply yield a shorter row.
 */
@Composable
private fun Podium(
    top: List<LeaderboardEntry>,
    format: LeaderboardValueFormat,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
        verticalAlignment = Alignment.Bottom,
    ) {
        top.forEach { entry ->
            PodiumTile(
                entry = entry,
                format = format,
                modifier = Modifier.weight(1f),
            )
        }
        // Pad a short podium so a single or double winner does not stretch full-width.
        repeat(LeaderboardBoard.PODIUM_SIZE - top.size) {
            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun PodiumTile(
    entry: LeaderboardEntry,
    format: LeaderboardValueFormat,
    modifier: Modifier = Modifier,
) {
    val medal = medalColor(entry.rank)
    val avatarSize = if (entry.rank == 1) KccSpacing.s12 else KccSpacing.s10
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = KccSpacing.s3, horizontal = KccSpacing.s2),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(KccSpacing.s1),
        ) {
            RankBadge(rank = entry.rank, color = medal)
            MemberAvatar(avatarPath = entry.avatarPath, size = avatarSize, ringColor = medal)
            Text(
                text = entry.displayName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (entry.isViewer) FontWeight.Bold else FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            if (entry.isViewer) {
                Text(
                    text = stringResource(R.string.leaderboard_you),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                text = formattedValue(format, entry.value),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A single ranked line (rank 4 downwards). */
@Composable
private fun LeaderboardListRow(
    entry: LeaderboardEntry,
    format: LeaderboardValueFormat,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = KccSpacing.s4, vertical = KccSpacing.s3),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
    ) {
        Text(
            text = stringResource(R.string.leaderboard_rank, entry.rank),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(KccSpacing.s8),
        )
        MemberAvatar(avatarPath = entry.avatarPath, size = KccSpacing.s8, ringColor = null)
        Text(
            text = entry.displayName,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (entry.isViewer) FontWeight.Bold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (entry.isViewer) {
            Text(
                text = stringResource(R.string.leaderboard_you),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = formattedValue(format, entry.value),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
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
            color = MaterialTheme.colorScheme.inverseOnSurface,
        )
    }
}

/** A circular member avatar; [ringColor] draws a medal ring on the podium. */
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
            LeaderboardValueFormat.DAYS -> R.string.leaderboard_valueDays
        }
    return stringResource(template, magnitude)
}

/** Gold / silver / bronze for ranks 1–3; the brand gold as a fallback. */
@Composable
private fun medalColor(rank: Int): Color =
    when (rank) {
        1 -> Color(0xFFEAB54B) // crownGold
        2 -> Color(0xFFB4B1AD) // silverGrey
        3 -> Color(0xFFCD7F32) // bronze
        else -> MaterialTheme.colorScheme.primary
    }
