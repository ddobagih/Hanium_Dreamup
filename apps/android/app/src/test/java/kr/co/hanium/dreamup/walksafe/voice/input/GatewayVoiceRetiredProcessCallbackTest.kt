package kr.co.hanium.dreamup.walksafe.voice.input

import android.content.Intent
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import java.io.File
import java.nio.file.Files
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.voice.*
import kr.co.hanium.dreamup.walksafe.voice.output.CommandPromptEffect
import org.junit.Assert.*
import org.junit.Test

/** Real gateway owner; deliberately delayed UI dispatch from a process-session callback. */
class GatewayVoiceRetiredProcessCallbackTest {
    @Test fun queuedSessionChangeCannotCancelReplacementRecording() {
        val f = GatewayFixture()
        assertTrue(f.controller.startGatewayVoiceCapture())
        val old = f.platform.recorders.single()
        f.scheduler.main = false
        f.controller.onProcessSessionChanged(GatewaySessionProcessSnapshot(null, null, 4, false, null, false))
        assertEquals(1, f.scheduler.posted.size)
        f.scheduler.main = true
        f.controller.cancelGatewaySpeechInteraction("explicit_old_cancel")
        assertFalse(old.isRecording)
        f.process = GatewaySessionProcessSnapshot(GatewayFieldSession.verified("https://example.test", "actor",
            "walksafe_field_session=replacement", Long.MAX_VALUE), null, 5, false, null, false)
        f.environment.identity = f.environment.identity.copy(processGeneration = 5)
        assertTrue(f.controller.startGatewayVoiceCapture())
        val replacement = f.platform.recorders.last()
        f.scheduler.posted.single().invoke()
        assertTrue("Old queued process event must leave the new recording alive", replacement.isRecording)
        assertTrue(f.controller.hasInteraction())
        assertTrue(f.controller.isRecording())
        assertTrue(f.input.active)
        assertTrue(replacement.file.isFile)
        f.controller.cancelGatewaySpeechInteraction("test_cleanup")
    }

    @Test fun queuedSessionChangeStillCancelsItsOriginalRecording() {
        val f = GatewayFixture()
        assertTrue(f.controller.startGatewayVoiceCapture())
        val old = f.platform.recorders.single()
        f.scheduler.main = false
        f.controller.onProcessSessionChanged(GatewaySessionProcessSnapshot(null, null, 4, false, null, false))
        f.scheduler.main = true
        f.scheduler.posted.single().invoke()
        assertFalse(old.isRecording)
        assertFalse(old.file.exists())
        assertFalse(f.controller.hasInteraction())
        assertFalse(f.controller.isRecording())
        assertFalse(f.input.active)
    }
    @Test fun immediateSessionCancellationCannotCancelOrDeleteReplacementUpload() {
        for (mainThread in listOf(false, true)) {
        val f = GatewayFixture()
        assertTrue(f.controller.startGatewayVoiceCapture())
        f.controller.stopGatewayVoiceCapture()
        val oldCall = f.platform.transcriptionCalls.single()
        val oldFile = f.platform.recorders.single().file
        f.scheduler.main = mainThread
        // Deterministically let UI replacement happen after capturing the old active owner,
        // before the callback reaches its synchronous call/file cancellation effects.
        f.scheduler.onMainThreadCheck = {
            f.controller.cancelGatewaySpeechInteraction("explicit_old_cancel")
            f.process = GatewaySessionProcessSnapshot(GatewayFieldSession.verified("https://example.test", "actor",
                "walksafe_field_session=replacement", Long.MAX_VALUE), null, 5, false, null, false)
            f.environment.identity = f.environment.identity.copy(processGeneration = 5)
            assertTrue(f.controller.startGatewayVoiceCapture())
            f.controller.stopGatewayVoiceCapture()
        }
        f.controller.onProcessSessionChanged(GatewaySessionProcessSnapshot(null, null, 4, false, null, false))
        val replacementCall = f.platform.transcriptionCalls.last()
        val replacementFile = f.platform.recorders.last().file
        assertNotSame(oldCall, replacementCall)
        assertTrue(oldCall.isCancelled()); assertFalse(oldFile.exists())
        assertFalse("Retired event cannot cancel replacement HTTP work", replacementCall.isCancelled())
        assertTrue("Retired event cannot delete replacement upload", replacementFile.exists())
        f.scheduler.main = true
        f.scheduler.posted.forEach { it.invoke() }
        assertTrue(f.controller.hasInteraction()); assertTrue(replacementFile.exists())
        // The old completion is also harmless; only the replacement transcript may dispatch.
        f.platform.work.removeAt(0).invoke()
        assertTrue(f.input.results.isEmpty()); assertTrue(f.controller.hasInteraction())
        f.platform.work.removeAt(0).invoke()
        assertEquals(1, f.input.results.size)
        assertFalse(f.controller.hasInteraction()); assertFalse(replacementFile.exists())
        }
    }

