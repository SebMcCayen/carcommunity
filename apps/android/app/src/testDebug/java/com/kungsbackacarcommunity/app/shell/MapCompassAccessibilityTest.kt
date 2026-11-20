package com.kungsbackacarcommunity.app.shell

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapCompassAccessibilityTest {
    @get:Rule val compose = createComposeRule()

    @Test fun compassAnnouncesNextActionAndCurrentModeAfterATap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val mode = mutableStateOf(MapCompassMode.NorthUp)
        compose.setContent { KccTheme { CompassCircleControl(mode.value, 37f, { mode.value = it }) } }
        compose.onNodeWithContentDescription(context.getString(R.string.shell_compassSwitchToCourse))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                context.getString(R.string.shell_compassNorthUp)))
            .performClick()
        compose.onNodeWithContentDescription(context.getString(R.string.shell_compassSwitchToNorth))
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
                context.getString(R.string.shell_compassCourseUp)))
    }
}
