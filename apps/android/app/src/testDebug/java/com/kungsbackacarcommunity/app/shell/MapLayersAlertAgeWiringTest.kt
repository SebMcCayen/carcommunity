package com.kungsbackacarcommunity.app.shell

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.design.KccTheme
import com.kungsbackacarcommunity.app.incidents.IncidentAgeFilterPreferenceStore
import com.kungsbackacarcommunity.app.incidents.IncidentAgeOption
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Exercise the production popup, not just an isolated preview of the age chips. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w390dp-h900dp-mdpi")
class MapLayersAlertAgeWiringTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun popupCommitsAgeToDeviceStoreAndHidingLayerRetainsTheSelection() {
        compose.activity.getSharedPreferences("app_incident_age_filter", Context.MODE_PRIVATE).edit().clear().commit()
        val store = IncidentAgeFilterPreferenceStore(compose.activity)
        val incidentsOn = mutableStateOf(true)
        compose.setContent {
            val maxAge by store.maxAge.collectAsState()
            KccTheme {
                Box(Modifier.requiredSize(320.dp, 800.dp)) {
                    MapLayersPopup(
                        incidentsOn = incidentsOn.value, onIncidentsChange = { incidentsOn.value = it },
                        trafikverketDataShown = false, trafficOn = false, nightMode = false, is3d = true,
                        onTrafficChange = {}, onNightModeChange = {}, on3dChange = {},
                        browsingZoom = 12.0, onBrowsingZoomChange = {},
                        incidentMaxAge = maxAge, onIncidentMaxAgeChange = store::set,
                        crownHuntParticipating = false, onCrownHuntParticipatingChange = {}, onDismiss = {},
                    )
                }
            }
        }
        val weekTag = "${MAP_HOME_LAYERS_ALERT_AGE_TAG}_WEEK_1"
        compose.onNodeWithTag(weekTag).performScrollTo().performClick().assertIsSelected()
        assertEquals(IncidentAgeOption.WEEK_1, IncidentAgeFilterPreferenceStore(compose.activity).maxAge.value)
        compose.onNodeWithTag(MAP_HOME_LAYERS_INCIDENTS_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(MAP_HOME_LAYERS_ALERT_AGE_TAG).assertDoesNotExist()
        compose.onNodeWithTag(MAP_HOME_LAYERS_INCIDENTS_TAG).performClick()
        compose.onNodeWithTag(weekTag).performScrollTo().assertIsSelected()
    }
}
