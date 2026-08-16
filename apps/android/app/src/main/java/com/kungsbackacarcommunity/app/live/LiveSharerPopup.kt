package com.kungsbackacarcommunity.app.live

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import coil.compose.AsyncImage
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccAlpha
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.friends.FriendPointsFormat
import com.kungsbackacarcommunity.app.friends.FriendPointsRepository
import com.kungsbackacarcommunity.app.media.rememberStorageImageUrl
import com.kungsbackacarcommunity.app.navigation.runCatchingCancellable

/** Test tag on the live-sharer profile sub-menu popup. */
const val LIVE_SHARER_POPUP_TAG = "live_sharer_popup"

/** Test tag on the popup's "visit profile" action. */
const val LIVE_SHARER_POPUP_VISIT_TAG = "live_sharer_popup_visit"

/**
 * The sharer's public Crown Points balance ("XP" in the UI), as the popup knows
 * it: [Loading] until the by-uid read settles, then [Loaded] with the balance
 * (0 when the member has no wallet or the read failed — the repository already
 * folds both into "absent", which this maps to 0). Pure so the tiny state
 * machine is unit-testable without Compose or Firebase.
 */
sealed interface LiveSharerPoints {
    data object Loading : LiveSharerPoints

    data class Loaded(val balance: Long) : LiveSharerPoints

    companion object {
        /**
         * The state for a settled [balancesFor] result: the member's own balance
         * when present, otherwise 0 (no wallet / failed read — never an error, so
         * the popup always resolves to a number rather than hanging on Loading).
         */
        fun fromBalances(uid: String, balances: Map<String, Long>): Loaded =
            Loaded(balances[uid] ?: 0L)
    }
}

/**
 * Pure display helpers for the live-sharer popup, kept off the composable so the
 * nickname fallback and the points label are JVM-unit-testable.
 */
object LiveSharerPopupContent {
    /**
     * The nickname to show, falling back to [unknownLabel] when the marker has no
     * display name — so the header always names a subject rather than a blank.
     */
    fun nickname(marker: LiveMarker, unknownLabel: String): String =
        marker.displayName?.takeIf { it.isNotBlank() } ?: unknownLabel

    /**
     * Whether the "visit profile" action can do anything: only with a non-blank
     * uid and a wired navigation callback. The popup hides the action otherwise,
     * so it never offers a dead button.
     */
    fun canVisitProfile(uid: String, hasNavigation: Boolean): Boolean =
        hasNavigation && uid.isNotBlank()
}

/**
 * The small sub-menu popup opened by TAPPING a live sharer's car-photo chip on
 * the map (the approved 2026-08-11 "tap-live-user profile popup"). Profile-only:
 * it shows the sharer's nickname + Crown Points ("XP", the same headline number
 * their profile shows) and a single "Besök profil" action that opens their
 * member profile. No messaging or other actions — visiting the profile is the
 * one thing offered, and the profile screen owns everything past that.
 *
 * Modelled on [com.kungsbackacarcommunity.app.billboards.BillboardMapPopup] —
 * the same translucent aero [Surface] over the live map, the same close
 * affordance and dismiss-on-outside-tap — so a tap on a live chip feels like a
 * tap on any other map pin.
 *
 * The points balance is read best-effort through [pointsRepository]; a null
 * repository (config-less / CI build) or a member with no wallet simply shows 0,
 * and the read never blocks the popup from opening. Blocked users are already
 * excluded from the nearby roster server-side, so no marker for a blocked user
 * reaches this popup; the member-profile screen additionally withholds a blocked
 * profile, so navigation degrades safely even if a stale marker slips through.
 *
 * @param onVisitProfile navigates to the sharer's member profile; null when the
 *   host has no profile navigation wired, which hides the action entirely.
 */
@Composable
fun LiveSharerPopup(
    sharer: LiveMarker,
    pointsRepository: FriendPointsRepository?,
    onVisitProfile: ((String) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val unknownLabel = stringResource(R.string.nearby_unknownSharer)
    val nickname = LiveSharerPopupContent.nickname(sharer, unknownLabel)
    val canVisit = LiveSharerPopupContent.canVisitProfile(sharer.uid, onVisitProfile != null)

    // Best-effort points read, keyed on the sharer so re-opening a different chip
    // re-reads. A null repository resolves straight to 0 (no wallet visible).
    val points by produceState<LiveSharerPoints>(LiveSharerPoints.Loading, sharer.uid, pointsRepository) {
        val repo = pointsRepository
        value =
            if (repo == null) {
                LiveSharerPoints.Loaded(0L)
            } else {
                // runCatchingCancellable (not plain runCatching) so a cancellation
                // propagates instead of being swallowed as an empty-map "success",
                // which would break structured concurrency for this producer.
                val balances =
                    runCatchingCancellable { repo.balancesFor(listOf(sharer.uid)) }
                        .getOrDefault(emptyMap())
                LiveSharerPoints.fromBalances(sharer.uid, balances)
            }
    }

    Popup(
        alignment = Alignment.BottomCenter,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            modifier =
                Modifier
                    .padding(16.dp)
                    .fillMaxWidth()
                    .widthIn(max = 360.dp)
                    .testTag(LIVE_SHARER_POPUP_TAG),
            shape = RoundedCornerShape(KccRadius.lg),
            color = MaterialTheme.colorScheme.surface.copy(alpha = KccAlpha.aeroSurface),
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SharerAvatar(imagePath = sharer.mainCar?.imagePath)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = nickname,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        val pointsText =
                            when (val p = points) {
                                is LiveSharerPoints.Loading -> DASH
                                is LiveSharerPoints.Loaded -> FriendPointsFormat.grouped(p.balance)
                            }
                        Text(
                            text =
                                stringResource(
                                    R.string.nearby_sharerPoints,
                                    pointsText,
                                    stringResource(R.string.profile_pointsTitle),
                                ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.nearby_sharerPopupClose),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (canVisit && onVisitProfile != null) {
                    TextButton(
                        onClick = {
                            onVisitProfile(sharer.uid)
                            onDismiss()
                        },
                        modifier = Modifier.testTag(LIVE_SHARER_POPUP_VISIT_TAG),
                    ) {
                        Text(text = stringResource(R.string.nearby_visitProfile))
                    }
                }
            }
        }
    }
}

/** The em dash shown for the points figure while the balance read is in flight. */
private const val DASH = "—"

/**
 * The sharer's main-car photo (the same image the chip shows) in a round frame,
 * with a person glyph while the URL resolves or when there is no photo.
 */
@Composable
private fun SharerAvatar(imagePath: String?) {
    val url = rememberStorageImageUrl(LocalContext.current, imagePath)
    Box(
        modifier =
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(44.dp).clip(CircleShape),
            )
        } else {
            Icon(
                imageVector = Icons.Filled.Person,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp),
            )
        }
    }
}
