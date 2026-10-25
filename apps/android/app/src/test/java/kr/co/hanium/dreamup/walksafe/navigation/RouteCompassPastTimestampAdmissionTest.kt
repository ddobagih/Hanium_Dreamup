package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedGeomagneticReference
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

/** Original measurement identity is separate from receipt liveness and displayed milliseconds. */
class RouteCompassPastTimestampAdmissionTest {
    @Test fun directPastAndEqualBadSamplesPreserveTheNewestGoodAndRateBaseline() {
        for (kind in listOf("missing", "nan", "wide", "equal")) {
            val s = state(); rv(s, 1_000L); rv(s, 1_100L)
            val original = good(s, 1_100L)
            s.onRotationVector(1, if (kind == "missing") null else matrix(180.0),
                if (kind == "nan") Float.NaN else 31f, if (kind == "equal") 1_100L else 900L,
                EarthOrientationAccuracy.HIGH, 1_200L)
            assertEquals(kind, original, good(s, 1_200L))
            assertEquals(1_100L, s.latestRotationEventAt())
            assertEquals(1_200L, s.lastReceiptAt(RouteCompassStream.ROTATION_VECTOR))
            rv(s, 1_300L, 10.0)
            assertEquals(50.0, s.latestRotationRateDegreesPerSecond()!!, .01)
        }
    }

    @Test fun currentBadOwnsTheWatermarkAndOnlyANewerGoodCanRecover() {
        for (error in listOf(31f, Float.NaN)) {
            val s = state(); rv(s, 1_100L)
            rv(s, 1_200L, error = error)
            assertNull(s.evaluate(1_200L, reference).observation)
            rv(s, 1_150L, receipt = 1_250L)
            assertNull(s.evaluate(1_250L, reference).observation)
            assertEquals(1_200L, s.latestRotationEventAt())
            rv(s, 1_250L)
            assertEquals(1_250L, good(s, 1_250L).observedAtElapsedRealtimeMs)
        }
    }

    @Test fun originalNsDropHappensBeforeTheWholeSdkConsumerButReceiptsRemainReal() {
        val s = state(); var mutations = 0
        raw(s, 1_100_900_000L, 1_200_000_000L) { mutations++; rv(s, 1_100L, receipt = it) }
        val original = good(s, 1_200L)
        for (ns in listOf(1_100_100_000L, 1_100_900_000L, 1_099_000_000L)) {
            raw(s, ns, 1_270_000_000L) { mutations++; rv(s, ns / 1_000_000L, 180.0, 31f, it) }
        }
        assertEquals(1, mutations)
        assertEquals(original, good(s, 1_270L))
        assertEquals(1_200L, s.firstReceiptAt(RouteCompassStream.ROTATION_VECTOR))
        assertEquals(1_270L, s.lastReceiptAt(RouteCompassStream.ROTATION_VECTOR))
    }

    @Test fun distinctOwnedSdkNanosecondsInOneMillisecondRemainDistinctWithoutRestamping() {
        val s = state(); val publisher = RouteCompassObservationPublisher().apply { reset(1) }
        var mutations = 0
        for ((ns, heading) in listOf(1_000_100_000L to 0.0, 1_000_300_000L to 20.0)) {
            raw(s, ns, 1_000_400_000L) { mutations++; rv(s, 1_000L, heading, receipt = it) }
            val event = requireNotNull(publisher.sample(1, RouteCompassStream.ROTATION_VECTOR,
                ns, 1_000_400_000L, s.evaluate(1_000L, reference)))
            assertEquals(ns, event.sensorTimestampNanos)
            assertEquals(1_000L, event.heading.observation!!.observedAtElapsedRealtimeMs)
        }
        assertEquals(2, mutations)
        assertEquals(20.0, good(s, 1_000L).degreesTrueNorth, .0001)
        assertNull(s.latestRotationRateDegreesPerSecond()) // No invented sub-ms rate from rounded times.
        raw(s, 1_000_300_000L, 1_001_000_000L) { mutations++; rv(s, 1_000L, 180.0, 31f, it) }
        assertEquals(2, mutations)
        assertEquals(20.0, good(s, 1_001L).degreesTrueNorth, .0001)
    }

    @Test fun separateStreamsKeepIndependentOriginalWatermarksAndRejectCrossStreamPayloads() {
        val s = state(); raw(s, 1_100_000_000L, 1_100_000_000L) { rv(s, 1_100L) }
        s.onSensorCallback(1, RouteCompassStream.GRAVITY, 1_000_000_000L, 1_200_000_000L) {
            s.onVector(1, RouteCompassStream.GRAVITY, gravity(1_000L, 1_000_000_000L), it)
        }
        assertEquals(1_000L, s.latestGravityAt()); assertEquals(1_100L, s.latestRotationEventAt())
        raw(s, 1_250_000_000L, 1_250_000_000L) {
            s.onVector(1, RouteCompassStream.GRAVITY, gravity(1_250L, 1_250_000_000L), it)
        }
        assertEquals("RV callback cannot retag itself as gravity", 1_000L, s.latestGravityAt())
        assertEquals(1_200L, s.lastReceiptAt(RouteCompassStream.GRAVITY))
    }

