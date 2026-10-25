package kr.co.hanium.dreamup.walksafe.navigation.positioning

import kr.co.hanium.dreamup.walksafe.navigation.StepEvent
import kr.co.hanium.dreamup.walksafe.navigation.StepEventConfidence
import kr.co.hanium.dreamup.walksafe.navigation.StepEventSource
import kr.co.hanium.dreamup.walksafe.navigation.UserMotionLocationEvidence
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class PositioningCoordinatorTurningHeadingAdmissionRegressionTest {
    @Test
    fun currentDeviceTurnDoesNotIntegrateGpsCourseFromBeforeTheGyroEvidence() {
        val coordinator = coordinator()
        val before = requireNotNull(coordinator.evaluate(1_000L).filtered)
        val originalGyro = sample()
        observeGyro(coordinator, originalGyro)

        val result = step(coordinator)

        val motion = requireNotNull(result.userMotion)
        assertEquals(UserDeviceRotationState.TURNING, motion.deviceRotation)
        assertEquals(UserMovementState.MOVING, motion.movement)
        assertEquals(UserMotionStateReason.STEP_OBSERVED, motion.reason)
        assertFalse(motion.zeroVelocityUpdateAllowed)
        assertTrue(motion.support.conflicts.isEmpty())
        val gyro = motion.evidenceRefs.single { it.source == UserMotionEvidenceSource.GYROSCOPE_WINDOW }
        assertEquals(originalGyro.gyroscopeWindow, gyro.windowCoverage)
        assertEquals(UserMotionSensorEvidence(gyroscopeRmsRadPerSecond = 0.3,
            gyroscopePeakRadPerSecond = 0.5), gyro.sensorEvidence)
        assertEquals(1_400L, gyro.measuredAtElapsedRealtimeMs)
        assertEquals(1_400L, gyro.receivedAtElapsedRealtimeMs)
        val stepRef = motion.evidenceRefs.single { it.stepEvidence != null }
        assertEquals(UserMotionStepEvidence(1, PdrStepQuality.HIGH), stepRef.stepEvidence)
        assertEquals(1_450L, stepRef.measuredAtElapsedRealtimeMs)
        assertEquals(1_500L, stepRef.receivedAtElapsedRealtimeMs)
        assertEquals(1_650L, motion.expiresAtElapsedRealtimeMs)
        assertCandidate(result, 0.0, 1_000L)
        assertEquals(PedestrianMotionUpdateDisposition.COVARIANCE_ONLY, result.motionUpdate?.disposition)
        val after = requireNotNull(result.filtered)
        assertEquals(before.localEnu, after.localEnu)
        assertEquals(before.coordinate, after.coordinate)
        assertTrue(after.horizontalUncertaintyM > before.horizontalUncertaintyM)
    }

    @Test
    fun futureGyroWindowDoesNotRestrictAnEarlierDelayedStep() {
        val coordinator = coordinator()
        val control = coordinator()
        val originalGyro = sample(at = 1_500L)
        observeGyro(coordinator, originalGyro, receipt = 1_500L)
        val heading = input(at = 1_200L, gpsAt = 1_200L)

        val result = step(coordinator, at = 1_200L, receipt = 1_550L, headingInput = heading)
        val expected = step(control, at = 1_200L, receipt = 1_550L, headingInput = heading)

        val motion = requireNotNull(result.userMotion)
        assertEquals(UserDeviceRotationState.TURNING, motion.deviceRotation)
        assertEquals(UserMovementState.MOVING, motion.movement)
        assertTrue(motion.support.conflicts.isEmpty())
        val gyro = motion.evidenceRefs.single { it.source == UserMotionEvidenceSource.GYROSCOPE_WINDOW }
        assertEquals(originalGyro.gyroscopeWindow, gyro.windowCoverage)
        assertEquals(UserMotionSensorEvidence(gyroscopeRmsRadPerSecond = 0.3,
            gyroscopePeakRadPerSecond = 0.5), gyro.sensorEvidence)
        assertEquals(1_500L, gyro.measuredAtElapsedRealtimeMs)
        assertEquals(1_500L, gyro.receivedAtElapsedRealtimeMs)
        val stepRef = motion.evidenceRefs.single { it.stepEvidence != null }
        assertEquals(UserMotionStepEvidence(1, PdrStepQuality.HIGH), stepRef.stepEvidence)
        assertEquals(1_200L, stepRef.measuredAtElapsedRealtimeMs)
        assertEquals(1_550L, stepRef.receivedAtElapsedRealtimeMs)
        assertEquals(1_750L, motion.expiresAtElapsedRealtimeMs)
        assertCandidate(result, 0.0, 1_200L)
        assertDirected(result)
        assertEquals(expected.motionUpdate, result.motionUpdate)
        assertEquals(expected.filtered, result.filtered)
    }

    @Test
    fun gyroEndBecomesRelevantExactlyAtTheStepMeasurementBoundary() {
        listOf(1_399L, 1_400L, 1_401L).forEach { stepAt ->
            val coordinator = coordinator()
            val before = requireNotNull(coordinator.evaluate(1_000L).filtered)
            observeGyro(coordinator)

            val result = step(coordinator, at = stepAt, headingInput = input(at = stepAt))

            assertEquals(UserDeviceRotationState.TURNING, result.userMotion?.deviceRotation)
            assertCandidate(result, 0.0, 1_000L)
            val gyro = requireNotNull(result.userMotion).evidenceRefs.single {
                it.source == UserMotionEvidenceSource.GYROSCOPE_WINDOW }
            assertEquals(1_400L, gyro.measuredAtElapsedRealtimeMs)
            if (stepAt < 1_400L) {
                assertDirected(result)
            } else {
                assertEquals(PedestrianMotionUpdateDisposition.COVARIANCE_ONLY, result.motionUpdate?.disposition)
                val after = requireNotNull(result.filtered)
                assertEquals(before.localEnu, after.localEnu)
                assertEquals(before.coordinate, after.coordinate)
                assertTrue(after.horizontalUncertaintyM > before.horizontalUncertaintyM)
            }
        }
    }

    @Test
    fun zeroTimeStepKeepsItsExistingAdmissionWhenGyroEvidenceIsLater() {
        val coordinator = coordinator(initialAt = 0L)
        val control = coordinator(initialAt = 0L)
        observeGyro(coordinator, sample(at = 200L), receipt = 200L)
        val heading = input(at = 0L, gpsAt = 0L)

        val result = step(coordinator, at = 0L, receipt = 250L, headingInput = heading)
        val expected = step(control, at = 0L, receipt = 250L, headingInput = heading)

        assertEquals(UserDeviceRotationState.TURNING, result.userMotion?.deviceRotation)
        assertEquals(UserMovementState.MOVING, result.userMotion?.movement)
        val gyro = requireNotNull(result.userMotion).evidenceRefs.single {
            it.source == UserMotionEvidenceSource.GYROSCOPE_WINDOW }
        assertEquals(PedestrianMotionWindowCoverage(5, 0L, 200L, 50L), gyro.windowCoverage)
        assertEquals(200L, gyro.measuredAtElapsedRealtimeMs)
        assertEquals(200L, gyro.receivedAtElapsedRealtimeMs)
        assertCandidate(result, 0.0, 0L)
        assertDirected(result)
        assertEquals(expected.motionUpdate, result.motionUpdate)
        assertEquals(expected.filtered, result.filtered)
    }

    @Test
    fun gpsCourseAtOrAfterGyroEvidenceRemainsDirected() {
        listOf(1_400L, 1_450L).forEach { sourceAt ->
            val coordinator = coordinator()
            observeGyro(coordinator)
            val result = step(coordinator, headingInput = input(gpsAt = sourceAt, heading = 90.0))

            assertCandidate(result, 90.0, sourceAt)
            assertDirected(result)
            assertEquals(UserDeviceRotationState.TURNING, result.userMotion?.deviceRotation)
        }
    }

    @Test
    fun magneticHeadingFromBeforeGyroEvidenceDoesNotMovePositionMean() {
        val coordinator = coordinator()
        observeGyro(coordinator)

        val result = step(coordinator, headingInput = magneticInput(1_350L))

        assertCandidate(result, 90.0, 1_350L)
        assertEquals(PedestrianHeadingSource.MAGNETIC_TRUE, result.selectedHeading?.source)
        assertEquals(PedestrianMotionUpdateDisposition.COVARIANCE_ONLY, result.motionUpdate?.disposition)
        assertEquals(LocalEnuPosition(0.0, 0.0), requireNotNull(result.filtered).localEnu)
    }

    @Test
    fun magneticHeadingAtOrAfterGyroEvidenceRemainsDirected() {
        listOf(1_400L, 1_450L).forEach { sourceAt ->
            val coordinator = coordinator()
            observeGyro(coordinator)
            val result = step(coordinator, headingInput = magneticInput(sourceAt))

            assertCandidate(result, 90.0, sourceAt)
            assertEquals(PedestrianHeadingSource.MAGNETIC_TRUE, result.selectedHeading?.source)
            assertDirected(result)
        }
    }

    @Test
    fun validatedGpsTransportAtOrAfterGyroEvidenceRemainsDirected() {
        listOf(1_400L, 1_450L).forEach { rotationAt ->
            val coordinator = coordinator()
            observeGyro(coordinator)
            val result = step(coordinator, headingInput = transportedInput(rotationAt))

            assertCandidate(result, 90.0, 1_000L, rotationAt, sqrt(75.0))
            assertEquals(PedestrianHeadingSource.GPS_COURSE, result.selectedHeading?.source)
            assertDirected(result)
        }
    }

    @Test
    fun validatedTransportStillBeforeGyroEvidenceCannotDirectTheStep() {
        val coordinator = coordinator()
        observeGyro(coordinator)

        val result = step(coordinator, headingInput = transportedInput(1_350L))

        assertCandidate(result, 90.0, 1_000L, 1_350L, sqrt(75.0))
        assertEquals(PedestrianMotionUpdateDisposition.COVARIANCE_ONLY, result.motionUpdate?.disposition)
        assertEquals(LocalEnuPosition(0.0, 0.0), requireNotNull(result.filtered).localEnu)
    }

    @Test
    fun invalidTransportBaselineCannotUpgradeTheOriginalGpsMeasurementTime() {
        val coordinator = coordinator()
        observeGyro(coordinator)
        val heading = transportedInput(1_450L).copy(
            magneticTrueHeadingAtGpsCourse = HeadingObservation(0.0, 5.0, 1_001L),
        )

        val result = step(coordinator, headingInput = heading)

        assertCandidate(result, 0.0, 1_000L)
        assertEquals(PedestrianMotionUpdateDisposition.COVARIANCE_ONLY, result.motionUpdate?.disposition)
        assertEquals(LocalEnuPosition(0.0, 0.0), requireNotNull(result.filtered).localEnu)
    }

    @Test
    fun stableAndUnknownGyroscopeKeepTheExistingCourseAdmission() {
        listOf(0.02 to UserDeviceRotationState.STABLE, 0.12 to UserDeviceRotationState.UNKNOWN).forEach { (rms, rotation) ->
            val coordinator = coordinator()
            observeGyro(coordinator, sample(rms = rms, peak = rms))
            val result = step(coordinator)

            assertEquals(rotation, result.userMotion?.deviceRotation)
            assertCandidate(result, 0.0, 1_000L)
            assertDirected(result)
        }
    }

    @Test
    fun absentInvalidInsufficientOrExpiredGyroDoesNotInventATurnRestriction() {
        val window = requireNotNull(sample().gyroscopeWindow)
        val inputs = listOf<PedestrianMotionSample?>(
            null,
            sample().copy(gyroscopeWindow = null),
            sample().copy(gyroscopeRmsRadPerSecond = Double.NaN),
            sample().copy(gyroscopeWindow = window.copy(sampleCount = 4)),
            sample().copy(gyroscopeWindow = window.copy(startedAtElapsedRealtimeMs = 1_201L)),
            sample().copy(gyroscopeWindow = window.copy(maximumGapMs = 251L)),
            sample(at = 1_000L),
        )
        inputs.forEach { original ->
            val coordinator = coordinator()
            if (original != null) observeGyro(coordinator, original,
                detectorAccepts = original.gyroscopeRmsRadPerSecond?.isFinite() != false)
            val result = step(coordinator)

            assertEquals(UserDeviceRotationState.UNKNOWN, result.userMotion?.deviceRotation)
            assertCandidate(result, 0.0, 1_000L)
            assertDirected(result)
        }
    }

    @Test
    fun epochCrossingGyroIsRejectedAndCannotConsumeTheFreshWindowWatermark() {
        val coordinator = coordinator()
        val current = owner(2L)
        assertTrue(coordinator.beginUserMotionEpoch(current, 1_200L))
        val crossing = sample().copy(gyroscopeWindow = PedestrianMotionWindowCoverage(5, 1_199L, 1_400L, 50L))
        observeGyro(coordinator, crossing)
        val first = step(coordinator, headingInput = input(gpsAt = 1_200L))
        assertEquals(UserDeviceRotationState.UNKNOWN, first.userMotion?.deviceRotation)
        assertFalse(requireNotNull(first.userMotion).evidenceRefs.any { it.source == UserMotionEvidenceSource.GYROSCOPE_WINDOW })
        assertDirected(first)

        observeGyro(coordinator, sample(), receipt = 1_500L)
        val after = step(coordinator, at = 1_500L, receipt = 1_550L, headingInput = input(at = 1_500L, gpsAt = 1_200L))

        assertEquals(UserDeviceRotationState.TURNING, after.userMotion?.deviceRotation)
        assertEquals(1_400L, requireNotNull(after.userMotion).evidenceRefs.single {
            it.source == UserMotionEvidenceSource.GYROSCOPE_WINDOW }.measuredAtElapsedRealtimeMs)
        assertEquals(PedestrianMotionUpdateDisposition.COVARIANCE_ONLY, after.motionUpdate?.disposition)
        assertEquals(first.filtered?.localEnu, after.filtered?.localEnu)
    }

    @Test
    fun oldOwnerAndGenerationGyroCannotPublishTurnEvidenceForTheCurrentStep() {
        val coordinator = coordinator()
        val current = owner(2L)
        assertTrue(coordinator.beginUserMotionEpoch(current, 900L))
        val generation = coordinator.currentInputGeneration()
        val oldOwner = observeGyro(coordinator, expectedOwner = owner(1L))
        val oldGeneration = observeGyro(coordinator, expectedGeneration = generation - 1L)
        assertEquals(UserMotionStateReason.OWNER_MISMATCH, oldOwner.reason)
        assertEquals(UserMotionStateReason.GENERATION_MISMATCH, oldGeneration.reason)
        val first = step(coordinator)
        assertEquals(UserDeviceRotationState.UNKNOWN, first.userMotion?.deviceRotation)
        assertDirected(first)

        observeGyro(coordinator, receipt = 1_500L)
        val after = step(coordinator, at = 1_500L, receipt = 1_550L, headingInput = input(at = 1_500L))
        assertEquals(UserDeviceRotationState.TURNING, after.userMotion?.deviceRotation)
        assertEquals(PedestrianMotionUpdateDisposition.COVARIANCE_ONLY, after.motionUpdate?.disposition)
        assertEquals(first.filtered?.localEnu, after.filtered?.localEnu)
        assertEquals(current, after.userMotion?.owner)
        assertEquals(generation, after.inputGeneration)
    }

    @Test
    fun counterBatchKeepsOriginalFactsWhilePreTurnCourseIsWithheld() {
        val coordinator = coordinator()
        observeGyro(coordinator)
        val original = StepEvent(3, 1_450L, StepEventSource.STEP_COUNTER, StepEventConfidence.MEDIUM,
            1_450_000_000L, 1_500_000_000L, 8)

        val result = step(coordinator, count = 3, quality = PdrStepQuality.MEDIUM, original = original, coversBatch = true)

        assertCandidate(result, 0.0, 1_000L)
        assertEquals(PedestrianMotionUpdateDisposition.COVARIANCE_ONLY, result.motionUpdate?.disposition)
        assertEquals(LocalEnuPosition(0.0, 0.0), requireNotNull(result.filtered).localEnu)
        val motion = requireNotNull(result.userMotion)
        val ref = motion.evidenceRefs.single { it.stepEvidence != null }
        assertEquals(UserMotionStepEvidence(3, PdrStepQuality.MEDIUM, StepEventSource.STEP_COUNTER,
            StepEventConfidence.MEDIUM, original), ref.stepEvidence)
        assertEquals(1_450L, ref.measuredAtElapsedRealtimeMs)
        assertEquals(1_500L, ref.receivedAtElapsedRealtimeMs)
        assertEquals(UserMovementState.MOVING, motion.movement)
        assertEquals(UserLocomotionState.UNKNOWN, motion.locomotion)
        assertEquals(UserDeviceRotationState.TURNING, motion.deviceRotation)
    }

    @Test
    fun lowQualityKeepsItsExistingLimitWithoutCreatingWalkingEvidence() {
        val coordinator = coordinator()
        observeGyro(coordinator)

        val result = step(coordinator, quality = PdrStepQuality.LOW)

        assertCandidate(result, 0.0, 1_000L)
        assertEquals(PedestrianMotionUpdateDisposition.COVARIANCE_ONLY, result.motionUpdate?.disposition)
        assertEquals(UserDeviceRotationState.TURNING, result.userMotion?.deviceRotation)
        assertEquals(UserMovementState.UNKNOWN, result.userMotion?.movement)
        assertNull(result.userMotion?.stepObservedAtElapsedRealtimeMs)
        assertFalse(requireNotNull(result.userMotion).evidenceRefs.any { it.stepEvidence != null })
    }

    @Test
    fun newCourseWithConservativeSmoothingAccuracyKeepsTheExistingCovarianceOnlyPath() {
        val coordinator = coordinator()
        val control = coordinator()
        step(coordinator, at = 1_300L, receipt = 1_300L, headingInput = input(at = 1_300L, gpsAt = 1_300L))
        step(control, at = 1_300L, receipt = 1_300L, headingInput = input(at = 1_300L, gpsAt = 1_300L))
        observeGyro(coordinator)
        val freshInput = input(gpsAt = 1_450L, heading = 90.0)

        val result = step(coordinator, headingInput = freshInput)
        val expected = step(control, headingInput = freshInput)

        assertEquals(1_450L, result.selectedHeading?.sourceObservationAtElapsedRealtimeMs)
        assertTrue(requireNotNull(result.selectedHeadingAccuracyDegrees) > 45.0)
        val heading = requireNotNull(result.selectedHeading).headingDegreesTrueNorth
        assertTrue(heading > 0.0 && heading < 90.0)
        assertEquals(expected.selectedHeading, result.selectedHeading)
        assertEquals(expected.selectedHeadingAccuracyDegrees, result.selectedHeadingAccuracyDegrees)
        assertEquals(expected.filtered, result.filtered)
        assertEquals(PedestrianMotionUpdateDisposition.COVARIANCE_ONLY, result.motionUpdate?.disposition)
    }

    @Test
    fun stationaryGnssConflictStillRestrictsAHeadingNewerThanTheTurn() {
        val coordinator = coordinator()
        coordinator.observeUserSpeed(WalkingSpeedObservation(0.0, 0.1, 1_000L),
            UserMotionLocationEvidence(1_000L, 2.0, false, 11L), 1_100L)
        observeGyro(coordinator)
        val heading = magneticInput(1_450L).copy(speed = WalkingSpeedObservation(0.0, 0.1, 1_000L))

        val result = step(coordinator, headingInput = heading)

        assertCandidate(result, 90.0, 1_450L)
        assertEquals(UserDeviceRotationState.TURNING, result.userMotion?.deviceRotation)
        assertEquals(UserMovementState.MOVING, result.userMotion?.movement)
        assertEquals(UserMotionStateReason.STEP_WITH_STATIONARY_GNSS,
            requireNotNull(result.userMotion).support.conflicts.single().reason)
        assertEquals(PedestrianMotionUpdateDisposition.COVARIANCE_ONLY, result.motionUpdate?.disposition)
        assertEquals(LocalEnuPosition(0.0, 0.0), requireNotNull(result.filtered).localEnu)
    }

    private fun coordinator(initialAt: Long = 1_000L) = PositioningCoordinator().also {
        val first = it.observeGnss(GnssPositionObservation(37.5, 127.0, 1.5, initialAt))
        assertEquals(GnssObservationDisposition.INITIALIZED, first.gnssUpdate?.disposition)
    }

    private fun sample(at: Long = 1_400L, rms: Double = 0.3, peak: Double = 0.5) =
        PedestrianMotionSample(at, gyroscopeRmsRadPerSecond = rms, gyroscopePeakRadPerSecond = peak,
            gyroscopeWindow = PedestrianMotionWindowCoverage(5, at - 200L, at, 50L))

    private fun observeGyro(coordinator: PositioningCoordinator, original: PedestrianMotionSample = sample(),
        receipt: Long = original.elapsedRealtimeMs, detectorAccepts: Boolean = true,
        expectedOwner: UserMotionStateOwner? = coordinator.currentUserMotionOwner(),
        expectedGeneration: Long = coordinator.currentInputGeneration()): UserMotionStateSnapshot {
        val decision = PedestrianStationaryDetector().observe(original)
        assertEquals(detectorAccepts, decision.accepted)
        return coordinator.observeUserMotion(decision, receipt, expectedGeneration, expectedOwner)
    }

    private fun input(at: Long = 1_450L, gpsAt: Long = 1_000L, heading: Double = 0.0) =
        PedestrianHeadingInput(at, WalkingSpeedObservation(1.0, 0.1, at),
            HeadingObservation(heading, 5.0, gpsAt), null, true)

    private fun magneticInput(sourceAt: Long) = input().copy(
        speed = WalkingSpeedObservation(0.3, 0.1, 1_450L), gpsCourse = null,
        magneticTrueHeading = HeadingObservation(90.0, 5.0, sourceAt))

    private fun transportedInput(rotationAt: Long) = input().copy(
        magneticTrueHeading = HeadingObservation(90.0, 5.0, rotationAt),
        magneticTrueHeadingAtGpsCourse = HeadingObservation(0.0, 5.0, 1_000L))

    private fun step(coordinator: PositioningCoordinator, at: Long = 1_450L, receipt: Long = 1_500L,
        headingInput: PedestrianHeadingInput = input(at = at), count: Int = 1, quality: PdrStepQuality = PdrStepQuality.HIGH,
        original: StepEvent? = null, coversBatch: Boolean = false) = coordinator.observeStep(
            count, headingInput, quality, receivedAtElapsedRealtimeMs = receipt,
            originalStepEvent = original, headingCoversWholeBatch = coversBatch)

    private fun owner(actor: Long) = UserMotionStateOwner(actor, WalkRuntimeEpoch("walk-$actor", 0L), "boot-1", "process-1")

    private fun assertCandidate(result: PositioningSnapshot, heading: Double, sourceAt: Long,
        rotationAt: Long? = null, accuracy: Double = 5.0) {
        val selected = requireNotNull(result.selectedHeading)
        assertEquals(heading, selected.headingDegreesTrueNorth, 1e-9)
        assertEquals(sourceAt, selected.sourceObservationAtElapsedRealtimeMs)
        assertEquals(rotationAt, selected.rotationObservationAtElapsedRealtimeMs)
        assertEquals(accuracy, requireNotNull(result.selectedHeadingAccuracyDegrees), 1e-9)
    }

    private fun assertDirected(result: PositioningSnapshot) {
        assertEquals(PedestrianMotionUpdateDisposition.APPLIED, result.motionUpdate?.disposition)
        val enu = requireNotNull(result.filtered).localEnu
        assertTrue(enu.eastM != 0.0 || enu.northM != 0.0)
    }
}
