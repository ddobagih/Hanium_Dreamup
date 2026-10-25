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

/** Real input coordinators and fences; factories, output callbacks and external facts are controlled. */
class VoiceInputSessionControllerBehaviorTest {
    @Test fun constructionHasNoFactoriesQueriesOrScheduledWork() {
        val f = Fixture()
        assertEquals(0, f.platform.effects)
        assertTrue(f.scheduler.pending.isEmpty())
        assertFalse(f.core.state.recognitionActive)
        assertFalse(f.core.state.promptPending)
        assertEquals(0, f.environment.decisionQueries)
    }

    @Test fun dispatchedPromptDoesNotOpenMicrophoneUntilItsCompletionCallback() {
        val f = Fixture()
        assertTrue(f.core.startVoiceCommandRecognition())
        assertTrue(f.core.state.promptPending)
        assertEquals(0, f.platform.recognizer.starts)
        f.output.complete()
        assertEquals(1, f.platform.recognizer.starts)
        assertTrue(f.core.state.recognitionActive)
        assertEquals(1, f.core.state.recognitionGeneration)
        f.output.complete()
        assertEquals(1, f.platform.recognizer.starts)
    }

    @Test fun failedOrCancelledPromptCannotOpenMicrophoneAndLateOldDoneCannotReleaseNewPrompt() {
        val f = Fixture()
        f.core.startVoiceCommandRecognition()
        val oldDone = f.output.done!!
        f.output.fail!!()
        assertFalse(f.core.state.promptPending)
        assertEquals(0, f.platform.recognizer.starts)
        f.core.startVoiceCommandRecognition()
        f.core.cancelVoiceCommandRecognition()
        f.core.startVoiceCommandRecognition()
        oldDone()
        assertTrue(f.core.state.promptPending)
        assertEquals(0, f.platform.recognizer.starts)
        f.output.complete()
        assertEquals(1, f.platform.recognizer.starts)
    }

    @Test fun cancellationRetiresRecognizerCallbacksBeforeTheyCanChangeNewRequestState() {
        val f = Fixture()
        f.core.markPromptReadyAfterCue()
        f.core.startVoiceCommandRecognition()
        val old = f.platform.recognizer.listener!!
        f.core.cancelVoiceCommandRecognition()
        f.core.markPromptReadyAfterCue()
        f.core.startVoiceCommandRecognition()
        val generation = f.core.state.recognitionGeneration
        old.onError(SpeechRecognizer.ERROR_NO_MATCH)
        old.onResults(null)
        assertEquals(generation, f.core.state.recognitionGeneration)
        assertTrue(f.core.state.recognitionActive)
        assertEquals(0, f.commands.misses)
        assertTrue(f.commands.results.isEmpty())
        f.platform.recognizer.listener!!.onError(SpeechRecognizer.ERROR_NO_MATCH)
        assertFalse(f.core.state.recognitionActive)
        assertEquals(1, f.commands.misses)
    }

    @Test fun changedWalkEpochRejectsReadyResultAndErrorBeforeAnyPresentationOrCommandEffect() {
        val f = Fixture()
        f.core.markPromptReadyAfterCue(); f.core.startVoiceCommandRecognition()
        val listener = f.platform.recognizer.listener!!
        val count = f.presentation.messages.size
        val generation = f.core.state.recognitionGeneration
        f.environment.walk = f.environment.walk.copy(epoch = WalkRuntimeEpoch("new-walk", 0L))
        listener.onReadyForSpeech(null); listener.onResults(null); listener.onError(SpeechRecognizer.ERROR_AUDIO)
        assertEquals(count, f.presentation.messages.size)
        assertEquals(generation, f.core.state.recognitionGeneration)
        assertTrue(f.commands.results.isEmpty())
        assertEquals(0, f.output.startedCues)
    }

