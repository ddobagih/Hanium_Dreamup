package kr.co.hanium.dreamup.walksafe.voice.input

import android.content.Intent
import java.io.File
import java.nio.file.Files
import java.util.concurrent.RejectedExecutionException
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorDecisionToken
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.voice.*
import org.junit.Assert.*
import org.junit.Test

/** Real runtime and controller; only external model/service/scheduler effects are controlled. */
class HandsFreeRuntimeSessionBehaviorTest {
    @Test fun constructionDoesNotReadAssetsEnvironmentOrCreateAController() = withFixture { f ->
        assertTrue(f.events.isEmpty())
        assertEquals(0, f.environment.queries)
        assertTrue(f.platform.created.isEmpty())
        assertTrue(f.scheduler.pending.isEmpty())
        assertNull(f.session.modelDirectory)
    }

    @Test fun queuedModelCompletionCannotAttachOrStartAfterRetirement() = withFixture { f ->
        f.session.prepareHandsFreeVoiceModel()
        f.platform.work.single().invoke()
        assertEquals(1, f.scheduler.main.size)
        f.input.onRetired = {
            assertTrue(f.session.destroyed)
            // Cleanup can synchronously deliver a callback already queued by the model worker.
            f.scheduler.drainMain()
        }
        f.session.closeHandsFreeVoice()
        assertNull(f.session.modelDirectory)
        assertTrue(f.platform.created.isEmpty())
        assertTrue(f.platform.starts.isEmpty())
        assertFalse(f.presentation.messages.contains("voice_hands_free=model_ready"))
        assertEquals(1, f.events.count { it == "worker_shutdown" })
    }

    @Test fun rejectedModelWorkCanBeRetriedExplicitlyWithoutAttachingItsFailedAttempt() = withFixture { f ->
        f.platform.rejectWork = true
        f.session.prepareHandsFreeVoiceModel()
        assertTrue(f.session.modelPreparationFailed)
        assertFalse(f.session.modelPreparing)
        assertTrue(f.platform.created.isEmpty())
        assertTrue(f.presentation.messages.contains("voice_hands_free=model_install_rejected"))
        f.platform.rejectWork = false
        f.prepare()
        assertFalse(f.session.modelPreparationFailed)
        assertEquals(f.directory, f.session.modelDirectory)
        assertEquals(1, f.platform.created.size)
        assertEquals(1, f.platform.starts.size)
    }

    @Test fun modelFailureDoesNotAttachAndAnExplicitRetryCanRecover() = withFixture { f ->
        f.platform.modelFailure = IllegalStateException("controlled install failure")
        f.prepare()
        assertTrue(f.session.modelPreparationFailed)
        assertFalse(f.session.modelPreparing)
        assertTrue(f.platform.created.isEmpty())
        f.platform.modelFailure = null
        f.prepare()
        assertFalse(f.session.modelPreparationFailed)
        assertEquals(1, f.platform.starts.size)
    }

    @Test fun lateFailureMustMatchBothStartRequestAndControllerBeforeStoppingCurrentService() = withFixture { f ->
        f.prepare()
        val first = f.platform.requests.single()
        f.session.stopHandsFreeVoiceService()
        f.session.maybeStartHandsFreeVoiceService()
        val current = f.platform.requests.last()
        val other = f.platform.createHandsFree(f.directory, f.platform.callbacks!!)
        val stopCount = f.platform.stops
        val messages = f.presentation.messages.toList()
        first.fail(first.id, first.controller, WalkVoiceStartFailure.SECURITY)
        current.fail(current.id, other, WalkVoiceStartFailure.SECURITY)
        assertEquals(stopCount, f.platform.stops)
        assertEquals(messages, f.presentation.messages)
        f.session.maybeStartHandsFreeVoiceService()
        assertEquals(2, f.platform.starts.size)
        current.fail(current.id, current.controller, WalkVoiceStartFailure.SECURITY)
        assertEquals(stopCount + 1, f.platform.stops)
        assertTrue(f.presentation.messages.contains("voice_hands_free=microphone_access_restricted_retry_required"))
        f.session.maybeStartHandsFreeVoiceService()
        assertEquals(2, f.platform.starts.size)
        f.session.onStopped()
        f.session.onBeforeForegroundReturn()
        f.session.maybeStartHandsFreeVoiceService()
        assertEquals(3, f.platform.starts.size)
    }

