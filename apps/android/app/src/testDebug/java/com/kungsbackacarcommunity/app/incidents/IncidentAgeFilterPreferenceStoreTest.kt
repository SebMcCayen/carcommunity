package com.kungsbackacarcommunity.app.incidents

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test fun confirmedNewAccountGetsOneWeekAndItSurvivesRestart() {
        val store = IncidentAgeFilterPreferenceStore(context)
        store.initializeForSignIn(isNewUser = true)
        assertEquals(IncidentAgeOption.WEEK_1, store.maxAge.value)
        assertEquals("WEEK_1", prefs.getString("max_age", null))
        assertEquals(IncidentAgeOption.WEEK_1, IncidentAgeFilterPreferenceStore(context).maxAge.value)
    }

    @Test fun existingAccountWithoutStoredChoiceKeepsAllEvenOnFreshInstall() {
        val store = IncidentAgeFilterPreferenceStore(context)
        store.initializeForSignIn(isNewUser = false)
        assertEquals(IncidentAgeOption.ALL, store.maxAge.value)
        assertFalse(prefs.contains("max_age"))
    }

    @Test fun initializationNeverOverwritesAnyExplicitChoiceIncludingAll() {
        for (option in IncidentAgeOption.entries) {
            val store = IncidentAgeFilterPreferenceStore(context)
            store.set(option)
            store.initializeForSignIn(isNewUser = true)
            store.initializeForSignIn(isNewUser = false)
            assertEquals(option, store.maxAge.value)
            assertEquals(option.name, prefs.getString("max_age", null))
            assertEquals(option, IncidentAgeFilterPreferenceStore(context).maxAge.value)
        }
    }

    @Test fun userCanChangeNewAccountDefaultAndReinitializationKeepsThatChoice() {
        val store = IncidentAgeFilterPreferenceStore(context)
        store.initializeForSignIn(isNewUser = true)
        store.set(IncidentAgeOption.ALL)
        val restored = IncidentAgeFilterPreferenceStore(context)
        restored.initializeForSignIn(isNewUser = true)
        assertEquals(IncidentAgeOption.ALL, restored.maxAge.value)
    }
}
