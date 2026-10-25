package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.device.RuntimeMeasuredQualityGate
import kr.co.hanium.dreamup.walksafe.navigation.RouteDirectionGraceMode
import kr.co.hanium.dreamup.walksafe.navigation.WalkSessionVoiceAction
import kr.co.hanium.dreamup.walksafe.navigation.session.GuidanceRecoveryTiming
import kr.co.hanium.dreamup.walksafe.network.GatewayCapacityProcessState
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

internal data class StationaryFullReplayCase(
    val id:String, val kind:String, val phase:String, val durationMs:Long,
    val jitter:List<Pair<Double,Double>>, val gpsAccuracy:List<Float>,
    val yaws:List<Double>, val headingErrors:List<Double>, val boundary:List<Int>,
    val approvedGpsAccuracyM:Double, val nativeStartDelayMs:Long, val nativeDoneDelayMs:Long,
) { override fun toString()=id }

/** Real HOME/readiness/Gateway/archive/Guide owners; SDK samples and native callbacks are leaves.
 * Original timestamps and fresh zero-delta Counter observations are preserved.
 * RGB observations exercise CameraFrameQualityPolicy only. Depth, ACTIVE mounting ownership,
 * physical stationary sensing, Android Handler scheduling and acoustics remain outside this test.
 */
@RunWith(Parameterized::class)
internal class GuidanceStationaryFullJourneyReplayTest(private val case:StationaryFullReplayCase) {
    private val home=GuidanceFullJourneyReplayTest()

    @After fun resetCapacity()=GatewayCapacityProcessState.resetForTests()

    @Test fun replayActualHomeAndStationaryOwners() {
        GuidanceFullJourneyReplayHarness(caseId=case.id,approvedGpsAccuracyM=case.approvedGpsAccuracyM).use { f ->
            home.admitAndInstall(f,initialNorth=20.0)
            val first=home.firstCue(f)
            f.beginWalkingStepInputs()
            assertEquals("Actual Counter normalization must start at zero",0,f.currentStepCount)
            if(case.phase=="playing"){
                f.runtime.start(first.id);f.advance(0);f.assertNativeRouteStartAccepted(first)
            }
            f.runtime.scheduleNativeCallbacks(case.nativeStartDelayMs,case.nativeDoneDelayMs)
            when(case.kind) {
                "jitter","heading","joint","gps_boundary","heading_boundary","camera_metadata" -> stationaryNoise(f,first)
                "gps_loss","heading_loss","dual_gps_first","dual_heading_first" -> persistentFaultAndRecovery(f)
                "reversal" -> genuineReversal(f,first)
                "corner" -> genuineCornerAndEnd(f)
                "retired" -> retiredCallbacks(f,first)
                "silence" -> sourceSilence(f)
                else -> error("Unknown stationary scenario kind: "+case.kind)
            }
        }
    }

    private fun stationaryNoise(f:GuidanceFullJourneyReplayHarness,first:GuidanceFullJourneyReplayHarness.NativeSpoken) {
        val start=f.now
        val progress=requireNotNull(f.currentProgressM)
        val generation=f.currentFeedbackGeneration
        val stops=f.eventCount("TTS_STOP")
        val initialGuide=f.recordRouteProgress().nextGuide
        // A distinct receipt before the scheduled native START; never replay the install timestamp.
        noiseSample(f,1L,1)
        var sample=2
        while(f.now<start+case.durationMs) {
            noiseSample(f,minOf(300L,start+case.durationMs-f.now),sample++)
            assertStationary(f,first,progress,generation,stops,initialGuide)
        }
        assertTrue("Actual first native utterance must finish despite normal fixed-phone noise",first.done)
        assertFalse(first.cancelled)
        assertTrue("The exact original first cue needs actual Navigator completion ACK",
            first.dispatchCue in f.acknowledgedRouteCues)
        assertCompletedNativeCues(f)
        assertEquals(1,f.activations);assertEquals(1,f.providerRequests.size)
    }