    @Test fun replacedRestartCannotStartEarlyOrConsumeTheCurrentTimerAndCloseRetiresIt() = withFixture { f ->
        f.environment.active = false
        f.prepare()
        f.environment.active = true
        f.session.scheduleHandsFreeVoiceRestart()
        val old = f.scheduler.pending.single().second
        f.session.scheduleHandsFreeVoiceRestart()
        val replacement = f.scheduler.pending.single().second
        assertNotSame(old, replacement)
        old.run()
        assertTrue(f.platform.starts.isEmpty())
        assertSame(replacement, f.scheduler.pending.single().second)
        f.scheduler.advance(499)
        assertTrue(f.platform.starts.isEmpty())
        f.scheduler.advance(1)
        assertEquals(1, f.platform.starts.size)
        f.session.stopHandsFreeVoiceService()
        f.session.scheduleHandsFreeVoiceRestart()
        val retired = f.scheduler.pending.single().second
        f.session.closeHandsFreeVoice()
        retired.run()
        assertEquals(1, f.platform.starts.size)
        assertTrue(f.scheduler.pending.isEmpty())
    }

    @Test fun notificationStopBlocksTheRestOfItsWalkButNotANewWalk() = withFixture { f ->
        f.prepare()
        val controller = f.platform.created.single()
        f.platform.userStop!!.invoke(controller)
        assertEquals(setOf("walk"), f.platform.stoppedWalks)
        assertTrue(f.platform.invalidated.contains(1L))
        f.session.scheduleHandsFreeVoiceRestart()
        f.scheduler.advance(500)
        f.session.onStopped()
        f.session.onBeforeForegroundReturn()
        f.session.maybeStartHandsFreeVoiceService()
        assertEquals(1, f.platform.starts.size)
        f.environment.walk = Environment.activeWalk("new-walk")
        f.session.maybeStartHandsFreeVoiceService()
        assertEquals(2, f.platform.starts.size)
    }

    @Test fun serviceAdmissionKeepsPermissionDisclosureCapabilityAndOtherInputGuards() {
        for (guard in listOf("foreground", "page", "feature", "capability", "disclosure", "notification", "microphone", "input", "wake", "walk")) {
            withFixture { f ->
                f.environment.active = false
                f.prepare()
                f.environment.active = true
                when (guard) {
                    "foreground" -> f.environment.foreground = false
                    "page" -> f.environment.page = f.environment.page.copy(pendingVoiceCommand = true)
                    "feature" -> f.environment.feature = false
                    "capability" -> f.environment.capability = WalkSafeStartupCapabilityDecision(
                        WalkSafeStartupCapabilityTier.BLOCKED, listOf(WalkSafeStartupRequirement.ON_DEVICE_STT), emptyList(), "blocked")
                    "disclosure" -> f.environment.disclosure = false
                    "notification" -> f.environment.notifications = false
                    "microphone" -> f.environment.microphone = false
                    "input" -> f.input.busy = true
                    "wake" -> f.input.probe = true
                    "walk" -> f.environment.active = false
                }
                f.session.maybeStartHandsFreeVoiceService()
                assertTrue(guard, f.platform.starts.isEmpty())
                assertTrue(guard, f.platform.requests.isEmpty())
                if (guard == "microphone") assertEquals(listOf("voice_hands_free_microphone_permission_missing"), f.effects.permissionChanges)
            }
        }
    }

    @Test fun platformStartRestrictionsReportTypedFailureAndWaitForForegroundReturn() {
        for ((error, failure) in listOf(
            SecurityException("denied") to WalkVoiceStartFailure.SECURITY,
            IllegalStateException("restricted") to WalkVoiceStartFailure.FOREGROUND_START_RESTRICTED,
        )) withFixture { f ->
            f.platform.startFailure = error
            f.prepare()
            assertEquals(listOf(failure), f.platform.reported)
            assertEquals(1, f.platform.stops)
            f.session.scheduleHandsFreeVoiceRestart()
            f.scheduler.advance(500)
            assertEquals(1, f.platform.starts.size)
            f.platform.startFailure = null
            f.session.onStopped()
            f.session.onBeforeForegroundReturn()
            f.session.maybeStartHandsFreeVoiceService()
            assertEquals(2, f.platform.starts.size)
        }
    }

