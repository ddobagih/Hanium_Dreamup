package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorUserDecision
import kr.co.hanium.dreamup.walksafe.navigation.RouteCompassHeadingReason
import kr.co.hanium.dreamup.walksafe.navigation.RouteDirectionGraceMode
import kr.co.hanium.dreamup.walksafe.navigation.session.GuidanceRecoveryTiming
import kr.co.hanium.dreamup.walksafe.navigation.session.RouteGuidanceController
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchQuality
import kr.co.hanium.dreamup.walksafe.navigation.positioning.RouteMatchReason
import kr.co.hanium.dreamup.walksafe.device.RuntimeMeasuredQualityGate
import kr.co.hanium.dreamup.walksafe.navigation.start.GuidanceStartCalibration
import kr.co.hanium.dreamup.walksafe.network.GatewayCapacityProcessState
import kr.co.hanium.dreamup.walksafe.session.WalkSessionAction
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Exact same production owners from HOME READY through route installation, walking and END.
 * SDK permissions/registration, account receipts, camera/model observations, HTTP and native
 * speech callbacks are controlled leaves. Android Views/RAW sensors/acoustic output are separate.
 */
class GuidanceFullJourneyReplayTest {
    @After fun resetCapacity()=GatewayCapacityProcessState.resetForTests()

    @Test fun readyHomeToFirstClockMetresTurnAutomaticArrivalAndEndKeepTheSameOwners() {
        GuidanceFullJourneyReplayHarness(caseId="healthy_home_to_end").use { f ->
            admitAndInstall(f)
            assertSame(f.gateway.lifecycle.snapshot().epoch, f.walk.epoch)
            assertEquals(1,f.providerRequests.size)
            assertSame(f.destination.point,f.providerRequests.single().destination)
            f.finishPendingStatusSpeech()
            f.advance(300)
            f.heading()
            f.gps()
            f.advance(300)
            assertTrue("Installed route must produce an actual single clock/metres native utterance: ${f.runtime.spoken}",f.routeSpeech().isNotEmpty())
            val first=f.routeSpeech().first()
            assertFalse("The actual first route cue must be preserved until its native callbacks",first.done)
            assertFalse(first.started)
            assertFalse(first.cancelled)
            f.runtime.start(first.id)
            f.advance(0);f.drainUi()
            f.assertNativeRouteStartAccepted(first)
            f.advance(100)
            f.runtime.done(first.id)
            f.advance(0);f.drainUi()
            f.assertNativeRouteCompletionAcknowledged(first)
            assertTrue(first.started&&first.done)
            f.assertSameHomeOwner()
            assertEquals("First route instruction must use the actual northbound segment",0,f.recordRouteProgress().segment)

            fun step(east:Double,north:Double,facing:Double) {
                f.advance(3_000)
                f.observeCameraFrame()
                f.heading(facing)
                f.gps(east,north)
                f.finishPendingSpeech()
                f.advance(300)
                f.recordRouteProgress()
                f.assertSameHomeOwner()
            }
            for(north in listOf(10.0,20.0,30.0,40.0,50.0,60.0))step(0.0,north,0.0)
            for(east in listOf(10.0,20.0,30.0,40.0))step(east,60.0,90.0)
            repeat(3){
                if(!f.arrivalStarted)step(40.0,60.0,90.0)
            }
            assertTrue("Actual accepted route progress must move from northbound segment 0 to eastbound segment 1",
                f.routeProgressEvidence.any{it.segment==0}&&f.routeProgressEvidence.any{it.segment==1})
            val acceptedTurn=f.routeProgressEvidence.firstOrNull{it.acceptedTurnRawAtMs!=null}
            assertNotNull("The protected L turn requires accepted raw and filtered movement evidence",acceptedTurn)
            assertNotNull(requireNotNull(acceptedTurn).acceptedTurnFilteredAtMs)
            assertEquals(acceptedTurn.acceptedTurnRawAtMs,acceptedTurn.acceptedTurnFilteredAtMs)
            assertTrue("Accepted turn evidence must belong to the actual 60m guide",
                requireNotNull(acceptedTurn.acceptedTurnGuideProgressM) in 59.0..61.0)
            assertTrue("The actual turn-guide route action must receive native START and DONE",
                f.routeSpeech().any{it.guideIndex==1&&it.started&&it.done&&!it.cancelled&&it.dispatchCue in f.acknowledgedRouteCues})
            completeAutomaticArrivalAndEnd(f)
        }
    }

    @Test fun timerWithoutCurrentCameraEvidenceCannotConfirmOrAcquireRoute() {
        GuidanceFullJourneyReplayHarness(caseId="missing_camera_start").use { f ->
            f.gps();f.heading();f.selectDestination();f.button()
            f.runtime.start(f.promptId());f.runtime.done(f.promptId())
            f.advance(3_000);f.drainUi()
            assertEquals(GuidanceStartCalibration.Phase.READY,f.calibration.snapshot?.phase)
            assertFalse(f.capture(WalkSessionAction.START_WALK).isReadyAt(f.now))
            assertNull(f.confirm.confirmedDecision())
            assertTrue(f.gateway.background.isEmpty())
            assertTrue(f.providerRequests.isEmpty())
            assertEquals(WalkSessionState.READY,f.walk.state)
            f.assertSameHomeOwner()
        }
    }

