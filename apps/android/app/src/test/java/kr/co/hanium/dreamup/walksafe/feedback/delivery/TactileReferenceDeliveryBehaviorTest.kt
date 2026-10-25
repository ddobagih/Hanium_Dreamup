package kr.co.hanium.dreamup.walksafe.feedback.delivery

import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.voice.interaction.port
import org.junit.Assert.*
import org.junit.Test

class TactileReferenceDeliveryBehaviorTest {
    @Test fun constructionDoesNotReadCurrentActorClockOrSource() {
        val f=ReferenceFixture();assertTrue(f.events.isEmpty());assertEquals(0,f.clockReads)
    }
    @Test fun acceptedDotRequiresNativeDoneAndCadenceBeginsAtCompletion() {
        val f=ReferenceFixture();f.runtime.ready();f.publishDot();assertTrue(f.dot())
        val speech=f.runtime.spoken.single();assertFalse(f.dot())
        f.runtime.start(speech.id);f.runtime.advanceBy(900);f.runtime.done(speech.id)
        f.publishDot(frame=2);assertFalse(f.dot())
        f.runtime.advanceBy(8_000);f.publishDot(frame=3);assertTrue(f.dot())
        assertEquals(2,f.runtime.spoken.size)
    }
    @Test fun rejectedStartExpiresWithoutNativeCompletionOrCadenceConsumption() {
        val f=ReferenceFixture();f.runtime.ready();f.publishDot();assertTrue(f.dot())
        val old=f.runtime.spoken.single();f.runtime.advanceBy(401);f.runtime.start(old.id);f.runtime.done(old.id)
        assertEquals(1,f.runtime.stops)
        f.publishDot(frame=2);assertTrue(f.dot());assertEquals(2,f.runtime.spoken.size)
    }
    @Test fun cancelledOldActorReceiptNeverStopsReplacementActorAndRunsOutsideFrameLock() {
        val f=ReferenceFixture();f.runtime.ready();f.publishDot();assertTrue(f.dot());f.runtime.start(f.runtime.spoken.single().id)
        val newRuntime=DeliveryNativeRuntime();val newActor=AndroidFeedbackActuator(runtime=newRuntime);newRuntime.ready()
        assertEquals(NavigationSpeechDispatchResult.ACCEPTED,newActor.speakNavigation("현재 안내",requiresExplicitTerminalCallback=true))
        newRuntime.start(newRuntime.spoken.single().id);f.currentActor=newActor
        synchronized(f.lock){f.controller.resetTactileDotReferenceDelivery();assertEquals(0,f.runtime.stops);assertEquals(1,f.retired.size)}
        f.retired.removeAt(0).invoke()
        assertEquals(1,f.runtime.stops);assertEquals(0,newRuntime.stops);assertTrue(newActor.isAppSpeechActive())
        newRuntime.done(newRuntime.spoken.single().id);assertFalse(newActor.isAppSpeechActive())
    }
    @Test fun staleActorRegistrationCancelsOnlyItsExactReceipt() {
        val f=ReferenceFixture();f.runtime.ready();f.publishDot()
        val newRuntime=DeliveryNativeRuntime();val replacement=AndroidFeedbackActuator(runtime=newRuntime);newRuntime.ready()
        f.currentActor=replacement
        assertFalse(f.dot())
        assertTrue(f.runtime.spoken.isEmpty());assertEquals(0,newRuntime.stops)
    }
    @Test fun crosswalkAdmissionAndCompletionRemainSeparateAndSourceLossDoesNotEraseStartedCompletion() {
        val f=ReferenceFixture();f.runtime.ready();f.publishCrosswalk();var sourceCurrent=true
        assertTrue(f.crosswalk{sourceCurrent});val speech=f.runtime.spoken.single();f.runtime.start(speech.id)
        sourceCurrent=false;f.controller.updateCrosswalkReferenceObservation(null,f.crossOwner,f.runtime.now)
        f.runtime.advanceBy(1_000);f.runtime.done(speech.id)
        sourceCurrent=true;f.publishCrosswalk(frame=2);assertFalse(f.crosswalk{sourceCurrent})
        f.runtime.advanceBy(8_000);f.publishCrosswalk(frame=3);assertTrue(f.crosswalk{sourceCurrent})
    }
    @Test fun referencePublicationUsesOriginalLockAndExactConsumedReceipt() {
        val f=ReferenceFixture();val current=Any();f.currentReceipt=current
        val frame=deliveryFrame(emptyList())
        assertFalse(f.controller.publishConsumedTactileFrameReferenceObservation(frame,f.owner,Any()))
        assertTrue(f.events.isEmpty())
        assertTrue(f.controller.publishConsumedTactileFrameReferenceObservation(frame,f.owner,current))
        assertEquals(listOf("ready-clear","diagnostic"),f.events)
        f.sourceCurrent=false
        assertFalse(f.controller.publishConsumedTactileFrameReferenceObservation(frame,f.owner,current))
        assertEquals(2,f.events.size)
    }
    @Test fun rejectedAdmissionCannotReserveOrSuppressLaterValidReference() {
        val f=ReferenceFixture();f.runtime.ready();f.publishCrosswalk()
        assertFalse(f.crosswalk{false});assertTrue(f.runtime.spoken.isEmpty())
        assertTrue(f.crosswalk{true});assertEquals(1,f.runtime.spoken.size)
    }
}

