package com.kungsbackacarcommunity.app.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kungsbackacarcommunity.app.design.KccRadius
import com.kungsbackacarcommunity.app.design.KccSpacing

/**
 * Shared "Aero" chrome for every full-screen sub-route so all in-app pages read
 * as one system. It mirrors the map-first home ([MapHome]): a plain page
 * background with frosted, rounded, tonally-elevated surfaces floating on top,
 * comfortable top spacing so content is never jammed under the status bar, and a
 * single consistent title treatment.
 *
 * The Android system Back button (handled once, centrally, by the shell's
 * `BackHandler`) is the only way back — pages no longer render their own Back
 * affordance.
 */

/** Extra breathing room above the title, on top of the status-bar inset. */
val AeroPageTopSpacing: Dp = KccSpacing.s6

/** Default gutters + comfortable content spacing shared by every Aero page. */
val AeroPageHorizontalPadding: Dp = KccSpacing.s6
val AeroPageBottomPadding: Dp = KccSpacing.s6

/**
 * A scrollable Aero page: page background, top spacing, the frosted
 * [AeroPageTitle], then the caller's [content] as ordinary [Column] children.
 *
 * @param scrollable set false only when [content] already hosts its own scroll
 *   container; pages backed by a `LazyColumn` should instead keep that list and
 *   use [AeroPageTitle] as its first item (see the notifications / blocked /
 *   drive-history lists).
 * @param verticalArrangement spacing between children; defaults to the shared
 *   16dp rhythm. Forms that want a tighter field rhythm may override it.
 */
@Composable
fun AeroPage(
    title: String,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    horizontalPadding: Dp = AeroPageHorizontalPadding,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(KccSpacing.s4),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val scrollState = rememberScrollState()
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(top = AeroPageTopSpacing)
                    .then(if (scrollable) Modifier.verticalScroll(scrollState) else Modifier)
                    .padding(
                        start = horizontalPadding,
                        end = horizontalPadding,
                        bottom = AeroPageBottomPadding,
                    ),
            verticalArrangement = verticalArrangement,
        ) {
            AeroPageTitle(title)
            content()
        }
    }
}

/**
 * The shared frosted title header: the page title on a rounded, tonally-elevated
 * surface, matching the map-first home's floating controls. Exposed on its own
 * so `LazyColumn`-backed pages can drop it in as their first item and stay
 * visually identical to [AeroPage].
 */
@Composable
fun AeroPageTitle(
    title: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(KccRadius.lg),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 3.dp,
    ) {
        Text(
            text = title,
            modifier = Modifier.padding(horizontal = KccSpacing.s5, vertical = KccSpacing.s4),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Shared content padding for `LazyColumn`-backed Aero pages (see [AeroPage]). */
fun aeroLazyContentPadding(): PaddingValues =
    PaddingValues(
        start = AeroPageHorizontalPadding,
        end = AeroPageHorizontalPadding,
        top = AeroPageTopSpacing,
        bottom = AeroPageBottomPadding,
    )
