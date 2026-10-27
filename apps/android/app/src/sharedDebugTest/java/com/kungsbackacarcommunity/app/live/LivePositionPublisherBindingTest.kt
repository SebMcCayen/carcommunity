package com.kungsbackacarcommunity.app.live

import android.Manifest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.location.LocationAccess
import com.kungsbackacarcommunity.app.location.foregroundLocationPermissions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LivePositionPublisherBindingTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun autoSessionStartsPublishingAfterPermissionIsGrantedWithoutStartingAnotherSession() {
        val access = mutableStateOf(LocationAccess.PERMISSION_DENIED)
        val started = mutableListOf<String>()
        compose.setContent {
            BindLivePositionPublisher(true, "member", "convoy-session", access.value, 0) { started += it }
        }
        compose.runOnIdle {
            assertTrue(started.isEmpty())
            access.value = LocationAccess.GRANTED
        }
        compose.runOnIdle { assertEquals(listOf("member"), started) }
    }

    @Test
    fun foregroundRecoveryAndReplacementSessionRetryWithoutAFalseSharingEmission() {
        val session = mutableStateOf("first-session")
        val recovery = mutableStateOf(0)
        val started = mutableListOf<String>()
        compose.setContent {
            BindLivePositionPublisher(true, "member", session.value, LocationAccess.GRANTED, recovery.value) {
                started += session.value
            }
        }
        compose.runOnIdle {
            assertEquals(listOf("first-session"), started)
            recovery.value++
        }
        compose.runOnIdle {
            assertEquals(listOf("first-session", "first-session"), started)
            session.value = "second-session"
        }
        compose.runOnIdle { assertEquals(listOf("first-session", "first-session", "second-session"), started) }
    }

    @Test
    fun disabledLocationRecoversButStopAndMissingSessionNeverStartPublishing() {
        val access = mutableStateOf(LocationAccess.SERVICES_OFF)
        val sharing = mutableStateOf(true)
        val session = mutableStateOf<String?>("convoy-session")
        val recovery = mutableStateOf(0)
        var starts = 0
        compose.setContent {
            BindLivePositionPublisher(sharing.value, "member", session.value, access.value, recovery.value) { starts++ }
        }
        compose.runOnIdle {
            assertEquals(1, starts)
            access.value = LocationAccess.GRANTED
        }
        compose.runOnIdle {
            assertEquals(2, starts)
            sharing.value = false
            recovery.value++
        }
        compose.runOnIdle {
            assertEquals(2, starts)
            sharing.value = true
            session.value = null
        }
        compose.runOnIdle { assertEquals(2, starts) }
    }

    @Test
    fun finePermissionWithServicesOffRegistersWithoutAProviderOrResumeEmission() {
        // Quick Settings may leave the Activity resumed and locationAccess
        // cached as SERVICES_OFF. The location service must already be running
        // with a fused request waiting for the switch to become enabled.
        val unrelatedUiTick = mutableStateOf(0)
        var starts = 0
        compose.setContent {
            unrelatedUiTick.value
            BindLivePositionPublisher(true, "member", "convoy-session", LocationAccess.SERVICES_OFF, 0) { starts++ }
        }
        compose.runOnIdle {
            assertEquals(1, starts)
            unrelatedUiTick.value++
        }
        compose.runOnIdle { assertEquals(1, starts) }
    }

    @Test
    fun ownershipChangeUsesOnlyTheCurrentUserAndCallbackChangesDoNotRestartTheService() {
        val uid = mutableStateOf("first-user")
        val useNewCallback = mutableStateOf(false)
        val started = mutableListOf<String>()
        compose.setContent {
            val label = if (useNewCallback.value) "new" else "old"
            BindLivePositionPublisher(true, uid.value, "session", LocationAccess.GRANTED, 0) { started += "$label:$it" }
        }
        compose.runOnIdle {
            assertEquals(listOf("old:first-user"), started)
            useNewCallback.value = true
        }
        compose.runOnIdle {
            assertEquals(listOf("old:first-user"), started)
            uid.value = "second-user"
        }
        compose.runOnIdle {
            assertEquals(listOf("old:first-user", "new:second-user"), started)
            uid.value = ""
        }
        compose.runOnIdle { assertEquals(2, started.size) }
    }

    @Test
    fun preciseLocationIntentRequestsCoarseAndFineTogether() {
        val intent = ActivityResultContracts.RequestMultiplePermissions().createIntent(
            ApplicationProvider.getApplicationContext(), foregroundLocationPermissions(),
        )
        assertEquals(
            setOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
            intent.getStringArrayExtra(ActivityResultContracts.RequestMultiplePermissions.EXTRA_PERMISSIONS)?.toSet(),
        )
    }
}