    @Test fun locationAccessFailureKeepsHomeDestinationAndCannotActivateUntilCorrected() {
        GuidanceFullJourneyReplayHarness(caseId="location_access_start_recovery").use { f ->
            f.gps();f.heading();f.selectDestination()
            f.permission=false
            f.button()
            assertTrue(f.prompts.isEmpty())
            assertEquals("LOCATION_ACCESS_UNAVAILABLE",f.stage)
            assertNull(f.confirm.confirmedDecision())
            assertTrue(f.providerRequests.isEmpty())
            assertSame(f.destination,f.workflow.pendingUiDestination)
            f.finishPendingSpeech()
            f.permission=true
            f.advance(2_000)
            assertEquals(1,f.prompts.size)
            assertNull(f.calibration.snapshot?.voiceDoneAtMs)
            f.runtime.start(f.promptId());f.runtime.done(f.promptId())
            observeIndependentReadiness(f)
            f.advance(3_000);f.drainUi()
            assertEquals(1,f.gateway.background.size)
            f.prepareArchive()
            f.gateway.runBackground();f.drainUi()
            f.acknowledgeWalkOwnership()
            assertEquals(WalkSessionState.ACTIVE,f.walk.state)
            f.runProvider()
            assertFalse(f.graph.run.navigator.hasRoute())
            f.runIo();f.drainUi()
            assertTrue(f.graph.run.navigator.hasRoute())
            f.assertSameHomeOwner()
        }
    }

    @Test fun nativePromptDoneAndThreeSecondsCannotSubstituteForPendingSynthesisProbeCompletion() {
        GuidanceFullJourneyReplayHarness(initialEngineReady=true,caseId="pending_synthesis_after_calibration",
            initialSynthesisReady=null).use { f ->
            assertTrue(f.engineIsReady)
            assertNull("Native actuator initialization cannot prove synthesis capability",f.synthesisProbeAvailable)
            f.gps();f.heading();f.selectDestination();f.button()
            assertEquals(1,f.synthesisProbeStarts)
            assertEquals(1,f.synthesisProbeReplacements)
            assertEquals(1,f.synthesisProbeCloses)
            val prompt=f.promptId()
            f.runtime.start(prompt);f.runtime.done(prompt)
            val done=f.now
            // Deliver a new physical camera observation after the request, preserving its own timestamp.
            f.advance(1)
            observeIndependentReadiness(f)
            f.advance(2_999);f.drainUi()
            assertEquals(GuidanceStartCalibration.Phase.READY,f.calibration.snapshot?.phase)
            assertEquals(done+3_000,f.calibration.snapshot?.readyAtMs)
            assertNull(f.synthesisProbeAvailable)
            assertTrue(f.decision.pendingRequirements.contains(kr.co.hanium.dreamup.walksafe.device.WalkSafeStartupRequirement.OFFLINE_KOREAN_TTS))
            assertFalse(f.capture(WalkSessionAction.START_WALK).isReadyAt(f.now))
            assertNull(f.confirm.confirmedDecision())
            assertTrue(f.gateway.background.isEmpty())
            assertTrue(f.providerRequests.isEmpty())
            assertSame(f.destination,f.workflow.pendingUiDestination)
            f.assertSameHomeOwner()
            f.completeSynthesisProbe()
            f.drainUi()
            assertEquals(true,f.synthesisProbeAvailable)
            assertEquals("Readiness refresh must retain the actual in-flight probe",1,f.synthesisProbeStarts)
            assertEquals(1,f.prompts.size)
            assertTrue(f.confirm.isStartupCapabilityConfirmed())
            assertEquals("Actual probe completion must automatically resume the retained request",1,f.gateway.background.size)
            f.prepareArchive()
            f.gateway.runBackground();f.drainUi()
            f.acknowledgeWalkOwnership()
            assertEquals(WalkSessionState.ACTIVE,f.walk.state)
            f.runProvider()
            assertFalse(f.graph.run.navigator.hasRoute())
            f.runIo();f.drainUi()
            assertTrue(f.graph.run.navigator.hasRoute())
            f.finishPendingStatusSpeech()
            f.advance(300);f.heading();f.gps();f.advance(300)
            assertTrue("Probe recovery must reach actual first clock/metres speech",f.routeSpeech().isNotEmpty())
            val first=f.routeSpeech().first()
            assertFalse(first.started);assertFalse(first.done);assertFalse(first.cancelled)
            f.runtime.start(first.id);f.advance(0);f.drainUi()
            f.assertNativeRouteStartAccepted(first)
            f.runtime.done(first.id);f.advance(0);f.drainUi()
            f.assertNativeRouteCompletionAcknowledged(first)
            assertTrue(first.started&&first.done)
            f.assertSameHomeOwner()
        }
    }

