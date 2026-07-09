package com.kungsbackacarcommunity.app.feedback

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.kungsbackacarcommunity.app.BuildConfig
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * "Report a problem" integration route. Auto-collects the client context
 * (appVersion/osVersion/deviceModel) from BuildConfig + [Build] and wires the
 * screen to the coordinator. Opens a created issue in the browser on request.
 */
@Composable
fun FeedbackReportRoute(
    coordinator: FeedbackCoordinator?,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clientContext = remember { currentFeedbackClientContext() }
    val status by
        (coordinator?.status ?: flowOf(FeedbackStatus.Idle))
            .collectAsState(initial = FeedbackStatus.Idle)

    FeedbackReportScreen(
        status = status,
        clientContext = clientContext,
        onSubmit = { input -> coordinator?.let { c -> scope.launch { c.submit(input) } } },
        onViewIssue = { url -> openUrl(context, url) },
        onBack = {
            coordinator?.reset()
            onBack()
        },
    )
}

/** appVersion from BuildConfig; osVersion/deviceModel from [Build]. */
private fun currentFeedbackClientContext(): FeedbackClientContext =
    FeedbackClientContext(
        appVersion = BuildConfig.VERSION_NAME,
        osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
    )

private fun openUrl(context: Context, url: String) {
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: ActivityNotFoundException) {
        // No browser available — nothing to open; the report was already filed.
    }
}