    @Test fun validFinalResultIsConsumedOnceAndRetainsItsCapturedNavigationToken() {
        val f = Fixture()
        val token = kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorDecisionToken(2, 3, "route",
            kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorUserDecision.LOCATION_RECHECK)
        f.environment.decisionToken = token
        f.core.markPromptReadyAfterCue(); f.core.startVoiceCommandRecognition()
        f.environment.decisionToken = null
        val listener = f.platform.recognizer.listener!!
        listener.onResults(null)
        listener.onResults(null)
        assertEquals(1, f.commands.results.size)
        assertEquals(emptyList<String>(), f.commands.results.single().phrases)
        assertEquals(token, f.commands.results.single().expectedNavigationDecisionToken)
        assertFalse(f.core.state.recognitionActive)
        assertEquals(2, f.core.state.recognitionGeneration)
    }

    @Test fun logicalLocationWorkTransfersToItsHttpSearchAndRejectsAReplacedConsumer() {
        val f=Fixture()
        val type=f.core.javaClass.declaredClasses.single { it.simpleName=="HandsFreeCommandWork" }
        val bound=type.declaredConstructors.single { !it.isSynthetic }.also { it.isAccessible=true }.newInstance(1L,null,null,7L,3)
        f.core.javaClass.getDeclaredField("handsFreeCommandWork").also { it.isAccessible=true }.set(f.core,bound)
        f.environment.work=VoiceInputCommandWork(3,false,0,false,7,true)
        assertTrue(f.core.isHandsFreeCommandWorkPending(1));assertFalse(f.core.isHandsFreeCommandWorkPending(2))
        f.environment.work=f.environment.work.copy(locationInFlight=false,searchGeneration=4,searchInFlight=true)
        assertTrue(f.core.isHandsFreeCommandWorkPending(1))
        f.environment.work=f.environment.work.copy(searchInFlight=false)
        assertFalse(f.core.isHandsFreeCommandWorkPending(1))
        f.core.javaClass.getDeclaredField("handsFreeCommandWork").also { it.isAccessible=true }.set(f.core,bound)
        f.environment.work=VoiceInputCommandWork(3,false,0,false,8,true)
        assertFalse(f.core.isHandsFreeCommandWorkPending(1))
    }

    @Test fun commandWorkGenerationsAndDeferredPollsDoNotReadTheExpiringDecisionLease() {
        val f = Fixture()
        val before = f.core.handsFreeCommandWorkGenerations()
        f.core.bindHandsFreeCommandWork(before)
        assertFalse(f.core.isHandsFreeCommandWorkPending(1L))
        assertEquals(0, f.environment.decisionQueries)
        f.output.busy = true
        f.core.startVoiceCommandRecognition()
        assertEquals(1, f.environment.decisionQueries)
        f.scheduler.advance(300)
        assertTrue(f.core.state.deferredPending)
        assertEquals(1, f.environment.decisionQueries)
        f.core.cancelDeferredVoiceRecognitionStart()
        assertEquals(1, f.environment.decisionQueries)
    }

    @Test fun deferredInputWaitsForIdleAndThenAsksForPromptBeforeStarting() {
        val f = Fixture()
        f.output.busy = true
        assertTrue(f.core.startVoiceCommandRecognition())
        assertTrue(f.core.state.deferredPending)
        assertNull(f.output.done)
        f.scheduler.advance(100)
        assertEquals(0, f.platform.recognizer.starts)
        f.output.busy = false
        f.scheduler.advance(100)
        assertFalse(f.core.state.deferredPending)
        assertTrue(f.core.state.promptPending)
        assertEquals(0, f.platform.recognizer.starts)
        f.output.complete()
        assertEquals(1, f.platform.recognizer.starts)
    }

