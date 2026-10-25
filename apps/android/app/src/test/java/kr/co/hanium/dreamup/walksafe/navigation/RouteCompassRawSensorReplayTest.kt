package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedGeomagneticReference
import kr.co.hanium.dreamup.walksafe.navigation.positioning.FilteredRoutePosition
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

/** Actual raw consumer -> original-time admission -> State -> Facing/Nav; no Android delivery/audio. */
class RouteCompassRawSensorReplayTest {
    private val reference = ChestMountedGeomagneticReference(0.0, Math.toDegrees(kotlin.math.atan2(40.0, 30.0)), 50.0)

    @Test fun rearCameraCardinalDirectionsUseTheBodyFacingClock() {
        for ((heading, hour) in listOf(0.0 to 12, 90.0 to 9, 180.0 to 6, 270.0 to 3)) {
            val replay = Replay()
            replay.rotation(1_000L, pose(heading))
            val observation = replay.observation(1_000L)
            assertAngle(heading, observation.degreesTrueNorth)
            assertEquals(hour, RouteFacingGuidance.evaluate(0.0, observation, 1_000L).clockHour)
            assertEquals(1_000L, observation.observedAtElapsedRealtimeMs)
        }
    }

    @Test fun initialFlatPhoneCannotInventBodyDirectionAndActualMountingRecovers() {
        val replay = Replay()
        replay.rotation(1_000L, doubleArrayOf(0.0, 0.0, 0.0, 1.0))
        assertEquals(RouteCompassHeadingReason.REAR_AXIS_VERTICAL, replay.result(1_000L).reason)
        assertNull(replay.result(1_000L).observation)
        replay.rotation(1_100L, pose(0.0))
        val mounted = replay.observation(1_100L)
        assertAngle(0.0, mounted.degreesTrueNorth)
        assertEquals(1_100L, mounted.observedAtElapsedRealtimeMs)
        assertEquals(1, mounted.headingTrend!!.sampleCount)
    }

    @Test fun smallYawSwayAndButtonRollKeepTheRawMeasurementSeparateFromItsTrend() {
        val replay = Replay()
        val inputs = listOf(86.0, 94.0, 86.0, 94.0)
        var latest: RouteFacingObservation? = null
        inputs.forEachIndexed { i, heading ->
            val at = 1_000L + i * 100L
            replay.rotation(at, pose(heading, tilt = if (i % 2 == 0) -3.0 else 3.0, roll = if (i % 2 == 0) 2.0 else -2.0))
            latest = replay.observation(at)
        }
        val observation = requireNotNull(latest)
        assertAngle(94.0, observation.degreesTrueNorth)
        assertAngle(90.0, observation.headingTrend!!.representativeDegreesTrueNorth)
        assertEquals(1_300L, observation.observedAtElapsedRealtimeMs)
        assertEquals(1_300L, observation.headingTrend!!.latestActualObservedAtMs)
        assertEquals(4, observation.headingTrend!!.sampleCount)
        assertEquals(2.0, observation.reportedHeadingAccuracyDegrees!!, 0.00001)
        assertTrue(observation.headingAccuracyDegrees!! >= observation.reportedHeadingAccuracyDegrees!!)
        assertEquals(12, RouteFacingGuidance.evaluate(90.0, observation, 1_300L).clockHour)
    }

    @Test fun temporaryPhoneManipulationRejectsVerticalRearAxisThenUsesTheNewMountSample() {
        val replay = Replay()
        replay.rotation(1_000L, pose(90.0))
        assertAngle(90.0, replay.observation(1_000L).degreesTrueNorth)
        replay.rotation(1_100L, pose(90.0, tilt = 85.0))
        assertEquals(RouteCompassHeadingReason.REAR_AXIS_VERTICAL, replay.result(1_100L).reason)
        assertNull(replay.result(1_100L).observation)
        replay.rotation(1_200L, pose(90.0, roll = 10.0))
        val recovered = replay.observation(1_200L)
        assertAngle(90.0, recovered.degreesTrueNorth)
        assertEquals(1_200L, recovered.observedAtElapsedRealtimeMs)
        assertEquals(1, recovered.headingTrend!!.sampleCount)
        replay.rotation(1_300L, pose(90.0))
        assertEquals(12, RouteFacingGuidance.evaluate(90.0, replay.observation(1_300L), 1_300L).clockHour)
    }

