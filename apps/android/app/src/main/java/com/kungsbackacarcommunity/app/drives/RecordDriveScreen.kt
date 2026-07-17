package com.kungsbackacarcommunity.app.drives

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kungsbackacarcommunity.app.R
import kotlinx.coroutines.launch

/**
 * In-screen drive recorder (Phase 12 slice 12, write side).
 *
 * Flow: request ACCESS_FINE_LOCATION → start recording → live elapsed time +
 * point count → stop → explicit save/discard prompt (product rule: a drive is
 * stored only after an explicit user action) → save via the `drives-save`
 * callable or discard (nothing stored).
 *
 * Entry is member-gated ([isActiveMember]) and the `drives-save` callable is
 * member-gated backend-side too — but member gating is currently DISABLED on
 * both sides (config/MemberGating.kt and functions/src/shared/memberGating.ts),
 * so both currently admit any signed-in, non-suspended user.
 *
 * RE-LOCKING TRAP: these two gates must be re-locked TOGETHER, and they must
 * also stay aligned with whatever gates the recording ENTRY (today the live
 * -sharing path in AuthenticatedApp binds recording to `canShareLive`, which is
 * flag-gated only). If saving is member-gated while recording is not, a
 * non-member can record a drive and then be refused at save with no way to keep
 * it — an unrecoverable prompt. That was a real, reported bug, not a theory.
 *
 * Location and the callable are behind availability guards so
 * a config-less CI build (no google-services.json, no device GPS) never
 * crashes: [DriveLocationController.createIfAvailable] may return null and
 * fixes simply never arrive.
 */
@Composable
fun RecordDriveScreen(
    coordinator: DriveRecordingCoordinator,
    isActiveMember: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by coordinator.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val locationController =
        remember { DriveLocationController.createIfAvailable(context) }

    var title by remember { mutableStateOf("") }
    var permissionDenied by remember { mutableStateOf(false) }

    // Always release the foreground GPS updates when leaving the screen.
    DisposableEffect(locationController) {
        onDispose { locationController?.stop() }
    }

    fun beginRecording() {
        permissionDenied = false
        val controller = locationController
        if (controller == null) {
            // Location unavailable (config-less build / no device). Recording
            // still runs; no fixes arrive, so a summary-only save results.
            coordinator.start()
            return
        }
        val started =
            controller.start { latitude, longitude, timestampMs ->
                coordinator.addFix(latitude, longitude, timestampMs)
            }
        if (started) {
            // Only enter the Recording state once GPS updates are actually
            // flowing; otherwise the permission-denied UI (start button) shows.
            coordinator.start()
        } else {
            permissionDenied = true
        }
    }

    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                beginRecording()
            } else {
                permissionDenied = true
            }
        }

    fun onStartClicked() {
        val alreadyGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) {
            beginRecording()
        } else {
            permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun onStopClicked() {
        locationController?.stop()
        coordinator.stop()
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.savedDrives_recordTitle),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )

            if (!isActiveMember) {
                Text(
                    text = stringResource(R.string.savedDrives_memberRequired),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.savedDrives_recordBack))
                }
                return@Column
            }

            if (locationController == null) {
                Text(
                    text = stringResource(R.string.savedDrives_recordLocationUnavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            when (val current = state) {
                RecordingState.Idle,
                RecordingState.Discarded,
                RecordingState.Saved,
                -> {
                    if (current is RecordingState.Discarded) {
                        Text(
                            text = stringResource(R.string.savedDrives_noDriveSaved),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (current is RecordingState.Saved) {
                        Text(
                            text = stringResource(R.string.savedDrives_saveSuccess),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    if (permissionDenied) {
                        Text(
                            text = stringResource(R.string.savedDrives_recordPermissionDenied),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.savedDrives_recordPermissionRationale),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Button(
                        onClick = {
                            coordinator.reset()
                            onStartClicked()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text = stringResource(R.string.savedDrives_recordStart))
                    }
                }

                is RecordingState.Recording -> {
                    RecordingStats(current.elapsedMillis, current.pointCount)
                    Button(onClick = { onStopClicked() }, modifier = Modifier.fillMaxWidth()) {
                        Text(text = stringResource(R.string.savedDrives_recordStop))
                    }
                }

                is RecordingState.PromptSave ->
                    SavePrompt(
                        elapsedMillis = current.elapsedMillis,
                        pointCount = current.pointCount,
                        title = title,
                        onTitleChange = { title = it.take(DriveRecorder.DRIVE_TITLE_MAX_LENGTH) },
                        showError = false,
                        onSave = { scope.launch { coordinator.save(title) } },
                        onDiscard = { coordinator.discard() },
                    )

                is RecordingState.Failed ->
                    SavePrompt(
                        elapsedMillis = current.elapsedMillis,
                        pointCount = current.pointCount,
                        title = title,
                        onTitleChange = { title = it.take(DriveRecorder.DRIVE_TITLE_MAX_LENGTH) },
                        showError = true,
                        onSave = { scope.launch { coordinator.save(title) } },
                        onDiscard = { coordinator.discard() },
                    )

                RecordingState.Saving ->
                    Text(
                        text = stringResource(R.string.savedDrives_saveAction),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }

            TextButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.savedDrives_recordBack))
            }
        }
    }
}

@Composable
private fun RecordingStats(elapsedMillis: Long, pointCount: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text =
                    stringResource(R.string.savedDrives_recordElapsed) +
                        ": " + DriveFormatters.formatDuration(elapsedMillis / 1000L),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text =
                    stringResource(R.string.savedDrives_recordPoints) + ": " + pointCount,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SavePrompt(
    elapsedMillis: Long,
    pointCount: Int,
    title: String,
    onTitleChange: (String) -> Unit,
    showError: Boolean,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.savedDrives_promptTitle),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.savedDrives_promptBody),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.savedDrives_promptPrivacyNote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            RecordingStats(elapsedMillis, pointCount)

            OutlinedTextField(
                value = title,
                onValueChange = onTitleChange,
                singleLine = true,
                label = { Text(stringResource(R.string.savedDrives_recordTitleLabel)) },
                modifier = Modifier.fillMaxWidth(),
            )

            if (showError) {
                Text(
                    text = stringResource(R.string.savedDrives_saveError),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onSave()
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.savedDrives_saveAction))
            }
            TextButton(onClick = onDiscard, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(R.string.savedDrives_discardAction))
            }
        }
    }
}
