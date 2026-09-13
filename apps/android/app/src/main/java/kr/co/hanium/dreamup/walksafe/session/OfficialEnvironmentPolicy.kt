package kr.co.hanium.dreamup.walksafe.session

enum class EnvironmentEvidenceStatus {
    PASS,
    FAIL,
    UNKNOWN,
}

enum class OfficialEnvironmentSupport {
    SUPPORTED,
    LIMITED,
    UNSUPPORTED,
}

enum class OfficialEnvironmentFactor {
    GPS_QUALITY,
    CAMERA_QUALITY,
    BRIGHT_TIME,
    DRY_WEATHER,
    NO_DENSE_FOG,
    ORDINARY_URBAN_SIDEWALK,
    NO_CONSTRUCTION,
    NO_SEVERE_CROWDING,
    SUPPORT_LIMITS_NOTICE_ACKNOWLEDGED,
}

data class MeasuredEnvironmentEvidence(
    val factor: OfficialEnvironmentFactor,
    val epoch: WalkRuntimeEpoch?,
    val observedAtElapsedRealtimeMs: Long?,
    val status: EnvironmentEvidenceStatus,
    val measurementProfileId: String?,
    val maximumEvidenceAgeMs: Long?,
    val detail: String,
)

data class GpsQualityObservation(
    val epoch: WalkRuntimeEpoch,
    val observedAtElapsedRealtimeMs: Long,
    val trustedFixAvailable: Boolean?,
    val horizontalAccuracyMeters: Double?,
)

data class OfficialEnvironmentUserConfirmation(
    val epoch: WalkRuntimeEpoch,
    val brightTime: EnvironmentEvidenceStatus,
    val dryWeather: EnvironmentEvidenceStatus,
    val noDenseFog: EnvironmentEvidenceStatus,
    val ordinaryUrbanSidewalk: EnvironmentEvidenceStatus,
    val noConstruction: EnvironmentEvidenceStatus,
    val noSevereCrowding: EnvironmentEvidenceStatus,
    val supportLimitsNoticeAcknowledged: EnvironmentEvidenceStatus,
)

data class ApprovedOfficialEnvironmentProfile(
    val profileId: String,
    val cameraQualityProfileId: String,
    val maximumGpsHorizontalAccuracyMeters: Double,
    val maximumMeasuredEvidenceAgeMs: Long,
    val maximumRuntimeRetryAttempts: Int,
    // Allows two regular GNSS update intervals before consuming another retry.
    val runtimeRetryIntervalMs: Long = 3_000L,
) {
    init {
        require(profileId.isNotBlank()) { "profileId must not be blank" }
        require(cameraQualityProfileId.isNotBlank()) {
            "cameraQualityProfileId must not be blank"
        }
        require(
            maximumGpsHorizontalAccuracyMeters.isFinite() &&
                maximumGpsHorizontalAccuracyMeters > 0.0,
        ) {
            "maximumGpsHorizontalAccuracyMeters must be finite and positive"
        }
        require(maximumMeasuredEvidenceAgeMs >= 0L) {
            "maximumMeasuredEvidenceAgeMs must not be negative"
        }
        require(maximumRuntimeRetryAttempts >= 0) {
            "maximumRuntimeRetryAttempts must not be negative"
        }
        require(runtimeRetryIntervalMs > 0L) {
            "runtimeRetryIntervalMs must be positive"
        }
    }
}

