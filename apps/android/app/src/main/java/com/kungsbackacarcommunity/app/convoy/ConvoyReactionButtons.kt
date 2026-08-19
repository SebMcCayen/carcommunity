package com.kungsbackacarcommunity.app.convoy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalPolice
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.WavingHand
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccPalette
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.design.ReactionOverlay
import com.kungsbackacarcommunity.app.design.ReactionOverlayEvent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.ceil

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
 */
@Composable
fun ConvoyReactionsHost(
    convoyId: String,
    repository: ConvoyReactionRepository,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var cooldown by remember(convoyId) { mutableStateOf(ConvoyReactionCooldownState()) }
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
            onReact = { kind ->
                val now = System.currentTimeMillis()
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
                        ConvoyReactionSendResult.Sent -> Unit
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
 * The three reaction buttons, bottom-centre. Each is a car/game-styled coloured
 * disc with a label; while its cooldown is running it greys and shows the seconds
 * left (the client mirror of the server's anti-spam window). Pure UI — the tap
 * decision and the send live in [ConvoyReactionsHost].
 */
@Composable
fun ConvoyReactionButtons(
    cooldown: ConvoyReactionCooldownState,
    nowMs: Long,
    onReact: (ConvoyReactionKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.testTag(CONVOY_REACTIONS_TAG),
        horizontalArrangement = Arrangement.spacedBy(KccSpacing.s5),
        verticalAlignment = Alignment.Top,
    ) {
        for (kind in ConvoyReactionKind.entries) {
            ReactionButton(
                kind = kind,
                remainingMs = cooldown.remainingMs(kind, nowMs),
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
) {
    val ready = remainingMs <= 0L
    val label = reactionButtonLabel(kind)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(KccSpacing.s1),
    ) {
        Surface(
            onClick = onClick,
            enabled = ready,
            shape = CircleShape,
            color = if (ready) reactionColor(kind) else MaterialTheme.colorScheme.surfaceVariant,
            shadowElevation = if (ready) 6.dp else 0.dp,
            modifier =
                Modifier
                    .size(60.dp)
                    .testTag(convoyReactionButtonTag(kind)),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = reactionIcon(kind),
                    contentDescription = label,
                    tint =
                        if (ready) Color.White
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(28.dp).graphicsLayer { alpha = if (ready) 1f else 0.5f },
                )
                if (!ready) {
                    // Seconds remaining, so the member sees WHY the button is greyed
                    // rather than a dead control.
                    Text(
                        text = ceil(remainingMs / 1000.0).toInt().coerceAtLeast(1).toString(),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                }
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
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
