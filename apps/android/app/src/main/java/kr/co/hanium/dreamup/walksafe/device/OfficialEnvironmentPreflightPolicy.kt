package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.session.ApprovedOfficialEnvironmentProfile
import kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus
import kr.co.hanium.dreamup.walksafe.session.GpsQualityObservation
import kr.co.hanium.dreamup.walksafe.session.MeasuredEnvironmentEvidence
import kr.co.hanium.dreamup.walksafe.session.OfficialEnvironmentPolicy
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

data class OfficialEnvironmentGpsSample(
    val observedAtElapsedRealtimeMs: Long,
    val latitude: Double,
    val longitude: Double,
    val horizontalAccuracyMeters: Double?,
    val mock: Boolean,
)

/** Keeps structural location trust separate from the field-test accuracy threshold. */
object OfficialEnvironmentGpsPreflightPolicy {
    fun assess(
        currentEpoch: WalkRuntimeEpoch,
        nowElapsedRealtimeMs: Long,
        sample: OfficialEnvironmentGpsSample,
        approvedProfile: ApprovedOfficialEnvironmentProfile?,
    ): MeasuredEnvironmentEvidence = OfficialEnvironmentPolicy.assessGpsQuality(
        currentEpoch = currentEpoch,
        nowElapsedRealtimeMs = nowElapsedRealtimeMs,
        observation = GpsQualityObservation(
            epoch = currentEpoch,
            observedAtElapsedRealtimeMs = sample.observedAtElapsedRealtimeMs,
            trustedFixAvailable =
                !sample.mock &&
                    sample.latitude.isFinite() &&
                    sample.latitude in -90.0..90.0 &&
                    sample.longitude.isFinite() &&
                    sample.longitude in -180.0..180.0,
            horizontalAccuracyMeters = sample.horizontalAccuracyMeters,
        ),
        approvedProfile = approvedProfile,
    )

    fun selectEvidence(
        previous: MeasuredEnvironmentEvidence?,
        candidate: MeasuredEnvironmentEvidence,
        nowElapsedRealtimeMs: Long,
    ): MeasuredEnvironmentEvidence {
        if (previous == null) return candidate
        if (
            previous.factor != candidate.factor ||
            previous.epoch != candidate.epoch ||
            previous.measurementProfileId != candidate.measurementProfileId
        ) return candidate
        val previousObservedAtMs = previous.observedAtElapsedRealtimeMs ?: return candidate
        val previousMaximumAgeMs = previous.maximumEvidenceAgeMs ?: return candidate
        val previousAgeMs = nowElapsedRealtimeMs - previousObservedAtMs
        if (previousAgeMs !in 0L..previousMaximumAgeMs) return candidate
        val candidateObservedAtMs = candidate.observedAtElapsedRealtimeMs ?: return candidate
        if (candidateObservedAtMs < previousObservedAtMs) return previous
        return when {
            candidate.status == EnvironmentEvidenceStatus.PASS -> candidate
            previous.status == EnvironmentEvidenceStatus.PASS &&
                candidate.detail == "GPS_ACCURACY_OUTSIDE_APPROVED_RANGE" -> previous
            else -> candidate
        }
    }
}

enum class CameraPreflightStabilityDecision {
    ACCEPT,
    STABILIZING,
    RETAIN_PREVIOUS_PASS,
}

class CameraPreflightStabilityGate(
    private val requiredPasses: Int,
    private val requiredFailuresAfterPass: Int,
) {
    init {
        require(requiredPasses > 0) { "requiredPasses must be positive" }
        require(requiredFailuresAfterPass > 0) {
            "requiredFailuresAfterPass must be positive"
        }
    }

    var consecutivePasses: Int = 0
        private set
    var consecutiveFailures: Int = 0
        private set
    private var stablePass = false

    fun observe(
        status: EnvironmentEvidenceStatus,
        transientQualityFailure: Boolean,
    ): CameraPreflightStabilityDecision {
        if (status == EnvironmentEvidenceStatus.PASS) {
            consecutiveFailures = 0
            if (stablePass) {
                consecutivePasses = requiredPasses
                return CameraPreflightStabilityDecision.ACCEPT
            }
            consecutivePasses = (consecutivePasses + 1).coerceAtMost(requiredPasses)
            if (consecutivePasses >= requiredPasses) {
                stablePass = true
                return CameraPreflightStabilityDecision.ACCEPT
            }
            return CameraPreflightStabilityDecision.STABILIZING
        }

        consecutivePasses = 0
        if (
            stablePass &&
            status == EnvironmentEvidenceStatus.FAIL &&
            transientQualityFailure
        ) {
            consecutiveFailures += 1
            if (consecutiveFailures < requiredFailuresAfterPass) {
                return CameraPreflightStabilityDecision.RETAIN_PREVIOUS_PASS
            }
        }
        stablePass = false
        consecutiveFailures = 0
        return CameraPreflightStabilityDecision.ACCEPT
    }

    fun reset() {
        consecutivePasses = 0
        consecutiveFailures = 0
        stablePass = false
    }
}