    @Test fun transientFailureRestartsButPersistentAndSilencedFailuresKeepTheirDistinctOutcomes() = withFixture { f ->
        f.prepare()
        val transient = HandsFreeVoiceTerminalFailure.Transcriber(VoskStreamingError.AUDIO_READ_FAILED)
        f.session.handleHandsFreeVoiceTerminalFailure(transient)
        assertEquals(0, f.effects.limits)
        assertEquals(1, f.scheduler.pending.size)
        f.scheduler.advance(500)
        assertEquals(2, f.platform.starts.size)
        f.platform.callbacks!!.listeningStarted()
        assertEquals(1, f.input.listening)
        f.session.handleHandsFreeVoiceTerminalFailure(transient)
        assertEquals(0, f.effects.limits)
        f.session.handleHandsFreeVoiceTerminalFailure(transient)
        assertEquals(1, f.effects.limits)
        assertTrue(f.scheduler.pending.isEmpty())
        f.session.clearTranscriberFailureHistory()
        f.session.handleHandsFreeVoiceTerminalFailure(HandsFreeVoiceTerminalFailure.Transcriber(VoskStreamingError.AUDIO_SILENCED))
        assertEquals(1, f.effects.limits)
        assertTrue(f.scheduler.pending.isEmpty())
        assertEquals(1, f.output.spoken.size)
    }

    @Test fun closeRetiresThenStopsBeforeDetachingAndShuttingDownTheModelWorker() = withFixture { f ->
        f.prepare()
        f.events.clear()
        f.session.closeHandsFreeVoice()
        assertTrue(f.session.destroyed)
        assertEquals(listOf("input_retired", "capture_clear", "request_invalidated", "controller_stop", "service_stop",
            "listener_clear", "controller_detach", "controller_close", "ack_close", "worker_shutdown"), f.events)
        val events = f.events.toList()
        f.session.closeHandsFreeVoice()
        assertEquals(events, f.events)
    }

    private fun withFixture(test: (Fixture) -> Unit) {
        val f = Fixture()
        try { test(f) } finally {
            f.session.closeHandsFreeVoice()
            f.platform.created.forEach { it.close() }
            f.directory.delete()
        }
    }

    private class Fixture {
        val events = mutableListOf<String>()
        val directory = Files.createTempDirectory("hands-free-runtime-test").toFile()
        val environment = Environment()
        val input = Input(events)
        val presentation = Presentation()
        val effects = Effects()
        val output = Output()
        val scheduler = Scheduler()
        val platform = Platform(directory, scheduler, events)
        val session = HandsFreeRuntimeSession(environment, input, presentation, effects, output, scheduler, platform, Diagnostics)
        fun prepare() {
            session.prepareHandsFreeVoiceModel()
            platform.work.last().invoke()
            scheduler.drainMain()
        }
    }

