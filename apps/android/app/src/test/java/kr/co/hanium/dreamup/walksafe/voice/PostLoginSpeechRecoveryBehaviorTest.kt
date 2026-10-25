package kr.co.hanium.dreamup.walksafe.voice

import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.util.Locale
import kr.co.hanium.dreamup.walksafe.feedback.AndroidFeedbackActuator
import kr.co.hanium.dreamup.walksafe.feedback.FeedbackSpeechRuntime
import kr.co.hanium.dreamup.walksafe.feedback.FeedbackSpeechScheduler
import kr.co.hanium.dreamup.walksafe.feedback.ProgressToneEffects
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.voice.output.*
import org.junit.Assert.*
import org.junit.Test

import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.session.WalkSessionReadinessStatus

/** Real device/output owners and actuator; only OS effects, probe facts and time are synthetic. */
class PostLoginSpeechRecoveryBehaviorTest {
    private class Fixture {
        val output = OutputFixture()
        val device = DeviceFixture()
        var replacements = 0
        var passEffects = 0
        init {
            device.effects.onOverrides = { device.reader.hardware = device.reader.hardware.copy(koreanSpeechOverride = it.koreanSpeech) }
            output.unavailableEffect = { device.reader.hardware = device.reader.hardware.copy(koreanSpeechOverride = false) }
            // Same wiring as MainActivity's afterPassed effect, after the real controller saves.
            device.effects.onPassed = onPassed@{ snapshot ->
                assertTrue(device.store.saves > 0)
                val lifecycleGeneration = output.lifetime.generation
                val isCurrent = {
                    output.lifetime.foreground && output.lifetime.generation == lifecycleGeneration &&
                        device.controller.isPassedSnapshotCurrent(snapshot)
                }
                if (!isCurrent()) return@onPassed PostLoginDeviceCheckPassContinuation.TRANSITION_REJECTED
                if (output.controller.restartFailedActuatorAfterDeviceCheck(isCurrent)) replacements++
                if (!isCurrent()) return@onPassed PostLoginDeviceCheckPassContinuation.TRANSITION_REJECTED
                passEffects++
                PostLoginDeviceCheckPassContinuation.COMPLETED
            }
        }
        fun pass() {
            device.ready()
            device.controller.maybeContinue()
            assertEquals(PostLoginDeviceCheckState.FULL, device.controller.snapshot.state)
        }
    }

    private fun failActor(f: OutputFixture, failure: String): AndroidFeedbackActuator {
        val actor = f.controller.ensureFeedbackActuator()
        when (failure) {
            "init_error" -> f.runtime.failInitialization()
            "missing_data" -> { f.runtime.languageStatus = TextToSpeech.LANG_MISSING_DATA; f.runtime.ready() }
            "unsupported_language" -> { f.runtime.languageStatus = TextToSpeech.LANG_NOT_SUPPORTED; f.runtime.ready() }
            "offline_voice_unavailable" -> { f.runtime.selection = KoreanOfflineVoiceSelection.UNAVAILABLE; f.runtime.ready() }
            else -> error("Unknown failure")
        }
        assertTrue(actor.hasTerminalSpeechFailure())
        assertFalse(actor.isSpeechInitializing())
        assertNull(actor.readySpeechEngineGenerationOrNull())
        return actor
    }