    @Test fun cancelledAndClosedInteractionCannotBeResurrectedByQueuedSessionOrWorkerCallback() {
        val f = GatewayFixture()
        assertTrue(f.controller.startGatewayVoiceCapture()); f.controller.stopGatewayVoiceCapture()
        val call = f.platform.transcriptionCalls.single(); val file = f.platform.recorders.single().file
        f.scheduler.main = false
        f.controller.onProcessSessionChanged(GatewaySessionProcessSnapshot(null, null, 4, false, null, false))
        f.scheduler.main = true
        f.controller.cancelGatewaySpeechInteraction("activity_stopped")
        f.controller.shutdownWorker()
        f.scheduler.posted.single().invoke(); f.platform.work.single().invoke()
        assertTrue(call.isCancelled()); assertFalse(file.exists())
        assertFalse(f.controller.hasInteraction()); assertFalse(f.controller.hasPlayer())
        assertFalse(f.controller.isRecording()); assertFalse(f.input.active)
        assertTrue(f.input.results.isEmpty())
    }

    @Test fun processEventPredatingCurrentInteractionCannotCancelNewRecording() {
        val f = GatewayFixture()
        assertTrue(f.controller.startGatewayVoiceCapture())
        val recorder = f.platform.recorders.single()
        f.controller.onProcessSessionChanged(GatewaySessionProcessSnapshot(null, null, 2, false, null, false))
        assertTrue(recorder.isRecording); assertTrue(recorder.file.exists())
        assertTrue(f.controller.hasInteraction()); assertTrue(f.input.active)
        f.controller.cancelGatewaySpeechInteraction("test_cleanup")
    }

