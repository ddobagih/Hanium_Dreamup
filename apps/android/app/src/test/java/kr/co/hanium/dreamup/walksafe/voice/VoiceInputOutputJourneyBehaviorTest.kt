package kr.co.hanium.dreamup.walksafe.voice

import android.content.Intent
import android.media.AudioManager
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.io.File
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.Executor
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.feedback.delivery.*
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.session.*
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.voice.composition.*
import kr.co.hanium.dreamup.walksafe.voice.input.*
import kr.co.hanium.dreamup.walksafe.voice.interaction.*
import kr.co.hanium.dreamup.walksafe.voice.output.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Real input/output owners, both production peer adapters, actuator and callback registry.
 * Only SDK effects, clock, UI and external actor/session/permission facts are controlled.
 * This is host behavior evidence, not Android microphone/audio or a complete route journey.
 */
class VoiceInputOutputJourneyBehaviorTest {
    @Test fun realNativeDoneReleasesPromptThenOpensMicrophoneExactlyOnce() {
        val f = Fixture(); f.ready(); val id = f.beginPrompt()
        f.assertNoMicrophone()
        f.native.start(id); f.clock.advanceBy(500L); f.assertNoMicrophone()
        f.native.done(id); f.clock.advanceBy(1_000L)
        assertEquals(1, f.platform.recognizer.starts)
        assertTrue(f.core.state.recognitionActive)
        f.native.done(id); f.native.stopped(id, true); f.native.error(id)
        assertEquals(1, f.platform.recognizer.starts)
        assertTrue(f.core.state.recognitionActive)
    }

    @Test fun coldPromptWaitsForReadyAndNativeDoneBeforeOpeningMicrophone() {
        val f = Fixture()
        assertTrue(f.core.startVoiceCommandRecognition())
        assertTrue(f.core.state.promptPending)
        f.clock.advanceBy(500L); assertTrue(f.native.spoken.isEmpty()); f.assertNoMicrophone()
        f.native.ready(); assertEquals(1, f.native.spoken.size); f.assertNoMicrophone()
        f.finish(f.native.spoken.single().id)
        assertEquals(1, f.platform.recognizer.starts)
    }

    @Test fun failedInitializationCannotReleasePromptAndDoesNotSilentlyRetry() {
        val f = Fixture(); assertTrue(f.core.startVoiceCommandRecognition())
        f.native.failInitialization(); f.clock.advanceBy(30_000L)
        f.assertNoMicrophone(); assertFalse(f.core.state.promptPending)
        assertTrue(f.native.spoken.isEmpty())
        assertTrue(f.output.currentActuatorOrNull()!!.hasTerminalSpeechFailure())
    }

    @Test fun errorStopAndTerminalTimeoutNeverOpenMicrophoneOrAcceptLateDone() {
        for (terminal in listOf("error", "stop", "interrupted", "timeout")) {
            val f = Fixture(); f.ready(); val id = f.beginPrompt(); f.native.start(id)
            when (terminal) {
                "error" -> f.native.error(id)
                "stop" -> f.native.stopped(id, false)
                "interrupted" -> f.native.stopped(id, true)
                else -> f.clock.advanceBy(30_000L)
            }
            assertFalse(terminal, f.core.state.promptPending)
            f.native.done(id); f.clock.advanceBy(1_000L); f.assertNoMicrophone()
            assertFalse(terminal, f.output.hasPendingCommandResponse())
        }
    }

    @Test fun oldTerminalCallbacksCannotReleaseReplacementPromptAfterExplicitCancellation() {
        val f = Fixture(); f.ready(); val old = f.beginPrompt(); f.native.start(old)
        f.core.cancelVoiceCommandRecognition(); f.clock.advanceBy(2_000L)
        val fresh = f.beginPrompt()
        f.native.done(old); f.native.error(old); f.native.stopped(old, true)
        assertTrue(f.core.state.promptPending); f.assertNoMicrophone()
        f.finish(fresh); assertEquals(1, f.platform.recognizer.starts)
    }

