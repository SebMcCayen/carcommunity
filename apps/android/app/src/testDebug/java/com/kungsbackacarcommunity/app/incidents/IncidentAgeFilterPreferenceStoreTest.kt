package com.kungsbackacarcommunity.app.incidents

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class IncidentAgeFilterPreferenceStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences("app_incident_age_filter", Context.MODE_PRIVATE)

    @Before fun clear() { prefs.edit().clear().commit() }

    @Test fun retiredNamesMigrateOnDiskAndSurviveStoreRecreation() {
        for (name in listOf("HOURS_6", "HOURS_12", "DAY_1")) {
            prefs.edit().putString("max_age", name).commit()
            assertEquals(IncidentAgeOption.DAYS_3, IncidentAgeFilterPreferenceStore(context).maxAge.value)
            assertEquals("DAYS_3", prefs.getString("max_age", null))
            assertEquals(IncidentAgeOption.DAYS_3, IncidentAgeFilterPreferenceStore(context).maxAge.value)
        }
    }

    @Test fun everySupportedSelectionUpdatesFlowAndSurvivesRestart() {
        val store = IncidentAgeFilterPreferenceStore(context)
        assertEquals(IncidentAgeOption.ALL, store.maxAge.value)
        for (option in IncidentAgeOption.entries) {
            store.set(option)
            assertEquals(option, store.maxAge.value)
            assertEquals(option, IncidentAgeFilterPreferenceStore(context).maxAge.value)
        }
    }

    @Test fun unknownStoredChoiceSafelyShowsEverything() {
        prefs.edit().putString("max_age", "unknown").commit()
        assertEquals(IncidentAgeOption.ALL, IncidentAgeFilterPreferenceStore(context).maxAge.value)
        assertEquals("ALL", prefs.getString("max_age", null))
    }
}