    @Test fun homeAccuracyProgressivelyImprovesFromFiftyToFifteenToEightToThreeMetres() {
        GuidanceFullJourneyReplayHarness(caseId="full_initial_accuracy_improves").use { f ->
            f.gps(accuracy=50f);f.heading();f.advance(250);f.gps(accuracy=50f)
            f.selectDestination();f.button()
            val prompt=f.promptId();f.runtime.start(prompt);f.advance(1_000);f.runtime.done(prompt)
            val done=f.now
            f.confirmOfficialConditions();f.confirmPhoneMounting();f.observeCameraFrame();f.loadDetector()
            f.gps(accuracy=50f);f.heading()
            assertFalse(f.capture(WalkSessionAction.START_WALK).isReadyAt(f.now))
            assertNull(f.confirm.confirmedDecision());assertTrue(f.gateway.background.isEmpty())
            f.advance(1_000);f.observeCameraFrame();f.gps(accuracy=15f);f.heading()
            assertEquals(15.0,f.recordSensorEvidence().gpsAccuracy!!,0.0)
            assertFalse(f.capture(WalkSessionAction.START_WALK).isReadyAt(f.now))
            assertNull(f.confirm.confirmedDecision());assertTrue(f.providerRequests.isEmpty())
            f.advance(1_000);f.observeCameraFrame();f.gps(accuracy=8f);f.heading()
            assertEquals(8.0,f.recordSensorEvidence().gpsAccuracy!!,0.0)
            assertTrue("Eight metres already qualifies the approved ten-metre GPS policy",f.capture(WalkSessionAction.START_WALK).isReadyAt(f.now))
            assertNull("Qualified GPS cannot replace actual calibration DONE+3s",f.confirm.confirmedDecision())
            f.advance(1_000);f.observeCameraFrame();f.gps(accuracy=3f);f.heading();f.drainUi()
            assertEquals(done+3_000,f.calibration.snapshot?.readyAtMs)
            assertEquals(3.0,f.recordSensorEvidence().gpsAccuracy!!,0.0)
            activateAndInstall(f)
            completeCue(f,firstCue(f))
            finishDefaultLJourney(f)
        }
    }

    @Test fun firstRouteCueWaitingForNativeStartSurvivesTransientGpsAndHeadingQualityLoss() {
        replayTransientFirstCue(startBeforeFault=false)
    }

    @Test fun firstRouteCueAlreadyPlayingSurvivesTransientGpsAndHeadingQualityLoss() {
        replayTransientFirstCue(startBeforeFault=true)
    }

    private fun replayTransientFirstCue(startBeforeFault:Boolean) {
        val id=if(startBeforeFault)"full_first_playing_transient" else "full_first_waiting_start_transient"
        GuidanceFullJourneyReplayHarness(caseId=id).use { f ->
            admitAndInstall(f)
            val first=firstCue(f)
            val original=requireNotNull(first.dispatchCue)
            val retainedHeading=original.facingObservedAtElapsedRealtimeMs
            if(startBeforeFault){f.runtime.start(first.id);f.advance(0);f.assertNativeRouteStartAccepted(first)}
            f.advance(100)
            val firstBad=f.now
            f.heading(accuracyDegrees=60.0,reason=RouteCompassHeadingReason.HEADING_ACCURACY)
            f.gps(accuracy=50f);f.observeCameraFrame();f.advance(0)
            if(!startBeforeFault){
                // Validate actual native START while BAD/grace is still active, inside the original normal lease.
                assertEquals(RouteDirectionGraceMode.GRACE_RETAINED,
                    requireNotNull(f.graph.run.navigator.currentDirectionGraceSnapshot()).mode)
                assertTrue(f.now<=requireNotNull(original.facingValidUntilElapsedRealtimeMs))
                f.runtime.start(first.id);f.advance(0);f.assertNativeRouteStartAccepted(first)
                val duringStart=f.recordSensorEvidence()
                assertEquals(firstBad,duringStart.gpsFirstBadAt)
                assertEquals(firstBad,duringStart.directionFirstBadAt)
                assertEquals(RouteDirectionGraceMode.GRACE_RETAINED,duringStart.directionMode)
            }
            f.advance(300)
            val bad=f.recordSensorEvidence()
            assertEquals(firstBad,bad.gpsFirstBadAt)
            assertEquals(firstBad+GuidanceRecoveryTiming.GPS_GRACE_MS,bad.gpsDeadline)
            assertEquals(RuntimeMeasuredQualityGate.GpsRecoveryStage.RECHECKING,bad.gpsStage)
            assertEquals("Raw bad GPS keeps its actual accuracy",50.0,bad.gpsAccuracy!!,0.0)
            val grace=requireNotNull(f.graph.run.navigator.currentDirectionGraceSnapshot())
            assertEquals(firstBad,grace.onsetElapsedRealtimeMs)
            assertEquals(firstBad+GuidanceRecoveryTiming.DIRECTION_GRACE_MS,grace.deadlineElapsedRealtimeMs)
            assertEquals(retainedHeading,grace.retainedObservedAtElapsedRealtimeMs)
            assertFalse("A soft fluctuation cannot repeatedly restart the actual first utterance",first.cancelled)
            assertEquals(WalkSessionState.ACTIVE,f.walk.state)
            f.heading();f.gps();f.observeCameraFrame();f.advance(300)
            f.runtime.done(first.id);f.advance(0);f.drainUi()
            f.assertNativeRouteCompletionAcknowledged(first)
            assertEquals(original,f.completedRouteSpeechCue())
            f.assertSameHomeOwner()
            finishDefaultLJourney(f)
        }
    }