data class OfficialEnvironmentAssessment(
    val epoch: WalkRuntimeEpoch,
    val assessedAtElapsedRealtimeMs: Long,
    val profileId: String?,
    val factorStatuses: Map<OfficialEnvironmentFactor, EnvironmentEvidenceStatus>,
    val usageLimitsAcknowledged: Boolean = false,
    val enabledMeasuredFactors: Set<OfficialEnvironmentFactor> = setOf(
        OfficialEnvironmentFactor.GPS_QUALITY,
        OfficialEnvironmentFactor.CAMERA_QUALITY,
    ),
    // Runtime gates such as mounting correction can block outputs despite good raw quality.
    val runtimeUnavailableMeasuredFactors: Set<OfficialEnvironmentFactor> = emptySet(),
) {
    init {
        require(factorStatuses.keys == OfficialEnvironmentFactor.entries.toSet()) {
            "An environment assessment must include every factor"
        }
        require(enabledMeasuredFactors.all {
            it == OfficialEnvironmentFactor.GPS_QUALITY || it == OfficialEnvironmentFactor.CAMERA_QUALITY
        }) { "Only measured factors can be enabled or disabled" }
        require(runtimeUnavailableMeasuredFactors.all {
            it == OfficialEnvironmentFactor.GPS_QUALITY || it == OfficialEnvironmentFactor.CAMERA_QUALITY
        }) { "Runtime output restrictions must identify measured factors" }
    }

    private val applicableStatuses: Map<OfficialEnvironmentFactor, EnvironmentEvidenceStatus>
        get() = factorStatuses.filterKeys {
            it in enabledMeasuredFactors ||
                (it != OfficialEnvironmentFactor.GPS_QUALITY && it != OfficialEnvironmentFactor.CAMERA_QUALITY)
        }

    val support: OfficialEnvironmentSupport
        get() = when {
            applicableStatuses.values.any { it == EnvironmentEvidenceStatus.FAIL } ->
                OfficialEnvironmentSupport.UNSUPPORTED

            applicableStatuses.values.all { it == EnvironmentEvidenceStatus.PASS } ->
                OfficialEnvironmentSupport.SUPPORTED

            else -> OfficialEnvironmentSupport.LIMITED
        }

    val canStartWalk: Boolean
        get() = profileId != null && (support == OfficialEnvironmentSupport.SUPPORTED ||
            (usageLimitsAcknowledged &&
                applicableStatuses.values.none { it == EnvironmentEvidenceStatus.FAIL } &&
                enabledMeasuredFactors.all { factorStatuses[it] == EnvironmentEvidenceStatus.PASS }))

    val conditionallyAllowed: Boolean
        get() = canStartWalk && support != OfficialEnvironmentSupport.SUPPORTED

    val blockingFactors: Set<OfficialEnvironmentFactor>
        get() = applicableStatuses
            .filterValues { it != EnvironmentEvidenceStatus.PASS }
            .keys
}

object OfficialEnvironmentPolicy {
    val productionProfile: ApprovedOfficialEnvironmentProfile? = null

    fun assessGpsQuality(
        currentEpoch: WalkRuntimeEpoch,
        nowElapsedRealtimeMs: Long,
        observation: GpsQualityObservation?,
        approvedProfile: ApprovedOfficialEnvironmentProfile?,
    ): MeasuredEnvironmentEvidence {
        fun unknown(detail: String) = MeasuredEnvironmentEvidence(
            factor = OfficialEnvironmentFactor.GPS_QUALITY,
            epoch = observation?.epoch,
            observedAtElapsedRealtimeMs = observation?.observedAtElapsedRealtimeMs,
            status = EnvironmentEvidenceStatus.UNKNOWN,
            measurementProfileId = approvedProfile?.profileId,
            maximumEvidenceAgeMs = approvedProfile?.maximumMeasuredEvidenceAgeMs,
            detail = detail,
        )

        val profile = approvedProfile ?: return unknown("PROFILE_NOT_APPROVED")
        val measured = observation ?: return unknown("MISSING_OBSERVATION")
        if (measured.epoch != currentEpoch) return unknown("EPOCH_MISMATCH")
        if (!isFresh(
                observedAtElapsedRealtimeMs = measured.observedAtElapsedRealtimeMs,
                nowElapsedRealtimeMs = nowElapsedRealtimeMs,
                maximumAgeMs = profile.maximumMeasuredEvidenceAgeMs,
            )
        ) {
            return unknown("STALE_OR_INVALID_TIMESTAMP")
        }

        return when (measured.trustedFixAvailable) {
            null -> unknown("TRUST_STATE_UNKNOWN")
            false -> measuredEvidence(
                measured = measured,
                profileId = profile.profileId,
                maximumEvidenceAgeMs = profile.maximumMeasuredEvidenceAgeMs,
                status = EnvironmentEvidenceStatus.FAIL,
                detail = "TRUSTED_FIX_UNAVAILABLE",
            )

            true -> {
                val accuracyMeters = measured.horizontalAccuracyMeters
                if (
                    accuracyMeters == null ||
                    !accuracyMeters.isFinite() ||
                    accuracyMeters < 0.0
                ) {
                    unknown("INVALID_ACCURACY")
                } else {
                    measuredEvidence(
                        measured = measured,
                        profileId = profile.profileId,
                        maximumEvidenceAgeMs = profile.maximumMeasuredEvidenceAgeMs,
                        status = if (
                            accuracyMeters <= profile.maximumGpsHorizontalAccuracyMeters
                        ) {
                            EnvironmentEvidenceStatus.PASS
                        } else {
                            EnvironmentEvidenceStatus.FAIL
                        },
                        detail = if (
                            accuracyMeters <= profile.maximumGpsHorizontalAccuracyMeters
                        ) {
                            "GPS_QUALITY_PASSED"
                        } else {
                            "GPS_ACCURACY_OUTSIDE_APPROVED_RANGE"
                        },
                    )
                }
            }
        }
    }

