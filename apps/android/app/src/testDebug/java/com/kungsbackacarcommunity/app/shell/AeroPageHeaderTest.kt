package com.kungsbackacarcommunity.app.shell

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import coil.Coil
import coil.ImageLoader
import coil.intercept.Interceptor
import coil.request.ErrorResult
import coil.request.ImageResult
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.profile.SupporterBadge
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** Static delivery: these Robolectric tests must be executed by the integration lane. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h900dp-mdpi")
class AeroPageHeaderTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var replacedImageLoader = false

    @After fun resetImageLoader() {
        if (replacedImageLoader) Coil.reset()
    }

    @Test fun noProviderAndExplicitNullKeepLegacyGeometryAndTitleAction() {
        val provideNull = mutableStateOf(false)
        var titleClicks = 0
        compose.setContent {
            KccTheme {
                val page: @Composable () -> Unit = {
                    CompositionLocalProvider(LocalAeroBackAvailable provides true) {
                        AeroPage("Settings", onTitleClick = { titleClicks++ }) { Text("body") }
                    }
                }
                if (provideNull.value) {
                    CompositionLocalProvider(LocalAeroOwnerHeader provides null) { page() }
                } else {
                    page()
                }
            }
        }
        val originalTitle = compose.onNodeWithText("Settings").fetchSemanticsNode().boundsInRoot
        val originalBack = compose.onNodeWithTag(AeroBackButtonTag).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag(AeroOwnerAvatarTag).assertDoesNotExist()
        assertTrue("Legacy Back is above the scrolling title", originalBack.bottom <= originalTitle.top)
        compose.runOnIdle { provideNull.value = true }
        assertEquals(originalTitle, compose.onNodeWithText("Settings").fetchSemanticsNode().boundsInRoot)
        assertEquals(originalBack, compose.onNodeWithTag(AeroBackButtonTag).fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag(AeroOwnerAvatarTag).assertDoesNotExist()
        compose.onNodeWithText("Settings").performClick()
        compose.runOnIdle { assertEquals(1, titleClicks) }
    }

    @Test fun ownerShortcutAndTitleHaveSeparateActionsAndHeadingSemantics() {
        var profileClicks = 0
        var titleClicks = 0
        compose.setContent {
            KccTheme {
                CompositionLocalProvider(
                    LocalAeroOwnerHeader provides AeroOwnerHeader(null, false) { profileClicks++ },
                ) {
                    AeroPage("Member", onTitleClick = { titleClicks++ }) { Text("body") }
                }
            }
        }
        compose.onNodeWithTag(AeroBackButtonTag).assertDoesNotExist()
        compose.onNodeWithText("Member")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .assertHasClickAction().performClick()
        compose.onNodeWithTag(AeroOwnerAvatarTag).assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.navigation_profile))
            .assertHasClickAction()
        compose.onNodeWithTag(AeroOwnerAvatarFallbackTag, useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, titleClicks)
            assertEquals(1, profileClicks)
        }
        val title = compose.onNodeWithText("Member").fetchSemanticsNode().boundsInRoot
        val avatar = compose.onNodeWithTag(AeroOwnerAvatarTag).fetchSemanticsNode().boundsInRoot
        assertEquals(48f, title.height, 0.5f)
        assertEquals(title.center.y, avatar.center.y, 0.5f)
        assertTrue(title.right <= avatar.left)
    }

    @Test fun lazyPageWithoutOwnerKeepsOriginalGuttersAndScrollingTitle() {
        val explicitNull = mutableStateOf(false)
        compose.setContent {
            KccTheme {
                CompositionLocalProvider(LocalAeroBackAvailable provides true) {
                    if (explicitNull.value) {
                        CompositionLocalProvider(LocalAeroOwnerHeader provides null) { TestPage(lazy = true) }
                    } else {
                        TestPage(lazy = true)
                    }
                }
            }
        }
        val originalTitle = compose.onNodeWithText("Settings").fetchSemanticsNode().boundsInRoot
        val originalList = compose.onNodeWithTag("list").fetchSemanticsNode().boundsInRoot
        val originalBack = compose.onNodeWithTag(AeroBackButtonTag).fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { explicitNull.value = true }
        assertEquals(originalTitle, compose.onNodeWithText("Settings").fetchSemanticsNode().boundsInRoot)
        assertEquals(originalList, compose.onNodeWithTag("list").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag(AeroOwnerAvatarTag).assertDoesNotExist()
        val expectedGutter = with(compose.density) { AeroPageHorizontalPadding.toPx() }
        assertEquals(expectedGutter, originalTitle.left - originalList.left, 0.5f)
        compose.onNodeWithTag("list").performScrollToIndex(35)
        compose.onNodeWithText("Settings").assertDoesNotExist()
        assertEquals(originalBack, compose.onNodeWithTag(AeroBackButtonTag).fetchSemanticsNode().boundsInRoot)
    }

    @Test fun actualResolvedUrlReachesCoilAndFailedImageKeepsNativeFallback() {
        val requested = CopyOnWriteArrayList<Any>()
        Coil.setImageLoader(
            ImageLoader.Builder(compose.activity).components {
                add(object : Interceptor {
                    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
                        requested.add(chain.request.data)
                        // Never access the network. Exercise the real image error path.
                        return ErrorResult(
                            drawable = null,
                            request = chain.request,
                            throwable = IllegalStateException("Unavailable test image"),
                        )
                    }
                })
            }.build(),
        )
        replacedImageLoader = true
        val resolvedUrl = "https://images.example.test/resolved-owner-avatar.jpg"
        val owner = mutableStateOf(AeroOwnerHeader(null, false) {})
        compose.setContent {
            KccTheme {
                CompositionLocalProvider(LocalAeroOwnerHeader provides owner.value) {
                    AeroPage("Settings") { Text("body") }
                }
            }
        }
        compose.onNodeWithTag(AeroOwnerAvatarFallbackTag, useUnmergedTree = true).assertIsDisplayed()
        assertTrue(requested.isEmpty())
        compose.runOnIdle { owner.value = owner.value.copy(avatarUrl = resolvedUrl) }
        compose.waitUntil(5_000) { requested.contains(resolvedUrl) }
        compose.onNodeWithTag(AeroOwnerAvatarFallbackTag, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(AeroOwnerAvatarTag).assertHasClickAction()
        assertTrue(requested.all { it == resolvedUrl })
        compose.runOnIdle { owner.value = owner.value.copy(avatarUrl = " ") }
        compose.onNodeWithTag(AeroOwnerAvatarFallbackTag, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun effectiveSupporterVisibilityAndOwnerOptOutUpdateWithoutExtraStatus() {
        val owner = mutableStateOf<AeroOwnerHeader?>(AeroOwnerHeader(null, false) {})
        compose.setContent {
            KccTheme {
                CompositionLocalProvider(LocalAeroOwnerHeader provides owner.value) {
                    AeroPage("Settings") { Text("body") }
                }
            }
        }
        val badgeDescription = compose.activity.getString(R.string.supporterBadge_accessibility)
        for (badge in listOf(SupporterBadge(true, true), SupporterBadge(true, false), SupporterBadge(false, true))) {
            compose.runOnIdle { owner.value = owner.value!!.copy(showSupporterBadge = badge.visible) }
            val node = compose.onNodeWithContentDescription(badgeDescription, useUnmergedTree = true)
            if (badge.visible) node.assertIsDisplayed() else node.assertDoesNotExist()
        }
        compose.runOnIdle { owner.value = null }
        compose.onNodeWithTag(AeroOwnerAvatarTag).assertDoesNotExist()
        compose.onNodeWithText("Settings").assertIsDisplayed()
    }

    @Test fun compactPageBackUsesNestedDispatcherAndStaysPinnedWhileScrolling() =
        verifyPinnedDispatcher(lazy = false)

    @Test fun lazyPageBackUsesNestedDispatcherAndStaysPinnedWhileScrolling() =
        verifyPinnedDispatcher(lazy = true)

    private fun verifyPinnedDispatcher(lazy: Boolean) {
        val nestedEnabled = mutableStateOf(true)
        var nestedBacks = 0
        var routeBacks = 0
        compose.setContent {
            KccTheme {
                BackHandler { routeBacks++ }
                BackHandler(enabled = nestedEnabled.value) { nestedBacks++ }
                CompositionLocalProvider(
                    LocalAeroBackAvailable provides true,
                    LocalAeroOwnerHeader provides AeroOwnerHeader(null, false) {},
                ) {
                    TestPage(lazy)
                }
            }
        }
        compose.onAllNodesWithTag(AeroBackButtonTag).assertCountEquals(1)
        val back = compose.onNodeWithTag(AeroBackButtonTag)
        back.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        compose.runOnIdle {
            assertEquals(1, nestedBacks)
            assertEquals(0, routeBacks)
            nestedEnabled.value = false
        }
        val before = back.fetchSemanticsNode().boundsInRoot
        if (lazy) {
            compose.onNodeWithTag("list").performScrollToIndex(35)
        } else {
            compose.onNodeWithText("row 39").performScrollTo()
        }
        back.assertIsDisplayed().performClick()
        assertEquals(before, back.fetchSemanticsNode().boundsInRoot)
        compose.runOnIdle {
            assertEquals(1, nestedBacks)
            assertEquals(1, routeBacks)
        }
    }

    @Test fun lazyTitleRetainsHeadingClickAndSingleOwnerShortcut() {
        var titleClicks = 0
        compose.setContent {
            KccTheme {
                CompositionLocalProvider(LocalAeroOwnerHeader provides AeroOwnerHeader(null, false) {}) {
                    AeroLazyPage {
                        LazyColumn(contentPadding = aeroLazyContentPadding()) {
                            item { AeroPageTitle("Inbox", onClick = { titleClicks++ }) }
                            item { Text("body") }
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("Inbox")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).performClick()
        compose.runOnIdle { assertEquals(1, titleClicks) }
        compose.onAllNodesWithTag(AeroOwnerAvatarTag).assertCountEquals(1)
        compose.onNodeWithTag(AeroBackButtonTag).assertDoesNotExist()
        val title = compose.onNodeWithText("Inbox").fetchSemanticsNode().boundsInRoot
        val avatar = compose.onNodeWithTag(AeroOwnerAvatarTag).fetchSemanticsNode().boundsInRoot
        assertEquals(title.center.y, avatar.center.y, 0.5f)
    }

    @Test fun narrowLargeTextWrapsWithoutClippingOrShrinkingAndActionsStayReachable() {
        val scale = mutableStateOf(1f)
        val lazy = mutableStateOf(false)
        val dark = mutableStateOf(true)
        val title = "Notifieringsinställningar och blockerade användare"
        compose.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f, scale.value),
                LocalAeroBackAvailable provides true,
                LocalAeroOwnerHeader provides AeroOwnerHeader(null, false) {},
            ) {
                KccTheme(darkTheme = dark.value) {
                    Box(Modifier.requiredWidth(320.dp)) { TestPage(lazy.value, title) }
                }
            }
        }
        for (isLazy in listOf(false, true)) for (fontScale in listOf(1f, 2f)) for (isDark in listOf(true, false)) {
            compose.runOnIdle {
                lazy.value = isLazy
                scale.value = fontScale
                dark.value = isDark
            }
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(title).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            val layout = layouts.single()
            assertEquals(24.sp, layout.layoutInput.style.fontSize)
            assertEquals(fontScale, layout.layoutInput.density.fontScale, 0.001f)
            assertFalse("Title must wrap without clipping", layout.hasVisualOverflow)
            assertEquals(title.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
            val textBounds = compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot
            val avatar = compose.onNodeWithTag(AeroOwnerAvatarTag)
            avatar.assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
            assertTrue(textBounds.right <= avatar.fetchSemanticsNode().boundsInRoot.left)
            compose.onNodeWithTag(AeroBackButtonTag).assertIsDisplayed()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
    }

    @Test fun ownerHeaderPreservesContentInsetsAndTranslucentPanelTopSpacing() {
        val inPanel = mutableStateOf(false)
        val bottomInset = mutableStateOf(0)
        compose.setContent {
            KccTheme {
                CompositionLocalProvider(
                    LocalInTranslucentPanel provides inPanel.value,
                    LocalAeroOwnerHeader provides AeroOwnerHeader(null, false) {},
                ) {
                    AeroPage(
                        "Settings", scrollable = false, modifier = Modifier.testTag("page"),
                        contentWindowInsets = WindowInsets(bottom = bottomInset.value),
                    ) {
                        Column(Modifier.weight(1f).testTag("body")) { Text("body") }
                    }
                }
            }
        }
        val fullPage = compose.onNodeWithTag("page").fetchSemanticsNode().boundsInRoot
        val originalBody = compose.onNodeWithTag("body").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { bottomInset.value = 180 }
        assertEquals(fullPage, compose.onNodeWithTag("page").fetchSemanticsNode().boundsInRoot)
        val insetBody = compose.onNodeWithTag("body").fetchSemanticsNode().boundsInRoot
        assertEquals(180f, originalBody.bottom - insetBody.bottom, 0.5f)
        compose.runOnIdle { inPanel.value = true }
        val avatar = compose.onNodeWithTag(AeroOwnerAvatarTag).fetchSemanticsNode().boundsInRoot
        val expectedTop = with(compose.density) { AeroPageTopSpacing.toPx() }
        assertEquals(expectedTop, avatar.top - fullPage.top, 0.5f)
    }

    @Composable private fun TestPage(lazy: Boolean, title: String = "Settings") {
        if (lazy) {
            AeroLazyPage {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag("list"),
                    contentPadding = aeroLazyContentPadding(),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    item { AeroPageTitle(title) }
                    items(40) { Text("row $it", Modifier.height(48.dp)) }
                }
            }
        } else {
            AeroPage(title) {
                repeat(40) {
                    Text("row $it")
                    Spacer(Modifier.height(32.dp))
                }
            }
        }
    }
}