    @Test fun deferredInputDoesNotStartAfterActorProcessPageEpochRouteOrOutputOwnershipChanges() {
        for (change in listOf("actor", "process", "page", "epoch", "route", "output", "foreground")) {
            val f = Fixture(); f.output.busy = true; f.core.startVoiceCommandRecognition()
            when (change) {
                "actor" -> f.environment.identity = f.environment.identity.copy(actorId = "other")
                "process" -> f.environment.identity = f.environment.identity.copy(processGeneration = 12)
                "page" -> f.environment.page = f.environment.page.copy(owner = Page.OTHER)
                "epoch" -> f.environment.walk = f.environment.walk.copy(epoch = WalkRuntimeEpoch("other", 0L))
                "route" -> f.environment.work = f.environment.work.copy(routeGeneration = 9)
                "output" -> f.output.generation++
                "foreground" -> f.environment.foreground = false
            }
            f.output.busy = false; f.scheduler.advance(100)
            assertFalse(change, f.core.state.deferredPending)
            assertEquals(change, 0, f.platform.recognizer.starts)
            assertNull(change, f.output.done)
        }
    }

    @Test fun deferredDeadlineExpiresOnceAndClearsItsOwnedConfirmation() {
        val f = Fixture(); f.output.busy = true; f.core.startVoiceCommandRecognition()
        f.scheduler.advance(30_000)
        assertFalse(f.core.state.deferredPending)
        assertEquals(1, f.commands.endConfirmationClears)
        assertEquals(1, f.commands.guidanceConfirmationClears)
        assertTrue(f.presentation.messages.contains("voice=input_wait_expired"))
        assertEquals(0, f.platform.recognizer.starts)
    }

    @Test fun microphoneDenialDoesNotCreateRecognizerOrPrompt() {
        val f = Fixture(); f.environment.microphone = false
        assertFalse(f.core.startVoiceCommandRecognition())
        assertEquals(0, f.platform.effects)
        assertNull(f.output.done)
        assertFalse(f.core.state.recognitionActive)
    }

    @Test fun modelPreparationCompletionAfterCloseCannotAttachAControllerOrRestart() {
        val f = Fixture(); f.platform.modelAvailable = true
        f.core.prepareHandsFreeVoiceModel()
        assertTrue(f.core.state.modelPreparing)
        f.core.closeHandsFreeVoice()
        f.platform.modelWork!!.invoke()
        assertTrue(f.core.state.destroyed)
        assertNull(f.core.state.modelDirectory)
        assertEquals(0, f.platform.handsFreeCreates)
        assertFalse(f.presentation.messages.contains("voice_hands_free=model_ready"))
        f.platform.directory.delete()
    }

    @Test fun wakeDispatchNeedsCompletedCueAndStaleActorCueCannotStartInput() {
        val f = Fixture(); val session = WakeSession(); val wake = ForegroundWakeInputController(f.environment, session, f.presentation, f.platform, NoDiagnostics)
        wake.acknowledgeForegroundHomeWake(null, null)
        assertEquals(0, session.started)
        f.platform.ack.finish(WakeAcknowledgementResult.COMPLETED)
        assertEquals(1, session.cues)
        assertEquals(1, session.started)
        wake.acknowledgeForegroundHomeWake(null, null)
        f.environment.identity = f.environment.identity.copy(actorId = "other")
        f.platform.ack.finish(WakeAcknowledgementResult.COMPLETED)
        assertEquals(1, session.started)
        session.directory.delete()
    }

    @Test fun failedWakeCueFallsBackToNormalPromptButCancelledCueDoesNotStartInput() {
        val f = Fixture(); val session = WakeSession(); val wake = ForegroundWakeInputController(f.environment, session, f.presentation, f.platform, NoDiagnostics)
        wake.acknowledgeForegroundHomeWake(null, null)
        f.platform.ack.finish(WakeAcknowledgementResult.FAILED)
        assertEquals(0, session.cues)
        assertEquals(1, session.started)
        wake.acknowledgeForegroundHomeWake(null, null)
        f.platform.ack.finish(WakeAcknowledgementResult.CANCELLED)
        assertEquals(1, session.started)
        assertEquals(1, session.restarts)
        session.directory.delete()
    }