    fun assess(
        currentEpoch: WalkRuntimeEpoch,
        nowElapsedRealtimeMs: Long,
        gpsQuality: MeasuredEnvironmentEvidence?,
        cameraQuality: MeasuredEnvironmentEvidence?,
        userConfirmation: OfficialEnvironmentUserConfirmation?,
        approvedProfile: ApprovedOfficialEnvironmentProfile?,
        usageLimitsAcknowledged: Boolean = false,
    ): OfficialEnvironmentAssessment {
        val statuses = linkedMapOf<OfficialEnvironmentFactor, EnvironmentEvidenceStatus>()
        statuses[OfficialEnvironmentFactor.GPS_QUALITY] = normalizeMeasuredEvidence(
            evidence = gpsQuality,
            expectedFactor = OfficialEnvironmentFactor.GPS_QUALITY,
            expectedEpoch = currentEpoch,
            nowElapsedRealtimeMs = nowElapsedRealtimeMs,
            maximumAgeMs = approvedProfile?.maximumMeasuredEvidenceAgeMs,
            expectedMeasurementProfileId = approvedProfile?.profileId,
        )
        statuses[OfficialEnvironmentFactor.CAMERA_QUALITY] = normalizeMeasuredEvidence(
            evidence = cameraQuality,
            expectedFactor = OfficialEnvironmentFactor.CAMERA_QUALITY,
            expectedEpoch = currentEpoch,
            nowElapsedRealtimeMs = nowElapsedRealtimeMs,
            maximumAgeMs = approvedProfile?.maximumMeasuredEvidenceAgeMs,
            expectedMeasurementProfileId = approvedProfile?.cameraQualityProfileId,
        )

        val confirmation = userConfirmation?.takeIf { it.epoch == currentEpoch }
        statuses[OfficialEnvironmentFactor.BRIGHT_TIME] =
            confirmation?.brightTime ?: EnvironmentEvidenceStatus.UNKNOWN
        statuses[OfficialEnvironmentFactor.DRY_WEATHER] =
            confirmation?.dryWeather ?: EnvironmentEvidenceStatus.UNKNOWN
        statuses[OfficialEnvironmentFactor.NO_DENSE_FOG] =
            confirmation?.noDenseFog ?: EnvironmentEvidenceStatus.UNKNOWN
        statuses[OfficialEnvironmentFactor.ORDINARY_URBAN_SIDEWALK] =
            confirmation?.ordinaryUrbanSidewalk ?: EnvironmentEvidenceStatus.UNKNOWN
        statuses[OfficialEnvironmentFactor.NO_CONSTRUCTION] =
            confirmation?.noConstruction ?: EnvironmentEvidenceStatus.UNKNOWN
        statuses[OfficialEnvironmentFactor.NO_SEVERE_CROWDING] =
            confirmation?.noSevereCrowding ?: EnvironmentEvidenceStatus.UNKNOWN
        statuses[OfficialEnvironmentFactor.SUPPORT_LIMITS_NOTICE_ACKNOWLEDGED] =
            confirmation?.supportLimitsNoticeAcknowledged ?: EnvironmentEvidenceStatus.UNKNOWN

        return OfficialEnvironmentAssessment(
            epoch = currentEpoch,
            assessedAtElapsedRealtimeMs = nowElapsedRealtimeMs,
            profileId = approvedProfile?.profileId,
            factorStatuses = statuses,
            usageLimitsAcknowledged = usageLimitsAcknowledged,
        )
    }

