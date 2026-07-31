package com.kungsbackacarcommunity.app.update

import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Play's int codes, folded into the model — asserted against the REAL
 * `UpdateAvailability` / `InstallStatus` constants rather than a restatement of
 * them, so a Play library that renumbers them fails here instead of silently
 * changing what the app does.
 *
 * The pair this exists for: `DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS` is
 * reported ALONGSIDE `PENDING` / `DOWNLOADING` / `INSTALLING` — the two are
 * independent axes, and that combination is the ordinary shape of a blocking
 * update the app is required to resume.
 */
class PlayAppUpdateSourceTest {

    @Test
    fun `Play's update-availability codes fold to the three cases acted on`() {
        assertEquals(
            PlayUpdateState.AVAILABLE,
            PlayAppUpdateSource.updateState(UpdateAvailability.UPDATE_AVAILABLE),
        )
        assertEquals(
            PlayUpdateState.IN_PROGRESS,
            PlayAppUpdateSource.updateState(
                UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS,
            ),
        )
        assertEquals(
            PlayUpdateState.NOTHING,
            PlayAppUpdateSource.updateState(UpdateAvailability.UPDATE_NOT_AVAILABLE),
        )
        assertEquals(
            PlayUpdateState.NOTHING,
            PlayAppUpdateSource.updateState(UpdateAvailability.UNKNOWN),
        )
    }

    @Test
    fun `Play's install-status codes fold to the three cases acted on`() {
        assertEquals(
            PlayInstallState.DOWNLOADED,
            PlayAppUpdateSource.installState(InstallStatus.DOWNLOADED),
        )
        for (status in listOf(
            InstallStatus.PENDING,
            InstallStatus.DOWNLOADING,
            InstallStatus.INSTALLING,
        )) {
            assertEquals(
                "status $status is work in flight",
                PlayInstallState.WORKING,
                PlayAppUpdateSource.installState(status),
            )
        }
        for (status in listOf(
            InstallStatus.UNKNOWN,
            InstallStatus.INSTALLED,
            InstallStatus.FAILED,
            InstallStatus.CANCELED,
        )) {
            assertEquals(
                "status $status is nothing in flight",
                PlayInstallState.IDLE,
                PlayAppUpdateSource.installState(status),
            )
        }
    }

    /**
     * End to end on the exact readings Play produces for an interrupted
     * blocking update: the availability axis says an update this app started is
     * running, the install axis says how far it has got, and neither may
     * cancel the other out.
     */
    @Test
    fun `a developer-triggered update mid-flight resumes the blocking flow`() {
        for (status in listOf(
            InstallStatus.PENDING,
            InstallStatus.DOWNLOADING,
            InstallStatus.INSTALLING,
        )) {
            val availability =
                AppUpdateAvailability.fromPlay(
                    updateState =
                        PlayAppUpdateSource.updateState(
                            UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS,
                        ),
                    installState = PlayAppUpdateSource.installState(status),
                    availableVersionCode = 42,
                    isFlexibleAllowed = true,
                    isImmediateAllowed = true,
                    priority = AppUpdateAvailability.MAX_PRIORITY,
                )
            assertTrue(
                "installStatus $status must not swallow the resume",
                availability?.isImmediateInProgress ?: false,
            )
            assertEquals(
                "installStatus $status",
                AppUpdateDecision.IMMEDIATE,
                AppUpdatePolicy.decide(availability, dismissal = null, nowMillis = 0L),
            )
        }
    }

    /**
     * The same readings on an ordinary release: Play reports a flexible
     * background download as in progress too, and that must stay a background
     * download rather than becoming a full-screen takeover mid-drive.
     */
    @Test
    fun `a flexible download mid-flight is left downloading`() {
        for (status in listOf(
            InstallStatus.PENDING,
            InstallStatus.DOWNLOADING,
            InstallStatus.INSTALLING,
        )) {
            val availability =
                AppUpdateAvailability.fromPlay(
                    updateState =
                        PlayAppUpdateSource.updateState(
                            UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS,
                        ),
                    installState = PlayAppUpdateSource.installState(status),
                    availableVersionCode = 42,
                    isFlexibleAllowed = true,
                    isImmediateAllowed = true,
                    priority = 0,
                )
            assertEquals(
                "installStatus $status",
                AppUpdateDecision.NONE,
                AppUpdatePolicy.decide(availability, dismissal = null, nowMillis = 0L),
            )
        }
    }
}
