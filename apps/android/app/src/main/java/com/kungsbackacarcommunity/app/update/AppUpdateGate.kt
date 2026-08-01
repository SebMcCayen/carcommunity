package com.kungsbackacarcommunity.app.update

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.kungsbackacarcommunity.app.BuildConfig
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.design.KccSpacing
import com.kungsbackacarcommunity.app.navigation.runCatchingCancellable
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The startup update gate: the ONE thing that has to run ahead of the
 * authenticated shell, not alongside it.
 *
 * ## Why this exists
 *
 * The in-app update prompt ([AppUpdateDialog] and its wiring) lives deep inside
 * the authenticated shell, so it is composed at the same moment as — and no
 * earlier than — the shell's dozens of Firestore listeners and Cloud Function
 * coordinators. That is fine for the offer-a-newer-build case: a member on a
 * working build sees a dismissible prompt while the app keeps running.
 *
 * It is NOT fine for an outdated build that a backend contract has moved out
 * from under. Then one of those startup interactions can throw before the
 * prompt has a frame to render in, and the process dies on the first cold
 * launch — the exact "crashes the first time, says 'update' the second time"
 * report this gate answers. The update decision has to WIN that race, which
 * means it cannot be one more thing the shell composes: it has to gate whether
 * the shell composes at all.
 *
 * So a BLOCKING ([AppUpdateDecision.IMMEDIATE]) update is turned into a verdict
 * the shell's router consults BEFORE it builds the authenticated experience.
 * When the verdict is [AppStartupUpdateGate.FORCED] the shell is never composed
 * — [ForcedUpdateGate] is rendered instead — so none of the version-incompatible
 * startup wiring runs, and there is nothing left to crash.
 *
 * ## What still does NOT gate here
 *
 * Only a blocking update gates. A [AppUpdateDecision.FLEXIBLE] offer, a
 * finished-download restart, a non-Play install, an offline device, any error
 * — every one of those is [AppStartupUpdateGate.CLEAR], and the shell composes
 * exactly as before, with the in-shell prompt continuing to handle the flexible
 * and awaiting-restart cases. Forcing a build is a deliberate act: Play only
 * ever reports IMMEDIATE for a release published at the top of its
 * `inAppUpdatePriority` scale (see [AppUpdatePolicy]), so this gate stays inert
 * unless a release is deliberately marked mandatory.
 */
enum class AppStartupUpdateGate {
    /** Play has not answered yet. Hold the loading state; do not compose the shell. */
    CHECKING,

    /** Nothing blocks startup — compose the shell as normal. */
    CLEAR,

    /** A blocking update must be installed before the app may be used. */
    FORCED,
}

/**
 * The pure decision behind the gate. No Android, no Play, no clock — the whole
 * of "does this block startup" is a function of the update decision, so it is
 * unit-testable on its own.
 */
object AppStartupUpdate {

    /**
     * A blocking (IMMEDIATE) update is the only decision that gates startup.
     * FLEXIBLE, AWAITING_RESTART and NONE all let the app run.
     */
    fun gates(decision: AppUpdateDecision): Boolean =
        decision == AppUpdateDecision.IMMEDIATE

    /** The gate verdict for a completed check. */
    fun verdict(decision: AppUpdateDecision): AppStartupUpdateGate =
        if (gates(decision)) AppStartupUpdateGate.FORCED else AppStartupUpdateGate.CLEAR

    /**
     * Upper bound on how long the router waits on Play before composing the
     * shell anyway.
     *
     * The check overlaps the profile read the shell already waits for, and
     * Play answers from the Play Store's own local cache — usually in tens of
     * milliseconds — so an up-to-date member is not made to wait: the verdict
     * is [AppStartupUpdateGate.CLEAR] before the profile even resolves. The
     * bound only matters when Play is slow or unreachable (offline), where it
     * caps the hold and then proceeds rather than leaving a member staring at a
     * spinner. A non-Play install skips the wait entirely (see
     * [rememberAppStartupUpdateGate]).
     */
    const val CHECK_TIMEOUT_MILLIS: Long = 1_000L

