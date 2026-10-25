package kr.co.hanium.dreamup.walksafe

import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.util.Locale
import kr.co.hanium.dreamup.walksafe.feedback.AndroidFeedbackActuator
import kr.co.hanium.dreamup.walksafe.feedback.FeedbackSpeechRuntime
import kr.co.hanium.dreamup.walksafe.feedback.FeedbackSpeechScheduler
import kr.co.hanium.dreamup.walksafe.feedback.NavigationSpeechDispatchResult
import kr.co.hanium.dreamup.walksafe.feedback.ProgressToneEffects
import kr.co.hanium.dreamup.walksafe.feedback.UtteranceCallbackRegistry
import kr.co.hanium.dreamup.walksafe.feedback.utteranceTerminalTimeoutMs
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Runtime cases use the production Main gate and actuator; the last two are SOURCE_ONLY. */
class MainActivityExternalFallbackNativeSpeechOwnershipTest {
    @Test
    fun externalNavigationHoldBlocksFreshNativeNavigationWithoutFocusOrDeferredReplay() {
        val main = MainActivity()
        setLong(main, "navigationAnnouncementHoldUntilMs", 1_300L)
        val runtime = FakeRuntime()
        val actuator = readyActuator(main, runtime)
        var completed = 0
        var failed = 0

        assertEquals(
            NavigationSpeechDispatchResult.UNAVAILABLE,
            actuator.speakNavigation(
                message = "만료 전에 생성한 길안내",
                onCompleted = { completed += 1 },
                onFailed = { failed += 1 },
                requiresExplicitTerminalCallback = true,
                isStillValidAtStart = { true },
            ),
        )
        assertTrue(runtime.spoken.isEmpty())
        assertEquals(0, runtime.focusRequests)
        assertFalse(actuator.isAppSpeechActive())

        runtime.advanceBy(300L)
        assertTrue(main.authorizeNativeFeedbackSpeech(runtime.now, true))
        assertEquals(
            NavigationSpeechDispatchResult.ACCEPTED,
            actuator.speakNavigation(
                message = "현재 확인한 새 길안내",
                onCompleted = { completed += 1 },
                onFailed = { failed += 1 },
                requiresExplicitTerminalCallback = true,
                isStillValidAtStart = { true },
            ),
        )
        assertEquals(listOf("현재 확인한 새 길안내"), runtime.spoken.map { it.message })
        assertEquals(1, runtime.focusRequests)
        runtime.start(runtime.spoken.single().id)
        runtime.done(runtime.spoken.single().id)
        assertEquals(1, completed)
        assertEquals(0, failed)
        assertFalse(actuator.isAppSpeechActive())
        runtime.advanceBy(8_000L)
        assertEquals(listOf("현재 확인한 새 길안내"), runtime.spoken.map { it.message })
    }

    @Test
    fun actualMainGateRejectsTheIssuedExternalRiskHold() {
        val main = MainActivity()
        setLong(main, "riskAnnouncementHoldUntilMs", 1_300L)
        assertFalse(main.authorizeNativeFeedbackSpeech(1_000L, true))
    }

    @Test
    fun actualMainGateRejectsTheIssuedExternalNavigationHold() {
        val main = MainActivity()
        setLong(main, "navigationAnnouncementHoldUntilMs", 1_300L)
        assertFalse(main.authorizeNativeFeedbackSpeech(1_000L, true))
    }

    @Test
    fun actualMainGateRejectsTheIssuedExternalInteractionHold() {
        val main = MainActivity()
        set(main, "lastInteractionAnnouncement", "외부 상호작용 안내")
        setLong(main, "lastInteractionAnnouncementMs", 1_000L)
        assertFalse(main.authorizeNativeFeedbackSpeech(1_000L, true))
        val until = 1_000L + utteranceTerminalTimeoutMs("외부 상호작용 안내".length)
        assertTrue(main.authorizeNativeFeedbackSpeech(until, true))
    }

    @Test
    fun actualMainGateRejectsTheIssuedExternalAdvisoryHold() {
        val main = MainActivity()
        set(main, "lastAdvisoryAnnouncement", "외부 참고 안내")
        setLong(main, "lastAdvisoryAnnouncementMs", 1_000L)
        assertFalse(main.authorizeNativeFeedbackSpeech(1_000L, true))
        val until = 1_000L + utteranceTerminalTimeoutMs("외부 참고 안내".length)
        assertTrue(main.authorizeNativeFeedbackSpeech(until, true))
    }

