package kr.co.hanium.dreamup.walksafe.runtime.vision.capture

import java.lang.reflect.Proxy
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.capture.*
import kr.co.hanium.dreamup.walksafe.depth.*
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.feedback.delivery.*
import kr.co.hanium.dreamup.walksafe.runtime.vision.frame.*
import kr.co.hanium.dreamup.walksafe.runtime.vision.unknown.*
import kr.co.hanium.dreamup.walksafe.device.DeviceGateState
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.*
import org.junit.Test

/** Uses actual consumed receipt, route/policy lock, feedback policy and reference publication. */
class CompletedTactileCapturePublisherTest {
    @Test fun constructorDoesNotQueryReceiptReferenceRiskLeaseOrClock() { assertTrue(Fixture().events.isEmpty()) }
    @Test fun exactReceiptPublishesThroughRealReferenceAndFeedbackWithOriginalClockAndLease() {
        val f=Fixture();val receipt=f.consume();f.events.clear();f.owner.publish(f.publication,f.frame,receipt,100_000_000L){true}
        assertEquals(listOf("reference","current","clear-ready","reference-diagnostic","gate","consume","remember","fresh:100","dispatch:100:false","emit","lease","current","cross:true","lease","current","dot:true"),f.events)
        assertNotNull(f.dispatch);assertEquals(100L,f.dispatch!!.policyEvaluatedAtMs)
        assertSame(f.frame,f.rememberedFrame);assertSame(receipt,f.rememberedReceipt)
        assertSame(f.lease,f.crossLease);assertSame(f.lease,f.dotLease)
        assertEquals(WarningRegionContext(f.source.owner.epoch,"geometry",f.source.owner.frameGeneration),f.warning)
    }
    @Test fun equalForeignReceiptIsRejectedBeforeRiskAndLeaseQueries() {
        val f=Fixture();val receipt=f.consume();f.events.clear()
        f.owner.publish(f.publication,f.frame,Receipt(receipt.source,receipt.maskUpgrade),100_000_000L){true}
        assertEquals(listOf("reference"),f.events);assertNull(f.dispatch)
    }
    @Test fun retiredOutputStopsAtRealReferenceAdmission() {
        val f=Fixture();val receipt=f.consume();f.events.clear();var outputReads=0
        f.owner.publish(f.publication,f.frame,receipt,100_000_000L){outputReads++;false}
        assertEquals(listOf("reference","current"),f.events);assertEquals(1,outputReads);assertNull(f.dispatch)
    }
    @Test fun replacedReceiptDoesNotReadOutputCurrentSupplier() {
        val f=Fixture();val old=f.consume();f.consume(f.source.copy(frameId=2,capturedAtElapsedRealtimeMs=101));f.events.clear()
        f.owner.publish(f.publication,f.frame,old,100_000_000L){fail("old output read");true}
        assertEquals(listOf("reference"),f.events)
    }
    @Test fun retirementInsideActualConsumeSuppressesActorAndLeavesReferenceCallbacksFenced() {
        val f=Fixture();val receipt=f.consume();f.retireInRemember=true;f.events.clear()
        f.owner.publish(f.publication,f.frame,receipt,100_000_000L){true}
        assertNull(f.dispatch);assertFalse(f.events.contains("emit"));assertTrue(f.events.contains("cross:false"));assertTrue(f.events.contains("dot:false"))
    }
    @Test fun storedRiskStartPredicateRechecksOriginalReceiptAndCallerPredicate() {
        val f=Fixture();val receipt=f.consume();var outputCurrent=true
        f.owner.publish(f.publication,f.frame,receipt,100_000_000L){outputCurrent}
        assertTrue(f.riskCurrent!!.invoke());outputCurrent=false;assertFalse(f.riskCurrent!!.invoke())
        outputCurrent=true;f.capture.retireConsumedResult();assertFalse(f.riskCurrent!!.invoke())
    }
    @Test fun wrongLeaseCastFailsAtOriginalTailAfterFeedbackWithoutDotEffect() {
        val f=Fixture();val receipt=f.consume();f.failLease=true;f.events.clear()
        try { f.owner.publish(f.publication,f.frame,receipt,100_000_000L){true};fail("lease failure") }catch(caught:IllegalArgumentException){assertSame(f.leaseFailure,caught)}
        assertNotNull(f.dispatch);assertEquals("lease",f.events.last());assertFalse(f.events.any{it.startsWith("cross")||it.startsWith("dot")})
    }
    @Test fun referencePublicationKeepsSameFrameLockAndActorEmissionOccursOutsideIt() {
        val f=Fixture();val receipt=f.consume();var calls=0;f.owner.publish(f.publication,f.frame,receipt,100_000_000L){assertEquals(calls++ == 0,Thread.holdsLock(f.lock));true}
        assertEquals(3,calls)
        assertEquals(1,f.events.count{it=="emit"})
    }
    private data class Source(override val owner:TactileDotReferenceDeliveryOwner,override val frameId:Long,
        override val capturedAtElapsedRealtimeMs:Long,override val cpuImageTimestampNs:Long?,override val geometryVersion:Long,
        override val routeId:String?,override val routeProgress:TactileActiveRouteProgress?):TactileCaptureSourceFacts
    private class Receipt(override val source:Source,override val maskUpgrade:Boolean):TactileCaptureReceiptFacts<Source>
    private class Fixture {
        val events=mutableListOf<String>();val lock=Any();val lease=Any();var failLease=false;val leaseFailure=IllegalArgumentException("lease")
        var retireInRemember=false;var dispatch:TactileFrameFeedbackDispatch?=null;var rememberedFrame:TactileSnapshotFrameResult?=null
        var rememberedReceipt:Receipt?=null;var riskCurrent:(()->Boolean)?=null;var warning:WarningRegionContext?=null
        var crossLease:Any?=null;var dotLease:Any?=null
        val navigator=RouteNavigator(nowElapsedRealtimeMs={error("route clock")})
        val frame=TactileSnapshotFrameResult(null,emptyList(),false,TactileRouteGuidanceResult(emptyList(),null,TactileRouteDecision(LocalRouteMode.TMAP,null,"empty",null)))
        val source=Source(TactileDotReferenceDeliveryOwner(WalkRuntimeEpoch("completed",0),1,2,3),1,100,77,1,navigator.currentRouteId(),navigator.currentTactileRouteProgress())
        val publication=UnknownTactilePublication(source.owner.epoch,2,3,1,1,"geometry",UnknownCaptureProcessingLease(UnknownCameraSourceLease(lease,3),1,100,null))
        val coordinator=AndroidTactileFrameCoordinator(AndroidTactileRouteGuidance(),WalkSafeFeedbackPolicy(),TactileFrameFeedbackActuator{})
        val capture=TactileFrameCaptureController(lock,{coordinator},{navigator},object:TactileCaptureRouteFacts{
            override fun navigationActiveNow()=false;override fun tmapOnRouteNow()=true
        },::Receipt,{})
        val reference=TactileReferenceDeliveryController<Receipt>(lock,object:TactileReferenceSource<Receipt>{
            override fun currentFrame(generation:Int,epoch:WalkRuntimeEpoch)=generation==source.owner.frameGeneration&&epoch==source.owner.epoch
            override fun currentReceipt(receipt:Receipt)=capture.tactileResultIsCurrent(receipt)
            override fun clearReadyRiskInput(){assertTrue(Thread.holdsLock(lock));events+="clear-ready"}
            override fun updateLocalRouteDiagnostic(message:String){assertTrue(Thread.holdsLock(lock));events+="reference-diagnostic"}
        },object:TactileReferenceOutput{
            override fun currentActor():AndroidFeedbackActuator?=null;override fun isMainThread()=true
            override fun postRetiredCancellation(action:()->Unit){fail("no registered speech")}
        },{100L})
        @Suppress("UNCHECKED_CAST") val tactile=Proxy.newProxyInstance(javaClass.classLoader,arrayOf(FrameTactileResultPeer::class.java)){_,method,args->
            val a=args?:emptyArray()
            when(method.name){
                "resultIsCurrent"->{events+="current";capture.tactileResultIsCurrent(a[0] as Receipt)}
                "consumeCurrent"->{events+="consume";capture.consumeCurrentTactileResult(a[0] as Receipt,a[1] as ()->Unit);Unit}
                "dispatchFeedback"->{events+="dispatch:${a[4]}:${a[3]}";capture.dispatchConsumedTactileFrameFeedback(a[0] as Receipt,a[1] as TactileSnapshotFrameResult,a[2] as Boolean,a[3] as Boolean,a[4] as Long,a[5] as TactileFrameFeedbackActuator,a[6] as List<UnknownObjectFeedbackBatch>,a[7] as WarningRegionContext?);Unit}
                "crosswalkReference"->{crossLease=a[1];events+="cross:${(a[2] as ()->Boolean)()}";Unit}
                "dotReference"->{dotLease=a[1];events+="dot:${(a[2] as ()->Boolean)()}";Unit}
                else->error("unexpected tactile method ${method.name}")
            }
        } as FrameTactileResultPeer<Any,Source,Receipt>
        @Suppress("UNCHECKED_CAST") val risk=Proxy.newProxyInstance(javaClass.classLoader,arrayOf(FrameRiskOutputPeer::class.java)){_,method,args->
            val a=args?:emptyArray()
            when(method.name){
                "deviceGate"->{events+="gate";assertNull(a[0]);assertNull(a[1]);assertEquals(source.owner.arGeneration,a[2]);DeviceGateState()}
                "rememberFrame"->{assertTrue(Thread.holdsLock(lock));events+="remember";rememberedFrame=a[0] as TactileSnapshotFrameResult;rememberedReceipt=a[5] as Receipt;riskCurrent=a[3] as ()->Boolean;warning=a[6] as WarningRegionContext?;if(retireInRemember)capture.retireConsumedResult();Unit}
                "freshIndependentOutputs"->{events+="fresh:${a[0]}";emptyList<UnknownObjectFeedbackBatch>()}
                "emitFeedback"->{assertFalse(Thread.holdsLock(lock));events+="emit";dispatch=a[0] as TactileFrameFeedbackDispatch;Unit}
                "currentAlertsAllowed"->false
                else->error("unexpected risk method ${method.name}")
            }
        } as FrameRiskOutputPeer<Receipt>
        val owner=CompletedTactileCapturePublisher(tactile,risk,CompletedTactileReferencePublication<Receipt>{frame,owner,receipt,current->
            events+="reference";reference.publishConsumedTactileFrameReferenceObservation(frame,owner,receipt,current)
        },{borrowed->events+="lease";if(failLease)throw leaseFailure;borrowed},.55f)
        fun consume(source:Source=this.source):Receipt {var receipt:Receipt?=null;assertTrue(capture.consumeTactileFrameResult(source,frame){_,r->receipt=r});return requireNotNull(receipt)}
    }
}
