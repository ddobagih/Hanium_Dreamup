package kr.co.hanium.dreamup.walksafe.inference

import java.util.concurrent.atomic.AtomicBoolean
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState

internal class DetectorStallWatchdog(
    private val budgetMs: Long,
    private val nowMs: () -> Long,
) {
    internal class Flight internal constructor(
        val deadlineElapsedRealtimeMs: Long,
        val walkEpoch: WalkRuntimeEpoch?,
    ) {
        internal var completed = false
    }

    private val lock = Any()
    private var active: Flight? = null
    private var admittedWalkEpoch: WalkRuntimeEpoch? = null
    private var blocked = false
    private var stopRequestedForEpoch: WalkRuntimeEpoch? = null

    fun begin(walkEpoch: WalkRuntimeEpoch? = null): Flight? = synchronized(lock) {
        if (blocked || active != null ||
            (admittedWalkEpoch != null && walkEpoch != admittedWalkEpoch)
        ) return@synchronized null
        Flight(nowMs() + budgetMs, walkEpoch).also { active = it }
    }

    fun expire(flight: Flight): Boolean = synchronized(lock) {
        if (active !== flight || flight.completed || blocked || nowMs() < flight.deadlineElapsedRealtimeMs) {
            return@synchronized false
        }
        blocked = true
        true
    }

    fun complete(flight: Flight): Boolean = synchronized(lock) {
        if (active !== flight) return@synchronized false
        if (nowMs() >= flight.deadlineElapsedRealtimeMs) blocked = true
        active = null
        flight.completed = true
        !blocked
    }

    fun publishIfCurrent(flight: Flight, publish: () -> Unit): Boolean = synchronized(lock) {
        if (active !== flight || flight.completed || blocked) return@synchronized false
        if (nowMs() >= flight.deadlineElapsedRealtimeMs) {
            blocked = true
            return@synchronized false
        }
        publish()
        true
    }

    fun blocksNewWork(): Boolean = synchronized(lock) { blocked }

    fun rejectBlockedWork(
        currentEpoch: WalkRuntimeEpoch?,
        currentState: WalkSessionState?,
        requestSafeStop: (WalkRuntimeEpoch) -> Unit,
    ): Boolean = rejectUnavailableWork(currentEpoch, currentState, false, requestSafeStop)

    fun rejectUnsafeWalkEntry(
        currentEpoch: WalkRuntimeEpoch,
        currentState: WalkSessionState,
        requestSafeStop: (WalkRuntimeEpoch) -> Unit,
    ): Boolean = rejectUnavailableWork(currentEpoch, currentState, true, requestSafeStop)

    private fun rejectUnavailableWork(
        currentEpoch: WalkRuntimeEpoch?,
        currentState: WalkSessionState?,
        rejectUnresolvedOtherFlight: Boolean,
        requestSafeStop: (WalkRuntimeEpoch) -> Unit,
    ): Boolean {
        var stopEpoch: WalkRuntimeEpoch? = null
        val rejected = synchronized(lock) {
            if (rejectUnresolvedOtherFlight && currentEpoch != null) {
                admittedWalkEpoch = currentEpoch
            }
            val unresolvedOtherFlight = rejectUnresolvedOtherFlight &&
                active?.let { it.walkEpoch != currentEpoch } == true
            if (!blocked && !unresolvedOtherFlight) return@synchronized false
            if (currentState == WalkSessionState.ACTIVE &&
                currentEpoch != null && stopRequestedForEpoch != currentEpoch
            ) {
                stopRequestedForEpoch = currentEpoch
                stopEpoch = currentEpoch
            }
            true
        }
        stopEpoch?.let(requestSafeStop)
        return rejected
    }
}

internal data class NativeDetectorFlight(
    val ticket: DetectorStallWatchdog.Flight,
    val timeout: Runnable,
    val walkEpoch: WalkRuntimeEpoch,
    val detectorGeneration: Int,
    val stopClaim: AtomicBoolean,
)