    @Test fun retiredWakeProbeCannotTakeOverTheReplacementProbe() {
        val f = Fixture(); val session = WakeSession(); val wake = ForegroundWakeInputController(f.environment, session, f.presentation, f.platform, NoDiagnostics)
        wake.refreshForegroundHomeWakeListening()
        val old = f.platform.probes.single()
        wake.cancelForegroundHomeWakeListening()
        wake.refreshForegroundHomeWakeListening()
        old.result(VoskWakePhraseProbeResult.available())
        assertEquals(0, f.platform.ack.plays)
        assertTrue(wake.hasProbe())
        f.platform.probes.last().result(VoskWakePhraseProbeResult.available())
        assertEquals(1, f.platform.ack.plays)
        session.directory.delete()
    }

    @Test fun synchronousInputFailureDuringStartKeepsItsGuidanceAndNeverSchedulesOrUploads() {
        for (failure in VoiceInputFailure.entries) {
            val f = GatewayFixture(); f.platform.failureOnStart = failure
            assertFalse(f.controller.startGatewayVoiceCapture())
            assertFalse(f.controller.hasInteraction()); assertFalse(f.input.active)
            assertEquals(listOf(failure.guidance), f.output.messages)
            assertEquals(0, f.input.restarts); assertEquals(1,f.input.failures)
            assertTrue(f.platform.work.isEmpty()); assertFalse(f.platform.recorders.single().file.exists())
        }
    }
    @Test fun inputFailureDuringUploadAndRetiredRecorderCallbacksCannotExecuteOrStopNewOwner() {
        val f=GatewayFixture(); assertTrue(f.controller.startGatewayVoiceCapture())
        val old=f.platform.recorders.single(); f.controller.stopGatewayVoiceCapture()
        old.fail(VoiceInputFailure.ROUTE_CHANGED); f.platform.work.removeAt(0).invoke()
        assertTrue(f.input.results.isEmpty()); assertFalse(old.file.exists())
        assertTrue(f.controller.startGatewayVoiceCapture()); old.fail(VoiceInputFailure.SILENCED)
        assertTrue(f.controller.isRecording()); assertTrue(f.input.active)
        f.controller.cancelGatewaySpeechInteraction("cleanup")
    }

    @Test fun gatewayRecordingLimitFromOldLeaseCannotStopReplacementRecording() {
        val f = GatewayFixture()
        assertTrue(f.controller.startGatewayVoiceCapture())
        val old = f.platform.recorders.single()
        f.controller.cancelGatewaySpeechInteraction("test_cancel")
        assertTrue(f.controller.startGatewayVoiceCapture())
        old.limit()
        assertTrue(f.controller.isRecording())
        assertTrue(f.input.active)
        assertEquals(0, f.platform.work.size)
        f.controller.cancelGatewaySpeechInteraction("cleanup")
    }

    @Test fun gatewayTranscriptionDeletesOwnedFileAndDeliversItsCapturedResultOnlyOnce() {
        val f = GatewayFixture()
        assertTrue(f.controller.startGatewayVoiceCapture())
        val recording = f.platform.recorders.single().file
        f.controller.stopGatewayVoiceCapture()
        assertTrue(recording.isFile)
        assertFalse(f.input.active)
        f.platform.work.removeAt(0).invoke()
        assertFalse(recording.exists())
        assertFalse(f.controller.hasInteraction())
        assertEquals(listOf("현재 상태"), f.input.results.single().phrases)
        assertEquals(listOf(0.91f), f.input.results.single().confidenceScores)
        assertEquals("gateway_stt", f.input.results.single().source)
        assertFalse(f.input.results.single().includeRecognizedTextInStatus)
    }

    @Test fun gatewayNetworkRevocationCancelsCallAndDeletesAudioBeforeLateWorkerReturns() {
        val f = GatewayFixture()
        f.controller.startGatewayVoiceCapture(); f.controller.stopGatewayVoiceCapture()
        val recording = f.platform.recorders.single().file
        f.controller.markNetworkPolicyChanged()
        assertTrue(f.platform.transcriptionCalls.single().isCancelled())
        f.controller.cancelForNetworkPolicyIfNeeded()
        assertFalse(recording.exists())
        assertFalse(f.controller.hasInteraction())
        f.platform.work.removeAt(0).invoke()
        assertTrue(f.input.results.isEmpty())
    }