    private fun noiseSample(f:GuidanceFullJourneyReplayHarness,delta:Long,index:Int) {
        f.advance(delta)
        if(case.kind=="camera_metadata") {
            f.observeCameraFrame(occlusion=if(index%2==0)null else 0.0,
                angularShake=if(index%2==0)null else 90.0,
                nearBlackFraction=if(index%2==0)null else 0.9)
        } else f.observeCameraFrame()
        val varyHeading=case.kind in setOf("heading","joint","heading_boundary")
        val heading=if(varyHeading)case.yaws[index%case.yaws.size] else 0.0
        val angularError=when(case.kind) {
            "heading_boundary" -> if(case.boundary[index%case.boundary.size]==0)29.0 else 31.0
            "heading","joint" -> case.headingErrors[index%case.headingErrors.size]
            else -> 3.0
        }
        f.heading(heading,angularError)
        f.stationaryStepCounterObservation()
        if(index%3==0 || delta==1L) {
            val offset=if(case.kind in setOf("jitter","joint"))case.jitter[index%case.jitter.size] else 0.0 to 0.0
            val accuracy=when(case.kind) {
                "gps_boundary" -> if(case.boundary[index%case.boundary.size]==0)14.8f else 15.2f
                "jitter","joint" -> case.gpsAccuracy[index%case.gpsAccuracy.size]
                else -> 3f
            }
            f.gps(offset.first,20.0+offset.second,accuracy)
        }
        settle(f)
        f.recordSensorEvidence()
    }

    private fun assertStationary(f:GuidanceFullJourneyReplayHarness,
        first:GuidanceFullJourneyReplayHarness.NativeSpoken,progress:Double,generation:Int,stops:Int,guide:Int) {
        assertEquals(WalkSessionState.ACTIVE,f.walk.state)
        assertTrue(f.graph.run.active&&f.graph.run.navigator.hasRoute())
        f.assertSameHomeOwner()
        assertEquals("Fresh zero-delta Counter samples cannot manufacture steps",0,f.currentStepCount)
        assertNull("Fixed-phone noise cannot invent reroute, join or arrival decisions",f.graph.run.navigator.pendingUserDecision())
        f.currentProgressM?.let { current ->
            assertTrue("Metre-scale GPS jitter cannot accumulate walking progress: "+current+" vs "+progress,
                current<=progress+2.0)
        }
        val evidence=f.recordRouteProgress()
        assertEquals("Stationary uncertainty cannot consume the protected L turn",guide,evidence.nextGuide)
        assertNull(evidence.acceptedTurnRawAtMs)
        assertEquals("Normal fixed-phone observations cannot repeatedly replace the output owner",generation,f.currentFeedbackGeneration)
        assertEquals("Soft uncertainty must not chop current speech",stops,f.eventCount("TTS_STOP"))
        assertFalse(first.cancelled)
        if(first.started&&!first.done)f.assertNativeRouteStartAccepted(first)
        assertCompletedNativeCues(f)
    }

