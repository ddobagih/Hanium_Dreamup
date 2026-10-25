package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedGeomagneticReference
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual SensorState -> Navigator outputs for 08.txt #15, including the unchanged-source recovery. */
class RouteCompassRecoveryContinuityRegressionTest {
    // This suite isolates source recovery evidence and the resulting spoken claim.
    // Production 1.5-second delay and immediate safety actions are covered by RouteDirectionRecheckGraceTest.

    private val generation = 1
    private val reference = ChestMountedGeomagneticReference(0.0, 53.13, 50.0)
    private val north = 1.0 / 111_320.0

    @Test
    fun aLowRotationEventRequiresFreshContinuityBeforeTheSameCheckedFallbackResumes() {
        verifyRecovery(accuracyCallback = false)
    }

    @Test
    fun aLowAccuracyCallbackRequiresFreshContinuityBeforeTheSameCheckedFallbackResumes() {
        verifyRecovery(accuracyCallback = true)
    }

    @Test
    fun aCheckedFallbackWithoutAnyPoorRotationIsStillImmediatelyUsable() {
        val state = state()
        state.checked(1_000L)
        val result = state.evaluate(1_000L, reference)
        assertChecked(result, 1_000L)
        val navigator = navigator()

        assertFallbackGuidance(
            warmFallback(navigator, state),
            expected = true,
        )
    }

    @Test
    fun aFreshPoorRotationDoesNotVetoIndependentFallbackButItsNewEpisodeNeedsTwoSamples() {
        val state = state()
        state.checked(1_000L)
        val navigator = navigator()
        assertFallbackGuidance(warmFallback(navigator, state),
            expected = true)
        state.lowRotation(1_550L)
        state.checked(1_601L)

        val result = state.evaluate(1_601L, reference)
        assertChecked(result, 1_601L)
        assertFallbackGuidance(navigator.currentGuidance(1_601L, result.observation, result.reason), expected = false)
        assertFallbackGuidance(navigator.currentGuidance(1_650L, result.observation, result.reason), expected = false)
        state.checked(1_701L)
        assertFallbackGuidance(navigator.currentGuidance(1_701L, state.evaluate(1_701L, reference).observation), expected = true)
    }

    @Test
    fun missingTheRejectedOutputCannotReuseEarlierSameSourceConfirmations() {
        val state = state()
        val navigator = navigator()
        state.checked(1_000L)
        assertFallbackGuidance(warmFallback(navigator, state),
            expected = true)
        state.checked(1_050L)
        val second = state.evaluate(1_050L, reference)
        assertChecked(second, 1_050L)
        assertFallbackGuidance(navigator.currentGuidance(1_050L, second.observation),
            expected = true)
        state.lowRotation(1_100L)
        val oldFallback = state.evaluate(1_100L, reference)
        assertChecked(oldFallback, 1_050L)
        assertEquals(1_100L, oldFallback.observation!!.fallbackRecoveryEpisode!!.startedAtElapsedRealtimeMs)
        // The independent fallback retains its real older time. The consumer misses this new episode.
        state.checked(1_601L)
        val first = state.evaluate(1_601L, reference)
        assertChecked(first, 1_601L)
        assertFallbackGuidance(navigator.currentGuidance(1_601L, first.observation, first.reason), expected = false)
        assertFallbackGuidance(navigator.currentGuidance(1_650L, state.evaluate(1_650L, reference).observation), expected = false)
        state.checked(1_701L)
        assertFallbackGuidance(navigator.currentGuidance(1_701L, state.evaluate(1_701L, reference).observation),
            expected = true)
    }

