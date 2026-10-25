package com.kungsbackacarcommunity.app.drives

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.design.KccTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DriveMetricSummaryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun largeTextStacksMetricsAndKeepsBothFactsVisible() {
        compose.setContent {
            KccTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                    Box(Modifier.width(320.dp)) {
                        DriveMetricSummary(distance = "12 km", duration = "42 min")
                    }
                }
            }
        }
        val distance = compose.onNodeWithText("12 km", substring = true)
        val duration = compose.onNodeWithText("42 min", substring = true)
        distance.assertIsDisplayed()
        duration.assertIsDisplayed()
        assertTrue(duration.getUnclippedBoundsInRoot().top > distance.getUnclippedBoundsInRoot().bottom)
    }
}
