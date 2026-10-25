package kr.co.hanium.dreamup.walksafe

import java.lang.reflect.InvocationTargetException
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.*
import kr.co.hanium.dreamup.walksafe.navigation.session.*
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.session.runtime.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Reuses the admitted, stable-session ColdFixture and the actual Main owner graph.
 * GPS/RV scalars, network results, dispatcher, view effects and TTS ports are controlled.
 * Arrival's Activity effect is a controlled leaf around actual Acquisition/Lifecycle/Cleanup/Guide.
 * Main's profile admission, HOME view, native sensors, real HTTP and acoustic speech are outside JVM. */
class MainActivityTypicalDirectionFlowIntegrationTest {
    @After fun retireGateway() {
        GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
    }

    @Test fun ordinaryLowGpsAndReportedRvErrorsReachASingleClockCueAndUpdateItsDistance() {
        for (sample in listOf(Sample(8.0, 0.0, 12.0), Sample(10.0, 0.0, 15.0), Sample(15.0, 15.0, 8.0))) {
            val f = Fixture(sample)
            val first = f.startAndCompleteIntro()
            f.startThenComplete(first)
            val firstDistance = distance(first.message)
            val previousCount = f.requests.size
            f.publishGps(20.0, 16_000L)
            assertEquals(16_000L, f.fix.elapsedRealtimeMs)
            assertEquals(sample.gpsAccuracyM.toFloat(), f.raw.accuracyM, 0f)
            assertEquals(16_000L, f.raw.elapsedRealtimeMs)
            assertEquals(f.generation, f.coordinator.currentInputGeneration())
            assertTrue("A fresh position should reach the next current direction: $sample ${f.interactions.takeLast(4)}", f.requests.size > previousCount)
            val updated = f.requests.last()
            f.assertSingleClock(updated)
            assertTrue(updated.message, distance(updated.message) < firstDistance)
            f.startThenComplete(updated)
            assertFalse("An old cue cannot regain ownership after newer GPS", first.current())
        }
    }

    @Test fun sameMainGraphWalksToArrivalAndEndsGuidanceWithoutAcknowledgingOldCallbacks() {
        for (sample in listOf(Sample(8.0, 0.0, 12.0), Sample(10.0, 0.0, 15.0), Sample(15.0, 15.0, 8.0))) {
            val f = Fixture(sample)
            val first = f.startAndCompleteIntro()
            f.startThenComplete(first)
            val originalRetry = f.executedRetries.first()
            val endpoint = f.destinationNorthM
            var latestAction = first
            // Six metres per five seconds models ordinary walking, with genuine original fix times.
            for (index in 1..50) {
                if (f.arrivalStarted) break
                f.publishGps(index * 6.0, 6_000L + index * 5_000L)
                f.requests.lastOrNull()?.takeIf { it !== latestAction }?.let {
                    latestAction = it
                    f.startThenComplete(it)
                }
            }
            // The exact installed destination is observed twice, with the original 3 s dwell retained.
            if (!f.arrivalStarted) f.publishGps(endpoint, 259_000L)
            if (!f.arrivalStarted) f.publishGps(endpoint, 262_000L)
            if (sample.gpsAccuracyM > 10.0) {
                assertNull("15m ordinary guidance cannot manufacture precise automatic arrival", f.navigator.pendingUserDecision())
                assertFalse(f.arrivalStarted)
                assertTrue(f.arrivalSpeech.isEmpty())
                // New precise SDK inputs qualify the unchanged sample-count and dwell conditions.
                f.publishGps(endpoint, 265_000L, accuracyM = 8.0)
                assertFalse("One precise sample cannot bypass the original arrival dwell", f.arrivalStarted)
                f.publishGps(endpoint, 268_000L, accuracyM = 8.0)
                if (!f.arrivalStarted) f.publishGps(endpoint, 271_000L, accuracyM = 8.0)
            }
            assertTrue("Qualified original GPS samples must initiate automatic arrival", f.arrivalStarted)
            assertEquals(WalkSessionState.PAUSED, f.base.lifecycle.snapshot().state)
            assertNull(f.navigator.pendingUserDecision())
            assertFalse(f.run.active)
            assertFalse(f.navigator.hasRoute())
            assertNull(f.run.destination)
            val final = f.arrivalSpeech.single()
            assertEquals("도착했습니다. 안내를 종료합니다", final.message)
            assertTrue(final.current())
            assertEquals("Speech dispatch acceptance cannot finish the walk", 0, f.arrivalFinishes)
            final.done()
            assertEquals(WalkSessionState.ENDED, f.base.lifecycle.snapshot().state)
            final.done(); final.failed()
            assertEquals(1, f.arrivalFinishes)
            assertFalse(first.current())
            assertFalse(latestAction.current())
            assertNull(f.completedCue())
            latestAction.done()
            first.done()
            assertNull("Late DONE cannot restore a completed cue after route end", f.completedCue())
            val requestCount = f.requests.size
            originalRetry.run()
            assertEquals(requestCount, f.requests.size)
            assertEquals(WalkSessionState.ENDED, f.base.lifecycle.snapshot().state)
            assertFalse(f.guide.isRouteSpeechCurrent(f.base.epoch, f.acquisition.requestGeneration, f.scope.feedbackGeneration))
        }
    }