    @Test
    fun anotherPoorRvReceiptInTheSameEpisodeCannotEraseFreshIndependentFallbackConfirmation() {
        val state = state()
        val navigator = navigator()
        state.checked(1_000L)
        assertFallbackGuidance(warmFallback(navigator, state),
            expected = true)
        state.lowRotation(1_100L)
        state.checked(1_601L)
        val first = state.evaluate(1_601L, reference)
        val episode = requireNotNull(first.observation?.fallbackRecoveryEpisode)
        assertFallbackGuidance(navigator.currentGuidance(1_601L, first.observation), expected = false)

        state.lowRotation(1_700L)
        state.checked(1_701L)
        val rejected = state.evaluate(1_701L, reference)
        assertChecked(rejected, 1_701L)
        val continuingEpisode = requireNotNull(rejected.observation!!.fallbackRecoveryEpisode)
        assertEquals(episode.sequence, continuingEpisode.sequence)
        assertEquals(1_100L, continuingEpisode.startedAtElapsedRealtimeMs)
        assertEquals(1_700L, continuingEpisode.lastRejectedAtElapsedRealtimeMs)
        assertFallbackGuidance(navigator.currentGuidance(1_701L, rejected.observation), expected = true)
        // The receipt updates diagnostics, not the first onset or the source's actual confirmation.
        state.checked(2_201L)
        val next = state.evaluate(2_201L, reference)
        assertChecked(next, 2_201L)
        val sameEpisode = requireNotNull(next.observation?.fallbackRecoveryEpisode)
        assertEquals(episode.sequence, sameEpisode.sequence)
        assertEquals(1_100L, sameEpisode.startedAtElapsedRealtimeMs)
        assertEquals(1_700L, sameEpisode.lastRejectedAtElapsedRealtimeMs)
        assertFallbackGuidance(navigator.currentGuidance(2_201L, next.observation), expected = true)
        state.checked(2_301L)
        assertFallbackGuidance(navigator.currentGuidance(2_301L, state.evaluate(2_301L, reference).observation),
            expected = true)
    }

    @Test
    fun onlyAUsablePrimaryEndsAnEpisodeAndAnotherPoorEventStartsANewOne() {
        val state = state()
        val navigator = navigator()
        state.checked(1_000L)
        assertFallbackGuidance(warmFallback(navigator, state),
            expected = true)
        state.lowRotation(1_100L)
        state.checked(1_601L)
        val first = state.evaluate(1_601L, reference)
        val oldEpisode = requireNotNull(first.observation?.fallbackRecoveryEpisode)
        assertFallbackGuidance(navigator.currentGuidance(1_601L, first.observation), expected = false)
        state.checked(1_701L)
        assertFallbackGuidance(navigator.currentGuidance(1_701L, state.evaluate(1_701L, reference).observation),
            expected = true)

        state.rotation(1_750L, EarthOrientationAccuracy.HIGH)
        val healthy = state.evaluate(1_750L, reference)
        assertEquals(RouteCompassHeadingReason.VALID, healthy.reason)
        assertEquals(RouteFacingSource.ROTATION_VECTOR, healthy.observation?.source)
        assertNull(healthy.observation?.fallbackRecoveryEpisode)
        // The consumer misses the healthy output, then a distinct SDK measurement is LOW in the same ms.
        state.onSensorCallback(1, RouteCompassStream.ROTATION_VECTOR,
            1_750_900_000L, 1_750_900_000L) {
            state.lowRotation(1_750L)
        }
        val oldFallbackAfterNewEpisode = state.evaluate(1_750L, reference)
        assertChecked(oldFallbackAfterNewEpisode, 1_701L)
        assertEquals(1_750L, oldFallbackAfterNewEpisode.observation!!.fallbackRecoveryEpisode!!.startedAtElapsedRealtimeMs)
        assertTrue(oldFallbackAfterNewEpisode.observation!!.observedAtElapsedRealtimeMs <
            oldFallbackAfterNewEpisode.observation!!.fallbackRecoveryEpisode!!.startedAtElapsedRealtimeMs)
        state.checked(2_251L)
        val recovered = state.evaluate(2_251L, reference)
        assertChecked(recovered, 2_251L)
        val newEpisode = requireNotNull(recovered.observation?.fallbackRecoveryEpisode)
        assertTrue(newEpisode.sequence > oldEpisode.sequence)
        assertEquals(1_750L, newEpisode.startedAtElapsedRealtimeMs)
        assertFallbackGuidance(navigator.currentGuidance(2_251L, recovered.observation), expected = false)
        state.checked(2_351L)
        assertFallbackGuidance(navigator.currentGuidance(2_351L, state.evaluate(2_351L, reference).observation),
            expected = true)
    }

