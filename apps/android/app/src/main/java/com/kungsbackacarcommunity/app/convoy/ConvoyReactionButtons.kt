package com.kungsbackacarcommunity.app.convoy

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalPolice
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.WavingHand
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccPalette
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.design.LocalKccMapColors
import com.kungsbackacarcommunity.app.design.ReactionOverlay
import com.kungsbackacarcommunity.app.design.ReactionOverlayEvent
import java.util.UUID
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The full-width action row and its gap above the session dock. */
val CONVOY_REACTION_CONTROLS_CLEARANCE = 56.dp + KccSpacing.s3

/** Test tag on the convoy reaction button cluster. */
const val CONVOY_REACTIONS_TAG = "convoy_reactions"

/** Per-button test tag, e.g. `convoy_reaction_police`. */
fun convoyReactionButtonTag(kind: ConvoyReactionKind): String = "convoy_reaction_${kind.wire}"

/**
 * The full convoy-reactions layer for the map: the mid-screen [ReactionOverlay]
 * (centre) plus the [ConvoyReactionButtons] cluster (bottom-centre, above the
 * shell's bottom bar). Composed by the map home ONLY while the caller is in an
 * active convoy, so it is absent in the default shell (and its instrumented test).
 *
 * Receiving: a live listener on the convoy channel ([ConvoyReactionRepository.observeReactions],
 * from the moment this attaches) drives the centre overlay — including the
 * sender's OWN reaction, which is its confirmation. Sending: a tap checks the
 * client cooldown mirror, optimistically starts it, and calls the callable; a
 * server refusal (resource-exhausted) overrides the greying with the server's
 * exact remaining time (the server is the anti-spam source of truth). The root
 * Box is transparent and non-blocking — only the buttons capture touches, so the
 * map stays fully interactive.
 *
 * The POLICE reaction is more than a transient pop: on a successful broadcast it
 * ALSO drops the standard, persistent police pin (via [onPoliceReaction], wired to
 * the same `police.report` the standalone report uses) so a convoy police signal
 * leaves ONE real, map-drawn police marker every nearby driver sees — not a
 * separate throwaway icon. The mid-screen broadcast to convoy members is kept.
 *
 * @param onPoliceReaction invoked (suspending) once a POLICE reaction has been
 *   broadcast (`Sent`), to drop the persistent police pin. Null in a config-less
 *   build (or when no police layer is available), which simply keeps the old
 *   broadcast-only behavior.
 * @param followMeLeading whether the local user currently owns the shared
 *   follow-me leader TRAIL — drives the FollowMe button's ACTIVE/toggled state.
 * @param onFollowMeToggle invoked with the DESIRED new active state when the
 *   FollowMe button is tapped (true = start/take over the trail, false = turn my
 *   own trail off). Null keeps the pure broadcast behaviour (no persistent trail).
 *   When non-null, tapping FollowMe toggles the trail AND — only when ACTIVATING —
 *   still fires the transient "Follow me" animation; turning the trail off fires
 *   no animation. Only FollowMe is disabled while this callback is outstanding;
 *   selection always reflects [followMeLeading], including on failure.
 */