    private fun persistentFaultAndRecovery(f:GuidanceFullJourneyReplayHarness) {
        val gpsFault=case.kind!="heading_loss"
        val headingFault=case.kind!="gps_loss"
        val firstBad=f.now
        faultSample(f,0L,gpsFault,headingFault)
        val initial=f.recordSensorEvidence()
        if(gpsFault) {
            assertEquals(firstBad,initial.gpsFirstBadAt)
            assertEquals(firstBad+GuidanceRecoveryTiming.GPS_GRACE_MS,initial.gpsDeadline)
        }
        if(headingFault) {
            assertEquals(firstBad,initial.directionFirstBadAt)
            assertEquals(firstBad+GuidanceRecoveryTiming.DIRECTION_GRACE_MS,initial.directionDeadline)
        }
        val grace=if(gpsFault)GuidanceRecoveryTiming.GPS_GRACE_MS else GuidanceRecoveryTiming.DIRECTION_GRACE_MS
        val end=firstBad+grace+600L
        while(f.now<end) {
            faultSample(f,minOf(300L,end-f.now),gpsFault,headingFault)
            val state=f.recordSensorEvidence()
            if(gpsFault) {
                assertEquals("Repeated rejected accuracy cannot restart the GPS episode",firstBad,state.gpsFirstBadAt)
                assertEquals("Output restrictions cannot stop fresh GPS observation collection",f.now,state.gpsOriginalAt)
            }
            if(headingFault) {
                assertEquals("Output generations cannot erase the same unresolved heading fault",firstBad,state.directionFirstBadAt)
                assertEquals(firstBad+GuidanceRecoveryTiming.DIRECTION_GRACE_MS,state.directionDeadline)
            }
            assertRetainedWalk(f)
        }
        if(gpsFault)assertEquals(RuntimeMeasuredQualityGate.GpsRecoveryStage.RESTRICTED,f.recordSensorEvidence().gpsStage)
        if(headingFault)assertEquals(RouteDirectionGraceMode.PROBLEM,f.recordSensorEvidence().directionMode)
        val word=if(gpsFault)"위치" else "방향"
        val cause=f.runtime.spoken.firstOrNull { request ->
            request.requestedAtMs>=firstBad&&request.message.contains(word)&&
                (request.message.contains("확인")||request.message.contains("오차")||request.message.contains("품질"))&&
                request.dispatchCue?.requiresFacingEvidence!=true
        }
        assertNotNull("Explain the measured cause when its feature is actually restricted",cause)
        val notice = requireNotNull(cause)
        if (!gpsFault) {
            assertTrue("Short direction rechecks stay silent until the original grace expires",
                notice.requestedAtMs >= firstBad + grace)
        } else {
            // GPS raw evidence can expire before its longer quality recheck deadline.
            assertTrue("Explain the GPS restriction when the original raw evidence expires",
                notice.requestedAtMs < firstBad + grace)
        }
        // The fault is intentionally silent during grace. Its concrete notice can therefore
        // start only at the restriction, and still needs the configured real callback timeline.
        val noticeCompletionDeadline = f.now + case.nativeStartDelayMs + case.nativeDoneDelayMs + 600L
        while (!notice.done && f.now < noticeCompletionDeadline) {
            faultSample(f,minOf(300L,noticeCompletionDeadline-f.now),gpsFault,headingFault)
            assertRetainedWalk(f)
        }
        assertTrue("The restriction notice must actually receive native START and DONE",notice.started&&notice.done&&!notice.cancelled)

        val before=f.routeSpeech().size
        if(case.kind=="dual_gps_first" || case.kind=="dual_heading_first") {
            val keepGpsBad=case.kind=="dual_heading_first"
            val interimEnd=f.now+1_200L
            while(f.now<interimEnd)faultSample(f,300L,keepGpsBad,!keepGpsBad)
            assertTrue("Recovery of only one source cannot publish a new current clock/metres claim",
                f.routeSpeech().drop(before).none{!it.cancelled})
        }
        recover(f,before)
    }

    private fun faultSample(f:GuidanceFullJourneyReplayHarness,delta:Long,gpsBad:Boolean,headingBad:Boolean) {
        f.advance(delta);f.observeCameraFrame()
        if(headingBad)f.headingUnavailable() else f.heading()
        // Every sample carries its actual timestamp, including while navigation outputs are closed.
        if(delta>0L)f.stationaryStepCounterObservation()
        f.gps(accuracy=if(gpsBad)50f else 3f)
        settle(f)
    }

    private fun recover(f:GuidanceFullJourneyReplayHarness,before:Int) {
        val end=f.now+6_000L
        while(f.now<end)faultSample(f,300L,false,false)
        // A recovery response and transport backoff can use part of those six seconds.
        // Consume the already scheduled original route DONE, with current measured inputs.
        val pending=f.routeSpeech().drop(before).firstOrNull{!it.done&&!it.cancelled}
        if(pending!=null) {
            val actualDoneAt=requireNotNull(pending.scheduledDoneAtMs)
            while(f.now<actualDoneAt)faultSample(f,minOf(300L,actualDoneAt-f.now),false,false)
            f.advance(0L);f.drainUi()
        }
        f.recordSpeechReadinessEvidence()
        assertRetainedWalk(f)
        assertEquals(RuntimeMeasuredQualityGate.GpsRecoveryStage.FRESH,f.recordSensorEvidence().gpsStage)
        assertEquals(RouteDirectionGraceMode.NORMAL,f.recordSensorEvidence().directionMode)
        val resumed=f.routeSpeech().drop(before).firstOrNull { it.started&&it.done&&!it.cancelled }
        assertNotNull("New qualified original measurements must automatically produce a delivered route cue",resumed)
        assertTrue(requireNotNull(resumed).dispatchCue in f.acknowledgedRouteCues)
        assertEquals("Recovery cannot require another start",1,f.activations)
        assertEquals("Recovery cannot silently replace the installed provider route",1,f.providerRequests.size)
    }

