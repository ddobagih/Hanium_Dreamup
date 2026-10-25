package kr.co.hanium.dreamup.walksafe.navigation.capture

import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.util.Locale
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.feedback.delivery.*
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.junit.Assert.*
import org.junit.Test

/** Actual reference policy + actual Actor; native START/DONE/UI dispatch are deterministic effects. */
class TactileDotReferenceDispatchControllerTest {
    @Test fun constructionDoesNotObserveAnyGateClockActorOrLease() {
        val f = Fixture(); assertTrue(f.events.isEmpty()); assertEquals(0, f.clockReads); assertEquals(0, f.actorQueries)
    }
    @Test fun staleQueuedSourceStillReadsOriginalSafetyClockFirstAndStopsBeforeOwnerQueries() {
        val f = Fixture(); f.readyAndPublish(); var accepted: Boolean? = null
        var current = true
        f.speak(source = { current }, accepted = { accepted = it }); assertTrue(f.events.isEmpty())
        current = false; f.deliverUi()
        assertEquals(false, accepted); assertEquals(listOf("clock", "safety"), f.events)
        assertEquals(0, f.actorQueries); assertTrue(f.runtime.spoken.isEmpty())
    }
    @Test fun feedbackOwnerRejectionDoesNotReadFrameLeaseOrMetric() {
        val f = Fixture(); f.readyAndPublish(); f.feedbackCurrent = false; f.speak(); f.deliverUi()
        assertEquals(listOf("clock", "safety", "feedback"), f.events)
        assertTrue(f.runtime.spoken.isEmpty())
    }
    @Test fun nativeLeaseUsesExactIdentityAndDoesNotBorrowReplacementAuthority() {
        val f = Fixture(); f.readyAndPublish(); f.speak(); f.liveLease = Any(); f.deliverUi()
        assertEquals(listOf("clock", "safety", "feedback", "frame", "lease", "observe"), f.events)
        assertFalse(f.events.contains("metric")); assertTrue(f.runtime.spoken.isEmpty())
    }
    @Test fun metricGateRejectionPrecedesDeviceStartupAndActorQueries() {
        val f = Fixture(); f.readyAndPublish(); f.metricAllowed = false; f.speak(); f.deliverUi()
        assertTrue(f.events.contains("metric")); assertFalse(f.events.contains("device"))
        assertEquals(0, f.actorQueries); assertTrue(f.runtime.spoken.isEmpty())
    }
    @Test fun unsafeEpochOrDispositionNeverDispatchesEvenAfterOtherStartupQueries() {
        listOf(true, false).forEach { mismatch ->
            val f = Fixture(); f.readyAndPublish()
            f.safety = if (mismatch) WalkRuntimeSafetyDecision(WalkRuntimeSafetyDisposition.ALLOW_SAFETY_OUTPUT,
                WalkRuntimeEpoch("other", 0)) else WalkRuntimeSafetyDecision(WalkRuntimeSafetyDisposition.SAFE_STOP_LATCHED, f.owner.epoch)
            f.speak(); f.deliverUi(); assertTrue(f.events.contains("tts")); assertEquals(0, f.actorQueries)
            assertFalse(f.events.contains("voice-input")); assertTrue(f.runtime.spoken.isEmpty())
        }
    }
    @Test fun admissionIsNotNativeDoneAndCompletionCadenceStartsAtActualDone() {
        val f = Fixture(); f.readyAndPublish(); var accepted: Boolean? = null
        f.speak(accepted = { accepted = it }); f.deliverUi(); assertEquals(true, accepted)
        val first = f.runtime.spoken.single().id
        f.publish(2); f.speak(); f.deliverUi(); assertEquals(1, f.runtime.spoken.size)
        f.runtime.start(first); f.runtime.advanceBy(900); f.runtime.done(first)
        f.publish(3); f.speak(); f.deliverUi(); assertEquals(1, f.runtime.spoken.size)
        f.runtime.advanceBy(8_000); f.publish(4); f.speak(); f.deliverUi(); assertEquals(2, f.runtime.spoken.size)
    }
    @Test fun expiredStartCancelsActualReceiptAndDoesNotConsumeCompletionCadence() {
        val f = Fixture(); f.readyAndPublish(); f.speak(); f.deliverUi()
        val original = f.runtime.spoken.single().id
        f.runtime.advanceBy(401); f.runtime.start(original); f.runtime.done(original)
        assertEquals(1, f.runtime.stops)
        f.publish(2); f.speak(); f.deliverUi(); assertEquals(2, f.runtime.spoken.size)
    }
    @Test fun sourceLossCancelsOriginalReceiptOutsideSharedFrameLock() {
        val f = Fixture(); f.readyAndPublish(); f.speak(); f.deliverUi()
        val original = f.runtime.spoken.single().id; f.runtime.start(original)
        synchronized(f.lock) { f.reference.updateTactileDotReferenceObservation(null, f.owner, f.runtime.now)
            assertEquals(0, f.runtime.stops) }
        assertEquals(1, f.retired.size); f.retired.removeAt(0).invoke(); assertEquals(1, f.runtime.stops)
        f.publish(2); f.speak(); f.deliverUi(); assertEquals(2, f.runtime.spoken.size)
    }
    @Test fun actorReplacementDuringRegistrationCannotCancelReplacementSpeech() {
        val f = Fixture(); f.readyAndPublish()
        val otherRuntime = NativeRuntime(); val replacement = AndroidFeedbackActuator(runtime = otherRuntime); otherRuntime.ready()
        f.currentActor = replacement
        f.speak(); f.deliverUi(); assertTrue(f.runtime.spoken.isEmpty()); assertEquals(0, otherRuntime.stops)
        f.currentActor = f.actor; f.publish(2); f.speak(); f.deliverUi(); assertEquals(1, f.runtime.spoken.size)
    }
    @Test fun completionRechecksOwnerWithoutRequeryingMetricSourceOrDevicePolicy() {
        val f = Fixture(); f.readyAndPublish(); f.speak(); f.deliverUi()
        val id = f.runtime.spoken.single().id; f.runtime.start(id); f.events.clear()
        f.feedbackCurrent = false; f.runtime.done(id)
        assertEquals(listOf("feedback"), f.events)
        f.feedbackCurrent = true; f.publish(2); f.speak(); f.deliverUi(); assertEquals(2, f.runtime.spoken.size)
    }
    @Test fun rejectedVoiceInputDoesNotReserveCurrentReferenceOrInitializeActor() {
        val f = Fixture(); f.readyAndPublish(); f.voiceInputActive = true; f.speak(); f.deliverUi()
        assertEquals(0, f.actorQueries); assertTrue(f.runtime.spoken.isEmpty())
        f.voiceInputActive = false; f.speak(); f.deliverUi(); assertEquals(1, f.runtime.spoken.size)
    }

