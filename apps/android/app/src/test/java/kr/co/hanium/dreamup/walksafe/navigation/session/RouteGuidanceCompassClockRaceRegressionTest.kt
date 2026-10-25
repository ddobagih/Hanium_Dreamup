package kr.co.hanium.dreamup.walksafe.navigation.session

import kr.co.hanium.dreamup.walksafe.navigation.AndroidEarthOrientationTracker
import kr.co.hanium.dreamup.walksafe.navigation.EarthOrientationAccuracy
import kr.co.hanium.dreamup.walksafe.navigation.GeomagneticReferenceRecord
import kr.co.hanium.dreamup.walksafe.navigation.RouteCompassHeadingReason
import kr.co.hanium.dreamup.walksafe.navigation.RouteCompassHeadingResult
import kr.co.hanium.dreamup.walksafe.navigation.RouteCompassRawSensorConsumer
import kr.co.hanium.dreamup.walksafe.navigation.RouteCompassSensorState
import kr.co.hanium.dreamup.walksafe.navigation.RouteCompassStream
import kr.co.hanium.dreamup.walksafe.navigation.RouteFacingSource
import kr.co.hanium.dreamup.walksafe.navigation.positioning.ChestMountedGeomagneticReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import sun.misc.Unsafe

/**
 * Actual Tracker -> controller -> Navigator, with raw sensor input and elapsed time controlled.
 * GuidanceFixture exposes the real playback validator/terminal callbacks; it is not native TTS.
 * Android construction/registration is skipped and the test runner supplies Log.isLoggable=false.
 */
class RouteGuidanceCompassClockRaceRegressionTest {
    @Test
    fun firstGuidanceUsesTheClockAfterTheTrackerReturnsANewerNormalSample() {
        val f = Fixture()
        f.publishDuringNextQuery()

        f.g.controller.updateRouteGuidance(f.fix)

        f.assertNormalRacedResult()
        assertEquals("The first valid direction must produce its route instruction", 1, f.g.sent.size)
        f.assertNoSpuriousDirectionFault()
        assertTrue(f.g.sent.single().message.contains("약 12시 방향"))
        assertTrue(f.g.sent.single().current())
    }

    @Test
    fun scheduledSpeechRetryUsesTheClockAfterTheTrackerReturnsANewerNormalSample() {
        val f = Fixture()
        val original = f.startCue()
        original.failed()
        val retry = f.g.pending.single()
        f.g.pending.remove(retry)
        // Run the actual registered retry after the existing transport backoff has elapsed.
        f.g.now += f.g.nav.instructionRetryIntervalMs
        f.publishDuringNextQuery()

        retry.second.run()

        f.assertNormalRacedResult()
        assertEquals("A normal fresh direction must restore the failed instruction", 2, f.g.sent.size)
        f.assertNoSpuriousDirectionFault()
        assertTrue(f.g.sent.last().current())
        assertTrue("Retry must keep the original route position evidence",
            f.g.nav.currentAcceptedRouteMatchFor(f.fix.elapsedRealtimeMs) != null)
        assertNull(f.g.nav.currentAcceptedRouteMatchFor(f.g.now))
    }

    @Test
    fun playbackStartUsesTheClockAfterItsFacingLookupWithoutOpeningFalseGrace() {
        val f = Fixture()
        val cue = f.startCue()
        f.g.now += 250L
        f.g.validatingStarted = true
        f.publishDuringNextQuery()

        val currentAtStart = cue.current()

        f.assertNormalRacedResult()
        assertTrue("Actual fresh input still supports this exact queued instruction", currentAtStart)
        // The old clock can also hide behind a retained-direction grant; that is still a false
        // BAD episode, even when it happens to keep this one packet's START validator true.
        f.assertNoSpuriousDirectionFault()
        cue.done()
        assertEquals(listOf(cue.message), f.g.lastResults)
    }

    @Test
    fun aTrulyFutureOriginalSampleStillCannotProduceTheFirstGuidanceInstruction() {
        val f = Fixture()
        f.publishDuringNextQuery(futureAtReceipt = true)

        f.g.controller.updateRouteGuidance(f.fix)

        val read = f.racedReads.single()
        assertFalse("An original sample later than receipt must not reach the raw consumer", read.admitted)
        assertNull(f.state.latestRotationEventAt())
        assertNull(read.result.observation)
        assertTrue("Refreshing the query clock must not admit an invalid direction", f.g.sent.isEmpty())
    }