    @Test fun anActualNinetyDegreeTurnConfirmedAcrossThreeHundredMillisecondsKeepsTheNewTime() {
        val replay = Replay()
        for ((i, heading) in listOf(358.0, 2.0, 358.0, 2.0).withIndex()) {
            val at = 1_000L + i * 100L
            replay.rotation(at, pose(heading))
            replay.observation(at)
        }
        replay.rotation(1_400L, pose(90.0))
        val pending = replay.observation(1_400L)
        assertTrue(pending.headingTrend!!.largeTurnPending)
        assertNull(RouteFacingGuidance.evaluate(0.0, pending, 1_400L).clockHour)
        replay.rotation(1_500L, pose(92.0))
        assertTrue(replay.observation(1_500L).headingTrend!!.largeTurnPending)
        replay.rotation(1_700L, pose(92.0))
        val turned = replay.observation(1_700L)
        assertTrue(turned.headingTrend!!.followsActualLargeTurn)
        assertFalse(turned.headingTrend!!.largeTurnPending)
        assertAngle(92.0, turned.headingTrend!!.representativeDegreesTrueNorth)
        assertEquals(1_700L, turned.headingTrend!!.latestActualObservedAtMs)
        assertEquals(1_700L, turned.observedAtElapsedRealtimeMs)
        assertEquals(9, RouteFacingGuidance.evaluate(0.0, turned, 1_700L).clockHour)
    }

    @Test fun actualOneEightyReversalImmediatelyInvalidatesTheReservedNavigationCue() {
        val replay = Replay()
        replay.rotation(1_000L, pose(0.0))
        val navigator = RouteNavigator(RouteNavigatorConfig(directionRecheckGraceMs = 4_500L)).apply {
            setRoute(WalkingRoute("WALK", WalkingRouteSummary(300, 300), listOf(point(0.0), point(300.0)), emptyList()))
        }
        val location = point(50.0)
        val first = navigator.update(TrustedLocation(location.latitude, location.longitude, 8f, 1_000L), 1_000L, false,
            filteredPosition = FilteredRoutePosition(location, 6.0, 1_000L), facingObservation = replay.observation(1_000L))
        assertNotNull(first.instruction)
        assertTrue(navigator.reserveInstruction(first))
        assertTrue(navigator.isInstructionCurrent(first, 1_000L, replay.observation(1_000L)))
        replay.rotation(1_100L, pose(180.0))
        val reverse = replay.observation(1_100L)
        assertAngle(180.0, reverse.headingTrend!!.representativeDegreesTrueNorth)
        assertTrue(navigator.hasHardInvalidatedDirectionClaim(1_100L, reverse))
        assertTrue(requireNotNull(navigator.retryGuidance(1_100L, reverse)).cancelStaleNavigationSpeech)
        assertFalse(navigator.isInstructionCompletionCurrent(first, 1_100L))
    }

    @Test fun unreportedAngularAccuracyRequiresIndependentRawFallbackWithoutFabricatedError() {
        for (form in listOf("three", "four", "minus_one", "nan")) {
            val replay = Replay()
            val quaternion = pose(90.0)
            val values = when (form) {
                "three" -> quaternion.take(3).map(Double::toFloat).toFloatArray()
                "four" -> quaternion.map(Double::toFloat).toFloatArray()
                "minus_one" -> rawRotation(quaternion, -1f)
                else -> rawRotation(quaternion, Float.NaN)
            }
            replay.raw(RouteCompassStream.ROTATION_VECTOR, values, 1_000L * NS_PER_MS)
            assertNull(form, replay.result(1_000L).observation)
            replay.fallback(1_100L, quaternion)
            val observation = replay.observation(1_100L)
            assertEquals(form, RouteFacingSource.GRAVITY_MAGNETIC, observation.source)
            assertAngle(90.0, observation.degreesTrueNorth)
            assertNull(form, observation.headingAccuracyDegrees)
            assertNull(form, observation.reportedHeadingAccuracyDegrees)
            assertEquals(RouteFacingSensorQuality.CALIBRATED_SENSOR_CHECKS, observation.sensorQuality)
            assertEquals(1_100L, observation.observedAtElapsedRealtimeMs)
        }
    }