    @Test fun homeOffRouteStartReferencesTheClearNearestPointThenJoinsQualifiedRouteProgress() {
        GuidanceFullJourneyReplayHarness(caseId="full_clear_nearest_join").use { f ->
            admitAndInstall(f,initialEast=-20.0,initialNorth=30.0)
            // A real new measurement follows the archive-install UI work; do not duplicate its clock.
            f.finishPendingStatusSpeech();f.advance(300);f.heading();f.gps();f.advance(300)
            // The nominal reference clock needs a second distinct fresh observation at the same raw quality.
            f.heading();f.gps();f.advance(0)
            assertTrue(f.graph.run.navigator.isWaitingForInitialRouteJoin())
            assertNull("A nearest-point reference cannot create accepted route progress",f.latestAcceptedMatch)
            assertNull(f.currentProgressM)
            val target=requireNotNull(f.graph.run.navigator.currentRouteApproachTarget(f.now))
            assertEquals("The reference must use the actual nearest point, not the provider start",f.point(0.0,30.0).latitude,target.point.latitude,0.00002)
            assertEquals(f.point(0.0,30.0).longitude,target.point.longitude,0.00002)
            assertTrue(target.distanceM in 15.0..25.0)
            val reference=f.routeSpeech().firstOrNull{it.dispatchCue?.directionOnly==true}
            assertNotNull("A clear measured nearest-point reference must reach native output",reference)
            completeCue(f,requireNotNull(reference))
            assertTrue("Reference speech DONE cannot admit route movement",f.graph.run.navigator.isWaitingForInitialRouteJoin())
            for(east in listOf(-15.0,-10.0,-5.0,0.0))walkSample(f,east,30.0,90.0)
            repeat(2){walkSample(f,0.0,30.0,0.0,walking=false)}
            assertFalse("Actual qualified GPS must automatically complete joining",f.graph.run.navigator.isWaitingForInitialRouteJoin())
            assertNotNull(f.latestAcceptedMatch)
            assertNotNull(f.currentProgressM)
            f.assertSameHomeOwner()
            finishDefaultLJourney(f,startNorth=30.0)
        }
    }

    @Test fun homeAmbiguousParallelBranchesWithholdPrecisionUntilFreshQualifiedBranchInputsArrive() {
        GuidanceFullJourneyReplayHarness(caseId="full_ambiguous_branch_recovery").use { f ->
            f.providerResponse={f.parallelProviderResponse()}
            admitAndInstall(f,initialEast=30.0,initialNorth=3.0,initialAccuracy=5f,initialFacing=90.0)
            f.finishPendingStatusSpeech();f.heading(90.0);f.gps(30.0,3.0,5f);f.advance(300)
            val ambiguous=requireNotNull(f.graph.run.navigator.currentRouteMatch())
            assertEquals(RouteMatchQuality.LOW,ambiguous.quality)
            assertEquals(RouteMatchReason.AMBIGUOUS_CANDIDATES,ambiguous.reason)
            assertNull("Branch ambiguity cannot manufacture matched progress",f.latestAcceptedMatch)
            assertNull(f.currentProgressM)
            assertNull(f.graph.run.navigator.currentTactileRouteProgress().segmentIndex)
            assertNotEquals(RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION,f.graph.run.navigator.pendingUserDecision())
            assertTrue("Ambiguity cannot acknowledge a precise route action",f.acknowledgedRouteCues.none{!it.directionOnly})
            repeat(6){
                f.advance(500);f.observeCameraFrame();f.heading(90.0);f.gps(30.0,0.0,1f);f.finishPendingStatusSpeech()
            }
            f.advance(300)
            assertNotNull("Distinct fresh branch observations must automatically reacquire actual progress",f.latestAcceptedMatch)
            assertNotNull(f.currentProgressM)
            assertEquals(0,f.graph.run.navigator.currentTactileRouteProgress().segmentIndex)
            val recovered=f.routeSpeech().lastOrNull{!it.done&&!it.cancelled&&it.dispatchCue?.directionOnly==false}
            assertNotNull("Qualified branch recovery must produce an actual native action",recovered)
            completeCue(f,requireNotNull(recovered))
            f.assertSameHomeOwner()
            for(east in listOf(40.0,50.0,60.0))walkSample(f,east,0.0,90.0)
            walkSample(f,60.0,3.0,0.0);walkSample(f,60.0,6.0,0.0)
            for(east in listOf(50.0,40.0,30.0,20.0,10.0,0.0))walkSample(f,east,6.0,270.0)
            for(north in listOf(16.0,26.0,36.0,46.0,56.0,60.0))walkSample(f,0.0,north,0.0)
            for(east in listOf(10.0,20.0,30.0,40.0))walkSample(f,east,60.0,90.0)
            waitForQualifiedArrival(f);completeAutomaticArrivalAndEnd(f)
        }
    }