    /**
     * The fail-safe heart of the gate, pulled out of the composable so the
     * "never crashes, never locks anyone out" property is enforced in one place
     * and testable without Compose or Play.
     *
     * The verdict is [AppStartupUpdateGate.CLEAR] (proceed) for every outcome
     * except a blocking update actually being reported:
     *  - the check throws anything non-cancellation -> CLEAR,
     *  - the check outruns [CHECK_TIMEOUT_MILLIS] -> CLEAR,
     *  - the check reports NONE / FLEXIBLE / AWAITING_RESTART -> CLEAR.
     *
     * Only [AppUpdateDecision.IMMEDIATE] yields [AppStartupUpdateGate.FORCED].
     * Cancellation is RE-THROWN, never swallowed, so leaving the composition (or
     * a changed source key) unwinds the check instead of being misread as "no
     * update" — the same discipline [AppUpdateCheck] keeps.
     *
     * @param check the update check to run, timed and guarded here. Defaulted so
     *   production passes nothing; a test passes a block that throws, hangs or
     *   returns a chosen reading.
     */
    suspend fun resolve(
        timeoutMillis: Long = CHECK_TIMEOUT_MILLIS,
        check: suspend () -> AppUpdateCheckResult,
    ): AppStartupUpdateGate {
        val decision =
            runCatchingCancellable {
                withTimeoutOrNull(timeoutMillis) { check() }?.decision
            }.getOrNull() ?: AppUpdateDecision.NONE
        return verdict(decision)
    }
}

/**
 * Runs the Play check once, off the shell's critical path, and reports the
 * gate verdict.
 *
 * Fail-safe by construction: [AppUpdateCheck] already swallows every Play
 * failure into [AppUpdateDecision.NONE], and a timeout, a null source (no Play
 * on this device) or a thrown check all resolve to [AppStartupUpdateGate.CLEAR]
 * — the app must never be locked out of itself by the very mechanism meant to
 * keep it working.
 */
@Composable
fun rememberAppStartupUpdateGate(
    source: AppUpdateSource?,
    nowMillis: () -> Long = { System.currentTimeMillis() },
): State<AppStartupUpdateGate> =
    produceState(
        initialValue =
            if (source == null) AppStartupUpdateGate.CLEAR else AppStartupUpdateGate.CHECKING,
        source,
    ) {
        val activeSource = source
        if (activeSource == null) {
            value = AppStartupUpdateGate.CLEAR
            return@produceState
        }
        // The whole "never crash, never lock out" property lives in
        // AppStartupUpdate.resolve — timed, guarded, cancellation-respecting — so
        // the composable only has to hand it the check to run. A forced update is
        // never throttled by a dismissal, so the gate needs no dismissal store;
        // passing none keeps it a read with no device state behind it.
        value =
            AppStartupUpdate.resolve {
                AppUpdateCheck.run(source = activeSource, dismissal = null, nowMillis = nowMillis())
            }
    }

/**
 * The full-screen, non-dismissible "you must update" experience shown in place
 * of the shell when the gate verdict is [AppStartupUpdateGate.FORCED].
 *
 * A screen rather than [AppUpdateDialog]'s dialog because there is no shell
 * behind it to dim — this IS the whole app until the update is taken. The
 * button hands off to Play's blocking IMMEDIATE flow, and every way that can
 * fail (a flow that will not start, a flow that comes back failed) falls back
 * to the Play listing, saying something only when even that has nowhere to go —
 * the same single recovery the in-shell prompt uses.
 */
@Composable
fun ForcedUpdateGate(
    source: AppUpdateSource?,
    applicationId: String = BuildConfig.APPLICATION_ID,
) {
    val context = LocalContext.current
    var storeUnavailable by remember { mutableStateOf(false) }

    val openStore = {
        PlayStoreLink.open(context, applicationId) { storeUnavailable = true }
    }

    val flowLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
        ) { result ->
            if (AppUpdateFlowResult.read(result.resultCode) == AppUpdateFlowOutcome.FAILED) {
                openStore()
            }
        }

    val onUpdate = {
        // Reset any earlier "store unavailable" notice: the member is trying
        // again, and Play may be reachable now.
        storeUnavailable = false
        val started = source?.startFlow(flowLauncher, immediate = true) == true
        if (!started) openStore()
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.fillMaxSize().padding(KccSpacing.s6),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.appUpdate_requiredTitle),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = stringResource(R.string.appUpdate_requiredMessage),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(top = KccSpacing.s3),
            )
            Button(onClick = onUpdate, modifier = Modifier.padding(top = KccSpacing.s6)) {
                Text(stringResource(R.string.appUpdate_update))
            }
            if (storeUnavailable) {
                Text(
                    text = stringResource(R.string.appUpdate_storeUnavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = KccSpacing.s4),
                )
            }
        }
    }
}