    @Test fun explicitBadNumericRotationCannotBeHiddenByHealthyFallbackVectors() {
        for (error in listOf(Math.toRadians(31.0).toFloat(), Float.POSITIVE_INFINITY)) {
            val replay = Replay()
            replay.fallback(1_000L, pose(0.0))
            replay.raw(RouteCompassStream.ROTATION_VECTOR, rawRotation(pose(0.0), error), 1_000L * NS_PER_MS)
            assertEquals(RouteCompassHeadingReason.HEADING_ACCURACY, replay.result(1_000L).reason)
            assertNull(replay.result(1_000L).observation)
        }
        val malformed = Replay()
        malformed.fallback(1_000L, pose(0.0))
        malformed.raw(RouteCompassStream.ROTATION_VECTOR, floatArrayOf(Float.NaN, 0f, 0f, 1f, 0.02f), 1_000L * NS_PER_MS)
        assertEquals(RouteCompassHeadingReason.INVALID_ROTATION, malformed.result(1_000L).reason)
        assertNull(malformed.result(1_000L).observation)
    }

    @Test fun rawAccelerationFallbackNeedsARealStableWindowAndPreservesItsOldestTime() {
        val replay = Replay(setOf(RouteCompassStream.ROTATION_VECTOR, RouteCompassStream.MAGNETIC_FIELD, RouteCompassStream.ACCELEROMETER))
        replay.rotation(1_000L, pose(0.0), errorRad = -1f)
        replay.fallback(1_000L, pose(0.0), gravity = false)
        assertEquals(RouteCompassHeadingReason.GRAVITY_UNSTABLE, replay.result(1_000L).reason)
        replay.fallback(1_100L, pose(0.0), gravity = false)
        replay.fallback(1_200L, pose(0.0), gravity = false)
        val stable = replay.observation(1_200L)
        assertEquals(RouteFacingSource.ACCELEROMETER_MAGNETIC, stable.source)
        assertEquals(1_000L, stable.observedAtElapsedRealtimeMs)
        assertNull(stable.headingAccuracyDegrees)
        replay.fallback(1_300L, pose(0.0), gravity = false, accelerationOffset = 6f)
        assertNull(replay.result(1_300L).observation)
        for (at in listOf(1_400L, 1_500L, 1_600L)) replay.fallback(at, pose(0.0), gravity = false)
        val recovered = replay.observation(1_600L)
        assertEquals(1_400L, recovered.observedAtElapsedRealtimeMs)
        assertEquals(RouteFacingSource.ACCELEROMETER_MAGNETIC, recovered.source)
    }

    @Test fun rawMagneticDisturbanceWithholdsFallbackUntilANewNormalVectorArrives() {
        val replay = Replay()
        replay.rotation(1_000L, pose(0.0), errorRad = -1f)
        replay.fallback(1_000L, pose(0.0))
        assertAngle(0.0, replay.observation(1_000L).degreesTrueNorth)
        replay.raw(RouteCompassStream.MAGNETIC_FIELD, floatArrayOf(0f, -80f, -60f), 1_100L * NS_PER_MS)
        assertEquals(RouteCompassHeadingReason.MAGNETIC_FIELD_ANOMALY, replay.result(1_100L).reason)
        assertNull(replay.result(1_100L).observation)
        assertEquals(1_100L, replay.result(1_100L).checkEvidence!!.observedAtElapsedRealtimeMs)
        assertEquals(100.0, replay.result(1_100L).checkEvidence!!.magneticFieldMicrotesla!!, 0.0001)
        replay.fallback(1_200L, pose(0.0))
        assertEquals(1_200L, replay.observation(1_200L).observedAtElapsedRealtimeMs)
    }

    @Test fun currentRawMagneticDisturbanceAlsoRejectsAMatchingFreshGravityBasis() {
        val replay = Replay()
        replay.rotation(1_000L, pose(0.0), errorRad = -1f)
        replay.fallback(1_000L, pose(0.0))
        replay.observation(1_000L)
        replay.raw(RouteCompassStream.GRAVITY, floatArrayOf(0f, 9.80665f, 0f), 1_100L * NS_PER_MS)
        replay.raw(RouteCompassStream.MAGNETIC_FIELD, floatArrayOf(0f, -80f, -60f), 1_100L * NS_PER_MS)
        assertEquals(RouteCompassHeadingReason.MAGNETIC_FIELD_ANOMALY, replay.result(1_100L).reason)
        assertNull(replay.result(1_100L).observation)
        replay.fallback(1_200L, pose(0.0))
        assertEquals(1_200L, replay.observation(1_200L).observedAtElapsedRealtimeMs)
    }