    private enum class Page { GUIDANCE }
    private class Environment : VoiceInputEnvironment {
        var queries = 0
        var active = true
        var foreground = true
        var feature = true
        var disclosure = true
        var notifications = true
        var microphone = true
        var capability: WalkSafeStartupCapabilityDecision? = null
        var walk = activeWalk("walk")
        companion object {
            fun activeWalk(id: String): WalkSessionSnapshot {
                val epoch = WalkRuntimeEpoch(id, 0)
                val plan = WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL)
                val readiness = WalkSessionReadinessSnapshot(epoch, plan, 0,
                    WalkSessionReadinessRequirement.entries.associateWith { requirement ->
                        WalkSessionReadinessObservation(epoch, requirement,
                            if (plan.requires(requirement)) WalkSessionReadinessStatus.READY else WalkSessionReadinessStatus.NOT_REQUIRED,
                            "runtime fixture")
                    })
                return WalkSessionSnapshot(epoch, WalkSessionState.ACTIVE, WalkSessionMode.FULL, null, true, readiness)
            }
        }
        var page = VoiceInputPage(Page.GUIDANCE, false, false, true, false, false, true)
        override fun identity() = error("runtime does not own command identity")
        override fun page(): VoiceInputPage { queries++; return page }
        override fun walkOrNull(): WalkSessionSnapshot { queries++; return walk }
        override fun foreground(): Boolean { queries++; return foreground }
        override fun feedbackLifetimeGeneration() = error("runtime does not own speech lifetime")
        override fun capabilityDecision(): WalkSafeStartupCapabilityDecision? { queries++; return capability }
        override fun capabilityAllows(decision: WalkSafeStartupCapabilityDecision, requirement: WalkSafeStartupRequirement) =
            requirement !in decision.unavailableRequirements && requirement !in decision.pendingRequirements
        override fun microphoneGranted(): Boolean { queries++; return microphone }
        override fun notificationGranted(): Boolean { queries++; return notifications }
        override fun handsFreeFeatureEnabled(): Boolean { queries++; return feature }
        override fun voiceGuidanceFeatureEnabled() = error("not used")
        override fun disclosureAccepted(): Boolean { queries++; return disclosure }
        override fun homeFeatureContextAvailable() = error("arbitration belongs to input")
        override fun startupCapabilityConfirmed() = error("not used")
        override fun runtimeActive(): Boolean { queries++; return active }
        override fun confirmation() = error("not used")
        override fun commandWork() = error("command work belongs to input")
        override fun pendingNavigationDecisionToken(): RouteNavigatorDecisionToken? = error("decision capture belongs to input")
        override fun takeoverConflictAvailable(epoch: WalkRuntimeEpoch) = error("not used")
    }

    private class Input(private val events: MutableList<String>) : HandsFreeRuntimeInput {
        var busy = false
        var probe = false
        var listening = 0
        var onRetired: () -> Unit = {}
        override fun refreshWakeAndHasProbe() = probe
        override fun otherInputBusy() = busy
        override fun outputActive() = false
        override fun recognitionBlocked() = false
        override fun modelPrepared() = Unit
        override fun recognizedCommand(text: String, confidence: Float?) = Unit
        override fun commandListeningStarted() { listening++ }
        override fun clearCommandCapture() { events += "capture_clear" }
        override fun commandWorkPending(commandId: Long) = false
        override fun lowConfidenceReprompt(onFinished: (WakeAcknowledgementResult) -> Unit) = Unit
        override fun runtimeRetired() { events += "input_retired"; onRetired() }
    }

    private class Presentation : VoiceInputPresentation {
        val messages = mutableListOf<String>()
        override fun voiceStatus(message: String) { messages += message }
        override fun navigationStatus(message: String) { messages += message }
        override fun refreshControls(active: Boolean?) = Unit
        override fun status(title: String, detail: String) { messages += title; messages += detail }
        override fun lastResult(message: String) { messages += message }
        override fun recognitionSignal(message: String) = Unit
        override fun showCommandPage() = Unit
        override fun canShowStatus() = true
    }
    private class Effects : VoiceInputApplicationEffects {
        var limits = 0
        val permissionChanges = mutableListOf<String>()
        override fun requireStartupCapabilityConfirmation() = true
        override fun permissionStateChanged(reason: String) { permissionChanges += reason }
        override fun showMicrophoneDenial() = Unit
        override fun continueDeviceCheck() = Unit
        override fun refreshCapabilityUi() = Unit
        override fun limitPersistentHandsFreeRecognition() { limits++ }
    }
    private class Output : VoiceInputOutput {
        val spoken = mutableListOf<String>()
        override fun commandResponsePending() = false
        override fun commandGeneration() = 0L
        override fun nativeOrAccessibilitySpeechActive() = false
        override fun nativeRecognitionBlocked() = false
        override fun prepareForRecognition(): Boolean? = true
        override fun canSpeakNavigation(nowMs: Long) = true
        override fun speakInteraction(message: String): Boolean { spoken += message; return true }
        override fun speakPreparingInput(message: String, onCompleted: () -> Unit, onFailed: () -> Unit) = false
        override fun cancelCommandResponse() = Unit
        override fun playListeningStarted() = Unit
        override fun playListeningEnded() = Unit
        override fun speakLowConfidenceReprompt(message: String, onCompleted: () -> Unit, onFailed: () -> Unit) = false
        override fun screenReaderActive() = false
    }
    private class Scheduler : VoiceInputScheduler, VoiceCallbackRuntime {
        var now = 0L
        val pending = mutableListOf<Pair<Long, Runnable>>()
        val main = mutableListOf<() -> Unit>()
        override fun nowMs() = now
        override fun isMainThread() = true
        override fun runOnMain(action: () -> Unit) { main += action }
        override fun canScheduleRestart() = true
        override fun postRestart(task: Runnable, delayMs: Long) { pending += now + delayMs to task }
        override fun removeRestart(task: Runnable) { pending.removeAll { it.second === task } }
        override fun postDeferred(task: Runnable, delayMs: Long) = postRestart(task, delayMs)
        override fun removeDeferred(task: Runnable) = removeRestart(task)
        override fun checkMainThread(owner: String) = Unit
        override fun post(task: Runnable) = postRestart(task, 0)
        override fun postDelayed(task: Runnable, delayMs: Long) = postRestart(task, delayMs)
        override fun removeCallbacks(task: Runnable) = removeRestart(task)
        override fun removeAllCallbacks() = error("no owner may clear shared scheduled work")
        fun drainMain() { while (main.isNotEmpty()) main.removeAt(0).invoke() }
        fun advance(ms: Long) {
            val target = now + ms
            while (true) {
                val next = pending.filter { it.first <= target }.minByOrNull { it.first } ?: break
                pending.remove(next); now = next.first; next.second.run()
            }
            now = target
        }
    }
    private data class Request(val id: Long, val controller: HandsFreeVoiceController,
        val fail: (Long, WalkVoiceSessionController, WalkVoiceStartFailure) -> Unit)
    private class Platform(private val directory: File, private val scheduler: Scheduler,
        private val events: MutableList<String>) : VoiceInputPlatform {
        var rejectWork = false
        var modelFailure: RuntimeException? = null
        var startFailure: RuntimeException? = null
        var callbacks: HandsFreeInputCallbacks? = null
        var userStop: ((WalkVoiceSessionController) -> Unit)? = null
        var attached: HandsFreeVoiceController? = null
        var stops = 0
        val work = mutableListOf<() -> Unit>()
        val created = mutableListOf<HandsFreeVoiceController>()
        val requests = mutableListOf<Request>()
        val starts = mutableListOf<Long>()
        val invalidated = mutableListOf<Long>()
        val reported = mutableListOf<WalkVoiceStartFailure>()
        val stoppedWalks = mutableSetOf<String>()
        override fun applicationAvailable() = true
        override fun modelAssetAvailable(): Boolean { events += "asset_check"; return true }
        override fun prepareInstalledModel(): File { modelFailure?.let { throw it }; return directory }
        override fun executeModelWork(action: () -> Unit) {
            if (rejectWork) throw RejectedExecutionException("controlled rejection")
            work += action
        }
        override fun shutdownModelWorker() { events += "worker_shutdown" }
        override fun createRecognizer(modelDirectory: () -> File?): VoiceCommandRecognizer = error("one-shot belongs to input")
        override fun commandIntent(): Intent = error("one-shot belongs to input")
        override fun createHandsFree(modelDirectory: File, callbacks: HandsFreeInputCallbacks): HandsFreeVoiceController {
            this.callbacks = callbacks
            return HandsFreeVoiceController(
                HandsFreeVoiceDependencies(scheduler, { _, _, _ -> object : HandsFreeStreamingPort {
                    override fun start(onRunReserved: (Long) -> Unit): Long? = error("native start is outside this test")
                    override fun isRunning() = false
                    override fun stop() { events += "controller_stop" }
                    override fun close() { events += "controller_close" }
                } }, {}, {}, { events += "ack_close" }),
                callbacks.eligibility, callbacks.speechActive, callbacks.command, callbacks.status,
                callbacks.listeningStarted, callbacks.terminalFailure, callbacks.sessionEnded,
                callbacks.recognitionBlocked, callbacks.workPending,
            ).also { created += it }
        }
        override fun attachHandsFree(controller: HandsFreeVoiceController): Boolean { attached = controller; return true }
        override fun detachHandsFree(controller: HandsFreeVoiceController) { events += "controller_detach"; if (attached === controller) attached = null }
        override fun setUserStopListener(listener: ((WalkVoiceSessionController) -> Unit)?) {
            userStop = listener; if (listener == null) events += "listener_clear"
        }
        override fun isAttached(controller: HandsFreeVoiceController) = attached === controller
        override fun userStoppedWalk(walkId: String) = walkId in stoppedWalks
        override fun recordUserStop(walkId: String) { stoppedWalks += walkId }
        override fun registerStart(controller: HandsFreeVoiceController,
            onFailure: (Long, WalkVoiceSessionController, WalkVoiceStartFailure) -> Unit): Long =
            (requests.size + 1L).also { requests += Request(it, controller, onFailure) }
        override fun invalidateStart(requestId: Long, controller: HandsFreeVoiceController) {
            invalidated += requestId; events += "request_invalidated"
        }
        override fun invalidateStartOnSessionEnd(requestId: Long, controller: HandsFreeVoiceController): Boolean = true
        override fun reportStartFailure(requestId: Long, controller: HandsFreeVoiceController, failure: WalkVoiceStartFailure) {
            reported += failure
            requests.single { it.id == requestId }.fail(requestId, controller, failure)
        }
        override fun startService(requestId: Long) { starts += requestId; startFailure?.let { throw it } }
        override fun stopService() { stops++; events += "service_stop" }
        override fun inputHeldElsewhere() = false
    }
    private object Diagnostics : VoiceInputDiagnostics {
        override fun input(event: VoiceInputDiagnosticEvent, confidence: Float?, resultCount: Int?, errorCode: Int?) = Unit
        override fun wake(message: String) = Unit
        override fun serviceInfo(message: String) = Unit
        override fun serviceWarning(message: String, error: Throwable?) = Unit
    }
}
