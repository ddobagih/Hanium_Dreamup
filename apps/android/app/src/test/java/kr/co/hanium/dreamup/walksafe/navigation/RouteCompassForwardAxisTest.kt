package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedGeomagneticReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/** A stationary instrumentation axis is not body direction or evidence of bearing accuracy. */
class RouteCompassForwardAxisTest {
    private val reference = ChestMountedGeomagneticReference(0.0, 40.0, 50.0)
    private val identity = RotationMatrix3(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private val top = RouteCompassForwardAxis.TOP_EDGE

    @Test
    fun defaultRearProjectionRejectsFlatPhoneWhileTopUsesTheMeasuredMatrix() {
        val orientation = DeviceEarthOrientation(identity, NOW, 5f, EarthOrientationAccuracy.HIGH)
        assertEquals(RouteCompassHeadingReason.REAR_AXIS_VERTICAL,
            RouteCompassHeading.evaluate(orientation, 0.0, NOW).reason)
        val result = RouteCompassHeading.evaluate(orientation, 0.0, NOW, forwardAxis = top)
        assertEquals(RouteCompassHeadingReason.VALID, result.reason)
        assertEquals(0.0, requireNotNull(result.observation).degreesTrueNorth, 0.0)
        assertEquals(NOW, result.observation?.observedAtElapsedRealtimeMs)
        assertEquals(5.0, requireNotNull(result.observation?.headingAccuracyDegrees), 0.0)
        assertSame("No matrix rotation or strict orientation replacement", identity, orientation.deviceToMagneticEnu)

        val eastTop = RotationMatrix3(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)
        val turned = RouteCompassHeading.evaluate(orientation.copy(deviceToMagneticEnu = eastTop),
            0.0, NOW, forwardAxis = top)
        assertEquals("This must follow the real top axis, not supply a fixed good heading",
            90.0, requireNotNull(turned.observation).degreesTrueNorth, 0.0)
    }

    @Test
    fun topProjectionKeepsRvTimeQualityAndReportedErrorGates() {
        val orientation = DeviceEarthOrientation(identity, NOW, 5f, EarthOrientationAccuracy.HIGH)
        fun result(value: DeviceEarthOrientation, now: Long = NOW) =
            RouteCompassHeading.evaluate(value, 0.0, now, forwardAxis = top)
        assertEquals(RouteCompassHeadingReason.STALE, result(orientation, NOW + 501L).reason)
        assertEquals(RouteCompassHeadingReason.INVALID_TIMESTAMP,
            result(orientation.copy(observedAtElapsedRealtimeMs = NOW + 1L)).reason)
        assertEquals(RouteCompassHeadingReason.QUALITY_LOW,
            result(orientation.copy(accuracy = EarthOrientationAccuracy.LOW)).reason)
        assertEquals(RouteCompassHeadingReason.HEADING_ACCURACY,
            result(orientation.copy(headingErrorDeg = 31f)).reason)
        assertEquals(RouteCompassHeadingReason.HEADING_ACCURACY_UNAVAILABLE,
            result(orientation.copy(headingErrorDeg = Float.NaN)).reason)
    }

    @Test
    fun fallbackOnlyChangesProjectionAndPreservesMeasuredFieldEvidence() {
        val gravity = gravity()
        val magnetic = magnetic()
        val rear = RouteCompassHeading.evaluateFallback(gravity, null, magnetic, reference, NOW)
        val result = fallback(gravity, magnetic)
        assertEquals(RouteCompassHeadingReason.REAR_AXIS_VERTICAL, rear.reason)
        assertEquals(RouteCompassHeadingReason.VALID, result.reason)
        assertEquals(0.0, requireNotNull(result.observation).degreesTrueNorth, 0.00001)
        assertNull("The diagnostic axis cannot invent numeric angular accuracy", result.observation?.headingAccuracyDegrees)
        assertEquals(rear.checkEvidence?.magneticFieldMicrotesla, result.checkEvidence?.magneticFieldMicrotesla)
        assertEquals(rear.checkEvidence?.magneticInclinationResidualDegrees, result.checkEvidence?.magneticInclinationResidualDegrees)
        assertEquals(NOW, result.observation?.observedAtElapsedRealtimeMs)
    }

    @Test
    fun topFallbackCannotWaiveStrengthDipTimeQualityOrHorizontalBasis() {
        assertEquals(RouteCompassHeadingReason.MAGNETIC_FIELD_ANOMALY,
            fallback(gravity(), magnetic(strength = 81.0)).reason)
        val badDip = fallback(gravity(), magnetic(dip = 80.0))
        assertEquals(RouteCompassRejectedCheck.MAGNETIC_FIELD_INCLINATION, badDip.checkEvidence?.rejectedCheck)
        assertNull(badDip.observation)
        assertEquals(RouteCompassHeadingReason.QUALITY_LOW,
            fallback(gravity(), magnetic().copy(accuracy = EarthOrientationAccuracy.LOW)).reason)
        assertEquals(RouteCompassHeadingReason.STALE,
            fallback(gravity(), magnetic(), nowMs = NOW + 501L).reason)
        assertEquals(RouteCompassHeadingReason.MAGNETIC_FIELD_MISSING,
            fallback(gravity(), null).reason)
        val nearlyParallel = RouteCompassHeading.evaluateFallback(gravity(), null, magnetic(dip = 87.0),
            reference.copy(inclinationDegrees = 60.0), NOW, forwardAxis = top)
        assertEquals(RouteCompassRejectedCheck.MAGNETIC_HORIZONTAL_BASIS, nearlyParallel.checkEvidence?.rejectedCheck)
        assertNull(nearlyParallel.observation)
    }

    @Test
    fun instanceSeamKeepsRawSamplesOriginalNanosecondsAndOtherInstancesUnchanged() {
        val diagnostic = state(top)
        val normal = state()
        val gravity = gravity()
        val magnetic = magnetic()
        for (state in listOf(diagnostic, normal)) {
            vector(state, RouteCompassStream.GRAVITY, gravity)
            vector(state, RouteCompassStream.MAGNETIC_FIELD, magnetic)
        }
        assertEquals(RouteCompassHeadingReason.VALID, diagnostic.evaluate(NOW, reference).reason)
        assertEquals(RouteCompassHeadingReason.REAR_AXIS_VERTICAL, normal.evaluate(NOW, reference).reason)
        assertSame("The cache also feeds strict Chest/PDR selection and must remain raw",
            magnetic, diagnostic.latestMagneticMeasurement())
        assertEquals(NOW * NS_PER_MS + 123L, diagnostic.latestMagneticMeasurement()?.sensorTimestampNanos)
        vector(diagnostic, RouteCompassStream.MAGNETIC_FIELD, magnetic.copy(x = 500.0,
            sensorTimestampNanos = NOW * NS_PER_MS + 122L))
        assertSame("An older callback must still be rejected", magnetic, diagnostic.latestMagneticMeasurement())
        vector(diagnostic, RouteCompassStream.MAGNETIC_FIELD,
            magnetic.copy(observedAtMs = NOW + 1L, sensorTimestampNanos = (NOW + 1L) * NS_PER_MS),
            receivedAtNanos = NOW * NS_PER_MS + 999L)
        assertSame("An original future timestamp must still be rejected", magnetic, diagnostic.latestMagneticMeasurement())
        assertEquals(RouteCompassHeadingReason.STALE, diagnostic.evaluate(NOW + 501L, reference).reason)
        assertEquals(RouteCompassHeadingReason.SENSOR_STALLED, diagnostic.evaluate(NOW + 2_001L, reference).reason)
    }

    @Test
    fun projectionRateUsesTheSameTopAxisWhileThreeDimensionalRateStaysOriginal() {
        val diagnostic = state(top)
        val normal = state()
        val angle = Math.toRadians(30.0)
        val aboutTop = RotationMatrix3(cos(angle).toFloat(), 0f, sin(angle).toFloat(),
            0f, 1f, 0f, -sin(angle).toFloat(), 0f, cos(angle).toFloat())
        for (state in listOf(diagnostic, normal)) {
            rotation(state, identity, NOW)
            rotation(state, aboutTop, NOW + 100L)
        }
        val observed = requireNotNull(diagnostic.evaluate(NOW + 100L, reference).observation)
        assertEquals("Pitch about +Y is not a turn of the diagnostic facing", 0.0,
            requireNotNull(observed.angularRateDegreesPerSecond), 0.00001)
        assertEquals(300.0, requireNotNull(diagnostic.latestRotationRateDegreesPerSecond()), 0.001)
        assertEquals(normal.latestRotationRateDegreesPerSecond(), diagnostic.latestRotationRateDegreesPerSecond())
    }

    @Test
    fun restoringDefaultAtStopStartBoundaryRequiresNewSamplesAndRestoresPostureRejection() {
        val state = state(top)
        vector(state, RouteCompassStream.GRAVITY, gravity())
        vector(state, RouteCompassStream.MAGNETIC_FIELD, magnetic())
        assertEquals(RouteCompassHeadingReason.VALID, state.evaluate(NOW, reference).reason)
        state.stop()
        setAxis(state, RouteCompassForwardAxis.REAR_CAMERA)
        state.start(2, NOW + 10L, streams)
        assertNull(state.evaluate(NOW + 10L, reference).observation)
        vector(state, RouteCompassStream.GRAVITY, gravity(), generation = 1)
        vector(state, RouteCompassStream.MAGNETIC_FIELD, magnetic(), generation = 1)
        assertNull("Retired callbacks cannot recover the restored session", state.evaluate(NOW + 10L, reference).observation)
        vector(state, RouteCompassStream.GRAVITY, gravity(NOW + 20L), generation = 2)
        vector(state, RouteCompassStream.MAGNETIC_FIELD, magnetic(atMs = NOW + 20L), generation = 2)
        assertEquals(RouteCompassHeadingReason.REAR_AXIS_VERTICAL, state.evaluate(NOW + 20L, reference).reason)
    }

    private fun fallback(gravity: RouteCompassVectorSample?, magnetic: RouteCompassVectorSample?, nowMs: Long = NOW) =
        RouteCompassHeading.evaluateFallback(gravity, null, magnetic, reference, nowMs, forwardAxis = top)
    private fun gravity(atMs: Long = NOW) = sample(0.0, 0.0, 9.80665, atMs)
    private fun magnetic(dip: Double = 40.0, strength: Double = 50.0, atMs: Long = NOW): RouteCompassVectorSample {
        val radians = Math.toRadians(dip)
        return sample(0.0, strength * cos(radians), -strength * sin(radians), atMs)
    }
    private fun sample(x: Double, y: Double, z: Double, atMs: Long) =
        RouteCompassVectorSample(x, y, z, atMs, EarthOrientationAccuracy.HIGH, atMs * NS_PER_MS + 123L)
    private fun state(axis: RouteCompassForwardAxis = RouteCompassForwardAxis.REAR_CAMERA) = RouteCompassSensorState().also {
        setAxis(it, axis)
        it.start(1, NOW, streams)
    }
    private fun setAxis(state: RouteCompassSensorState, axis: RouteCompassForwardAxis) =
        RouteCompassSensorState::class.java.getDeclaredField("forwardAxisForInstrumentation")
            .apply { isAccessible = true }.set(state, axis)
    private fun vector(state: RouteCompassSensorState, stream: RouteCompassStream, sample: RouteCompassVectorSample,
        generation: Int = 1, receivedAtNanos: Long = sample.observedAtMs * NS_PER_MS + 999L,
    ) {
        state.onSensorCallback(generation, stream, requireNotNull(sample.sensorTimestampNanos), receivedAtNanos) {
            state.onVector(generation, stream, sample, it)
        }
    }
    private fun rotation(state: RouteCompassSensorState, rotation: RotationMatrix3, atMs: Long) {
        state.onSensorCallback(1, RouteCompassStream.ROTATION_VECTOR, atMs * NS_PER_MS, atMs * NS_PER_MS) {
            assertTrue(state.onRotationVector(1, rotation, 5f, atMs, EarthOrientationAccuracy.HIGH, it) != null)
        }
    }

    companion object {
        private const val NOW = 10_000L
        private const val NS_PER_MS = 1_000_000L
        private val streams = setOf(RouteCompassStream.ROTATION_VECTOR, RouteCompassStream.GRAVITY,
            RouteCompassStream.MAGNETIC_FIELD)
    }
}