    private class Fixture {
        val g = GuidanceFixture().apply { now = 10_000L; install() }
        val fix = g.fix.copy(elapsedRealtimeMs = g.now)
        val state = RouteCompassSensorState().apply {
            start(GENERATION, 8_000L, setOf(RouteCompassStream.ROTATION_VECTOR))
        }
        private val tracker = unsafe().allocateInstance(AndroidEarthOrientationTracker::class.java)
            as AndroidEarthOrientationTracker
        private var nextQuery: ((Long) -> Boolean)? = null
        val racedReads = mutableListOf<RacedRead>()

        init {
            field("routeCompassState", state)
            field("started", true)
            field("registrationGeneration", GENERATION)
            val clock: () -> Long = {
                assertTrue(Thread.holdsLock(tracker))
                g.now
            }
            field("elapsedRealtimeMs", clock)
            field("geomagneticReferenceRecord", GeomagneticReferenceRecord(
                ChestMountedGeomagneticReference(0.0, 53.13, 50.0),
                9_000L, 0.0, 0.0, 0L,
            ))
            g.compassPeer = object : RouteGuidanceController.CompassReader {
                override val revision = 0L
                override val invalidationPending = false
                override fun heading(nowMs: Long): RouteCompassHeadingResult {
                    val publication = nextQuery
                    nextQuery = null
                    val admitted = publication?.invoke(nowMs)
                    val result = tracker.latestRouteCompassHeading(nowMs)
                    if (admitted != null) racedReads += RacedRead(nowMs, g.now, admitted, result)
                    return result
                }
                override fun facing(nowMs: Long) = heading(nowMs).observation
            }
        }

        fun startCue(): GuidanceFixture.Submission {
            assertTrue(rotation(g.now, g.now))
            g.controller.updateRouteGuidance(fix)
            assertEquals("Normal initial fixture must establish one real controller cue", 1, g.sent.size)
            assertNoSpuriousDirectionFault()
            return g.sent.single()
        }

        fun publishDuringNextQuery(futureAtReceipt: Boolean = false) {
            check(nextQuery == null)
            nextQuery = { callerNowMs ->
                // The UI captured callerNowMs, then the sensor callback won the Tracker monitor.
                // One shared current clock advances; the original sensor timestamp is unchanged.
                g.now = callerNowMs + 2L
                val sampleMs = callerNowMs + if (futureAtReceipt) 3L else 1L
                val receiptMs = callerNowMs + if (futureAtReceipt) 2L else 1L
                rotation(sampleMs, receiptMs)
            }
        }

        fun assertNormalRacedResult() {
            val read = racedReads.single()
            assertTrue(read.admitted)
            assertEquals(read.queryMs + 2L, read.currentMs)
            assertEquals("The tracker itself must have returned a valid current sample",
                RouteCompassHeadingReason.VALID, read.result.reason)
            val observation = requireNotNull(read.result.observation)
            assertEquals(RouteFacingSource.ROTATION_VECTOR, observation.source)
            assertEquals(read.queryMs + 1L, observation.observedAtElapsedRealtimeMs)
            assertEquals(90.0, observation.degreesTrueNorth, 0.01)
        }

        fun assertNoSpuriousDirectionFault() {
            assertNull("A normal newer sample must not start a direction fault episode",
                g.nav.currentDirectionGraceSnapshot()?.onsetElapsedRealtimeMs)
            assertFalse(g.nav.isDirectionRecheckPending())
        }

        private fun rotation(sampleMs: Long, receiptMs: Long): Boolean {
            var consumed = false
            synchronized(tracker) {
                state.onSensorCallback(GENERATION, RouteCompassStream.ROTATION_VECTOR,
                    sampleMs * NS_PER_MS, receiptMs * NS_PER_MS) { receivedAtMs ->
                    consumed = true
                    // Upright rear-camera axis east, with a reported 2-degree heading error.
                    RouteCompassRawSensorConsumer.rotationVector(state, GENERATION,
                        floatArrayOf(0.5f, -0.5f, -0.5f, 0.5f, Math.toRadians(2.0).toFloat()),
                        sampleMs * NS_PER_MS, EarthOrientationAccuracy.HIGH, receivedAtMs)
                }
            }
            return consumed
        }

        private fun field(name: String, value: Any) {
            AndroidEarthOrientationTracker::class.java.getDeclaredField(name).apply {
                isAccessible = true
            }.set(tracker, value)
        }

        data class RacedRead(val queryMs: Long, val currentMs: Long, val admitted: Boolean,
            val result: RouteCompassHeadingResult)

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