    @Test fun successfulExplicitRecheckReplacesEachTerminalFailureAndOnlyNativeDoneCompletesOutput() {
        for (failure in listOf("init_error", "missing_data", "unsupported_language", "offline_voice_unavailable")) {
            val f = Fixture()
            val o = f.output
            val d = f.device
            val old = failActor(o, failure)
            val oldRuntime = o.runtime
            val lifetime = o.lifetime
            d.controller.bindSession("actor", 1L)
            d.reader.hardware = d.reader.hardware.copy(startup = d.reader.hardware.startup.copy(offlineKoreanTextToSpeechAvailable = false))
            d.controller.handlePrimaryAction(false)
            assertEquals(PostLoginDeviceCheckState.FAIL, d.controller.snapshot.state)
            assertEquals(PostLoginDeviceCheckFailure.KOREAN_TTS_UNAVAILABLE, d.controller.snapshot.failure)
            o.clock.advanceBy(60_000L)
            assertEquals(1, oldRuntime.initializations) // engine retry budget remains zero
            assertSame(old, o.controller.currentActuatorOrNull())
            val attempt = d.controller.snapshot.attemptGeneration
            d.reader.hardware = d.reader.hardware.copy(startup = d.reader.hardware.startup.copy(offlineKoreanTextToSpeechAvailable = true))
            d.controller.handlePrimaryAction(false)
            f.pass()
            assertTrue(d.controller.snapshot.attemptGeneration > attempt)
            assertNull(d.reader.hardware.koreanSpeechOverride)
            assertEquals(1, f.replacements)
            assertEquals(lifetime, o.lifetime)
            assertEquals(2, o.runtimes.size)
            val replacement = o.controller.currentActuatorOrNull()!!
            assertNotSame(old, replacement)
            assertEquals("tts=closed", old.statusText())
            assertTrue(replacement.isSpeechInitializing())
            assertEquals(listOf("invalidate", "invalidate", "refresh"), o.storageTrace.takeLast(3))
            var completed = 0
            var failed = 0
            assertTrue(o.controller.speakCommandResponse("재점검 후 안내", onCompleted = { completed++ }, onFailed = { failed++ }))
            assertTrue(o.runtime.spoken.isEmpty())
            assertEquals(0, completed)
            o.runtime.ready()
            val speech = o.runtime.spoken.single()
            assertEquals(0, completed)
            o.runtime.start(speech.id)
            assertEquals(0, completed)
            o.runtime.done(speech.id); o.runtime.done(speech.id)
            assertEquals(1, completed)
            assertEquals(0, failed)
            assertTrue(oldRuntime.spoken.isEmpty())
            assertEquals(1, oldRuntime.initializations)
        }
    }

    @Test fun eligibleDeferredCameraRecheckRecoversFailedOutputInTheSameLifetime() {
        val f = Fixture(); val d = f.device; val o = f.output
        d.store.restored = PersistedPostLoginDeviceCheckResult(PostLoginDeviceCheckState.LIMITED,
            setOf(PostLoginDeviceCheckFeature.OBSTACLE_DETECTION, PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE),
            true, true, PostLoginMetricDepthState.PENDING)
        d.controller.bindSession("actor", 1L)
        assertTrue(d.controller.cameraDependentChecksDeferred)
        val old = failActor(o, "offline_voice_unavailable")
        assertEquals(false, d.reader.hardware.koreanSpeechOverride)
        d.store.restored = null
        d.controller.handlePrimaryAction(true)
        assertNull(d.reader.hardware.koreanSpeechOverride)
        f.pass()
        assertEquals(1, f.replacements)
        assertNotSame(old, o.controller.currentActuatorOrNull())
        o.runtime.ready()
        assertTrue(o.controller.speakCommandResponse("카메라 재점검 후 안내"))
        assertEquals(1, o.runtime.spoken.size)
    }

    @Test fun successfulCheckPreservesReadyInitializingAndAbsentOutputOwners() {
        for (state in listOf("ready", "initializing", "absent")) {
            val f = Fixture(); val o = f.output
            val actor = if (state == "absent") null else o.controller.ensureFeedbackActuator()
            if (state == "ready") o.runtime.ready()
            f.device.running(); f.pass()
            assertSame(state, actor, o.controller.currentActuatorOrNull())
            assertEquals(state, 0, f.replacements)
            assertEquals(state, if (state == "absent") 0 else 1, o.runtimes.size)
        }
    }

    @Test fun automaticSavedResultRestoreAndFullPrimaryNoopDoNotRetryFailedOutput() {
        val f = Fixture(); val d = f.device; val o = f.output
        val old = failActor(o, "missing_data")
        d.store.restored = PersistedPostLoginDeviceCheckResult(PostLoginDeviceCheckState.FULL,
            emptySet(), false, true, PostLoginMetricDepthState.SUPPORTED)
        d.controller.bindSession("actor", 1L)
        assertTrue(d.controller.passesFeatureGate())
        d.controller.handlePrimaryAction(false)
        o.factoryReadyCallbacks.first().invoke()
        o.clock.advanceBy(60_000L)
        assertSame(old, o.controller.currentActuatorOrNull())
        assertEquals(1, o.runtimes.size)
        assertEquals(0, d.effects.passes)
        assertEquals(0, f.replacements)
    }

