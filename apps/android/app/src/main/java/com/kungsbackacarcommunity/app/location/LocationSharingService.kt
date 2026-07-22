package com.kungsbackacarcommunity.app.location

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.kungsbackacarcommunity.app.MainActivity
import com.kungsbackacarcommunity.app.R
import com.kungsbackacarcommunity.app.live.FirebaseLiveLocationRepository
import com.kungsbackacarcommunity.app.live.LiveLocation
import com.kungsbackacarcommunity.app.live.LiveLocationRepository
import com.kungsbackacarcommunity.app.live.LiveSessionInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Foreground service that keeps a live-location session publishing while the app
 * is backgrounded, the screen is locked, or the phone is in a mount.
 *
 * ## Why this exists
 * Live sharing, convoy tracking, the off-screen member arrows and convoy focus
 * mode all consume `liveLocation/{uid}/latest`. Without a foreground service the
 * fused-location updates stop the moment the process leaves the foreground, so
 * every one of those features silently degrades in exactly the situation they
 * were built for — a driver with the phone in a mount or a pocket.
 *
 * ## Permissions: no ACCESS_BACKGROUND_LOCATION
 * A foreground service declared `android:foregroundServiceType="location"` may
 * access location for as long as it runs WITHOUT `ACCESS_BACKGROUND_LOCATION` —
 * that permission is only required for location access with no foreground
 * service at all. This is the standard navigation/fitness-app pattern, and it
 * keeps the app out of Google Play's background-location declaration review.
 * The manifest therefore declares only FOREGROUND_SERVICE,
 * FOREGROUND_SERVICE_LOCATION and the existing fine/coarse location permissions.
 *
 * ## Single source of truth
 * The service is NOT a second writer and holds NO session state of its own. It
 * publishes through the same [LiveLocationRepository] the in-app path uses
 * (live.updatePosition → `liveLocation/{uid}/latest`) and derives its entire
 * lifetime by observing the same `liveLocation/{uid}/session` node the UI
 * observes. Every stop condition — manual stop, expiry, sign-out, a remote end —
 * is one shape: the session stopped being active, so the service stops. The
 * decision itself lives in the pure, unit-tested [LiveSharingLifecycle].
 *
 * ## Restart behaviour
 * [START_REDELIVER_INTENT] rather than `START_STICKY`: the uid arrives in the
 * intent, and `START_STICKY` redelivers a NULL intent, which would leave a
 * restarted service with nothing to observe. Redelivery hands the original
 * intent back, the service re-reads the session from the server, and resumes
 * only if it is still active — a session that ended while the process was dead
 * results in an immediate self-stop rather than resurrected sharing. There is
 * deliberately NO boot receiver: sharing must never silently resume after a
 * reboot, which the user did not consent to and would not see coming.
 *
 * ## What cannot be verified here
 * No device is available. Real GPS fixes, the runtime location/notification
 * grants, Doze and app-standby behaviour, OEM background killers, and genuine
 * process death + intent redelivery all need hardware. The pure logic
 * ([LiveSharingLifecycle], [BackgroundLocation]) is unit-tested; the framework
 * plumbing around it is not exercisable in CI.
 */
class LocationSharingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var repository: LiveLocationRepository? = null
    private var fusedClient: FusedLocationProviderClient? = null
    private var sessionJob: Job? = null
    private var ownerUid: String? = null

    /** Serialises the ticker and the session observer; see [decideAndApply]. */
    private val lifecycleMutex = Mutex()

    /**
     * The ceiling anchor is persisted, so a process kill plus the
     * START_REDELIVER_INTENT restart cannot hand a restarted service a fresh
     * 4h05m budget. See [SharingAnchorStore].
     */
    private val lifecycle by lazy {
        LiveSharingLifecycle(anchorStore = PersistedSharingAnchorStore(applicationContext))
    }

    /**
     * Last sample SUBMITTED for publication, for the movement/heartbeat throttle.
     *
     * Recorded when the publish is dispatched, not when it succeeds — `false`
     * here would mean a failing backend retried at the full fix cadence
     * (every 5 s) for as long as the failure lasted, which is the opposite of
     * what a throttle is for. A lost publish costs at most one heartbeat before
     * the next sample goes out anyway.
     */
    private var lastSubmittedAtMillis: Long? = null
    private var lastSubmittedLatitude: Double? = null
    private var lastSubmittedLongitude: Double? = null

    /** Minutes currently rendered in the notification; -1 = nothing posted yet. */
    private var shownRemainingMinutes: Long = -1L

    /**
     * Detects "parked for a while" from the raw fix stream to drive the
     * stationary prompt / auto-stop. Pure state machine; guarded by
     * [stationaryLock] because [onFix] runs on the main looper (the fused-location
     * callback) while [decide] runs on the IO ticker.
     */
    private val stationaryMonitor = StationarySharingMonitor()
    private val stationaryLock = Any()

    /**
     * Last observed session, kept so the IO ticker can compute
     * [LiveLocation.isExpiringSoon] without reaching into the private
     * [LiveSharingLifecycle] state. Volatile: written on the observer coroutine,
     * read on the ticker coroutine.
     */
    @Volatile
    private var lastSession: LiveSessionInfo? = null

    /** Which prompt (if any) the ongoing notification is currently offering. */
    private var promptMode: SharingPromptMode = SharingPromptMode.NORMAL

    /** Prompt mode last rendered, so a mode change forces a re-post; null = none yet. */
    private var shownPromptMode: SharingPromptMode? = null

    /** Most recent onStartCommand startId; see [latestStartId] usage in auto-stop. */
    private var latestStartId: Int = 0

    private val locationCallback =
        object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val repo = repository ?: return
                val fix = result.lastLocation ?: return
                val now = System.currentTimeMillis()
                // Feed EVERY raw fix to the stationary monitor (before the publish
                // throttle): movement detection is about where the device actually
                // is, not about which fixes we chose to publish. A fix beyond the
                // movement threshold re-anchors it and cancels any pending
                // stationary prompt/auto-stop.
                synchronized(stationaryLock) {
                    stationaryMonitor.onFix(fix.latitude, fix.longitude, now)
                }
                if (!BackgroundLocation.shouldPublish(
                        lastSubmittedAtMillis = lastSubmittedAtMillis,
                        lastSubmittedLatitude = lastSubmittedLatitude,
                        lastSubmittedLongitude = lastSubmittedLongitude,
                        latitude = fix.latitude,
                        longitude = fix.longitude,
                        nowMillis = now,
                    )
                ) {
                    // Parked at a meet: GPS jitter is not worth a callable round
                    // trip. The heartbeat still publishes every
                    // BackgroundLocation.STATIONARY_HEARTBEAT_MS (3 min while
                    // parked — the main data saver).
                    return
                }
                lastSubmittedAtMillis = now
                lastSubmittedLatitude = fix.latitude
                lastSubmittedLongitude = fix.longitude

                val coordinate =
                    BackgroundLocation.buildCoordinate(
                        latitude = fix.latitude,
                        longitude = fix.longitude,
                        timeMillis = fix.time,
                        accuracyMeters = if (fix.hasAccuracy()) fix.accuracy.toDouble() else null,
                        bearingDegrees = if (fix.hasBearing()) fix.bearing.toDouble() else null,
                        speedMps = if (fix.hasSpeed()) fix.speed.toDouble() else null,
                    )
                scope.launch {
                    try {
                        repo.updatePosition(coordinate)
                    } catch (c: CancellationException) {
                        // Preserve cooperative cancellation — never swallow.
                        throw c
                    } catch (_: Exception) {
                        // A single failed publish must not tear down sharing; the
                        // next fix retries. Details may reference the payload —
                        // never logged.
                    }
                }
            }
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Record the most recent startId on EVERY path, before any early return.
        // The stationary auto-stop tears down via stopSelf(latestStartId), and
        // stopSelf(id) only stops the service when `id` is the latest start the
        // system delivered. The extend / "still sharing" / repeat-start branches
        // below all return early without reaching the full-start path, so if this
        // were set only there, a notification-action start would leave
        // latestStartId stale and the auto-stop's stopSelf(staleId) would be a
        // no-op (the service would keep running / the wrong delivery would stop).
        latestStartId = startId

        if (intent?.action == ACTION_STOP_SHARING) {
            // The notification's "Stop sharing" action: end the session on the
            // backend too, so stopping from the shade is a real stop and not
            // just a service that quietly walked away from an open session.
            // The uid it was built for comes along so we can verify the callable
            // would act on the right account — see stopSharingAndSelf.
            stopSharingAndSelf(
                expectedUid = intent.getStringExtra(EXTRA_UID),
                startId = startId,
            )
            return START_NOT_STICKY
        }

        val uid = intent?.getStringExtra(EXTRA_UID)
        if (uid.isNullOrBlank()) {
            // Nothing to observe (should be unreachable — the controller always
            // supplies a uid). Never hold a foreground service we cannot verify.
            stopSelf()
            return START_NOT_STICKY
        }

        val alreadyRunning = ownerUid == uid && sessionJob?.isActive == true

        // Non-terminal replies to a notification prompt (Extend / "still sharing").
        // Handled ONLY on the already-running instance: a fresh instance means the
        // process died and this is a stale/redelivered intent, so we must NOT
        // re-perform the side effect (a crash-loop re-firing ACTION_EXTEND could
        // otherwise keep re-extending past the cap). In that case we fall through
        // and simply resume normal sharing, bounded by the session's own expiry.
        if (intent?.action == ACTION_EXTEND_SHARING) {
            if (alreadyRunning) {
                handleExtend(uid)
                return START_REDELIVER_INTENT
            }
        } else if (intent?.action == ACTION_STILL_SHARING) {
            if (alreadyRunning) {
                handleStillSharing()
                return START_REDELIVER_INTENT
            }
        } else if (alreadyRunning) {
            // Already running for this user: a repeat start is a no-op rather than
            // a second observer + a second location request.
            return START_REDELIVER_INTENT
        }

        val repo = FirebaseLiveLocationRepository.createIfAvailable(applicationContext)
        if (repo == null) {
            // Firebase not configured (e.g. CI / config-less build). Nothing to
            // publish to, so do not hold a foreground service — stop BEFORE
            // showing any foreground notification.
            stopSelf()
            return START_NOT_STICKY
        }
        repository = repo
        ownerUid = uid

        // Reset the per-run throttle and notification state. stopSelf() is
        // asynchronous, so a fresh start can land on this SAME instance before
        // onDestroy() has run. Carrying the previous run's last-published sample
        // over would let shouldPublish() throttle the new session's first fix —
        // breaking its documented "the first fix of a session always publishes"
        // contract and leaving the user invisible to viewers for up to a
        // heartbeat while they believe they are sharing.
        lastSubmittedAtMillis = null
        lastSubmittedLatitude = null
        lastSubmittedLongitude = null
        shownRemainingMinutes = -1L
        lastSession = null
        promptMode = SharingPromptMode.NORMAL
        shownPromptMode = null
        synchronized(stationaryLock) { stationaryMonitor.reset() }

        // Post the notification and enter the foreground FIRST: the platform
        // requires startForeground within a few seconds of the start request.
        postNotification(remainingSeconds = null, foreground = true)
        // A live-share session now owns the shade with its own ongoing
        // notification. The separate "app is active" notice reads this and
        // suppresses itself so the two never compete — see
        // AppActiveNotificationController.
        sessionActive = true

        if (!startLocationUpdates()) return START_NOT_STICKY

        observeSession(repo, uid)
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        // Every stop path (manual, expiry, sign-out, remote end, permission
        // refusal) funnels through stopSelf() -> onDestroy, so clearing the flag
        // here covers them all. The "app is active" notice re-evaluates on the
        // next foreground and reappears once the shade is free.
        sessionActive = false
        sessionJob?.cancel()
        sessionJob = null
        fusedClient?.removeLocationUpdates(locationCallback)
        fusedClient = null
        repository = null
        ownerUid = null
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Requests fused-location updates. Returns false (and stops the service) when
     * the runtime location permission is absent.
     */
    private fun startLocationUpdates(): Boolean {
        val client = LocationServices.getFusedLocationProviderClient(applicationContext)
        fusedClient = client
        val request =
            LocationRequest.Builder(
                // Driving needs true GPS: the convoy arrows and focus mode point
                // at a heading, and a network-derived fix is too coarse and too
                // laggy to aim with. The battery cost is bounded by the session's
                // own 1/2/4-hour expiry and by the publish throttle below.
                Priority.PRIORITY_HIGH_ACCURACY,
                BackgroundLocation.UPDATE_INTERVAL_MS,
            )
                .setMinUpdateIntervalMillis(BackgroundLocation.MIN_UPDATE_INTERVAL_MS)
                // Do not sit on the first fix waiting for it to sharpen; a
                // slightly coarse marker now beats an accurate one 30 s late.
                .setWaitForAccurateLocation(false)
                .build()
        return try {
            client.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            true
        } catch (_: SecurityException) {
            // Location permission not granted at runtime. Cannot share — stop
            // cleanly rather than hold a foreground service that publishes nothing.
            //
            // startForeground() has already run by this point (the platform
            // requires it within seconds of the start request), so drop the
            // notification explicitly instead of waiting for onDestroy: telling
            // the user we are sharing their location, on the one path where we
            // have just been refused permission to do so, is the most misleading
            // thing this notification could say.
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            false
        }
    }

    /**
     * Observes the owner's session node and ticks the clock, folding both into
     * [LiveSharingLifecycle]. The tick is what enforces expiry while offline and
     * what keeps the notification's countdown honest.
     */
    private fun observeSession(repo: LiveLocationRepository, uid: String) {
        sessionJob?.cancel()
        sessionJob =
            scope.launch {
                launch {
                    while (isActive) {
                        delay(BackgroundLocation.EXPIRY_TICK_MS)
                        val now = System.currentTimeMillis()
                        // Stationary auto-stop is privacy-critical, so evaluate it
                        // before the lifecycle tick. AutoStop tears the session down
                        // server-side (same path as a manual stop); if it fires,
                        // there is nothing left to tick.
                        if (evaluateStationary(now)) continue
                        decideAndApply { lifecycle.onTick(isStillSignedIn(uid), now) }
                    }
                }
                try {
                    repo.observeOwnSession(uid).collectLatest { session ->
                        lastSession = session
                        decideAndApply {
                            lifecycle.onObservation(
                                signedIn = isStillSignedIn(uid),
                                session = session,
                                nowMillis = System.currentTimeMillis(),
                            )
                        }
                    }
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Exception) {
                    // The session listener died for good. Keeping a foreground
                    // service alive with no way to learn that sharing ended is
                    // the privacy-worst outcome, so stop.
                    stopSharingLocally()
                }
            }
    }

    /**
     * Whether [uid] is still the signed-in user. Covers sign-out and account
     * switches; a Firebase-less build cannot be signed in, but it also never
     * reaches here (the repository guard stops the service first).
     */
    private fun isStillSignedIn(uid: String): Boolean =
        FirebaseApp.getApps(applicationContext).isNotEmpty() &&
            FirebaseAuth.getInstance().currentUser?.uid == uid

    /**
     * Computes a decision and applies it under [lifecycleMutex].
     *
     * The ticker and the session observer are separate coroutines on
     * `Dispatchers.IO`, which is multi-threaded, so without this they run
     * genuinely concurrently against the same mutable [LiveSharingLifecycle]
     * (`lastSession`, `firstAbsentAtMillis`, the anchor) and the same
     * notification state (`shownRemainingMinutes`). Neither is thread-safe, and
     * the fields are not volatile, so a tick could evaluate expiry against a
     * session the observer had already replaced — deciding stop-or-continue for
     * background location sharing on a stale read.
     *
     * The lock spans the decision AND its application, so a decision cannot be
     * computed from one state and applied against another.
     */
    private suspend fun decideAndApply(decide: () -> LiveSharingDecision) {
        lifecycleMutex.withLock { apply(decide()) }
    }

    private fun apply(decision: LiveSharingDecision) {
        when (decision) {
            is LiveSharingDecision.Continue -> postNotification(decision.remainingSeconds)
            is LiveSharingDecision.Stop -> stopSharingLocally()
        }
    }

    /**
     * Folds the current stationary state into the notification prompt (and, at the
     * end of the grace window, a server-side auto-stop). Returns true when it
     * auto-stopped — the caller then skips the ordinary lifecycle tick, there being
     * nothing left to tick.
     *
     * Prompt precedence: a pending STATIONARY prompt (privacy) outranks the EXTEND
     * prompt; with neither pending the notification returns to NORMAL. The
     * resulting [promptMode] is read by the [decideAndApply] that runs right after
     * this in the ticker, which posts the notification.
     */
    private suspend fun evaluateStationary(nowMillis: Long): Boolean {
        val decision = synchronized(stationaryLock) { stationaryMonitor.decide(nowMillis) }
        if (decision is StationaryDecision.AutoStop) {
            // Parked ~15 min with no reply: stop on the SAME server path as a
            // manual stop (removes the marker; leaves any convoy membership intact).
            stopSharingAndSelf(expectedUid = ownerUid, startId = latestStartId)
            return true
        }
        val stationaryPending =
            decision is StationaryDecision.Prompt ||
                synchronized(stationaryLock) { stationaryMonitor.isPromptOutstanding() }
        lifecycleMutex.withLock {
            promptMode =
                when {
                    stationaryPending -> SharingPromptMode.STATIONARY
                    LiveLocation.isExpiringSoon(lastSession, nowMillis) -> SharingPromptMode.EXTEND
                    else -> SharingPromptMode.NORMAL
                }
        }
        return false
    }

    /**
     * The notification's "Keep sharing" (Extend) action: pushes the session's
     * expiry forward server-side and clears the extend prompt. Non-terminal — the
     * service keeps running. The callable rides [stopScope] so it survives a
     * near-simultaneous teardown, exactly like the stop action.
     */
    private fun handleExtend(uid: String) {
        if (!isStillSignedIn(uid)) return
        val repo =
            repository ?: FirebaseLiveLocationRepository.createIfAvailable(applicationContext) ?: return
        stopScope.launch {
            try {
                repo.extendSession()
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                // A failed extend leaves the session on its existing expiry; the
                // pre-expiry prompt will simply re-appear on the next tick.
            }
        }
        // Optimistically clear the prompt for instant feedback; the pushed expiry
        // makes isExpiringSoon false once the session node update arrives.
        scope.launch {
            lifecycleMutex.withLock {
                promptMode = SharingPromptMode.NORMAL
                postNotification(LiveLocation.remainingSeconds(lastSession, System.currentTimeMillis()))
            }
        }
    }

    /**
     * The stationary prompt's "Yes, still sharing" action: restarts the stationary
     * clock (another full quiet window before asking again) and clears the prompt.
     * Non-terminal.
     */
    private fun handleStillSharing() {
        val now = System.currentTimeMillis()
        synchronized(stationaryLock) { stationaryMonitor.answerStillSharing(now) }
        scope.launch {
            lifecycleMutex.withLock {
                promptMode = SharingPromptMode.NORMAL
                postNotification(LiveLocation.remainingSeconds(lastSession, now))
            }
        }
    }

    /**
     * Tears the service down without touching the backend. Used for every stop
     * the backend already knows about (manual stop from the app, expiry, remote
     * end, sign-out) — calling stopSession again would be redundant traffic.
     */
    private fun stopSharingLocally() {
        // Same reasoning as stopSharingAndSelf(): stopSelf() is NOT synchronous,
        // so between here and onDestroy() the observer could emit once more. A
        // Continue landing after stopForeground(STOP_FOREGROUND_REMOVE) would
        // re-post via notify() — and a notify()d notification is not tied to the
        // service, so it would outlive it as a permanent "sharing your location"
        // notice with nothing behind it. Cancelling here also breaks the ticker
        // loop, whose `while (isActive)` would otherwise still be live.
        //
        // Safe to self-cancel: this is always reached from inside sessionJob, and
        // the rest of this method never suspends, so it runs to completion.
        sessionJob?.cancel()
        sessionJob = null

        fusedClient?.removeLocationUpdates(locationCallback)
        fusedClient = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * Notification "Stop sharing": ends the session server-side, then stops.
     *
     * The callable runs on the process-lifetime [stopScope], NOT on the service
     * [scope], and `stopSelf()` is deferred until it settles. Launching it on
     * [scope] and stopping immediately would let `onDestroy()`'s `scope.cancel()`
     * kill the call before dispatch, leaving the session live server-side until
     * expiry — see [LiveSharingStop] for the measurement.
     */
    private fun stopSharingAndSelf(expectedUid: String?, startId: Int) {
        // `live.stopSession` is scoped to the CURRENTLY signed-in FirebaseAuth
        // user, not to whoever this notification was posted for. Those can differ:
        // the SIGNED_OUT teardown is driven by the 15 s tick, so after a sign-out
        // or an account switch the notification can still be on screen for a
        // moment. Tapping it then would end the NEW account's session while
        // leaving the original one ACTIVE until expiry — the tapping user gets
        // stopped without asking, and the user who asked to stop keeps sharing.
        //
        // A missing uid means we cannot verify, and an unverifiable stop is not
        // worth ending the wrong person's sharing over: tear down locally and let
        // the session expire on its own.
        if (expectedUid.isNullOrBlank() || !isStillSignedIn(expectedUid)) {
            stopSharingLocally()
            return
        }

        // The PendingIntent outlives this process, so the stop action can reach a
        // FRESH service instance whose [repository] was never set — a stale
        // notification tapped after a kill, or a restart racing the tap. Building
        // one on demand keeps that path a real stop instead of silently walking
        // away from a session that stays ACTIVE server-side until expiry.
        // live.stopSession carries no client state, so a fresh repository is
        // equivalent to the running one.
        val repo = repository ?: FirebaseLiveLocationRepository.createIfAvailable(applicationContext)
        if (repo == null) {
            stopSharingLocally()
            return
        }
        // Silence the session observer and the expiry ticker FIRST. They run
        // until onDestroy(), which is now deferred for the length of the
        // stopSession round trip; a Continue decision landing in that window
        // would re-post the very notification the user just dismissed, visually
        // undoing their tap.
        sessionJob?.cancel()
        sessionJob = null

        // Stop publishing and drop the notification immediately: the user's tap
        // gets instant feedback while the callable finishes behind it.
        fusedClient?.removeLocationUpdates(locationCallback)
        fusedClient = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopScope.launch {
            LiveSharingStop.run(
                stopSession = { repo.stopSession() },
                // stopSelf(startId), NOT stopSelf(): the teardown is deferred for
                // the length of the round trip, and the user can start sharing
                // again inside that window. An unconditional stop would then kill
                // the NEW run — sharing that silently dies seconds after the user
                // asked for it. The startId form stops only while this remains
                // the most recent start, so a newer one wins.
                finish = { stopSelf(startId) },
            )
        }
    }

    /**
     * Posts (or refreshes) the ongoing notification. Refreshes are suppressed
     * unless the displayed minute OR the active prompt mode actually changed, so a
     * 4-hour session re-posts ~240 times rather than once per tick, while a
     * prompt appearing/clearing always refreshes immediately.
     */
    private fun postNotification(remainingSeconds: Long?, foreground: Boolean = false) {
        val minutes = remainingSeconds?.let { (it + 59) / 60 } ?: -1L
        if (!foreground && minutes == shownRemainingMinutes && promptMode == shownPromptMode) return
        shownRemainingMinutes = minutes
        shownPromptMode = promptMode

        val body =
            when (promptMode) {
                SharingPromptMode.STATIONARY ->
                    getString(R.string.liveLocation_stationaryPromptBody)
                SharingPromptMode.EXTEND ->
                    if (minutes > 0) {
                        getString(R.string.liveLocation_extendPromptBodyRemaining, minutes)
                    } else {
                        getString(R.string.liveLocation_extendPromptBody)
                    }
                SharingPromptMode.NORMAL ->
                    if (minutes > 0) {
                        getString(R.string.liveLocation_backgroundNotificationBodyRemaining, minutes)
                    } else {
                        getString(R.string.liveLocation_backgroundNotificationBody)
                    }
            }

        val notification = buildNotification(body)
        if (foreground) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            // notify() on a channel the user disabled is a silent no-op — it does
            // NOT throw and does NOT affect the running foreground service.
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(body: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(
                when (promptMode) {
                    SharingPromptMode.STATIONARY ->
                        getString(R.string.liveLocation_stationaryPromptTitle)
                    SharingPromptMode.EXTEND ->
                        getString(R.string.liveLocation_extendPromptTitle)
                    SharingPromptMode.NORMAL ->
                        getString(R.string.liveLocation_backgroundNotificationTitle)
                },
            )
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openAppIntent())
            .apply {
                // The affirmative reply comes FIRST so it sits nearest the body,
                // then the always-present Stop. Only one prompt is ever active.
                when (promptMode) {
                    SharingPromptMode.EXTEND ->
                        addAction(
                            R.drawable.ic_notification,
                            getString(R.string.liveLocation_extendAction),
                            extendSharingIntent(),
                        )
                    SharingPromptMode.STATIONARY ->
                        addAction(
                            R.drawable.ic_notification,
                            getString(R.string.liveLocation_stillSharingAction),
                            stillSharingIntent(),
                        )
                    SharingPromptMode.NORMAL -> Unit
                }
            }
            .addAction(
                R.drawable.ic_notification,
                getString(R.string.liveLocation_backgroundNotificationStop),
                stopSharingIntent(),
            )
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // Location sharing is privacy-visible: show it on the lock screen in
            // full, so a phone in a mount still says out loud that it is sharing.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    /**
     * Tap → bring the EXISTING task forward rather than launch a duplicate
     * activity instance.
     *
     * An `ACTION_MAIN` + `CATEGORY_LAUNCHER` intent with `FLAG_ACTIVITY_NEW_TASK`
     * is precisely what the launcher icon sends: the platform matches the running
     * task whose root activity it is and resumes that task with its back stack
     * intact. `FLAG_ACTIVITY_NEW_TASK` alone (on a bare component intent) would
     * NOT do this — it only reuses a task when the intent also identifies the
     * task root, which is what the MAIN/LAUNCHER pair supplies. This needs no
     * `launchMode` change on MainActivity.
     */
    private fun openAppIntent(): PendingIntent {
        val intent =
            Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                addCategory(Intent.CATEGORY_LAUNCHER)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
        return PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * The notification's "Stop sharing" action.
     *
     * Deliberately [PendingIntent.getService], NOT `getForegroundService()`.
     * Tapping a notification action puts the app on the system's temporary
     * background-start allowlist, so the plain `startService` this fires is
     * permitted even from a dead/background process — the Android 8+ background
     * service restriction does not apply to a user-initiated notification action.
     *
     * `getForegroundService()` would be strictly worse here: it obliges the
     * service to call `startForeground()` within the platform deadline, and this
     * entry point's whole job is to fire one callable and stop. Entering the
     * foreground to immediately leave it would post a notification the user just
     * dismissed, and missing the deadline is a `ForegroundServiceDidNotStartInTime`
     * crash on Android 12+. A stop path must not be able to crash the app.
     *
     * Carries [EXTRA_UID] so the handler can confirm the account this
     * notification was posted for is still the signed-in one before invoking the
     * auth-scoped `live.stopSession`. FLAG_UPDATE_CURRENT keeps that extra in
     * step with [ownerUid] as the notification is rebuilt.
     */
    private fun stopSharingIntent(): PendingIntent {
        val intent =
            Intent(this, LocationSharingService::class.java).apply {
                action = ACTION_STOP_SHARING
                putExtra(EXTRA_UID, ownerUid)
            }
        return PendingIntent.getService(
            this,
            REQUEST_STOP_SHARING,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * The extend prompt's "Keep sharing" action. Same getService rationale as
     * [stopSharingIntent], but NON-terminal: the handler pushes the session's
     * expiry forward and the service keeps running. Carries [EXTRA_UID] so a stale
     * tap after a sign-out/kill is ignored rather than acting on the wrong account.
     */
    private fun extendSharingIntent(): PendingIntent {
        val intent =
            Intent(this, LocationSharingService::class.java).apply {
                action = ACTION_EXTEND_SHARING
                putExtra(EXTRA_UID, ownerUid)
            }
        return PendingIntent.getService(
            this,
            REQUEST_EXTEND_SHARING,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** The stationary prompt's "Yes, still sharing" action. Non-terminal — see [extendSharingIntent]. */
    private fun stillSharingIntent(): PendingIntent {
        val intent =
            Intent(this, LocationSharingService::class.java).apply {
                action = ACTION_STILL_SHARING
                putExtra(EXTRA_UID, ownerUid)
            }
        return PendingIntent.getService(
            this,
            REQUEST_STILL_SHARING,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.liveLocation_backgroundNotificationChannel),
                NotificationManager.IMPORTANCE_LOW,
            )
        channel.description =
            getString(R.string.liveLocation_backgroundNotificationChannelDescription)
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val ACTION_STOP_SHARING = "com.kungsbackacarcommunity.app.action.STOP_LIVE_SHARING"

        /** The extend prompt's "Keep sharing" notification action. Non-terminal. */
        const val ACTION_EXTEND_SHARING = "com.kungsbackacarcommunity.app.action.EXTEND_LIVE_SHARING"

        /** The stationary prompt's "Yes, still sharing" notification action. Non-terminal. */
        const val ACTION_STILL_SHARING = "com.kungsbackacarcommunity.app.action.STILL_LIVE_SHARING"

        const val EXTRA_UID = "uid"

        /**
         * Process-lifetime scope for the one operation that must outlive the
         * service: the `live.stopSession` callable behind the notification's
         * "Stop sharing" action. Deliberately never cancelled — `onDestroy()`
         * cancels the per-instance [scope], which is exactly what would abort
         * that call. Nothing long-running is ever launched here; the single
         * user of it is bounded by [LiveSharingStop.STOP_SESSION_TIMEOUT_MS].
         */
        private val stopScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Whether a live-location foreground session is currently running (its
         * own ongoing notification is on screen). Read — never written — by the
         * separate "app is active" notification so the two ongoing notices do
         * not compete in the shade. Volatile: written on the main thread from
         * the service lifecycle, read from the ProcessLifecycle observer.
         */
        @Volatile
        private var sessionActive: Boolean = false

        /** See [sessionActive]. */
        fun isSessionActive(): Boolean = sessionActive

        private const val CHANNEL_ID = "live_location_sharing"
        private const val NOTIFICATION_ID = 4201
        private const val REQUEST_OPEN_APP = 4202
        private const val REQUEST_STOP_SHARING = 4203
        private const val REQUEST_EXTEND_SHARING = 4204
        private const val REQUEST_STILL_SHARING = 4205
    }
}

/** Which cost/safety prompt (if any) the ongoing notification is offering. */
private enum class SharingPromptMode {
    /** Just the ongoing "you are sharing" notice + Stop. */
    NORMAL,

    /** 15 min before expiry: "keep sharing?" with an Extend action. */
    EXTEND,

    /** Parked a while: "still sharing?" with a "Yes, still sharing" action. */
    STATIONARY,
}
