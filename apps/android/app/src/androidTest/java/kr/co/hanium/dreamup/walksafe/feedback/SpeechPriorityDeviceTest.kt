package kr.co.hanium.dreamup.walksafe.feedback

import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Native Korean TTS, synthetic speech requests. No camera, walking session, or account mutation.
 * onDone proves engine completion only; human audibility/comprehension is not measured. */
class SpeechPriorityDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private class Terminal {
        val done=AtomicInteger();val failed=AtomicInteger();val cancelled=AtomicInteger()
        fun complete(){done.incrementAndGet()}
        fun fail(){failed.incrementAndGet()}
        fun cancel(){cancelled.incrementAndGet()}
        fun assertCounts(d:Int,f:Int,c:Int){assertEquals(d,done.get());assertEquals(f,failed.get());assertEquals(c,cancelled.get())}
    }
    private fun ready():AndroidFeedbackActuator {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowSpeechPriorityTest")=="true")
        val latch=CountDownLatch(1);val available=AtomicBoolean(false)
        lateinit var a:AndroidFeedbackActuator
        instrumentation.runOnMainSync { a=AndroidFeedbackActuator(instrumentation.targetContext,
            hapticAllowed={false},onOfflineKoreanSpeechReady={available.set(true);latch.countDown()},
            onOfflineKoreanSpeechUnavailable={latch.countDown()}) }
        try {assertTrue("TTS init timed out",latch.await(25,TimeUnit.SECONDS));assertTrue("Korean TTS unavailable",available.get())}
        catch(e:Throwable){instrumentation.runOnMainSync{a.close()};throw e}
        return a
    }
    private fun engine(a:AndroidFeedbackActuator):TextToSpeech {
        val f=AndroidFeedbackActuator::class.java.getDeclaredField("textToSpeech");f.isAccessible=true
        return f.get(a) as TextToSpeech
    }
    private fun started(a:AndroidFeedbackActuator,prefix:String):Boolean {
        val f=AndroidFeedbackActuator::class.java.getDeclaredField("startedUtterances");f.isAccessible=true
        return (f.get(a) as Set<*>).any{it.toString().startsWith("$prefix-")}
    }
    private fun <T> main(action:()->T):T {
        val value=AtomicReference<T>();instrumentation.runOnMainSync{value.set(action())};return value.get()
    }
    private fun await(label:String,timeout:Long=8_000,condition:()->Boolean){
        val end=SystemClock.elapsedRealtime()+timeout
        while(SystemClock.elapsedRealtime()<end){if(condition())return;Thread.sleep(30)}
        assertTrue(label,condition())
    }
    private fun interaction(a:AndroidFeedbackActuator,message:String,t:Terminal)=a.speakHomeCommandInteraction(
        message,onFailed=t::fail,onCompleted=t::complete,onCancelled=t::cancel)
    private fun navigation(a:AndroidFeedbackActuator,message:String,t:Terminal)=a.speakNavigation(
        message,onCompleted=t::complete,onFailed=t::fail,onCancelled=t::cancel,requiresExplicitTerminalCallback=true)
    private fun report(s:String)=instrumentation.sendStatus(0,Bundle().apply{putString("stream","SPEECH_PRIORITY $s\n")})

    @Test(timeout=90_000) fun playingRouteThenInteractionArePreemptedAndStaleCancellationCannotStopRisk() {
        val a=ready();val nav=Terminal();val user=Terminal();val risk=Terminal();val suppressed=Terminal();val resumed=Terminal()
        try {
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,main {navigation(a,
                "시험용 경로 안내를 읽고 있습니다. 실제 이동 지시가 아닙니다. ".repeat(8),nav)})
            await("Native route must actually be speaking"){main{engine(a).isSpeaking && started(a,"nav")}}
            main{nav.assertCounts(0,0,0);assertEquals(NavigationSpeechDispatchResult.ACCEPTED,interaction(a,
                "시험용 사용자 응답입니다. 음성 우선순위를 확인하는 중입니다. ".repeat(8),user))}
            await("Route must be cancelled once"){nav.cancelled.get()==1}
            await("Interaction must own native speech"){main{engine(a).isSpeaking && started(a,"interaction")}}
            main {
                user.assertCounts(0,0,0)
                val action=FeedbackAction("synthetic-risk","시험 경고입니다. 이 음성은 실제 장애물 탐지 결과가 아닙니다.",
                    MessageLevel.WARNING,null,"synthetic-priority-test")
                assertEquals(NavigationSpeechDispatchResult.ACCEPTED,a.emit(action,risk::complete,risk::fail,{true},risk::cancel).speech)
                assertEquals(NavigationSpeechDispatchResult.SUPPRESSED,interaction(a,"경고 중 사용자 응답 시험",suppressed))
                assertEquals(NavigationSpeechDispatchResult.SUPPRESSED,navigation(a,"경고 중 경로 안내 시험",suppressed))
                // Calls from the retired UI/route owner must not stop its higher-priority replacement.
                assertFalse(a.cancelNavigationSpeech());a.cancelCommandInteraction()
            }
            await("Interaction must be cancelled once"){user.cancelled.get()==1}
            await("Risk must reach actual onDone",20_000){risk.done.get()==1}
            main{nav.assertCounts(0,0,1);user.assertCounts(0,0,1);risk.assertCounts(1,0,0);suppressed.assertCounts(0,0,0)
                assertEquals(NavigationSpeechDispatchResult.ACCEPTED,navigation(a,"시험을 마쳤습니다.",resumed))}
            await("Fresh route must complete",15_000){resumed.done.get()==1}
            main{nav.assertCounts(0,0,1);user.assertCounts(0,0,1);risk.assertCounts(1,0,0);suppressed.assertCounts(0,0,0);resumed.assertCounts(1,0,0);assertFalse(a.isAppSpeechActive())}
            report("route_cancel=1 interaction_cancel=1 risk_actual_onDone=1 lower_priority_suppressed=true stale_cancel_safe=true fresh_route_done=1 human_audibility=NOT_TESTED")
        } finally {main{a.close()}}
    }

    @Test(timeout=90_000) fun closeWhileSpeakingCannotCompleteOldOwnerAndNewOwnerCanSpeak() {
        val old=ready();val oldTerminal=Terminal();var next:AndroidFeedbackActuator?=null
        try {
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,main{interaction(old,
                "시험용 긴 안내입니다. 화면 소유자가 닫힌 뒤에는 완료로 처리되면 안 됩니다. ".repeat(8),oldTerminal)})
            await("Old owner must actually speak"){main{engine(old).isSpeaking && started(old,"interaction")}}
            main{old.close()}
            // close() retires the owner and clears callbacks; unlike cancellation it deliberately
            // emits no terminal. Main lifecycle separately invalidates command/feedback ownership.
            // Observe this exact contract through the complete lifetime of the next real utterance.
            main{oldTerminal.assertCounts(0,0,0)}
            val afterClose=listOf(oldTerminal.done.get(),oldTerminal.failed.get(),oldTerminal.cancelled.get())
            assertEquals(0,afterClose[0]);assertFalse(main{old.isAppSpeechActive()})
            val fresh=ready();next=fresh;val terminal=Terminal()
            main{assertEquals(NavigationSpeechDispatchResult.ACCEPTED,interaction(fresh,"새 음성 안내 시험을 완료했습니다.",terminal))}
            await("New owner must receive actual onDone",15_000){terminal.done.get()==1}
            main{terminal.assertCounts(1,0,0);assertEquals(afterClose,listOf(oldTerminal.done.get(),oldTerminal.failed.get(),oldTerminal.cancelled.get()));assertFalse(fresh.isAppSpeechActive())}
            report("closed_owner_terminals=0 callbacks_retired=true fresh_engine_actual_onDone=1 stale_callback_after_new_speech=NONE human_audibility=NOT_TESTED")
        } finally {main{old.close();next?.close()}}
    }
    @Test(timeout=90_000) fun nativePermanentFocusLossFailsOnceAndExplicitReplayCompletes() =
        verifyNativeFocusLoss(android.media.AudioManager.AUDIOFOCUS_GAIN)

    @Test(timeout=90_000) fun nativeTransientFocusLossFailsOnceAndExplicitReplayCompletes() =
        verifyNativeFocusLoss(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)

    @Test(timeout=90_000) fun nativeDuckingFocusLossFailsOnceAndExplicitReplayCompletes() =
        verifyNativeFocusLoss(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)

    private fun verifyNativeFocusLoss(gain:Int) {
        val a=ready();val interrupted=Terminal();val replay=Terminal()
        val focusLost=AtomicInteger()
        val manager=instrumentation.targetContext.getSystemService(android.media.AudioManager::class.java)
        val competing=android.media.AudioFocusRequest.Builder(gain)
            .setAudioAttributes(android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener({ },android.os.Handler(android.os.Looper.getMainLooper()))
            .build()
        val message="음성 중단 복구를 확인하는 시험입니다. 실제 이동 안내가 아닙니다."
        try {
            main {
                a.speechOutputListener={if(it.outcome==SpeechOutputOutcome.FOCUS_LOST) focusLost.incrementAndGet()}
                assertEquals(NavigationSpeechDispatchResult.ACCEPTED,interaction(a,message,interrupted))
            }
            await("Actual native TTS must start before focus changes"){main{engine(a).isSpeaking&&started(a,"interaction")}}
            assertEquals(android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED,main{manager.requestAudioFocus(competing)})
            await("Native focus loss must fail the unfinished utterance"){interrupted.failed.get()==1}
            await("Interrupted native TTS must stop"){main{!engine(a).isSpeaking&&!a.isAppSpeechActive()}}
            main{interrupted.assertCounts(0,1,0);assertEquals(1,focusLost.get())}
            main{manager.abandonAudioFocusRequest(competing)}
            Thread.sleep(400)
            main{assertFalse("Focus return must not automatically resume old speech",a.isAppSpeechActive())
                assertEquals(NavigationSpeechDispatchResult.ACCEPTED,interaction(a,message,replay))}
            await("Same text explicit replay must finish",20_000){replay.done.get()==1}
            main{interrupted.assertCounts(0,1,0);replay.assertCounts(1,0,0);assertEquals(1,focusLost.get());assertFalse(a.isAppSpeechActive())}
            report("native_competing_focus_gain=$gain focus_lost=1 failed=1 old_done=0 explicit_same_text_done=1 automatic_resume=false human_audibility=NOT_TESTED other_uid_call_not_tested=true")
        } finally {main{manager.abandonAudioFocusRequest(competing);a.close()}}
    }

}
