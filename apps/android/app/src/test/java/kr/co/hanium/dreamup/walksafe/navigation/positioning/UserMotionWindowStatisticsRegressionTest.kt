package kr.co.hanium.dreamup.walksafe.navigation.positioning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ADD-MOTION-010: source statistics must not disappear behind identical coverage/classification. */
class UserMotionWindowStatisticsRegressionTest {
    @Test
    fun distinctGyroscopeStatisticsRemainDistinctWithTheSameWindowAndRotationState() {
        val first = snapshot(0.02, 0.04)
        val second = snapshot(0.06, 0.08)
        assertEquals(UserDeviceRotationState.STABLE, first.deviceRotation)
        assertEquals(first.deviceRotation, second.deviceRotation)
        assertEquals(UserMovementState.UNKNOWN, first.movement)
        assertEquals(first.movement, second.movement)
        val a = first.evidenceRefs.single { it.source == UserMotionEvidenceSource.GYROSCOPE_WINDOW }
        val b = second.evidenceRefs.single { it.source == UserMotionEvidenceSource.GYROSCOPE_WINDOW }
        assertEquals(a.windowCoverage, b.windowCoverage)
        assertEquals(UserMotionSensorEvidence(gyroscopeRmsRadPerSecond = 0.02, gyroscopePeakRadPerSecond = 0.04), a.sensorEvidence)
        assertEquals(UserMotionSensorEvidence(gyroscopeRmsRadPerSecond = 0.06, gyroscopePeakRadPerSecond = 0.08), b.sensorEvidence)
        assertNull(a.observationId)
        assertNull(b.observationId)
        assertNotEquals("The original gyro statistics must survive motion publication", a, b)
    }

    @Test
    fun accelerationAndGyroscopeFactsStaySeparateThroughTheCoordinator() {
        val decision = PedestrianStationaryDetector().observe(sample())
        assertTrue(decision.accepted)
        val published = PositioningCoordinator().observeUserMotion(decision, 1_100L)
        val acceleration = published.evidenceRefs.single { it.source == UserMotionEvidenceSource.ACCELEROMETER_WINDOW }
        val gyro = published.evidenceRefs.single { it.source == UserMotionEvidenceSource.GYROSCOPE_WINDOW }
        assertEquals(UserMotionSensorEvidence(accelerationMagnitudeMeanMps2 = 9.81,
            accelerationMagnitudeVarianceMps4 = 0.02), acceleration.sensorEvidence)
        assertEquals(UserMotionSensorEvidence(gyroscopeRmsRadPerSecond = 0.02,
            gyroscopePeakRadPerSecond = 0.04), gyro.sensorEvidence)
    }

    @Test
    fun missingStatisticsRemainUnknownInsteadOfBecomingZero() {
        val input = sample().copy(accelerationMagnitudeVarianceMps4 = null, gyroscopePeakRadPerSecond = null)
        val decision = PedestrianStationaryDetector().observe(input)
        assertTrue(decision.accepted)
        val published = UserMotionStateEstimator().also { it.observeMotion(decision, 1_100L) }.peek(1_100L)
        assertEquals(UserMotionSensorEvidence(accelerationMagnitudeMeanMps2 = 9.81),
            published.evidenceRefs.single { it.source == UserMotionEvidenceSource.ACCELEROMETER_WINDOW }.sensorEvidence)
        assertEquals(UserMotionSensorEvidence(gyroscopeRmsRadPerSecond = 0.02),
            published.evidenceRefs.single { it.source == UserMotionEvidenceSource.GYROSCOPE_WINDOW }.sensorEvidence)
    }

    @Test
    fun repackagingKeepsTheFirstStatisticsAndReceiptWithoutExtendingExpiry() {
        val estimator = UserMotionStateEstimator()
        estimator.observeMotion(PedestrianStationaryDetector().observe(sample()), 1_100L)
        val first = estimator.peek(1_100L)
        estimator.observeMotion(PedestrianStationaryDetector().observe(sample().copy(
            elapsedRealtimeMs = 1_150L, gyroscopeRmsRadPerSecond = 0.06, gyroscopePeakRadPerSecond = 0.08)), 1_200L)
        val repeated = estimator.peek(1_200L)
        assertEquals(first.evidenceRefs, repeated.evidenceRefs)
        assertEquals(first.expiresAtElapsedRealtimeMs, repeated.expiresAtElapsedRealtimeMs)
        assertTrue(estimator.peek(1_251L).evidenceRefs.isEmpty())
        estimator.reset()
        assertTrue(estimator.peek(1_300L).evidenceRefs.isEmpty())
        assertEquals(0.02, first.evidenceRefs.single {
            it.source == UserMotionEvidenceSource.GYROSCOPE_WINDOW }.sensorEvidence?.gyroscopeRmsRadPerSecond)
    }

    @Test
    fun resetClearsBothActiveSourcePayloadsWithoutChangingThePublishedSnapshot() {
        val estimator = UserMotionStateEstimator()
        estimator.observeMotion(PedestrianStationaryDetector().observe(sample()), 1_100L)
        val published = estimator.peek(1_100L)
        assertEquals(2, published.evidenceRefs.size)
        estimator.reset()
        assertTrue(estimator.peek(1_100L).evidenceRefs.isEmpty())
        assertEquals(9.81, published.evidenceRefs.single {
            it.source == UserMotionEvidenceSource.ACCELEROMETER_WINDOW }.sensorEvidence?.accelerationMagnitudeMeanMps2)
        assertEquals(0.02, published.evidenceRefs.single {
            it.source == UserMotionEvidenceSource.GYROSCOPE_WINDOW }.sensorEvidence?.gyroscopeRmsRadPerSecond)
    }

    private fun sample() = PedestrianMotionSample(1_000L, accelerationMagnitudeMeanMps2 = 9.81,
        accelerationMagnitudeVarianceMps4 = 0.02, gyroscopeRmsRadPerSecond = 0.02,
        gyroscopePeakRadPerSecond = 0.04,
        accelerationWindow = PedestrianMotionWindowCoverage(5, 800L, 1_000L, 50L),
        gyroscopeWindow = PedestrianMotionWindowCoverage(5, 800L, 1_000L, 50L))

    private fun snapshot(rms: Double, peak: Double): UserMotionStateSnapshot {
        val window = PedestrianMotionWindowCoverage(5, 800L, 1_000L, 50L)
        val sample = PedestrianMotionSample(1_000L, accelerationMagnitudeMeanMps2 = 9.81,
            accelerationMagnitudeVarianceMps4 = 0.02, gyroscopeRmsRadPerSecond = rms,
            gyroscopePeakRadPerSecond = peak, accelerationWindow = window, gyroscopeWindow = window)
        val decision = PedestrianStationaryDetector().observe(sample)
        assertTrue(decision.accepted)
        return UserMotionStateEstimator().also { it.observeMotion(decision, 1_100L) }.snapshot(1_100L)
    }
}