    private class GatewayInput : GatewayVoiceInputSession {
        var active = false; val results = mutableListOf<RecognizedVoiceInput>()
        override fun recognitionActive() = active
        override fun recognitionBlocked() = false
        override fun stopHandsFree() = true
        override fun setRecordingInputActive(active: Boolean) { this.active = active }
        override fun invalidateHomeWakeInput() = Unit
        override fun scheduleRestart() = Unit
        override fun recognized(result: RecognizedVoiceInput) { results += result }
    }
    private class Recorder(val limit: () -> Unit) : GatewayVoiceRecorder {
        val file = Files.createTempFile("gateway-input-test", ".aac").toFile()
        private var recording = false
        override val isRecording get() = recording
        override fun start(): Boolean { recording = true; return true }
        override fun stop(): File { recording = false; return file }
        override fun cancel() { recording = false; file.delete() }
    }
    private class Player : GatewayVoicePlayer {
        var file: File? = null; var done: (() -> Unit)? = null
        override fun play(temporaryFile: File, onCompleted: () -> Unit, onFailed: () -> Unit): Boolean {
            file = temporaryFile; done = onCompleted; return true
        }
        override fun cancel() { file?.delete() }
        fun complete() { file?.delete(); done!!.invoke() }
    }
    private class GatewayPlatform : GatewayVoicePlatform {
        val recorders = mutableListOf<Recorder>(); val players = mutableListOf<Player>()
        val transcriptionCalls = mutableListOf<CancellableNetworkCall<GatewaySpeechTranscript>>()
        val work = mutableListOf<() -> Unit>(); var allowCommand = true
        override fun createRecorder(onLimitReached: () -> Unit): GatewayVoiceRecorder = Recorder(onLimitReached).also { recorders += it }
        override fun createPlayer(): GatewayVoicePlayer = Player().also { players += it }
        override fun transcribe(session: GatewayFieldSession, audio: File, requestId: String) = CancellableNetworkCall({
            GatewaySpeechTranscript(requestId, "현재 상태", GatewaySpeechAcoustic(0.91, null, null, allowCommand), "test")
        }).also { transcriptionCalls += it }
        override fun synthesize(session: GatewayFieldSession, message: String, requestId: String) = CancellableNetworkCall({
            GatewaySpeechAudio(requestId, "test", Files.createTempFile("gateway-output-test", ".wav").toFile())
        })
        override fun execute(action: () -> Unit) { work += action }
        override fun shutdownWorker() = Unit
    }
    private class GatewayFixture {
        val environment = Environment().also { it.walk = it.walk.copy(state = WalkSessionState.PAUSED,
            recoveryStage = WalkSessionRecoveryStage.RECHECK_REQUIRED) }
        val input = GatewayInput(); val platform = GatewayPlatform(); val scheduler = Scheduler()
        private val session = GatewayFieldSession.verified("https://example.test", "actor", "walksafe_field_session=test", Long.MAX_VALUE)
        var process = GatewaySessionProcessSnapshot(session, null, 3, false, null, false)
        val controller = GatewayVoiceCaptureController(environment, object : GatewayVoiceEnvironment {
            override fun networkAllowed(reason: String) = true
            override fun networkState() = MobileNetworkPreference.WIFI_ONLY to ActiveNetworkTransport.WIFI
            override fun sessionPrerequisitesAllowed() = true
            override fun processSnapshot() = process
        }, input, Output(), Presentation(), platform, scheduler)
    }