    @Test
    fun aHighCallbackAndAnUnboundedPrimaryErrorDoNotEndTheEpisode() {
        val state = state()
        state.checked(1_100L)
        state.onAccuracyChanged(generation, RouteCompassStream.ROTATION_VECTOR, EarthOrientationAccuracy.LOW, 1_100L)
        state.onAccuracyChanged(generation, RouteCompassStream.ROTATION_VECTOR, EarthOrientationAccuracy.HIGH, 1_200L)
        assertChecked(state.evaluate(1_200L, reference), 1_100L)
        state.checked(1_601L)
        val fallback = state.evaluate(1_601L, reference)
        assertChecked(fallback, 1_601L)
        val episode = requireNotNull(fallback.observation?.fallbackRecoveryEpisode)
        state.rotation(1_750L, EarthOrientationAccuracy.HIGH, Float.NaN)
        state.checked(1_750L)
        val withoutUsablePrimary = state.evaluate(1_750L, reference)
        assertChecked(withoutUsablePrimary, 1_750L)
        assertEquals(episode, withoutUsablePrimary.observation?.fallbackRecoveryEpisode)
    }

    private fun verifyRecovery(accuracyCallback: Boolean) {
        val state = state()
        val navigator = navigator()
        state.checked(1_000L)
        val initial = state.evaluate(1_000L, reference)
        assertChecked(initial, 1_000L)
        assertFallbackGuidance(warmFallback(navigator, state),
            expected = true)

        state.checked(1_100L)
        if (accuracyCallback) {
            state.onAccuracyChanged(generation, RouteCompassStream.ROTATION_VECTOR, EarthOrientationAccuracy.LOW, 1_100L)
        } else {
            state.lowRotation(1_100L)
        }
        val independentFallback = state.evaluate(1_100L, reference)
        assertChecked(independentFallback, 1_100L)
        assertEquals(1_100L, independentFallback.observation!!.fallbackRecoveryEpisode!!.startedAtElapsedRealtimeMs)
        assertFallbackGuidance(navigator.currentGuidance(1_100L, independentFallback.observation, independentFallback.reason), expected = false)
        // Re-reading the same original measurement is not a second confirmation.
        assertFallbackGuidance(navigator.currentGuidance(1_150L, state.evaluate(1_150L, reference).observation), expected = false)

        // A second actual checked observation confirms this same independent source.
        state.checked(1_601L)
        val firstRecovery = state.evaluate(1_601L, reference)
        assertChecked(firstRecovery, 1_601L)
        assertFallbackGuidance(navigator.currentGuidance(1_601L, firstRecovery.observation, firstRecovery.reason), expected = true)

        // Querying the same original measurement cannot manufacture a second confirmation.
        val repeated = state.evaluate(1_650L, reference)
        assertChecked(repeated, 1_601L)
        assertFallbackGuidance(navigator.currentGuidance(1_650L, repeated.observation, repeated.reason), expected = true)

        state.checked(1_701L)
        val confirmed = state.evaluate(1_701L, reference)
        assertChecked(confirmed, 1_701L)
        assertFallbackGuidance(navigator.currentGuidance(1_701L, confirmed.observation, confirmed.reason),
            expected = true)
    }

    private fun assertChecked(result: RouteCompassHeadingResult, atMs: Long) {
        assertEquals(RouteCompassHeadingReason.VALID, result.reason)
        val observation = requireNotNull(result.observation)
        assertEquals(RouteFacingSource.GRAVITY_MAGNETIC, observation.source)
        assertEquals(RouteFacingSensorQuality.CALIBRATED_SENSOR_CHECKS, observation.sensorQuality)
        assertEquals(90.0, observation.degreesTrueNorth, 0.00001)
        assertEquals(atMs, observation.observedAtElapsedRealtimeMs)
        assertNull(observation.headingAccuracyDegrees)
        assertNull(observation.reportedHeadingAccuracyDegrees)
    }

