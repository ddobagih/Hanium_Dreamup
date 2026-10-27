package kr.co.hanium.dreamup.walksafe.feedback.delivery

import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.voice.interaction.port
import org.junit.Assert.*
import org.junit.Test

/** Production policy + production actuator native START/DONE path; fake only device/effect boundaries. */
class RiskFeedbackDeliveryBehaviorTest {
    @Test fun distanceFollowsNativeStopCompletionAndUsesCurrentSourceOnly() {
        for (expire in listOf(false, true)) {
            val f = RiskFixture(); f.runtime.ready()
            val action = f.reserve(level = MessageLevel.STOP)
            val measured = deliveryRiskOutput().copy(trackId = action.trackId,
                userFacing = UserFacingDepth(null, MessageLevel.STOP, action.message), measuredSurfaceRangeM = 1.25f)
            val dispatch = f.dispatch(action).copy(distanceOutputsByDeliveryKey = mapOf(action.deliveryKey to measured))
            f.controller.emitTactileFrameFeedback(dispatch)
            val warning = f.runtime.spoken.single()
            assertEquals(action.message, warning.message)
            f.runtime.start(warning.id)
            if (expire) f.runtime.advanceBy(701)
            f.runtime.done(warning.id)
            assertEquals(if (expire) 1 else 2, f.runtime.spoken.size)
            if (!expire) assertEquals("카메라에서 감지된 표면까지 약 1.3미터입니다.", f.runtime.spoken.last().message)
        }
    }