    private data class Sample(val gpsAccuracyM: Double, val facingDegrees: Double, val rvReportedErrorDegrees: Double)
    private class Request(val owner: Any) {
        val message: String get() = read(owner, "message")
        val current: () -> Boolean get() = read(owner, "current")
        val done: () -> Unit get() = read(owner, "done")
    }
    private data class ArrivalSpeech(val message: String, val current: () -> Boolean,
        val done: () -> Unit, val failed: () -> Unit)

    private class Fixture(val sample: Sample) {
        private val cold = Class.forName("kr.co.hanium.dreamup.walksafe.MainActivityColdFirstDirectionFlowIntegrationTest\$ColdFixture")
            .getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        val base: MainActivityContinuousLocationStartBehaviorTest.Fixture = read(cold, "base")
        val coordinator: PositioningCoordinator = read(cold, "coordinator")
        val generation: Long = read(cold, "generation")
        val guide: RouteGuidanceController = read(cold, "guide")
        val positions: PositioningSessionController = read(cold, "positions")
        val acquisition: RouteAcquisitionController = read(cold, "acquisition")
        val run: NavigationRunState = read(cold, "run")
        val navigator get() = run.navigator
        val destinationNorthM get() = (requireNotNull(run.destination).latitude - 37.5) * METRES_PER_DEGREE
        val scope: RouteGuidanceController.ScopeReader = read(cold, "scope")
        private val speech: Any = read(cold, "speech")
        private val callbacks: MutableList<Pair<Runnable, Long>> = read(cold, "callbacks")
        private val events: MutableList<Pair<String, Map<String, Any?>>> = read(cold, "events")
        private val requestCache = java.util.IdentityHashMap<Any, Request>()
        val requests: List<Request> get() = read<List<Any>>(speech, "requests").map { requestCache.getOrPut(it) { Request(it) } }
        val interactions = mutableListOf<String>()
        val executedRetries = mutableListOf<Runnable>()
        val arrivalSpeech = mutableListOf<ArrivalSpeech>()
        var arrivalStarted = false; private set
        var arrivalFinishes = 0; private set
        val fix get() = requireNotNull(positions.currentRouteLocationOrNull())
        val raw get() = requireNotNull(positions.rawRouteLocation)

        init {
            // These coordinators capture their ports when the production graph is constructed.
            // Install the same controlled SDK leaves on the real children as on the parent.
            val scheduler: RouteCallbackScheduler = read(guide, "callbacks")
            for (name in listOf("decisionSpeech", "recheckNotices")) {
                val child: Any = read(guide, name)
                put(child, "speech", speech)
                put(child, "callbacks", scheduler)
                if (name == "recheckNotices") put(child, "elapsedRealtimeMs", { base.now })
            }
            val speechPort = read<RouteGuidanceController.SpeechPort>(guide, "speech")
            put(guide, "speech", object : RouteGuidanceController.SpeechPort by speechPort {
                override fun submitStatus(message: String, current: () -> Boolean, completed: () -> Unit,
                    failed: (() -> Unit)?): Boolean {
                    if (message != "도착했습니다. 안내를 종료합니다") return speechPort.submitStatus(message, current, completed, failed)
                    arrivalSpeech += ArrivalSpeech(message, current, completed, { failed?.invoke() })
                    return true
                }
            })
            val effects = read<RouteGuidanceController.Effects>(guide, "effects")
            put(guide, "effects", object : RouteGuidanceController.Effects by effects {
                override fun beginArrival(token: RouteNavigatorDecisionToken): RouteGuidanceController.ArrivalCompletion? {
                    if (acquisition.completeDetectedArrival(token) == null) return null
                    cleanup.enterWalkSessionForegroundRecheckAndCancelOutputs("arrival_ending")
                    arrivalStarted = true
                    val endingEpoch = base.lifecycle.snapshot().epoch
                    val session = GatewaySessionProcessCoordinator.snapshot()
                    return object : RouteGuidanceController.ArrivalCompletion {
                        override fun isCurrent() = base.lifecycle.snapshot().let {
                            it.epoch == endingEpoch && it.state == WalkSessionState.PAUSED
                        } && GatewaySessionProcessCoordinator.snapshot().let {
                            it.generation == session.generation && it.session === session.session
                        }
                        override fun finish() { if (isCurrent()) {
                            arrivalFinishes++
                            base.lifecycle.handle(WalkSessionEvent.EndRequested)
                            cleanup.cancelWalkSessionOutputs("arrival_completed")
                        } }
                    }
                }
            })
            invoke(cold, "acquire", sample.gpsAccuracyM)
            assertEquals(PositionQuality.LOW, read<PositioningSnapshot>(cold, "acquired").quality)
            invoke(cold, "requestAndInstall", false)
            invoke(cold, "installIntro")
            assertFalse(scope.developmentBypass)
            val runtime = MainActivityControllerTestFixture.controller(base.main, "officialEnvironmentRuntimeController")
            put(runtime, "nowMs", { base.now })
            put(runtime, "schedule", { delay: Long, action: () -> Unit -> callbacks += Runnable(action) to delay })
            val presentation = read<RoutePresentation>(acquisition, "presentation")
            put(acquisition, "presentation", object : RoutePresentation by presentation {
                override fun navigationStatus(text: String) { interactions += text }
            })
        }