    private fun genuineReversal(f:GuidanceFullJourneyReplayHarness,first:GuidanceFullJourneyReplayHarness.NativeSpoken) {
        assertTrue(first.started);assertFalse(first.done)
        val completedBefore=f.completedRouteSpeechCue()
        f.advance(100L);f.heading(180.0);f.gps();f.observeCameraFrame();f.advance(0)
        assertTrue("A real measured reversal must still immediately stop the exact old cue",first.cancelled)
        assertTrue(f.eventCount("TTS_STOP")>0)
        f.runtime.done(first.id);f.advance(0)
        assertEquals("Late native DONE cannot acknowledge the retired reversal cue",completedBefore,f.completedRouteSpeechCue())
        assertFalse(first.dispatchCue in f.acknowledgedRouteCues)
        recover(f,f.routeSpeech().size)
    }

    private fun sourceSilence(f:GuidanceFullJourneyReplayHarness) {
        val original=f.recordSensorEvidence()
        val end=f.now+25_000L
        while(f.now<end) {
            f.advance(300L);f.observeCameraFrame();f.stationaryStepCounterObservation();settle(f)
            val current=f.recordSensorEvidence()
            assertEquals("Clock and camera callbacks cannot restamp GPS",original.gpsOriginalAt,current.gpsOriginalAt)
            assertEquals("Clock and step callbacks cannot restamp heading",original.headingOriginalAt,current.headingOriginalAt)
            assertRetainedWalk(f)
        }
        assertNotEquals(RuntimeMeasuredQualityGate.GpsRecoveryStage.FRESH,f.recordSensorEvidence().gpsStage)
        recover(f,f.routeSpeech().size)
    }

    private fun genuineCornerAndEnd(f:GuidanceFullJourneyReplayHarness) {
        val idleUntil=f.now+10_000L
        while(f.now<idleUntil)faultSample(f,300L,false,false)
        assertEquals(0,f.currentStepCount)
        for(north in listOf(30.0,40.0,50.0,60.0))walkSample(f,0.0,north,0.0)
        for(east in listOf(10.0,20.0,30.0,40.0))walkSample(f,east,60.0,90.0)
        repeat(4) {
            if(!f.arrivalStarted)
                standAt(f,40.0,60.0,90.0)
        }
        val turn=f.routeProgressEvidence.firstOrNull{it.acceptedTurnRawAtMs!=null}
        assertNotNull("Actual counter-observed movement must qualify the protected L corner",turn)
        assertEquals(requireNotNull(turn).acceptedTurnRawAtMs,turn.acceptedTurnFilteredAtMs)
        assertTrue(requireNotNull(turn.acceptedTurnGuideProgressM) in 59.0..61.0)
        assertTrue("Actual turn action needs native START/DONE and its original cue ACK",
            f.runtime.spoken.any{it.guideIndex==1&&it.started&&it.done&&!it.cancelled&&it.dispatchCue in f.acknowledgedRouteCues})
        home.completeAutomaticArrivalAndEnd(f)
    }