    @Test fun homeJourneyMidwalkRerouteKeepsTheInstalledRouteUntilHttpAndStorageAcksComplete() {
        GuidanceFullJourneyReplayHarness(caseId="full_midwalk_reroute_delayed_acks").use { f ->
            admitAndInstall(f);completeCue(f,firstCue(f))
            f.runtime.scheduleNativeCallbacks()
            for(north in listOf(10.0,20.0,30.0))walkSample(f,0.0,north,0.0)
            // West of the northbound leg has one clear nearest segment; -50m exceeds the actual 35m boundary.
            for(east in listOf(-10.0,-20.0,-30.0,-40.0,-50.0))walkSample(f,east,30.0,270.0)
            repeat(2){if(f.graph.run.navigator.pendingUserDecision()!=RouteNavigatorUserDecision.REROUTE)walkSample(f,-50.0,30.0,270.0,walking=false)}
            assertEquals(RouteNavigatorUserDecision.REROUTE,f.graph.run.navigator.pendingUserDecision())
            val oldId=f.graph.run.navigator.currentRouteId()
            val oldGeneration=f.routeGeneration
            f.providerResponse=f::straightProviderResponse
            f.commands.requestRerouteFromVoice(requireNotNull(f.graph.run.navigator.pendingDecisionToken()))
            assertTrue(f.graph.acquisition.value.requestPending)
            assertEquals(2,f.providerRequests.size)
            assertTrue(f.routeGeneration>oldGeneration)
            assertEquals(oldId,f.graph.run.navigator.currentRouteId())
            holdInputs(f,3_000,-50.0,30.0,270.0)
            assertEquals("A timer cannot stand in for HTTP response",oldId,f.graph.run.navigator.currentRouteId())
            assertTrue(f.graph.acquisition.value.requestPending)
            f.runProvider()
            assertEquals("HTTP response cannot stand in for encrypted storage/readback ACK",oldId,f.graph.run.navigator.currentRouteId())
            assertTrue(f.graph.acquisition.value.requestPending)
            holdInputs(f,3_000,-50.0,30.0,270.0)
            assertEquals(oldId,f.graph.run.navigator.currentRouteId())
            assertTrue(f.graph.acquisition.value.requestPending)
            f.runIo();f.drainUi()
            assertFalse(f.graph.acquisition.value.requestPending)
            assertNotEquals(oldId,f.graph.run.navigator.currentRouteId())
            assertEquals(2,f.providerRequests.size)
            val newRouteId=f.graph.run.navigator.currentRouteId()
            val beforeNewRoute=f.routeSpeech().size
            f.advance(300);f.heading(72.0);f.gps(-50.0,30.0);f.advance(300)
            val newCue=awaitAutomaticRouteCompletion(f,beforeNewRoute,-50.0,30.0,72.0)
            assertEquals(newRouteId,newCue.dispatchCue?.routeId)
            for((east,north) in listOf(-41.0 to 33.0,-32.0 to 36.0,-23.0 to 39.0,-14.0 to 42.0,-5.0 to 45.0,
                4.0 to 48.0,13.0 to 51.0,22.0 to 54.0,31.0 to 57.0,40.0 to 60.0))walkSample(f,east,north,72.0)
            waitForQualifiedArrival(f);completeAutomaticArrivalAndEnd(f)
        }
    }

    @Test fun homeJourneySimultaneousPersistentGpsAndDirectionFaultsKeepFirstBadThenAutomaticallyRecover() {
        GuidanceFullJourneyReplayHarness(caseId="full_simultaneous_persistent_recovery").use { f ->
            admitAndInstall(f);completeCue(f,firstCue(f))
            // Later recheck/recovery notices use real 80ms START and 3s DONE, including during the BAD loop.
            f.runtime.scheduleNativeCallbacks()
            val firstBad=f.now
            f.headingUnavailable();f.gps(accuracy=50f);f.observeCameraFrame();f.advance(300)
            val initial=f.recordSensorEvidence()
            assertEquals(firstBad,initial.gpsFirstBadAt)
            assertEquals(firstBad+GuidanceRecoveryTiming.GPS_GRACE_MS,initial.gpsDeadline)
            assertEquals(firstBad,initial.directionFirstBadAt)
            assertEquals(firstBad+GuidanceRecoveryTiming.DIRECTION_GRACE_MS,initial.directionDeadline)
            val recoveryAfter=firstBad+GuidanceRecoveryTiming.GPS_GRACE_MS+300L
            while(f.now<recoveryAfter){
                f.advance(minOf(300L,recoveryAfter-f.now));f.observeCameraFrame();f.headingUnavailable();f.gps(accuracy=50f)
                val current=f.recordSensorEvidence()
                assertEquals("GPS remeasurement cannot move first BAD",firstBad,current.gpsFirstBadAt)
                assertEquals("Direction remeasurement cannot move first BAD",firstBad,current.directionFirstBadAt)
                assertEquals(firstBad+GuidanceRecoveryTiming.DIRECTION_GRACE_MS,current.directionDeadline)
                f.assertSameHomeOwner()
            }
            val restricted=f.recordSensorEvidence()
            assertEquals(RuntimeMeasuredQualityGate.GpsRecoveryStage.RESTRICTED,restricted.gpsStage)
            assertEquals(RouteDirectionGraceMode.PROBLEM,restricted.directionMode)
            assertEquals("GPS loss alone restricts navigation while the real camera stays healthy",WalkSessionState.ACTIVE,f.walk.state)
            assertTrue(f.graph.run.navigator.hasRoute())
            val before=f.routeSpeech().size
            // Receive a genuinely new GOOD packet after the final BAD, preserving both original timestamps.
            f.advance(1)
            f.heading();f.gps();f.observeCameraFrame()
            holdInputs(f,600,0.0,0.0,0.0)
            f.advance(1);f.heading();f.gps();f.observeCameraFrame();f.advance(300)
            assertEquals(RuntimeMeasuredQualityGate.GpsRecoveryStage.FRESH,f.recordSensorEvidence().gpsStage)
            assertEquals(RouteDirectionGraceMode.NORMAL,f.recordSensorEvidence().directionMode)
            assertEquals(WalkSessionState.ACTIVE,f.walk.state)
            val recovered=awaitAutomaticRouteCompletion(f,before,0.0,0.0,0.0)
            assertTrue("Actual fresh GPS+direction must automatically resume without a second start or manual retry",
                recovered.started&&recovered.done&&!recovered.cancelled)
            assertTrue(recovered.dispatchCue in f.acknowledgedRouteCues);f.assertSameHomeOwner()
            finishDefaultLJourney(f)
        }
    }

