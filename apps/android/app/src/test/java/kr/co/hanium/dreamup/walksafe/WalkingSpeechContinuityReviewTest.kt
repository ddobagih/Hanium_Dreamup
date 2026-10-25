package kr.co.hanium.dreamup.walksafe

import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.util.Locale
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.session.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.*
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import kr.co.hanium.dreamup.walksafe.voice.output.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Actual Main composition/Guide/Nav/Output/Actuator with controlled native START/DONE/stop.
 * Same N6 stable SDK-admitted owner/no-risk fixture. New samples have new original times.
 * The controlled fixture admits startup capability. Private reproduction used N5 app4;
 * integrated Gradle execution uses the current MainActivity and startup callback implementation.
 */
class WalkingSpeechContinuityReviewTest {
    @After fun retireGateway() { GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation) }
    @Test fun stableRvRangeAndEightMetreGpsFinishThreeSecondSentence() = normalWalk(8.0, false, List(10){0.0})
    @Test fun oneMetreWalkingTenMetreGpsFinishThreeSecondSentence() = normalWalk(10.0, true, List(10){0.0})
    @Test fun oneMetreWalkingFifteenMetreGpsFinishThreeSecondSentence() = normalWalk(15.0, true, List(10){0.0})
    @Test fun smallFiveDegreeSwayRetainsPlayingRange() = normalWalk(15.0, true, listOf(2.0,5.0,2.0,0.0,-2.0,-5.0,-2.0,0.0,2.0,0.0))
    @Test fun ordinaryFifteenDegreeSwayRetainsPlayingRange() = normalWalk(15.0, true, listOf(5.0,10.0,15.0,10.0,5.0,0.0,-5.0,-10.0,-15.0,0.0))
    @Test fun threeDegreeSwayAcrossRangeBoundaryRetainsStartedSentence() = normalWalk(15.0,true,List(10){2.0},5.0)
    @Test fun unsafeReverseDirectionCancelsAlreadyStartedRange() {
        val f=Fixture(15.0,12.0)
        try {
            val action=f.startWithNativeIntro();f.connectCompassEvents()
            f.clock.advanceBy(100);f.face();f.native.start(action.id)
            val stops=f.native.stops
            f.clock.advanceBy(300);f.headingTick(180.0)
            assertEquals(stops+1,f.native.stops)
            assertNull(read<Any?>(f.navigator,"completedSpeechCue"))
            f.native.done(action.id)
            assertNull(read<Any?>(f.navigator,"completedSpeechCue"))
        } finally {f.output.closeActuator()}
    }
    @Test fun replacedRouteRejectsOldQueuedNativeStart() = obsoleteQueuedStart("route")
    @Test fun revokedLocationAdmissionRejectsQueuedNativeStart() = obsoleteQueuedStart("permission")
    @Test fun retiredGatewayActorRejectsQueuedNativeStart() = obsoleteQueuedStart("actor")
    private fun obsoleteQueuedStart(kind:String) {
        val f=Fixture(15.0,12.0)
        try {
            val action=f.startWithNativeIntro();f.connectCompassEvents()
            val old:RouteSpeechCueToken=read(f.navigator,"pendingSpeechCue")
            val stopsBeforeHandoff=f.native.stops
            when(kind) {
                "route" -> {
                    val acquisition:RouteAcquisitionController=read(read<Any>(f,"cold"),"acquisition")
                    val route:WalkingRoute=read(f.navigator,"route")
                    acquisition.installRequestedRoute(route,route.polyline.last(),f.fix,null,"review_replacement",false)
                }
                "permission" -> put(read<Any>(f,"cold"),"locationGranted",false)
                "actor" -> GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
            }
            f.clock.advanceBy(100);f.face()
            val before=f.native.stops
            if(kind=="route") {
                assertEquals("Installation must cancel the queued old route before START",stopsBeforeHandoff+1,before)
                assertFalse(f.actor.isAppSpeechActive())
            }
            f.native.start(action.id)
            assertEquals("$kind must reject native START for old owner without stopping twice",
                if(kind=="route") before else before+1,f.native.stops)
            f.native.done(action.id)
            assertNotEquals(old,read<RouteSpeechCueToken?>(f.navigator,"completedSpeechCue"))
        } finally {f.output.closeActuator()}
    }
    @Test fun tenDegreeRangeOscillationWithRawFifteenMetreWalkingFinishes() = normalWalk(15.0,true,listOf(5.0,2.0,-5.0,5.0,2.0,-5.0,5.0,2.0,-5.0,5.0),5.0)
    @Test fun fifteenDegreeRangeOscillationWithRawFifteenMetreWalkingFinishes() = normalWalk(15.0,true,listOf(10.0,2.0,-5.0,10.0,2.0,-5.0,10.0,2.0,-5.0,10.0),5.0)
    @Test fun adjacentNominalClockCandidateDoesNotChopPlayingSentence() = changedDirection(320.0,12.0,false)
    @Test fun unknownDirectionRetainsPlayingUntilItsFixedGraceDeadline() = changedDirection(340.0,30.0,true)
    private fun changedDirection(heading:Double,error:Double,unknown:Boolean) {
        val f=Fixture(15.0,12.0,5.0)
        try {
            val action=f.startWithNativeIntro();f.connectCompassEvents()
            f.clock.advanceBy(100);f.face();f.native.start(action.id)
            val offered:RouteFacingGuidanceResult=read(f.navigator,"offeredFacing")
            val cue:RouteSpeechCueToken=read(f.navigator,"pendingSpeechCue")
            val fresh=RouteFacingGuidance.evaluate(0.0,RouteFacingObservation(heading,error,f.clock.now,reportedHeadingAccuracyDegrees=error),f.clock.now)
            if(unknown) assertEquals(RouteFacingDirection.UNKNOWN,fresh.direction)
            else {
                assertEquals(1,fresh.clockHour);assertNull(fresh.clockRange)
                assertFalse(fresh.clockPrecisionAvailable)
                assertEquals(RouteClockReferenceKind.NOMINAL_TREND_CLOCK,fresh.clockReferenceKind)
                assertNotEquals(offered.clockHour,fresh.clockHour)
                assertNotEquals(RouteFacingDirection.BEHIND,fresh.direction)
            }
            val before=f.native.stops
            f.clock.advanceBy(300);f.headingTick(heading,error)
            if(unknown) {
                val deadline=requireNotNull(f.navigator.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
                assertEquals(9_000L,GuidanceRecoveryTiming.DIRECTION_GRACE_MS)
                assertEquals(f.clock.now+GuidanceRecoveryTiming.DIRECTION_GRACE_MS,deadline)
                assertEquals(before,f.native.stops)
                f.clock.advanceBy(GuidanceRecoveryTiming.DIRECTION_GRACE_MS-1L);f.headingTick(heading,error)
                assertEquals(before,f.native.stops)
                f.clock.advanceBy(1);f.headingTick(heading,error)
                assertEquals(before+1,f.native.stops)
                f.native.done(action.id)
                assertNull(read<Any?>(f.navigator,"completedSpeechCue"))
            } else {
                assertEquals(before,f.native.stops)
                assertEquals(cue,read<RouteSpeechCueToken>(f.navigator,"pendingSpeechCue"))
                assertTrue(f.actor.isAppSpeechActive())
                f.native.done(action.id)
                assertEquals(cue,read<RouteSpeechCueToken>(f.navigator,"completedSpeechCue"))
            }
        } finally {f.output.closeActuator()}
    }
    @Test fun sameNominalClockBeforeNativeStartRetainsTheCurrentOwnerAndCompletes() {
        val f=Fixture(15.0,12.0,5.0)
        try {
            val action=f.startWithNativeIntro();f.connectCompassEvents()
            val cue:RouteSpeechCueToken=read(f.navigator,"pendingSpeechCue")
            val before=f.native.stops
            val offered:RouteFacingGuidanceResult=read(f.navigator,"offeredFacing")
            assertEquals(12,offered.clockHour);assertNull(offered.clockRange)
            assertFalse(offered.clockPrecisionAvailable)
            assertEquals(RouteClockReferenceKind.NOMINAL_TREND_CLOCK,offered.clockReferenceKind)
            f.clock.advanceBy(100);f.headingTick(2.0)
            val actual:RouteFacingObservation=read(read<Any>(f,"cold"),"compass")
            assertEquals(2.0,actual.degreesTrueNorth,0.0)
            assertEquals(12.0,requireNotNull(actual.reportedHeadingAccuracyDegrees),0.0)
            assertEquals(f.clock.now,actual.observedAtElapsedRealtimeMs)
            assertTrue(f.clock.now<=requireNotNull(cue.facingValidUntilElapsedRealtimeMs))
            assertEquals(before,f.native.stops)
            f.native.start(action.id)
            assertEquals(before,f.native.stops)
            assertEquals(cue,read<RouteSpeechCueToken>(f.navigator,"pendingSpeechCue"))
            assertTrue(f.actor.isAppSpeechActive())
            f.native.done(action.id)
            assertEquals(cue,read<RouteSpeechCueToken>(f.navigator,"completedSpeechCue"))
        } finally {f.output.closeActuator()}
    }
    @Test fun sixHundredMsQueuedStartUsesActualFreshSameDirectionWithoutRestamping() {
        val f=Fixture(15.0,12.0,5.0)
        try {
            val action=f.startWithNativeIntro();f.connectCompassEvents()
            val cue:RouteSpeechCueToken=read(f.navigator,"pendingSpeechCue")
            val before=f.native.stops
            repeat(2) {f.clock.advanceBy(300);f.headingTick(5.0)}
            assertTrue(f.clock.now>requireNotNull(cue.facingValidUntilElapsedRealtimeMs))
            assertEquals(before,f.native.stops)
            f.native.start(action.id)
            assertEquals(before,f.native.stops)
            assertFalse(f.navigator.isDirectionRecheckPending())
            assertEquals(cue,read<RouteSpeechCueToken>(f.navigator,"pendingSpeechCue"))
            assertEquals(requireNotNull(cue.facingObservedAtElapsedRealtimeMs)+500L,cue.facingValidUntilElapsedRealtimeMs)
            f.native.done(action.id)
            assertEquals(cue,read<RouteSpeechCueToken>(f.navigator,"completedSpeechCue"))
        } finally {f.output.closeActuator()}
    }
    @Test fun startedExactClockFinishesWhenNewMeasuredRangeStillSupportsOldHour() {
        val f=Fixture(15.0,5.0,0.0)
        try {
            val action=f.startWithNativeIntro();f.connectCompassEvents()
            val facing:RouteFacingGuidanceResult=read(f.navigator,"offeredFacing")
            assertEquals(12,facing.clockHour);assertNull(facing.clockRange)
            val cue:RouteSpeechCueToken=read(f.navigator,"pendingSpeechCue")
            f.clock.advanceBy(100);f.face();f.native.start(action.id)
            val before=f.native.stops
            repeat(10) {index ->
                f.clock.advanceBy(300);f.headingTick(0.0,12.0)
                if(index%3==2) f.publishWalkingGps((index+1)/3.0,15.0)
            }
            assertEquals(before,f.native.stops)
            assertEquals(cue,read<RouteSpeechCueToken>(f.navigator,"pendingSpeechCue"))
            f.native.done(action.id)
            assertEquals(cue,read<RouteSpeechCueToken>(f.navigator,"completedSpeechCue"))
        } finally {f.output.closeActuator()}
    }
    private fun normalWalk(accuracy:Double,moving:Boolean,headings:List<Double>,initialHeading:Double=0.0) {
        val f=Fixture(accuracy,12.0,initialHeading)
        try {
            val action=f.startWithNativeIntro();f.connectCompassEvents()
            val facing:RouteFacingGuidanceResult=read(f.navigator,"offeredFacing")
            assertEquals(12,facing.clockHour);assertNull(facing.clockRange)
            assertFalse(facing.clockPrecisionAvailable)
            assertEquals(RouteClockReferenceKind.NOMINAL_TREND_CLOCK,facing.clockReferenceKind)
            assertTrue(action.message,action.message.contains("약 ${facing.clockHour}시 방향"))
            assertTrue(action.message,Regex("[0-9]+(?:미터|m)").containsMatchIn(action.message))
            val cue:RouteSpeechCueToken=read(f.navigator,"pendingSpeechCue")
            f.clock.advanceBy(100);f.face();f.native.start(action.id)
            assertTrue(f.actor.isAppSpeechActive())
            val stops=f.native.stops
            headings.forEachIndexed { index, heading ->
                f.clock.advanceBy(300);f.headingTick(heading)
                if(moving && index%3==2) f.publishWalkingGps((index+1)/3.0,accuracy)
                println("accuracy=$accuracy tick=$index heading=$heading stops=${f.native.stops-stops} active=${f.actor.isAppSpeechActive()} pending=${read<Any?>(f.navigator,"pendingSpeechCue")}")
            }
            assertTrue(f.clock.now>requireNotNull(cue.facingValidUntilElapsedRealtimeMs))
            assertEquals("Fresh plausible walking inputs must not chop the started nominal CLOCK sentence",stops,f.native.stops)
            assertEquals(cue,read<RouteSpeechCueToken>(f.navigator,"pendingSpeechCue"))
            assertNull(read<Any?>(f.navigator,"completedSpeechCue"))
            f.native.done(action.id)
            assertEquals(cue,read<RouteSpeechCueToken>(f.navigator,"completedSpeechCue"))
        } finally {f.output.closeActuator()}
    }
    private class Fixture(private val gpsAccuracyM: Double = 5.0, private val rvReportedErrorDegrees: Double? = null, private val initialHeading:Double = 0.0) {
        private val cold = Class.forName(MainActivityColdFirstDirectionFlowIntegrationTest::class.java.name + "\$ColdFixture")
            .getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        init {
            invokeCold("acquire", arrayOf(Double::class.javaPrimitiveType!!), gpsAccuracyM)
            invokeCold("requestAndInstall", arrayOf(Boolean::class.javaPrimitiveType!!), false)
        }
        val base: MainActivityContinuousLocationStartBehaviorTest.Fixture = read(cold, "base")
        val main = base.main
        val guide: RouteGuidanceController = read(cold, "guide")
        val positions: PositioningSessionController = read(cold, "positions")
        val fix: TrustedLocation = read(cold, "fix")
        val navigator = read<NavigationRunState>(guide, "navigationRunState").navigator
        val intro: InstalledRouteStartSpeechCoordinator = read(guide, "installedRouteStartSpeechCoordinator")
        val output: SpeechOutputController = read(main, "speechOutputController")
        val trace = mutableListOf<String>()
        val clock = Clock(base)
        lateinit var native: NativeRuntime
        val actor get() = requireNotNull(output.currentActuatorOrNull())
        init {
            val runtime = MainActivityControllerTestFixture.controller(main, "officialEnvironmentRuntimeController")
            put(runtime, "nowMs", { base.now })
            put(runtime, "schedule", { delay: Long, action: () -> Unit -> clock.post(runtime, Runnable(action), delay) })
            val originalReader: SpeechOutputReader = read(output, "reader")
            put(output, "reader", object : SpeechOutputReader by originalReader {
                override fun screenReaderActive() = false // controlled SDK screen-reader observation
            })
            put(output, "scheduler", clock)
            put(output, "input", object : SpeechOutputInputEffects {
                override fun invalidateHomeWakeInput() = Unit
                override fun promptState(token: CommandResponseToken, effect: CommandPromptEffect) = Unit
                override fun cancelDeferredInput() = Unit
                override fun refreshHomeWakeListening() = Unit
                override fun cancelHomeWakeListening() = Unit
                override fun cancelCommandDialogs(token: CommandResponseToken) = Unit
            })
            put(output, "presentation", object : SpeechOutputPresentation {
                override fun showLastResult(message: String) = Unit
                override fun showVoiceStatus(message: String) = Unit
                override fun refreshVoiceControls() = Unit
                override fun navigationStatus(message: String) = Unit
                override fun commandTerminalDiagnostic(completed: Boolean) = Unit
                override fun accessibilityTargetOrNull(): SpeechAccessibilityTarget? = null
                override fun canScheduleInteraction() = true
                override fun postInteraction(task: Runnable, delayMs: Long) = clock.post(this, task, delayMs)
                override fun removeInteraction(task: Runnable) = clock.remove(this, task)
            })
            put(output, "storage", object : kr.co.hanium.dreamup.walksafe.voice.output.SpeechOutputStorageBinding {
                override fun invalidate() = Unit
                override fun refresh(actuator: AndroidFeedbackActuator) = Unit
            })
            put(output, "debugDiagnostic", { value: String -> trace += value })
            put(output, "readyEffects", object : SpeechOutputReadyEffects {
                override fun refreshReadyUi() = Unit
                override fun reevaluateCurrentRisk(actuator: AndroidFeedbackActuator, lifecycleGeneration: Int, nowMs: Long) = Unit
                override fun retryCurrentNavigation(nowMs: Long) = guide.retryCurrentNavigationSpeechAfterReady(nowMs)
                override fun offlineSpeechUnavailable(lifecycleGeneration: Int, actuator: AndroidFeedbackActuator) = error("Unexpected engine unavailability")
                override fun beforeCommandReplacement(previousGeneration: Long) = guide.failInterruptedRouteDecisionSpeech(previousGeneration)
            })
            put(output, "actuatorFactory", SpeechOutputActuatorFactory { unavailable, ready ->
                native = NativeRuntime(clock)
                // Use the real actor/gate/registry. Capability and native focus are admitted fixture leaves.
                AndroidFeedbackActuator(runtime = native, onOfflineKoreanSpeechUnavailable = unavailable,
                    onOfflineKoreanSpeechReady = ready)
            })
            val composition: NavigationSessionComposition = read(main, "navigationSessions")
            val bindings: GuidanceAssemblyBindings = read(composition, "guides")
            val productionSpeech = bindings.speech()
            put(guide, "speech", object : RouteGuidanceController.SpeechPort by productionSpeech {
                // Avoid OS accessibility queries only; registry/native activity and actual input admission remain.
                override fun busy() = productionSpeech.inputActive() || output.hasPendingCommandResponse() ||
                    output.currentActuatorOrNull()?.isAppSpeechActive() == true
                override fun outputBusy() = output.hasPendingCommandResponse() || output.currentActuatorOrNull()?.isAppSpeechActive() == true
            })
            put(guide, "callbacks", object : RouteCallbackScheduler {
                override fun post(task: Runnable) = clock.post(this, task, 0)
                override fun postDelayed(task: Runnable, delayMs: Long) = clock.post(this, task, delayMs)
                override fun remove(task: Runnable) = clock.remove(this, task)
            })
            val controlledSpeech=read<RouteGuidanceController.SpeechPort>(guide,"speech")
            val controlledCallbacks=read<RouteCallbackScheduler>(guide,"callbacks")
            for(name in listOf("decisionSpeech","recheckNotices")) {
                val child=read<Any>(guide,name)
                put(child,"speech",controlledSpeech);put(child,"callbacks",controlledCallbacks)
                if(name=="recheckNotices") put(child,"elapsedRealtimeMs",{clock.now})
            }
        }
        private lateinit var compassSession:RouteCompassSession
        fun connectCompassEvents() {
            compassSession=MainActivityControllerTestFixture.controller(main,"routeCompassSession") as RouteCompassSession
            // Existing registered, stable location-owner assumption; tracker registration is an SDK leaf.
            put(compassSession,"orientationLocationOwnerActive",true)
            put(compassSession,"elapsedRealtimeMs",{clock.now})
            main.bindCompassObservationOwner()
            check(read<Any?>(compassSession,"compassObservationOwner")!=null)
            val old:RouteGuidanceController.CompassReader=read(guide,"compassInput")
            put(guide,"compassInput",object:RouteGuidanceController.CompassReader by old {
                override val revision get()=compassSession.revision
                override val invalidationPending get()=compassSession.invalidationPending
            })
        }
        fun headingTick(degrees:Double,error:Double=12.0) {
            val observation=RouteFacingObservation((degrees % 360.0 + 360.0) % 360.0,error,clock.now,
                source=RouteFacingSource.ROTATION_VECTOR,sensorQuality=RouteFacingSensorQuality.REPORTED_ANGULAR_ERROR,
                reportedHeadingAccuracyDegrees=error)
            put(cold,"compass",observation)
            val heading=RouteCompassHeadingResult(observation,RouteCompassHeadingReason.VALID)
            val old:RouteFacingGuidanceResult?=read(navigator,"offeredFacing")
            val context:Any?=read(navigator,"latestGuidanceContext")
            if(old!=null&&context!=null) {
                val alignment:RouteAlignmentSelection=read(context,"alignment")
                val fresh=RouteFacingGuidance.evaluate(alignment.bearingDegrees,observation,clock.now,alignment.bearingUncertaintyDegrees)
                if(navigator.hasInvalidatedDirectionClaim(clock.now,observation))
                    println("INVALID: old=${old.clockRange?.hours}/${old.direction}; fresh=${fresh.clockRange?.hours}/${fresh.direction}; degrees=${observation.degreesTrueNorth}; error=${observation.reportedHeadingAccuracyDegrees}; age=${clock.now-observation.observedAtElapsedRealtimeMs}; spread=${alignment.bearingUncertaintyDegrees}")
            }
            val event=RouteCompassObservationEvent(1,RouteCompassStream.ROTATION_VECTOR,clock.now*1_000_000L,clock.now,heading,false)
            main.onRouteCompassObservation(event,trackerCurrent={true},sdkSourceCurrent={true},
                post={clock.post(compassSession,it,0)},
                invalidateClaim={guide.retryCurrentNavigationSpeechForObservation(clock.now,it.receivedAtMs,it.heading,false)},
                schedule={_,badAt->if(badAt==null) guide.retryCurrentNavigationSpeechAfterReady(clock.now)
                    else guide.retryCurrentNavigationSpeechForObservation(clock.now,badAt,heading)})
            clock.advanceBy(0)
            // Actual controller callback used by the normal 300-ms presentation schedule.
            guide.retryCurrentNavigationSpeechAfterReady(clock.now)
        }
        fun publishWalkingGps(northMetres:Double,accuracy:Double) {
            val coordinator:PositioningCoordinator=read(cold,"coordinator")
            val observed=clock.now-100L
            val latitude=37.5+northMetres/111_195.0
            val snapshot=coordinator.observeGnss(GnssPositionObservation(latitude,127.0,accuracy,observed,clock.now))
            assertNotEquals(GnssObservationDisposition.HARD_REJECTED,snapshot.gnssUpdate?.disposition)
            main.recordPositioningGpsObservation(snapshot,observed,accuracy,{clock.now},{})
            guide.applyPositionConfidenceDecision(snapshot,false)
            positions.applyPositioningMotionSnapshot(snapshot,base.binding as PositioningCollectionBinding)
            val fresh=requireNotNull(positions.currentRouteLocationOrNull())
            assertEquals(observed,fresh.elapsedRealtimeMs)
            put(positions,"latestRawRouteLocation",positions.rawAndroidRouteLocation(latitude,127.0,accuracy.toFloat(),observed))
            guide.updateRouteGuidance(fresh,requireNotNull(positions.rawRouteLocation))
        }
        fun publishFreshGps(measuredAt: Long) {
            val coordinator: PositioningCoordinator = read(cold, "coordinator")
            base.now = measuredAt + 100L
            val snapshot = coordinator.observeGnss(GnssPositionObservation(37.5, 127.0, 5.0, measuredAt, base.now))
            assertNotEquals(GnssObservationDisposition.HARD_REJECTED, snapshot.gnssUpdate?.disposition)
            base.main.recordPositioningGpsObservation(snapshot, measuredAt, 5.0, { base.now }, {})
            guide.applyPositionConfidenceDecision(snapshot, emitAnnouncements = false)
            positions.applyPositioningMotionSnapshot(snapshot, base.binding as PositioningCollectionBinding)
            val fresh = requireNotNull(positions.currentRouteLocationOrNull())
            assertEquals(measuredAt, fresh.elapsedRealtimeMs)
            // Same raw Android leaf as the established N5 fixture, with a distinct new observation.
            put(positions, "latestRawRouteLocation", positions.rawAndroidRouteLocation(37.5, 127.0, 5f, measuredAt))
            face()
            guide.updateRouteGuidance(fresh, requireNotNull(positions.rawRouteLocation))
        }
        fun face() {
            if (rvReportedErrorDegrees == null) {
                invokeCold("faceAt", arrayOf(Long::class.javaPrimitiveType!!), clock.now)
            } else {
                put(cold, "compass", RouteFacingObservation(initialHeading, rvReportedErrorDegrees, clock.now,
                    source = RouteFacingSource.ROTATION_VECTOR,
                    sensorQuality = RouteFacingSensorQuality.REPORTED_ANGULAR_ERROR,
                    reportedHeadingAccuracyDegrees = rvReportedErrorDegrees))
            }
        }
        fun startWithNativeIntro(): NativeRuntime.Spoken {
            invokeCold("installIntro", emptyArray())
            face()
            guide.updateRouteGuidance(fix, requireNotNull(positions.rawRouteLocation))
            assertTrue(native.spoken.isEmpty())
            assertTrue(output.hasPendingCommandResponse())
            native.ready()
            assertEquals(1, native.spoken.size)
            val introSpeech = native.spoken.single()
            assertTrue(introSpeech.message, introSpeech.message.contains("길안내를 시작"))
            clock.advanceBy(100); face(); native.start(introSpeech.id)
            assertNull(read<Any?>(navigator, "completedSpeechCue"))
            assertEquals(1, native.spoken.size)
            clock.advanceBy(100); face(); native.done(introSpeech.id)
            assertNull(intro.pending)
            assertEquals(2, native.spoken.size)
            assertNull(read<Any?>(navigator, "completedSpeechCue"))
            trace += "INTRO_NATIVE_START_DONE@${clock.now}"
            return native.spoken.last()
        }
        private fun invokeCold(name: String, types: Array<Class<*>>, vararg values: Any?) =
            cold.javaClass.getDeclaredMethod(name, *types).apply { isAccessible = true }.invoke(cold, *values)
    }

    private class Clock(private val base: MainActivityContinuousLocationStartBehaviorTest.Fixture) : SpeechOutputScheduler {
        private data class Task(val owner: Any, val due: Long, val order: Long, val task: Runnable)
        private val tasks = mutableListOf<Task>()
        private var sequence = 0L
        val now get() = base.now
        override fun elapsedRealtimeMs() = now
        override fun isMainThread() = true
        override fun runOnMain(action: () -> Unit) = action()
        override fun postReadiness(task: Runnable, delayMs: Long) = post(this, task, delayMs)
        override fun removeReadiness(task: Runnable) = remove(this, task)
        override fun postRetired(action: () -> Unit) = post(this, Runnable(action), 0)
        fun post(owner: Any, task: Runnable, delayMs: Long) { tasks += Task(owner, now + delayMs, sequence++, task) }
        fun remove(owner: Any, task: Runnable) { tasks.removeAll { it.owner === owner && it.task === task } }
        fun clear(owner: Any) { tasks.removeAll { it.owner === owner } }
        fun contains(task: Runnable?) = task != null && tasks.any { it.task === task }
        fun advanceBy(delta: Long) {
            val target = now + delta
            var steps = 0
            while (true) {
                check(++steps < 1_000) { "Unexpected callback loop" }
                val next = tasks.filter { it.due <= target }.minWithOrNull(compareBy<Task> { it.due }.thenBy { it.order }) ?: break
                tasks.remove(next); base.now = maxOf(now, next.due); next.task.run()
            }
            base.now = target
        }
    }
    private class NativeRuntime(private val clock: Clock) : FeedbackSpeechRuntime, FeedbackSpeechScheduler {
        data class Spoken(val message: String, val id: String)
        val spoken = mutableListOf<Spoken>()
        var stops = 0
        var focusAllowed = true
        private var initialized: ((Int) -> Unit)? = null
        private var listener: FeedbackSpeechRuntime.Listener? = null
        override val scheduler: FeedbackSpeechScheduler get() = this
        override val engineInitialized get() = initialized != null
        override fun elapsedRealtime() = clock.now
        override fun initialize(onInitialized: (Int) -> Unit) { initialized = onInitialized }
        override fun setLanguage(language: Locale) = TextToSpeech.LANG_AVAILABLE
        override fun selectOfflineKoreanVoice() = kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection.SELECTED
        override fun configureAudioAttributes() = Unit
        override fun setListener(listener: FeedbackSpeechRuntime.Listener): Int { this.listener = listener; return TextToSpeech.SUCCESS }
        override fun speak(message: String, queueMode: Int, utteranceId: String): Int { spoken += Spoken(message, utteranceId); return TextToSpeech.SUCCESS }
        override fun stop() { stops++ }
        override fun shutdown() = Unit
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener) = focusAllowed
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) = Unit
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = error("No tone expected")
        override fun post(task: Runnable): Boolean = postDelayed(task, 0)
        override fun postDelayed(task: Runnable, delayMs: Long): Boolean { clock.post(this, task, delayMs); return true }
        override fun removeCallbacks(task: Runnable) = clock.remove(this, task)
        override fun removeCallbacksAndMessages(token: Any?) = clock.clear(this)
        fun ready() { requireNotNull(initialized)(TextToSpeech.SUCCESS); clock.advanceBy(0) }
        fun start(id: String) { requireNotNull(listener).onStart(id); clock.advanceBy(0) }
        fun stopCallback(id: String) { requireNotNull(listener).onStop(id, true); clock.advanceBy(0) }
        fun error(id: String, code: Int) { requireNotNull(listener).onError(id, code); clock.advanceBy(0) }
        fun done(id: String) { requireNotNull(listener).onDone(id); clock.advanceBy(0) }
    }
    companion object {
        @Suppress("UNCHECKED_CAST") private fun <T> read(owner: Any, name: String): T = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner) as T
        private fun put(owner: Any, name: String, value: Any?) { owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(owner, value) }
    }
}