    @Test fun failedSaveAndCancelledAttemptCannotRecreateOutputFromLateProbeCallbacks() {
        for (exit in listOf("save_failure", "background", "session_changed")) {
            val f = Fixture(); val d = f.device; val o = f.output
            val old = failActor(o, "init_error")
            d.running()
            val late = d.probes.changed!!
            when (exit) {
                "save_failure" -> d.store.saveSucceeds = false
                "background" -> { d.reader.actor = d.reader.actor.copy(foreground = false); d.controller.enteredBackground() }
                "session_changed" -> { d.reader.actor = d.reader.actor.copy(actorId = "next", verifiedActorId = "next", sessionGeneration = 2L); d.controller.bindSession("next", 2L) }
            }
            d.ready(); late()
            assertSame(exit, old, o.controller.currentActuatorOrNull())
            assertEquals(exit, 1, o.runtimes.size)
            assertEquals(exit, 0, f.replacements)
            assertFalse(exit, d.controller.snapshot.passesFeatureGate)
        }
    }

    @Test fun staleSuccessfulSnapshotCannotRestartAnActorForANewerSession() {
        val f = Fixture(); val d = f.device; val o = f.output
        d.running(); f.pass()
        val completed = d.controller.snapshot
        val old = failActor(o, "missing_data")
        d.reader.actor = d.reader.actor.copy(actorId = "next", verifiedActorId = "next", sessionGeneration = 2L)
        d.controller.bindSession("next", 2L)
        assertFalse(o.controller.restartFailedActuatorAfterDeviceCheck { d.controller.isPassedSnapshotCurrent(completed) })
        assertSame(old, o.controller.currentActuatorOrNull())
        assertEquals(1, o.runtimes.size)
    }

    @Test fun nativeCloseReentryKeepsTheReplacementAndRejectsRetiredReadyAndUnavailableCallbacks() {
        val f = Fixture(); val d = f.device; val o = f.output
        val old = failActor(o, "init_error")
        val oldRuntime = o.runtime
        var reentrant: AndroidFeedbackActuator? = null
        oldRuntime.onShutdown = { reentrant = o.controller.ensureFeedbackActuator() }
        d.running(); f.pass()
        assertNotSame(old, reentrant)
        assertSame(reentrant, o.controller.currentActuatorOrNull())
        assertEquals(2, o.runtimes.size)
        o.factoryReadyCallbacks.first().invoke()
        o.factoryUnavailableCallbacks.first().invoke()
        oldRuntime.ready()
        assertTrue(o.readyTrace.isEmpty())
        assertNull(d.reader.hardware.koreanSpeechOverride)
        o.runtime.ready()
        assertEquals(listOf("ui", "risk", "navigation"), o.readyTrace)
    }

    @Test fun lossOfLifetimeOrCheckOwnershipDuringNativeCloseDoesNotAcquireAnotherActor() {
        for (loss in listOf("background", "lifecycle", "session")) {
            val f = Fixture(); val d = f.device; val o = f.output
            failActor(o, "init_error")
            o.runtime.onShutdown = {
                when (loss) {
                    "background" -> o.lifetime = o.lifetime.copy(foreground = false)
                    "lifecycle" -> o.lifetime = o.lifetime.copy(generation = 8)
                    "session" -> {
                        d.reader.actor = d.reader.actor.copy(sessionGeneration = 2L)
                        d.controller.bindSession("actor", 2L)
                    }
                }
            }
            d.running(); d.ready(); d.controller.maybeContinue()
            assertEquals(loss, if (loss == "session") PostLoginDeviceCheckState.NOT_RUN else PostLoginDeviceCheckState.FULL,
                d.controller.snapshot.state)
            if (loss == "session") assertEquals(2L, d.controller.snapshot.sessionGeneration)
            assertNull(loss, o.controller.currentActuatorOrNull())
            assertEquals(loss, 1, o.runtimes.size)
            assertEquals(loss, 0, f.replacements)
            assertEquals("$loss must suppress successful pass effects", 0, f.passEffects)
            assertEquals("$loss must not schedule calibration", 0, d.effects.calibrations)
        }
    }

