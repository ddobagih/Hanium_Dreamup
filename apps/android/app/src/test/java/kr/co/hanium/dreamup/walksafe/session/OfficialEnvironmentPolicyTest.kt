package kr.co.hanium.dreamup.walksafe.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialEnvironmentPolicyTest {
    @Test
    fun productionProfileRemainsUnapprovedAndFailsClosed() {
        assertEquals(null, OfficialEnvironmentPolicy.productionProfile)
    }

    @Test
    fun allMeasuredAndConfirmedFactorsMustPassForSupportedStartup() {
        val assessment = supportedAssessment()

        assertEquals(OfficialEnvironmentSupport.SUPPORTED, assessment.support)
        assertTrue(assessment.canStartWalk)
        assertTrue(assessment.blockingFactors.isEmpty())
    }

    @Test
    fun eachExplicitlyAdverseUserConditionIsUnsupported() {
        val confirmations = listOf(
            confirmation().copy(brightTime = EnvironmentEvidenceStatus.FAIL),
            confirmation().copy(dryWeather = EnvironmentEvidenceStatus.FAIL),
            confirmation().copy(noDenseFog = EnvironmentEvidenceStatus.FAIL),
            confirmation().copy(ordinaryUrbanSidewalk = EnvironmentEvidenceStatus.FAIL),
            confirmation().copy(noConstruction = EnvironmentEvidenceStatus.FAIL),
            confirmation().copy(noSevereCrowding = EnvironmentEvidenceStatus.FAIL),
            confirmation().copy(
                supportLimitsNoticeAcknowledged = EnvironmentEvidenceStatus.FAIL,
            ),
        )

        confirmations.forEach { adverse ->
            val assessment = assess(userConfirmation = adverse)

            assertEquals(OfficialEnvironmentSupport.UNSUPPORTED, assessment.support)
            assertFalse(assessment.canStartWalk)
        }
    }

    @Test
    fun missingUnknownStaleAndWrongEpochEvidenceAreLimitedAndBlocked() {
        val staleGps = gpsEvidence().copy(observedAtElapsedRealtimeMs = NOW_MS - MAX_AGE_MS - 1L)
        val wrongEpochCamera = cameraEvidence().copy(epoch = OTHER_EPOCH)
        val cases = listOf(
            assess(gpsQuality = null),
            assess(cameraQuality = cameraEvidence().copy(status = EnvironmentEvidenceStatus.UNKNOWN)),
            assess(gpsQuality = staleGps),
            assess(cameraQuality = wrongEpochCamera),
            assess(userConfirmation = confirmation().copy(epoch = OTHER_EPOCH)),
            assess(approvedProfile = null),
        )

        cases.forEach { assessment ->
            assertEquals(OfficialEnvironmentSupport.LIMITED, assessment.support)
            assertFalse(assessment.canStartWalk)
        }
    }

    @Test
    fun gpsQualityRejectsUnavailablePoorInvalidAndStaleFixes() {
        val unavailable = OfficialEnvironmentPolicy.assessGpsQuality(
            EPOCH,
            NOW_MS,
            gpsObservation().copy(trustedFixAvailable = false, horizontalAccuracyMeters = null),
            PROFILE,
        )
        val poor = OfficialEnvironmentPolicy.assessGpsQuality(
            EPOCH,
            NOW_MS,
            gpsObservation().copy(horizontalAccuracyMeters = GPS_LIMIT_M + 0.1),
            PROFILE,
        )
        val invalidValues = listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0).map { invalid ->
            OfficialEnvironmentPolicy.assessGpsQuality(
                EPOCH,
                NOW_MS,
                gpsObservation().copy(horizontalAccuracyMeters = invalid),
                PROFILE,
            )
        }
        val stale = OfficialEnvironmentPolicy.assessGpsQuality(
            EPOCH,
            NOW_MS,
            gpsObservation().copy(observedAtElapsedRealtimeMs = NOW_MS - MAX_AGE_MS - 1L),
            PROFILE,
        )
        val wrongEpoch = OfficialEnvironmentPolicy.assessGpsQuality(
            EPOCH,
            NOW_MS,
            gpsObservation().copy(epoch = OTHER_EPOCH),
            PROFILE,
        )

        assertEquals(EnvironmentEvidenceStatus.FAIL, unavailable.status)
        assertEquals(EnvironmentEvidenceStatus.FAIL, poor.status)
        (invalidValues + stale + wrongEpoch).forEach {
            assertEquals(EnvironmentEvidenceStatus.UNKNOWN, it.status)
        }
    }

    @Test
    fun gpsBoundaryPassesAndMissingProfileFailsClosedAsUnknown() {
        val atBoundary = OfficialEnvironmentPolicy.assessGpsQuality(
            EPOCH,
            NOW_MS,
            gpsObservation().copy(horizontalAccuracyMeters = GPS_LIMIT_M),
            PROFILE,
        )
        val withoutProfile = OfficialEnvironmentPolicy.assessGpsQuality(
            EPOCH,
            NOW_MS,
            gpsObservation(),
            approvedProfile = null,
        )

        assertEquals(EnvironmentEvidenceStatus.PASS, atBoundary.status)
        assertEquals(EnvironmentEvidenceStatus.UNKNOWN, withoutProfile.status)
    }

    @Test
    fun runtimeDegradationSuppressesImmediatelyThenSafeStopsAfterBoundedRetries() {
        val guard = OfficialEnvironmentRuntimeGuard(EPOCH, PROFILE)
        val degraded = assess(
            cameraQuality = cameraEvidence().copy(status = EnvironmentEvidenceStatus.FAIL),
        )

        val first = guard.onAssessment(degraded, NOW_MS)
        val secondAssessment = degraded.copy(assessedAtElapsedRealtimeMs = NOW_MS + 1L)
        val second = guard.onAssessment(secondAssessment, NOW_MS + 1L)
        val thirdAssessment = degraded.copy(assessedAtElapsedRealtimeMs = NOW_MS + 2L)
        val third = guard.onAssessment(thirdAssessment, NOW_MS + 2L)

        assertEquals(OfficialEnvironmentRuntimeAction.SUPPRESS_OUTPUTS_AND_RETRY, first.action)
        assertEquals(OfficialEnvironmentRuntimeAction.SUPPRESS_OUTPUTS_AND_RETRY, second.action)
        assertEquals(OfficialEnvironmentRuntimeAction.SAFE_STOP, third.action)
        assertTrue(first.suppressAllWalkOutputs)
        assertTrue(second.suppressAllWalkOutputs)
        assertTrue(third.suppressAllWalkOutputs)
        assertTrue(third.isTerminal)
    }

    @Test
    fun terminalSafeStopNeverAutomaticallyRecovers() {
        val guard = OfficialEnvironmentRuntimeGuard(
            EPOCH,
            PROFILE.copy(maximumRuntimeRetryAttempts = 0),
        )
        val stopped = guard.onAssessment(
            assess(cameraQuality = cameraEvidence().copy(status = EnvironmentEvidenceStatus.FAIL)),
            NOW_MS,
        )

        val afterRecovery = guard.onAssessment(
            supportedAssessment().copy(assessedAtElapsedRealtimeMs = NOW_MS + 1L),
            NOW_MS + 1L,
        )

        assertEquals(OfficialEnvironmentRuntimeAction.SAFE_STOP, stopped.action)
        assertEquals(OfficialEnvironmentRuntimeAction.SAFE_STOP, afterRecovery.action)
        assertTrue(afterRecovery.suppressAllWalkOutputs)
    }

    @Test
    fun recoveryBeforeTerminalClearsTheRetryBudget() {
        val guard = OfficialEnvironmentRuntimeGuard(EPOCH, PROFILE)
        val degraded = assess(
            cameraQuality = cameraEvidence().copy(status = EnvironmentEvidenceStatus.UNKNOWN),
        )

        assertEquals(
            OfficialEnvironmentRuntimeAction.SUPPRESS_OUTPUTS_AND_RETRY,
            guard.onAssessment(degraded, NOW_MS).action,
        )
        assertEquals(
            OfficialEnvironmentRuntimeAction.CONTINUE,
            guard.onAssessment(
                supportedAssessment().copy(assessedAtElapsedRealtimeMs = NOW_MS + 1L),
                NOW_MS + 1L,
            ).action,
        )
        assertEquals(0, guard.decision().consecutiveDegradations)
    }

    @Test
    fun missingRuntimeProfileIsTerminalAndSuppressesAllOutputs() {
        val decision = OfficialEnvironmentRuntimeGuard(EPOCH, approvedProfile = null).decision()

        assertEquals(OfficialEnvironmentRuntimeAction.SAFE_STOP, decision.action)
        assertTrue(decision.suppressAllWalkOutputs)
        assertTrue(decision.isTerminal)
    }

    @Test
    fun repeatedAssessmentDoesNotConsumeRetryBudgetAndStaleReplayCannotContinue() {
        val guard = OfficialEnvironmentRuntimeGuard(EPOCH, PROFILE)
        val supported = supportedAssessment()

        assertEquals(
            OfficialEnvironmentRuntimeAction.CONTINUE,
            guard.onAssessment(supported, NOW_MS).action,
        )
        val stale = guard.onAssessment(supported, NOW_MS + MAX_AGE_MS + 1L)
        val repeated = guard.onAssessment(supported, NOW_MS + MAX_AGE_MS + 2L)

        assertEquals(
            OfficialEnvironmentRuntimeAction.SUPPRESS_OUTPUTS_AND_RETRY,
            stale.action,
        )
        assertEquals(stale, repeated)
        assertEquals(1, repeated.consecutiveDegradations)
    }

    @Test
    fun cameraEvidenceKeepsItsStricterFreshnessLimit() {
        val camera = cameraEvidence().copy(
            observedAtElapsedRealtimeMs = NOW_MS - 101L,
            maximumEvidenceAgeMs = 100L,
        )

        val assessment = assess(cameraQuality = camera)

        assertEquals(
            EnvironmentEvidenceStatus.UNKNOWN,
            assessment.factorStatuses.getValue(OfficialEnvironmentFactor.CAMERA_QUALITY),
        )
        assertFalse(assessment.canStartWalk)
    }

    @Test
    fun sensorsMustOverlapInFreshnessAndARefreshedGpsRestoresReadiness() {
        val laterMs = NOW_MS + MAX_AGE_MS + 1L
        val freshCamera = cameraEvidence().copy(observedAtElapsedRealtimeMs = laterMs)
        val staleGpsAssessment = OfficialEnvironmentPolicy.assess(
            currentEpoch = EPOCH,
            nowElapsedRealtimeMs = laterMs,
            gpsQuality = gpsEvidence(),
            cameraQuality = freshCamera,
            userConfirmation = confirmation(),
            approvedProfile = PROFILE,
        )
        val refreshedGps = OfficialEnvironmentPolicy.assessGpsQuality(
            currentEpoch = EPOCH,
            nowElapsedRealtimeMs = laterMs,
            observation = gpsObservation().copy(observedAtElapsedRealtimeMs = laterMs),
            approvedProfile = PROFILE,
        )
        val refreshedAssessment = OfficialEnvironmentPolicy.assess(
            currentEpoch = EPOCH,
            nowElapsedRealtimeMs = laterMs,
            gpsQuality = refreshedGps,
            cameraQuality = freshCamera,
            userConfirmation = confirmation(),
            approvedProfile = PROFILE,
        )

        assertEquals(OfficialEnvironmentSupport.LIMITED, staleGpsAssessment.support)
        assertEquals(OfficialEnvironmentSupport.SUPPORTED, refreshedAssessment.support)
    }

    private fun supportedAssessment() = assess()

    private fun assess(
        gpsQuality: MeasuredEnvironmentEvidence? = gpsEvidence(),
        cameraQuality: MeasuredEnvironmentEvidence? = cameraEvidence(),
        userConfirmation: OfficialEnvironmentUserConfirmation? = confirmation(),
        approvedProfile: ApprovedOfficialEnvironmentProfile? = PROFILE,
    ) = OfficialEnvironmentPolicy.assess(
        currentEpoch = EPOCH,
        nowElapsedRealtimeMs = NOW_MS,
        gpsQuality = gpsQuality,
        cameraQuality = cameraQuality,
        userConfirmation = userConfirmation,
        approvedProfile = approvedProfile,
    )

    private fun gpsObservation() = GpsQualityObservation(
        epoch = EPOCH,
        observedAtElapsedRealtimeMs = NOW_MS,
        trustedFixAvailable = true,
        horizontalAccuracyMeters = 5.0,
    )

    private fun gpsEvidence() = OfficialEnvironmentPolicy.assessGpsQuality(
        currentEpoch = EPOCH,
        nowElapsedRealtimeMs = NOW_MS,
        observation = gpsObservation(),
        approvedProfile = PROFILE,
    )

    private fun cameraEvidence() = MeasuredEnvironmentEvidence(
        factor = OfficialEnvironmentFactor.CAMERA_QUALITY,
        epoch = EPOCH,
        observedAtElapsedRealtimeMs = NOW_MS,
        status = EnvironmentEvidenceStatus.PASS,
        measurementProfileId = CAMERA_PROFILE_ID,
        maximumEvidenceAgeMs = MAX_AGE_MS,
        detail = "PASSED",
    )

    private fun confirmation() = OfficialEnvironmentUserConfirmation(
        epoch = EPOCH,
        brightTime = EnvironmentEvidenceStatus.PASS,
        dryWeather = EnvironmentEvidenceStatus.PASS,
        noDenseFog = EnvironmentEvidenceStatus.PASS,
        ordinaryUrbanSidewalk = EnvironmentEvidenceStatus.PASS,
        noConstruction = EnvironmentEvidenceStatus.PASS,
        noSevereCrowding = EnvironmentEvidenceStatus.PASS,
        supportLimitsNoticeAcknowledged = EnvironmentEvidenceStatus.PASS,
    )

    private companion object {
        const val NOW_MS = 10_000L
        const val MAX_AGE_MS = 2_000L
        const val GPS_LIMIT_M = 15.0
        const val CAMERA_PROFILE_ID = "camera-field-approved-r001"
        val EPOCH = WalkRuntimeEpoch("walk-1", 1L)
        val OTHER_EPOCH = WalkRuntimeEpoch("walk-2", 1L)
        val PROFILE = ApprovedOfficialEnvironmentProfile(
            profileId = "official-environment-field-approved-r001",
            cameraQualityProfileId = CAMERA_PROFILE_ID,
            maximumGpsHorizontalAccuracyMeters = GPS_LIMIT_M,
            maximumMeasuredEvidenceAgeMs = MAX_AGE_MS,
            maximumRuntimeRetryAttempts = 2,
        )
    }
}