    @Test fun homeJourneyWeakDestinationInputsCannotEndBeforeQualifiedDwellAndFinalSpeech() {
        GuidanceFullJourneyReplayHarness(caseId="full_weak_arrival_then_qualified_end").use { f ->
            admitAndInstall(f);completeCue(f,firstCue(f))
            for(north in listOf(10.0,20.0,30.0,40.0,50.0,60.0))walkSample(f,0.0,north,0.0)
            for(east in listOf(10.0,20.0))walkSample(f,east,60.0,90.0)
            repeat(3){sample->
                f.advance(3_000);f.observeCameraFrame();f.heading(90.0)
                // The first weak endpoint report follows actual Counter-observed walking, then the user stands.
                if(sample==0)f.walkingSteps(28) else f.stationaryStepCounterObservation()
                f.gps(40.0,60.0,50f);f.finishPendingStatusSpeech()
                assertEquals(50.0,f.recordSensorEvidence().gpsAccuracy!!,0.0)
                assertNotEquals("Weak destination observations cannot qualify automatic arrival",RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION,f.graph.run.navigator.pendingUserDecision())
                assertFalse(f.arrivalStarted);assertTrue(f.arrivalSpeech().isEmpty())
                assertTrue(f.graph.run.navigator.hasRoute());assertTrue(f.graph.run.active)
            }
            f.advance(300);f.heading(90.0);f.gps(40.0,60.0,3f);f.observeCameraFrame();f.finishPendingStatusSpeech()
            assertNotEquals("The first fresh good observation alone cannot satisfy the 3s arrival dwell",RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION,f.graph.run.navigator.pendingUserDecision())
            assertFalse(f.arrivalStarted)
            waitForQualifiedArrival(f);completeAutomaticArrivalAndEnd(f)
        }
    }

    @Test fun automaticArrivalErrorStopAndMissingTerminalCallbacksEndOnlyTheCurrentWalkOnce() {
        for(terminal in listOf("error","stop","timeout")) {
            GuidanceFullJourneyReplayHarness(caseId="arrival_final_$terminal").use { f ->
                reachAutomaticArrival(f)
                val final=f.arrivalSpeech().single()
                assertEquals(WalkSessionState.PAUSED,f.walk.state)
                assertEquals(0,f.arrivalFinishes)
                f.assertArrivalInputsRetired()
                f.runtime.start(final.id)
                f.advance(0)
                assertEquals(WalkSessionState.PAUSED,f.walk.state)
                when(terminal){
                    "error"->f.runtime.error(final.id)
                    "stop"->f.runtime.stopCallback(final.id)
                    "timeout"->f.advance(RouteGuidanceController.ARRIVAL_COMPLETION_TIMEOUT_MS)
                }
                f.advance(0);f.drainUi();f.runIo();f.drainUi()
                assertEquals("$terminal must release only the pending arrival owner",WalkSessionState.ENDED,f.walk.state)
                assertEquals(1,f.arrivalFinishes)
                f.runtime.done(final.id);f.runtime.error(final.id);f.runtime.stopCallback(final.id)
                f.gps(40.0,60.0);f.advance(RouteGuidanceController.ARRIVAL_COMPLETION_TIMEOUT_MS)
                assertEquals(WalkSessionState.ENDED,f.walk.state)
                assertEquals(1,f.arrivalBegins);assertEquals(1,f.arrivalFinishes)
                assertEquals("Duplicate terminal callbacks and late GPS cannot repeat the final utterance",1,f.arrivalSpeech().size)
                assertFalse(f.graph.run.navigator.hasRoute());assertNull(f.stored)
            }
        }
    }