    @Test fun pendingOldCommandIsRetiredBeforeReplacementAndCannotCompleteANewResponse() {
        val f = Fixture(); val o = f.output
        var retired = 0; var oldTerminal = 0; var newDone = 0
        o.controller.speakCommandResponse("오래된 준비 안내", preparingInput = true,
            onCompleted = { oldTerminal++ }, onFailed = { oldTerminal++ }, onRetired = { retired++ })
        o.runtime.failInitialization()
        assertTrue(o.controller.hasPendingCommandResponse())
        f.device.running(); f.pass()
        assertFalse(o.controller.hasPendingCommandResponse())
        o.controller.speakCommandResponse("새 안내", onCompleted = { newDone++ })
        o.factoryReadyCallbacks.first().invoke()
        o.clock.advanceBy(100L)
        assertEquals(1, retired)
        assertEquals(0, oldTerminal)
        assertTrue(o.runtime.spoken.isEmpty())
        o.runtime.ready()
        val speech = o.runtime.spoken.single()
        assertEquals("새 안내", speech.message)
        o.runtime.start(speech.id); o.runtime.done(speech.id)
        assertEquals(1, newDone)
        assertEquals(0, oldTerminal)
    }
    private enum class Page { HOME, VOICE, SETTINGS }

    private class OutputFixture {
        val clock = Clock()
        var unavailableEffect: () -> Unit = {}
        var lifetime = SpeechOutputLifetime(7, true)
        var context = CommandSpeechContext(3, "actor", 11L, WalkRuntimeEpoch("walk", 0L),
            Page.VOICE, true, true, false, false)
        var navigationCurrent = true
        var screenReader = false
        val promptEffects = mutableListOf<Pair<CommandResponseToken, CommandPromptEffect>>()
        val readyTrace = mutableListOf<String>()
        val diagnosticTrace = mutableListOf<String>()
        val storageTrace = mutableListOf<String>()
        val accessibilityMessages = mutableListOf<String>()
        val runtimes = mutableListOf<NativeRuntime>()
        val factoryReadyCallbacks = mutableListOf<() -> Unit>()
        val factoryUnavailableCallbacks = mutableListOf<() -> Unit>()
        val runtime get() = runtimes.last()
        var onRisk: ((AndroidFeedbackActuator, Int, Long) -> Unit)? = null
        val controller = SpeechOutputController(
            debugDiagnostic = { diagnosticTrace += it },
            reader = object : SpeechOutputReader {
                override fun lifetime() = this@OutputFixture.lifetime
                override fun commandContext() = context
                override fun navigationOwner() = NavigationSpeechOwner(context.walkEpoch, "route", null)
                override fun navigationOwnerIsCurrent(owner: NavigationSpeechOwner) = navigationCurrent
                override fun homeFeatureContextAvailable() = true
                override fun stateSourceIsCurrent(source: CurrentStateSpeechLease) = true
                override fun guidanceVoiceInputActive() = context.recognitionActive
                override fun screenReaderActive() = screenReader
            },
            input = object : SpeechOutputInputEffects {
                override fun invalidateHomeWakeInput() = Unit
                override fun promptState(token: CommandResponseToken, effect: CommandPromptEffect) {
                    promptEffects += token to effect
                }
                override fun cancelDeferredInput() = Unit
                override fun refreshHomeWakeListening() = Unit
                override fun cancelHomeWakeListening() = Unit
                override fun cancelCommandDialogs(token: CommandResponseToken) = Unit
            },
            presentation = object : SpeechOutputPresentation {
                override fun showLastResult(message: String) = Unit
                override fun showVoiceStatus(message: String) = Unit
                override fun refreshVoiceControls() = Unit
                override fun navigationStatus(message: String) = Unit
                override fun commandTerminalDiagnostic(completed: Boolean) = Unit
                override fun accessibilityTargetOrNull() = SpeechAccessibilityTarget { message, _ ->
                    accessibilityMessages += message
                    true
                }
                override fun canScheduleInteraction() = true
                override fun postInteraction(task: Runnable, delayMs: Long) = clock.post(this, task, delayMs)
                override fun removeInteraction(task: Runnable) = clock.remove(this, task)
            },
            readyEffects = object : SpeechOutputReadyEffects {
                override fun refreshReadyUi() { readyTrace += "ui" }
                override fun reevaluateCurrentRisk(actuator: AndroidFeedbackActuator, lifecycleGeneration: Int, nowMs: Long) {
                    readyTrace += "risk"
                    onRisk?.invoke(actuator, lifecycleGeneration, nowMs)
                }
                override fun retryCurrentNavigation(nowMs: Long) { readyTrace += "navigation" }
                override fun offlineSpeechUnavailable(lifecycleGeneration: Int, actuator: AndroidFeedbackActuator) = unavailableEffect()
                override fun beforeCommandReplacement(previousGeneration: Long) = Unit
            },
            storage = object : SpeechOutputStorageBinding {
                override fun invalidate() { storageTrace += "invalidate" }
                override fun refresh(actuator: AndroidFeedbackActuator) { storageTrace += "refresh" }
            },
            actuatorFactory = SpeechOutputActuatorFactory { unavailable, ready ->
                val native = NativeRuntime(clock).also(runtimes::add)
                factoryReadyCallbacks += ready
                factoryUnavailableCallbacks += unavailable
                AndroidFeedbackActuator(runtime = native, onOfflineKoreanSpeechUnavailable = unavailable,
                    onOfflineKoreanSpeechReady = ready)
            },
            scheduler = clock,
        )
    }