    @Test fun changedOwnerBeforeNativeDoneRetiresPromptWithoutOpeningMicrophone() {
        for (change in listOf("actor", "process", "epoch", "page", "background", "lifetime", "permission")) {
            val f = Fixture(); f.ready(); val id = f.beginPrompt(); f.native.start(id)
            when (change) {
                "actor" -> f.environment.identity = f.environment.identity.copy(actorId = "other")
                "process" -> f.environment.identity = f.environment.identity.copy(processGeneration = 99)
                "epoch" -> f.environment.walk = f.environment.walk.copy(epoch = WalkRuntimeEpoch("other", 1))
                "page" -> f.environment.page = f.environment.page.copy(owner = Page.OTHER, voiceInteraction = false)
                "background" -> f.environment.foreground = false
                "lifetime" -> f.environment.lifetime++
                "permission" -> f.environment.microphone = false
            }
            f.finish(id); f.assertNoMicrophone()
            assertFalse(change, f.core.state.promptPending)
            assertFalse(change, f.output.hasPendingCommandResponse())
        }
    }

    @Test fun commandWaitsForNavigationTerminalWithoutAcknowledgingRetiredGuidanceThenRequiresPromptDone() {
        val f = Fixture(); f.ready(); var navigationDone = 0
        var navigationFailed = 0
        assertTrue(f.output.dispatchNavigationSpeech("첫 안내입니다.", { navigationDone++ }, { navigationFailed++ }))
        val navigation = f.native.spoken.single().id; f.native.start(navigation)
        assertTrue(f.core.startVoiceCommandRecognition()); assertTrue(f.core.state.deferredPending)
        f.clock.advanceBy(500L); f.assertNoMicrophone(); assertEquals(1, f.native.spoken.size)
        f.native.done(navigation); f.clock.advanceBy(1_000L)
        assertEquals(0, navigationDone); assertEquals(1, navigationFailed)
        assertEquals(2, f.native.spoken.size)
        assertTrue(f.core.state.promptPending); f.assertNoMicrophone()
        f.finish(f.native.spoken.last().id); assertEquals(1, f.platform.recognizer.starts)
    }

    @Test fun replacementStatusCancelsPendingInputWithoutLettingOldPromptDoneOpenMicrophone() {
        val f = Fixture(); f.ready(); val old = f.beginPrompt(); f.native.start(old)
        assertTrue(f.output.speakStatusExplanation("경로를 다시 확인하고 있습니다."))
        val status = f.native.spoken.last().id
        assertNotEquals(old, status); assertFalse(f.core.state.promptPending)
        f.native.done(old); f.finish(status); f.assertNoMicrophone()
    }

    @Test fun audioFocusDenialOrLossNeverCountsAsInputPromptCompletion() {
        for (change in listOf(0, AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)) {
            val f = Fixture(); f.ready()
            if (change == 0) f.native.focusAllowed = false
            f.core.startVoiceCommandRecognition()
            if (change != 0) {
                val id = f.native.spoken.single().id; f.native.start(id)
                f.native.focusLost(change); f.native.done(id)
            }
            f.clock.advanceBy(1_000L); f.assertNoMicrophone()
            assertFalse("focus=$change", f.core.state.promptPending)
        }
    }

    @Test fun talkBackDispatchCannotStandInForNativePromptDoneOrOpenMicrophone() {
        val f = Fixture(); f.screenReader = true
        f.output.ensureFeedbackActuator(); f.native.failInitialization()
        var routeCompleted = 0
        var routeFailed = 0
        f.output.dispatchNavigationSpeech("직진하세요.", { routeCompleted++ }, { routeFailed++ })
        assertEquals(listOf("직진하세요."), f.accessibilityMessages)
        assertEquals(0, routeCompleted); assertEquals(1, routeFailed)
        f.core.startVoiceCommandRecognition(); f.clock.advanceBy(30_000L)
        f.assertNoMicrophone(); assertFalse(f.core.state.promptPending)
        assertTrue(f.native.spoken.isEmpty())
    }

    @Test fun generalCommandResponseIsStoppedWhenItsOwnerChangesBeforeNativeStart() {
        for (change in listOf("epoch", "background", "actor", "page")) {
            val f = Fixture(); f.homeAvailable = false; f.ready()
            var completed = 0
            assertTrue(f.output.speakCommandResponse("보행을 종료할까요?", onCompleted = { completed++ }))
            val id = f.native.spoken.single().id
            val stops = f.native.stops
            when (change) {
                "epoch" -> f.environment.walk = f.environment.walk.copy(epoch = WalkRuntimeEpoch("other", 1))
                "background" -> f.environment.foreground = false
                "actor" -> f.environment.identity = f.environment.identity.copy(actorId = "other")
                "page" -> f.environment.page = f.environment.page.copy(owner = Page.OTHER, voiceInteraction = false)
            }
            f.native.start(id)
            assertTrue("$change must stop stale general speech at START", f.native.stops > stops)
            assertFalse(change, f.output.hasPendingCommandResponse())
            f.native.done(id); assertEquals(0, completed); f.assertNoMicrophone()
        }
    }