    @Test fun anOlderQualifiedRawPairKeepsItsOriginalTimeWhenANewerNormalFieldArrives() {
        val replay = Replay()
        replay.rotation(1_000L, pose(0.0), errorRad = -1f)
        replay.fallback(1_000L, pose(0.0))
        replay.raw(RouteCompassStream.GRAVITY, floatArrayOf(0f, 9.80665f, 0f), 1_020L * NS_PER_MS)
        val original = replay.observation(1_020L)
        replay.raw(RouteCompassStream.MAGNETIC_FIELD, floatArrayOf(0f, -40f, -30f), 1_300L * NS_PER_MS)
        val retained = replay.observation(1_350L)
        assertEquals(RouteFacingSource.GRAVITY_MAGNETIC, retained.source)
        assertAngle(original.degreesTrueNorth, retained.degreesTrueNorth)
        assertEquals(1_000L, retained.observedAtElapsedRealtimeMs)
        assertEquals(1_000L, retained.headingTrend!!.latestActualObservedAtMs)
        assertEquals(1_300L, replay.state.latestMagneticAt())
        assertEquals(1_300L, replay.state.lastReceiptAt(RouteCompassStream.MAGNETIC_FIELD))
        assertEquals(50.0, replay.result(1_350L).checkEvidence!!.magneticFieldMicrotesla!!, 0.0001)
        assertNull(replay.result(1_501L).observation)
    }

    @Test fun qualityCallbackAloneAndAnOldRawSampleCannotRecoverTheRotationCache() {
        val replay = Replay(setOf(RouteCompassStream.ROTATION_VECTOR))
        replay.rotation(1_000L, pose(0.0))
        replay.state.onAccuracyChanged(1, RouteCompassStream.ROTATION_VECTOR, EarthOrientationAccuracy.LOW, 1_100L)
        assertNull(replay.result(1_100L).observation)
        replay.state.onAccuracyChanged(1, RouteCompassStream.ROTATION_VECTOR, EarthOrientationAccuracy.HIGH, 1_200L)
        assertNull(replay.result(1_200L).observation)
        val consumed = replay.consumed
        replay.raw(RouteCompassStream.ROTATION_VECTOR, rawRotation(pose(0.0)), 1_000L * NS_PER_MS, 1_200L * NS_PER_MS)
        assertEquals(consumed, replay.consumed)
        assertNull(replay.result(1_200L).observation)
        replay.rotation(1_300L, pose(0.0))
        val recovered = replay.observation(1_300L)
        assertEquals(1_300L, recovered.observedAtElapsedRealtimeMs)
        assertNull(recovered.fallbackRecoveryEpisode)
    }

    @Test fun rawNanosecondAdmissionProtectsEveryStreamBeforeItsConsumerRuns() {
        for (stream in RouteCompassStream.entries) {
            val replay = Replay()
            val values = valuesFor(stream)
            replay.raw(stream, values, 1_000_100_000L, 1_000_200_000L)
            assertEquals(1, replay.consumed)
            for (timestamp in listOf(-1L, 1_000_300_000L, 1_000_100_000L, 1_000_050_000L)) {
                replay.raw(stream, values, timestamp, 1_000_250_000L)
            }
            assertEquals(stream.name, 1, replay.consumed)
            assertEquals(1_000L, replay.latestAt(stream))
            replay.raw(stream, values, 1_000_300_000L, 1_000_400_000L)
            assertEquals(2, replay.consumed) // Distinct original ns in the same ms are real samples.
            assertEquals(1_000L, replay.latestAt(stream))
            assertEquals(1_000L, replay.state.lastReceiptAt(stream))
        }
    }

    @Test fun stoppedAndOldRegistrationInputsCannotReviveAnyRawStream() {
        for (stream in RouteCompassStream.entries) {
            val replay = Replay()
            replay.raw(stream, valuesFor(stream), 1_000L * NS_PER_MS)
            replay.state.stop()
            replay.raw(stream, valuesFor(stream), 1_100L * NS_PER_MS)
            assertEquals(1, replay.consumed)
            assertNull(replay.state.lastReceiptAt(stream))
            replay.state.start(2, 1_200L, RouteCompassStream.entries.toSet())
            replay.raw(stream, valuesFor(stream), 1_300L * NS_PER_MS, generation = 1)
            assertEquals(1, replay.consumed)
            assertNull(replay.latestAt(stream))
            assertNull(replay.state.lastReceiptAt(stream))
            replay.raw(stream, valuesFor(stream), 1_400L * NS_PER_MS, generation = 2)
            assertEquals(2, replay.consumed)
            assertEquals(1_400L, replay.latestAt(stream))
        }
    }