    private fun walkSample(f:GuidanceFullJourneyReplayHarness,east:Double,north:Double,heading:Double) {
        f.advance(3_000L);f.observeCameraFrame();f.heading(heading);f.walkingSteps(14);f.gps(east,north)
        settle(f);f.recordRouteProgress();f.assertSameHomeOwner();assertCompletedNativeCues(f)
    }
    private fun standAt(f:GuidanceFullJourneyReplayHarness,east:Double,north:Double,heading:Double) {
        repeat(10) {
            f.advance(300L);f.observeCameraFrame();f.heading(heading);f.stationaryStepCounterObservation();f.gps(east,north)
            settle(f);f.recordRouteProgress()
        }
    }
    private fun requestEnd(f:GuidanceFullJourneyReplayHarness) {
        f.commands.executeWalkSessionVoiceAction(WalkSessionVoiceAction.REQUEST_END,f.walk.epoch)
        assertTrue(f.commands.endConfirmationPending)
        // Actual native confirmation response is completed by the SDK callback timeline.
        f.advance(3_600L);f.drainUi()
        f.commands.executeWalkSessionVoiceAction(WalkSessionVoiceAction.CONFIRM_END,f.walk.epoch)
        f.advance(0L);f.runIo();f.drainUi()
    }
    private fun retiredCallbacks(f:GuidanceFullJourneyReplayHarness,first:GuidanceFullJourneyReplayHarness.NativeSpoken) {
        requestEnd(f)
        assertEquals(WalkSessionState.ENDED,f.walk.state)
        val completed=f.completedRouteSpeechCue()
        val requests=f.runtime.spoken.size
        // Keep the exact listener captured when this retired native utterance was requested.
        f.runtime.start(first.id);f.runtime.done(first.id);f.runtime.stopCallback(first.id);f.advance(0L)
        assertEquals(WalkSessionState.ENDED,f.walk.state)
        assertFalse(f.graph.run.navigator.hasRoute());assertFalse(f.graph.run.active)
        assertEquals("Retired callback cannot consume any current Navigator completion",completed,f.completedRouteSpeechCue())
        assertEquals("Retired callbacks cannot restart output",requests,f.runtime.spoken.size)
    }
    private fun assertRetainedWalk(f:GuidanceFullJourneyReplayHarness) {
        assertEquals(WalkSessionState.ACTIVE,f.walk.state)
        assertTrue(f.graph.run.active&&f.graph.run.navigator.hasRoute())
        f.assertSameHomeOwner()
        assertEquals(0,f.currentStepCount)
    }
    private fun assertCompletedNativeCues(f:GuidanceFullJourneyReplayHarness) {
        f.runtime.spoken.filter{it.done&&!it.cancelled&&it.dispatchCue!=null}.forEach { cue ->
            assertTrue("Native DONE alone is insufficient; actual Navigator must ACK "+cue.id,
                cue.dispatchCue in f.acknowledgedRouteCues)
            assertNotNull(cue.startedAtMs);assertNotNull(cue.doneAtMs)
        }
    }
    private fun settle(f:GuidanceFullJourneyReplayHarness) {
        f.advance(0L);f.runGatewayRequests();f.runIo();f.drainUi()
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name="{0}")
        fun cases():List<Array<Any>> {
            val document=JSONObject(requireNotNull(GuidanceStationaryFullJourneyReplayTest::class.java.classLoader)
                .getResourceAsStream("navigation/guidance_stationary_full_journey_v2_20261010.json")
                ?.bufferedReader()?.use{it.readText()}?:error("Missing stationary Full v2 fixture"))
            require(document.getString("schema")=="walksafe.guidance_stationary_full_journey.v2")
            val policy=document.getJSONObject("policy")
            require(policy.getLong("gps_grace_ms")==GuidanceRecoveryTiming.GPS_GRACE_MS)
            require(policy.getLong("direction_grace_ms")==GuidanceRecoveryTiming.DIRECTION_GRACE_MS)
            require(policy.getLong("camera_grace_ms")==GuidanceRecoveryTiming.CAMERA_GRACE_MS)
            val input=document.getJSONObject("stationary_inputs")
            val native=document.getJSONObject("native_callbacks")
            val jitters=input.getJSONArray("gps_jitter_m").let { rows ->
                (0 until rows.length()).map{rows.getJSONArray(it).getDouble(0) to rows.getJSONArray(it).getDouble(1)}
            }
            fun doubles(key:String)=input.getJSONArray(key).let{values->(0 until values.length()).map{values.getDouble(it)}}
            val boundary=input.getJSONArray("boundary_pattern").let{values->(0 until values.length()).map{values.getInt(it)}}
            val rows=document.getJSONArray("cases")
            val parsed=(0 until rows.length()).map { index ->
                val row=rows.getJSONObject(index)
                StationaryFullReplayCase(row.getString("id"),row.getString("kind"),row.getString("native_phase"),
                    row.getLong("duration_ms"),jitters,doubles("gps_accuracy_m").map{it.toFloat()},
                    doubles("yaw_degrees"),doubles("heading_error_degrees"),boundary,
                    policy.getDouble("approved_gps_accuracy_m"),native.getLong("start_delay_ms"),native.getLong("done_delay_ms"))
            }
            require(parsed.map{it.id}.distinct().size==parsed.size)
            val filter=System.getProperty("walksafe.stationaryReplay.caseFilter")?.let(::Regex)
            val selected=parsed.filter{filter==null||filter.matches(it.id)}
            require(selected.isNotEmpty()){ "Stationary case selector matched nothing" }
            return selected.map{arrayOf<Any>(it)}
        }
    }
}
