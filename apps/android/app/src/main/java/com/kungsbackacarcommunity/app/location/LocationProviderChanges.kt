package com.kungsbackacarcommunity.app.location

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Refresh cached access when Quick Settings changes providers without an Activity resume. */
@Composable
fun ObserveLocationProviderChanges(onChanged: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val owner = LocalLifecycleOwner.current
    val latestCallback by rememberUpdatedState(onChanged)
    DisposableEffect(context, owner) {
        val observer = LocationProviderChangeObserver(context) { latestCallback() }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            observer.close()
        }
    }
}

/** Protected system broadcasts only; registered while STARTED, including a refresh after a gap. */
internal class LocationProviderChangeObserver(
    private val context: Context,
    private val onChanged: () -> Unit,
) : DefaultLifecycleObserver, AutoCloseable {
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // A delivery queued before unregister must not refresh a stopped/disposed owner.
            if (registered && (intent.action == LocationManager.MODE_CHANGED_ACTION ||
                    intent.action == LocationManager.PROVIDERS_CHANGED_ACTION)) {
                onChanged()
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        if (registered) return
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter().apply {
                addAction(LocationManager.MODE_CHANGED_ACTION)
                addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        registered = true
        // Register first, then read the platform: changes while stopped or before
        // registration cannot leave the resumed screen on an old cached value.
        onChanged()
    }

    override fun onStop(owner: LifecycleOwner) = close()

    override fun onDestroy(owner: LifecycleOwner) = close()

    override fun close() {
        if (!registered) return
        registered = false
        context.unregisterReceiver(receiver)
    }
}
