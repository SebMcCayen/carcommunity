package com.kungsbackacarcommunity.app.design

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The theme preference as the user experiences it: that each setting produces
 * the COLOURS it promises (not merely that the value was stored), that the
 * choice survives a new store instance (process death), and that it applies
 * without recreating the activity.
 *
 * The decision table itself is unit-tested in ThemePreferenceTest; this covers
 * the Compose half, which needs a real Context and renderer.
 */
@RunWith(AndroidJUnit4::class)
class ThemePreferenceUiTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The store is device-local and persists, so a preference left behind by an
     * earlier test would leak into the next. Reset to the default before each.
     */
    @Before
    fun resetPreference() {
        ThemePreferenceStore(context).set(ThemePreference.SYSTEM)
    }

    /**
     * Renders [KccTheme] with [preference] applied to a fixed [systemInDark],
     * capturing what the theme actually resolved to. Asserting the rendered
     * background/surface colours — not the enum — is the point: a setting that
     * stores correctly but themes wrongly is exactly the bug being fixed.
     */
    private fun renderedScheme(
        preference: ThemePreference,
        systemInDark: Boolean,
    ): Triple<Boolean, Color, Color> {
        var dark = false
        var background = Color.Unspecified
        var surface = Color.Unspecified
        composeTestRule.setContent {
            KccTheme(darkTheme = preference.resolveDark(systemInDark)) {
                dark = LocalKccDarkTheme.current
                background = MaterialTheme.colorScheme.background
                surface = MaterialTheme.colorScheme.surface
                Text("themed")
            }
        }
        composeTestRule.waitForIdle()
        return Triple(dark, background, surface)
    }

    @Test
    fun automaticRendersTheDarkSchemeWhenTheSystemIsDark() {
        val (dark, background, _) = renderedScheme(ThemePreference.SYSTEM, systemInDark = true)
        assertEquals(true, dark)
        assertEquals(KccDarkColors.pageBackground, background)
    }

    @Test
    fun automaticRendersTheLightSchemeWhenTheSystemIsLight() {
        val (dark, background, _) = renderedScheme(ThemePreference.SYSTEM, systemInDark = false)
        assertEquals(false, dark)
        assertEquals(KccLightColors.pageBackground, background)
    }

    /**
     * Seb's report, as an assertion: sunny day, device on dark, app must be
     * light. Also asserts the SURFACE colour, because that is what the shell's
     * translucent bottom bar tints itself with — "white and transparent rather
     * than dark transparent" is this value being #FFFFFF.
     */
    @Test
    fun lightStaysLightAndWhiteSurfacedWhileTheSystemIsDark() {
        val (dark, background, surface) = renderedScheme(ThemePreference.LIGHT, systemInDark = true)
        assertEquals(false, dark)
        assertEquals(KccLightColors.pageBackground, background)
        assertEquals(Color(0xFFFFFFFF), surface)
        assertNotEquals(KccDarkColors.surfaceBackground, surface)
    }

    @Test
    fun darkStaysDarkWhileTheSystemIsLight() {
        val (dark, background, surface) = renderedScheme(ThemePreference.DARK, systemInDark = false)
        assertEquals(true, dark)
        assertEquals(KccDarkColors.pageBackground, background)
        assertEquals(KccDarkColors.surfaceBackground, surface)
    }

    /** Survives process death: a brand-new store reads the persisted choice. */
    @Test
    fun theChoicePersistsAcrossStoreInstances() {
        ThemePreferenceStore(context).set(ThemePreference.DARK)
        assertEquals(ThemePreference.DARK, ThemePreferenceStore(context).preference.value)

        ThemePreferenceStore(context).set(ThemePreference.LIGHT)
        assertEquals(ThemePreference.LIGHT, ThemePreferenceStore(context).preference.value)
    }

    /**
     * Applying a preference must re-theme the RUNNING app — no restart. Collects
     * the store the way MainActivity does and asserts the rendered colours change
     * after a write, with the activity never recreated.
     */
    @Test
    fun changingThePreferenceRethemesWithoutARestart() {
        val store = ThemePreferenceStore(context)
        var background = Color.Unspecified

        composeTestRule.setContent {
            val preference by store.preference.collectAsState()
            KccTheme(darkTheme = preference.resolveDark(systemInDark = false)) {
                background = MaterialTheme.colorScheme.background
                Text("themed")
            }
        }

        composeTestRule.waitForIdle()
        assertEquals(KccLightColors.pageBackground, background)

        composeTestRule.runOnIdle { store.set(ThemePreference.DARK) }
        composeTestRule.waitForIdle()
        assertEquals(KccDarkColors.pageBackground, background)

        composeTestRule.runOnIdle { store.set(ThemePreference.LIGHT) }
        composeTestRule.waitForIdle()
        assertEquals(KccLightColors.pageBackground, background)
    }
}