    private fun measuredEvidence(
        measured: GpsQualityObservation,
        profileId: String,
        maximumEvidenceAgeMs: Long,
        status: EnvironmentEvidenceStatus,
        detail: String,
    ) = MeasuredEnvironmentEvidence(
        factor = OfficialEnvironmentFactor.GPS_QUALITY,
        epoch = measured.epoch,
        observedAtElapsedRealtimeMs = measured.observedAtElapsedRealtimeMs,
        status = status,
        measurementProfileId = profileId,
        maximumEvidenceAgeMs = maximumEvidenceAgeMs,
        detail = detail,
    )

    private fun normalizeMeasuredEvidence(
        evidence: MeasuredEnvironmentEvidence?,
        expectedFactor: OfficialEnvironmentFactor,
        expectedEpoch: WalkRuntimeEpoch,
        nowElapsedRealtimeMs: Long,
        maximumAgeMs: Long?,
        expectedMeasurementProfileId: String?,
    ): EnvironmentEvidenceStatus {
        if (
            evidence == null ||
            maximumAgeMs == null ||
            expectedMeasurementProfileId == null ||
            evidence.factor != expectedFactor ||
            evidence.epoch != expectedEpoch ||
            evidence.measurementProfileId != expectedMeasurementProfileId
        ) {
            return EnvironmentEvidenceStatus.UNKNOWN
        }
        val observedAt = evidence.observedAtElapsedRealtimeMs
            ?: return EnvironmentEvidenceStatus.UNKNOWN
        val evidenceMaximumAgeMs = evidence.maximumEvidenceAgeMs
            ?: return EnvironmentEvidenceStatus.UNKNOWN
        if (
            !isFresh(
                observedAt,
                nowElapsedRealtimeMs,
                minOf(maximumAgeMs, evidenceMaximumAgeMs),
            )
        ) {
            return EnvironmentEvidenceStatus.UNKNOWN
        }
        return evidence.status
    }

    private fun isFresh(
        observedAtElapsedRealtimeMs: Long,
        nowElapsedRealtimeMs: Long,
        maximumAgeMs: Long,
    ): Boolean {
        if (observedAtElapsedRealtimeMs < 0L || nowElapsedRealtimeMs < 0L) return false
        val ageMs = nowElapsedRealtimeMs - observedAtElapsedRealtimeMs
        return ageMs in 0L..maximumAgeMs
    }
}

enum class OfficialEnvironmentRuntimeAction {
    CONTINUE,
    SUPPRESS_OUTPUTS_AND_RETRY,
    SAFE_STOP,
}

data class OfficialEnvironmentRuntimeDecision(
    val epoch: WalkRuntimeEpoch,
    val profileId: String?,
    val action: OfficialEnvironmentRuntimeAction,
    val consecutiveDegradations: Int,
    val retryAttemptsRemaining: Int,
    val unavailableFactors: Set<OfficialEnvironmentFactor>,
    val enabledMeasuredFactors: Set<OfficialEnvironmentFactor>,
) {
    val suppressAllWalkOutputs: Boolean
        get() = action != OfficialEnvironmentRuntimeAction.CONTINUE

    val isTerminal: Boolean
        get() = action == OfficialEnvironmentRuntimeAction.SAFE_STOP

    val navigationOutputsAllowed: Boolean
        get() = !suppressAllWalkOutputs &&
            OfficialEnvironmentFactor.GPS_QUALITY in enabledMeasuredFactors &&
            OfficialEnvironmentFactor.GPS_QUALITY !in unavailableFactors

    val cameraOutputsAllowed: Boolean
        get() = !suppressAllWalkOutputs &&
            OfficialEnvironmentFactor.CAMERA_QUALITY in enabledMeasuredFactors &&
            OfficialEnvironmentFactor.CAMERA_QUALITY !in unavailableFactors
}