    @Test fun oldArrivalCallbacksAndTimeoutCannotEndOrClearANewWalk() {
        GuidanceFullJourneyReplayHarness(caseId="arrival_retired_before_new_walk").use { f ->
            admitAndInstall(f);completeCue(f,firstCue(f))
            val oldGps=f.captureGpsDelivery()
            walkToAutomaticArrival(f)
            val final=f.arrivalSpeech().single()
            val endingEpoch=f.walk.epoch
            f.startFreshWalk()
            val replacementEpoch=f.walk.epoch
            val replacementBinding=f.currentBinding()
            assertEquals(WalkSessionState.READY,f.walk.state)
            assertNotEquals(endingEpoch,replacementEpoch)
            f.graph.run.selectDestination(f.point(80.0,60.0))
            val replacementDestination=f.graph.run.destination
            f.runtime.start(final.id);f.runtime.done(final.id);f.runtime.error(final.id);f.runtime.stopCallback(final.id)
            oldGps(f.now+1L)
            f.advance(RouteGuidanceController.ARRIVAL_COMPLETION_TIMEOUT_MS)
            assertEquals(WalkSessionState.READY,f.walk.state)
            assertEquals(replacementEpoch,f.walk.epoch)
            assertSame(replacementBinding,f.currentBinding())
            assertSame(replacementDestination,f.graph.run.destination)
            assertEquals(0,f.arrivalFinishes)
            assertEquals(1,f.arrivalSpeech().size)
        }
    }

    private fun reachAutomaticArrival(f:GuidanceFullJourneyReplayHarness) {
        admitAndInstall(f);completeCue(f,firstCue(f));walkToAutomaticArrival(f)
    }

    private fun walkToAutomaticArrival(f:GuidanceFullJourneyReplayHarness) {
        for(north in listOf(10.0,20.0,30.0,40.0,50.0,60.0))walkSample(f,0.0,north,0.0)
        for(east in listOf(10.0,20.0,30.0,40.0))walkSample(f,east,60.0,90.0)
        waitForQualifiedArrival(f)
    }

    internal fun firstCue(f:GuidanceFullJourneyReplayHarness):GuidanceFullJourneyReplayHarness.NativeSpoken {
        f.finishPendingStatusSpeech();f.advance(300);f.heading();f.gps();f.advance(300)
        val cue=f.routeSpeech().firstOrNull{!it.started&&!it.done&&!it.cancelled}
        assertNotNull("The installed route must offer an actual first native clock/metres cue",cue)
        return requireNotNull(cue)
    }

    private fun completeCue(f:GuidanceFullJourneyReplayHarness,cue:GuidanceFullJourneyReplayHarness.NativeSpoken) {
        f.runtime.start(cue.id);f.advance(0);f.drainUi();f.assertNativeRouteStartAccepted(cue)
        f.advance(100);f.runtime.done(cue.id);f.advance(0);f.drainUi();f.assertNativeRouteCompletionAcknowledged(cue)
    }

    private fun walkSample(f:GuidanceFullJourneyReplayHarness,east:Double,north:Double,facing:Double,walking:Boolean=true) {
        if(walking)f.beginWalkingStepInputs()
        f.advance(3_000);f.observeCameraFrame();f.heading(facing)
        if(walking)f.walkingSteps(14) else f.stationaryStepCounterObservation()
        f.gps(east,north)
        if(!f.runtime.automaticCallbacksEnabled)f.finishPendingSpeech()
        f.advance(300);f.runGatewayRequests();f.runIo();f.drainUi()
        f.recordRouteProgress();f.assertSameHomeOwner()
    }

    private fun holdInputs(f:GuidanceFullJourneyReplayHarness,durationMs:Long,east:Double,north:Double,facing:Double) {
        var remaining=durationMs
        while(remaining>0){
            val step=minOf(300L,remaining);f.advance(step);f.observeCameraFrame();f.heading(facing);f.gps(east,north)
            f.drainUi();remaining-=step;f.assertSameHomeOwner()
        }
    }

    private fun awaitAutomaticRouteCompletion(f:GuidanceFullJourneyReplayHarness,before:Int,
        east:Double,north:Double,facing:Double):GuidanceFullJourneyReplayHarness.NativeSpoken {
        // One recovery notice, one route action, a real transport retry and one actor cadence.
        // Stop on actual ACK; the budget does not change any sensor/output grant or callback delay.
        val deadline=f.now+2*f.runtime.automaticDoneDelayMs+f.graph.run.navigator.instructionRetryIntervalMs+300L
        fun completed()=f.routeSpeech().drop(before).firstOrNull {
            it.started&&it.done&&!it.cancelled&&it.dispatchCue in f.acknowledgedRouteCues
        }
        while(completed()==null&&f.now<deadline) {
            holdInputs(f,minOf(300L,deadline-f.now),east,north,facing)
            f.runGatewayRequests();f.runIo();f.drainUi()
        }
        f.recordSpeechReadinessEvidence()
        val cue=completed()
        assertNotNull("Actual scheduler and native callbacks must deliver a current automatically generated route cue",cue)
        return requireNotNull(cue)
    }

    private fun finishDefaultLJourney(f:GuidanceFullJourneyReplayHarness,startNorth:Double=0.0) {
        for(north in listOf(10.0,20.0,30.0,40.0,50.0,60.0).filter{it>startNorth})walkSample(f,0.0,north,0.0)
        for(east in listOf(10.0,20.0,30.0,40.0))walkSample(f,east,60.0,90.0)
        waitForQualifiedArrival(f);completeAutomaticArrivalAndEnd(f)
    }

    private fun waitForQualifiedArrival(f:GuidanceFullJourneyReplayHarness) {
        repeat(4){if(!f.arrivalStarted)walkSample(f,40.0,60.0,90.0,walking=false)}
        assertTrue("Original qualified samples and their real dwell must enter automatic arrival",f.arrivalStarted)
        assertEquals(1,f.arrivalBegins)
        assertNull(f.graph.run.navigator.pendingUserDecision())
    }

