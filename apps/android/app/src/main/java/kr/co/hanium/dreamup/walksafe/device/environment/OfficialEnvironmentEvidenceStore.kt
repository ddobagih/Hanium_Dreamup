package kr.co.hanium.dreamup.walksafe.device.environment

import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityAssessment
import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityObservation
import kr.co.hanium.dreamup.walksafe.device.CameraFrameQualityReason
import kr.co.hanium.dreamup.walksafe.device.DeviceCheckLocationFixPolicy
import kr.co.hanium.dreamup.walksafe.device.OfficialEnvironmentGpsPreflightPolicy
import kr.co.hanium.dreamup.walksafe.device.OfficialEnvironmentGpsSample
import kr.co.hanium.dreamup.walksafe.device.RuntimeMeasuredQualityGate
import kr.co.hanium.dreamup.walksafe.session.ApprovedOfficialEnvironmentProfile
import kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus
import kr.co.hanium.dreamup.walksafe.session.GpsQualityObservation
import kr.co.hanium.dreamup.walksafe.session.MeasuredEnvironmentEvidence
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentAssessment
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentFactor
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentPolicy
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentUserConfirmation
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

/** Measured evidence, not source subscriptions. Both supplied gates keep their original history. */
internal class OfficialEnvironmentEvidenceStore(
    val observationLock: Any,
    val gpsQualityGate: RuntimeMeasuredQualityGate,
    val cameraQualityGate: RuntimeMeasuredQualityGate,
) {
    @Volatile var userConfirmation: OfficialEnvironmentUserConfirmation? = null
        private set
    @Volatile var gpsEvidence: MeasuredEnvironmentEvidence? = null
        private set
    @Volatile var cameraEvidence: MeasuredEnvironmentEvidence? = null
        private set
    @Volatile var gpsDiagnosticAccuracyMeters: Double? = null
        private set
    @Volatile var gpsDiagnosticDetail: String? = null
        private set
    @Volatile var cameraDiagnosticObservation: CameraFrameQualityObservation? = null
        private set
    @Volatile var cameraDiagnosticReason: String? = null
        private set
    @Volatile private var latestRuntimeRawCameraAssessment: CameraFrameQualityAssessment? = null
    val latestRawCameraAssessment: CameraFrameQualityAssessment? get() = latestRuntimeRawCameraAssessment

    fun clearGpsEvidence() {
        gpsEvidence = null
        gpsDiagnosticAccuracyMeters = null
        gpsDiagnosticDetail = null
    }

    fun clearGpsEvidenceOnly() { gpsEvidence = null }

    fun clearCameraEvidence() { cameraEvidence = null }

    fun clearCameraDiagnostics() {
        cameraDiagnosticObservation = null
        cameraDiagnosticReason = null
    }

    fun markCameraFailure(reason: String) { cameraDiagnosticReason = reason }

    fun invalidate() {
        userConfirmation = null
        clearGpsEvidence()
        clearCameraEvidence()
        clearCameraDiagnostics()
    }

    fun prepareGpsForPreflight(
        nativeCheckRequested: Boolean,
        epoch: () -> WalkRuntimeEpoch,
        nowMs: () -> Long,
        stopSeparateGps: () -> Unit,
        continuousGpsEvidence: (WalkRuntimeEpoch, Long) -> MeasuredEnvironmentEvidence?,
    ) {
        gpsDiagnosticAccuracyMeters = null
        gpsDiagnosticDetail = null
        if (nativeCheckRequested) {
            stopSeparateGps()
            gpsEvidence = continuousGpsEvidence(epoch(), nowMs())
            gpsDiagnosticDetail = gpsEvidence?.detail
        } else {
            gpsEvidence = null
        }
    }

    fun markGpsPreflightFailure(
        reason: String,
        epoch: WalkRuntimeEpoch? = null,
        nowMs: Long? = null,
        profile: ApprovedOfficialEnvironmentProfile? = null,
    ) {
        gpsDiagnosticDetail = reason
        if (epoch != null && nowMs != null) {
            gpsEvidence = OfficialEnvironmentPolicy.assessGpsQuality(epoch, nowMs, null, profile)
        }
    }

    fun gpsPreflightMaximumAgeMs(profile: ApprovedOfficialEnvironmentProfile?): Long =
        minOf(DeviceCheckLocationFixPolicy.MAX_AGE_MS, profile?.maximumMeasuredEvidenceAgeMs ?: 0L)

    fun assessGpsPreflightSample(
        epoch: WalkRuntimeEpoch,
        nowMs: Long,
        sample: OfficialEnvironmentGpsSample,
        profile: ApprovedOfficialEnvironmentProfile?,
    ): MeasuredEnvironmentEvidence? {
        val observedAtMs = sample.observedAtElapsedRealtimeMs
        if (nowMs < 0L || observedAtMs < 0L || observedAtMs > nowMs ||
            nowMs - observedAtMs > gpsPreflightMaximumAgeMs(profile)
        ) return null
        return OfficialEnvironmentGpsPreflightPolicy.assess(epoch, nowMs, sample, profile)
    }

    fun acceptGpsPreflightSample(
        candidate: MeasuredEnvironmentEvidence,
        horizontalAccuracyMeters: Double?,
        nowMs: Long,
    ): MeasuredEnvironmentEvidence {
        val selected = OfficialEnvironmentGpsPreflightPolicy.selectEvidence(gpsEvidence, candidate, nowMs)
        gpsEvidence = selected
        if (selected === candidate) {
            gpsDiagnosticAccuracyMeters = horizontalAccuracyMeters
            gpsDiagnosticDetail = selected.detail
        }
        return selected
    }

    /** All source admission remains with the admitted general stream. */
    fun recordGpsObservation(
        currentEpoch: WalkRuntimeEpoch,
        epoch: WalkRuntimeEpoch,
        observedAtMs: Long,
        trustedFixAvailable: Boolean?,
        horizontalAccuracyMeters: Double?,
        nowMs: () -> Long,
        profile: () -> ApprovedOfficialEnvironmentProfile?,
        runtimeActive: (WalkRuntimeEpoch) -> Boolean,
        continuousEpoch: () -> WalkRuntimeEpoch?,
        separateGpsActive: () -> Boolean,
        walkRuntimeActive: () -> Boolean,
        singleOutlierAgainstFreshEstimate: Boolean,
        onRuntimeObservation: (WalkRuntimeEpoch, MeasuredEnvironmentEvidence?, MeasuredEnvironmentEvidence) -> Unit,
    ) {
        if (currentEpoch != epoch) return
        val previous = gpsEvidence
        val measuredAtMs = nowMs()
        val rawEvidence = OfficialEnvironmentPolicy.assessGpsQuality(
            epoch, measuredAtMs, GpsQualityObservation(epoch, observedAtMs, trustedFixAvailable, horizontalAccuracyMeters), profile(),
        ).let { measured ->
            if (singleOutlierAgainstFreshEstimate && measured.status == EnvironmentEvidenceStatus.FAIL &&
                measured.detail == "TRUSTED_FIX_UNAVAILABLE"
            ) measured.copy(detail = "GPS_SINGLE_OUTLIER_AGAINST_FRESH_ESTIMATE") else measured
        }
        val active = runtimeActive(epoch)
        val evidence = if (active || continuousEpoch() == epoch) gpsQualityGate.observe(rawEvidence, measuredAtMs) else rawEvidence
        if (!active && separateGpsActive() && continuousEpoch() != null) return
        gpsEvidence = evidence
        if (!active && continuousEpoch() != null) {
            gpsDiagnosticDetail = evidence.detail
            gpsDiagnosticAccuracyMeters = null
        }
        if (walkRuntimeActive()) onRuntimeObservation(epoch, previous, evidence)
    }

    fun prepareGpsForRuntime(
        epoch: () -> WalkRuntimeEpoch,
        nowMs: Long,
        continuousSourcePresent: () -> Boolean,
        continuousGpsEvidence: (WalkRuntimeEpoch, Long) -> MeasuredEnvironmentEvidence?,
    ) {
        if (continuousSourcePresent()) {
            gpsEvidence = continuousGpsEvidence(epoch(), nowMs)
            return
        }
        gpsQualityGate.reset()
        gpsEvidence?.let { gpsQualityGate.observe(it, nowMs) }
    }

    fun resetRuntimeRawCameraAssessment() = synchronized(observationLock) {
        cameraQualityGate.reset()
        latestRuntimeRawCameraAssessment = null
    }

    fun observeRuntimeRawCameraAssessment(assessment: CameraFrameQualityAssessment, nowMs: Long): Boolean =
        synchronized(observationLock) {
            val candidate = assessment.asMeasuredEnvironmentEvidence()
            cameraQualityGate.observe(candidate, nowMs)
            val latest = requireNotNull(cameraQualityGate.latestRawObservation())
            latestRuntimeRawCameraAssessment = when {
                latest == candidate -> assessment
                latestRuntimeRawCameraAssessment?.asMeasuredEnvironmentEvidence() == latest -> latestRuntimeRawCameraAssessment
                else -> CameraFrameQualityAssessment(
                    epoch = latest.epoch,
                    observedAtElapsedRealtimeMs = latest.observedAtElapsedRealtimeMs,
                    profileId = latest.measurementProfileId,
                    maximumEvidenceAgeMs = latest.maximumEvidenceAgeMs,
                    status = latest.status,
                    reason = CameraFrameQualityReason.entries.firstOrNull { it.name == latest.detail }
                        ?: CameraFrameQualityReason.MISSING_OBSERVATION,
                )
            }
            latest == candidate
        }

    fun recordCameraDiagnostic(
        observation: CameraFrameQualityObservation,
        evidence: MeasuredEnvironmentEvidence,
        overrideReason: String?,
    ) {
        cameraDiagnosticObservation = observation
        cameraDiagnosticReason = overrideReason ?: evidence.detail
    }

    /** Called in the shared observation transaction, after mounting/source admission. */
    fun commitCameraEvidence(evidence: MeasuredEnvironmentEvidence) { cameraEvidence = evidence }

    fun seedRuntimeCameraHistory(
        nowMs: Long,
        latestMountingCameraAssessment: () -> CameraFrameQualityAssessment?,
    ) = synchronized(observationLock) {
        resetRuntimeRawCameraAssessment()
        cameraEvidence?.let { cameraQualityGate.observe(it, nowMs) }
        latestMountingCameraAssessment()?.let { observeRuntimeRawCameraAssessment(it, nowMs) }
    }

    fun currentAssessment(
        epoch: WalkRuntimeEpoch,
        nowMs: Long,
        facts: OfficialEnvironmentAssessmentFactsReader,
        continuousGpsEvidence: (WalkRuntimeEpoch, Long) -> MeasuredEnvironmentEvidence?,
        updateMountingAssessment: (CameraFrameQualityAssessment) -> Unit,
    ): OfficialEnvironmentAssessment {
        if (facts.continuousSourcePresent() && (!facts.separateGpsActive() || facts.isRuntimeEpochCurrent(epoch))) {
            gpsEvidence = continuousGpsEvidence(epoch, nowMs)
            if (gpsDiagnosticDetail !in setOf("LOCATION_PERMISSION_MISSING", "LOCATION_SERVICE_DISABLED", "LOCATION_REQUEST_FAILED")) {
                gpsDiagnosticDetail = gpsEvidence?.detail
                gpsDiagnosticAccuracyMeters = null
            }
        }
        if (facts.isRuntimeEpochCurrent(epoch)) {
            gpsQualityGate.current(nowMs)?.let { gpsEvidence = it }
            synchronized(observationLock) {
                cameraQualityGate.current(nowMs)?.let { evidence ->
                    cameraEvidence = evidence
                    latestRuntimeRawCameraAssessment?.takeIf {
                        it.epoch == evidence.epoch && it.observedAtElapsedRealtimeMs == evidence.observedAtElapsedRealtimeMs && it.status == evidence.status
                    }?.let { raw ->
                        val reason = CameraFrameQualityReason.entries.firstOrNull { it.name == evidence.detail } ?: raw.reason
                        updateMountingAssessment(raw.copy(status = evidence.status, reason = reason))
                    }
                }
            }
        }
        val policy = facts.policyFacts()
        val assessment = OfficialEnvironmentPolicy.assess(epoch, nowMs, gpsEvidence, cameraEvidence, userConfirmation,
            policy.profile, policy.usageLimitsAcknowledged)
        val enabled = buildSet {
            if (facts.locationEnabled()) add(OfficialEnvironmentFactor.GPS_QUALITY)
            if (facts.cameraEnabled()) add(OfficialEnvironmentFactor.CAMERA_QUALITY)
        }
        val (runtimeUnavailable, recoverableUnavailable) = synchronized(observationLock) {
            val unavailable = if (OfficialEnvironmentFactor.CAMERA_QUALITY in enabled && facts.isRuntimeEpochCurrent(epoch) &&
                facts.mountingCameraRestricted(epoch)
            ) setOf(OfficialEnvironmentFactor.CAMERA_QUALITY) else emptySet()
            unavailable to if (unavailable.isNotEmpty() && facts.mountingCameraRestrictionRecoverable(epoch))
                unavailable else emptySet()
        }
        return assessment.copy(enabledMeasuredFactors = enabled, runtimeUnavailableMeasuredFactors = runtimeUnavailable,
            recoverableRuntimeUnavailableMeasuredFactors = recoverableUnavailable)
    }
}

internal data class OfficialEnvironmentPolicyFacts(
    val profile: ApprovedOfficialEnvironmentProfile?,
    val usageLimitsAcknowledged: Boolean,
)

/** Capture immutable policy facts after evidence refresh, matching the original read order. */
internal interface OfficialEnvironmentAssessmentFactsReader {
    fun continuousSourcePresent(): Boolean
    fun separateGpsActive(): Boolean
    fun isRuntimeEpochCurrent(epoch: WalkRuntimeEpoch): Boolean
    fun policyFacts(): OfficialEnvironmentPolicyFacts
    fun locationEnabled(): Boolean
    fun cameraEnabled(): Boolean
    fun mountingCameraRestricted(epoch: WalkRuntimeEpoch): Boolean
    fun mountingCameraRestrictionRecoverable(epoch: WalkRuntimeEpoch): Boolean = false
}