    @Test fun queuedCommandCannotAcquireNewActorEpochPageOrResponseOwnerOnMainThread() {
        for (change in listOf("actor", "process", "epoch", "page", "response")) {
            val f = Fixture(); f.ready(); f.clock.main = false
            var completed = 0; var failed = 0
            assertTrue(f.output.speakCommandResponse("이전 요청의 응답입니다.",
                onCompleted = { completed++ }, onFailed = { failed++ }))
            assertTrue(f.native.spoken.isEmpty())
            when (change) {
                "actor" -> f.environment.identity = f.environment.identity.copy(actorId = "other")
                "process" -> f.environment.identity = f.environment.identity.copy(processGeneration = 99)
                "epoch" -> f.environment.walk = f.environment.walk.copy(epoch = WalkRuntimeEpoch("other", 1))
                "page" -> f.environment.page = f.environment.page.copy(owner = Page.OTHER, voiceInteraction = false)
                "response" -> {
                    f.clock.main = true
                    assertTrue(f.output.speakStatusExplanation("현재 요청의 응답입니다."))
                }
            }
            f.clock.drainMain()
            assertEquals(change, 1, failed); assertEquals(change, 0, completed)
            assertFalse(change, f.native.spoken.any { it.message == "이전 요청의 응답입니다." })
            if (change == "response") {
                assertEquals(1, f.native.spoken.size)
                assertTrue(f.output.hasPendingCommandResponse())
                f.finish(f.native.spoken.single().id)
                assertFalse(f.output.hasPendingCommandResponse())
            }
        }
    }

    @Test fun currentGeneralCommandRetainsItsNativeCompletionSemantics() {
        val f = Fixture(); f.homeAvailable = false; f.ready()
        var completed = 0; var failed = 0; var cancelled = 0
        assertTrue(f.output.speakCommandResponse("보행을 종료할까요?", onCompleted = { completed++ },
            onFailed = { failed++ }, onCancelled = { cancelled++ }))
        val id = f.native.spoken.single().id
        f.native.start(id); assertEquals(0, completed)
        f.native.done(id); f.native.done(id)
        assertEquals(1, completed); assertEquals(0, failed); assertEquals(0, cancelled)
        assertFalse(f.output.hasPendingCommandResponse())
    }

    @Test fun completedInputCannotAcceptOldRecognitionAfterCancellationAndReplacement() {
        val f = Fixture(); f.ready(); f.finish(f.beginPrompt())
        val oldListener = f.platform.recognizer.listener!!
        f.core.cancelVoiceCommandRecognition(); f.clock.advanceBy(2_000L)
        f.finish(f.beginPrompt())
        oldListener.onResults(null); oldListener.onError(SpeechRecognizer.ERROR_NO_MATCH)
        assertTrue(f.commands.results.isEmpty()); assertEquals(0, f.commands.misses)
        assertTrue(f.core.state.recognitionActive)
        f.platform.recognizer.listener!!.onResults(null)
        assertEquals(1, f.commands.results.size)
        assertFalse(f.core.state.recognitionActive)
    }

    @Test fun recognitionResultCannotCrossActorProcessLifetimeOrPermissionOwnership() {
        for (change in listOf("actor", "process", "lifetime", "permission", "background")) {
            val f = Fixture(); f.ready(); f.finish(f.beginPrompt())
            val listener = f.platform.recognizer.listener!!
            when (change) {
                "actor" -> f.environment.identity = f.environment.identity.copy(actorId = "replacement")
                "process" -> f.environment.identity = f.environment.identity.copy(processGeneration = 5)
                "lifetime" -> f.environment.lifetime++
                "permission" -> f.environment.microphone = false
                "background" -> { f.environment.foreground = false; f.environment.walk = f.environment.walk.copy(isForeground = false) }
            }
            val messages = f.presentation.messages.size
            listener.onReadyForSpeech(null); listener.onBeginningOfSpeech(); listener.onEndOfSpeech()
            listener.onError(SpeechRecognizer.ERROR_NO_MATCH); listener.onResults(null)
            assertTrue("$change changed while microphone was listening", f.commands.results.isEmpty())
            assertEquals(change, 0, f.commands.misses)
            assertEquals(change, messages, f.presentation.messages.size)
        }
    }

