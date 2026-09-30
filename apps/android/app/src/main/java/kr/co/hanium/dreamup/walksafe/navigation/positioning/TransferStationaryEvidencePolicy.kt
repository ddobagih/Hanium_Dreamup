package kr.co.hanium.dreamup.walksafe.navigation.positioning

/**
 * ADD-MOTION-043 / FP-035-DETAIL-01: a report or raw-source transfer may start only when GNSS speed
 * and phone motion both indicate stillness, repeatedly and without interruption. Unchanged step
 * counts, walk PAUSED state, consent or permission are never stillness evidence. UNKNOWN, expired,
 * partial or differently-timed snapshots fail closed.
 *
 * [MINIMUM_CONTINUOUS_STATIONARY_MS] is an engineering default equal to the previous step-silence
 * window, not the measured field value FP-035-DETAIL-01 still requires.
 */
internal object TransferStationaryEvidencePolicy {
    const val MINIMUM_CONTINUOUS_STATIONARY_MS = 5_000L

    fun confirmed(snapshot: UserMotionStateSnapshot, nowElapsedRealtimeMs: Long): Boolean {
        if (nowElapsedRealtimeMs < 0L || snapshot.evaluatedAtElapsedRealtimeMs != nowElapsedRealtimeMs) return false
        if (snapshot.movement != UserMovementState.STATIONARY ||
            snapshot.reason != UserMotionStateReason.STATIONARY_CONFIRMED
        ) return false
        // Both independent sources must be present in the snapshot that declared STATIONARY.
        val speedAt = snapshot.speedObservedAtElapsedRealtimeMs ?: return false
        val motionAt = snapshot.motionObservedAtElapsedRealtimeMs ?: return false
        if (speedAt > nowElapsedRealtimeMs || motionAt > nowElapsedRealtimeMs) return false
        val expiresAt = snapshot.expiresAtElapsedRealtimeMs ?: return false
        if (nowElapsedRealtimeMs > expiresAt) return false
        // GNSS+inertial STATIONARY published continuously (any MOVING/UNKNOWN restarts it) and
        // inertial quiet measured on sensor time, both for the whole window.
        val stateSince = snapshot.stateSinceElapsedRealtimeMs ?: return false
        val quietSince = snapshot.stationaryCandidateSinceElapsedRealtimeMs ?: return false
        return stateSince <= nowElapsedRealtimeMs && quietSince <= nowElapsedRealtimeMs &&
            nowElapsedRealtimeMs - stateSince >= MINIMUM_CONTINUOUS_STATIONARY_MS &&
            nowElapsedRealtimeMs - quietSince >= MINIMUM_CONTINUOUS_STATIONARY_MS
    }
}
