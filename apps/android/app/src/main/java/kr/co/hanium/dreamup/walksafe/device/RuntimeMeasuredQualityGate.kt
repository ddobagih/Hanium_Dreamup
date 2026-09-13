package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus
import kr.co.hanium.dreamup.walksafe.session.MeasuredEnvironmentEvidence
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentFactor

/** Retains real, fresh evidence briefly during a measured quality fluctuation. */
class RuntimeMeasuredQualityGate(
    // One regular GNSS update interval: one bad fix can recover on the next sample.
    private val maximumTransientFailureMs: Long = 1_500L,
) {
    init {
        require(maximumTransientFailureMs > 0L)
    }

    private var lastPass: MeasuredEnvironmentEvidence? = null
    private var failureStartedAtMs: Long? = null
    private var latestCandidate: MeasuredEnvironmentEvidence? = null

    @Synchronized
    fun observe(
        candidate: MeasuredEnvironmentEvidence,
        nowElapsedRealtimeMs: Long,
    ): MeasuredEnvironmentEvidence {
        val previous = lastPass
        if (previous != null &&
            (previous.epoch != candidate.epoch ||
                previous.factor != candidate.factor ||
                previous.measurementProfileId != candidate.measurementProfileId)
        ) reset()
        latestCandidate = candidate

        if (candidate.status == EnvironmentEvidenceStatus.PASS &&
            candidate.isFresh(nowElapsedRealtimeMs)
        ) {
            lastPass = candidate
            failureStartedAtMs = null
            return candidate
        }

        val passing = lastPass
        if (passing != null && passing.isFresh(nowElapsedRealtimeMs) &&
            candidate.isFresh(nowElapsedRealtimeMs) && candidate.isQualityFluctuation() &&
            candidate.observedAtElapsedRealtimeMs!! >= passing.observedAtElapsedRealtimeMs!!
        ) {
            val failureStarted = failureStartedAtMs ?: nowElapsedRealtimeMs.also {
                failureStartedAtMs = it
            }
            if (nowElapsedRealtimeMs - failureStarted in 0L until maximumTransientFailureMs) {
                // Keep the original measurement time; this never renews a stale PASS.
                return passing
            }
        }
        clearPass()
        return if (candidate.status == EnvironmentEvidenceStatus.PASS) {
            candidate.copy(
                status = EnvironmentEvidenceStatus.UNKNOWN,
                detail = "STALE_OR_INVALID_TIMESTAMP",
            )
        } else {
            candidate
        }
    }

    /** Reassesses the original observation without pretending a new sample arrived. */
    @Synchronized
    fun current(nowElapsedRealtimeMs: Long): MeasuredEnvironmentEvidence? =
        latestCandidate?.let { observe(it, nowElapsedRealtimeMs) }

    @Synchronized
    fun pendingFailureDeadlineElapsedRealtimeMs(): Long? =
        failureStartedAtMs?.plus(maximumTransientFailureMs)

    @Synchronized
    fun reset() {
        latestCandidate = null
        clearPass()
    }

    private fun clearPass() {
        lastPass = null
        failureStartedAtMs = null
    }

    private fun MeasuredEnvironmentEvidence.isFresh(nowMs: Long): Boolean {
        val observedAtMs = observedAtElapsedRealtimeMs ?: return false
        val maximumAgeMs = maximumEvidenceAgeMs ?: return false
        return epoch != null && !measurementProfileId.isNullOrBlank() &&
            observedAtMs >= 0L && nowMs >= 0L &&
            nowMs - observedAtMs in 0L..maximumAgeMs
    }

    private fun MeasuredEnvironmentEvidence.isQualityFluctuation(): Boolean {
        if (status != EnvironmentEvidenceStatus.FAIL) return false
        return when (factor) {
            OfficialEnvironmentFactor.GPS_QUALITY ->
                detail == "GPS_ACCURACY_OUTSIDE_APPROVED_RANGE"
            OfficialEnvironmentFactor.CAMERA_QUALITY -> detail in setOf(
                CameraFrameQualityReason.BRIGHTNESS_OUTSIDE_APPROVED_RANGE.name,
                CameraFrameQualityReason.OCCLUSION_OUTSIDE_APPROVED_RANGE.name,
                CameraFrameQualityReason.SHAKE_OUTSIDE_APPROVED_RANGE.name,
                CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE.name,
            )
            else -> false
        }
    }
}