@Composable
fun ConvoyReactionsHost(
    convoyId: String,
    repository: ConvoyReactionRepository,
    modifier: Modifier = Modifier,
    onPoliceReaction: (suspend () -> Unit)? = null,
    followMeLeading: Boolean = false,
    onFollowMeToggle: (suspend (Boolean) -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var cooldown by remember(convoyId) { mutableStateOf(ConvoyReactionCooldownState()) }
    var followMeTogglePending by remember(convoyId) { mutableStateOf(false) }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    // A slow tick just to keep the cooldown countdown live; 500ms is plenty for a
    // per-second display and costs nothing.
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(500)
        }
    }

    // Reactions strictly AFTER we attach, so old (still-in-TTL) reactions never
    // replay as fresh pops when the convoy screen opens.
    val since = remember(convoyId) { System.currentTimeMillis() }
    var incoming by remember(convoyId) { mutableStateOf<ConvoyReactionEvent?>(null) }
    LaunchedEffect(convoyId, repository) {
        repository.observeReactions(convoyId, since).collect { incoming = it }
    }

    val overlayEvent: ReactionOverlayEvent? =
        incoming?.let { reaction ->
            val caption = reactionOverlayCaption(reaction.kind, reaction.senderName)
            ReactionOverlayEvent(
                id = reaction.id,
                icon = reactionIcon(reaction.kind),
                caption = caption,
                tint = reactionColor(reaction.kind),
                // TalkBack announcement text; police interrupts (safety-relevant).
                contentDescription = caption,
                assertive = reaction.kind == ConvoyReactionKind.Police,
            )
        }

    Box(modifier = modifier.fillMaxSize()) {
        ReactionOverlay(
            event = overlayEvent,
            onFinished = { incoming = null },
            modifier = Modifier.align(Alignment.Center),
        )
        ConvoyReactionButtons(
            cooldown = cooldown,
            nowMs = nowMs,
            followMeLeading = followMeLeading,
            followMeToggleEnabled = onFollowMeToggle != null,
            followMeTogglePending = followMeTogglePending,
            onReact = { kind ->
                val now = System.currentTimeMillis()
                // FOLLOW-ME is a TOGGLE of the persistent leader trail when a
                // handler is wired: tapping flips the trail on/off regardless of
                // the reaction cooldown, and fires the transient animation only
                // when ACTIVATING (turning the trail OFF announces nothing).
                if (kind == ConvoyReactionKind.FollowMe && onFollowMeToggle != null) {
                    if (followMeTogglePending) return@ConvoyReactionButtons
                    val activate = !followMeLeading
                    followMeTogglePending = true
                    scope.launch {
                        try {
                            onFollowMeToggle(activate)
                        } catch (cancellation: CancellationException) {
                            throw cancellation
                        } catch (_: Exception) {
                            // Keep the observed leadership state on failure; the
                            // Unit callback cannot confirm a successful toggle.
                        } finally {
                            followMeTogglePending = false
                        }
                    }
                    if (activate && cooldown.isReady(kind, now)) {
                        cooldown = cooldown.recordSent(kind, now)
                        val clientId = UUID.randomUUID().toString().replace("-", "").take(32)
                        scope.launch {
                            when (val result = repository.send(convoyId, kind, clientId)) {
                                is ConvoyReactionSendResult.RateLimited ->
                                    cooldown =
                                        cooldown.applyServerCooldown(
                                            kind,
                                            result.retryAfterMs,
                                            System.currentTimeMillis(),
                                        )
                                ConvoyReactionSendResult.Failed -> cooldown = cooldown.clear(kind)
                                ConvoyReactionSendResult.Sent -> Unit
                            }
                        }
                    }
                    return@ConvoyReactionButtons
                }
                if (!cooldown.isReady(kind, now)) return@ConvoyReactionButtons
                // Optimistically start the local window so a double-tap is stopped
                // before the round-trip; the server still enforces the real limit.
                cooldown = cooldown.recordSent(kind, now)
                val clientId = UUID.randomUUID().toString().replace("-", "").take(32)
                scope.launch {
                    when (val result = repository.send(convoyId, kind, clientId)) {
                        is ConvoyReactionSendResult.RateLimited ->
                            cooldown =
                                cooldown.applyServerCooldown(
                                    kind,
                                    result.retryAfterMs,
                                    System.currentTimeMillis(),
                                )
                        // A send that never reached the server (offline/transient):
                        // clear the optimistic window so the member can retry.
                        ConvoyReactionSendResult.Failed -> cooldown = cooldown.clear(kind)
                        // A POLICE signal that actually went out ALSO drops the
                        // standard persistent police pin (the same one police.report
                        // creates), so it is a real, verifiable marker on every
                        // nearby driver's map — not just a fleeting pop. Only on a
                        // real send, so a cooldown-blocked tap never drops a pin.
                        ConvoyReactionSendResult.Sent ->
                            if (kind == ConvoyReactionKind.Police) onPoliceReaction?.invoke()
                    }
                }
            },
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = KccSpacing.s3),
        )
    }
}

/**
 * Three equal, icon-only rectangular map controls. The coloured lower edge
 * drains during cooldown; the remaining seconds are available to TalkBack.
 * Persistent FollowMe toggles bypass that cooldown in either state, but wait for any pending toggle.
 * Pure UI — the tap decision and the send live in [ConvoyReactionsHost].
 */
