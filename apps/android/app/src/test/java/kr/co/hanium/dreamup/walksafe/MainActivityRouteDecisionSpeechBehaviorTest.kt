package kr.co.hanium.dreamup.walksafe

import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.io.File
import java.util.Locale
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import kr.co.hanium.dreamup.walksafe.voice.*
import org.junit.Assert.*
import org.junit.Test

/** Actual Main decision ownership, Navigator and Actor; platform speech/clock/stream are doubles. */
class MainActivityRouteDecisionSpeechBehaviorTest {
    @Test fun riskPreemptionRetriesTheSameQuestionAndOnlyDoneMarksItAnnounced() {
        DecisionFixture().use { f ->
            assertTrue(f.speak())
            val firstQuestion = f.runtime.spoken.single().id
            f.runtime.start(firstQuestion)
            assertNull(MainActivityControllerTestFixture.decisionSpeechState(f.main).announcedToken)
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                f.actor.emit(FeedbackAction("risk", "멈춰 주세요", MessageLevel.STOP, null,
                    "decision-risk", 200_000L), null, null, { true }).speech)
            val risk = f.runtime.spoken.last().id
            assertEquals(1, f.retries.size)
            assertEquals(f.token, MainActivityControllerTestFixture.decisionSpeechState(f.main).pendingToken)
            f.runRetry()
            assertEquals("A pending hazard owns output until it completes", 2, f.runtime.spoken.size)
            assertEquals(1, f.retries.size)
            f.runtime.start(risk)
            f.runtime.done(risk)
            f.runRetry()
            val retried = f.runtime.spoken.last()
            assertEquals(f.message, retried.message)
            assertNotEquals(firstQuestion, retried.id)
            assertNull(MainActivityControllerTestFixture.decisionSpeechState(f.main).announcedToken)
            f.runtime.start(retried.id)
            f.runtime.done(retried.id)
            assertEquals(f.token, MainActivityControllerTestFixture.decisionSpeechState(f.main).announcedToken)
            assertNull(MainActivityControllerTestFixture.decisionSpeechState(f.main).pendingToken)
            assertTrue(f.speak())
            assertEquals(3, f.runtime.spoken.size)
            assertTrue(f.retries.isEmpty())
        }
    }

    @Test fun approvedRerouteCannotStartAQueuedQuestionOrScheduleItsRetry() {
        DecisionFixture().use { f ->
            assertTrue(f.speak())
            val id = f.runtime.spoken.single().id
            val approved = f.navigator.selectDeviationChoice(RouteDeviationChoice.NEW_ROUTE, f.token)
            assertEquals("off_route_reroute_approved", approved.reason)
            assertTrue(approved.shouldReroute)
            assertNull(f.navigator.pendingDecisionToken())
            f.runtime.start(id)
            f.runtime.done(id)
            assertEquals(1, f.runtime.stops)
            assertNull(MainActivityControllerTestFixture.decisionSpeechState(f.main).announcedToken)
            assertTrue(f.retries.isEmpty())
            assertFalse(f.speak())
            assertTrue(f.navigator.hasRoute())
        }
    }

    @Test fun oldRetryCannotSpeakAfterItsRouteScopeRetires() {
        DecisionFixture().use { f ->
            set(f.main, "voiceRecognitionActive", true)
            assertTrue(f.main.isGuidanceVoiceInputActive())
            assertFalse(f.speak())
            assertEquals(1, f.retries.size)
            f.scopeCurrent = false
            set(f.main, "voiceRecognitionActive", false)
            assertFalse(f.main.isGuidanceVoiceInputActive())
            f.runRetry()
            assertTrue(f.runtime.spoken.isEmpty())
            assertTrue(f.retries.isEmpty())
        }
    }

    @Test fun callbackFailurePlusRejectedSubmissionSchedulesOnlyOneRetry() {
        DecisionFixture().use { f ->
            var attempts = 0
            val submit: (String, () -> Boolean, () -> Unit, () -> Unit) -> Boolean = { _, _, _, fail ->
                attempts++
                fail()
                fail()
                false
            }
            assertFalse(f.main.speakCurrentRouteDecision(f.message, f.token,
                isScopeCurrent = { true }, submitSpeech = submit, enqueueRetry = { f.retries += it },
                outputBusy = { f.actor.isAppSpeechActive() }))
            assertEquals(1, attempts)
            assertEquals(1, f.retries.size)
            f.runRetry()
            assertEquals(2, attempts)
            assertEquals(1, f.retries.size)
            f.navigator.clear()
            f.runRetry()
            assertEquals(2, attempts)
            assertTrue(f.retries.isEmpty())
        }
    }

    @Test fun microphoneInputDefersTheQuestionThenRetriesWithoutLosingItsToken() {
        DecisionFixture().use { f ->
            set(f.main, "voiceRecognitionActive", true)
            assertTrue(f.main.isGuidanceVoiceInputActive())
            assertFalse(f.speak())
            assertTrue(f.runtime.spoken.isEmpty())
            assertEquals(f.token, MainActivityControllerTestFixture.decisionSpeechState(f.main).pendingToken)
            set(f.main, "voiceRecognitionActive", false)
            assertFalse(f.main.isGuidanceVoiceInputActive())
            f.runRetry()
            assertEquals(1, f.runtime.spoken.size)
            val question = f.runtime.spoken.single()
            f.runtime.start(question.id)
            f.runtime.done(question.id)
            assertEquals(f.token, MainActivityControllerTestFixture.decisionSpeechState(f.main).announcedToken)
        }
    }

    @Test fun wakeAcknowledgementAndCommandWindowDeferAutomaticDecisionButTheReplyIsNotBlocked() {
        DecisionFixture().use { f ->
            VoiceFixture(f.main).use { voice ->
                voice.start()
                voice.wake()
                assertTrue(f.main.isGuidanceVoiceInputActive())
                assertTrue(f.main.isGuidanceSpeechInputOrOutputBusy { false })
                assertFalse(f.speak())
                assertTrue(f.runtime.spoken.isEmpty())
                voice.finishAcknowledgement()
                assertTrue(f.main.isGuidanceVoiceInputActive())
                assertTrue(f.main.isGuidanceSpeechInputOrOutputBusy { false })
                f.runRetry()
                assertTrue(f.runtime.spoken.isEmpty())
                var busyDuringCommand: Boolean? = null
                var inputDuringCommand: Boolean? = null
                voice.onCommand = {
                    busyDuringCommand = f.main.isGuidanceSpeechInputOrOutputBusy { false }
                    inputDuringCommand = f.main.isGuidanceVoiceInputActive()
                }
                voice.command("현재 상태")
                assertEquals(false, busyDuringCommand)
                assertEquals(false, inputDuringCommand)
                assertFalse(f.main.isGuidanceVoiceInputActive())
                assertFalse(f.main.isGuidanceSpeechInputOrOutputBusy { false })
                f.runRetry()
                assertEquals(1, f.runtime.spoken.size)
            }
        }
    }

    @Test fun timedOutHandsFreeCommandDoesNotLeaveAutomaticGuidanceBusy() {
        val main = MainActivity()
        VoiceFixture(main).use { voice ->
            voice.start()
            voice.wake()
            voice.finishAcknowledgement()
            assertTrue(main.isGuidanceVoiceInputActive())
            assertTrue(main.isGuidanceSpeechInputOrOutputBusy { false })
            voice.runtime.advanceBy(7_000L)
            assertTrue(voice.statuses.contains("voice_hands_free=command_timeout"))
            assertFalse(main.isGuidanceVoiceInputActive())
            assertFalse(main.isGuidanceSpeechInputOrOutputBusy { false })
        }
    }

    @Test fun riskOutputStillPreemptsAnOpenHandsFreeCommandWindow() {
        DecisionFixture().use { f ->
            VoiceFixture(f.main, outputActive = { f.actor.isAppSpeechActive() }).use { voice ->
                voice.start()
                voice.wake()
                voice.finishAcknowledgement()
                assertTrue(f.main.isGuidanceVoiceInputActive())
                assertFalse(shouldSuppressFeedbackDuringVoiceRecognition(true, isRisk = true))
                assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                    f.actor.emit(FeedbackAction("urgent-risk", "멈춰 주세요", MessageLevel.STOP, null,
                        "command-window-risk", 200_000L), null, null, { true }).speech)
                val risk = f.runtime.spoken.single().id
                f.runtime.start(risk)
                voice.runtime.advanceBy(500L)
                assertTrue(voice.statuses.contains("voice_hands_free=command_interrupted_by_output"))
                assertFalse(f.main.isGuidanceVoiceInputActive())
                var dispatchedCommands = 0
                voice.onCommand = { dispatchedCommands++ }
                voice.command("현재 상태")
                assertEquals(0, dispatchedCommands)
                f.runtime.done(risk)
                voice.runtime.advanceBy(1_000L)
                assertFalse(f.main.isGuidanceVoiceInputActive())
            }
        }
    }

    @Test fun everyRoutineOutputEntryUsesInputOwnershipWhileRiskKeepsPriority() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val advisoryOwner = File("src/main/java/kr/co/hanium/dreamup/walksafe/feedback/delivery/CameraNonMetricAdvisoryDeliveryController.kt").readText()
        val advisoryPorts = MainActivityRefactorSource.composition(source, "cameraAdvisoryDelivery")
        val entries = listOf(
            "private fun dispatchNavigationSpeech(" to "!isGuidanceVoiceInputActive()",
            "private fun maybeSpeakCrosswalkReference(" to "!isGuidanceVoiceInputActive()",
            "internal fun maybeSpeakTactileDotReference(" to "!isGuidanceVoiceInputActive()",
            "private fun speakAdvisory(" to "if (isGuidanceVoiceInputActive())",
            "private fun maybePlayProgressBeep(" to "if (isGuidanceVoiceInputActive()) return",
            "private fun isCameraFallbackAdvisoryStillDeliverable(" to "!isGuidanceVoiceInputActive()",
        )
        entries.forEach { (entry, guard) ->
            when (entry) {
                "private fun dispatchNavigationSpeech(" -> {
                    assertTrue(MainActivityRefactorSource.function(source, entry).contains("reader.navigationOwnerIsCurrent(owner)"))
                    assertTrue(MainActivityRefactorSource.member(source, "createSpeechOutputController").contains(guard))
                }
                "private fun speakAdvisory(" -> {
                    assertTrue(advisoryPorts.contains("speechOutputController.speakAdvisory(message, deadline, current, delivered, failed)"))
                    assertTrue(MainActivityRefactorSource.member(MainActivityRefactorSource.speech, "speakAdvisory")
                        .contains("reader.guidanceVoiceInputActive()"))
                    assertTrue(MainActivityRefactorSource.member(source, "createSpeechOutputController")
                        .contains("override fun guidanceVoiceInputActive() = isGuidanceVoiceInputActive()"))
                }
                "private fun isCameraFallbackAdvisoryStillDeliverable(" -> {
                    val current = MainActivityRefactorSource.member(advisoryOwner, "stillDeliverable")
                    assertTrue(current.contains("authority.newOutputAllowed(nowMs)"))
                    assertTrue(current.contains("authority.ownerCurrent(epoch, fallbackGeneration, lifecycleGeneration, nowMs)"))
                    assertTrue(current.contains("nowMs <= action.validUntilMs"))
                    assertTrue(advisoryPorts.contains("override fun newOutputAllowed(nowMs: Long) ="))
                    assertTrue(advisoryPorts.contains("$guard && currentRawCameraFrameAllowsNewOutput(nowMs)"))
                }
                "internal fun maybeSpeakTactileDotReference(" -> {
                    assertTrue("$entry must keep the shared command-input guard",
                        sourceFunction(source, entry).contains("!authority.guidanceVoiceInputActive()"))
                    assertTrue(MainActivityRefactorSource.composition(source, "tactileDotDispatch")
                        .contains("override fun guidanceVoiceInputActive() = isGuidanceVoiceInputActive()"))
                    assertTrue(MainActivityRefactorSource.member(source, "maybeSpeakTactileDotReference", occurrence = 1)
                        .contains("tactileDotDispatch.maybeSpeak(owner, arLease, observeRuntimeAr, nowElapsedRealtimeMs, onUiThread, onDispatchResult, sourceIsCurrent)"))
                }
                else -> assertTrue("$entry must keep the shared command-input guard", sourceFunction(source, entry).contains(guard))
            }
        }
        val navigation = MainActivityRefactorSource.function(source, "private fun dispatchNavigationSpeech(")
        assertTrue(navigation.substringAfter("fun outputCurrent()").substringBefore("fun failOnce()")
            .contains("reader.navigationOwnerIsCurrent(owner)"))
        assertTrue(navigation.contains("isStillValid = ::outputCurrent"))
        val advisory = MainActivityRefactorSource.member(advisoryOwner, "dispatch")
        assertTrue(MainActivityRefactorSource.member(source, "createPrimaryCameraFallbackDetectorWorker")
            .contains("cameraAdvisoryDelivery.dispatch(action, epoch, generation, deliveryOwner)"))
        assertTrue(advisory.substringAfter("current = {").substringBefore("delivered = {")
            .contains("stillDeliverable(action, epoch, fallbackGeneration, lifecycleGeneration) &&"))
        assertTrue(advisory.contains("policy.isDeliveryCurrent(action)"))
        assertTrue(advisoryPorts.contains("policy = nonMetricAdvisoryPolicy"))
        assertTrue(advisoryPorts.contains("mountingLock = { phoneMountingObservationLock }"))
        assertTrue(advisoryPorts.contains("elapsedRealtimeMs = SystemClock::elapsedRealtime"))
        assertTrue(advisoryPorts.contains("recordDelivered = ::recordCameraFallbackAdvisoryDelivery"))
        assertTrue(advisoryPorts.contains("isCameraFallbackAdvisoryOwnerCurrent(\n                epoch, fallbackGeneration, lifecycleGeneration, nowMs)"))
        val outputAdvisory = MainActivityRefactorSource.member(MainActivityRefactorSource.speech, "speakAdvisory")
        assertTrue(outputAdvisory.contains("isStillValid = isStillDeliverable"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(outputAdvisory,
            "val terminal = AtomicBoolean(false)",
            "val delivered = { if (terminal.compareAndSet(false, true)) onDelivered() }",
            "val failed = { if (terminal.compareAndSet(false, true)) onFailed() }",
            "val accepted = ensureFeedbackActuator().speakAdvisory(",
            "onCompleted = delivered, onFailed = failed",
            "if (!accepted) failed()", "return accepted",
        ))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(advisory,
            "delivered = {", "val nowMs = elapsedRealtimeMs()", "val confirmed = complete(action, nowMs)",
            "authority.ownerCurrent(epoch, fallbackGeneration, lifecycleGeneration, nowMs)",
            "if (confirmed) recordDelivered(action, nowMs)",
        ))
        val completion = MainActivityRefactorSource.member(advisoryOwner, "complete")
        assertTrue(MainActivityRefactorSource.member(source, "completeCameraFallbackAdvisory")
            .contains("cameraAdvisoryDelivery.complete(action, nowMs, ownerCurrent)"))
        assertTrue(completion.contains("synchronized(mountingLock())"))
        assertTrue(completion.contains("!ownerCurrent() || !authority.mountingAllowed()"))
        assertTrue(completion.contains("policy.confirmDelivery(action, nowMs)"))
        assertFalse("A delivered sentence retains completion ownership", completion.contains("isGuidanceVoiceInputActive("))
        assertFalse("A real DONE keeps its owner after the START admission expires", completion.contains("newOutputAllowed("))
        assertFalse("A real DONE keeps its owner after the START admission expires", completion.contains("stillDeliverable("))
        val risk = sourceFunction(source, "private fun emitFeedbackAction(")
        assertTrue(risk.contains("val isRisk = action.level != MessageLevel.INFO"))
        assertTrue(MainActivityRefactorSource.composition(source, "riskFeedbackComponents.delivery")
            .contains("output = RiskVoicePeerAdapter({ speechOutputController }, { voiceInputController })"))
        val peers = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/composition/VoiceControllerPeerAdapters.kt").readText()
        val output = ReportStaticSourceInspector.blockAfter(peers, "internal class RiskVoicePeerAdapter")
        assertTrue(MainActivityRefactorSource.member(output, "guidanceVoiceInputActive")
            .contains("guidanceVoiceInputActive(input)"))
        val inputGuard = MainActivityRefactorSource.member(peers, "guidanceVoiceInputActive", occurrence = 1)
        assertTrue(inputGuard.contains("input().state.deferredPending || input().state.recognitionActive || input().gateway.isRecording() || input().commandInputActive()"))
        assertTrue(MainActivityRefactorSource.member(source, "isGuidanceVoiceInputActive")
            .contains("guidanceVoiceInputActive { voiceInputController }"))
        assertTrue(risk.contains("shouldSuppressFeedbackDuringVoiceRecognition(output.guidanceVoiceInputActive(), isRisk)"))
        val start = risk.substringAfter("val outputCurrent:").substringBefore("val cancelDelivery:")
        assertTrue("Routine INFO yields but urgent risk remains eligible at native START",
            start.contains("action.level != MessageLevel.INFO || !output.guidanceVoiceInputActive()"))
        assertTrue(risk.contains("isStillValidAtStart = outputCurrent"))
        val riskCompletion = risk.substringAfter("val completionCurrent:").substringBefore("val outputCurrent:")
        assertFalse("Input changes cannot erase a completed risk delivery", riskCompletion.contains("guidanceVoiceInputActive("))
    }

    @Test fun realCommandBridgeUsesTheDecisionGuardAndEveryFailureTerminal() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val bridge = MainActivityRefactorSource.member(source, "submitRouteDecisionSpeech")
        assertTrue(bridge.contains("statusExplanation = true"))
        assertTrue(bridge.contains("additionalCurrent = current"))
        assertTrue(bridge.contains("onCompleted = completed"))
        assertTrue(bridge.contains("onFailed = failed"))
        assertTrue(bridge.contains("onCancelled = failed"))
        assertTrue(bridge.contains("onRetired = failed"))
        val entry = MainActivityRefactorSource.function(source, "private fun speakRouteDecision(")
        assertTrue(entry.contains("speakCurrentRouteDecision("))
        assertTrue(entry.contains("isRouteSpeechCurrent(epoch, requestGeneration, lifecycleGeneration)"))
    }

    private class DecisionFixture : AutoCloseable {
        val main = MainActivity()
        val runtime = SpeechRuntime()
        val actor = AndroidFeedbackActuator(runtime = runtime)
        val navigator = field(main, "routeNavigator") as RouteNavigator
        val retries = mutableListOf<Runnable>()
        var scopeCurrent = true
        val token: RouteNavigatorDecisionToken
        val message: String
        init {
            val endpoint = point(100.0, -200.0)
            navigator.setRoute(WalkingRoute("TEST", WalkingRouteSummary(300, 300),
                listOf(point(0.0), point(100.0), endpoint),
                listOf(WalkingRouteGuidePoint(0, point(100.0), null, 100, 200, turnType = 13),
                    WalkingRouteGuidePoint(1, point(100.0, -150.0), null, 250, 50, turnType = 11))))
            // Two distinct trusted samples outside the real corridor open the supported reroute choice.
            val outside = point(0.0, 80.0)
            val first = navigator.update(TrustedLocation(outside.latitude, outside.longitude, 3f, runtime.now),
                runtime.now, false)
            assertEquals(RouteNavigatorUserDecision.LOCATION_RECHECK, first.pendingUserDecision)
            runtime.advanceBy(1_000L)
            val update = navigator.update(TrustedLocation(outside.latitude, outside.longitude, 3f, runtime.now),
                runtime.now, false)
            assertEquals(RouteNavigatorUserDecision.REROUTE, update.pendingUserDecision)
            assertFalse(update.shouldReroute)
            token = requireNotNull(navigator.pendingDecisionToken())
            assertEquals(RouteNavigatorUserDecision.REROUTE, token.decision)
            message = requireNotNull(update.instruction)
            set(main, "feedbackActuator", actor)
            runtime.ready()
        }
        fun speak(): Boolean = main.speakCurrentRouteDecision(message, token,
            isScopeCurrent = { scopeCurrent },
            // The production bounded Main body calls the actual guarded Main dispatcher/Actor.
            // Readiness/UI plumbing is the injected boundary; the default bridge is checked above.
            submitSpeech = { text, current, done, failed ->
                main.dispatchCommandSpeechResponse(actor, text, true, null, current,
                    completed = done, failed = failed, cancelled = failed) == NavigationSpeechDispatchResult.ACCEPTED
            }, enqueueRetry = { retries += it }, outputBusy = { actor.isAppSpeechActive() })
        fun runRetry() {
            val retry = retries.removeAt(0)
            runtime.advanceBy(1_000L)
            retry.run()
        }
        override fun close() { actor.close() }
    }

    private class VoiceFixture(
        private val main: MainActivity,
        outputActive: () -> Boolean = { false },
    ) : AutoCloseable {
        val runtime = VoiceRuntime()
        val statuses = mutableListOf<String>()
        var onCommand: () -> Unit = {}
        private var acknowledgement: ((WakeAcknowledgementResult) -> Unit)? = null
        private lateinit var stream: Stream
        val controller = HandsFreeVoiceController(
            dependencies = HandsFreeVoiceDependencies(runtime,
                createStream = { listener, _, generation -> Stream(listener, generation).also { stream = it } },
                playAcknowledgement = { acknowledgement = it }, cancelAcknowledgement = {}, closeAcknowledgement = {}),
            eligibility = { HandsFreeVoiceEligibility(WalkSessionState.ACTIVE, true, true, true) },
            isAppSpeechActive = outputActive, onCommand = { _, _ -> onCommand() },
            onStatus = { statuses += it })
        init { set(main, "handsFreeVoiceController", controller) }
        fun start() { assertTrue(controller.start()); stream.ready(); runtime.drain() }
        fun wake() { stream.transcript("길라잡이"); runtime.drain() }
        fun finishAcknowledgement() { requireNotNull(acknowledgement)(WakeAcknowledgementResult.COMPLETED); runtime.drain() }
        fun command(text: String) { stream.transcript(text); runtime.drain() }
        override fun close() { controller.close(); set(main, "handsFreeVoiceController", null) }
    }

    private class Stream(private val listener: VoskStreamingListener,
        private val generation: () -> Long) : HandsFreeStreamingPort {
        private var running = false
        override fun start(onRunReserved: (Long) -> Unit): Long { running = true; onRunReserved(1L); return 1L }
        override fun isRunning() = running
        override fun stop() { running = false }
        override fun close() { running = false }
        fun ready() = listener.onReady(1L)
        fun transcript(text: String) = listener.onTranscript(1L, VoskTranscript(text, .95f, true), generation())
    }

    private class VoiceRuntime : VoiceCallbackRuntime {
        private data class Task(val at: Long, val order: Long, val runnable: Runnable)
        private var now = 0L
        private var order = 0L
        private val tasks = mutableListOf<Task>()
        override fun checkMainThread(owner: String) = Unit
        override fun nowMs() = now
        override fun post(task: Runnable) = postDelayed(task, 0L)
        override fun postDelayed(task: Runnable, delayMs: Long) { tasks += Task(now + delayMs, order++, task) }
        override fun removeCallbacks(task: Runnable) { tasks.removeAll { it.runnable === task } }
        override fun removeAllCallbacks() = error("must not cancel unrelated callback owners")
        fun drain() = advanceBy(0L)
        fun advanceBy(delta: Long) {
            val target = now + delta
            while (true) {
                val next = tasks.filter { it.at <= target }.minWithOrNull(compareBy<Task> { it.at }.thenBy { it.order }) ?: break
                tasks.remove(next); now = next.at; next.runnable.run()
            }
            now = target
        }
    }

    private class SpeechRuntime : FeedbackSpeechRuntime, FeedbackSpeechScheduler {
        data class Spoken(val message: String, val id: String)
        private data class Task(val at: Long, val order: Long, val runnable: Runnable)
        var now = 1_000L
        var stops = 0
        val spoken = mutableListOf<Spoken>()
        private var initialized: ((Int) -> Unit)? = null
        private var listener: FeedbackSpeechRuntime.Listener? = null
        private var order = 0L
        private val tasks = mutableListOf<Task>()
        override val scheduler: FeedbackSpeechScheduler get() = this
        override val engineInitialized: Boolean get() = initialized != null
        override fun elapsedRealtime() = now
        override fun initialize(onInitialized: (Int) -> Unit) { initialized = onInitialized }
        override fun setLanguage(language: Locale) = TextToSpeech.LANG_AVAILABLE
        override fun selectOfflineKoreanVoice() = KoreanOfflineVoiceSelection.SELECTED
        override fun configureAudioAttributes() = Unit
        override fun setListener(listener: FeedbackSpeechRuntime.Listener): Int { this.listener = listener; return TextToSpeech.SUCCESS }
        override fun speak(message: String, queueMode: Int, utteranceId: String): Int {
            spoken += Spoken(message, utteranceId); return TextToSpeech.SUCCESS
        }
        override fun stop() { stops++ }
        override fun shutdown() = Unit
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener) = true
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) = Unit
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = error("speech only")
        override fun vibrate(patternMs: LongArray) = true
        override fun post(task: Runnable) = postDelayed(task, 0L)
        override fun postDelayed(task: Runnable, delayMs: Long): Boolean {
            tasks += Task(now + delayMs, order++, task); return true
        }
        override fun removeCallbacks(task: Runnable) { tasks.removeAll { it.runnable === task } }
        override fun removeCallbacksAndMessages(token: Any?) { tasks.clear() }
        fun ready() { requireNotNull(initialized)(TextToSpeech.SUCCESS); advanceBy(0L) }
        fun start(id: String) { requireNotNull(listener).onStart(id); advanceBy(0L) }
        fun done(id: String) { requireNotNull(listener).onDone(id); advanceBy(0L) }
        fun advanceBy(delta: Long) {
            val target = now + delta
            while (true) {
                val next = tasks.filter { it.at <= target }.minWithOrNull(compareBy<Task> { it.at }.thenBy { it.order }) ?: break
                tasks.remove(next); now = next.at; next.runnable.run()
            }
            now = target
        }
    }

    private fun sourceFunction(source: String, marker: String): String {
        return MainActivityRefactorSource.function(source, marker)
    }

    companion object {
        private fun field(main: MainActivity, name: String): Any? = MainActivityControllerTestFixture.field(name)
            .apply { isAccessible = true }.get(main)
        private fun set(main: MainActivity, name: String, value: Any?) = MainActivityControllerTestFixture.field(name)
            .apply { isAccessible = true }.set(main, value)
        private fun point(east: Double, north: Double = 0.0) = RoutePoint(north / 111194.92664455874, east / 111194.92664455874)
    }
}
