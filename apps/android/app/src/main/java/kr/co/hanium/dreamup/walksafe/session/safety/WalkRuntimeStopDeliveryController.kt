package kr.co.hanium.dreamup.walksafe.session.safety

import java.util.Locale
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.session.*

internal interface RuntimeStopScheduler {
    fun dispatchOnMain(action: () -> Unit)
    fun enqueueOnMain(action: () -> Unit): Boolean
    fun postRetry(action: Runnable, delayMs: Long): Boolean
    fun removeRetry(action: Runnable)
}

internal fun interface RuntimeStopCleanupRunner {
    fun run(reason: String, persistInterruptionMarker: Boolean, checkpoints: WalkRuntimeStopCleanupCheckpoints,
        onCameraResult: (Boolean) -> Unit, onLocationResult: (Boolean) -> Unit): Boolean
}

internal interface RuntimeStopEffects {
    fun showRestartNotice()
    fun showCauseStatus(navigationStatus: String)
    fun refreshOwnedStop()
}

/** Existing safety latch/checkpoints are reused; this owner only joins actual cleanup ACKs. */
internal class WalkRuntimeStopDeliveryController(
    private val safety: WalkRuntimeSafetyCoordinator,
    private val failLocationStopCleanupCheckpoint: (WalkRuntimeStopCleanupCheckpoints) -> List<(Boolean) -> Unit>,
    private val currentLifecycle: () -> WalkSessionLifecycle?,
    private val scheduler: RuntimeStopScheduler,
    private val cleanup: RuntimeStopCleanupRunner,
    private val effects: RuntimeStopEffects,
) {
    private var walkRuntimeSafetyStopCleanupEpoch: WalkRuntimeEpoch? = null
    private var walkRuntimeSafetyStopCleanup: WalkRuntimeStopCleanupCheckpoints? = null
    @Volatile private var walkRuntimeSafetyStopDestroyed = false
    @Volatile private var walkRuntimeSafetyStopRetryRunnable: Runnable? = null

    fun recordSafetyStopTransitionIfOwned(transition: WalkSessionTransition) {
        if (walkRuntimeSafetyStopCleanupEpoch == transition.previous.epoch) {
            walkRuntimeSafetyStopCleanup?.recordSafetyStopTransition(transition)
        }
    }

    fun close() {
        walkRuntimeSafetyStopDestroyed = true
        safety.close()
        walkRuntimeSafetyStopRetryRunnable?.let(scheduler::removeRetry)
        walkRuntimeSafetyStopRetryRunnable = null
    }

    fun currentOwnedWalkRuntimeSafetyStopStatus(): WalkRuntimeSafetyStopStatus? {
        if (walkRuntimeSafetyStopDestroyed) return null
        val lifecycle = currentLifecycle() ?: return null
        val walk = lifecycle.snapshot()
        if (walk.state != WalkSessionState.SAFE_STOP) return null
        val status = safety.latchedStopStatus() ?: return null
        if (walkRuntimeSafetyStopCleanupEpoch != status.stop.epoch) return null
        return status.takeIf { walkRuntimeSafetyStopCleanup?.ownsStoppedSession(it.stop.epoch, walk) == true }
    }
    fun scheduleWalkRuntimeSafetyStopRetry(retry: Runnable): Boolean {
        if (walkRuntimeSafetyStopDestroyed) return false
        lateinit var scheduled: Runnable
        scheduled = Runnable {
            if (walkRuntimeSafetyStopRetryRunnable !== scheduled || walkRuntimeSafetyStopDestroyed) {
                return@Runnable
            }
            walkRuntimeSafetyStopRetryRunnable = null
            retry.run()
        }
        walkRuntimeSafetyStopRetryRunnable = scheduled
        val posted = scheduler.postRetry(scheduled, 250L)
        if (!posted && walkRuntimeSafetyStopRetryRunnable === scheduled) {
            walkRuntimeSafetyStopRetryRunnable = null
        }
        return posted
    }

    fun onWalkRuntimeSafetyStopExhausted(stop: WalkRuntimeSafetyStop) {
        scheduler.dispatchOnMain {
            if (walkRuntimeSafetyStopDestroyed || currentLifecycle() == null) return@dispatchOnMain
            val current = safety.latchedStopStatus() ?: return@dispatchOnMain
            if (current.stop.epoch != stop.epoch || !current.cleanupExhausted ||
                walkRuntimeSafetyStopCleanupEpoch != current.stop.epoch
            ) return@dispatchOnMain
            effects.showRestartNotice()
        }
    }

    /** Cleanup stays one-shot; only the displayed stop reason follows causes added later. */
    fun onWalkRuntimeSafetyStopCausesChanged(
        @Suppress("UNUSED_PARAMETER") changed: WalkRuntimeSafetyStop,
    ) {
        // Always queue behind an already-posted stop delivery, even from the UI thread, so the
        // cleanup epoch is set before this runs and a cause added in between is not dropped.
        scheduler.enqueueOnMain {
            if (walkRuntimeSafetyStopDestroyed || currentLifecycle() == null) {
                return@enqueueOnMain
            }
            // Re-read the latch so out-of-order callbacks cannot show an older cause set.
            val current = safety.latchedStopStatus() ?: return@enqueueOnMain
            val stop = current.stop
            if (walkRuntimeSafetyStopCleanupEpoch != stop.epoch) return@enqueueOnMain
            if (current.cleanupExhausted) {
                effects.showRestartNotice()
                return@enqueueOnMain
            }
            val causes = stop.causes.map { it.name.lowercase(Locale.US) }.sorted().joinToString("+")
            // Not a route failure token: camera or thermal causes must not read as a failed route.
            effects.showCauseStatus("navigation=runtime_safety_stopped reason=runtime_safety_coordinator:$causes")
        }
    }

    fun onWalkRuntimeSafetyStop(
        stop: WalkRuntimeSafetyStop,
        completion: (Boolean) -> Unit,
    ) {
        scheduler.dispatchOnMain {
            if (walkRuntimeSafetyStopDestroyed || currentLifecycle() == null) {
                completion(false)
                return@dispatchOnMain
            }
            val currentSession = checkNotNull(currentLifecycle()).snapshot()
            val retainedCheckpoints = walkRuntimeSafetyStopCleanup
                ?.takeIf { walkRuntimeSafetyStopCleanupEpoch == stop.epoch }
            if (
                currentSession.epoch != stop.epoch &&
                retainedCheckpoints?.ownsStoppedSession(stop.epoch, currentSession) != true
            ) {
                completion(false)
                return@dispatchOnMain
            }
            val checkpoints = retainedCheckpoints ?: WalkRuntimeStopCleanupCheckpoints()
            walkRuntimeSafetyStopCleanupEpoch = stop.epoch
            walkRuntimeSafetyStopCleanup = checkpoints
            // A cause latched between the coordinator's delivery snapshot and this runnable
            // was dropped by the cause handler (epoch not set yet); show the current set now.
            safety.latchedStop()
                ?.takeIf { it.epoch == stop.epoch && it.causes != stop.causes }
                ?.let(::onWalkRuntimeSafetyStopCausesChanged)
            var synchronousFinished = false
            var synchronousComplete = false
            var cameraReleased: Boolean? = null
            var locationReleased: Boolean? = null
            var reported = false
            fun reportIfReady() {
                if (!synchronousFinished || cameraReleased == null || locationReleased == null || reported) return
                reported = true
                completion(synchronousComplete && cameraReleased == true && locationReleased == true)
                if (currentOwnedWalkRuntimeSafetyStopStatus()?.stop?.epoch == stop.epoch) {
                    effects.refreshOwnedStop()
                }
            }
            try {
                val causes = stop.causes.map { it.name.lowercase(Locale.US) }.sorted().joinToString("+")
                synchronousComplete = cleanup.run(
                    reason = "runtime_safety_coordinator:$causes",
                    persistInterruptionMarker = true,
                    checkpoints = checkpoints,
                    onCameraResult = { released ->
                        cameraReleased = released
                        reportIfReady()
                    },
                    onLocationResult = { released ->
                        locationReleased = released
                        reportIfReady()
                    },
                )
            } catch (_: Exception) {
                checkpoints.finishAsync("camera_release", false)
                val locationWaiters = failLocationStopCleanupCheckpoint(checkpoints)
                cameraReleased = false
                locationReleased = false
                locationWaiters.forEach { it(false) }
            }
            synchronousFinished = true
            reportIfReady()
        }
    }
}
