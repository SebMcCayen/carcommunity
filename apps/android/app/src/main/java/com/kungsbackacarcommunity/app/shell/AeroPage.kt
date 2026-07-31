package com.kungsbackacarcommunity.app.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
 * A scrollable Aero page: page background, top spacing, the [AeroPageTitle],
 * then the caller's [content] as ordinary [Column] children.
 *
 * @param scrollable set false only when [content] already hosts its own scroll
 *   container; pages backed by a `LazyColumn` should instead keep that list and
 *   use [AeroPageTitle] as its first item (see the notifications / blocked /
 *   drive-history lists).
 * @param verticalArrangement spacing between children; defaults to the shared
 *   16dp rhythm. Forms that want a tighter field rhythm may override it.
 * @param onTitleClick makes the title header itself tappable. Null (the default)
 *   keeps it inert — the plain header every other page renders. Used where the
 *   title names a member (the DM thread), so tapping it opens their profile.
 * @param contentWindowInsets extra insets to hold the page's CONTENT clear of —
 *   the DM thread passes `ime.union(navigationBars)` so its composer rides above
 *   the keyboard and the nav bar. Applied on the inner chrome [Column], NOT via
 *   [modifier]: the background [Surface] draws at its own node's size, so any
 *   padding anywhere in the Surface's modifier chain shrinks the page background
 *   and leaves a bare band under the (transparent, edge-to-edge) system bars.
 *   Same reason the status-bar inset is applied here rather than outside.
 *   Null (the default) leaves every other page byte-identical.
 */
/**
 * The page background an Aero page paints, and whether it insets itself for the
 * status bar.
 *
 * Both answers flip when the page is rendered inside a [TranslucentShellPanel]:
 * the panel's card already paints a TRANSLUCENT surface (an opaque page
 * background on top of it would defeat the whole point — the live map has to
 * read through) and the card already sits below the status bar (a second inset
 * would push the title down by the status-bar height for no reason). Derived
 * here, in ONE place, from [LocalInTranslucentPanel], so [AeroPage] and
 * [AeroLazyPage] cannot answer it differently.
 */
@Composable
private fun aeroPageBackground(): Color =
    if (LocalInTranslucentPanel.current) {
        Color.Transparent
    } else {
        MaterialTheme.colorScheme.background
    }

/**
 * The status-bar inset an Aero page applies to its own chrome — none inside a
 * [TranslucentShellPanel], which has already applied it to the card. See
 * [aeroPageBackground].
 */
@Composable
private fun Modifier.aeroPageStatusBarInset(): Modifier =
    if (LocalInTranslucentPanel.current) this else this.statusBarsPadding()

@Composable
fun AeroPage(
    title: String,
    modifier: Modifier = Modifier,
    scrollable: Boolean = true,
    horizontalPadding: Dp = AeroPageHorizontalPadding,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(KccSpacing.s4),
    onTitleClick: (() -> Unit)? = null,
    contentWindowInsets: WindowInsets? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier = modifier.fillMaxSize(), color = aeroPageBackground()) {
        val scrollState = rememberScrollState()
        // Fixed chrome: the status-bar inset and the top breathing room live on
        // this outer Column, OUTSIDE the scroll viewport, so they stay pinned and
        // the title/content can never scroll up under the status bar. Any caller
        // insets join them here, INSIDE the background Surface, for the same
        // reason: the page background must still paint edge to edge behind the
        // transparent system bars.
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .aeroPageStatusBarInset()
                    .then(
                        if (contentWindowInsets != null) {
                            Modifier.windowInsetsPadding(contentWindowInsets)
                        } else {
                            Modifier
                        },
                    )
                    .padding(top = AeroPageTopSpacing),
        ) {
            // Scrollable content: only the title + caller content (and the gutter /
            // bottom padding around them) move when the user scrolls.
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .then(if (scrollable) Modifier.verticalScroll(scrollState) else Modifier)
                        .padding(
                            start = horizontalPadding,
                            end = horizontalPadding,
                            bottom = AeroPageBottomPadding,
                        ),
                verticalArrangement = verticalArrangement,
            ) {
                AeroPageTitle(title, onClick = onTitleClick)
                content()
            }
        }
    }
}

/**
 * A `LazyColumn`-backed Aero page. Mirrors [AeroPage]'s fixed-chrome structure:
 * the page background, the status-bar inset and the top breathing room live on a
 * fixed outer [Column] OUTSIDE the scroll viewport, so they stay pinned exactly
 * like [AeroPage]. The caller supplies its own `LazyColumn` as [content] (with
 * [AeroPageTitle] as the first item and [aeroLazyContentPadding] for gutters), so
 * only the list — title included — scrolls.
 *
 * Use this instead of hand-rolling a `Surface` + `statusBarsPadding()` around a
 * `LazyColumn`: doing so would only pin the status-bar inset, and the top
 * breathing room (previously baked into [aeroLazyContentPadding]'s `top`) would
 * scroll away, letting list content slide under the status bar — inconsistent
 * with [AeroPage].
 */
@Composable
fun AeroLazyPage(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Surface(modifier = modifier.fillMaxSize(), color = aeroPageBackground()) {
        // Fixed chrome: status-bar inset + top breathing room, OUTSIDE the
        // caller's LazyColumn scroll viewport, so they stay pinned (parity with
        // AeroPage). The caller's list then scrolls beneath this fixed top inset.
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .aeroPageStatusBarInset()
                    .padding(top = AeroPageTopSpacing),
        ) {
            content()
        }
    }
}

/**
 * The shared page title: a plain heading sitting directly on the page
 * background — no surrounding box, so it reads as a clean, modern page title
 * rather than a boxed-in card. Exposed on its own so `LazyColumn`-backed pages
 * can drop it in as their first item and stay visually identical to [AeroPage].
 *
 * @param onClick makes the header tappable (announced as a button, so the
 *   affordance reaches accessibility services). Null (the default) keeps it a
 *   plain, inert header — visually identical either way.
 */
@Composable
fun AeroPageTitle(
    title: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    // No surrounding box: the title sits directly on the page background as a
    // clean page heading. It aligns to the page's own horizontal gutter (applied
    // by AeroPage / aeroLazyContentPadding), so no horizontal padding is added
    // here — that would indent the title away from the content below it. A small
    // vertical padding keeps it from cramping against adjacent content and, when
    // [onClick] is set, gives the tappable header a comfortable touch target.
    Text(
        text = title,
        modifier =
            modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null) {
                        Modifier.clickable(role = Role.Button, onClick = onClick)
                    } else {
                        Modifier
                    },
                )
                .padding(vertical = KccSpacing.s2),
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/**
 * Shared content padding for the `LazyColumn` inside an [AeroLazyPage]: the
 * horizontal gutters and the bottom padding only. The top breathing room is
 * deliberately NOT included here — [AeroLazyPage] applies it to its fixed outer
 * chrome so it stays pinned, instead of scrolling away as `contentPadding.top`
 * would.
 */
fun aeroLazyContentPadding(): PaddingValues =
    PaddingValues(
        start = AeroPageHorizontalPadding,
        end = AeroPageHorizontalPadding,
        bottom = AeroPageBottomPadding,
    )
