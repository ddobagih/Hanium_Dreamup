package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedGeomagneticReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * The real synchronized Tracker query reads the same State/RawSensorConsumer as Android callbacks.
 * Only SDK construction and elapsed time are controlled; no sensor registration or device claim.
 */
class AndroidEarthOrientationQueryReceiptRaceRegressionTest {
    @Test
    fun rotationReceiptPublishedAfterCallerClockDoesNotBecomeASensorStall() {
        val fixture = Fixture(setOf(RouteCompassStream.ROTATION_VECTOR))
        val callerNowMs = fixture.nowMs
        fixture.rotation(sampleMs = 9_999L, receiptMs = 10_001L)
        fixture.nowMs = 10_002L

        fixture.assertHeading(fixture.nowMs, RouteFacingSource.ROTATION_VECTOR, 9_999L)
        fixture.assertHeading(callerNowMs, RouteFacingSource.ROTATION_VECTOR, 9_999L)
    }

    @Test
    fun fallbackReceiptsPublishedAfterCallerClockDoNotBecomeASensorStall() {
        val fixture = Fixture()
        val callerNowMs = fixture.nowMs
        fixture.fallback(sampleMs = 9_999L, receiptMs = 10_001L)
        fixture.nowMs = 10_002L

        fixture.assertHeading(fixture.nowMs, RouteFacingSource.GRAVITY_MAGNETIC, 9_999L)
        fixture.assertHeading(callerNowMs, RouteFacingSource.GRAVITY_MAGNETIC, 9_999L)
    }

    @Test
    fun referencePublishedAfterCallerClockUsesTheSameCurrentSnapshotAsSensors() {
        val fixture = Fixture()
        fixture.fallback(sampleMs = 9_999L, receiptMs = 9_999L)
        val callerNowMs = fixture.nowMs
        fixture.publishReference(createdAtMs = 10_001L)
        fixture.nowMs = 10_002L

        fixture.assertHeading(fixture.nowMs, RouteFacingSource.GRAVITY_MAGNETIC, 9_999L)
        fixture.assertHeading(callerNowMs, RouteFacingSource.GRAVITY_MAGNETIC, 9_999L)
    }

    @Test
    fun currentReceiptDoesNotRefreshAnOriginalMeasurementPastTheFreshnessLimit() {
        val fixture = Fixture()
        fixture.fallback(sampleMs = 9_500L, receiptMs = 10_000L)
        fixture.assertHeading(10_000L, RouteFacingSource.GRAVITY_MAGNETIC, 9_500L)
        fixture.nowMs = 10_001L

        val result = fixture.tracker.latestRouteCompassHeading(fixture.nowMs)
        assertEquals(RouteCompassHeadingReason.STALE, result.reason)
        assertNull(result.observation)
        assertEquals(9_500L, fixture.state.latestGravityAt())
        assertEquals(9_500L, fixture.state.latestMagneticAt())
    }

    @Test
    fun actualReceiptGapStillReportsSensorStalled() {
        val fixture = Fixture()
        fixture.fallback(sampleMs = 10_000L, receiptMs = 10_000L)
        fixture.assertHeading(10_000L, RouteFacingSource.GRAVITY_MAGNETIC, 10_000L)
        fixture.nowMs = 12_001L

        val result = fixture.tracker.latestRouteCompassHeading(fixture.nowMs)
        assertEquals(RouteCompassHeadingReason.SENSOR_STALLED, result.reason)
        assertNull(result.observation)
    }

    @Test
    fun laterQueryClockCannotAdmitAnOriginalSampleThatWasFutureAtReceipt() {
        val fixture = Fixture()
        fixture.fallback(sampleMs = 9_999L, receiptMs = 9_999L)
        // Both ns values truncate to 10001 ms. The original sample is still later than receipt,
        // even though the query below occurs after both; it must never reach the raw consumer.
        val consumed = fixture.raw(
            RouteCompassStream.MAGNETIC_FIELD,
            floatArrayOf(0f, -120f, -90f),
            sampleNanos = 10_001_500_000L,
            receiptNanos = 10_001_100_000L,
        )
        fixture.nowMs = 10_002L

        assertFalse("A future original timestamp must not reach the consumer", consumed)
        assertEquals(10_001L, fixture.state.lastReceiptAt(RouteCompassStream.MAGNETIC_FIELD))
        assertEquals(9_999L, fixture.state.latestMagneticAt())
        fixture.assertHeading(fixture.nowMs, RouteFacingSource.GRAVITY_MAGNETIC, 9_999L)
    }

    @Test
    fun callbackPublicationUsesTheLockedClockAfterReferenceRefreshWithoutChangingReceiptTime() {
        val fixture = Fixture(setOf(RouteCompassStream.ROTATION_VECTOR))
        // Receipt was captured before the callback acquired the monitor. A reference update
        // acquired it first; original-time admission must still use that earlier receipt.
        fixture.publishReference(createdAtMs = 10_001L)
        fixture.nowMs = 10_002L
        fixture.rotation(sampleMs = 9_999L, receiptMs = 10_000L)

        val event = requireNotNull(fixture.tracker.publishAdmittedRouteCompassSample(
            generation = 1,
            stream = RouteCompassStream.ROTATION_VECTOR,
            sensorTimestampNanos = 9_999_000_000L,
            receivedAtNanos = 10_000_000_000L,
        ))

        assertEquals(1, event.registrationGeneration)
        assertEquals(RouteCompassStream.ROTATION_VECTOR, event.stream)
        assertEquals(9_999_000_000L, event.sensorTimestampNanos)
        assertEquals("Publication must retain the real receipt, not the later evaluation clock",
            10_000L, event.receivedAtMs)
        assertEquals(RouteCompassHeadingReason.VALID, event.heading.reason)
        assertFalse("A normal sample must not publish a false direction invalidation", event.invalidated)
        val observation = requireNotNull(event.heading.observation)
        assertEquals(RouteFacingSource.ROTATION_VECTOR, observation.source)
        assertEquals(9_999L, observation.observedAtElapsedRealtimeMs)
        assertEquals(0.0, observation.degreesTrueNorth, 0.01)
    }

