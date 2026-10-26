package com.kungsbackacarcommunity.app.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.currentCompositionLocalContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import kotlin.math.roundToInt
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.design.LocalKccMapColors

const val MAP_DETAIL_SHEET_TAG = "map_detail_sheet"

/** Activity-window reading surface, docked inside the real shell body. */
@Composable
fun MapDetailSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    leadingContent: (@Composable () -> Unit)? = null,
    contentSpacing: Dp = KccSpacing.s3,
    closeContentDescription: String = stringResource(R.string.shell_placeMenuClose),
    content: @Composable ColumnScope.() -> Unit,
) {
    val host = LocalDockedMapSheetHost.current
    val owner = remember { Any() }
    val currentTitle by rememberUpdatedState(title)
    val currentDismiss by rememberUpdatedState(onDismiss)
    val currentModifier by rememberUpdatedState(modifier)
    val currentLeading by rememberUpdatedState(leadingContent)
    val currentContent by rememberUpdatedState(content)
    val currentSpacing by rememberUpdatedState(contentSpacing)
    val currentCloseDescription by rememberUpdatedState(closeContentDescription)
    val locals = currentCompositionLocalContext
    val currentLocals by rememberUpdatedState(locals)
    val presentation: @Composable () -> Unit = {
        CompositionLocalProvider(currentLocals) {
            DockedMapSheetContent(currentTitle, currentDismiss, currentModifier, currentLeading, currentSpacing, currentCloseDescription, currentContent)
        }
    }
    val currentPresentation by rememberUpdatedState(presentation)
    if (host != null) {
        DisposableEffect(host, owner) {
            host.register(DockedMapSheetState.Entry(owner, { currentDismiss() }, { currentPresentation() }))
            onDispose { host.remove(owner) }
        }
    } else {
        DockedMapSheetContent(title, onDismiss, modifier, leadingContent, contentSpacing, closeContentDescription, content)
    }
}

@Composable
private fun DockedMapSheetContent(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier,
    leadingContent: (@Composable () -> Unit)?,
    contentSpacing: Dp,
    closeContentDescription: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onDismiss)
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.18f))
            .pointerInput(onDismiss) { detectTapGestures { onDismiss() } }.clearAndSetSemantics {})
        Surface(
            modifier = modifier.align(Alignment.BottomCenter).widthIn(max = 600.dp).fillMaxWidth()
                .heightIn(max = maxHeight * 0.88f)
                .offset { IntOffset(0, dragOffset.roundToInt()) }
                .pointerInput(Unit) { detectTapGestures {} }
                .semantics { paneTitle = title; isTraversalGroup = true; dismiss { onDismiss(); true } },
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = LocalKccMapColors.current.overlay,
            contentColor = MaterialTheme.colorScheme.onSurface,
            shadowElevation = 6.dp,
        ) {
        Column(Modifier.fillMaxWidth().testTag(MAP_DETAIL_SHEET_TAG)) {
            Column(Modifier.draggable(
                state = rememberDraggableState { dragOffset = (dragOffset + it).coerceAtLeast(0f) },
                orientation = Orientation.Vertical,
                onDragStopped = { velocity ->
                    if (dragOffset > threshold || velocity > 1600f) onDismiss()
                    dragOffset = 0f
                },
            )) {
            Box(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), contentAlignment = Alignment.Center) {
                Surface(Modifier.size(32.dp, 4.dp), shape = RoundedCornerShape(2.dp), color = MaterialTheme.colorScheme.outline) {}
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = KccSpacing.s4),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(KccSpacing.s3),
            ) {
                leadingContent?.invoke()
                Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = closeContentDescription)
                }
            }
            }
            Column(
                modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())
                    .padding(start = KccSpacing.s4, end = KccSpacing.s4, bottom = KccSpacing.s6, top = KccSpacing.s3),
                verticalArrangement = Arrangement.spacedBy(contentSpacing),
                content = content,
            )
        }
        }
    }
}