class OfficialEnvironmentRuntimeGuard(
    val epoch: WalkRuntimeEpoch,
    private val approvedProfile: ApprovedOfficialEnvironmentProfile?,
) {
    private var consecutiveDegradations = 0
    private var terminal = approvedProfile == null
    private var lastDegradationCountedAtMs: Long? = null
    private var unavailableFactors = OfficialEnvironmentFactor.entries.toSet()
    private var enabledMeasuredFactors = emptySet<OfficialEnvironmentFactor>()
    private var lastDecision = decision(
        action = if (terminal) {
            OfficialEnvironmentRuntimeAction.SAFE_STOP
        } else {
            OfficialEnvironmentRuntimeAction.SUPPRESS_OUTPUTS_AND_RETRY
        },
    )

    @Synchronized
    fun decision(): OfficialEnvironmentRuntimeDecision = lastDecision

    @Synchronized
    fun onAssessment(
        assessment: OfficialEnvironmentAssessment,
        nowElapsedRealtimeMs: Long,
    ): OfficialEnvironmentRuntimeDecision {
        if (terminal) return lastDecision
        val profile = checkNotNull(approvedProfile)
        val freshAssessment =
            assessment.assessedAtElapsedRealtimeMs >= 0L &&
                nowElapsedRealtimeMs >= 0L &&
                nowElapsedRealtimeMs - assessment.assessedAtElapsedRealtimeMs in
                0L..profile.maximumMeasuredEvidenceAgeMs
        val contextCurrent =
            freshAssessment &&
            assessment.epoch == epoch &&
                assessment.profileId == profile.profileId
        enabledMeasuredFactors = assessment.enabledMeasuredFactors
        unavailableFactors = if (contextCurrent) {
            assessment.blockingFactors +
                assessment.runtimeUnavailableMeasuredFactors.intersect(enabledMeasuredFactors)
        } else {
            OfficialEnvironmentFactor.entries.toSet()
        }
        val commonStatuses = assessment.factorStatuses.filterKeys {
            it != OfficialEnvironmentFactor.GPS_QUALITY &&
                it != OfficialEnvironmentFactor.CAMERA_QUALITY
        }.values
        val commonConditionsAllowed = contextCurrent &&
            commonStatuses.none { it == EnvironmentEvidenceStatus.FAIL } &&
            (assessment.usageLimitsAcknowledged ||
                commonStatuses.all { it == EnvironmentEvidenceStatus.PASS })
        val anyMeasuredFeatureAvailable = enabledMeasuredFactors.isEmpty() ||
            enabledMeasuredFactors.any { it !in unavailableFactors }
        if (commonConditionsAllowed && anyMeasuredFeatureAvailable) {
            consecutiveDegradations = 0
            lastDegradationCountedAtMs = null
            lastDecision = decision(OfficialEnvironmentRuntimeAction.CONTINUE)
            return lastDecision
        }

        // Sensor callbacks and watchdogs can assess the same fault many times per second.
        // Restrict outputs immediately, but spend retry attempts only after real time passes.
        val lastCountedAtMs = lastDegradationCountedAtMs
        if (lastCountedAtMs != null &&
            nowElapsedRealtimeMs - lastCountedAtMs < profile.runtimeRetryIntervalMs
        ) {
            lastDecision = decision(OfficialEnvironmentRuntimeAction.SUPPRESS_OUTPUTS_AND_RETRY)
            return lastDecision
        }

        lastDegradationCountedAtMs = nowElapsedRealtimeMs
        consecutiveDegradations += 1
        if (consecutiveDegradations > profile.maximumRuntimeRetryAttempts) {
            terminal = true
            lastDecision = decision(OfficialEnvironmentRuntimeAction.SAFE_STOP)
        } else {
            lastDecision = decision(OfficialEnvironmentRuntimeAction.SUPPRESS_OUTPUTS_AND_RETRY)
        }
        return lastDecision
    }

    private fun decision(
        action: OfficialEnvironmentRuntimeAction,
    ) = OfficialEnvironmentRuntimeDecision(
        epoch = epoch,
        profileId = approvedProfile?.profileId,
        action = action,
        consecutiveDegradations = consecutiveDegradations,
        retryAttemptsRemaining = (
            approvedProfile?.maximumRuntimeRetryAttempts?.minus(consecutiveDegradations) ?: 0
            ).coerceAtLeast(0),
        unavailableFactors = unavailableFactors,
        enabledMeasuredFactors = enabledMeasuredFactors,
    )
}