    @Test fun directVectorsUseTheirActualNsIdentityAndEqualLegacyWrappersCannotReplaceGood() {
        val s = state()
        val latest = gravity(1_000L, 1_000_300_000L)
        s.onVector(1, RouteCompassStream.GRAVITY, gravity(1_000L, 1_000_100_000L), 1_000L)
        s.onVector(1, RouteCompassStream.GRAVITY, latest, 1_000L)
        s.onVector(1, RouteCompassStream.GRAVITY, gravity(1_000L, 1_000_200_000L).copy(y = 15.0), 1_100L)
        assertEquals(latest, field<RouteCompassVectorSample>(s, "latestGravity"))
        s.onVector(1, RouteCompassStream.GRAVITY, gravity(1_000L, null).copy(y = 15.0), 1_100L)
        assertEquals(latest, field<RouteCompassVectorSample>(s, "latestGravity"))
    }

    @Test fun qualityOnlyAccuracyChangesDoNotEraseTheOriginalRawWatermark() {
        val s = state(); raw(s, 1_100_900_000L, 1_101_000_000L) { rv(s, 1_100L, receipt = it) }
        s.onAccuracyChanged(1, RouteCompassStream.ROTATION_VECTOR, EarthOrientationAccuracy.LOW, 1_120L)
        assertTrue(s.rotationQualityWait()); assertNull(s.evaluate(1_120L, reference).observation)
        s.onAccuracyChanged(1, RouteCompassStream.ROTATION_VECTOR, EarthOrientationAccuracy.HIGH, 1_130L)
        var mutations = 0
        raw(s, 1_100_100_000L, 1_140_000_000L) { mutations++; rv(s, 1_100L, receipt = it) }
        assertEquals(0, mutations); assertTrue(s.rotationQualityWait())
        raw(s, 1_150_100_000L, 1_150_200_000L) { rv(s, 1_150L, receipt = it) }
        assertFalse(s.rotationQualityWait())
        assertEquals(1_150L, good(s, 1_150L).observedAtElapsedRealtimeMs)
    }

    @Test fun aCurrentRawBadThenALateGoodDoesNotRecoverBeforeTheActualNewGood() {
        val s = state(); raw(s, 1_100_000_000L, 1_100_000_000L) { rv(s, 1_100L) }
        raw(s, 1_200_100_000L, 1_200_200_000L) {
            s.onRotationVector(1, null, 12f, 1_200L, EarthOrientationAccuracy.HIGH, it)
        }
        var mutations = 0
        raw(s, 1_150_000_000L, 1_250_000_000L) { mutations++; rv(s, 1_150L, receipt = it) }
        rv(s, 1_150L, receipt = 1_250L)
        assertEquals(0, mutations); assertEquals(1_200L, s.latestRotationEventAt())
        assertNull(s.evaluate(1_250L, reference).observation)
        raw(s, 1_250_100_000L, 1_250_200_000L) { rv(s, 1_250L, receipt = it) }
        assertEquals(1_250L, good(s, 1_250L).observedAtElapsedRealtimeMs)
    }

    @Test fun aFreshCurrentReversalStillInvalidatesTheOriginalNavigationClaimImmediately() {
        val s = state(); rv(s, 1_100L)
        val nav = RouteNavigator().apply { setRoute(WalkingRoute("TEST", WalkingRouteSummary(300, 300),
            listOf(point(0.0), point(300.0)), emptyList())) }
        val p = point(50.0); val cue = nav.update(TrustedLocation(p.latitude, p.longitude, 2f, 1_100L),
            1_100L, false, facingObservation = good(s, 1_100L))
        assertNull(cue.speechCueToken) // A cold nominal clock requires a second actual sample.
        rv(s, 1_110L)
        val confirmed = requireNotNull(nav.retryGuidance(1_110L, good(s, 1_110L)))
        assertTrue(nav.reserveInstruction(confirmed))
        raw(s, 1_200_000_000L, 1_200_000_000L) { rv(s, 1_200L, 180.0) }
        val fresh = good(s, 1_200L)
        assertTrue(nav.hasHardInvalidatedDirectionClaim(1_200L, fresh))
        assertTrue(nav.retryGuidance(1_200L, fresh)!!.cancelStaleNavigationSpeech)
        assertFalse(nav.isInstructionCurrent(confirmed, 1_200L, fresh))
    }

