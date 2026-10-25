package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus
import kr.co.hanium.dreamup.walksafe.session.MeasuredEnvironmentEvidence
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentFactor
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

/** Retains real, fresh evidence briefly during a measured quality fluctuation. */
class RuntimeMeasuredQualityGate(
    // One regular GNSS update interval: one bad fix can recover on the next sample.
    private val maximumTransientFailureMs: Long = 1_500L,
    // GPS may reassess several consecutive periods; other measured factors keep one period.
    private val gpsFailureConfirmationPeriods: Int = 1,
) {
    init {
        require(maximumTransientFailureMs > 0L)
        require(gpsFailureConfirmationPeriods > 0)
        require(maximumTransientFailureMs <= Long.MAX_VALUE / gpsFailureConfirmationPeriods)
    }

    private var lastPass: MeasuredEnvironmentEvidence? = null
    private var failureStartedAtMs: Long? = null
    private var gpsFailureConfirmed = false
    private var latestCandidate: MeasuredEnvironmentEvidence? = null
    private var newestObservationAtMs: Long? = null
    private var gpsMeasurementFailure: GpsFailureObservation? = null

    internal enum class GpsMeasurementFailure { INVALID_ACCURACY, NUMERICAL_FAILURE, CORRUPT_JUMP, TRANSIENT_CORRUPT_JUMP }
    enum class GpsRecoveryStage { FRESH, RECHECKING, RESTRICTED, UNAVAILABLE }

    private data class GpsFailureObservation(
        val epoch: WalkRuntimeEpoch,
        val observedAtMs: Long,
        val profileId: String,
        val failure: GpsMeasurementFailure,
    )

    /** The admitted estimator outcome qualifies only this synchronous source observation.
     * Its cause is stored with the original status, so watchdog reads retain the same episode.
     */
    @Synchronized
    internal fun withGpsMeasurementFailure(
        epoch: WalkRuntimeEpoch,
        observedAtMs: Long,
        profileId: String,
        failure: GpsMeasurementFailure,
        recordObservation: () -> Unit,
    ) {
        val previous = gpsMeasurementFailure
        gpsMeasurementFailure = GpsFailureObservation(epoch, observedAtMs, profileId, failure)
        try {
            recordObservation()
        } finally {
            gpsMeasurementFailure = previous
        }
    }

    @Synchronized
    fun observe(
        candidate: MeasuredEnvironmentEvidence,
        nowElapsedRealtimeMs: Long,
    ): MeasuredEnvironmentEvidence {
        if (candidate.factor == OfficialEnvironmentFactor.CAMERA_QUALITY &&
            candidate.detail == CameraFrameQualityReason.MOUNT_ANGLE_RECHECK_PENDING.name
        ) {
            // A normalized assessment can be seeded after an output-owner reset. The marker
            // is never authority by itself: requalify it from this gate's actual PASS history.
            return observe(candidate.copy(status = EnvironmentEvidenceStatus.FAIL,
                detail = CameraFrameQualityReason.MOUNT_ANGLE_NEAR_BOUNDARY.name), nowElapsedRealtimeMs)
        }
        val previous = latestCandidate
        val previousEpoch = previous?.epoch
        val candidateEpoch = candidate.epoch
        if (previousEpoch != null && candidateEpoch != null &&
            candidateEpoch.walkSessionId == previousEpoch.walkSessionId &&
            candidateEpoch.recoveryGeneration < previousEpoch.recoveryGeneration
        ) {
            return requireNotNull(current(nowElapsedRealtimeMs))
        }
        if (previous != null &&
            (previous.epoch != candidate.epoch ||
                previous.factor != candidate.factor ||
                previous.measurementProfileId != candidate.measurementProfileId)
        ) reset()
        val observedAtMs = candidate.observedAtElapsedRealtimeMs
        val newestAtMs = newestObservationAtMs
        if (candidate.status != EnvironmentEvidenceStatus.PASS && observedAtMs != null &&
            newestAtMs != null && observedAtMs < newestAtMs
        ) {
            // A delayed failure cannot replace a newer observation from this same source.
            return requireNotNull(current(nowElapsedRealtimeMs))
        }
        if (candidate.status == EnvironmentEvidenceStatus.PASS && newestAtMs != null &&
            (observedAtMs == null || observedAtMs <= newestAtMs)
        ) {
            // An older or duplicate PASS cannot replace the latest quality observation.
            return requireNotNull(current(nowElapsedRealtimeMs))
        }
        if (candidate.status == EnvironmentEvidenceStatus.PASS &&
            !candidate.isFresh(nowElapsedRealtimeMs)
        ) {
            val unavailable = candidate.copy(
                status = EnvironmentEvidenceStatus.UNKNOWN,
                detail = "STALE_OR_INVALID_TIMESTAMP",
            )
            // Invalid PASS cannot replace rejection evidence or become valid on a later clock tick.
            clearPass()
            if (latestCandidate == null ||
                latestCandidate?.status == EnvironmentEvidenceStatus.PASS
            ) {
                latestCandidate = unavailable
            }
            return unavailable
        }
        val classifiedCandidate = candidate.withAdmittedGpsFailure()
        latestCandidate = classifiedCandidate
        if (observedAtMs != null && observedAtMs >= 0L && observedAtMs <= nowElapsedRealtimeMs &&
            (newestAtMs == null || observedAtMs > newestAtMs)
        ) {
            newestObservationAtMs = observedAtMs
        }
        return evaluate(classifiedCandidate, nowElapsedRealtimeMs)
    }

    private fun evaluate(
        candidate: MeasuredEnvironmentEvidence,
        nowElapsedRealtimeMs: Long,
    ): MeasuredEnvironmentEvidence {
        if (candidate.status == EnvironmentEvidenceStatus.PASS &&
            candidate.isFresh(nowElapsedRealtimeMs)
        ) {
            lastPass = candidate
            failureStartedAtMs = null
            gpsFailureConfirmed = false
            return candidate
        }

        val passing = lastPass
        if (candidate.factor == OfficialEnvironmentFactor.CAMERA_QUALITY &&
            candidate.status == EnvironmentEvidenceStatus.FAIL &&
            candidate.detail == CameraFrameQualityReason.MOUNT_ANGLE_NEAR_BOUNDARY.name
        ) {
            // Keep the current frame FAIL. Only the explicit-correction latch and warning wait;
            // no rejected frame borrows PASS or becomes current safety-output evidence.
            if (passing != null && passing.isFresh(nowElapsedRealtimeMs) &&
                candidate.isFresh(nowElapsedRealtimeMs) &&
                candidate.observedAtElapsedRealtimeMs!! > passing.observedAtElapsedRealtimeMs!!
            ) {
                val started = failureStartedAtMs ?: nowElapsedRealtimeMs.also { failureStartedAtMs = it }
                if (nowElapsedRealtimeMs - started in 0L until maximumTransientFailureMs) {
                    return candidate.copy(detail = CameraFrameQualityReason.MOUNT_ANGLE_RECHECK_PENDING.name)
                }
            }
            clearPass()
            return candidate.copy(detail = CameraFrameQualityReason.MOUNT_ANGLE_OUTSIDE_APPROVED_RANGE.name)
        }
        val repeatedGpsRecheck = candidate.factor == OfficialEnvironmentFactor.GPS_QUALITY &&
            gpsFailureConfirmationPeriods > 1 && candidate.isQualityFluctuation()
        val confirmationMs = maximumTransientFailureMs *
            if (repeatedGpsRecheck) gpsFailureConfirmationPeriods else 1
        // A cold coarse fix can trigger remeasurement, but cannot borrow an absent good fix.
        // Once started, expiry or watchdog reads cannot renew the original failure episode.
        if (repeatedGpsRecheck && candidate.isFresh(nowElapsedRealtimeMs) &&
            failureStartedAtMs == null
        ) failureStartedAtMs = nowElapsedRealtimeMs
        if (repeatedGpsRecheck && failureStartedAtMs?.let {
                nowElapsedRealtimeMs - it >= confirmationMs
            } == true
        ) gpsFailureConfirmed = true
        if (passing != null && passing.isFresh(nowElapsedRealtimeMs) &&
            candidate.isFresh(nowElapsedRealtimeMs) && candidate.isQualityFluctuation() &&
            candidate.observedAtElapsedRealtimeMs!! >= passing.observedAtElapsedRealtimeMs!! &&
            // Conflicting frame availability at the same time cannot borrow that frame's PASS.
            (candidate.detail != CameraFrameQualityReason.FRAME_UNAVAILABLE.name ||
                candidate.observedAtElapsedRealtimeMs > passing.observedAtElapsedRealtimeMs)
        ) {
            val failureStarted = failureStartedAtMs ?: nowElapsedRealtimeMs.also {
                failureStartedAtMs = it
            }
            if (nowElapsedRealtimeMs - failureStarted in 0L until confirmationMs) {
                // Keep the original measurement time; this never renews a stale PASS.
                return passing
            }
        }
        if (repeatedGpsRecheck && failureStartedAtMs != null &&
            nowElapsedRealtimeMs >= failureStartedAtMs!!
        ) lastPass = null else clearPass()
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
        latestCandidate?.let { evaluate(it, nowElapsedRealtimeMs) }

    /** The accepted original observation, before any session continuation grace. */
    @Synchronized
    fun latestRawObservation(): MeasuredEnvironmentEvidence? = latestCandidate

    @Synchronized
    fun pendingFailureDeadlineElapsedRealtimeMs(): Long? =
        // An already confirmed deadline must not schedule the runtime watchdog every 1 ms.
        if (gpsFailureConfirmed) null else
        failureStartedAtMs?.let { started ->
            val periods = if (latestCandidate?.factor == OfficialEnvironmentFactor.GPS_QUALITY)
                gpsFailureConfirmationPeriods else 1
            started + maximumTransientFailureMs * periods
        }

    /** Original episode and elapsed periods, including after restriction; neither counts callbacks. */
    @Synchronized
    fun gpsFailureStartedAtElapsedRealtimeMs(): Long? =
        failureStartedAtMs.takeIf { latestCandidate?.factor == OfficialEnvironmentFactor.GPS_QUALITY }

    @Synchronized
    fun gpsFailureEvaluationPeriod(nowElapsedRealtimeMs: Long): Long? {
        val started = gpsFailureStartedAtElapsedRealtimeMs() ?: return null
        if (nowElapsedRealtimeMs < started) return null
        return (nowElapsedRealtimeMs - started) / maximumTransientFailureMs
    }

    @Synchronized
    fun gpsRecoveryStage(nowElapsedRealtimeMs: Long): GpsRecoveryStage {
        val selected = current(nowElapsedRealtimeMs)
        val raw = latestCandidate
        if (raw?.factor != OfficialEnvironmentFactor.GPS_QUALITY) return GpsRecoveryStage.UNAVAILABLE
        val period = gpsFailureEvaluationPeriod(nowElapsedRealtimeMs)
        if (period != null) return if (period < gpsFailureConfirmationPeriods)
            GpsRecoveryStage.RECHECKING else GpsRecoveryStage.RESTRICTED
        return if (selected?.status == EnvironmentEvidenceStatus.PASS)
            GpsRecoveryStage.FRESH else GpsRecoveryStage.UNAVAILABLE
    }

    @Synchronized
    fun reset() {
        latestCandidate = null
        newestObservationAtMs = null
        clearPass()
    }

    private fun clearPass() {
        lastPass = null
        failureStartedAtMs = null
        gpsFailureConfirmed = false
    }

    private fun MeasuredEnvironmentEvidence.isFresh(nowMs: Long): Boolean {
        val observedAtMs = observedAtElapsedRealtimeMs ?: return false
        val maximumAgeMs = maximumEvidenceAgeMs ?: return false
        return epoch != null && !measurementProfileId.isNullOrBlank() &&
            observedAtMs >= 0L && nowMs >= 0L &&
            nowMs - observedAtMs in 0L..maximumAgeMs
    }

    private fun MeasuredEnvironmentEvidence.isQualityFluctuation(): Boolean {
        if (factor == OfficialEnvironmentFactor.GPS_QUALITY &&
            status == EnvironmentEvidenceStatus.UNKNOWN && detail == "GPS_INVALID_ACCURACY"
        ) return true
        if (status != EnvironmentEvidenceStatus.FAIL) return false
        return when (factor) {
            OfficialEnvironmentFactor.GPS_QUALITY -> detail in setOf(
                "GPS_ACCURACY_OUTSIDE_APPROVED_RANGE", "GPS_SINGLE_OUTLIER_AGAINST_FRESH_ESTIMATE",
                "GPS_INVALID_ACCURACY", "GPS_NUMERICAL_FAILURE", "GPS_TRANSIENT_CORRUPT_JUMP",
            )
            OfficialEnvironmentFactor.CAMERA_QUALITY -> detail in setOf(
                CameraFrameQualityReason.FRAME_UNAVAILABLE.name,
                CameraFrameQualityReason.BRIGHTNESS_OUTSIDE_APPROVED_RANGE.name,
                CameraFrameQualityReason.OCCLUSION_OUTSIDE_APPROVED_RANGE.name,
                CameraFrameQualityReason.SHAKE_OUTSIDE_APPROVED_RANGE.name,
            )
            else -> false
        }
    }

    private fun MeasuredEnvironmentEvidence.withAdmittedGpsFailure(): MeasuredEnvironmentEvidence {
        val admitted = gpsMeasurementFailure ?: return this
        if (factor != OfficialEnvironmentFactor.GPS_QUALITY ||
            epoch != admitted.epoch || observedAtElapsedRealtimeMs != admitted.observedAtMs ||
            measurementProfileId != admitted.profileId
        ) return this
        val rejectedFix = status == EnvironmentEvidenceStatus.FAIL &&
            detail in setOf("TRUSTED_FIX_UNAVAILABLE", "GPS_SINGLE_OUTLIER_AGAINST_FRESH_ESTIMATE")
        val missingAccuracy = status == EnvironmentEvidenceStatus.UNKNOWN && detail == "INVALID_ACCURACY" &&
            admitted.failure == GpsMeasurementFailure.INVALID_ACCURACY
        if (!rejectedFix && !missingAccuracy) return this
        // Preserve the source's actual single-outlier qualification. A corruption cause
        // without that qualification must retain its hard rejection instead of borrowing PASS.
        if (admitted.failure == GpsMeasurementFailure.CORRUPT_JUMP &&
            detail == "GPS_SINGLE_OUTLIER_AGAINST_FRESH_ESTIMATE"
        ) return this
        return copy(detail = "GPS_${admitted.failure.name}")
    }
}
