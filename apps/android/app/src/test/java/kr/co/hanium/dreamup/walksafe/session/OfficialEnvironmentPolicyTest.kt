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
            gpsQuality = gpsEvidence().copy(status = EnvironmentEvidenceStatus.FAIL),
            cameraQuality = cameraEvidence().copy(status = EnvironmentEvidenceStatus.FAIL),
        )

        val first = guard.onAssessment(degraded, NOW_MS)
        val secondAtMs = NOW_MS + PROFILE.runtimeRetryIntervalMs
        val secondAssessment = degraded.copy(assessedAtElapsedRealtimeMs = secondAtMs)
        val second = guard.onAssessment(secondAssessment, secondAtMs)
        val thirdAtMs = secondAtMs + PROFILE.runtimeRetryIntervalMs
        val thirdAssessment = degraded.copy(assessedAtElapsedRealtimeMs = thirdAtMs)
        val third = guard.onAssessment(thirdAssessment, thirdAtMs)

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
            assess(gpsQuality = null, cameraQuality = null),
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
            gpsQuality = null,
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
    fun rapidCallbacksForOneFaultDoNotExhaustRetries() {
        val guard = OfficialEnvironmentRuntimeGuard(EPOCH, PROFILE)
        val degraded = assess(gpsQuality = null, cameraQuality = null)
        repeat(100) { index ->
            val now = NOW_MS + index
            val decision = guard.onAssessment(
                degraded.copy(assessedAtElapsedRealtimeMs = now), now,
            )
            assertEquals(OfficialEnvironmentRuntimeAction.SUPPRESS_OUTPUTS_AND_RETRY, decision.action)
            assertEquals(1, decision.consecutiveDegradations)
        }
        val atBoundary = NOW_MS + PROFILE.runtimeRetryIntervalMs
        val second = guard.onAssessment(
            degraded.copy(assessedAtElapsedRealtimeMs = atBoundary), atBoundary,
        )
        assertEquals(2, second.consecutiveDegradations)
        assertFalse(second.isTerminal)
    }

    @Test
    fun staleEvidenceRemainsSuppressedAndEventuallyStopsEvenWithoutNewCallbacks() {
        val guard = OfficialEnvironmentRuntimeGuard(EPOCH, PROFILE)
        val supported = supportedAssessment()
        assertEquals(OfficialEnvironmentRuntimeAction.CONTINUE, guard.onAssessment(supported, NOW_MS).action)
        val staleAtMs = NOW_MS + MAX_AGE_MS + 1L
        repeat(PROFILE.maximumRuntimeRetryAttempts + 1) { index ->
            val decision = guard.onAssessment(
                supported, staleAtMs + index * PROFILE.runtimeRetryIntervalMs,
            )
            assertTrue(decision.suppressAllWalkOutputs)
        }
        assertTrue(guard.decision().isTerminal)
    }

    @Test
    fun gpsOnlyFailureRestrictsNavigationAndKeepsCameraForContinuedUse() {
        val guard = OfficialEnvironmentRuntimeGuard(EPOCH, PROFILE)
        val degraded = assess(gpsQuality = null)
        repeat(5) { index ->
            val now = NOW_MS + index * PROFILE.runtimeRetryIntervalMs
            val decision = guard.onAssessment(degraded.copy(assessedAtElapsedRealtimeMs = now), now)
            assertEquals(OfficialEnvironmentRuntimeAction.CONTINUE, decision.action)
            assertFalse(decision.navigationOutputsAllowed)
            assertTrue(decision.cameraOutputsAllowed)
            assertEquals(setOf(OfficialEnvironmentFactor.GPS_QUALITY), decision.unavailableFactors)
            assertEquals(0, decision.consecutiveDegradations)
        }
    }

    @Test
    fun cameraOnlyFailureRestrictsCameraAndKeepsNavigationForContinuedUse() {
        val guard = OfficialEnvironmentRuntimeGuard(EPOCH, PROFILE)
        val degraded = assess(cameraQuality = null)
        repeat(5) { index ->
            val now = NOW_MS + index * PROFILE.runtimeRetryIntervalMs
            val decision = guard.onAssessment(degraded.copy(assessedAtElapsedRealtimeMs = now), now)
            assertEquals(OfficialEnvironmentRuntimeAction.CONTINUE, decision.action)
            assertTrue(decision.navigationOutputsAllowed)
            assertFalse(decision.cameraOutputsAllowed)
            assertEquals(setOf(OfficialEnvironmentFactor.CAMERA_QUALITY), decision.unavailableFactors)
            assertEquals(0, decision.consecutiveDegradations)
        }
    }

    @Test
    fun passingCameraQualityCannotHideMountingRestrictionWhenGpsIsUnavailable() {
        val guard = OfficialEnvironmentRuntimeGuard(EPOCH, PROFILE)
        val blocked = assess(gpsQuality = null).copy(
            runtimeUnavailableMeasuredFactors = setOf(OfficialEnvironmentFactor.CAMERA_QUALITY),
        )
        assertEquals(EnvironmentEvidenceStatus.PASS, blocked.factorStatuses[OfficialEnvironmentFactor.CAMERA_QUALITY])
        repeat(PROFILE.maximumRuntimeRetryAttempts + 1) { index ->
            val now = NOW_MS + index * PROFILE.runtimeRetryIntervalMs
            val decision = guard.onAssessment(blocked.copy(assessedAtElapsedRealtimeMs = now), now)
            assertTrue(decision.suppressAllWalkOutputs)
            assertFalse(decision.cameraOutputsAllowed)
            assertFalse(decision.navigationOutputsAllowed)
            assertEquals(index + 1, decision.consecutiveDegradations)
        }
        assertTrue(guard.decision().isTerminal)
    }

    @Test
    fun mountingCorrectionBeforeTerminalRestoresCameraWithoutRestoringGps() {
        val guard = OfficialEnvironmentRuntimeGuard(EPOCH, PROFILE)
        val cameraOnly = assess(gpsQuality = null)
        val blocked = cameraOnly.copy(
            runtimeUnavailableMeasuredFactors = setOf(OfficialEnvironmentFactor.CAMERA_QUALITY),
        )
        assertTrue(guard.onAssessment(blocked, NOW_MS).suppressAllWalkOutputs)
        val recovered = guard.onAssessment(
            cameraOnly.copy(assessedAtElapsedRealtimeMs = NOW_MS + 1L), NOW_MS + 1L,
        )
        assertEquals(OfficialEnvironmentRuntimeAction.CONTINUE, recovered.action)
        assertTrue(recovered.cameraOutputsAllowed)
        assertFalse(recovered.navigationOutputsAllowed)
        assertEquals(0, recovered.consecutiveDegradations)
    }

    @Test
    fun mountingRestrictionPreservesNavigationAndDoesNotRewritePreflightQuality() {
        val blocked = supportedAssessment().copy(
            runtimeUnavailableMeasuredFactors = setOf(OfficialEnvironmentFactor.CAMERA_QUALITY),
        )
        assertTrue(blocked.canStartWalk)
        assertEquals(OfficialEnvironmentSupport.SUPPORTED, blocked.support)
        assertEquals(EnvironmentEvidenceStatus.PASS, blocked.factorStatuses[OfficialEnvironmentFactor.CAMERA_QUALITY])
        val decision = OfficialEnvironmentRuntimeGuard(EPOCH, PROFILE).onAssessment(blocked, NOW_MS)
        assertEquals(OfficialEnvironmentRuntimeAction.CONTINUE, decision.action)
        assertTrue(decision.navigationOutputsAllowed)
        assertFalse(decision.cameraOutputsAllowed)
        assertEquals(setOf(OfficialEnvironmentFactor.CAMERA_QUALITY), decision.unavailableFactors)
    }

    @Test
    fun lossOfRemainingFunctionStartsTheGlobalRetryWindow() {
        val guard = OfficialEnvironmentRuntimeGuard(EPOCH, PROFILE)
        val gpsMissing = assess(gpsQuality = null)
        assertTrue(guard.onAssessment(gpsMissing, NOW_MS).cameraOutputsAllowed)
        val bothMissing = assess(gpsQuality = null, cameraQuality = null)
        val first = guard.onAssessment(
            bothMissing.copy(assessedAtElapsedRealtimeMs = NOW_MS + 1L), NOW_MS + 1L,
        )
        assertTrue(first.suppressAllWalkOutputs)
        assertFalse(first.navigationOutputsAllowed)
        assertFalse(first.cameraOutputsAllowed)
        assertFalse(first.isTerminal)
        assertEquals(1, first.consecutiveDegradations)
    }

    @Test
    fun oneRecoveredSensorRestoresOnlyItsFunctionBeforeGlobalStop() {
        val guard = OfficialEnvironmentRuntimeGuard(EPOCH, PROFILE)
        guard.onAssessment(assess(gpsQuality = null, cameraQuality = null), NOW_MS)
        val recovered = guard.onAssessment(
            assess(cameraQuality = null).copy(assessedAtElapsedRealtimeMs = NOW_MS + 1L), NOW_MS + 1L,
        )
        assertTrue(recovered.navigationOutputsAllowed)
        assertFalse(recovered.cameraOutputsAllowed)
        assertEquals(0, recovered.consecutiveDegradations)
        assertFalse(recovered.isTerminal)
    }

    @Test
    fun explicitCommonEnvironmentFailuresRestrictBothFunctions() {
        val supported = supportedAssessment()
        OfficialEnvironmentFactor.entries.filter {
            it != OfficialEnvironmentFactor.GPS_QUALITY &&
                it != OfficialEnvironmentFactor.CAMERA_QUALITY
        }.forEach { factor ->
            val failed = supported.copy(
                factorStatuses = supported.factorStatuses + (factor to EnvironmentEvidenceStatus.FAIL),
                usageLimitsAcknowledged = true,
            )
            val decision = OfficialEnvironmentRuntimeGuard(EPOCH, PROFILE).onAssessment(failed, NOW_MS)
            assertTrue(decision.suppressAllWalkOutputs)
            assertFalse(decision.navigationOutputsAllowed)
            assertFalse(decision.cameraOutputsAllowed)
        }
    }

    @Test
    fun disabledCameraIsExcludedWithoutChangingItsMeasuredFailureToPass() {
        val assessment = assess(cameraQuality = null).copy(
            enabledMeasuredFactors = setOf(OfficialEnvironmentFactor.GPS_QUALITY),
        )
        assertTrue(assessment.canStartWalk)
        assertEquals(EnvironmentEvidenceStatus.UNKNOWN, assessment.factorStatuses[OfficialEnvironmentFactor.CAMERA_QUALITY])
        val decision = OfficialEnvironmentRuntimeGuard(EPOCH, PROFILE).onAssessment(assessment, NOW_MS)
        assertTrue(decision.navigationOutputsAllowed)
        assertFalse(decision.cameraOutputsAllowed)
        assertFalse(decision.suppressAllWalkOutputs)
    }

    @Test
    fun disabledSensorCannotMasqueradeAsASurvivingIndependentFunction() {
        val assessment = assess(gpsQuality = null).copy(
            enabledMeasuredFactors = setOf(OfficialEnvironmentFactor.GPS_QUALITY),
        )
        assertFalse(assessment.canStartWalk)
        val decision = OfficialEnvironmentRuntimeGuard(EPOCH, PROFILE).onAssessment(assessment, NOW_MS)
        assertTrue(decision.suppressAllWalkOutputs)
        assertFalse(decision.navigationOutputsAllowed)
        assertFalse(decision.cameraOutputsAllowed)
    }

    @Test
    fun disablingBothMeasuredFeaturesDoesNotForceAnUnrelatedApplicationStop() {
        val assessment = educatedAssessment(gpsQuality = null, cameraQuality = null).copy(
            enabledMeasuredFactors = emptySet(),
        )
        assertTrue(assessment.canStartWalk)
        val decision = OfficialEnvironmentRuntimeGuard(EPOCH, PROFILE).onAssessment(assessment, NOW_MS)
        assertEquals(OfficialEnvironmentRuntimeAction.CONTINUE, decision.action)
        assertFalse(decision.navigationOutputsAllowed)
        assertFalse(decision.cameraOutputsAllowed)
        assertFalse(assessment.copy(profileId = null).canStartWalk)
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


    @Test
    fun educatedLimitsAllowFreshSensorsWithoutPromotingUnknownConditionsToPass() {
        val assessment = educatedAssessment()
        assertTrue(assessment.usageLimitsAcknowledged)
        assertEquals(OfficialEnvironmentSupport.LIMITED, assessment.support)
        assertTrue(assessment.conditionallyAllowed)
        OfficialEnvironmentFactor.entries.filter {
            it !in setOf(OfficialEnvironmentFactor.GPS_QUALITY, OfficialEnvironmentFactor.CAMERA_QUALITY)
        }.forEach {
            assertEquals(EnvironmentEvidenceStatus.UNKNOWN, assessment.factorStatuses.getValue(it))
        }
        val decision = OfficialEnvironmentRuntimeGuard(EPOCH, PROFILE).onAssessment(assessment, NOW_MS)
        assertEquals(OfficialEnvironmentRuntimeAction.CONTINUE, decision.action)
        assertFalse(decision.suppressAllWalkOutputs)
    }

    @Test
    fun educationNeverReplacesRequiredMeasuredQualityOrItsFreshness() {
        val failures = listOf(
            educatedAssessment(gpsQuality = null),
            educatedAssessment(cameraQuality = null),
            educatedAssessment(gpsQuality = gpsEvidence().copy(
                observedAtElapsedRealtimeMs = NOW_MS - MAX_AGE_MS - 1L,
            )),
            educatedAssessment(cameraQuality = cameraEvidence().copy(epoch = OTHER_EPOCH)),
            educatedAssessment(cameraQuality = cameraEvidence().copy(measurementProfileId = "wrong")),
            educatedAssessment(approvedProfile = null),
        )
        failures.forEach {
            assertFalse(it.canStartWalk)
            val decision = OfficialEnvironmentRuntimeGuard(EPOCH, PROFILE).onAssessment(it, NOW_MS)
            assertEquals(
                it.profileId == PROFILE.profileId &&
                    it.factorStatuses[OfficialEnvironmentFactor.GPS_QUALITY] == EnvironmentEvidenceStatus.PASS,
                decision.navigationOutputsAllowed,
            )
            assertEquals(
                it.profileId == PROFILE.profileId &&
                    it.factorStatuses[OfficialEnvironmentFactor.CAMERA_QUALITY] == EnvironmentEvidenceStatus.PASS,
                decision.cameraOutputsAllowed,
            )
        }
        assertFalse(educatedAssessment().copy(usageLimitsAcknowledged = false).canStartWalk)
    }

    @Test
    fun educationCannotOverrideObservedBadWeatherOrSensorFailure() {
        val weather = educatedAssessment().copy(
            factorStatuses = educatedAssessment().factorStatuses +
                (OfficialEnvironmentFactor.DRY_WEATHER to EnvironmentEvidenceStatus.FAIL),
        )
        val camera = educatedAssessment(
            cameraQuality = cameraEvidence().copy(status = EnvironmentEvidenceStatus.FAIL),
        )
        listOf(weather, camera).forEach {
            assertEquals(OfficialEnvironmentSupport.UNSUPPORTED, it.support)
            assertFalse(it.canStartWalk)
        }
        assertTrue(OfficialEnvironmentRuntimeGuard(EPOCH, PROFILE)
            .onAssessment(weather, NOW_MS).suppressAllWalkOutputs)
        val cameraDecision = OfficialEnvironmentRuntimeGuard(EPOCH, PROFILE).onAssessment(camera, NOW_MS)
        assertTrue(cameraDecision.navigationOutputsAllowed)
        assertFalse(cameraDecision.cameraOutputsAllowed)
    }

    @Test
    fun conditionalRuntimeKeepsFreshnessEpochAndProfileFences() {
        val assessment = educatedAssessment()
        val cases = listOf(
            assessment.copy(epoch = OTHER_EPOCH) to NOW_MS,
            assessment.copy(profileId = "wrong") to NOW_MS,
            assessment to NOW_MS + MAX_AGE_MS + 1L,
        )
        cases.forEach { (candidate, now) ->
            val decision = OfficialEnvironmentRuntimeGuard(EPOCH, PROFILE).onAssessment(candidate, now)
            assertEquals(OfficialEnvironmentRuntimeAction.SUPPRESS_OUTPUTS_AND_RETRY, decision.action)
            assertTrue(decision.suppressAllWalkOutputs)
        }
    }

    @Test
    fun conditionalRuntimeStopsAfterRealDegradationAndDoesNotAutoResume() {
        val guard = OfficialEnvironmentRuntimeGuard(EPOCH, PROFILE)
        assertEquals(OfficialEnvironmentRuntimeAction.CONTINUE, guard.onAssessment(educatedAssessment(), NOW_MS).action)
        val failed = educatedAssessment(gpsQuality = null, cameraQuality = null)
        repeat(PROFILE.maximumRuntimeRetryAttempts + 1) { index ->
            val now = NOW_MS + index * PROFILE.runtimeRetryIntervalMs + 1L
            guard.onAssessment(failed.copy(assessedAtElapsedRealtimeMs = now), now)
        }
        assertEquals(OfficialEnvironmentRuntimeAction.SAFE_STOP, guard.decision().action)
        assertTrue(guard.decision().suppressAllWalkOutputs)
        val recovered = guard.onAssessment(
            educatedAssessment().copy(assessedAtElapsedRealtimeMs = NOW_MS + 10_000L),
            NOW_MS + 10_000L,
        )
        assertEquals(OfficialEnvironmentRuntimeAction.SAFE_STOP, recovered.action)
        assertTrue(recovered.suppressAllWalkOutputs)
    }

    private fun educatedAssessment(
        gpsQuality: MeasuredEnvironmentEvidence? = gpsEvidence(),
        cameraQuality: MeasuredEnvironmentEvidence? = cameraEvidence(),
        approvedProfile: ApprovedOfficialEnvironmentProfile? = PROFILE,
    ) = OfficialEnvironmentPolicy.assess(
        currentEpoch = EPOCH,
        nowElapsedRealtimeMs = NOW_MS,
        gpsQuality = gpsQuality,
        cameraQuality = cameraQuality,
        userConfirmation = null,
        approvedProfile = approvedProfile,
        usageLimitsAcknowledged = true,
    )

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