    private enum class Page { COMMAND, OTHER }
    private class Environment : VoiceInputEnvironment {
        var identity = VoiceInputIdentity("actor", 3)
        var page = VoiceInputPage(Page.COMMAND, true, true, false, false, false, true)
        var walk = WalkSessionSnapshot(WalkRuntimeEpoch("walk", 0), WalkSessionState.READY, WalkSessionMode.FULL, null, true, null)
        var work = VoiceInputCommandWork(0, false, 0, false)
        var decisionToken: kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorDecisionToken? = null
        var decisionQueries = 0
        var foreground = true
        var microphone = true
        override fun identity() = identity
        override fun page() = page
        override fun walkOrNull() = walk
        override fun foreground() = foreground
        override fun feedbackLifetimeGeneration() = 1
        override fun capabilityDecision(): WalkSafeStartupCapabilityDecision? = null
        override fun capabilityAllows(decision: WalkSafeStartupCapabilityDecision, requirement: WalkSafeStartupRequirement) =
            decision.tier != WalkSafeStartupCapabilityTier.BLOCKED && requirement !in decision.unavailableRequirements && requirement !in decision.pendingRequirements
        override fun microphoneGranted() = microphone
        override fun notificationGranted() = true
        override fun handsFreeFeatureEnabled() = true
        override fun voiceGuidanceFeatureEnabled() = true
        override fun disclosureAccepted() = true
        override fun homeFeatureContextAvailable() = true
        override fun startupCapabilityConfirmed() = true
        override fun runtimeActive() = false
        override fun confirmation() = VoiceInputConfirmation(false, null, false, null, null)
        override fun commandWork() = work
        override fun pendingNavigationDecisionToken(): kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorDecisionToken? {
            decisionQueries++; return decisionToken
        }
        override fun takeoverConflictAvailable(epoch: WalkRuntimeEpoch) = false
    }
    private class Commands : VoiceInputCommandEffects {
        val results = mutableListOf<RecognizedVoiceInput>(); var misses = 0
        var endConfirmationClears = 0; var guidanceConfirmationClears = 0
        override fun recognized(result: RecognizedVoiceInput) { results += result }
        override fun resumeRecognized(phrases: List<String>, confidenceScores: FloatArray?) = Unit
        override fun takeoverRecognized(phrases: List<String>) = Unit
        override fun resumeRecognizerNotStarted() = Unit
        override fun cancelTakeover(epoch: WalkRuntimeEpoch, reason: String) = Unit
        override fun clearWalkEndConfirmation() { endConfirmationClears++ }
        override fun invalidateGuidanceStopConfirmation() { guidanceConfirmationClears++ }
        override fun commandInputMiss(error: Int) { misses++ }
        override fun handleDestinationPromptIfPresent() = false
    }
    private class Presentation : VoiceInputPresentation {
        val messages = mutableListOf<String>()
        override fun voiceStatus(message: String) { messages += message }
        override fun navigationStatus(message: String) { messages += message }
        override fun refreshControls(active: Boolean?) = Unit
        override fun status(title: String, detail: String) { messages += title; messages += detail }
        override fun lastResult(message: String) { messages += message }
        override fun recognitionSignal(message: String) { messages += message }
        override fun showCommandPage() = Unit
        override fun canShowStatus() = true
    }
    private class Scheduler : VoiceInputScheduler {
        var main = true; val posted = mutableListOf<() -> Unit>()
        var onMainThreadCheck: (() -> Unit)? = null
        var now = 0L; val pending = mutableListOf<Pair<Long, Runnable>>()
        override fun nowMs() = now
        override fun isMainThread(): Boolean {
            val interleave = onMainThreadCheck
            onMainThreadCheck = null
            interleave?.invoke()
            return main
        }
        override fun runOnMain(action: () -> Unit) { if (main) action() else posted += action }
        override fun canScheduleRestart() = true
        override fun postRestart(task: Runnable, delayMs: Long) { pending += now + delayMs to task }
        override fun removeRestart(task: Runnable) { pending.removeAll { it.second === task } }
        override fun postDeferred(task: Runnable, delayMs: Long) { pending += now + delayMs to task }
        override fun removeDeferred(task: Runnable) { pending.removeAll { it.second === task } }
        fun advance(ms: Long) {
            val until = now + ms
            while (true) {
                val next = pending.filter { it.first <= until }.minByOrNull { it.first } ?: break
                pending.remove(next); now = next.first; next.second.run()
            }
            now = until
        }
    }
    private class Output : VoiceInputOutput {
        lateinit var core: VoiceInputSessionController
        var generation = 1L; var busy = false; var done: (() -> Unit)? = null; var fail: (() -> Unit)? = null
        var startedCues = 0
        override fun commandResponsePending() = false
        override fun commandGeneration() = generation
        override fun nativeOrAccessibilitySpeechActive() = busy
        override fun nativeRecognitionBlocked() = false
        override fun prepareForRecognition() = true
        override fun canSpeakNavigation(nowMs: Long) = true
        override fun speakInteraction(message: String) = true
        override fun speakPreparingInput(message: String, onCompleted: () -> Unit, onFailed: () -> Unit): Boolean {
            core.applyCommandPromptEffect(CommandPromptEffect.PREPARING); done = onCompleted; fail = onFailed; return true
        }
        override fun cancelCommandResponse() {
            generation++; core.cancelDeferredVoiceRecognitionStart(); core.applyCommandPromptEffect(CommandPromptEffect.CLEAR)
        }
        override fun playListeningStarted() { startedCues++ }
        override fun playListeningEnded() = Unit
        override fun speakLowConfidenceReprompt(message: String, onCompleted: () -> Unit, onFailed: () -> Unit) = true
        override fun screenReaderActive() = false
        fun complete() { done!!.invoke() }
    }

}