    private class Clock : SpeechOutputScheduler {
        private data class Task(val owner: Any, val due: Long, val order: Long, val task: Runnable)
        private val tasks = mutableListOf<Task>()
        private var sequence = 0L
        var now = 1_000L
        val pendingTasks get() = tasks.size
        override fun elapsedRealtimeMs() = now
        override fun isMainThread() = true
        override fun runOnMain(action: () -> Unit) = action()
        override fun postReadiness(task: Runnable, delayMs: Long) = post(this, task, delayMs)
        override fun removeReadiness(task: Runnable) = remove(this, task)
        override fun postRetired(action: () -> Unit) = post(this, Runnable(action), 0L)
        fun post(owner: Any, task: Runnable, delayMs: Long) {
            tasks += Task(owner, now + delayMs.coerceAtLeast(0L), sequence++, task)
        }
        fun remove(owner: Any, task: Runnable) { tasks.removeAll { it.owner === owner && it.task === task } }
        fun clear(owner: Any) { tasks.removeAll { it.owner === owner } }
        fun advanceBy(deltaMs: Long) {
            val target = now + deltaMs
            while (true) {
                val next = tasks.filter { it.due <= target }
                    .minWithOrNull(compareBy<Task> { it.due }.thenBy { it.order }) ?: break
                tasks.remove(next)
                now = maxOf(now, next.due)
                next.task.run()
            }
            now = target
        }
    }