private class ReferenceFixture {
    val runtime=DeliveryNativeRuntime();val actor=AndroidFeedbackActuator(runtime=runtime)
    var currentActor:AndroidFeedbackActuator?=actor;val lock=Any();var sourceCurrent=true;var currentReceipt:Any?=null
    var clockReads=0;val events=mutableListOf<String>();val retired=mutableListOf<()->Unit>()
    val owner=TactileDotReferenceDeliveryOwner(WalkRuntimeEpoch("reference-walk",0),7,2,3)
    val crossOwner=CrosswalkReferenceDeliveryOwner(owner.epoch,owner.feedbackLifecycleGeneration,owner.frameGeneration,owner.arGeneration)
    val controller=TactileReferenceDeliveryController<Any>(lock,
        source=port{n,a->when(n){
            "currentFrame"->{assertTrue(Thread.holdsLock(lock));sourceCurrent&&a[0]==owner.frameGeneration&&a[1]==owner.epoch}
            "currentReceipt"->{assertTrue(Thread.holdsLock(lock));a[0]===currentReceipt}
            "clearReadyRiskInput"->{assertTrue(Thread.holdsLock(lock));events+="ready-clear";null}
            "updateLocalRouteDiagnostic"->{assertTrue(Thread.holdsLock(lock));events+="diagnostic";null}
            else->error(n)
        }},
        output=port{n,a->when(n){
            "currentActor"->currentActor;"isMainThread"->true
            "postRetiredCancellation"->{@Suppress("UNCHECKED_CAST") retired+=a[0] as ()->Unit;null}
            else->error(n)
        }},nowMs={clockReads++;runtime.now})
    fun publishDot(frame:Long=1)=controller.updateTactileDotReferenceObservation(TactileDotReferenceNotice("dot",frame,runtime.now,runtime.now+400),owner,runtime.now)
    fun publishCrosswalk(frame:Long=1)=controller.updateCrosswalkReferenceObservation(CrosswalkReferenceNotice("crosswalk",frame,runtime.now,runtime.now+400,"route",1,0),crossOwner,runtime.now)
    fun dot():Boolean{
        var reserved:TactileDotReferenceDeliveryReservation?=null
        return controller.dispatchTactileDotReference(owner,{runtime.now},{true},onReserved={reserved=it},speak={notice,current,done,failed->
            actor.speakAdvisoryWithReceipt(notice.message,notice.validUntilElapsedRealtimeMs,current,
                onRegistered={controller.registerTactileDotReferenceSpeech(requireNotNull(reserved),actor,it,{true})},
                onCompleted=done,onFailed=failed,onCancelled=failed)
        })
    }
    fun crosswalk(current:()->Boolean)=controller.dispatchCrosswalkReference(crossOwner,{runtime.now},current,
        routeIsCurrent={it.isValidForRoute("route",TactileActiveRouteProgress(1,0))},
        speak={notice,valid,done,failed->actor.speakAdvisory(notice.message,validUntilMs=notice.validUntilElapsedRealtimeMs,isStillValid=valid,onCompleted=done,onFailed=failed)})
}