    @Test fun controllerConstructionReadsNoAuthorityClockOrSdkAndSchedulesNothing() {
        val f=RiskFixture()
        assertTrue(f.events.isEmpty());assertEquals(0,f.clockReads);assertTrue(f.timers.isEmpty())
    }
    @Test fun acceptedSpeechDoesNotConfirmUntilNativeDoneAndCompletionCanOutliveCapture() {
        val f=RiskFixture();f.runtime.ready();val action=f.deliver()
        assertTrue(f.policy.hasClaimedFeedbackDelivery(action));assertFalse(f.events.contains("risk_feedback_emitted"))
        val speech=f.runtime.spoken.single();f.runtime.start(speech.id);f.runtime.advanceBy(701)
        f.runtime.done(speech.id);f.runtime.done(speech.id)
        assertEquals(1,f.events.count{it=="risk_feedback_emitted"});assertFalse(f.policy.hasClaimedFeedbackDelivery(action))
        assertFalse(f.policy.canSpeakNavigation(f.runtime.now))
    }
    @Test fun lifetimeEpochAndMountingRetirementDoNotConfirmLateDone() {
        for(retire in listOf<(RiskFixture)->Unit>({it.generation++},{it.epoch=WalkRuntimeEpoch("new",0)},{it.mounted=false})) {
            val f=RiskFixture();f.runtime.ready();val action=f.deliver();val speech=f.runtime.spoken.single();f.runtime.start(speech.id)
            retire(f);f.runtime.done(speech.id)
            assertFalse(f.events.contains("risk_feedback_emitted"));assertFalse(f.policy.hasClaimedFeedbackDelivery(action))
        }
    }
    @Test fun riskInterruptsGatewayBeforeRecognitionButInformationDoesNot() {
        val f=RiskFixture();f.runtime.ready();f.recognizing=true;f.deliver()
        assertEquals(listOf("gateway:risk_feedback","recognizer"),f.events.filter{it.startsWith("gateway")||it=="recognizer"})
        val info=RiskFixture();info.runtime.ready();info.recognizing=true
        info.deliver(level=MessageLevel.INFO)
        assertFalse(info.events.any{it.startsWith("gateway")||it=="recognizer"})
        assertTrue(info.runtime.spoken.isEmpty())
    }
    @Test fun failedNativeSpeechUsesOnlyAcceptedVibrationTimerAndNeverDispatchAck() {
        val f=RiskFixture();f.runtime.acceptsVibration=true;f.runtime.ready();val action=f.deliver(level=MessageLevel.WARNING)
        val speech=f.runtime.spoken.single();f.runtime.start(speech.id);f.runtime.nativeError(speech.id)
        assertEquals(1,f.timers.size);assertFalse(f.events.contains("risk_feedback_emitted"))
        val (task,delay)=f.timers.entries.single();assertTrue(delay>=1)
        f.runtime.advanceBy(delay);task.run()
        assertEquals(1,f.events.count{it=="risk_feedback_emitted"});assertFalse(f.policy.hasClaimedFeedbackDelivery(action))
        val rejected=RiskFixture();rejected.runtime.ready();val failed=rejected.deliver()
        rejected.runtime.nativeError(rejected.runtime.spoken.single().id)
        assertTrue(rejected.timers.isEmpty());assertFalse(rejected.policy.hasClaimedFeedbackDelivery(failed))
    }
    @Test fun oldTerminalRunnableCannotConfirmOrClearTheReplacementTimer() {
        val f=RiskFixture();f.runtime.acceptsVibration=true;val first=f.deliver(level=MessageLevel.WARNING)
        val old=f.timers.keys.single();f.controller.cancelPendingFeedbackTerminalResolution()
        f.runtime.advanceBy(600);val second=f.deliver(track="new-risk",level=MessageLevel.STOP)
        val latest=f.timers.keys.single();assertNotSame(old,latest)
        old.run();assertFalse(f.events.contains("risk_feedback_emitted"));assertTrue(f.policy.hasClaimedFeedbackDelivery(second))
        assertFalse(f.policy.hasClaimedFeedbackDelivery(first));latest.run()
        assertEquals(1,f.events.count{it=="risk_feedback_emitted"})
    }
    @Test fun rejectedReplacementPreservesClaimAndTimerOfAcceptedOldWaveform() {
        val f=RiskFixture();f.runtime.acceptsVibration=true
        val first=f.reserve(level=MessageLevel.STOP,ttc=2_000,distance=1.1f)
        f.controller.emitTactileFrameFeedback(f.dispatch(first));val old=f.timers.keys.single()
        f.runtime.advanceBy(800);val proposal=f.reserve(level=MessageLevel.STOP,ttc=250,distance=1.0f)
        assertNotNull(proposal.replacesDeliveryOwner)
        f.controller.emitTactileFrameFeedback(f.dispatch(proposal),startIsCurrent={false})
        assertSame(old,f.timers.keys.single());assertTrue(f.policy.hasClaimedFeedbackDelivery(first))
        assertFalse(f.policy.hasClaimedFeedbackDelivery(proposal))
        old.run();assertEquals(1,f.events.count{it=="risk_feedback_emitted"})
    }

