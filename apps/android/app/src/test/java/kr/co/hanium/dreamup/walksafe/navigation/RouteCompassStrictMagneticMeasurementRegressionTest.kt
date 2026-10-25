package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedGeomagneticReference
import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedHeading
import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedHeadingGateReason
import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedHeadingResult
import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedMagneticFieldSample
import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedRotationSample
import kr.co.hanium.dreamup.walksafe.navigation.positioning.HeadingObservation
import kr.co.hanium.dreamup.walksafe.navigation.positioning.HeadingObservationHistory
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PedestrianHeadingInput
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PedestrianHeadingSelector
import kr.co.hanium.dreamup.walksafe.navigation.positioning.PedestrianHeadingSource
import kr.co.hanium.dreamup.walksafe.navigation.positioning.WalkingSpeedObservation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteCompassStrictMagneticMeasurementRegressionTest {
    private val strictCaches = mutableMapOf<RouteCompassSensorState, ChestMountedMagneticFieldSample?>()
    @Test
    fun lateOlderNormalCannotHideNewerStrongFieldAfterReferenceBecomesAvailable() {
        val result = missingReferenceThenLateNormal()

        assertEquals(
            "strict cache must retain the 1000 ms strong field after the 900 ms normal arrival",
            ChestMountedHeadingGateReason.FIELD_ANOMALY,
            result.gateReason,
        )
        assertNull(result.trueHeadingDegrees)
    }

    @Test
    fun lateOlderNormalCannotPublishAPdrHeadingAfterReferenceBecomesAvailable() {
        val result = missingReferenceThenLateNormal()
        val history = HeadingObservationHistory()
        if (result.isValid) {
            history.add(HeadingObservation(
                requireNotNull(result.trueHeadingDegrees),
                result.accuracyDegrees,
                requireNotNull(result.observedAtMs),
            ))
        } else {
            history.clear()
        }

        assertNull(
            "late older normal input must not publish a strict PDR heading over the newer anomaly",
            history.atOrBefore(1_100L),
        )
    }

    @Test
    fun latestStrongFieldSurvivesAnIntermediateLowQualityArrival() {
        assertLatestAnomalySurvivesDegradedArrival(magnetic(900L, accuracy = EarthOrientationAccuracy.LOW),
            ChestMountedHeadingGateReason.MAGNETIC_FIELD_ACCURACY)
    }

    @Test
    fun latestStrongFieldSurvivesAnIntermediateMalformedArrival() {
        assertLatestAnomalySurvivesDegradedArrival(magnetic(900L).copy(xMicrotesla = Double.NaN),
            ChestMountedHeadingGateReason.INVALID_MAGNETIC_FIELD)
    }

    @Test
    fun latestZeroFieldAnomalyIsNotHiddenByALateNormalArrival() {
        val core = ChestMountedHeading()
        val state = state()
        val zero = requireNotNull(magneticCallback(state, magnetic(1_000L, strength = 0.0), 1_000L))
        assertEquals(ChestMountedHeadingGateReason.GEOMAGNETIC_REFERENCE_MISSING,
            core.evaluate(1_000L, rotation(), zero, null).gateReason)
        val selected = requireNotNull(magneticCallback(state, magnetic(900L), 1_100L))

        assertEquals(ChestMountedHeadingGateReason.FIELD_ANOMALY,
            core.evaluate(1_100L, rotation(), selected, reference()).gateReason)
    }

    @Test
    fun freshNewerNormalReplacesStrongBeforeAnyAnomalyWasLatched() {
        val core = ChestMountedHeading()
        val state = state()
        val strong = requireNotNull(magneticCallback(state, magnetic(1_000L, strength = 150.0), 1_000L))
        assertEquals(ChestMountedHeadingGateReason.GEOMAGNETIC_REFERENCE_MISSING,
            core.evaluate(1_000L, rotation(), strong, null).gateReason)
        val normal = requireNotNull(magneticCallback(state, magnetic(1_100L), 1_100L))
        val result = core.evaluate(1_100L, rotation(), normal, reference())

        assertEquals(1_100L, normal.observedAtMs)
        assertTrue(result.isValid)
        assertEquals(0.0, requireNotNull(result.trueHeadingDegrees), 0.001)
    }

    @Test
    fun olderStrongDoesNotReplaceNewerNormal() {
        val state = state()
        magneticCallback(state, magnetic(1_000L), 1_000L)
        val selected = requireNotNull(magneticCallback(state, magnetic(900L, strength = 150.0), 1_100L))

        assertEquals(1_000L, selected.observedAtMs)
        assertTrue(ChestMountedHeading().evaluate(1_100L, rotation(), selected, reference()).isValid)
    }

    @Test
    fun equalMeasurementMillisecondsKeepLastArrivalInBothDirections() {
        for (lastStrength in listOf(50.0, 150.0)) {
            val state = state()
            val first = magnetic(1_000L, if (lastStrength == 50.0) 150.0 else 50.0)
            magneticCallback(state, first, 1_000L)
            val incoming = magnetic(1_000L, lastStrength)

            assertEquals("equal original ns is replay", first, magneticCallback(state, incoming, 1_100L))
            assertEquals("distinct newer SDK ns remains a new measurement", incoming,
                magneticCallback(state, incoming, 1_100L, measuredAtNanos = 1_000_900_000L))
        }
    }

    @Test
    fun equalMillisecondsDoNotIntroduceANanosecondTiePolicy() {
        for (lastNanos in listOf(1_000_010_000L, 1_000_090_000L)) {
            val state = state()
            magneticCallback(state, magnetic(1_000L, strength = 150.0), 1_001L,
                measuredAtNanos = 1_000_050_000L)
            val incoming = magnetic(1_000L)

            val newer = lastNanos > 1_000_050_000L
            assertEquals(if (newer) incoming else magnetic(1_000L, strength = 150.0),
                magneticCallback(state, incoming, 1_100L, measuredAtNanos = lastNanos))
            assertEquals(if (newer) lastNanos else 1_000_050_000L,
                state.latestMagneticMeasurement()?.sensorTimestampNanos)
        }
    }

    @Test
    fun existingCoreLatchStillRejectsOneLateNormalAsFieldRecovering() {
        val core = ChestMountedHeading()
        assertEquals(ChestMountedHeadingGateReason.FIELD_ANOMALY,
            core.evaluate(1_000L, rotation(), magnetic(1_000L, strength = 150.0), reference()).gateReason)

        assertEquals(ChestMountedHeadingGateReason.FIELD_RECOVERING,
            core.evaluate(1_100L, rotation(), magnetic(900L), reference()).gateReason)
    }

    @Test
    fun alreadyLatchedStrictCacheStillRejectsTheLateNormal() {
        val core = ChestMountedHeading()
        val state = state()
        val strong = requireNotNull(magneticCallback(state, magnetic(1_000L, strength = 150.0), 1_000L))
        assertEquals(ChestMountedHeadingGateReason.FIELD_ANOMALY,
            core.evaluate(1_000L, rotation(), strong, reference()).gateReason)
        val selected = requireNotNull(magneticCallback(state, magnetic(900L), 1_100L))

        assertEquals(ChestMountedHeadingGateReason.FIELD_ANOMALY,
            core.evaluate(1_100L, rotation(), selected, reference()).gateReason)
    }

    @Test
    fun newerNormalsPreserveFiveSampleAndOneSecondRecovery() {
        val core = ChestMountedHeading()
        val state = state()
        val strong = requireNotNull(magneticCallback(state, magnetic(100L, strength = 150.0), 100L))
        assertEquals(ChestMountedHeadingGateReason.FIELD_ANOMALY,
            core.evaluate(100L, rotation(100L), strong, reference()).gateReason)
        for (at in listOf(200L, 450L, 700L, 950L, 1_200L)) {
            val selected = requireNotNull(magneticCallback(state, magnetic(at), at))
            val result = core.evaluate(at, rotation(at), selected, reference())
            assertEquals(if (at == 1_200L) ChestMountedHeadingGateReason.VALID
                else ChestMountedHeadingGateReason.FIELD_RECOVERING, result.gateReason)
        }
    }

    @Test
    fun missingInputsAndUnavailableMagneticSensorRetainTheirGates() {
        val core = ChestMountedHeading()
        assertEquals(ChestMountedHeadingGateReason.MAGNETIC_FIELD_MISSING,
            core.evaluate(1_000L, rotation(), null, reference()).gateReason)
        assertEquals(ChestMountedHeadingGateReason.ROTATION_MISSING,
            core.evaluate(1_000L, null, magnetic(1_000L), reference()).gateReason)
        assertEquals(ChestMountedHeadingGateReason.GEOMAGNETIC_REFERENCE_MISSING,
            core.evaluate(1_000L, rotation(), magnetic(1_000L), null).gateReason)
        assertEquals(ChestMountedHeadingGateReason.MAGNETIC_SENSOR_UNAVAILABLE,
            core.evaluate(1_000L, rotation(), magnetic(1_000L), reference(), false).gateReason)
    }

    @Test
    fun lowAndUnreliableOlderArrivalsAreNotHiddenBehindGoodHistory() {
        for (accuracy in listOf(EarthOrientationAccuracy.LOW, EarthOrientationAccuracy.UNRELIABLE)) {
            val state = state()
            magneticCallback(state, magnetic(1_000L), 1_000L)
            val incoming = magnetic(900L, accuracy = accuracy)
            val selected = requireNotNull(magneticCallback(state, incoming, 1_100L))

            assertEquals("past quality cannot replace the current field", magnetic(1_000L), selected)
            assertTrue(ChestMountedHeading().evaluate(1_100L, rotation(), selected, reference()).isValid)
            val current = incoming.copy(observedAtMs = 1_200L)
            assertEquals(current, magneticCallback(state, current, 1_200L))
            assertEquals(ChestMountedHeadingGateReason.MAGNETIC_FIELD_ACCURACY,
                ChestMountedHeading().evaluate(1_200L, rotation(1_200L), current, reference()).gateReason)
        }
    }

    @Test
    fun nonFiniteAndOverflowArrivalsAreNotHiddenBehindGoodHistory() {
        for (badX in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE)) {
            val state = state()
            magneticCallback(state, magnetic(1_000L), 1_000L)
            val incoming = magnetic(900L).copy(xMicrotesla = badX)
            val selected = requireNotNull(magneticCallback(state, incoming, 1_100L))

            assertEquals("past malformed input cannot replace the current field", magnetic(1_000L), selected)
            assertTrue(ChestMountedHeading().evaluate(1_100L, rotation(), selected, reference()).isValid)
            val current = incoming.copy(observedAtMs = 1_200L)
            assertEquals(current, magneticCallback(state, current, 1_200L))
            assertEquals(ChestMountedHeadingGateReason.INVALID_MAGNETIC_FIELD,
                ChestMountedHeading().evaluate(1_200L, rotation(1_200L), current, reference()).gateReason)
        }
    }

    @Test
    fun accuracyDowngradeRequiresANewerGoodMeasurementForPromotion() {
        val state = state()
        magneticCallback(state, magnetic(1_000L), 1_000L)
        state.onAccuracyChanged(1, RouteCompassStream.MAGNETIC_FIELD, EarthOrientationAccuracy.LOW, 1_050L)
        val selected = requireNotNull(magneticCallback(state, magnetic(900L), 1_100L))
        assertEquals(EarthOrientationAccuracy.LOW, selected.accuracy)
        assertEquals(ChestMountedHeadingGateReason.MAGNETIC_FIELD_ACCURACY,
            ChestMountedHeading().evaluate(1_100L, rotation(), selected, reference()).gateReason)
        state.onAccuracyChanged(1, RouteCompassStream.MAGNETIC_FIELD, EarthOrientationAccuracy.HIGH, 1_110L)
        assertEquals(EarthOrientationAccuracy.LOW,
            magneticCallback(state, magnetic(950L), 1_150L)?.accuracy)
        val fresh = requireNotNull(magneticCallback(state, magnetic(1_200L), 1_200L))
        assertEquals(EarthOrientationAccuracy.HIGH, fresh.accuracy)
        assertTrue(ChestMountedHeading().evaluate(1_200L, rotation(1_200L), fresh, reference()).isValid)
    }

    @Test
    fun selectedMagneticOriginalTimeStillExpiresAtTheExistingLimit() {
        val state = state()
        val selected = requireNotNull(magneticCallback(state, magnetic(1_000L), 1_000L))

        assertEquals(1_000L, selected.observedAtMs)
        assertEquals(ChestMountedHeadingGateReason.MAGNETIC_FIELD_STALE,
            ChestMountedHeading().evaluate(1_501L, rotation(1_501L), selected, reference()).gateReason)
    }

    @Test
    fun acceptedHistoryKeepsOriginalRotationTimeAndPastQueryLimits() {
        val state = state()
        val magnetic = requireNotNull(magneticCallback(state, magnetic(1_100L), 1_100L))
        val result = ChestMountedHeading().evaluate(1_100L, rotation(), magnetic, reference())
        assertTrue(result.isValid)
        assertEquals(1_000L, result.observedAtMs)
        val history = HeadingObservationHistory()
        history.add(HeadingObservation(requireNotNull(result.trueHeadingDegrees), result.accuracyDegrees,
            requireNotNull(result.observedAtMs)))

        assertNull(history.atOrBefore(999L))
        assertEquals(1_000L, history.atOrBefore(1_100L)?.elapsedRealtimeMs)
        assertEquals(1_000L, history.atOrBefore(1_500L)?.elapsedRealtimeMs)
        assertNull(history.atOrBefore(1_501L))
    }

    @Test
    fun oldGenerationCannotReachSelectionAndResetAcceptsANewGeneration() {
        val state = state()
        magneticCallback(state, magnetic(1_000L, strength = 150.0), 1_000L)
        var selectedByOldGeneration = false
        assertEquals(magnetic(1_000L, strength = 150.0),
            magneticCallback(state, magnetic(2_000L), 2_000L, generation = 0,
                onConsumed = { selectedByOldGeneration = true }))
        assertFalse(selectedByOldGeneration)
        assertEquals(1_000L, state.latestMagneticMeasurement()?.observedAtMs)
        state.stop()
        assertNull(magneticCallback(state, magnetic(2_000L), 2_000L))
        state.start(2, 2_000L, setOf(RouteCompassStream.MAGNETIC_FIELD))
        assertNull(state.latestMagneticMeasurement())
        val fresh = requireNotNull(magneticCallback(state, magnetic(2_010L), 2_010L, generation = 2))
        assertEquals(2_010L, fresh.observedAtMs)
        assertTrue(ChestMountedHeading().evaluate(2_010L, rotation(2_010L), fresh, reference()).isValid)
    }

    @Test
    fun sameMsFutureCannotReachSelectionAndTheSameValidRetryCan() {
        val state = state()
        magneticCallback(state, magnetic(1_000L), 1_000L)
        val incoming = magnetic(1_100L, strength = 150.0)
        var selectedByFuture = false
        assertEquals(magnetic(1_000L), magneticCallback(state, incoming, 1_100L,
            measuredAtNanos = 1_100_500_000L, receivedAtNanos = 1_100_100_000L,
            onConsumed = { selectedByFuture = true }))
        assertFalse(selectedByFuture)
        assertEquals(1_000L, state.latestMagneticMeasurement()?.observedAtMs)
        val retry = requireNotNull(magneticCallback(state, incoming, 1_100L,
            measuredAtNanos = 1_100_500_000L, receivedAtNanos = 1_100_900_000L))

        assertEquals(1_100L, retry.observedAtMs)
        assertEquals(1_100_500_000L, state.latestMagneticMeasurement()?.sensorTimestampNanos)
        assertEquals(ChestMountedHeadingGateReason.FIELD_ANOMALY,
            ChestMountedHeading().evaluate(1_100L, rotation(1_100L), retry, reference()).gateReason)
    }

    @Test
    fun strictAnomalyDoesNotProvideAMagneticHeadingToThePdrSelector() {
        val result = missingReferenceThenLateNormal()
        val heading = if (result.isValid) HeadingObservation(requireNotNull(result.trueHeadingDegrees),
            result.accuracyDegrees, requireNotNull(result.observedAtMs)) else null

        assertNull(PedestrianHeadingSelector().select(PedestrianHeadingInput(
            1_100L, WalkingSpeedObservation(0.4, 0.1, 1_050L), null, heading, true)))
    }

    @Test
    fun newerNormalStrictHeadingRemainsUsableByThePdrSelector() {
        val state = state()
        magneticCallback(state, magnetic(1_000L, strength = 150.0), 1_000L)
        val normal = requireNotNull(magneticCallback(state, magnetic(1_100L), 1_100L))
        val result = ChestMountedHeading().evaluate(1_100L, rotation(), normal, reference())
        assertTrue(result.isValid)
        val heading = HeadingObservation(requireNotNull(result.trueHeadingDegrees), result.accuracyDegrees,
            requireNotNull(result.observedAtMs))
        val selected = PedestrianHeadingSelector().select(PedestrianHeadingInput(
            1_100L, WalkingSpeedObservation(0.4, 0.1, 1_050L), null, heading, true))

        assertEquals(PedestrianHeadingSource.MAGNETIC_TRUE, selected?.source)
        assertEquals(0.0, requireNotNull(selected).headingDegreesTrueNorth, 0.001)
    }

    private fun assertLatestAnomalySurvivesDegradedArrival(
        degraded: ChestMountedMagneticFieldSample,
        expectedReason: ChestMountedHeadingGateReason,
    ) {
        val core = ChestMountedHeading()
        val state = state()
        val strong = requireNotNull(magneticCallback(state, magnetic(1_000L, strength = 150.0), 1_000L))
        assertEquals(ChestMountedHeadingGateReason.GEOMAGNETIC_REFERENCE_MISSING,
            core.evaluate(1_000L, rotation(), strong, null).gateReason)
        val degradedCache = requireNotNull(magneticCallback(state, degraded, 1_100L))
        assertEquals("past degraded input cannot change the current strong field",
            ChestMountedHeadingGateReason.FIELD_ANOMALY,
            core.evaluate(1_100L, rotation(), degradedCache, reference()).gateReason)
        val afterRetry = requireNotNull(magneticCallback(state, magnetic(850L), 1_150L))

        assertEquals("an intermediate past arrival cannot discard the newer original strong field",
            ChestMountedHeadingGateReason.FIELD_ANOMALY,
            core.evaluate(1_150L, rotation(), afterRetry, reference()).gateReason)
        assertEquals(1_000L, afterRetry.observedAtMs)
        val currentDegraded = degraded.copy(observedAtMs = 1_200L)
        val current = requireNotNull(magneticCallback(state, currentDegraded, 1_200L))
        assertEquals("current degraded measurements retain their fail-closed gate", expectedReason,
            core.evaluate(1_200L, rotation(1_200L), current, reference()).gateReason)
        val lateRecovery = requireNotNull(magneticCallback(state, magnetic(1_100L), 1_250L))
        assertEquals(current, lateRecovery)
        assertEquals(expectedReason,
            core.evaluate(1_250L, rotation(1_250L), lateRecovery, reference()).gateReason)
    }

    private fun state() = RouteCompassSensorState().apply {
        start(1, 0L, setOf(RouteCompassStream.ROTATION_VECTOR, RouteCompassStream.MAGNETIC_FIELD))
    }

    private fun magneticCallback(
        state: RouteCompassSensorState,
        incoming: ChestMountedMagneticFieldSample,
        receivedAtMs: Long,
        generation: Int = 1,
        measuredAtNanos: Long = incoming.observedAtMs * 1_000_000L,
        receivedAtNanos: Long = receivedAtMs * 1_000_000L,
        onConsumed: () -> Unit = {},
    ): ChestMountedMagneticFieldSample? {
        // The real Tracker retains its strict cache when a raw callback is not dispatched.
        var selected = strictCaches[state]?.takeIf { state.isStarted }?.let { cached ->
            val accuracy = state.latestMagneticMeasurement()?.accuracy ?: cached.accuracy
            cached.copy(accuracy = if (accuracy < cached.accuracy) accuracy else cached.accuracy)
        }
        state.onSensorCallback(generation, RouteCompassStream.MAGNETIC_FIELD,
            measuredAtNanos, receivedAtNanos) { receipt ->
            onConsumed()
            state.onVector(generation, RouteCompassStream.MAGNETIC_FIELD,
                RouteCompassVectorSample(incoming.xMicrotesla, incoming.yMicrotesla, incoming.zMicrotesla,
                    incoming.observedAtMs, incoming.accuracy, measuredAtNanos), receipt)
            selected = selectStrictMagneticMeasurement(state.latestMagneticMeasurement(), incoming)
        }
        strictCaches[state] = selected
        return selected
    }

    private fun missingReferenceThenLateNormal(): ChestMountedHeadingResult {
        val core = ChestMountedHeading()
        val state = state()
        val rotation = rotation(1_000L)
        var cached = requireNotNull(magneticCallback(state, magnetic(1_000L, strength = 150.0), 1_000L))
        val beforeReference = core.evaluate(1_000L, rotation, cached, null)
        assertEquals(ChestMountedHeadingGateReason.GEOMAGNETIC_REFERENCE_MISSING, beforeReference.gateReason)
        assertFalse(beforeReference.isValid)
        val reference = reference() // Trusted reference becomes available at receipt 1050 ms.
        cached = requireNotNull(magneticCallback(state, magnetic(900L), 1_100L))
        return core.evaluate(1_100L, rotation, cached, reference)
    }

    private fun rotation(atMs: Long = 1_000L) = ChestMountedRotationSample(
        RotationMatrix3(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f),
        atMs,
        5.0,
        EarthOrientationAccuracy.HIGH,
    )

    private fun magnetic(
        atMs: Long,
        strength: Double = 50.0,
        accuracy: EarthOrientationAccuracy = EarthOrientationAccuracy.HIGH,
    ) = ChestMountedMagneticFieldSample(0.0, -strength * 0.8, -strength * 0.6, atMs, accuracy)

    private fun reference() = ChestMountedGeomagneticReference(0.0, 53.13, 50.0)
}