    private class Fixture {
        val lock = Any(); val runtime = NativeRuntime(); val actor = AndroidFeedbackActuator(runtime = runtime)
        var currentActor: AndroidFeedbackActuator? = actor; var liveLease = Any(); val originalLease = liveLease
        val owner = TactileDotReferenceDeliveryOwner(WalkRuntimeEpoch("reference", 0), 7, 2, 3)
        val ui = mutableListOf<() -> Unit>(); val retired = mutableListOf<() -> Unit>(); val events = mutableListOf<String>()
        var feedbackCurrent = true; var frameCurrent = true; var metricAllowed = true; var voiceInputActive = false
        var actorQueries = 0; var clockReads = 0
        var safety = WalkRuntimeSafetyDecision(WalkRuntimeSafetyDisposition.ALLOW_SAFETY_OUTPUT, owner.epoch)
        val reference = TactileReferenceDeliveryController<Any>(lock,
            object : TactileReferenceSource<Any> {
                override fun currentFrame(generation: Int, epoch: WalkRuntimeEpoch) = frameCurrent
                override fun currentReceipt(receipt: Any) = true
                override fun clearReadyRiskInput() = Unit
                override fun updateLocalRouteDiagnostic(message: String) = Unit
            }, object : TactileReferenceOutput {
                override fun currentActor() = currentActor
                override fun isMainThread() = true
                override fun postRetiredCancellation(action: () -> Unit) { retired += action }
            }, { error("Dispatch must use the explicit original clock") })
        val controller = TactileDotReferenceDispatchController({ reference },
            object : TactileDotDispatchAuthority<Any> {
                override fun feedbackLifecycleCurrent(generation: Int): Boolean { events += "feedback"; return feedbackCurrent && generation == owner.feedbackLifecycleGeneration }
                override fun frameCurrent(generation: Int, epoch: WalkRuntimeEpoch): Boolean { events += "frame"; return frameCurrent && generation == owner.frameGeneration && epoch == owner.epoch }
                override fun leaseCurrent(lease: Any, observe: () -> Any): Boolean { events += "lease"; return lease === observe() }
                override fun safety(nowMs: Long): WalkRuntimeSafetyDecision { events += "safety"; assertEquals(runtime.now, nowMs); return safety }
                override fun metricOutputAllowed(arGeneration: Long, observe: () -> Any): Boolean { events += "metric"; return metricAllowed && arGeneration == owner.arGeneration }
                override fun deviceGate(arGeneration: Long, observe: () -> Any): DeviceGateState { events += "device"; return DeviceGateState(true, true, true, true, true, true) }
                override fun voiceFeatureEnabled(): Boolean { events += "voice-feature"; return true }
                override fun offlineTtsAllowed(): Boolean { events += "tts"; return true }
                override fun guidanceVoiceInputActive(): Boolean { events += "voice-input"; return voiceInputActive }
            }, TactileDotDispatchActor { actorQueries++; actor })
        fun publish(frame: Long = 1) { reference.updateTactileDotReferenceObservation(
            TactileDotReferenceNotice("dot", frame, runtime.now, runtime.now + 400), owner, runtime.now) }
        fun readyAndPublish() { runtime.ready(); publish(); events.clear() }
        fun speak(source: (() -> Boolean)? = null, accepted: ((Boolean) -> Unit)? = null) {
            controller.maybeSpeak(owner, originalLease, { events += "observe"; liveLease },
                { events += "clock"; clockReads++; runtime.now }, { ui += it }, accepted, source)
        }
        fun deliverUi() { ui.removeAt(0).invoke(); runtime.advanceBy(0) }
    }
    private class NativeRuntime : FeedbackSpeechRuntime, FeedbackSpeechScheduler {
        data class Spoken(val message: String, val id: String, val queueMode: Int)
        private data class Task(val due: Long, val order: Long, val runnable: Runnable)
        var now = 1_000L
        val spoken = mutableListOf<Spoken>()
        var focusRequests = 0
        var focusReleases = 0
        var stops = 0
        var acceptsVibration = false
        var vibrationCalls = 0
        override fun vibrate(patternMs: LongArray): Boolean { vibrationCalls++; return acceptsVibration }
        private var initialized: ((Int) -> Unit)? = null
        private var listener: FeedbackSpeechRuntime.Listener? = null
        private val tasks = mutableListOf<Task>()
        private var sequence = 0L
        override val scheduler: FeedbackSpeechScheduler get() = this
        override val engineInitialized: Boolean get() = initialized != null
        override fun elapsedRealtime(): Long = now
        override fun initialize(onInitialized: (Int) -> Unit) { initialized = onInitialized }
        override fun setLanguage(language: Locale): Int = TextToSpeech.LANG_AVAILABLE
        override fun selectOfflineKoreanVoice(): KoreanOfflineVoiceSelection = KoreanOfflineVoiceSelection.SELECTED
        override fun configureAudioAttributes() = Unit
        override fun setListener(listener: FeedbackSpeechRuntime.Listener): Int {
            this.listener = listener
            return TextToSpeech.SUCCESS
        }
        override fun speak(message: String, queueMode: Int, utteranceId: String): Int {
            spoken += Spoken(message, utteranceId, queueMode)
            return TextToSpeech.SUCCESS
        }
        override fun stop() { stops += 1 }
        override fun shutdown() = Unit
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener): Boolean {
            focusRequests += 1
            return true
        }
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) { focusReleases += 1 }
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = error("risk READY must not create a tone")
        override fun post(task: Runnable): Boolean = postDelayed(task, 0L)
        override fun postDelayed(task: Runnable, delayMs: Long): Boolean {
            tasks += Task(now + delayMs.coerceAtLeast(0L), sequence++, task)
            return true
        }
        override fun removeCallbacks(task: Runnable) { tasks.removeAll { it.runnable === task } }
        override fun removeCallbacksAndMessages(token: Any?) { tasks.clear() }
        fun nativeError(id: String) { requireNotNull(listener).onError(id, -1); advanceBy(0L) }
        fun ready() {
            requireNotNull(initialized).invoke(TextToSpeech.SUCCESS)
            advanceBy(0L)
        }
        fun start(id: String) { requireNotNull(listener).onStart(id); advanceBy(0L) }
        fun done(id: String) { requireNotNull(listener).onDone(id); advanceBy(0L) }
        fun advanceBy(deltaMs: Long) {
            require(deltaMs >= 0L)
            val target = now + deltaMs
            while (true) {
                val next = tasks.filter { it.due <= target }
                    .minWithOrNull(compareBy<Task> { it.due }.thenBy { it.order }) ?: break
                tasks.remove(next)
                now = maxOf(now, next.due)
                next.runnable.run()
            }
            now = target
        }
    }


}
