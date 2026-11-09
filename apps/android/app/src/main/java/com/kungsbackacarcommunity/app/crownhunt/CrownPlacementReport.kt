package com.kungsbackacarcommunity.app.crownhunt

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.google.firebase.FirebaseApp
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.firebase.awaitOrThrow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal data class CrownPlacementTarget(
    val crownId: String,
    val source: String,
    val latitude: Double,
    val longitude: Double,
)

internal enum class CrownPlacementReason(val wire: String, @StringRes val label: Int) {
    Unsafe("unsafe", R.string.crownHunt_crownPlacementUnsafe),
    HardToReach("hard_to_reach", R.string.crownHunt_crownPlacementHardToReach),
    PrivateOrClosed("private_or_closed", R.string.crownHunt_crownPlacementPrivateOrClosed),
    Other("other", R.string.crownHunt_crownPlacementOther),
}

class CrownPlacementReporter internal constructor(
    private val send: suspend (CrownPlacementTarget, CrownPlacementReason, String) -> Unit,
) {
    private val reported = mutableStateMapOf<CrownPlacementTarget, Unit>()

    internal fun hasReported(target: CrownPlacementTarget): Boolean = target in reported

    internal suspend fun submit(target: CrownPlacementTarget, reason: CrownPlacementReason, comment: String) {
        send(target, reason, comment.trim())
        reported[target] = Unit
    }

    companion object {
        fun createIfAvailable(context: Context): CrownPlacementReporter? {
            if (FirebaseApp.getApps(context).isEmpty()) return null
            val callable = FirebaseFunctions.getInstance("europe-west1").getHttpsCallable("crownHunt-reportPlacement")
            return CrownPlacementReporter { target, reason, comment ->
                val response = callable.call(
                    mapOf(
                        "crownId" to target.crownId,
                        "source" to target.source,
                        "latitude" to target.latitude,
                        "longitude" to target.longitude,
                        "reason" to reason.wire,
                        "comment" to comment,
                    ),
                ).awaitOrThrow { "Crown placement report failed without a cause" }
                check((response?.getData() as? Map<*, *>)?.get("reported") == true)
            }
        }
    }
}

@Composable
internal fun CrownPlacementReportAction(target: CrownPlacementTarget, reporter: CrownPlacementReporter) {
    var open by remember(target) { mutableStateOf(false) }
    if (reporter.hasReported(target)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.crownHunt_crownPlacementReported))
        }
    } else {
        TextButton(
            onClick = { open = true },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("crown_report_placement"),
        ) {
            Icon(Icons.Outlined.Flag, contentDescription = null, modifier = Modifier.padding(end = KccSpacing.s2))
            Text(stringResource(R.string.crownHunt_crownPlacementReport))
        }
    }
    if (open) {
        CrownPlacementReportDialog(target, reporter, onDismiss = { open = false })
    }
}

@Composable
private fun CrownPlacementReportDialog(
    target: CrownPlacementTarget,
    reporter: CrownPlacementReporter,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var reason by remember { mutableStateOf<CrownPlacementReason?>(null) }
    var comment by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(stringResource(R.string.crownHunt_crownPlacementReport)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.crownHunt_crownPlacementReason))
                Column(Modifier.selectableGroup()) {
                    CrownPlacementReason.entries.forEach { option ->
                        Row(
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                                selected = reason == option,
                                enabled = !submitting,
                                role = Role.RadioButton,
                                onClick = { reason = option },
                            ),
                            horizontalArrangement = Arrangement.spacedBy(KccSpacing.s2),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = reason == option, onClick = null, enabled = !submitting)
                            Text(stringResource(option.label))
                        }
                    }
                }
                OutlinedTextField(
                    value = comment,
                    onValueChange = { if (it.length <= 400) comment = it },
                    label = { Text(stringResource(R.string.crownHunt_crownPlacementComment)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !submitting,
                    maxLines = 4,
                )
                error?.let {
                    Text(
                        stringResource(it),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = KccSpacing.s2).semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = reason != null && !submitting,
                onClick = {
                    val selected = reason ?: return@TextButton
                    submitting = true
                    error = null
                    scope.launch {
                        try {
                            reporter.submit(target, selected, comment)
                            onDismiss()
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = when ((failure as? FirebaseFunctionsException)?.code) {
                                FirebaseFunctionsException.Code.FAILED_PRECONDITION -> R.string.crownHunt_crownPlacementUnavailable
                                FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> R.string.crownHunt_crownPlacementLimit
                                else -> R.string.crownHunt_crownPlacementError
                            }
                        } finally {
                            submitting = false
                        }
                    }
                },
            ) {
                Text(stringResource(if (submitting) R.string.crownHunt_crownPlacementSending else R.string.crownHunt_crownPlacementSend))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) {
                Text(stringResource(R.string.crownHunt_crownPlacementCancel))
            }
        },
    )
}