    private class NativeRuntime(private val clock: Clock) : FeedbackSpeechRuntime, FeedbackSpeechScheduler {
        data class Spoken(val message: String, val id: String)
        val spoken = mutableListOf<Spoken>()
        var shutdowns = 0
        var initializations = 0
        var onShutdown: () -> Unit = {}
        var languageStatus = TextToSpeech.LANG_AVAILABLE
        var selection = KoreanOfflineVoiceSelection.SELECTED
        private var initialized: ((Int) -> Unit)? = null
        private var listener: FeedbackSpeechRuntime.Listener? = null
        override val scheduler: FeedbackSpeechScheduler get() = this
        override val engineInitialized get() = initialized != null
        override fun elapsedRealtime() = clock.now
        override fun initialize(onInitialized: (Int) -> Unit) { initialized = onInitialized; initializations++ }
        override fun setLanguage(language: Locale) = languageStatus
        override fun selectOfflineKoreanVoice() = selection
        override fun configureAudioAttributes() = Unit
        override fun setListener(listener: FeedbackSpeechRuntime.Listener): Int {
            this.listener = listener
            return TextToSpeech.SUCCESS
        }
        override fun speak(message: String, queueMode: Int, utteranceId: String): Int {
            spoken += Spoken(message, utteranceId)
            return TextToSpeech.SUCCESS
        }
        override fun stop() = Unit
        override fun shutdown() { shutdowns++; onShutdown() }
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener) = true
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) = Unit
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = error("No tone requested")
        override fun post(task: Runnable): Boolean = postDelayed(task, 0L)
        override fun postDelayed(task: Runnable, delayMs: Long): Boolean { clock.post(this, task, delayMs); return true }
        override fun removeCallbacks(task: Runnable) = clock.remove(this, task)
        override fun removeCallbacksAndMessages(token: Any?) = clock.clear(this)
        fun ready() { requireNotNull(initialized).invoke(TextToSpeech.SUCCESS); clock.advanceBy(0L) }
        fun failInitialization() { requireNotNull(initialized).invoke(TextToSpeech.ERROR); clock.advanceBy(0L) }
        fun start(id: String) { requireNotNull(listener).onStart(id); clock.advanceBy(0L) }
        fun done(id: String) { requireNotNull(listener).onDone(id); clock.advanceBy(0L) }
    }
    private class DeviceFixture {
        val calls = mutableListOf<String>()
        val reader = DeviceReader()
        val store = DeviceStore()
        val probes = DeviceProbes(calls)
        val permissions = DevicePermissions()
        val scheduler = DeviceScheduler()
        val effects = DeviceEffects(calls)
        val controller = PostLoginDeviceCheckController(reader, store, probes, permissions, effects, scheduler, PostLoginDeviceCheckController.Config())
        init { effects.snapshot = { controller.snapshot } }
        fun running() = apply { controller.bindSession("actor", 1L); controller.beginFromPrimaryAction() }
        fun ready() { probes.value = PostLoginDeviceCheckProbeSignals(PostLoginDeviceCheckSignal.READY, PostLoginDeviceCheckSignal.READY, PostLoginDeviceCheckSignal.READY, PostLoginMetricDepthState.SUPPORTED) }
    }

    private class DeviceReader : PostLoginDeviceCheckFactsReader {
        var reads = 0
        var actor = PostLoginDeviceCheckActorFacts(true, "actor", 1L, "actor", true, true, false, false)
        var hardware = PostLoginDeviceCheckHardwareFacts(
            WalkSafeStartupCapabilityInput(true, true, true, true, true, true, true, true, true, "profile"),
            WalkSessionReadinessStatus.READY, false,
            PostLoginDeviceCheckDetectorFacts(true, false, true, true, PostLoginDeviceCheckSignal.READY),
            emptyList(), true, true, true, true, false, null, null,
        )
        override fun actor() = actor.also { reads++ }
        override fun hardware() = hardware.also { reads++ }
        override fun resultBinding(actorId: String) = PostLoginDeviceCheckResultBinding(
            actorId, "installation", "maker", "model", "device", 36, "fingerprint", "app", 1, "1", "source", "policy",
            null, null, "registry", "sha", null, null, emptySet(), true, true, true, true,
        ).also { reads++ }
    }

    private class DeviceStore : PostLoginDeviceCheckSavedResults {
        var restored: PersistedPostLoginDeviceCheckResult? = null
        var saveSucceeds = true
        var saves = 0
        override fun candidatePresent() = restored != null
        override fun restore(binding: PostLoginDeviceCheckResultBinding) = restored
        override fun save(snapshot: PostLoginDeviceCheckSnapshot, binding: PostLoginDeviceCheckResultBinding, cameraDependentChecksDeferred: Boolean, metricDepthState: PostLoginMetricDepthState): Boolean { saves++; return saveSucceeds }
    }

    private class DevicePermissions : PostLoginDeviceCheckPermissionPort {
        var missing = emptyList<String>()
        var disclosureSaved = true
        override fun acknowledgeVoiceDisclosure() = disclosureSaved
        override fun requestedPermissions() = missing
        override fun request(binding: PostLoginDeviceCheckBinding, permissions: List<String>) = 42
        override fun cancel(requestCode: Int) = Unit
        override fun ownsDialog(requestCode: Int) = requestCode == 42
    }

    private class DeviceScheduler : PostLoginDeviceCheckScheduler {
        data class Task(val delay: Long, val action: () -> Unit, var cancelled: Boolean = false) : DeviceCheckScheduledTask {
            override fun cancel() { cancelled = true }
        }
        val tasks = mutableListOf<Task>()
        override fun schedule(delayMs: Long, action: () -> Unit) = Task(delayMs, action).also(tasks::add)
    }

    private class DeviceProbes(private val calls: MutableList<String>) : PostLoginDeviceCheckProbeSuite {
        var value = pending()
        var changed: (() -> Unit)? = null
        override val signals get() = value
        override val metricPreflightStarted = false
        override val capabilityProbeStarted = false
        override val storedBindingValidationPending = false
        override fun resetForAttempt(binding: PostLoginDeviceCheckBinding) { value = pending() }
        override fun restartCapabilityAndResources(binding: PostLoginDeviceCheckBinding, onChanged: () -> Unit) { changed = onChanged }
        override fun prepareVoiceModelAndDetector() { calls += "prepareModels" }
        override fun startAutomaticProbes(binding: PostLoginDeviceCheckBinding) { calls += "startAutomatic" }
        override fun startCamera(binding: PostLoginDeviceCheckBinding) { calls += "cameraStart" }
        override fun startMetricPreflight(binding: PostLoginDeviceCheckBinding) { calls += "metricStart" }
        override fun cancelCalibration(reason: String) { calls += "calibrationCancel:$reason" }
        override fun cancelFunctionalProbes() { calls += "functionalCancel" }
        override fun cancelMetricPreflight(reason: String) { calls += "metricCancel:$reason" }
        override fun closeCapabilityAndResetResources() { calls += "capabilityClose" }
        override fun clearStoredBindingValidation() = Unit
        override fun stopCamera() { calls += "cameraStop" }
        override fun restoreMetricDepth(state: PostLoginMetricDepthState) { value = value.copy(metricDepth = state) }
        override fun mayValidateStoredBinding() = false
        override fun startStoredBindingValidation(onChanged: () -> Unit) = false
        override fun mayStartStoredMetricRefresh(binding: PostLoginDeviceCheckBinding) = false
        override fun startStoredMetricRefresh(binding: PostLoginDeviceCheckBinding) { calls += "storedMetricStart" }
        private fun pending() = PostLoginDeviceCheckProbeSignals(PostLoginDeviceCheckSignal.PENDING, PostLoginDeviceCheckSignal.PENDING, PostLoginDeviceCheckSignal.PENDING, PostLoginMetricDepthState.PENDING)
    }

    private class DeviceEffects(private val calls: MutableList<String>) : PostLoginDeviceCheckEffects {
        var snapshot: () -> PostLoginDeviceCheckSnapshot = { PostLoginDeviceCheckPolicy.initial() }
        val statesDuringInvalidation = mutableListOf<PostLoginDeviceCheckState>()
        val overrides = mutableListOf<PostLoginDeviceCheckCapabilityOverrides>()
        var passes = 0
        var onPassed: (PostLoginDeviceCheckSnapshot) -> PostLoginDeviceCheckPassContinuation = { PostLoginDeviceCheckPassContinuation.COMPLETED }
        var onOverrides: (PostLoginDeviceCheckCapabilityOverrides) -> Unit = {}
        var calibrations = 0
        var terminals = 0
        override fun invalidateRouteResponseLease() { calls += "route"; statesDuringInvalidation += snapshot().state }
        override fun invalidateSpeechStorageLease() { calls += "speech"; statesDuringInvalidation += snapshot().state }
        override fun applyCapabilityOverrides(overrides: PostLoginDeviceCheckCapabilityOverrides) { this.overrides += overrides; onOverrides(overrides) }
        override fun clearAttemptPresentation() { calls += "clearPresentation" }
        override fun showStatus(title: String, detail: String) { calls += "status:$title" }
        override fun announceTerminal(message: String) { terminals++ }
        override fun failureMessage(failure: PostLoginDeviceCheckFailure?) = failure?.name.orEmpty()
        override fun refreshReadinessPresentation() = Unit
        override fun noteCancellation(reason: String) { calls += "cancel:$reason" }
        override fun recordPermissionObservation(binding: PostLoginDeviceCheckBinding) = true
        override fun afterPermissionObservation(binding: PostLoginDeviceCheckBinding) = Unit
        override fun afterPassed(snapshot: PostLoginDeviceCheckSnapshot): PostLoginDeviceCheckPassContinuation { passes++; return onPassed(snapshot) }
        override fun afterRestoredResult() = Unit
        override fun scheduleCalibration() { calibrations++ }
        override fun metricCapabilityChanged(available: Boolean) { calls += "metricChanged:$available" }
        override fun clearCameraCapabilityOverrides() = Unit
        override fun prepareDetectorRetry() = Unit
    }
}