        private fun faceNow() {
            put(cold, "compass", RouteFacingObservation(sample.facingDegrees, sample.rvReportedErrorDegrees, base.now,
                source = RouteFacingSource.ROTATION_VECTOR, sensorQuality = RouteFacingSensorQuality.REPORTED_ANGULAR_ERROR,
                reportedHeadingAccuracyDegrees = sample.rvReportedErrorDegrees))
        }

        fun startAndCompleteIntro(): Request {
            faceNow()
            guide.updateRouteGuidance(fix, raw)
            val intro = Request(read<List<Any>>(speech, "intros").single())
            assertTrue(intro.current())
            assertEquals(0, requests.size)
            intro.done()
            assertEquals("Intro DONE schedules rather than directly dispatches the action", 0, requests.size)
            runRegisteredRetry()
            assertEquals("The cold nominal clock requires a second real observation", 0, requests.size)
            runRegisteredRetry()
            val first = requests.single()
            assertSingleClock(first)
            assertEquals(6_000L, raw.elapsedRealtimeMs)
            assertEquals(sample.gpsAccuracyM.toFloat(), raw.accuracyM, 0f)
            return first
        }

        private fun runRegisteredRetry() {
            val registered = requireNotNull(read<Runnable?>(guide, "navigationSpeechRetryRunnable"))
            val posted = callbacks.single { it.first === registered }
            callbacks.remove(posted)
            base.now += posted.second
            faceNow()
            executedRetries += registered
            registered.run()
        }

        fun assertSingleClock(request: Request) {
            assertTrue(request.message, Regex("약 (?:[1-9]|1[0-2])시 방향.*[0-9]+(?:미터|m)")
                .containsMatchIn(request.message))
            assertFalse(request.message, request.message.contains("시에서"))
            assertFalse(request.message, request.message.contains("시 사이 방향"))
            val actual = read<RouteFacingObservation>(cold, "compass")
            assertEquals(sample.rvReportedErrorDegrees, actual.reportedHeadingAccuracyDegrees ?: Double.NaN, 0.0)
            assertEquals(sample.rvReportedErrorDegrees, actual.headingAccuracyDegrees ?: Double.NaN, 0.0)
        }

        fun startThenComplete(request: Request) {
            assertSingleClock(request)
            val previous = completedCue()
            val started = events.count { it.first == "route_guidance_started" }
            put(speech, "validating", true)
            assertTrue(request.current())
            put(speech, "validating", false)
            assertEquals(previous, completedCue())
            assertEquals(started + 1, events.count { it.first == "route_guidance_started" })
            request.done()
            assertNotNull(completedCue())
        }