    @Test fun gatewaySessionRevocationOffMainCancelsNetworkAndRemovesFileBeforeUiCleanup() {
        val f = GatewayFixture()
        f.controller.startGatewayVoiceCapture(); f.controller.stopGatewayVoiceCapture()
        val recording = f.platform.recorders.single().file
        f.scheduler.main = false
        val replacement = GatewaySessionProcessSnapshot(null, null, 99, false, null, false)
        f.controller.onProcessSessionChanged(replacement)
        assertTrue(f.platform.transcriptionCalls.single().isCancelled())
        assertFalse(recording.exists())
        assertTrue(f.controller.hasInteraction())
        assertEquals(1, f.scheduler.posted.size)
        f.scheduler.main = true; f.scheduler.posted.removeAt(0).invoke()
        assertFalse(f.controller.hasInteraction())
        assertTrue(f.input.results.isEmpty())
    }

    @Test fun lowConfidenceGatewayPlaybackRetainsNewPlayerWhenOldCompletionArrives() {
        val f = GatewayFixture(); f.platform.allowCommand = false
        f.controller.startGatewayVoiceCapture(); f.controller.stopGatewayVoiceCapture()
        f.platform.work.removeAt(0).invoke(); f.platform.work.removeAt(0).invoke()
        val old = f.platform.players.single()
        assertTrue(f.controller.hasPlayer())
        assertTrue(f.input.results.isEmpty())
        f.controller.cancelGatewaySpeechInteraction("replace")
        assertFalse(old.file!!.exists())
        f.controller.startGatewayVoiceCapture(); f.controller.stopGatewayVoiceCapture()
        f.platform.work.removeAt(0).invoke(); f.platform.work.removeAt(0).invoke()
        val replacement = f.platform.players.last()
        old.done!!.invoke()
        assertTrue(f.controller.hasPlayer())
        assertTrue(f.controller.hasInteraction())
        assertTrue(replacement.file!!.isFile)
        replacement.complete()
        assertFalse(f.controller.hasPlayer())
        assertFalse(f.controller.hasInteraction())
        assertTrue(f.input.results.isEmpty())
    }

    private class GatewayInput : GatewayVoiceInputSession {
        var active = false; val results = mutableListOf<RecognizedVoiceInput>(); var restarts=0; var failures=0
        override fun recognitionActive() = active
        override fun recognitionBlocked() = false
        override fun stopHandsFree() = true
        override fun setRecordingInputActive(active: Boolean) { this.active = active }
        override fun invalidateHomeWakeInput() = Unit
        override fun scheduleRestart() { restarts++ }
        override fun inputFailed(failure: VoiceInputFailure) { failures++ }
        override fun recognized(result: RecognizedVoiceInput) { results += result }
    }
    private class Recorder(val limit: () -> Unit, val failure: (VoiceInputFailure)->Unit = {}, val failureOnStart: VoiceInputFailure? = null) : GatewayVoiceRecorder {
        val file = Files.createTempFile("gateway-input-test", ".aac").toFile()
        private var recording = false
        override val isRecording get() = recording
        override fun start(): Boolean { recording = true; failureOnStart?.let { fail(it); return false }; return true }
        fun fail(reason: VoiceInputFailure) { cancel(); failure(reason) }
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
        val work = mutableListOf<() -> Unit>(); var allowCommand = true; var failureOnStart: VoiceInputFailure? = null
        override fun createRecorder(onLimitReached: () -> Unit): GatewayVoiceRecorder = Recorder(onLimitReached).also { recorders += it }
        override fun createRecorder(onLimitReached: () -> Unit, onInputFailure: (VoiceInputFailure) -> Unit): GatewayVoiceRecorder = Recorder(onLimitReached,onInputFailure,failureOnStart).also { recorders += it }
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
        val input = GatewayInput(); val platform = GatewayPlatform(); val scheduler = Scheduler(); val output=Output()
        private val session = GatewayFieldSession.verified("https://example.test", "actor", "walksafe_field_session=test", Long.MAX_VALUE)
        val controller = GatewayVoiceCaptureController(environment, object : GatewayVoiceEnvironment {
            override fun networkAllowed(reason: String) = true
            override fun networkState() = MobileNetworkPreference.WIFI_ONLY to ActiveNetworkTransport.WIFI
            override fun sessionPrerequisitesAllowed() = true
            override fun processSnapshot() = GatewaySessionProcessSnapshot(session, null, 3, false, null, false)
        }, input, output, Presentation(), platform, scheduler)
    }