    internal fun completeAutomaticArrivalAndEnd(f:GuidanceFullJourneyReplayHarness) {
        assertTrue("The real qualified arrival update must initiate completion",f.arrivalStarted)
        assertFalse(f.graph.run.navigator.hasRoute());assertFalse(f.graph.run.active)
        f.assertArrivalInputsRetired()
        f.runIo();f.drainUi();assertTrue("Automatic arrival must purge the stored route",f.stored==null)
        val final=f.arrivalSpeech().single()
        if(!final.done){
            assertEquals("Dispatch acceptance cannot end the walk",WalkSessionState.PAUSED,f.walk.state)
            assertEquals(0,f.arrivalFinishes)
            if(!final.started)f.runtime.start(final.id)
            f.advance(0)
            assertEquals("Native START still cannot end the walk",WalkSessionState.PAUSED,f.walk.state)
            f.runtime.done(final.id)
        }
        f.advance(0)
        assertTrue(final.started&&final.done&&!final.cancelled)
        assertEquals(1,f.arrivalBegins);assertEquals(1,f.arrivalFinishes)
        assertEquals(WalkSessionState.ENDED,f.walk.state);assertFalse(f.navigationAdmitted());f.assertSameHomeOwner()
    }

    private fun activateAndInstall(f:GuidanceFullJourneyReplayHarness) {
        assertTrue(f.confirm.isStartupCapabilityConfirmed());assertEquals(1,f.gateway.background.size)
        assertTrue(f.providerRequests.isEmpty());f.prepareArchive();f.gateway.runBackground();f.drainUi()
        f.acknowledgeWalkOwnership();assertEquals(WalkSessionState.ACTIVE,f.walk.state)
        assertEquals(1,f.activations);assertEquals(1,f.navigationStarts)
        assertEquals(listOf(f.destination),f.destinationHandOff);f.assertSameHomeOwner()
        f.runProvider();assertFalse(f.graph.run.navigator.hasRoute())
        f.runIo();f.drainUi();assertTrue(f.graph.run.navigator.hasRoute());assertTrue(f.graph.run.active)
    }

    private fun observeIndependentReadiness(f:GuidanceFullJourneyReplayHarness) {
        f.confirmOfficialConditions()
        f.confirmPhoneMounting()
        f.observeCameraFrame()
        f.loadDetector()
        f.gps()
        f.heading()
    }

    internal fun admitAndInstall(f:GuidanceFullJourneyReplayHarness,initialEast:Double=0.0,initialNorth:Double=0.0,
        initialAccuracy:Float=3f,initialFacing:Double=0.0) {
        assertEquals(WalkSessionState.READY,f.walk.state)
        assertNull(f.confirm.confirmedDecision())
        f.gps(initialEast,initialNorth,initialAccuracy);f.heading(initialFacing)
        f.advance(250);f.gps(initialEast,initialNorth,initialAccuracy);f.heading(initialFacing)
        f.selectDestination()
        f.button()
        assertEquals(1,f.prompts.size)
        f.runtime.start(f.promptId())
        f.advance(1_000)
        assertNull(f.calibration.snapshot?.voiceDoneAtMs)
        assertTrue(f.gateway.background.isEmpty())
        f.runtime.done(f.promptId())
        val done=f.now
        f.confirmOfficialConditions();f.confirmPhoneMounting();f.observeCameraFrame();f.loadDetector()
        f.gps(initialEast,initialNorth,initialAccuracy);f.heading(initialFacing)
        f.advance(2_999)
        assertNull(f.confirm.confirmedDecision())
        assertTrue(f.gateway.background.isEmpty())
        f.advance(1);f.drainUi()
        assertEquals(done+3_000,f.calibration.snapshot?.readyAtMs)
        assertTrue("Readiness must be produced from distinct live inputs",f.capture(WalkSessionAction.START_WALK).isReadyAt(f.now))
        assertTrue(f.confirm.isStartupCapabilityConfirmed())
        assertEquals(1,f.gateway.background.size)
        assertTrue(f.providerRequests.isEmpty())
        f.prepareArchive()
        f.gateway.runBackground();f.drainUi()
        assertTrue("Actual ACTIVE transition must queue its walk-ownership ACK",f.graph.archive.value.preparationPending)
        assertTrue("A READY archive scope cannot authorize the ACTIVE route",f.providerRequests.isEmpty())
        f.acknowledgeWalkOwnership()
        assertEquals(WalkSessionState.ACTIVE,f.walk.state)
        assertEquals(1,f.activations)
        assertEquals(1,f.navigationStarts)
        assertEquals(listOf(f.destination),f.destinationHandOff)
        f.assertSameHomeOwner()
        f.runProvider()
        assertFalse("Provider response must wait for encrypted write/readback ACK",f.graph.run.navigator.hasRoute())
        f.runIo();f.drainUi()
        assertTrue(f.graph.run.navigator.hasRoute())
        assertTrue(f.graph.run.active)
        assertNotNull(f.stored)
        assertTrue(f.stages.containsAll(listOf("ROUTE_ARCHIVE_COMPLETE","ROUTE_INSTALLED")))
        f.assertSameHomeOwner()
    }
}
