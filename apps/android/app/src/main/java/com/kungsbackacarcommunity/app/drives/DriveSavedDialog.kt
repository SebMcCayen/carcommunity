package com.kungsbackacarcommunity.app.drives

import android.animation.ValueAnimator
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccButton
import com.kungsbackacarcommunity.app.design.KccOutlinedButton
import com.kungsbackacarcommunity.app.design.KccSpacing

const val DRIVE_SAVED_DIALOG_TAG = "drive_saved_dialog"
const val DRIVE_SAVED_DIALOG_OK_TAG = "drive_saved_dialog_ok"
const val DRIVE_SAVED_DIALOG_HISTORY_TAG = "drive_saved_dialog_history"
const val DRIVE_SAVING_STATUS_TAG = "drive_saving_status"
const val DRIVE_SAVE_SUCCESS_TAG = "drive_save_success"

/**
 * Stop → saving → confirmed-save feedback. Presentation never advances on a timer:
 * [isSaved] comes from the host's definitive save outcome. Closing while pending
 * only hides feedback; it never cancels, clears or retries the background save.
 * The failure safety net remains owned by SessionSummaryDialog.
 */
@Composable
fun DriveSavedDialog(
    message: String,
    confirmLabel: String,
    historyLabel: String,
    onDismiss: () -> Unit,
    onHistory: () -> Unit,
    isSaved: Boolean = true,
    motionEnabled: Boolean = ValueAnimator.areAnimatorsEnabled(),
) {
    val savingMessage = stringResource(R.string.savedDrives_savingProgress)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        SaveResultSystemBars()
        Surface(Modifier.fillMaxSize().testTag(DRIVE_SAVED_DIALOG_TAG),
            color = MaterialTheme.colorScheme.background) {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                val viewportHeight = maxHeight
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                        .heightIn(min = viewportHeight).padding(KccSpacing.s6),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(Modifier.widthIn(max = 480.dp),
                        verticalArrangement = Arrangement.spacedBy(KccSpacing.s6),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        AnimatedContent(
                            targetState = isSaved,
                            transitionSpec = {
                                (fadeIn(tween(if (motionEnabled) 240 else 0)) +
                                    slideInVertically(tween(if (motionEnabled) 240 else 0)) { it / 12 })
                                    .togetherWith(fadeOut(tween(if (motionEnabled) 120 else 0)))
                            },
                            label = "drive-save-result",
                        ) { saved ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(KccSpacing.s6),
                            ) {
                                SaveResultBadge(saved, motionEnabled)
                                Text(
                                    if (saved) message else savingMessage,
                                    modifier = Modifier.fillMaxWidth()
                                        .testTag(if (saved) DRIVE_SAVE_SUCCESS_TAG else DRIVE_SAVING_STATUS_TAG)
                                        .semantics { liveRegion = LiveRegionMode.Polite },
                                    style = MaterialTheme.typography.headlineMedium,
                                    textAlign = TextAlign.Center,
                                )
                            }
                        }
                        KccButton(onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth().testTag(DRIVE_SAVED_DIALOG_OK_TAG)) {
                            Text(if (isSaved) confirmLabel else stringResource(R.string.savedDrives_closeButton))
                        }
                        KccOutlinedButton(
                            onClick = { onHistory(); onDismiss() },
                            enabled = isSaved,
                            modifier = Modifier.fillMaxWidth().testTag(DRIVE_SAVED_DIALOG_HISTORY_TAG),
                        ) { Text(historyLabel) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SaveResultSystemBars() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    val darkIcons = MaterialTheme.colorScheme.background.luminance() > 0.5f
    DisposableEffect(window, darkIcons) {
        if (window == null) {
            onDispose { }
        } else {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            val previousStatus = controller.isAppearanceLightStatusBars
            val previousNavigation = controller.isAppearanceLightNavigationBars
            controller.isAppearanceLightStatusBars = darkIcons
            controller.isAppearanceLightNavigationBars = darkIcons
            onDispose {
                controller.isAppearanceLightStatusBars = previousStatus
                controller.isAppearanceLightNavigationBars = previousNavigation
            }
        }
    }
}

@Composable
private fun SaveResultBadge(saved: Boolean, motionEnabled: Boolean) {
    val reveal = remember(saved, motionEnabled) { Animatable(if (motionEnabled) 0f else 1f) }
    LaunchedEffect(reveal) { reveal.animateTo(1f, tween(if (motionEnabled) 280 else 0)) }
    Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
        Surface(
            modifier = Modifier.size(96.dp).graphicsLayer {
                val scale = 0.88f + 0.12f * reveal.value
                scaleX = scale
                scaleY = scale
                alpha = 0.5f + 0.5f * reveal.value
            },
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(if (saved) Icons.Filled.Check else Icons.Filled.DirectionsCar,
                    contentDescription = null, tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(48.dp))
            }
        }
        if (!saved && motionEnabled) {
            CircularProgressIndicator(Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp)
        }
    }
}