        fun publishGps(northM: Double, measuredAt: Long, accuracyM: Double = sample.gpsAccuracyM) {
            val authority = base.field("gatewayWalkAuthorityController") as GatewayWalkAuthorityController
            val lease = requireNotNull(authority.activeLeaseOrNull(base.epoch))
            if (lease.localDeadlineElapsedMs - measuredAt < 20_000L) {
                // A controlled server renewal uses the real current-lease operation, no authority setter.
                val operation = requireNotNull(authority.beginRenew(base.epoch))
                assertEquals(GatewayWalkAuthorityCompletion.LEASE_ACTIVE,
                    authority.completeRenew(operation, lease.copy(result = "RENEWED", serverTimeEpochMs = lease.serverTimeEpochMs + 40_000L,
                        leaseExpiresAtEpochMs = lease.leaseExpiresAtEpochMs + 40_000L,
                        localDeadlineElapsedMs = base.now + 60_000L)))
            }
            base.now = measuredAt + 100L
            val snapshot = coordinator.observeGnss(GnssPositionObservation(37.5 + northM / METRES_PER_DEGREE, 127.0,
                accuracyM, measuredAt, base.now))
            assertNotEquals(GnssObservationDisposition.HARD_REJECTED, snapshot.gnssUpdate?.disposition)
            assertEquals(generation, coordinator.currentInputGeneration())
            base.main.recordPositioningGpsObservation(snapshot, measuredAt, accuracyM, { base.now }, {})
            guide.applyPositionConfidenceDecision(snapshot, emitAnnouncements = false)
            positions.applyPositioningMotionSnapshot(snapshot, base.binding as PositioningCollectionBinding)
            if (arrivalStarted) return
            assertEquals(measuredAt, fix.elapsedRealtimeMs)
            put(positions, "latestRawRouteLocation", positions.rawAndroidRouteLocation(
                37.5 + northM / METRES_PER_DEGREE, 127.0, accuracyM.toFloat(), measuredAt))
            val previousRequests = requests.size
            faceNow()
            guide.updateRouteGuidance(fix, raw)
            if (requests.size == previousRequests && navigator.pendingUserDecision() == null &&
                read<Runnable?>(guide, "navigationSpeechRetryRunnable") != null) {
                // An isolated GPS callback is followed by the actual registered retry with a
                // distinct current heading, as the continuous compass stream would supply it.
                runRegisteredRetry()
            }
        }

        private val cleanup by lazy { WalkSessionCleanupController(
            object : WalkSessionRuntimeAccess {
                override fun available() = true
                override fun snapshot() = base.lifecycle.snapshot()
                override fun isRuntimeEpochCurrent(epoch: WalkRuntimeEpoch) = base.lifecycle.isRuntimeEpochCurrent(epoch)
                override fun transition(event: WalkSessionEvent) = base.lifecycle.handle(event)
            }, base.field("gatewayWalkAuthorityController") as GatewayWalkAuthorityController,
            MainActivityControllerTestFixture.controller(base.main, "gatewayWalkSessionController") as GatewayWalkSessionController,
            { GatewaySessionProcessCoordinator.snapshot().session }, ResumeFixture().controller,
            port<WalkCleanupAdmission> { name -> if (name == "persistInterruptionMarker") true else null },
            port<WalkCleanupNative> { name -> if (name in setOf("stopVoiceService", "stopDepthSession")) true else null },
            port<WalkCleanupNavigation> { name -> when (name) {
                "cancelRequestsForPause" -> acquisition.cancelNavigationRequestsForPause()
                "cancelWalkSessionRouteState" -> { acquisition.resetRouteState(); null }
                "stopPositionFieldSession", "locationCleanupSucceeded" -> true
                "locationCollectionAllowsWork" -> false
                "stopLocationUpdates" -> { positions.stopLocationUpdates(); null }
                else -> null
            } }, port<WalkCleanupFeedback> { null }, port<WalkCleanupReports> { null }, false)
        }

        fun completedCue(): Any? = read(navigator, "completedSpeechCue")
    }

    companion object {
        private const val METRES_PER_DEGREE = 111_194.92664455874
        private fun distance(message: String) = requireNotNull(Regex("([0-9]+)(?:미터|m)").find(message)).groupValues[1].toInt()
        @Suppress("UNCHECKED_CAST") private fun <T> read(owner: Any, name: String): T =
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T
        private fun put(owner: Any, name: String, value: Any?) {
            owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value)
        }
        @Suppress("UNCHECKED_CAST") private inline fun <reified T> port(noinline handler: (String) -> Any?): T =
            java.lang.reflect.Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) {
                _, method, _ -> handler(method.name)
            } as T
        private fun invoke(owner: Any, name: String, vararg args: Any?): Any? {
            val method = owner.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }
                .apply { isAccessible = true }
            return try { method.invoke(owner, *args) } catch (failure: InvocationTargetException) { throw failure.targetException }
        }
    }
}