    @Test
    fun actualMainGateRejectsAPendingExternalInteractionUntilItIsRevoked() {
        val main = MainActivity()
        set(main, "pendingTalkBackInteraction", Runnable { })
        assertFalse(main.authorizeNativeFeedbackSpeech(1_000L, true))
        set(main, "pendingTalkBackInteraction", null)
        assertTrue(main.authorizeNativeFeedbackSpeech(1_000L, true))
    }

    @Test
    fun expiredRevokedOrDisabledExternalNavigationAllowsOneCurrentNativeUtterance() {
        for (release in listOf("expired", "revoked", "screen_reader_disabled")) {
            val main = MainActivity()
            setLong(main, "navigationAnnouncementHoldUntilMs", 1_300L)
            val runtime = FakeRuntime()
            var screenReaderActive = true
            val actuator = readyActuator(main, runtime) { screenReaderActive }
            when (release) {
                "expired" -> runtime.advanceBy(300L)
                "revoked" -> setLong(main, "navigationAnnouncementHoldUntilMs", 0L)
                "screen_reader_disabled" -> screenReaderActive = false
            }
            assertTrue(main.authorizeNativeFeedbackSpeech(runtime.now, screenReaderActive))
            var completed = 0
            assertEquals(
                NavigationSpeechDispatchResult.ACCEPTED,
                actuator.speakNavigation(
                    message = "현재 경로가 앞쪽입니다.",
                    onCompleted = { completed += 1 },
                    requiresExplicitTerminalCallback = true,
                    isStillValidAtStart = { true },
                ),
            )
            assertEquals(1, runtime.spoken.size)
            assertEquals(1, runtime.focusRequests)
            runtime.start(runtime.spoken.single().id)
            runtime.done(runtime.spoken.single().id)
            assertEquals(1, completed)
            assertFalse(actuator.isAppSpeechActive())
        }
    }

    @Test
    fun blankUnissuedMessagesNeverCreateAStartupExternalHold() {
        val main = MainActivity()
        assertTrue(main.authorizeNativeFeedbackSpeech(0L, true))
        assertTrue(main.authorizeNativeFeedbackSpeech(1_000L, true))
        setLong(main, "lastInteractionAnnouncementMs", 1_000L)
        setLong(main, "lastAdvisoryAnnouncementMs", 1_000L)
        assertTrue(main.authorizeNativeFeedbackSpeech(1_000L, true))
    }

    @Test
    fun anOwnedCommandCallbackDoesNotBlockItsOwnNativeInteraction() {
        val main = MainActivity()
        val registry = field("commandSpeechResponseCallbacks").get(main) as UtteranceCallbackRegistry
        var completed = 0
        var failed = 0
        registry.register("command-response-owner", { completed += 1 }, { failed += 1 })
        val runtime = FakeRuntime()
        val actuator = readyActuator(main, runtime)

        assertEquals(1, registry.size())
        assertTrue(main.authorizeNativeFeedbackSpeech(runtime.now, true))
        assertEquals(
            NavigationSpeechDispatchResult.ACCEPTED,
            actuator.speakInteraction(
                message = "명령을 확인했습니다.",
                onCompleted = {
                    registry.takeTerminalCallback("command-response-owner", completed = true, notifyFailure = true)?.invoke()
                },
                onFailed = {
                    registry.takeTerminalCallback("command-response-owner", completed = false, notifyFailure = true)?.invoke()
                },
            ),
        )
        assertEquals(listOf("명령을 확인했습니다."), runtime.spoken.map { it.message })
        assertEquals(1, runtime.focusRequests)
        runtime.start(runtime.spoken.single().id)
        runtime.done(runtime.spoken.single().id)
        assertEquals(1, completed)
        assertEquals(0, failed)
        assertEquals(0, registry.size())
    }

    @Test
    fun SOURCE_ONLY_homeCommandCallerChecksExternalHoldBeforeBypassingActuatorAdmission() {
        val command = MainActivityRefactorSource.function(source(), "private fun speakCommandResponse")
        val finalDispatch = ReportStaticSourceInspector.functionBlock(command, "fun dispatchWhenReady")
        assertExternalGuardBeforeNativeCall(finalDispatch, "dispatchCommandSpeechResponse(", "failed()")
        val shared = MainActivityRefactorSource.function(source(), "internal fun dispatchCommandSpeechResponse(")
        assertTrue(shared.contains("actuator.speakGuardedHomeCommandInteraction(message, isCurrent,"))
        assertTrue(shared.contains("actuator.speakGuardedHomeCommandInteraction(message, startCurrent"))
    }

