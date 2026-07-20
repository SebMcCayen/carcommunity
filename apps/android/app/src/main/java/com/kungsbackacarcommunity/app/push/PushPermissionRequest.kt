package com.kungsbackacarcommunity.app.push

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.kungsbackacarcommunity.app.notifications.PushPermissionStatus
import com.kungsbackacarcommunity.app.notifications.currentPushPermissionStatus

/**
 * POST_NOTIFICATIONS runtime permission (Android 13+).
 *
 * WHEN WE ASK. Not on first launch. A cold permission dialog before the member
 * has seen what the app does earns a reflexive "deny" — and on Android 13+ a
 * denial is effectively permanent (the second refusal makes the OS stop showing
 * the dialog at all, leaving only a trip to system settings). The ask is
 * therefore deferred until the member opens a surface that IS the notifications
 * feature — the chat hub or the notification inbox. At that point the request
 * is self-explaining: they are looking at messages and asking to be told about
 * new ones is the obvious next thing.
 *
 * ASKED ONCE. The gate persists that it has asked, so the dialog never reappears
 * on later visits. A member who declined keeps a fully working app — the durable
 * in-app inbox is unchanged and unaffected, they simply read it in the app. The
 * notification-settings screen still offers `openAppNotificationSettings` for
 * anyone who changes their mind, which is the only route once the OS has stopped
 * showing the dialog.
 *
 * Below API 33 there is no runtime permission; the gate is inert and delivery is
 * governed by the channel toggles alone.
 */
object PushPermissionGate {

    private const val PREFS = "kcc_push_permission"
    private const val KEY_ASKED = "post_notifications_asked"

    /** True on the versions where POST_NOTIFICATIONS must be requested at runtime. */
    val isRuntimePermissionRequired: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * Whether to show the system dialog now. Pure so the policy is testable
     * without an Android runtime.
     *
     * Asks only when the permission is actually required, is not already
     * granted, and has never been asked before.
     */
    fun shouldRequest(
        runtimePermissionRequired: Boolean,
        status: PushPermissionStatus,
        alreadyAsked: Boolean,
    ): Boolean =
        runtimePermissionRequired &&
            status != PushPermissionStatus.GRANTED &&
            !alreadyAsked

    fun hasAsked(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ASKED, false)

    fun markAsked(context: Context) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ASKED, true)
            .apply()
    }
}

/**
 * Requests POST_NOTIFICATIONS once, when composed, if [PushPermissionGate]
 * allows it. Drop this into a screen that justifies the ask (see the object's
 * KDoc) — it renders nothing.
 *
 * Whatever the member answers, the app carries on: the result is only recorded
 * so we do not ask twice. Denial is a supported, fully functional state.
 */
@Composable
fun RequestPushPermissionEffect() {
    if (!PushPermissionGate.isRuntimePermissionRequired) return

    val context = LocalContext.current
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            // Granted or not, we asked — never prompt again.
            PushPermissionGate.markAsked(context)
        }

    // Snapshot the decision once per entry to this screen: re-evaluating it
    // after the dialog resolves would re-launch it.
    val shouldRequest =
        remember {
            PushPermissionGate.shouldRequest(
                runtimePermissionRequired = PushPermissionGate.isRuntimePermissionRequired,
                status = currentPushPermissionStatus(context),
                alreadyAsked = PushPermissionGate.hasAsked(context),
            )
        }

    LaunchedEffect(shouldRequest) {
        if (shouldRequest) {
            PushPermissionGate.markAsked(context)
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