    private object NoDiagnostics : VoiceInputDiagnostics {
        override fun input(event: VoiceInputDiagnosticEvent, confidence: Float?, resultCount: Int?, errorCode: Int?) = Unit
        override fun wake(message: String) = Unit
        override fun serviceInfo(message: String) = Unit
        override fun serviceWarning(message: String, error: Throwable?) = Unit
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
        var now = 0L; val pending = mutableListOf<Pair<Long, Runnable>>()
        override fun nowMs() = now
        override fun isMainThread() = main
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
        val messages=mutableListOf<String>()
        lateinit var core: VoiceInputSessionController
        var generation = 1L; var busy = false; var done: (() -> Unit)? = null; var fail: (() -> Unit)? = null
        var startedCues = 0
        override fun commandResponsePending() = false
        override fun commandGeneration() = generation
        override fun nativeOrAccessibilitySpeechActive() = busy
        override fun nativeRecognitionBlocked() = false
        override fun prepareForRecognition() = true
        override fun canSpeakNavigation(nowMs: Long) = true
        override fun speakInteraction(message: String): Boolean { messages += message; return true }
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
    private class Recognizer : VoiceCommandRecognizer {
        var listener: RecognitionListener? = null; var starts = 0
        override fun setRecognitionListener(listener: RecognitionListener) { this.listener = listener }
        override fun startListening(intent: Intent, preferPlatform: Boolean, isRequestCurrent: () -> Boolean) {
            check(preferPlatform); check(isRequestCurrent()); starts++
        }
        override fun cancel() = Unit
        override fun destroy() = Unit
    }
    private class Ack : WakeAcknowledgementEffect {
        var callback: ((WakeAcknowledgementResult) -> Unit)? = null; var plays = 0
        override fun isPlaying() = false
        override fun play(onFinished: (WakeAcknowledgementResult) -> Unit) { plays++; callback = onFinished }
        override fun cancel() = Unit
        override fun close() = Unit
        fun finish(result: WakeAcknowledgementResult) { callback!!.invoke(result) }
    }
    private class Platform : VoiceInputPlatform, ForegroundWakePlatform, GatewayVoicePlatform {
        var effects = 0; var modelAvailable = false; var modelWork: (() -> Unit)? = null; var handsFreeCreates = 0
        val directory: File by lazy { Files.createTempDirectory("voice-input-model-test").toFile() }
        val recognizer = Recognizer(); val ack = Ack(); val probes = mutableListOf<ForegroundWakeCallbacks>()
        override fun applicationAvailable() = true
        override fun modelAssetAvailable(): Boolean { effects++; return modelAvailable }
        override fun prepareInstalledModel() = directory
        override fun executeModelWork(action: () -> Unit) { modelWork = action }
        override fun shutdownModelWorker() = Unit
        override fun createRecognizer(modelDirectory: () -> File?): VoiceCommandRecognizer { effects++; return recognizer }
        override fun commandIntent() = Intent()
        override fun createHandsFree(modelDirectory: File, callbacks: HandsFreeInputCallbacks): HandsFreeVoiceController {
            handsFreeCreates++; error("No stale model completion may attach a service")
        }
        override fun attachHandsFree(controller: HandsFreeVoiceController) = true
        override fun detachHandsFree(controller: HandsFreeVoiceController) = Unit
        override fun setUserStopListener(listener: ((WalkVoiceSessionController) -> Unit)?) = Unit
        override fun isAttached(controller: HandsFreeVoiceController) = false
        override fun userStoppedWalk(walkId: String) = false
        override fun recordUserStop(walkId: String) = Unit
        override fun registerStart(controller: HandsFreeVoiceController, onFailure: (Long, WalkVoiceSessionController, WalkVoiceStartFailure) -> Unit): Long? = null
        override fun invalidateStart(requestId: Long, controller: HandsFreeVoiceController) = Unit
        override fun invalidateStartOnSessionEnd(requestId: Long, controller: HandsFreeVoiceController): Boolean? = null
        override fun reportStartFailure(requestId: Long, controller: HandsFreeVoiceController, failure: WalkVoiceStartFailure) = Unit
        override fun startService(requestId: Long) = Unit
        override fun stopService() = Unit
        override fun inputHeldElsewhere() = false
        override fun createAcknowledgement(speechActive: () -> Boolean) = ack
        override fun createProbe(callbacks: ForegroundWakeCallbacks): WakeProbeEffect {
            probes += callbacks
            return object : WakeProbeEffect { override fun start() = Unit; override fun cancel() = Unit }
        }
        override fun createRecorder(onLimitReached: () -> Unit): GatewayVoiceRecorder = error("Not a gateway fixture")
        override fun createPlayer(): GatewayVoicePlayer = error("Not a gateway fixture")
        override fun transcribe(session: GatewayFieldSession, audio: File, requestId: String): CancellableNetworkCall<GatewaySpeechTranscript> = error("Not a gateway fixture")
        override fun synthesize(session: GatewayFieldSession, message: String, requestId: String): CancellableNetworkCall<GatewaySpeechAudio> = error("Not a gateway fixture")
        override fun execute(action: () -> Unit) = Unit
        override fun shutdownWorker() = Unit
    }
    private class WakeSession : ForegroundWakeSession {
        val directory = Files.createTempDirectory("voice-input-wake-test").toFile()
        var started = 0; var cues = 0; var restarts = 0
        override fun state() = WakeInputState(false, false, 0, false, false, false, false, false)
        override fun homeCommandAvailable() = true
        override fun speechActive() = false
        override fun recognitionBlocked() = false
        override fun modelDirectoryOrNull() = directory
        override fun stopHandsFree() = true
        override fun scheduleRestart() { restarts++ }
        override fun cueCompletedForCurrentRecognition() { cues++ }
        override fun startCommandRecognition(): Boolean { started++; return true }
        override fun recognized(result: RecognizedVoiceInput) = Unit
    }
    private class Fixture {
        val environment = Environment(); val commands = Commands(); val presentation = Presentation()
        val output = Output(); val scheduler = Scheduler(); val platform = Platform()
        val core = VoiceInputSessionController(environment, commands, presentation, object : VoiceInputApplicationEffects {
            override fun requireStartupCapabilityConfirmation() = true
            override fun permissionStateChanged(reason: String) = Unit
            override fun showMicrophoneDenial() = Unit
            override fun continueDeviceCheck() = Unit
            override fun refreshCapabilityUi() = Unit
            override fun limitPersistentHandsFreeRecognition() = Unit
        }, output, scheduler, platform, platform, platform, object : GatewayVoiceEnvironment {
            override fun networkAllowed(reason: String) = false
            override fun networkState() = MobileNetworkPreference.WIFI_ONLY to ActiveNetworkTransport.WIFI
            override fun sessionPrerequisitesAllowed() = false
            override fun processSnapshot() = GatewaySessionProcessSnapshot(null, null, 0, false, null, false)
        }, NoDiagnostics).also { output.core = it }
    }
}