    @Test
    fun SOURCE_ONLY_consentCallerChecksExternalHoldBeforeBypassingActuatorAdmission() {
        val owner = ReportStaticSourceInspector.read(
            "src/main/java/kr/co/hanium/dreamup/walksafe/session/account/AccountConsentSpeechOwner.kt",
        )
        val consent = ReportStaticSourceInspector.functionBlock(owner, "fun playAccountConsentClause")
        val finalChunk = ReportStaticSourceInspector.functionBlock(consent, "fun playChunk")
        assertExternalGuardBeforeNativeCall(finalChunk, "actuator.speakConsentClause(", "finish(false)",
            guardCall = "runtime.externalFallbackSpeechActive()")
        val binding = MainActivityRefactorSource.factory(source(), "createAccountConsentSpeechOwner")
        assertTrue(binding.contains("override fun externalFallbackSpeechActive() = externalFallbackSpeechOutputActive("))
    }

    private fun assertExternalGuardBeforeNativeCall(
        block: String,
        nativeCall: String,
        terminalFailure: String,
        guardCall: String = "externalFallbackSpeechOutputActive(",
    ) {
        val guard = block.indexOf(guardCall)
        val call = block.indexOf(nativeCall)
        assertTrue("SOURCE_ONLY: shared external hold guard must precede $nativeCall", guard >= 0 && call > guard)
        val guardedDispatch = block.substring(guard, call)
        assertTrue("SOURCE_ONLY: held output must release its caller", guardedDispatch.contains(terminalFailure))
        assertTrue("SOURCE_ONLY: held output must not reach native dispatch", guardedDispatch.contains("return"))
    }

    private fun source(): String = ReportStaticSourceInspector.read(
        "src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    )

    private fun field(name: String) = MainActivityControllerTestFixture.field(name).apply { isAccessible = true }
    private fun set(main: MainActivity, name: String, value: Any?) { field(name).set(main, value) }
    private fun setLong(main: MainActivity, name: String, value: Long) { field(name).setLong(main, value) }

    private fun readyActuator(
        main: MainActivity,
        runtime: FakeRuntime,
        screenReaderActive: () -> Boolean = { true },
    ): AndroidFeedbackActuator {
        val actuator = AndroidFeedbackActuator(
            runtime = runtime,
            speechAllowed = { main.authorizeNativeFeedbackSpeech(runtime.now, screenReaderActive()) },
        )
        set(main, "feedbackActuator", actuator)
        runtime.ready()
        assertEquals("tts=ready", actuator.statusText())
        return actuator
    }

    private class FakeRuntime : FeedbackSpeechRuntime, FeedbackSpeechScheduler {
        data class Spoken(val message: String, val id: String)
        private data class Task(val due: Long, val order: Long, val runnable: Runnable)
        var now = 1_000L
        val spoken = mutableListOf<Spoken>()
        var focusRequests = 0
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
            spoken += Spoken(message, utteranceId)
            return TextToSpeech.SUCCESS
        }
        override fun stop() = Unit
        override fun shutdown() = Unit
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener): Boolean {
            focusRequests += 1
            return true
        }
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) = Unit
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects =
            kotlin.error("speech ownership tests do not request progress tones")
        override fun post(task: Runnable): Boolean = postDelayed(task, 0L)
        override fun postDelayed(task: Runnable, delayMs: Long): Boolean {
            tasks += Task(now + delayMs.coerceAtLeast(0L), sequence++, task)
            return true
        }
        override fun removeCallbacks(task: Runnable) { tasks.removeAll { it.runnable === task } }
        override fun removeCallbacksAndMessages(token: Any?) { tasks.clear() }
        fun ready() {
            requireNotNull(initialized).invoke(TextToSpeech.SUCCESS)
            advanceBy(0L)
        }
        fun start(id: String) {
            requireNotNull(listener).onStart(id)
            advanceBy(0L)
        }
        fun done(id: String) {
            requireNotNull(listener).onDone(id)
            advanceBy(0L)
        }
        fun advanceBy(deltaMs: Long) {
            require(deltaMs >= 0L)
            val target = now + deltaMs
            while (true) {
                val next = tasks.filter { it.due <= target }
                    .minWithOrNull(compareBy<Task> { it.due }.thenBy { it.order }) ?: break
                tasks.remove(next)
                now = next.due
                next.runnable.run()
            }
            now = target
        }
    }
}