    @Test fun processTransitionRetiresNativeInputAndSameActorCanMakeANewExplicitRequest() =
        verifyMainProcessTransition { input, snapshot -> input.onProcessSessionChanged(snapshot) }

    /** Shared by the real Main adapter test; the owner/SDK effects stay in this fixture. */
    internal fun verifyMainProcessTransition(dispatch: (VoiceInputSessionController, GatewaySessionProcessSnapshot) -> Unit) {
        val f = Fixture(); f.ready(); f.finish(f.beginPrompt())
        val oldListener = f.platform.recognizer.listener!!
        val cancellations = f.platform.recognizer.cancels
        f.environment.identity = f.environment.identity.copy(processGeneration = 5)
        val snapshot = GatewaySessionProcessSnapshot(GatewayFieldSession.verified("https://example.test", "actor",
            "walksafe_field_session=replacement", Long.MAX_VALUE), null, 5, false, null, false)
        dispatch(f.core, snapshot)
        assertFalse("Process transition must close local input immediately", f.core.state.recognitionActive)
        assertEquals(cancellations + 1, f.platform.recognizer.cancels)
        oldListener.onReadyForSpeech(null); oldListener.onResults(null); oldListener.onError(SpeechRecognizer.ERROR_NO_MATCH)
        assertTrue(f.commands.results.isEmpty()); assertEquals(0, f.commands.misses)
        f.clock.advanceBy(2_000L)
        f.finish(f.beginPrompt())
        assertEquals(2, f.platform.recognizer.starts); assertTrue(f.core.state.recognitionActive)
        f.platform.recognizer.listener!!.onResults(null)
        assertEquals(1, f.commands.results.size); assertFalse(f.core.state.recognitionActive)
    }

    @Test fun unchangedAndOlderProcessEventsCannotCancelCurrentLocalRecognition() {
        val f = Fixture(); f.ready(); f.finish(f.beginPrompt())
        val cancellations = f.platform.recognizer.cancels
        val session = GatewayFieldSession.verified("https://example.test", "actor", "walksafe_field_session=current", Long.MAX_VALUE)
        f.core.onProcessSessionChanged(GatewaySessionProcessSnapshot(session, null, 3, false, null, false))
        f.core.onProcessSessionChanged(GatewaySessionProcessSnapshot(null, null, 2, false, null, false))
        assertEquals(cancellations, f.platform.recognizer.cancels)
        assertTrue(f.core.state.recognitionActive)
        f.platform.recognizer.listener!!.onResults(null)
        assertEquals(1, f.commands.results.size)
    }

    @Test fun delayedProcessRetirementCannotCancelTheReplacementRecognizer() {
        val f = Fixture(); f.ready(); f.finish(f.beginPrompt())
        val oldListener = f.platform.recognizer.listener!!
        f.environment.identity = f.environment.identity.copy(processGeneration = 5)
        f.clock.main = false
        f.core.onProcessSessionChanged(GatewaySessionProcessSnapshot(null, null, 5, false, null, false))
        assertEquals(1, f.clock.mainQueue.size)
        f.clock.main = true
        f.core.cancelVoiceCommandRecognition(); f.clock.advanceBy(2_000L)
        f.finish(f.beginPrompt())
        val cancellations = f.platform.recognizer.cancels
        f.clock.drainMain()
        oldListener.onResults(null); oldListener.onError(SpeechRecognizer.ERROR_NO_MATCH)
        assertEquals(cancellations, f.platform.recognizer.cancels)
        assertTrue(f.core.state.recognitionActive); assertTrue(f.commands.results.isEmpty())
        f.platform.recognizer.listener!!.onResults(null)
        assertEquals(1, f.commands.results.size)
    }