    private fun warmFallback(navigator: RouteNavigator, state: RouteCompassSensorState): RouteNavigatorUpdate {
        val cold = navigator.update(location(), 1_000L, false, null, state.evaluate(1_000L, reference).observation)
        assertFalse(cold.instruction.orEmpty().contains("약 9시 방향"))
        state.checked(1_010L)
        val fresh = state.evaluate(1_010L, reference)
        return requireNotNull(navigator.currentGuidance(1_010L, fresh.observation, fresh.reason))
    }

    private fun assertFallbackGuidance(updateOrNull: RouteNavigatorUpdate?, expected: Boolean) {
        val update = requireNotNull(updateOrNull)
        val sentence = requireNotNull(update.instruction)
        assertEquals("route_guidance", update.reason)
        assertFalse(update.offRoute); assertFalse(update.arrived)
        val diagnostic = requireNotNull(update.facingDiagnostic)
        val token = requireNotNull(update.speechCueToken)
        assertFalse(sentence, sentence.contains("시 사이"))
        if (expected) {
            assertTrue(sentence, sentence.contains("약 9시 방향"))
            assertTrue(sentence, sentence.contains("미터"))
            assertEquals(RouteFacingExpression.CLOCK, diagnostic.expression)
            assertEquals(RouteClockReferenceKind.NOMINAL_TREND_CLOCK, diagnostic.clockReferenceKind)
            assertTrue(token.requiresFacingEvidence)
            assertTrue(requireNotNull(token.facingObservedAtElapsedRealtimeMs) > 1_000L)
        } else {
            assertFalse(sentence, sentence.contains("약 9시 방향"))
            assertFalse(sentence, sentence.contains("몸을 돌리세요"))
            assertFalse(sentence, sentence.contains("직진하세요"))
            assertEquals(RouteFacingExpression.UNKNOWN, diagnostic.expression)
            assertFalse(token.requiresFacingEvidence)
            assertNull(token.facingObservedAtElapsedRealtimeMs)
        }
    }

    private fun state() = RouteCompassSensorState().apply {
        start(generation, 0L, RouteCompassStream.entries.toSet())
    }

    private fun RouteCompassSensorState.checked(atMs: Long) {
        val gravity = RouteCompassVectorSample(0.0, 9.80665, 0.0, atMs, EarthOrientationAccuracy.HIGH)
        val magnetic = RouteCompassVectorSample(-30.0, -40.0, 0.0, atMs, EarthOrientationAccuracy.HIGH)
        onVector(generation, RouteCompassStream.GRAVITY, gravity, atMs)
        onVector(generation, RouteCompassStream.ACCELEROMETER, gravity, atMs)
        onVector(generation, RouteCompassStream.MAGNETIC_FIELD, magnetic, atMs)
    }

    private fun RouteCompassSensorState.lowRotation(atMs: Long) {
        rotation(atMs, EarthOrientationAccuracy.LOW)
    }

    private fun RouteCompassSensorState.rotation(atMs: Long, accuracy: EarthOrientationAccuracy, error: Float = 5f) {
        val heading = Math.toRadians(90.0)
        val rotation = RotationMatrix3(
            cos(heading).toFloat(), 0f, (-sin(heading)).toFloat(),
            (-sin(heading)).toFloat(), 0f, (-cos(heading)).toFloat(),
            0f, 1f, 0f,
        )
        assertEquals(accuracy, onRotationVector(generation, rotation, error, atMs, accuracy, atMs))
    }

    private fun location() = TrustedLocation(37.0 + 20 * north, 127.0, 3f, 1_000L)

    private fun navigator() = RouteNavigator(RouteNavigatorConfig(directionRecheckGraceMs = 0L)).apply {
        setRoute(WalkingRoute(
            priority = "TEST",
            summary = WalkingRouteSummary(200, 200),
            polyline = listOf(RoutePoint(37.0, 127.0), RoutePoint(37.0 + 200 * north, 127.0)),
            guidePoints = listOf(
                WalkingRouteGuidePoint(0, RoutePoint(37.0 + 150 * north, 127.0), "직진하세요.", 150, 50, turnType = 11),
            ),
        ))
    }
}
