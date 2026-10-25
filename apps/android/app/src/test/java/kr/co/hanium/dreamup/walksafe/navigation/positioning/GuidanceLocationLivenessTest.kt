package kr.co.hanium.dreamup.walksafe.navigation.positioning

import java.lang.reflect.Proxy
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigator
import kr.co.hanium.dreamup.walksafe.navigation.session.*
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

/** Original GNSS streams, actual default Session/Coordinator and actual route publication predicate. */
class GuidanceLocationLivenessTest {
    @Test fun walkingEightMeterFixesMustAcquirePublishablePosition() = walking(8.0)
    @Test fun walkingTenMeterFixesMustAcquirePublishablePosition() = walking(10.0)
    @Test fun walkingFifteenMeterFixesMustAcquirePublishablePosition() = walking(15.0)

    private fun walking(accuracyM: Double) {
        val f = Fixture("walking-$accuracyM")
        val rows = (0 until 40).map { index ->
            val measuredAtMs = 1_000L + index * 1_500L
            f.accept(accuracyM, measuredAtMs, eastM = index * 1.8)
        }
        describe("walking-$accuracyM", rows)
        assertTrue("Distinct valid 1.2m/s fixes must eventually publish a route fix; quality=" +
            rows.groupingBy { it.quality }.eachCount(), rows.any { f.publishable(it) })
        assertFalse("Normal fresh fixes must eventually leave position recovery", rows.last().confidence.guidancePaused)
    }

    @Test fun coarseEpisodeMustRecoverUsingFreshFiveMeterFixesWithoutHighSeeding() {
        val f = Fixture("home-recovery")
        val initial = f.accept(5.0, 1_000L)
        val coarse = (1..5).map { f.accept(50.0, 1_000L + it * 1_500L) }
        val recovery = (6..35).map { f.accept(5.0, 1_000L + it * 1_500L) }
        describe("home5-coarse50x5-recovery5x30", listOf(initial) + coarse + recovery)
        assertTrue("Fresh GNSS recovery fixes remain informative", recovery.all {
            it.gnssUpdate?.estimateAtGnssMeasurement?.lastInformativeGnssAtElapsedRealtimeMs == it.raw?.elapsedRealtimeMs
        })
        assertTrue("Fresh usable recovery position must be publishable", f.publishable(recovery.last()))
        assertFalse("An earlier coarse episode must not permanently require unobtainable precision", recovery.last().confidence.guidancePaused)
    }

    @Test fun originalMeasurementGenerationAndMotionEpochRejectInvalidInputs() {
        val f = Fixture("admission")
        assertTrue(f.coordinator.isUserMotionInputCurrent(1_000L, f.owner))
        assertFalse(f.coordinator.isUserMotionInputCurrent(1_000L,
            f.owner.copy(walkEpoch = WalkRuntimeEpoch("foreign-walk", 0L))))
        val wrongGeneration = f.coordinator.observeGnss(GnssPositionObservation(37.5, 127.0, 8.0, 1_000L), f.generation + 1L)
        assertEquals(PedestrianMotionHardRejectReason.GENERATION_MISMATCH, wrongGeneration.motionUpdate?.hardRejectReason)
        assertNull(wrongGeneration.filtered)
        assertFalse(f.publishable(wrongGeneration))
        val mock = f.coordinator.observeGnss(GnssPositionObservation(37.5, 127.0, 8.0, 1_000L, mock = true), f.generation)
        assertEquals(GnssHardRejectReason.MOCK, mock.gnssUpdate?.hardRejectReason)
        val stale = f.coordinator.observeGnss(GnssPositionObservation(37.5, 127.0, 8.0, 1_000L, 11_001L), f.generation)
        assertEquals(GnssHardRejectReason.STALE, stale.gnssUpdate?.hardRejectReason)
        assertNull(stale.filtered)
        assertFalse(f.publishable(stale))
    }

    private fun describe(name: String, rows: List<PositioningSnapshot>) {
        println("TRACE name=$name samples=${rows.size} quality=${rows.groupingBy { it.quality }.eachCount()} " +
            "paused=${rows.count { it.confidence.guidancePaused }} " +
            "publishable=${rows.count { Fixture.actualPublicationPredicate(it) }} " +
            "informative=${rows.count { it.gnssUpdate?.estimateAtGnssMeasurement?.lastInformativeGnssAtElapsedRealtimeMs == it.raw?.elapsedRealtimeMs }}")
        rows.forEachIndexed { index, s ->
            if (index in listOf(0, 1, 2, 3, 4, 5, 6, 10, 20, 30, rows.lastIndex)) println(
                "SAMPLE index=$index measurement=${s.raw?.elapsedRealtimeMs} filtered=${s.filtered?.elapsedRealtimeMs} " +
                "sigma=${s.filtered?.horizontalUncertaintyM} quality=${s.quality} " +
                "disposition=${s.gnssUpdate?.disposition} gate=${s.gnssUpdate?.gateWeight} " +
                "confidence=${s.confidence.transition} paused=${s.confidence.guidancePaused} " +
                "recovery=${s.confidence.consecutiveHighRecoverySamples} generation=${s.inputGeneration}")
        }
    }

    private class Fixture(name: String) {
        val session = session()
        val coordinator = session.coordinator
        val generation = coordinator.currentInputGeneration()
        val owner = UserMotionStateOwner(0L, WalkRuntimeEpoch(name, 0L), null, "liveness-private")
        init { check(coordinator.beginUserMotionEpoch(owner, 1_000L)) }
        fun accept(accuracyM: Double, measurementMs: Long, eastM: Double = 0.0): PositioningSnapshot {
            val longitude = 127.0 + eastM / (111_320.0 * kotlin.math.cos(Math.toRadians(37.5)))
            val snapshot = coordinator.observeGnss(GnssPositionObservation(37.5, longitude, accuracyM,
                measurementMs, measurementMs), generation)
            assertNotEquals("Fresh non-mock distinct GNSS must not be hard rejected", GnssObservationDisposition.HARD_REJECTED,
                snapshot.gnssUpdate?.disposition)
            assertEquals(generation, snapshot.inputGeneration)
            assertEquals(measurementMs, snapshot.raw?.elapsedRealtimeMs)
            assertEquals(measurementMs, snapshot.filteredAtGnssMeasurement?.elapsedRealtimeMs)
            assertEquals(owner, coordinator.currentUserMotionOwner())
            return snapshot
        }
        fun publishable(s: PositioningSnapshot) = session.mayPublishPositioningRouteFix(s.quality, s.confidence)
        companion object {
            // Real constructor and default coordinator; unused Android/feedback boundaries fail if called.
            private fun session() = PositioningSessionController(NavigationRunState(RouteNavigator()),
                unexpected(), unexpected(), unexpected(), unexpected(), unexpected(),
                { error("GPS SDK outside numeric test") }, { error("Compass outside numeric test") },
                { error("Guidance actuator outside numeric test") }, { error("Route HTTP outside numeric test") },
                { error("Measurement time must not be replaced") }, { error("UI dispatch outside numeric test") })
            private inline fun <reified T> unexpected(): T = Proxy.newProxyInstance(T::class.java.classLoader,
                arrayOf(T::class.java)) { _, method, _ -> error("Unexpected boundary ${T::class.java.name}.${method.name}") } as T
            private val predicate by lazy { session() }
            fun actualPublicationPredicate(s: PositioningSnapshot) = predicate.mayPublishPositioningRouteFix(s.quality, s.confidence)
        }
    }
}