    @Test fun nativeStartRequestRechecksSourceAndRestoresIdleAfterIdentityChangesDuringHandoff() {
        val f = Fixture(); f.ready(); val prompt = f.beginPrompt()
        f.platform.recognizer.beforeStart = {
            f.environment.identity = f.environment.identity.copy(processGeneration = 5)
        }
        f.finish(prompt)
        assertEquals(listOf(false), f.platform.recognizer.currentChecks)
        f.assertNoMicrophone(); assertFalse(f.core.state.promptPending)
        val oldListener = f.platform.recognizer.listener!!
        oldListener.onResults(null); assertTrue(f.commands.results.isEmpty())
        f.clock.advanceBy(2_000L); f.finish(f.beginPrompt())
        assertEquals(listOf(false, true), f.platform.recognizer.currentChecks)
        assertEquals(1, f.platform.recognizer.starts); assertTrue(f.core.state.recognitionActive)
    }

    @Test fun nativeStartFailureAfterOwnerChangeCannotLeaveInputActive() {
        val f = Fixture(); f.ready(); val prompt = f.beginPrompt()
        f.platform.recognizer.beforeStart = {
            f.environment.identity = f.environment.identity.copy(processGeneration = 5)
            throw IllegalStateException("SDK start failed after ownership changed")
        }
        f.finish(prompt)
        f.assertNoMicrophone(); assertFalse(f.core.state.promptPending)
        f.clock.advanceBy(2_000L); f.finish(f.beginPrompt())
        assertEquals(1, f.platform.recognizer.starts); assertTrue(f.core.state.recognitionActive)
    }

    private class Fixture {
        val environment = Environment()
        val commands = Commands()
        val presentation = Presentation()
        val clock = Clock()
        val platform = Platform()
        var screenReader = false
        var speechAllowed = true
        var homeAvailable = true
        var navigationCurrent = true
        val accessibilityMessages = mutableListOf<String>()
        val native = NativeRuntime(clock)
        lateinit var core: VoiceInputSessionController
        val policy = WalkSafeFeedbackPolicy()
        val risk = RiskFeedbackDeliveryController<Unit>(policy, Any(), Any(),
            unusedPort(), unusedPort(), unusedPort(), unusedPort(), unusedPort())
        val search = DestinationSearchController(unusedPort(), unusedPort(), unusedPort(),
            AndroidNavigationRequestCoordinator(), Executor { error("No search worker in speech fixture") },
            { error("No search result in speech fixture") }, { null })
        val interaction = VoiceInteractionControllers({ search }, { error("No route query in speech fixture") },
            unusedPort(), unusedPort(), unusedPort(), unusedPort(), unusedPort(), unusedPort(), unusedPort())
        val output = SpeechOutputController(
            reader = object : SpeechOutputReader {
                override fun lifetime() = SpeechOutputLifetime(environment.lifetime, environment.foreground)
                override fun commandContext() = CommandSpeechContext(core.state.recognitionGeneration,
                    environment.identity.actorId, environment.identity.processGeneration, environment.walk.epoch,
                    environment.page.owner, environment.page.voiceInteraction, true,
                    core.state.recognitionActive, core.gateway.isRecording())
                override fun navigationOwner() = NavigationSpeechOwner(environment.walk.epoch, "route", null)
                override fun navigationOwnerIsCurrent(owner: NavigationSpeechOwner) =
                    navigationCurrent && owner.epoch == environment.walk.epoch &&
                        !core.state.deferredPending && !guidanceVoiceInputActive { core }
                override fun homeFeatureContextAvailable() = homeAvailable
                override fun stateSourceIsCurrent(source: CurrentStateSpeechLease) = true
                override fun guidanceVoiceInputActive() = guidanceVoiceInputActive { core }
                override fun screenReaderActive() = screenReader
            },
            input = SpeechInputPeerAdapter({ core }, { interaction }),
            presentation = object : SpeechOutputPresentation {
                override fun showLastResult(message: String) { presentation.messages += message }
                override fun showVoiceStatus(message: String) { presentation.messages += message }
                override fun refreshVoiceControls() = Unit
                override fun navigationStatus(message: String) { presentation.messages += message }
                override fun commandTerminalDiagnostic(completed: Boolean) = Unit
                override fun accessibilityTargetOrNull() = SpeechAccessibilityTarget { message, _ ->
                    accessibilityMessages += message; true
                }
                override fun canScheduleInteraction() = true
                override fun postInteraction(task: Runnable, delayMs: Long) = clock.post(this, task, delayMs)
                override fun removeInteraction(task: Runnable) = clock.remove(this, task)
            },
            readyEffects = object : SpeechOutputReadyEffects {
                override fun refreshReadyUi() = Unit
                override fun reevaluateCurrentRisk(actuator: AndroidFeedbackActuator, lifecycleGeneration: Int, nowMs: Long) = Unit
                override fun retryCurrentNavigation(nowMs: Long) = Unit
                override fun offlineSpeechUnavailable(lifecycleGeneration: Int, actuator: AndroidFeedbackActuator) = Unit
                override fun beforeCommandReplacement(previousGeneration: Long) = Unit
            },
            storage = object : kr.co.hanium.dreamup.walksafe.voice.output.SpeechOutputStorageBinding {
                override fun invalidate() = Unit
                override fun refresh(actuator: AndroidFeedbackActuator) = Unit
            },
            actuatorFactory = SpeechOutputActuatorFactory { unavailable, ready ->
                AndroidFeedbackActuator(native, unavailable, { speechAllowed }, onOfflineKoreanSpeechReady = ready)
            }, scheduler = clock, debugDiagnostic = {},
        )
        init {
            core = VoiceInputSessionController(environment, commands, presentation, object : VoiceInputApplicationEffects {
                override fun requireStartupCapabilityConfirmation() = true
                override fun permissionStateChanged(reason: String) = Unit
                override fun showMicrophoneDenial() = Unit
                override fun continueDeviceCheck() = Unit
                override fun refreshCapabilityUi() = Unit
                override fun limitPersistentHandsFreeRecognition() = Unit
            }, VoiceInputSpeechPeerAdapter({ output }, { risk }, { clock.now }, { screenReader }),
                clock, platform, platform, platform, object : GatewayVoiceEnvironment {
                    override fun networkAllowed(reason: String) = false
                    override fun networkState() = MobileNetworkPreference.WIFI_ONLY to ActiveNetworkTransport.WIFI
                    override fun sessionPrerequisitesAllowed() = false
                    override fun processSnapshot() = GatewaySessionProcessSnapshot(null, null, 0, false, null, false)
                }, NoDiagnostics)
        }
        fun ready() { output.ensureFeedbackActuator(); native.ready() }
        fun beginPrompt(): String {
            assertTrue(core.startVoiceCommandRecognition())
            assertTrue(core.state.promptPending)
            return native.spoken.last().id
        }
        fun finish(id: String) { native.start(id); native.done(id); clock.advanceBy(1_000L) }
        fun assertNoMicrophone() {
            assertEquals(0, platform.recognizer.starts)
            assertFalse(core.state.recognitionActive)
        }
    }

