package com.kungsbackacarcommunity.app.shell

import android.view.MotionEvent
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w390dp-h900dp-mdpi")
class MapMarkerTapsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun pinchStartingOnMarkerReachesNativeMapAndOnlySingleTapSelects() {
        val taps = MapMarkerTaps()
        val events = mutableListOf<Int>()
        var selections = 0
        compose.setContent {
            Box(Modifier.size(240.dp).testTag("map")) {
                Box(Modifier.fillMaxSize().routeMapMarkerTaps(taps)) {
                    AndroidView(modifier = Modifier.fillMaxSize(), factory = { context ->
                        object : View(context) {
                            override fun onTouchEvent(event: MotionEvent): Boolean {
                                events.add(event.actionMasked)
                                return true
                            }
                        }
                    })
                }
                CompositionLocalProvider(LocalMapMarkerTaps provides taps) {
                    Box(Modifier.align(Alignment.Center).size(48.dp).mapMarkerTap(MapMarkerLayer.Static) { selections++ })
                }
            }
        }
        val map = compose.onNodeWithTag("map")
        map.performTouchInput {
            down(0, center - Offset(8f, 0f))
            down(1, center + Offset(8f, 0f))
            updatePointerTo(0, center - Offset(60f, 0f))
            updatePointerTo(1, center + Offset(60f, 0f))
            move()
            updatePointerTo(0, center - Offset(4f, 0f))
            updatePointerTo(1, center + Offset(4f, 0f))
            move()
            up(1)
            up(0)
        }
        compose.runOnIdle {
            assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN,
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_UP,
                MotionEvent.ACTION_UP), events)
            assertEquals(0, selections)
            events.clear()
        }
        map.performTouchInput { click(center) }
        compose.runOnIdle {
            assertEquals(1, selections)
            assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_CANCEL), events)
            events.clear()
        }
        map.performTouchInput { click(Offset(8f, 8f)) }
        compose.runOnIdle {
            assertEquals(1, selections)
            assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP), events)
        }
    }
}