    @Test fun expiredStartAndRevokedGateRejectBeforeNativeSpeech() {
        for(retire in listOf<(RiskFixture)->Unit>({it.runtime.advanceBy(701)},{it.gate=false},{it.foreground=false})) {
            val f=RiskFixture();f.runtime.ready();val action=f.reserve();retire(f)
            f.controller.emitTactileFrameFeedback(f.dispatch(action))
            assertTrue(f.runtime.spoken.isEmpty());assertFalse(f.policy.hasClaimedFeedbackDelivery(action))
        }
    }
    @Test fun readyReevaluationIsLatestSlotOnlyAndRechecksReentrantProducerIdentity() {
        val f=RiskFixture();f.runtime.ready();var replaces=false
        f.controller.rememberSpeechReadyRiskInput(deliveryFrame(listOf(deliveryRiskOutput())),false,false,
            producerIsCurrent={ if(!replaces){replaces=true;f.controller.clearSpeechReadyRiskInput()};true },
            currentGateAllowsAlerts={true},nowElapsedRealtimeMs={f.runtime.now})
        f.controller.reevaluateCurrentRiskAfterSpeechReady(f.actor,f.generation,f.runtime.now)
        assertTrue(replaces);assertEquals(0,f.frameDispatches);assertTrue(f.runtime.spoken.isEmpty())
    }
    @Test fun readyReevaluatesRealFrameCoordinatorForCautionButNeverReplaysInfo() {
        for(level in listOf(MessageLevel.INFO,MessageLevel.CAUTION)) {
            val f=RiskFixture();f.runtime.ready()
            val depth=deliveryRiskOutput().copy(userFacing=UserFacingDepth(null,level,"현재 위험 $level"))
            f.controller.rememberSpeechReadyRiskInput(deliveryFrame(listOf(depth)),false,false,
                producerIsCurrent={true},currentGateAllowsAlerts={f.gate},nowElapsedRealtimeMs={f.runtime.now})
            f.controller.reevaluateCurrentRiskAfterSpeechReady(f.actor,f.generation,f.runtime.now)
            assertEquals(if(level==MessageLevel.INFO)0 else 1,f.frameDispatches)
            assertEquals(if(level==MessageLevel.INFO)0 else 1,f.runtime.spoken.size)
            if(level==MessageLevel.CAUTION){val speech=f.runtime.spoken.single();f.runtime.start(speech.id);f.runtime.done(speech.id);assertTrue(f.events.contains("risk_feedback_emitted"))}
        }
    }
    @Test fun sourceReceiptRemainsTypedAndGateFailureDoesNotDispatchReadyFrame() {
        val f=RiskFixture();f.runtime.ready();val receipt=Any();f.currentReceipt=receipt
        f.controller.rememberSpeechReadyRiskInput(deliveryFrame(listOf(deliveryRiskOutput())),false,false,
            producerIsCurrent={true},currentGateAllowsAlerts={f.gate},sourceReceipt=receipt,nowElapsedRealtimeMs={f.runtime.now})
        f.gate=false;f.controller.reevaluateCurrentRiskAfterSpeechReady(f.actor,f.generation,f.runtime.now)
        assertEquals(0,f.frameDispatches)
        f.gate=true;f.controller.reevaluateCurrentRiskAfterSpeechReady(f.actor,f.generation,f.runtime.now)
        assertSame(receipt,f.lastDispatchedReceipt);assertEquals(1,f.frameDispatches)
    }
}

