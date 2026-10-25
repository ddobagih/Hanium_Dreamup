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

/** Main initialJoinReference and real GPS coordinator; provider route geometry and native runtime are controlled. */
class ClosestPointSpeechReviewTest {
    @After fun retireGateway() { GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation) }
    @Test fun smallGpsWithinOneDistanceBandDoesNotCancelClosestPointSpeech() = gpsChange(60.4,12.0)
    @Test fun smallGpsAcrossThirtyMetreBandDoesNotCancelClosestPointSpeech() = gpsChange(30.4,3.0)
    @Test fun repeatedOneMetreGpsNoiseAtSixtyMetresDoesNotRestartEveryRangeOpening() {
        val f=Fixture(15.0,12.0,90.0,60.4)
        try {
            var action=f.startClosestPoint();f.connectCompassEvents();val before=f.native.stops
            assertNominalClock(read(f.navigator,"offeredFacing"))
            val first:RouteSpeechCueToken=read(f.navigator,"pendingSpeechCue")
            val started=mutableSetOf<String>()
            for(east in listOf(-1.0,1.0,-1.0,1.0,-1.0,1.0)) {
                f.clock.advanceBy(100);f.face();if(started.add(action.id)) f.native.start(action.id)
                f.clock.advanceBy(300);f.face();f.publishGpsOffset(east)
                val context:Any=read(f.navigator,"latestGuidanceContext")
                assertTrue(read<Boolean>(context,"initialJoinReference"))
                assertNominalClock(read(f.navigator,"offeredFacing"))
                action=f.native.spoken.last()
            }
            println("REPEATED raw15/RV12 stops=${f.native.stops-before} requests=${f.native.spoken.size}")
            assertEquals("Small GPS noise must allow the initial closest-point sentence to finish",before,f.native.stops)
            f.native.done(action.id)
            assertEquals(first,read<RouteSpeechCueToken>(f.navigator,"completedSpeechCue"))
        } finally {f.output.closeActuator()}
    }
    @Test fun freshNormalDirectionRevalidatesClosestPointStartWithoutRewritingItsOriginalLease() {
        val f=Fixture(15.0,12.0,90.0,60.4)
        try {
            val action=f.startClosestPoint();f.connectCompassEvents();val before=f.native.stops
            val old:RouteSpeechCueToken=read(f.navigator,"pendingSpeechCue")
            repeat(2){f.clock.advanceBy(300);f.headingTick(90.0,12.0)}
            assertTrue(f.clock.now>requireNotNull(old.facingValidUntilElapsedRealtimeMs))
            assertEquals(before,f.native.stops);f.native.start(action.id);assertEquals(before,f.native.stops)
            assertEquals(old,read<RouteSpeechCueToken>(f.navigator,"pendingSpeechCue"))
            assertEquals("NORMAL",f.navigator.currentDirectionGraceSnapshot()?.mode?.name)
            f.native.done(action.id);assertEquals(old,read<RouteSpeechCueToken?>(f.navigator,"completedSpeechCue"))
        } finally {f.output.closeActuator()}
    }
    @Test fun routeReplacementRejectsOldClosestPointAtNativeStart() {
        val f=Fixture(15.0,12.0,90.0,60.4)
        try {
            val action=f.startClosestPoint();f.connectCompassEvents();val before=f.native.stops
            val old:RouteSpeechCueToken=read(f.navigator,"pendingSpeechCue")
            val acquisition:RouteAcquisitionController=read(read<Any>(f,"cold"),"acquisition")
            val route:WalkingRoute=read(f.navigator,"route")
            acquisition.installRequestedRoute(route,route.polyline.last(),f.fix,null,"review_replacement",true)
            f.clock.advanceBy(100);f.face();f.native.start(action.id)
            assertEquals(before+1,f.native.stops)
            f.native.done(action.id);assertNotEquals(old,read<RouteSpeechCueToken?>(f.navigator,"completedSpeechCue"))
        } finally {f.output.closeActuator()}
    }
    @Test fun supportedDistanceBandChangeAllowsQueuedClosestPointStartAndDone() {
        val f=Fixture(15.0,12.0,90.0,60.4)
        try {
            val action=f.startClosestPoint();f.connectCompassEvents();val before=f.native.stops
            val old:RouteSpeechCueToken=read(f.navigator,"pendingSpeechCue")
            f.clock.advanceBy(100);f.face();f.publishGpsOffset(-1.0)
            assertTrue(f.clock.now<=requireNotNull(old.facingValidUntilElapsedRealtimeMs))
            assertEquals(before,f.native.stops)
            assertEquals(old,read<RouteSpeechCueToken>(f.navigator,"pendingSpeechCue"))
            assertTrue(requireNotNull(f.navigator.currentRouteApproachTarget(f.clock.now)).distanceM>60.5)
            f.native.start(action.id);assertEquals(before,f.native.stops)
            assertEquals(1,f.native.spoken.size)
            assertTrue(f.actor.isAppSpeechActive())
            f.native.done(action.id);assertEquals(old,read<RouteSpeechCueToken>(f.navigator,"completedSpeechCue"))
        } finally {f.output.closeActuator()}
    }
    @Test fun unsupportedLargeInitialDistanceChangeRejectsOldStartInsideItsOriginalLease() {
        val original = TrustedLocation(37.0002,127.0,1f,1_000L)
        val navigator = RouteNavigator(RouteNavigatorConfig(directionRecheckGraceMs=0L)).apply {
            setRoute(WalkingRoute("STAIR_AVOID",WalkingRouteSummary(200,200),
                listOf(RoutePoint(37.0,127.0),RoutePoint(37.0,127.002)),
                listOf(WalkingRouteGuidePoint(0,RoutePoint(37.0,127.002),"도착",200,0,90f))),
                origin=original,waitForInitialRouteJoin=true)
        }
        fun reference(point:TrustedLocation,now:Long) = navigator.update(point,now,false,
            filteredPosition=FilteredRoutePosition(RoutePoint(point.latitude,point.longitude),1.0,point.elapsedRealtimeMs),
            facingObservation=RouteFacingObservation(90.0,1.0,now))
        val first=reference(original,1_100L)
        assertTrue(requireNotNull(first.instruction).contains("약 22미터"))
        assertTrue(navigator.reserveInstruction(first))
        assertTrue(navigator.isInstructionCurrent(first,1_100L,RouteFacingObservation(90.0,1.0,1_100L)))
        val lease=requireNotNull(first.speechCueToken?.facingValidUntilElapsedRealtimeMs)
        val next=original.copy(latitude=37.0006,elapsedRealtimeMs=1_200L)
        val changed=reference(next,1_300L)
        assertTrue("The large-distance rejection must occur inside the old facing lease",1_300L<=lease)
        assertTrue(changed.cancelStaleNavigationSpeech)
        assertTrue(requireNotNull(changed.instruction).contains("약 67미터"))
        assertFalse(navigator.isInstructionCurrent(first,1_300L,RouteFacingObservation(90.0,1.0,1_300L)))
        assertTrue(navigator.reserveInstruction(changed))
        assertTrue(navigator.isWaitingForInitialRouteJoin())
        assertNull(navigator.currentAcceptedRouteMatchFor(next.elapsedRealtimeMs))
        assertEquals(1_000L,original.elapsedRealtimeMs)
        assertEquals(1_200L,next.elapsedRealtimeMs)
    }
    @Test fun leavingTheHundredMetreApproachBoundStillStopsPlaying() {
        val f=Fixture(15.0,12.0,90.0,99.8)
        try {
            val action=f.startClosestPoint();f.connectCompassEvents();val before=f.native.stops
            val old:RouteSpeechCueToken=read(f.navigator,"pendingSpeechCue")
            f.clock.advanceBy(100);f.face();f.native.start(action.id)
            f.clock.advanceBy(300);f.face();f.publishGpsOffset(-1.0)
            assertEquals(before+1,f.native.stops)
            assertNull(f.navigator.currentRouteApproachTarget(f.clock.now))
            assertTrue(f.navigator.isWaitingForInitialRouteJoin())
            f.native.done(action.id);assertNotEquals(old,read<RouteSpeechCueToken?>(f.navigator,"completedSpeechCue"))
        } finally {f.output.closeActuator()}
    }
    @Test fun closestUnavailableHeadingKeepsPlayingUntilTheFixedGraceDeadline() {
        val f=Fixture(15.0,12.0,90.0,60.4)
        try {
            val action=f.startClosestPoint();f.connectCompassEvents()
            val cue:RouteSpeechCueToken=read(f.navigator,"pendingSpeechCue")
            f.clock.advanceBy(100);f.face();f.native.start(action.id)
            val before=f.native.stops
            f.clock.advanceBy(300);f.publishGpsOffset(0.0);f.unavailableHeadingTick()
            val deadline=requireNotNull(f.navigator.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
            assertEquals(before,f.native.stops)
            assertEquals(cue,read<RouteSpeechCueToken>(f.navigator,"pendingSpeechCue"))
            // Isolate direction loss while the real positioning owner receives fresh, stationary fixes.
            // A time jump across the full grace also expires GPS and removes this direction context.
            while (f.clock.now < deadline - 1) {
                f.clock.advanceBy(minOf(300L, deadline - f.clock.now - 1))
                f.publishGpsOffset(0.0);f.unavailableHeadingTick()
                assertEquals(before,f.native.stops)
                assertEquals(deadline,f.navigator.currentDirectionGraceSnapshot()?.deadlineElapsedRealtimeMs)
                assertEquals(cue,read<RouteSpeechCueToken>(f.navigator,"pendingSpeechCue"))
            }
            assertEquals(deadline - 1,f.clock.now)
            assertEquals(before,f.native.stops)
            f.clock.advanceBy(1);f.unavailableHeadingTick()
            assertEquals(deadline,f.clock.now)
            assertTrue(f.native.stops>before)
            f.native.done(action.id)
            assertNotEquals(cue,read<RouteSpeechCueToken?>(f.navigator,"completedSpeechCue"))
        } finally {f.output.closeActuator()}
    }
    @Test fun reportedThirtyDegreeHeadingRecoversAsAValidNominalFourOClockReference() {
        val f=Fixture(15.0,12.0,90.0,60.4)
        try {
            val action=f.startClosestPoint();f.connectCompassEvents()
            f.clock.advanceBy(100);f.face();f.native.start(action.id)
            f.clock.advanceBy(300);f.headingTick(340.0,30.0)
            repeat(4) {f.clock.advanceBy(300);f.headingTick(340.0,30.0)}
            val actual:RouteFacingObservation=read(read<Any>(f,"cold"),"compass")
            assertEquals(340.0,actual.degreesTrueNorth,0.0)
            assertEquals(30.0,requireNotNull(actual.reportedHeadingAccuracyDegrees),0.0)
            assertEquals(f.clock.now,actual.observedAtElapsedRealtimeMs)
            val snapshot=requireNotNull(f.navigator.currentDirectionGraceSnapshot())
            assertEquals("NORMAL",snapshot.mode.name);assertNull(snapshot.deadlineElapsedRealtimeMs)
            assertEquals(actual.observedAtElapsedRealtimeMs,snapshot.retainedObservedAtElapsedRealtimeMs)
            assertEquals(actual.reportedHeadingAccuracyDegrees,snapshot.retainedReportedHeadingAccuracyDegrees)
            val normal:RouteFacingGuidanceResult=read(read<Any>(f.navigator,"lastGoodDirection"),"facing")
            assertNominalClock(normal,4)
            assertEquals(RouteFacingDirection.RIGHT,normal.direction)
        } finally {f.output.closeActuator()}
    }
    @Test fun closestReverseStillStopsPlaying() = unsafeClosestHeading(270.0,12.0)
    private fun unsafeClosestHeading(heading:Double,error:Double) {
        val f=Fixture(15.0,12.0,90.0,60.4)
        try {
            val action=f.startClosestPoint();f.connectCompassEvents();val before=f.native.stops
            val old:RouteSpeechCueToken=read(f.navigator,"pendingSpeechCue")
            f.clock.advanceBy(100);f.face();f.native.start(action.id)
            f.clock.advanceBy(300);f.headingTick(heading,error)
            assertEquals(before+1,f.native.stops)
            f.native.done(action.id);assertNotEquals(old,read<RouteSpeechCueToken?>(f.navigator,"completedSpeechCue"))
        } finally {f.output.closeActuator()}
    }
    private fun gpsChange(offset:Double,error:Double) {
        val f=Fixture(15.0,error,90.0,offset)
        try {
            val action=f.startClosestPoint();f.connectCompassEvents()
            val old:RouteSpeechCueToken=read(f.navigator,"pendingSpeechCue")
            f.clock.advanceBy(100);f.face();f.native.start(action.id)
            val before=f.native.stops
            assertTrue(f.actor.isAppSpeechActive())
            f.clock.advanceBy(300);f.face();f.publishGpsOffset(-1.0)
            assertTrue(f.navigator.isWaitingForInitialRouteJoin())
            val context:Any=read(f.navigator,"latestGuidanceContext")
            assertTrue(read<Boolean>(context,"initialJoinReference"))
            val latest:RouteFacingGuidanceResult=read(f.navigator,"offeredFacing")
            if(error==3.0) {assertNominalClock(latest);assertEquals(RouteFacingDirection.FRONT,latest.direction)}
            assertEquals(old.routeRevision,read<RouteSpeechCueToken>(f.navigator,"pendingSpeechCue").routeRevision)
            assertEquals(old.routeId,read<RouteSpeechCueToken>(f.navigator,"pendingSpeechCue").routeId)
            if(old.requiresFacingEvidence) assertTrue(f.clock.now<=requireNotNull(old.facingValidUntilElapsedRealtimeMs))
            println("AFTER offset=$offset old=$old now=${read<Any?>(f.navigator,"pendingSpeechCue")} stops=${f.native.stops-before} texts=${f.native.spoken.map{it.message}}")
            assertEquals("One metre ordinary GPS update must not repeatedly restart the closest-point sentence",before,f.native.stops)
            f.native.done(action.id)
            assertEquals(old,read<RouteSpeechCueToken>(f.navigator,"completedSpeechCue"))
        } finally {f.output.closeActuator()}
    }
    @Test fun healthyAdjacentFacingKeepsClosestPointRange() {
        val f=Fixture(15.0,3.0,90.0,30.4)
        try {
            val action=f.startClosestPoint();f.connectCompassEvents();val before=f.native.stops
            assertNominalClock(read(f.navigator,"offeredFacing"))
            f.clock.advanceBy(100);f.face();f.native.start(action.id)
            f.clock.advanceBy(300);f.headingTick(92.0,3.0)
            assertEquals(before,f.native.stops)
            f.native.done(action.id);assertNotNull(read<Any?>(f.navigator,"completedSpeechCue"))
        } finally {f.output.closeActuator()}
    }
    private fun assertNominalClock(facing:RouteFacingGuidanceResult,hour:Int=12) {
        assertEquals(hour,facing.clockHour);assertNull(facing.clockRange)
        assertFalse(facing.clockPrecisionAvailable)
        assertEquals(RouteClockReferenceKind.NOMINAL_TREND_CLOCK,facing.clockReferenceKind)
    }
    private class Fixture(private val gpsAccuracyM: Double = 5.0, private val rvReportedErrorDegrees: Double? = null, private val initialHeading:Double = 90.0, private val approachOffsetM:Double = 30.4) {
        private val cold = Class.forName(ClosestPointColdFixtureSupport::class.java.name + "\$ColdFixture")
            .getDeclaredConstructor().apply { isAccessible = true }.newInstance()
        init {
            for(type in listOf(MainActivity::class.java, RouteNavigator::class.java, RouteGuidanceController::class.java,
                RouteCompassSession::class.java, SpeechOutputController::class.java, AndroidFeedbackActuator::class.java)) {
                val required = "/runtime_app_classes_jar/debug/bundleDebugClassesToRuntimeJar/classes.jar"
                assertTrue("Current production class required: ${type.name} from ${type.protectionDomain.codeSource.location.path}", type.protectionDomain.codeSource.location.path.endsWith(required))
            }
            assertTrue(ClosestPointColdFixtureSupport::class.java.protectionDomain.codeSource.location.path.endsWith("/debugUnitTest/compileDebugUnitTestKotlin/classes/"))
            invokeCold("acquire", arrayOf(Double::class.javaPrimitiveType!!), gpsAccuracyM)
            put(cold,"approachOffsetM",approachOffsetM)
            invokeCold("requestAndInstall", arrayOf(Boolean::class.javaPrimitiveType!!), true)
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
            // These real child owners capture their ports at construction, before this fixture
            // installs the controlled native runtime and scheduler on their parent.
            for (name in listOf("decisionSpeech", "recheckNotices")) {
                val child: Any = read(guide, name)
                put(child, "speech", read<RouteGuidanceController.SpeechPort>(guide, "speech"))
                put(child, "callbacks", read<RouteCallbackScheduler>(guide, "callbacks"))
            }
            put(read<Any>(guide, "recheckNotices"), "elapsedRealtimeMs", { clock.now })
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
        fun unavailableHeadingTick() {
            put(cold,"compass",null)
            val heading=RouteCompassHeadingResult(null,RouteCompassHeadingReason.QUALITY_LOW)
            val event=RouteCompassObservationEvent(1,RouteCompassStream.ROTATION_VECTOR,clock.now*1_000_000L,clock.now,heading,false)
            main.onRouteCompassObservation(event,trackerCurrent={true},sdkSourceCurrent={true},
                post={clock.post(compassSession,it,0)},
                invalidateClaim={guide.retryCurrentNavigationSpeechForObservation(clock.now,it.receivedAtMs,it.heading,false)},
                schedule={_,badAt->if(badAt==null) guide.retryCurrentNavigationSpeechAfterReady(clock.now)
                    else guide.retryCurrentNavigationSpeechForObservation(clock.now,badAt,heading)})
            clock.advanceBy(0)
            guide.retryCurrentNavigationSpeechAfterReady(clock.now)
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
        fun startClosestPoint(): NativeRuntime.Spoken {
            face()
            guide.updateRouteGuidance(fix, requireNotNull(positions.rawRouteLocation))
            var ready=false
            repeat(40) {
                if(this::native.isInitialized && !ready) {ready=true;native.ready()}
                if(!this::native.isInitialized || native.spoken.isEmpty()) {
                    clock.advanceBy(100);face();guide.retryCurrentNavigationSpeechAfterReady(clock.now)
                }
            }
            assertTrue("Closest-point engine must be requested",this::native.isInitialized)
            assertTrue("Closest-point utterance must be produced",native.spoken.isNotEmpty())
            assertTrue(navigator.isWaitingForInitialRouteJoin())
            val context:Any=read(navigator,"latestGuidanceContext")
            assertTrue(read<Boolean>(context,"initialJoinReference"))
            assertTrue(read<Boolean>(navigator,"offeredInitialJoinReference"))
            assertNull(navigator.currentAcceptedRouteMatchFor(fix.elapsedRealtimeMs))
            val speech=native.spoken.last()
            assertTrue(speech.message,speech.message.contains("가장 가까운 경로 지점"))
            println("INITIAL@${clock.now}: target=${navigator.currentRouteApproachTarget(clock.now)} text=${speech.message} cue=${read<Any?>(navigator,"pendingSpeechCue")}")
            return speech
        }
        fun publishGpsOffset(eastMetres:Double) {
            val coordinator:PositioningCoordinator=read(cold,"coordinator")
            val observed=clock.now-100L
            val longitude=127.0+eastMetres/(111_195.0*kotlin.math.cos(Math.toRadians(37.5)))
            val snapshot=coordinator.observeGnss(GnssPositionObservation(37.5,longitude,gpsAccuracyM,observed,clock.now))
            assertNotEquals(GnssObservationDisposition.HARD_REJECTED,snapshot.gnssUpdate?.disposition)
            main.recordPositioningGpsObservation(snapshot,observed,gpsAccuracyM,{clock.now},{})
            guide.applyPositionConfidenceDecision(snapshot,false)
            positions.applyPositioningMotionSnapshot(snapshot,base.binding as PositioningCollectionBinding)
            val fresh=requireNotNull(positions.currentRouteLocationOrNull())
            assertEquals(observed,fresh.elapsedRealtimeMs)
            put(positions,"latestRawRouteLocation",positions.rawAndroidRouteLocation(37.5,longitude,gpsAccuracyM.toFloat(),observed))
            guide.updateRouteGuidance(fresh,requireNotNull(positions.rawRouteLocation))
            println("GPS@${clock.now}: rawAccuracy=$gpsAccuracyM position=$fresh target=${navigator.currentRouteApproachTarget(clock.now)} cue=${read<Any?>(navigator,"pendingSpeechCue")} stop=${native.stops}")
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