    /** Any unexpected sensor/search/route query fails: no policy is supplied by these ports. */
    private companion object {
    inline fun <reified T> unusedPort(): T = Proxy.newProxyInstance(
        T::class.java.classLoader, arrayOf(T::class.java),
    ) { _, method, _ -> error("Unexpected ${T::class.java.simpleName}.${method.name}") } as T
    }

    private object NoDiagnostics : VoiceInputDiagnostics {
        override fun input(event: VoiceInputDiagnosticEvent, confidence: Float?, resultCount: Int?, errorCode: Int?) = Unit
        override fun wake(message: String) = Unit
        override fun serviceInfo(message: String) = Unit
        override fun serviceWarning(message: String, error: Throwable?) = Unit
    }
    private enum class Page { COMMAND, OTHER, GUIDANCE }
    private class Environment : VoiceInputEnvironment {
        var identity = VoiceInputIdentity("actor", 3)
        var page = VoiceInputPage(Page.COMMAND, true, true, false, false, false, true)
        var walk = WalkSessionSnapshot(WalkRuntimeEpoch("walk", 0), WalkSessionState.READY, WalkSessionMode.FULL, null, true, null)
        var work = VoiceInputCommandWork(0, false, 0, false)
        var decisionToken: kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorDecisionToken? = null
        var decisionQueries = 0
        var foreground = true
        var microphone = true
        var lifetime = 1
        override fun identity() = identity
        override fun page() = page
        override fun walkOrNull() = walk
        override fun foreground() = foreground
        override fun feedbackLifetimeGeneration() = lifetime
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
    private class Recognizer : VoiceCommandRecognizer {
        var listener: RecognitionListener? = null; var starts = 0
        var cancels = 0
        var beforeStart: (() -> Unit)? = null
        val currentChecks = mutableListOf<Boolean>()
        override fun setRecognitionListener(listener: RecognitionListener) { this.listener = listener }
        override fun startListening(intent: Intent, preferPlatform: Boolean, isRequestCurrent: () -> Boolean) {
            check(preferPlatform)
            val callback = beforeStart; beforeStart = null; callback?.invoke()
            val current = isRequestCurrent(); currentChecks += current
            if (current) starts++
        }
        override fun cancel() { cancels++ }
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
    private class Clock : SpeechOutputScheduler, VoiceInputScheduler {
        private data class Task(val owner: Any, val due: Long, val order: Long, val task: Runnable)
        private val tasks = mutableListOf<Task>()
        private var sequence = 0L
        var now = 1_000L
        val pendingTasks get() = tasks.size
        override fun nowMs() = now
        override fun canScheduleRestart() = true
        override fun postRestart(task: Runnable, delayMs: Long) = post(this, task, delayMs)
        override fun removeRestart(task: Runnable) = remove(this, task)
        override fun postDeferred(task: Runnable, delayMs: Long) = post(this, task, delayMs)
        override fun removeDeferred(task: Runnable) = remove(this, task)
        override fun elapsedRealtimeMs() = now
        var main = true
        val mainQueue = mutableListOf<() -> Unit>()
        override fun isMainThread() = main
        override fun runOnMain(action: () -> Unit) { if (main) action() else mainQueue += action }
        fun drainMain() {
            main = true
            while (mainQueue.isNotEmpty()) mainQueue.removeAt(0).invoke()
        }
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
        var focusAllowed = true
        val focusListeners = mutableListOf<AudioManager.OnAudioFocusChangeListener>()
        val abandoned = mutableListOf<AudioManager.OnAudioFocusChangeListener>()
        var shutdowns = 0
        var stops = 0
        private var initialized: ((Int) -> Unit)? = null
        private var listener: FeedbackSpeechRuntime.Listener? = null
        override val scheduler: FeedbackSpeechScheduler get() = this
        override val engineInitialized get() = initialized != null
        override fun elapsedRealtime() = clock.now
        override fun initialize(onInitialized: (Int) -> Unit) { initialized = onInitialized }
        override fun setLanguage(language: Locale) = TextToSpeech.LANG_AVAILABLE
        override fun selectOfflineKoreanVoice() = KoreanOfflineVoiceSelection.SELECTED
        override fun configureAudioAttributes() = Unit
        override fun setListener(listener: FeedbackSpeechRuntime.Listener): Int {
            this.listener = listener
            return TextToSpeech.SUCCESS
        }
        override fun speak(message: String, queueMode: Int, utteranceId: String): Int {
            spoken += Spoken(message, utteranceId)
            return TextToSpeech.SUCCESS
        }
        override fun stop() { stops++ }
        override fun shutdown() { shutdowns++ }
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener): Boolean {
            focusListeners += listener
            return focusAllowed
        }
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) { abandoned += listener }
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = kotlin.error("No tone requested")
        override fun post(task: Runnable): Boolean = postDelayed(task, 0L)
        override fun postDelayed(task: Runnable, delayMs: Long): Boolean { clock.post(this, task, delayMs); return true }
        override fun removeCallbacks(task: Runnable) = clock.remove(this, task)
        override fun removeCallbacksAndMessages(token: Any?) = clock.clear(this)
        fun ready() { requireNotNull(initialized).invoke(TextToSpeech.SUCCESS); clock.advanceBy(0L) }
        fun failInitialization() { requireNotNull(initialized).invoke(TextToSpeech.ERROR); clock.advanceBy(0L) }
        fun start(id: String) { requireNotNull(listener).onStart(id); clock.advanceBy(0L) }
        fun error(id: String, code: Int = TextToSpeech.ERROR) { requireNotNull(listener).onError(id, code); clock.advanceBy(0L) }
        fun stopped(id: String, interrupted: Boolean) { requireNotNull(listener).onStop(id, interrupted); clock.advanceBy(0L) }
        fun focusLost(change: Int) { focusListeners.last().onAudioFocusChange(change); clock.advanceBy(0L) }
        fun done(id: String) { requireNotNull(listener).onDone(id); clock.advanceBy(0L) }
    }
}
