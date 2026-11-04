package com.kungsbackacarcommunity.app.location

import android.app.ForegroundServiceStartNotAllowedException
import android.os.Build

/** The platform can revoke its start allowance between startForegroundService and startForeground. */
internal object ForegroundPromotion {
    fun attempt(onRefused: () -> Unit, promote: () -> Unit): Boolean =
        try {
            promote()
            true
        } catch (failure: IllegalStateException) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                failure !is ForegroundServiceStartNotAllowedException
            ) {
                throw failure
            }
            onRefused()
            false
        }
}
