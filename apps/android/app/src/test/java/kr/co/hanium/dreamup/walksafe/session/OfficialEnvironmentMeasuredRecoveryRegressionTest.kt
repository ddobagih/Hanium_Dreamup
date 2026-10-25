package kr.co.hanium.dreamup.walksafe.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class OfficialEnvironmentMeasuredRecoveryRegressionTest {
    @Test
    fun sustainedRealMeasuredFailureRemainsRestrictedButRecoverableAfterTheRetryBudget() {
        val guard = OfficialEnvironmentRuntimeGuard(EPOCH, PROFILE)
        repeat(8) { index ->
            val now = NOW_MS + index * PROFILE.runtimeRetryIntervalMs
            val decision = guard.onAssessment(assessment(now, gpsPassing = false, cameraPassing = false), now)
            assertTrue(decision.suppressAllWalkOutputs)
            assertFalse(decision.navigationOutputsAllowed)
            assertFalse(decision.cameraOutputsAllowed)
            assertFalse("A recoverable measured-quality fault must not end the session", decision.isTerminal)
        }
        val restoredAt = NOW_MS + 8 * PROFILE.runtimeRetryIntervalMs
        val restored = guard.onAssessment(assessment(restoredAt), restoredAt)
        assertEquals(OfficialEnvironmentRuntimeAction.CONTINUE, restored.action)
        assertTrue(restored.navigationOutputsAllowed)
        assertTrue(restored.cameraOutputsAllowed)
        assertEquals(0, restored.consecutiveDegradations)
        assertEquals(EPOCH, restored.epoch)
    }

    @Test
    fun recoveryOfOnlyOneMeasuredFunctionRestoresOnlyThatFunctionAfterProlongedRestriction() {
        listOf(OfficialEnvironmentFactor.GPS_QUALITY, OfficialEnvironmentFactor.CAMERA_QUALITY).forEach { recovering ->
            val guard = OfficialEnvironmentRuntimeGuard(EPOCH, PROFILE)
            repeat(5) { index ->
                val time = NOW_MS + index * PROFILE.runtimeRetryIntervalMs
                guard.onAssessment(assessment(time, gpsPassing = false, cameraPassing = false), time)
            }
            val restoredAt = NOW_MS + 5 * PROFILE.runtimeRetryIntervalMs
            val restored = guard.onAssessment(
                assessment(
                    restoredAt,
                    gpsPassing = recovering == OfficialEnvironmentFactor.GPS_QUALITY,
                    cameraPassing = recovering == OfficialEnvironmentFactor.CAMERA_QUALITY,
                ), restoredAt,
            )
            assertFalse(restored.suppressAllWalkOutputs)
            assertEquals(recovering == OfficialEnvironmentFactor.GPS_QUALITY, restored.navigationOutputsAllowed)
            assertEquals(recovering == OfficialEnvironmentFactor.CAMERA_QUALITY, restored.cameraOutputsAllowed)
        }
    }

    @Test
    fun reobservingAStaleAssessmentDoesNotRestoreEitherFunction() {
        val guard = OfficialEnvironmentRuntimeGuard(EPOCH, PROFILE)
        repeat(4) { index ->
            val now = NOW_MS + index * PROFILE.runtimeRetryIntervalMs
            guard.onAssessment(assessment(now, gpsPassing = false, cameraPassing = false), now)
        }
        val staleAt = NOW_MS + 4 * PROFILE.runtimeRetryIntervalMs
        val stale = guard.onAssessment(assessment(NOW_MS), staleAt)
        assertFalse(stale.navigationOutputsAllowed)
        assertFalse(stale.cameraOutputsAllowed)
    }

    @Test
    fun hardSharedConditionStopsAndCannotBeReopenedByLaterMeasuredPasses() {
        val guard = OfficialEnvironmentRuntimeGuard(EPOCH, PROFILE)
        repeat(PROFILE.maximumRuntimeRetryAttempts + 1) { index ->
            val now = NOW_MS + index * PROFILE.runtimeRetryIntervalMs
            val current = assessment(now)
            guard.onAssessment(current.copy(factorStatuses = current.factorStatuses +
                (OfficialEnvironmentFactor.NO_CONSTRUCTION to EnvironmentEvidenceStatus.FAIL)), now)
        }
        assertTrue(guard.decision().isTerminal)
        val afterStop = NOW_MS + 3 * PROFILE.runtimeRetryIntervalMs
        val restored = guard.onAssessment(assessment(afterStop), afterStop)
        assertTrue(restored.isTerminal)
        assertFalse(restored.navigationOutputsAllowed)
        assertFalse(restored.cameraOutputsAllowed)
    }

    @Test
    fun explicitlyRecoverableRuntimeCameraRestrictionRecoversAfterTheRetryBudget() {
        val guard = OfficialEnvironmentRuntimeGuard(EPOCH, PROFILE)
        val camera = setOf(OfficialEnvironmentFactor.CAMERA_QUALITY)
        repeat(6) { index ->
            val now = NOW_MS + index * PROFILE.runtimeRetryIntervalMs
            val restricted = assessment(now, gpsPassing = false).copy(
                runtimeUnavailableMeasuredFactors = camera,
                recoverableRuntimeUnavailableMeasuredFactors = camera,
            )
            val decision = guard.onAssessment(restricted, now)
            assertTrue(decision.suppressAllWalkOutputs)
            assertFalse(decision.navigationOutputsAllowed)
            assertFalse(decision.cameraOutputsAllowed)
            assertFalse(decision.isTerminal)
        }
        val restoredAt = NOW_MS + 6 * PROFILE.runtimeRetryIntervalMs
        val restored = guard.onAssessment(assessment(restoredAt, gpsPassing = false), restoredAt)
        assertFalse(restored.suppressAllWalkOutputs)
        assertFalse(restored.navigationOutputsAllowed)
        assertTrue(restored.cameraOutputsAllowed)
    }

    @Test
    fun defaultRuntimeRestrictionRemainsHardAndCannotBeReclassifiedByAnotherSoftFactor() {
        val guard = OfficialEnvironmentRuntimeGuard(EPOCH, PROFILE)
        repeat(PROFILE.maximumRuntimeRetryAttempts + 1) { index ->
            val now = NOW_MS + index * PROFILE.runtimeRetryIntervalMs
            val restricted = assessment(now).copy(
                runtimeUnavailableMeasuredFactors = setOf(OfficialEnvironmentFactor.GPS_QUALITY, OfficialEnvironmentFactor.CAMERA_QUALITY),
                recoverableRuntimeUnavailableMeasuredFactors = setOf(OfficialEnvironmentFactor.GPS_QUALITY),
            )
            guard.onAssessment(restricted, now)
        }
        assertTrue(guard.decision().isTerminal)
        assertFalse(guard.decision().navigationOutputsAllowed)
        assertFalse(guard.decision().cameraOutputsAllowed)
    }

    @Test
    fun softRuntimeMetadataCannotDescribeAnUnrestrictedFactor() {
        assertThrows(IllegalArgumentException::class.java) {
            assessment(NOW_MS).copy(
                recoverableRuntimeUnavailableMeasuredFactors = setOf(OfficialEnvironmentFactor.CAMERA_QUALITY),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            assessment(NOW_MS).copy(
                runtimeUnavailableMeasuredFactors = setOf(OfficialEnvironmentFactor.CAMERA_QUALITY),
                recoverableRuntimeUnavailableMeasuredFactors = setOf(OfficialEnvironmentFactor.GPS_QUALITY),
            )
        }
    }

    @Test
    fun wrongEpochProfileFutureClockOrMissingMeasuredFeatureCannotUseSoftRecovery() {
        val invalidAssessments: List<(Long) -> OfficialEnvironmentAssessment> = listOf(
            { now -> assessment(now).copy(epoch = WalkRuntimeEpoch("other-walk", 1L)) },
            { now -> assessment(now).copy(profileId = "other-profile") },
            { now -> assessment(now).copy(assessedAtElapsedRealtimeMs = now + 1L) },
            { now -> assessment(now).copy(enabledMeasuredFactors = emptySet()) },
        )
        invalidAssessments.forEach { invalid ->
            val guard = OfficialEnvironmentRuntimeGuard(EPOCH, PROFILE)
            repeat(PROFILE.maximumRuntimeRetryAttempts + 1) { index ->
                val now = NOW_MS + index * PROFILE.runtimeRetryIntervalMs
                guard.onAssessment(invalid(now), now)
            }
            assertTrue(guard.decision().isTerminal)
            assertTrue(guard.decision().suppressAllWalkOutputs)
        }
        assertTrue(OfficialEnvironmentRuntimeGuard(EPOCH, approvedProfile = null).decision().isTerminal)
    }

    private fun assessment(time: Long, gpsPassing: Boolean = true, cameraPassing: Boolean = true) =
        OfficialEnvironmentPolicy.assess(
            currentEpoch = EPOCH,
            nowElapsedRealtimeMs = time,
            gpsQuality = OfficialEnvironmentPolicy.assessGpsQuality(
                currentEpoch = EPOCH,
                nowElapsedRealtimeMs = time,
                observation = GpsQualityObservation(EPOCH, time, true, if (gpsPassing) 3.0 else 90.0),
                approvedProfile = PROFILE,
            ),
            cameraQuality = MeasuredEnvironmentEvidence(
                factor = OfficialEnvironmentFactor.CAMERA_QUALITY,
                epoch = EPOCH,
                observedAtElapsedRealtimeMs = time,
                status = if (cameraPassing) EnvironmentEvidenceStatus.PASS else EnvironmentEvidenceStatus.FAIL,
                measurementProfileId = CAMERA_PROFILE,
                maximumEvidenceAgeMs = 5_000L,
                detail = if (cameraPassing) "PASSED" else "FRAME_UNAVAILABLE",
            ),
            userConfirmation = OfficialEnvironmentUserConfirmation(
                EPOCH, EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS,
                EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS,
                EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS,
            ),
            approvedProfile = PROFILE,
        )

    private companion object {
        const val NOW_MS = 10_000L
        const val CAMERA_PROFILE = "camera-field-approved-r001"
        val EPOCH = WalkRuntimeEpoch("stationary-walk", 1L)
        val PROFILE = ApprovedOfficialEnvironmentProfile("official-environment-field-approved-r001", CAMERA_PROFILE, 15.0, 10_000L, 2)
    }
}
