package kr.co.hanium.dreamup.walksafe

import android.location.Location
import com.google.android.gms.tasks.Tasks
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.Executor
import kr.co.hanium.dreamup.walksafe.navigation.session.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss.GnssQualityEstimator
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import kr.co.hanium.dreamup.walksafe.voice.output.SpeechOutputController
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Actual location handler and metadata admission; registration, model, pause presentation and sound are SDK leaves. */
class GpsReferenceMetadataContinuityRegressionTest {
    @After fun clearOwner() { GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation) }

    @Test fun aLateNormalFixKeepsTheNewerReferenceAndQualityButANewFixStillUpdates() = withFixture { h ->
        val newest = h.now
        h.location(); val trusted = h.position.trustedLocation; val raw = h.position.rawRouteLocation
        val quality = h.seedQuality()
        h.advance(100L); val older = newest - 100L
        h.location(measuredAt = older, latitude = 37.500001)
        assertSame(trusted, h.position.trustedLocation); assertSame(raw, h.position.rawRouteLocation)
        val rejected = h.coordinator.observeGnss(GnssPositionObservation(37.500001, 127.0, 15.0, older, h.now))
        assertEquals(GnssObservationDisposition.HARD_REJECTED, rejected.gnssUpdate!!.disposition)
        assertEquals(GnssHardRejectReason.OUT_OF_ORDER, rejected.gnssUpdate!!.hardRejectReason)
        println("newest=$newest old=$older receipt=${h.now} refInputTimes=${h.references} trusted=${h.position.trustedLocation?.elapsedRealtimeMs} disposition=${rejected.gnssUpdate!!.hardRejectReason}")
        assertEquals(listOf(newest), h.references); assertSame(quality, h.position.gnssQuality)
        h.advance(100L); h.location()
        assertEquals(listOf(newest, h.now), h.references)
        assertEquals(h.now, h.position.trustedLocation!!.elapsedRealtimeMs)
    }

    @Test fun aCurrentCoarseFixStillUpdatesTheReferenceAndAnOlderNormalCannotRegressIt() = withFixture { h ->
        h.location(); val preciseAt = h.now
        h.advance(200L); val coarseAt = h.now; h.location(accuracy = 100f)
        assertEquals(listOf(preciseAt, coarseAt), h.references)
        val quality = h.seedQuality()
        val trusted = requireNotNull(h.position.trustedLocation); val raw = h.position.rawRouteLocation
        val run = field(field(h.driver, "guide")!!, "navigationRunState") as NavigationRunState
        val pauseReason = run.directionPauseReason
        val paused = (field(h.driver, "guide") as RouteGuidanceController).positionPaused
        val deadline = requireNotNull(h.base.gate.pendingFailureDeadlineElapsedRealtimeMs())
        assertEquals(coarseAt + GuidanceRecoveryTiming.GPS_GRACE_MS, deadline)
        val pauses = h.pauseRequests.toList()
        h.advance(100L); h.location(measuredAt = preciseAt + 100L, latitude = 37.500001)
        assertEquals("geographic metadata has its own order when the precise fix is older", listOf(preciseAt, coarseAt), h.references)
        assertSame(quality, h.position.gnssQuality)
        assertSame(trusted, h.position.trustedLocation); assertSame(raw, h.position.rawRouteLocation)
        assertEquals(pauseReason, run.directionPauseReason)
        assertEquals(paused, (field(h.driver, "guide") as RouteGuidanceController).positionPaused)
        assertEquals(pauses, h.pauseRequests)
        assertEquals(deadline, h.base.gate.pendingFailureDeadlineElapsedRealtimeMs())
        assertEquals(preciseAt, trusted.elapsedRealtimeMs)
        h.advance(100L); h.location()
        assertEquals(listOf(preciseAt, coarseAt, h.now), h.references)
        assertEquals(h.now, h.position.trustedLocation!!.elapsedRealtimeMs)
        assertNull(h.base.gate.pendingFailureDeadlineElapsedRealtimeMs())
    }

    @Test fun geographicReferenceRemainsAvailableWithoutAnActiveNavigationScope() = withFixture { h ->
        val original = field(h.position, "scope") as PositioningSessionController.Scope
        put(h.position, "scope", object : PositioningSessionController.Scope by original {
            override val navigationAllowed = false
        })
        h.location(accuracy = 100f)
        assertEquals(listOf(h.now), h.references)
    }

    @Test fun anExactDuplicatePreservesTheOriginalReferenceAndQualitySnapshot() = withFixture { h ->
        h.location(); val measuredAt = h.now; val quality = h.seedQuality()
        h.advance(100L); h.location(measuredAt = measuredAt)
        assertEquals(listOf(measuredAt), h.references); assertSame(quality, h.position.gnssQuality)
    }

    @Test fun mockFutureAndWrongOwnerInputsCannotUpdateTheReference() {
        for (kind in listOf("mock", "future", "owner")) withFixture { h ->
            h.location(); val first = h.now; h.advance(100L)
            when (kind) {
                "mock" -> h.mock = true
                "owner" -> put(h.position, "locationCallbackGeneration", h.position.callbackGeneration + 1)
            }
            h.location(measuredAt = if (kind == "future") h.now + 100L else h.now)
            assertEquals(kind, listOf(first), h.references)
        }
    }

    @Test fun collectionTerminationClearsMetadataOrderAndRejectsItsOldCallback() = withFixture { h ->
        h.location(); val first = h.now
        assertTrue(h.position.stopLocationUpdates(stopFieldSession = false))
        assertNull(field(h.position, "latestGeographicMetadataBinding"))
        assertNull(field(h.position, "latestGeographicMetadataAtNanos"))
        assertNull(h.position.gnssQuality); assertEquals(1, h.referenceClears)
        h.advance(100L); h.location()
        assertEquals(listOf(first), h.references)
    }

    @Test fun anInputEpochResetRejectsThePreviousBindingBeforeAnyMetadataWrite() = withFixture { h ->
        h.location(); val first = h.now; val quality = h.seedQuality()
        h.coordinator.reset()
        h.advance(100L); h.location()
        assertEquals(listOf(first), h.references); assertSame(quality, h.position.gnssQuality)
    }

    @Test fun aHistoricalRefusalWithoutGeographicAuthorityStillUsesOrdinarySafetyHandling() = withFixture { h ->
        h.location(); val preciseAt = h.now
        h.advance(200L); h.location(accuracy = 100f)
        put(h.position, "latestGeographicMetadataBinding", null)
        put(h.position, "latestGeographicMetadataAtNanos", null)
        h.advance(100L); h.location(measuredAt = preciseAt + 100L)
        assertTrue(h.pauseRequests.contains("gps_invalid_rejected"))
        assertNull(h.position.trustedLocation)
    }

    @Test fun anExpiredReferenceCannotHideAStaleHistoricalRefusal() = withFixture { h ->
        h.location(); val first = h.now
        h.advance(200L); h.location(accuracy = 100f); val coarseAt = h.now
        // The intermediate old fix is newer than the published precise fix, so only the
        // geographic authority could suppress it. Its original reference time has now expired.
        h.base.now = coarseAt + 10_001L
        h.location(measuredAt = first + 100L)
        assertEquals(listOf(first, coarseAt), h.references)
        assertEquals(kr.co.hanium.dreamup.walksafe.session.EnvironmentEvidenceStatus.FAIL, h.base.evidence?.status)
        assertNull(h.position.trustedLocation)
    }

    private fun withFixture(run: (Fixture) -> Unit) = withSdk17 {
        val h = Fixture()
        try { run(h) } finally { h.output.closeActuator() }
    }

    private class Fixture {
        private val type = Class.forName(GpsAfterDeadlineRecoveryTraceTest::class.java.name + "$" + "Fixture")
        val driver = type.declaredConstructors.single { it.parameterCount == 1 }.apply { isAccessible = true }.newInstance(true)
        val position = field(driver, "position") as PositioningSessionController
        val base = field(driver, "base") as MainActivityContinuousLocationStartBehaviorTest.Fixture
        val output = field(driver, "output") as SpeechOutputController
        val coordinator get() = base.field("positioningCoordinator") as PositioningCoordinator
        val now get() = base.now
        val references = mutableListOf<Long>()
        var referenceClears = 0
        val pauseRequests = mutableListOf<String>()
        var mock = false
        init {
            call(driver, "start")
            val session = MainActivityControllerTestFixture.controller(base.main, "routeCompassSession") as RouteCompassSession
            val tracker = field(session, "tracker") as RouteCompassSession.Tracker
            put(session, "tracker", object : RouteCompassSession.Tracker by tracker { override val available = false })
            val sources = field(position, "sources") as PositioningSessionController.Sources
            put(position, "sources", object : PositioningSessionController.Sources by sources {
                override val orientationAvailable = true
                override val orientationCanStop = false
                override fun mock(location: Location) = this@Fixture.mock
                override fun geomagneticReference(location: Location) { references += location.elapsedRealtimeNanos / 1_000_000L }
                override fun clearGeomagneticReference() { referenceClears++ }
                override fun captureRemoval() = { _: com.google.android.gms.location.LocationCallback -> Tasks.forResult<Void>(null) }
                override val completionExecutor = Executor { it.run() }
            })
            val effects = field(position, "effects") as PositioningSessionController.Effects
            put(position, "effects", object : PositioningSessionController.Effects by effects {
                override fun pauseDirection(reason: String, message: String) { pauseRequests += reason } // Android presentation leaf only.
            })
        }
        fun advance(ms: Long) { call(driver, "step", ms) }
        fun location(accuracy: Float = 15f, measuredAt: Long = now, latitude: Double = 37.5) {
            call(driver, "location", accuracy, latitude, measuredAt - now, true)
        }
        fun seedQuality() = GnssQualityEstimator().snapshot(now * 1_000_000L).also {
            // A source-owned diagnostic snapshot, without inventing a GPS accuracy or usable fix.
            put(position, "latestGnssQualitySnapshot", it)
        }
    }

    companion object {
        fun field(o: Any, name: String): Any? = o.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(o)
        fun put(o: Any, name: String, value: Any?) { o.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(o, value) }
        fun call(o: Any, name: String, vararg args: Any): Any? = try {
            o.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }
                .apply { isAccessible = true }.invoke(o, *args)
        } catch (failure: InvocationTargetException) { throw failure.targetException }
        fun withSdk17(run: () -> Unit) {
            val unsafe = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null) as sun.misc.Unsafe
            val field = android.os.Build.VERSION::class.java.getDeclaredField("SDK_INT")
            val owner = unsafe.staticFieldBase(field); val offset = unsafe.staticFieldOffset(field)
            val old = unsafe.getInt(owner, offset); unsafe.putInt(owner, offset, 17)
            try { run() } finally { unsafe.putInt(owner, offset, old) }
        }
    }
}
