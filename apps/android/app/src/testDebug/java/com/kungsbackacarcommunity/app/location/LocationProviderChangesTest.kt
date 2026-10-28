package com.kungsbackacarcommunity.app.location

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Handler
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.kungsbackacarcommunity.app.live.BindLivePositionPublisher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LocationProviderChangesTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun receiverIsStartedScopedNonExportedAndRefreshesAfterEachGap() {
        val context = TrackingContext(compose.activity.applicationContext)
        val owner = TestOwner()
        var refreshes = 0
        val observer = LocationProviderChangeObserver(context) { refreshes++ }
        compose.runOnIdle {
            owner.lifecycle.addObserver(observer)
            assertTrue(context.registrations.isEmpty())
            owner.registry.currentState = Lifecycle.State.STARTED
            assertEquals(1, refreshes)
            assertEquals(1, context.registrations.size)
            val registration = context.registrations.single()
            assertEquals(ContextCompat.RECEIVER_NOT_EXPORTED, registration.flags)
            assertEquals(2, registration.filter.countActions())
            assertTrue(registration.filter.hasAction(LocationManager.MODE_CHANGED_ACTION))
            assertTrue(registration.filter.hasAction(LocationManager.PROVIDERS_CHANGED_ACTION))
            observer.onStart(owner) // Duplicate delivery must not double-register.
            owner.registry.currentState = Lifecycle.State.RESUMED
            assertEquals(1, refreshes)
            assertEquals(1, context.registrations.size)
            owner.registry.currentState = Lifecycle.State.CREATED
            assertEquals(1, context.unregisters)
            owner.registry.currentState = Lifecycle.State.STARTED
            assertEquals(2, refreshes) // Covers any provider changes while stopped.
            assertEquals(2, context.registrations.size)
            owner.registry.currentState = Lifecycle.State.DESTROYED
            observer.close()
            assertEquals(2, context.unregisters)
        }
    }

    @Test fun onlyProviderActionsRefreshAndQueuedDeliveriesAfterStopAreIgnored() {
        val context = TrackingContext(compose.activity.applicationContext)
        val owner = TestOwner()
        var refreshes = 0
        val observer = LocationProviderChangeObserver(context) { refreshes++ }
        compose.runOnIdle {
            owner.lifecycle.addObserver(observer)
            owner.registry.currentState = Lifecycle.State.STARTED
            val receiver = context.registrations.single().receiver
            receiver.onReceive(context, Intent(LocationManager.MODE_CHANGED_ACTION))
            receiver.onReceive(context, Intent(LocationManager.PROVIDERS_CHANGED_ACTION))
            receiver.onReceive(context, Intent("test.unrelated"))
            assertEquals(3, refreshes)
            owner.registry.currentState = Lifecycle.State.CREATED
            receiver.onReceive(context, Intent(LocationManager.MODE_CHANGED_ACTION))
            assertEquals(3, refreshes)
            observer.close()
            assertEquals(1, context.unregisters)
        }
    }

    @Test fun recompositionUsesFreshCallbackWithoutReregisteringAndDisposalUnregisters() {
        val context = TrackingContext(compose.activity.applicationContext)
        val owner = TestOwner()
        val version = mutableStateOf("first")
        val mounted = mutableStateOf(true)
        val observed = mutableListOf<String>()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalLifecycleOwner provides owner) {
                if (mounted.value) {
                    val capturedVersion = version.value
                    ObserveLocationProviderChanges { observed += capturedVersion }
                }
            }
        }
        compose.runOnIdle {
            assertEquals(listOf("first"), observed)
            version.value = "second"
        }
        compose.runOnIdle {
            assertEquals(1, context.registrations.size)
            context.registrations.single().receiver.onReceive(context, Intent(LocationManager.MODE_CHANGED_ACTION))
            assertEquals(listOf("first", "second"), observed)
            mounted.value = false
        }
        compose.runOnIdle {
            assertEquals(1, context.unregisters)
            context.registrations.single().receiver.onReceive(context, Intent(LocationManager.PROVIDERS_CHANGED_ACTION))
            owner.registry.currentState = Lifecycle.State.DESTROYED
            assertEquals(listOf("first", "second"), observed)
            assertEquals(1, context.unregisters)
        }
    }

    @Test fun replacingLifecycleOwnerClosesOldRegistrationAndRefreshesNewOwner() {
        val context = TrackingContext(compose.activity.applicationContext)
        val first = TestOwner()
        val second = TestOwner()
        val current = mutableStateOf<LifecycleOwner>(first)
        var refreshes = 0
        compose.runOnIdle {
            first.registry.currentState = Lifecycle.State.STARTED
            second.registry.currentState = Lifecycle.State.STARTED
        }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalLifecycleOwner provides current.value) {
                ObserveLocationProviderChanges { refreshes++ }
            }
        }
        compose.runOnIdle { current.value = second }
        compose.runOnIdle {
            assertEquals(2, context.registrations.size)
            assertEquals(1, context.unregisters)
            assertEquals(2, refreshes)
            context.registrations.first().receiver.onReceive(context, Intent(LocationManager.MODE_CHANGED_ACTION))
            assertEquals(2, refreshes)
            second.registry.currentState = Lifecycle.State.DESTROYED
            assertEquals(2, context.unregisters)
        }
    }

    @Test fun providerChangeRecoversCachedOffAccessWithoutResumeOrRecoveryKeyChange() {
        val context = TrackingContext(compose.activity.applicationContext)
        val owner = TestOwner()
        val manager = context.getSystemService(LocationManager::class.java)
        val probe = mutableIntStateOf(0)
        val starts = mutableListOf<LocationAccess>()
        val observed = mutableListOf<LocationAccess>()
        compose.runOnIdle {
            shadowOf(compose.activity.application).grantPermissions(
                Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION,
            )
            shadowOf(manager).setLocationEnabled(false)
            owner.registry.currentState = Lifecycle.State.RESUMED
            assertEquals(LocationAccess.SERVICES_OFF, currentLocationAccess(context))
        }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalLifecycleOwner provides owner) {
                ObserveLocationProviderChanges { probe.intValue++ }
                val access = remember(probe.intValue) { currentLocationAccess(context) }
                BindLivePositionPublisher(true, "member", "session", access, recoveryKey = 0) { starts += access }
                androidx.compose.runtime.SideEffect { observed += access }
            }
        }
        compose.runOnIdle {
            assertTrue(starts.isEmpty())
            assertEquals(LocationAccess.SERVICES_OFF, observed.last())
            shadowOf(manager).setLocationEnabled(true)
            // Model the protected system delivery; native coverage uses cmd location.
            context.registrations.single().receiver.onReceive(context, Intent(LocationManager.PROVIDERS_CHANGED_ACTION))
        }
        compose.runOnIdle {
            assertEquals(Lifecycle.State.RESUMED, owner.lifecycle.currentState)
            assertEquals(LocationAccess.GRANTED, observed.last())
            assertEquals(listOf(LocationAccess.GRANTED), starts)
            assertEquals(1, context.registrations.size)
            owner.registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    @Test fun providerBroadcastCannotTurnDeniedPermissionIntoGrantedAccess() {
        val context = TrackingContext(compose.activity.applicationContext)
        val owner = TestOwner()
        val probe = mutableIntStateOf(0)
        var starts = 0
        compose.runOnIdle {
            shadowOf(compose.activity.application).denyPermissions(
                Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION,
            )
            shadowOf(context.getSystemService(LocationManager::class.java)).setLocationEnabled(true)
            owner.registry.currentState = Lifecycle.State.STARTED
        }
        compose.setContent {
            CompositionLocalProvider(LocalContext provides context, LocalLifecycleOwner provides owner) {
                ObserveLocationProviderChanges { probe.intValue++ }
                val access = remember(probe.intValue) { currentLocationAccess(context) }
                BindLivePositionPublisher(true, "member", "session", access, 0) { starts++ }
            }
        }
        compose.runOnIdle {
            context.registrations.single().receiver.onReceive(context, Intent(LocationManager.MODE_CHANGED_ACTION))
        }
        compose.runOnIdle {
            assertEquals(LocationAccess.PERMISSION_DENIED, currentLocationAccess(context))
            assertEquals(0, starts)
            owner.registry.currentState = Lifecycle.State.DESTROYED
        }
    }

    private class TestOwner : LifecycleOwner {
        val registry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle get() = registry
    }

    private class TrackingContext(base: Context) : ContextWrapper(base) {
        data class Registration(val receiver: BroadcastReceiver, val filter: IntentFilter, val flags: Int)
        val registrations = mutableListOf<Registration>()
        var unregisters = 0
        // Keep the application-context seam observable without wrapping a real Activity.
        override fun getApplicationContext(): Context = this
        override fun registerReceiver(
            receiver: BroadcastReceiver?, filter: IntentFilter, broadcastPermission: String?, scheduler: Handler?, flags: Int,
        ): Intent? {
            registrations += Registration(checkNotNull(receiver), filter, flags)
            return super.registerReceiver(receiver, filter, broadcastPermission, scheduler, flags)
        }
        override fun unregisterReceiver(receiver: BroadcastReceiver) {
            unregisters++
            super.unregisterReceiver(receiver)
        }
    }
}