    @Test fun stopRestartAndLimitedReregistrationKeepTheirActualOwnerAndSampleRules() {
        val s = state(); raw(s, 1_100_000_000L, 1_100_000_000L) { rv(s, 1_100L) }
        s.stop(); s.start(2, 1_100L, streams)
        var mutations = 0
        s.onSensorCallback(1, RouteCompassStream.ROTATION_VECTOR, 1_200_000_000L, 1_200_000_000L) { mutations++ }
        assertEquals(0, mutations); assertNull(s.lastReceiptAt(RouteCompassStream.ROTATION_VECTOR))
        s.onSensorCallback(2, RouteCompassStream.ROTATION_VECTOR, 1_100_000_000L, 1_200_000_000L) {
            s.onRotationVector(2, matrix(20.0), 12f, 1_100L, EarthOrientationAccuracy.HIGH, it)
        }
        assertEquals(20.0, good(s, 1_200L).degreesTrueNorth, .0001)
        s.onReregistered(3, 1_300L, streams)
        s.onSensorCallback(2, RouteCompassStream.ROTATION_VECTOR, 1_400_000_000L, 1_400_000_000L) { mutations++ }
        s.onSensorCallback(3, RouteCompassStream.ROTATION_VECTOR, 1_100_000_000L, 1_400_000_000L) { mutations++ }
        assertEquals(0, mutations)
        s.onSensorCallback(3, RouteCompassStream.ROTATION_VECTOR, 1_400_000_000L, 1_400_000_000L) {
            s.onRotationVector(3, matrix(30.0), 12f, 1_400L, EarthOrientationAccuracy.HIGH, it)
        }
        assertEquals(1_400L, good(s, 1_400L).observedAtElapsedRealtimeMs)
    }

    @Test fun roundedPayloadTimeAndOriginalNsMustBelongToTheOwnedCallback() {
        val s = state(); rv(s, 1_000L)
        raw(s, 1_100_100_000L, 1_110_000_000L) { rv(s, 1_101L, 180.0, receipt = it) }
        assertEquals(1_000L, s.latestRotationEventAt())
        s.onSensorCallback(1, RouteCompassStream.GRAVITY, 1_100_100_000L, 1_110_000_000L) {
            s.onVector(1, RouteCompassStream.GRAVITY, gravity(1_100L, 1_100_200_000L), it)
        }
        assertNull(s.latestGravityAt())
        s.onSensorCallback(1, RouteCompassStream.GRAVITY, 1_100_300_000L, 1_110_000_000L) {
            s.onVector(1, RouteCompassStream.GRAVITY, gravity(1_100L, 1_100_300_000L), it)
        }
        assertEquals(1_100L, s.latestGravityAt())
    }

    @Test fun anOldCallbackCannotRetagItsPayloadAfterAnOwnerChangesInsideTheConsumer() {
        val s = state()
        raw(s, 1_100_000_000L, 1_100_000_000L) {
            s.stop(); s.start(2, 1_100L, streams)
            s.onRotationVector(2, matrix(180.0), 12f, 1_100L, EarthOrientationAccuracy.HIGH, it)
        }
        assertNull(s.latestRotationEventAt()); assertNull(s.lastReceiptAt(RouteCompassStream.ROTATION_VECTOR))
        s.onSensorCallback(2, RouteCompassStream.ROTATION_VECTOR, 1_200_000_000L, 1_200_000_000L) {
            s.onRotationVector(2, matrix(0.0), 12f, 1_200L, EarthOrientationAccuracy.HIGH, it)
        }
        assertEquals(1_200L, good(s, 1_200L).observedAtElapsedRealtimeMs)
    }

    private val reference = ChestMountedGeomagneticReference(0.0, 0.0, 50.0)
    private val streams = RouteCompassStream.entries.toSet()
    private fun state() = RouteCompassSensorState().apply { start(1, 0L, streams) }
    private fun good(s: RouteCompassSensorState, at: Long) = requireNotNull(s.evaluate(at, reference).observation)
    private fun rv(s: RouteCompassSensorState, at: Long, heading: Double = 0.0, error: Float = 12f,
        receipt: Long = at) = s.onRotationVector(1, matrix(heading), error, at, EarthOrientationAccuracy.HIGH, receipt)
    private fun raw(s: RouteCompassSensorState, ns: Long, receipt: Long, consume: (Long) -> Unit) =
        s.onSensorCallback(1, RouteCompassStream.ROTATION_VECTOR, ns, receipt, consume)
    private fun gravity(at: Long, ns: Long?) = RouteCompassVectorSample(0.0, 9.80665, 0.0, at,
        EarthOrientationAccuracy.HIGH, ns)
    private fun point(north: Double) = RoutePoint(north / 111194.92664455874, 0.0)
    private fun matrix(heading: Double): RotationMatrix3 {
        val h = Math.toRadians(heading)
        return RotationMatrix3(cos(h).toFloat(), 0f, (-sin(h)).toFloat(), (-sin(h)).toFloat(), 0f,
            (-cos(h)).toFloat(), 0f, 1f, 0f)
    }
    @Suppress("UNCHECKED_CAST") private fun <T> field(o: Any, name: String): T =
        o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o) as T
}