internal class RiskFixture {
    val runtime=DeliveryNativeRuntime();val policy=WalkSafeFeedbackPolicy();val lock=Any();val mountingLock=Any()
    var generation=7;var foreground=true;var epoch:WalkRuntimeEpoch?=WalkRuntimeEpoch("risk-walk",0)
    var gate=true;var mounted=true;var recognizing=false;var clockReads=0;var frameDispatches=0
    var currentReceipt:Any?=null;var lastDispatchedReceipt:Any?=null
    val events=mutableListOf<String>();val timers=linkedMapOf<Runnable,Long>()
    val actor=AndroidFeedbackActuator(runtime=runtime,hapticAllowed={true})
    var currentActor:AndroidFeedbackActuator?=actor
    val coordinator: AndroidTactileFrameCoordinator = createProductionAndroidTactileFrameCoordinator(policy,TactileFrameFeedbackActuator { deliverDispatch(it) })
    val controller: RiskFeedbackDeliveryController<Any> = RiskFeedbackDeliveryController<Any>(policy,lock,mountingLock,
        admission=port { n,a -> when(n){
            "generation"->generation;"lifecycleCurrent"->foreground&&a[0]==generation
            "runtimeEpochOrNull"->epoch;"runtimeEpochCurrent"->a[0]==epoch
            "evaluateSafety"->WalkRuntimeSafetyDecision(WalkRuntimeSafetyDisposition.ALLOW_SAFETY_OUTPUT,epoch)
            "qualitativeAllowsWork","deviceGateAllowsAlerts"->gate
            "freshUnknownFeedbackBatches"->emptyList<UnknownObjectFeedbackBatch>();"unknownFeedbackTrackIsCurrent"->true
            "phoneMountingOutputsAllowed"->{assertTrue(Thread.holdsLock(mountingLock));mounted}
            else->error(n)
        } },
        output=port { n,a->when(n){
            "currentActor","ensureActor"->currentActor
            "reconcileTalkBackRiskTracks"->{events+="reconcile";null}
            "guidanceVoiceInputActive","recognitionActive"->recognizing
            "cancelGatewaySpeechInteraction"->{events+="gateway:${a[0]}";null}
            "cancelVoiceCommandRecognition"->{events+="recognizer";recognizing=false;null}
            else->error(n)
        } },
        scheduler=port {n,a->when(n){
            "nowMs"->{clockReads++;runtime.now};"isMainThread","hasTerminalView"->true
            "runOnUiThread"->{@Suppress("UNCHECKED_CAST") (a[0] as ()->Unit).invoke();null}
            "postTerminal"->{timers[a[0] as Runnable]=a[1] as Long;null}
            "removeTerminal"->{timers.remove(a[0]);null}
            else->error(n)
        } },
        log=port{n,a->when(n){"initialized"->true;"recordEvent"->{events+=a[0] as String;null};else->error(n)}},
        frames=object:RiskFeedbackFrameDelivery<Any>{
            override fun deliver(dispatch:TactileFrameFeedbackDispatch)=deliverDispatch(dispatch)
            override fun dispatchTactileFrameFeedback(frame:TactileSnapshotFrameResult,stale:Boolean,deviceGateAllowsAlerts:Boolean,
                nowMs:Long,feedbackActuatorOverride:TactileFrameFeedbackActuator?,independentFreshOutputs:List<UnknownObjectFeedbackBatch>,warningContext:WarningRegionContext?):TactileFrameFeedbackDispatch {
                frameDispatches++
                return coordinator.dispatchFeedback(frame,stale,deviceGateAllowsAlerts,nowMs,feedbackActuatorOverride,independentFreshOutputs,warningContext)
            }
            override fun dispatchConsumedTactileFrameFeedback(receipt:Any,frame:TactileSnapshotFrameResult,stale:Boolean,deviceGateAllowsAlerts:Boolean,
                nowMs:Long,feedbackActuatorOverride:TactileFrameFeedbackActuator?,independentFreshOutputs:List<UnknownObjectFeedbackBatch>,warningContext:WarningRegionContext?):TactileFrameFeedbackDispatch? {
                if(receipt!==currentReceipt)return null
                lastDispatchedReceipt=receipt
                return dispatchTactileFrameFeedback(frame,stale,deviceGateAllowsAlerts,nowMs,feedbackActuatorOverride,independentFreshOutputs,warningContext)
            }
        })
    fun reserve(track:String="risk",level:MessageLevel=MessageLevel.CAUTION,ttc:Long?=null,distance:Float=1f):FeedbackAction = requireNotNull(policy.evaluate(
        FeedbackCandidate(track,"위험 $track",level,DepthSource.ARCORE_RAW_DEPTH,.99f,runtime.now,10,1000,false,
            validUntilMs=runtime.now+700,timeToCollisionMs=ttc,riskDistanceM=distance,observedAtElapsedRealtimeMs=runtime.now,observationMaxAgeMs=700),true,runtime.now))
    fun dispatch(action:FeedbackAction)=TactileFrameFeedbackDispatch(setOf(action.trackId),setOf(action.deliveryKey),true,action,runtime.now)
    fun deliver(track:String="risk",level:MessageLevel=MessageLevel.CAUTION):FeedbackAction=reserve(track,level).also{controller.emitTactileFrameFeedback(dispatch(it))}
    private fun deliverDispatch(dispatch:TactileFrameFeedbackDispatch): Unit =controller.emitTactileFrameFeedback(dispatch)
}