    private inner class Replay(streams: Set<RouteCompassStream> = RouteCompassStream.entries.toSet()) {
        val state = RouteCompassSensorState().apply { start(1, 900L, streams) }
        var consumed = 0
        fun raw(stream: RouteCompassStream, values: FloatArray, timestampNs: Long, receiptNs: Long = timestampNs,
            generation: Int = 1, accuracy: EarthOrientationAccuracy = EarthOrientationAccuracy.HIGH) {
            state.onSensorCallback(generation, stream, timestampNs, receiptNs) { receiptMs ->
                consumed++
                if (stream == RouteCompassStream.ROTATION_VECTOR) {
                    RouteCompassRawSensorConsumer.rotationVector(state, generation, values, timestampNs, accuracy, receiptMs)
                } else {
                    RouteCompassRawSensorConsumer.vector(state, generation, stream, values, timestampNs, accuracy, receiptMs)
                }
            }
        }
        fun rotation(at: Long, quaternion: DoubleArray, errorRad: Float = Math.toRadians(2.0).toFloat()) =
            raw(RouteCompassStream.ROTATION_VECTOR, rawRotation(quaternion, errorRad), at * NS_PER_MS)
        fun fallback(at: Long, quaternion: DoubleArray, gravity: Boolean = true, accelerationOffset: Float = 0f) {
            val gravityValues = deviceVector(quaternion, doubleArrayOf(0.0, 0.0, 9.80665))
            if (gravity) raw(RouteCompassStream.GRAVITY, gravityValues, at * NS_PER_MS)
            raw(RouteCompassStream.MAGNETIC_FIELD, deviceVector(quaternion, doubleArrayOf(0.0, 30.0, -40.0)), at * NS_PER_MS)
            raw(RouteCompassStream.ACCELEROMETER, gravityValues.copyOf().also { it[1] += accelerationOffset }, at * NS_PER_MS)
        }
        fun result(at: Long) = state.evaluate(at, reference)
        fun observation(at: Long): RouteFacingObservation {
            val result = result(at)
            assertEquals(RouteCompassHeadingReason.VALID, result.reason)
            return requireNotNull(result.observation)
        }
        fun latestAt(stream: RouteCompassStream): Long? = when (stream) {
            RouteCompassStream.ROTATION_VECTOR -> state.latestRotationEventAt()
            RouteCompassStream.GRAVITY -> state.latestGravityAt()
            RouteCompassStream.MAGNETIC_FIELD -> state.latestMagneticAt()
            RouteCompassStream.ACCELEROMETER -> state.latestAccelerometerAt()
        }
    }

    /** Independent fixture quaternion: world yaw * upright device pitch * device roll (x,y,z,w). */
    private fun pose(heading: Double, tilt: Double = 0.0, roll: Double = 0.0): DoubleArray {
        val yaw = Math.toRadians(-heading) / 2.0
        val pitch = Math.toRadians(90.0 + tilt) / 2.0
        val deviceRoll = Math.toRadians(roll) / 2.0
        return multiply(multiply(doubleArrayOf(0.0, 0.0, sin(yaw), cos(yaw)),
            doubleArrayOf(sin(pitch), 0.0, 0.0, cos(pitch))),
            doubleArrayOf(0.0, 0.0, sin(deviceRoll), cos(deviceRoll)))
    }
    private fun multiply(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
        a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
        a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
        a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
    )
    private fun deviceVector(q: DoubleArray, earth: DoubleArray): FloatArray {
        val inverse = doubleArrayOf(-q[0], -q[1], -q[2], q[3])
        val rotated = multiply(multiply(inverse, doubleArrayOf(earth[0], earth[1], earth[2], 0.0)), q)
        return floatArrayOf(rotated[0].toFloat(), rotated[1].toFloat(), rotated[2].toFloat())
    }
    private fun rawRotation(quaternion: DoubleArray, errorRad: Float = Math.toRadians(2.0).toFloat()) =
        quaternion.map(Double::toFloat).toFloatArray() + errorRad
    private fun valuesFor(stream: RouteCompassStream) = when (stream) {
        RouteCompassStream.ROTATION_VECTOR -> rawRotation(pose(0.0))
        RouteCompassStream.MAGNETIC_FIELD -> floatArrayOf(0f, -40f, -30f)
        else -> floatArrayOf(0f, 9.80665f, 0f)
    }
    private fun assertAngle(expected: Double, actual: Double) =
        assertEquals(0.0, kotlin.math.abs((actual - expected + 540.0) % 360.0 - 180.0), 0.0001)
    private fun point(north: Double) = RoutePoint(north / 111194.92664455874, 0.0)
    private companion object { const val NS_PER_MS = 1_000_000L }
}