    private class Fixture(
        streams: Set<RouteCompassStream> = setOf(
            RouteCompassStream.ROTATION_VECTOR,
            RouteCompassStream.GRAVITY,
            RouteCompassStream.MAGNETIC_FIELD,
        ),
    ) {
        var nowMs = 10_000L
        val state = RouteCompassSensorState().apply { start(GENERATION, 8_000L, streams) }
        val tracker = unsafe().allocateInstance(AndroidEarthOrientationTracker::class.java)
            as AndroidEarthOrientationTracker

        init {
            field("routeCompassState", state)
            field("routeObservationPublisher", RouteCompassObservationPublisher().apply { reset(GENERATION) })
            field("started", true)
            field("registrationGeneration", GENERATION)
            val clock: () -> Long = {
                assertTrue("Snapshot clock must be read under Tracker's monitor", Thread.holdsLock(tracker))
                nowMs
            }
            field("elapsedRealtimeMs", clock)
            publishReference(createdAtMs = 9_000L)
        }

        fun publishReference(createdAtMs: Long) = synchronized(tracker) {
            field("geomagneticReferenceRecord", GeomagneticReferenceRecord(
                reference = ChestMountedGeomagneticReference(0.0, Math.toDegrees(atan2(40.0, 30.0)), 50.0),
                createdAtElapsedRealtimeMs = createdAtMs,
                latitudeDegrees = 0.0,
                longitudeDegrees = 0.0,
                modelTimeMillis = 0L,
            ))
        }

        fun rotation(sampleMs: Long, receiptMs: Long, errorRadians: Float = Math.toRadians(2.0).toFloat()) {
            val halfTurnRoot = sqrt(0.5).toFloat()
            assertTrue(raw(RouteCompassStream.ROTATION_VECTOR,
                floatArrayOf(halfTurnRoot, 0f, 0f, halfTurnRoot, errorRadians),
                sampleMs * NS_PER_MS, receiptMs * NS_PER_MS))
        }

        fun fallback(sampleMs: Long, receiptMs: Long) {
            rotation(sampleMs, receiptMs, errorRadians = -1f)
            assertTrue(raw(RouteCompassStream.GRAVITY, floatArrayOf(0f, 9.80665f, 0f),
                sampleMs * NS_PER_MS, receiptMs * NS_PER_MS))
            assertTrue(raw(RouteCompassStream.MAGNETIC_FIELD, floatArrayOf(0f, -40f, -30f),
                sampleMs * NS_PER_MS, receiptMs * NS_PER_MS))
        }

        fun raw(stream: RouteCompassStream, values: FloatArray, sampleNanos: Long, receiptNanos: Long): Boolean {
            var consumed = false
            // Mirrors the production publication lock. The caller clock can already have been
            // captured by a UI query when these newer receipts win this monitor first.
            synchronized(tracker) {
                state.onSensorCallback(GENERATION, stream, sampleNanos, receiptNanos) { receivedAtMs ->
                    consumed = true
                    if (stream == RouteCompassStream.ROTATION_VECTOR) {
                        RouteCompassRawSensorConsumer.rotationVector(state, GENERATION, values, sampleNanos,
                            EarthOrientationAccuracy.HIGH, receivedAtMs)
                    } else {
                        RouteCompassRawSensorConsumer.vector(state, GENERATION, stream, values, sampleNanos,
                            EarthOrientationAccuracy.HIGH, receivedAtMs)
                    }
                }
            }
            return consumed
        }

        fun assertHeading(queryMs: Long, source: RouteFacingSource, sampleMs: Long) {
            val result = tracker.latestRouteCompassHeading(queryMs)
            assertEquals("Query=$queryMs, current clock=$nowMs must use one current input snapshot",
                RouteCompassHeadingReason.VALID, result.reason)
            val observation = requireNotNull(result.observation)
            assertEquals(source, observation.source)
            assertEquals("Receipt/query time must not replace the original sample time",
                sampleMs, observation.observedAtElapsedRealtimeMs)
            assertEquals(0.0, observation.degreesTrueNorth, 0.01)
        }

        private fun field(name: String, value: Any) {
            AndroidEarthOrientationTracker::class.java.getDeclaredField(name).apply {
                isAccessible = true
            }.set(tracker, value)
        }

        private companion object {
            const val GENERATION = 1
            const val NS_PER_MS = 1_000_000L

            fun unsafe(): Unsafe = Unsafe::class.java.getDeclaredField("theUnsafe").run {
                isAccessible = true
                get(null) as Unsafe
            }
        }
    }
}