@Composable
fun ConvoyReactionButtons(
    cooldown: ConvoyReactionCooldownState,
    nowMs: Long,
    onReact: (ConvoyReactionKind) -> Unit,
    modifier: Modifier = Modifier,
    followMeLeading: Boolean = false,
    followMeToggleEnabled: Boolean = false,
    followMeTogglePending: Boolean = false,
) {
    Row(
        modifier = modifier.widthIn(max = 480.dp).fillMaxWidth().padding(horizontal = KccSpacing.s3).testTag(CONVOY_REACTIONS_TAG),
        horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (kind in ConvoyReactionKind.entries) {
            // Both starting/taking over and stopping a persistent trail bypass
            // the transient reaction cooldown. Broadcast-only FollowMe does not.
            val persistentToggle = kind == ConvoyReactionKind.FollowMe && followMeToggleEnabled
            val active = kind == ConvoyReactionKind.FollowMe && followMeLeading
            ReactionButton(
                kind = kind,
                modifier = Modifier.weight(1f),
                remainingMs = if (persistentToggle) 0L else cooldown.remainingMs(kind, nowMs),
                active = active,
                pending = persistentToggle && followMeTogglePending,
                onClick = { onReact(kind) },
            )
        }
    }
}

@Composable
private fun ReactionButton(
    kind: ConvoyReactionKind,
    remainingMs: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    pending: Boolean = false,
) {
    val ready = remainingMs <= 0L && !pending
    val colors = LocalKccMapColors.current
    val accent = reactionColor(kind)
    val description =
        if (active) stringResource(R.string.convoyFollowTrail_activeContentDescription)
        else reactionButtonLabel(kind)
    val cooldownDescription = if (remainingMs > 0L) {
        stringResource(R.string.convoyReaction_cooldown, ceil(remainingMs / 1000.0).toInt())
    } else null
    val remainingFraction =
        (remainingMs.toFloat() / convoyReactionCooldownMs(kind)).coerceIn(0f, 1f)
    Surface(
        onClick = onClick,
        enabled = ready,
        shape = RoundedCornerShape(KccRadius.md),
        color = colors.overlay,
        border = BorderStroke(if (active) 2.dp else 1.dp, if (active) accent else colors.border),
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
        modifier = modifier.height(56.dp)
            .testTag(convoyReactionButtonTag(kind))
            .semantics {
                contentDescription = description
                if (cooldownDescription != null) stateDescription = cooldownDescription
                if (kind == ConvoyReactionKind.FollowMe) selected = active
            },
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val tint = if (ready || active) accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
            if (kind == ConvoyReactionKind.HelloGoodbye) {
                Icon(
                    painter = painterResource(R.drawable.ic_convoy_hand),
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(28.dp),
                )
            } else {
                Icon(
                    imageVector = reactionIcon(kind),
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(28.dp),
                )
            }
            // A colour strip replaces the visible countdown. No text competes
            // with the glyph; cooldown and selected state remain accessible.
            Box(
                Modifier.align(Alignment.BottomStart)
                    .fillMaxWidth(if (remainingMs > 0L) remainingFraction else 1f)
                    .height(if (active) 4.dp else 3.dp)
                    .background(accent.copy(alpha = if (ready || active) 1f else 0.45f)),
            )
            if (pending) {
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(12.dp).clearAndSetSemantics {},
                    color = accent,
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Per-kind presentation (icons + colours pure; captions/labels localized).
// ---------------------------------------------------------------------------

private fun reactionIcon(kind: ConvoyReactionKind): ImageVector =
    when (kind) {
        ConvoyReactionKind.Police -> Icons.Filled.LocalPolice
        ConvoyReactionKind.HelloGoodbye -> Icons.Filled.WavingHand
        ConvoyReactionKind.FollowMe -> Icons.Filled.Navigation
    }

private fun reactionColor(kind: ConvoyReactionKind): Color =
    when (kind) {
        ConvoyReactionKind.Police -> KccPalette.errorRed
        ConvoyReactionKind.HelloGoodbye -> KccPalette.successGreen
        ConvoyReactionKind.FollowMe -> KccPalette.crownGold
    }

@Composable
private fun reactionButtonLabel(kind: ConvoyReactionKind): String =
    stringResource(
        when (kind) {
            ConvoyReactionKind.Police -> R.string.convoyReaction_police_label
            ConvoyReactionKind.HelloGoodbye -> R.string.convoyReaction_hello_label
            ConvoyReactionKind.FollowMe -> R.string.convoyReaction_followMe_label
        },
    )

/** The mid-screen caption: the reaction phrase, prefixed with the sender's name when known. */
@Composable
private fun reactionOverlayCaption(kind: ConvoyReactionKind, senderName: String?): String {
    val phrase =
        stringResource(
            when (kind) {
                ConvoyReactionKind.Police -> R.string.convoyReaction_police_caption
                ConvoyReactionKind.HelloGoodbye -> R.string.convoyReaction_hello_caption
                ConvoyReactionKind.FollowMe -> R.string.convoyReaction_followMe_caption
            },
        )
    return if (senderName.isNullOrBlank()) phrase else "$senderName: $phrase"
}
