package kr.co.hanium.dreamup.walksafe

import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.io.File
import java.util.Locale
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.junit.Assert.*
import org.junit.Test

/** Actual admitted Main state/busy entries and Actor registration; native speech and scheduling vary. */
class MainActivitySpeechMessageFamilyHoldTest {
    private val risk = "전방 장애물. 즉시 멈추세요."

    @Test fun admittedLegacyRiskHoldCannotBlockTheMainNativeAuthorizationAfterEightSeconds() {
        val main = MainActivity()
        main.recordAcceptedTalkBackAnnouncement(risk, MainActivity.TalkBackAnnouncementPriority.RISK,
            1_000L, riskRank = 2, riskTrackId = "risk-1")
        assertTrue(main.externalFallbackSpeechOutputActive(8_999L, true))
        assertFalse(main.authorizeNativeFeedbackSpeech(8_999L, true))
        assertFalse("Actual Main hold must end at its original request plus the risk upper bound",
            main.externalFallbackSpeechOutputActive(9_000L, true))
        assertTrue(main.authorizeNativeFeedbackSpeech(9_000L, true))
    }

    @Test fun actualNavigationHoldUsesThirtySecondsFromTheIssuedRequest() {
        val main = MainActivity()
        main.recordAcceptedTalkBackAnnouncement("길안내".repeat(200), MainActivity.TalkBackAnnouncementPriority.NAVIGATION, 1_000L)
        assertFalse(main.authorizeNativeFeedbackSpeech(30_999L, true))
        assertTrue(main.authorizeNativeFeedbackSpeech(31_000L, true))
    }

    @Test fun actualGeneralInteractionHoldUsesSixtySecondsFromItsIssuedRequest() {
        val main = MainActivity()
        main.recordAcceptedTalkBackAnnouncement("안내".repeat(400), MainActivity.TalkBackAnnouncementPriority.INTERACTION, 1_000L)
        assertTrue(main.externalFallbackSpeechOutputActive(60_999L, true))
        assertFalse(main.externalFallbackSpeechOutputActive(61_000L, true))
    }

    @Test fun shortInteractionUsesItsOwnCharacterBudgetAndAdvisoryKeepsTheLegacyMinimum() {
        val main = MainActivity()
        main.recordAcceptedTalkBackAnnouncement("네", MainActivity.TalkBackAnnouncementPriority.INTERACTION, 1_000L)
        assertTrue(main.externalFallbackSpeechOutputActive(6_299L, true))
        assertFalse(main.externalFallbackSpeechOutputActive(6_300L, true))
        main.recordAcceptedTalkBackAnnouncement("참고", MainActivity.TalkBackAnnouncementPriority.ADVISORY, 7_000L)
        assertTrue(main.externalFallbackSpeechOutputActive(16_999L, true))
        assertFalse(main.externalFallbackSpeechOutputActive(17_000L, true))
    }

    @Test fun busyPollingAndNativeAuthorizationCannotRetagTheOriginalRequestClock() {
        val main = MainActivity()
        main.recordAcceptedTalkBackAnnouncement("길안내".repeat(200), MainActivity.TalkBackAnnouncementPriority.NAVIGATION, 1_000L)
        listOf(2_000L, 8_000L, 30_000L, 30_999L).forEach {
            assertTrue(main.externalFallbackSpeechOutputActive(it, true))
            assertFalse(main.authorizeNativeFeedbackSpeech(it, true))
        }
        repeat(3) {
            assertFalse(main.externalFallbackSpeechOutputActive(31_000L, true))
            assertTrue(main.authorizeNativeFeedbackSpeech(31_000L, true))
        }
    }

    @Test fun riskTrackCleanupClearsOnlyItsActualHoldAndPreservesCurrentNavigation() {
        val main = MainActivity()
        main.recordAcceptedTalkBackAnnouncement(risk, MainActivity.TalkBackAnnouncementPriority.RISK, 1_000L,
            riskTrackId = "current-risk")
        main.reconcileTalkBackRiskTracks(setOf("current-risk"))
        assertTrue(main.externalFallbackSpeechOutputActive(1_001L, true))
        main.reconcileTalkBackRiskTracks(emptySet())
        assertFalse(main.externalFallbackSpeechOutputActive(1_001L, true))
        main.recordAcceptedTalkBackAnnouncement("앞으로", MainActivity.TalkBackAnnouncementPriority.NAVIGATION, 1_100L)
        main.reconcileTalkBackRiskTracks(emptySet())
        assertFalse(main.authorizeNativeFeedbackSpeech(1_101L, true))
    }

    @Test fun disabledScreenReaderAndUnissuedStateDoNotCreateAnExternalHold() {
        val main = MainActivity()
        assertTrue(main.authorizeNativeFeedbackSpeech(0L, true))
        main.recordAcceptedTalkBackAnnouncement(risk, MainActivity.TalkBackAnnouncementPriority.RISK, 1_000L)
        assertFalse(main.externalFallbackSpeechOutputActive(1_000L, false))
        assertTrue(main.authorizeNativeFeedbackSpeech(1_000L, false))
    }

    @Test fun realNativeRiskRegistrationReachesTheSameMainBusyReadAndEndsAtItsFamilyBound() {
        Fixture().use { f ->
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                f.actor.emit(FeedbackAction("risk-1", risk, MessageLevel.STOP, null, "family-hold", 200_000L),
                    null, null, { true }).speech)
            val id = f.runtime.spoken.single().id
            assertTrue(f.busy())
            f.runtime.start(id)
            f.runtime.advanceBy(7_999L)
            assertTrue(f.busy())
            f.runtime.advanceBy(1L)
            assertFalse(f.busy())
        }
    }

    @Test fun realNativeNavigationDoneThenFreshInteractionDoNotInheritTheOldBudget() {
        Fixture().use { f ->
            var done = 0
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                f.actor.speakNavigation("길안내".repeat(200), onCompleted = { done++ }))
            val navigation = f.runtime.spoken.single().id
            f.runtime.start(navigation)
            f.runtime.advanceBy(1_000L)
            f.runtime.done(navigation)
            assertEquals(1, done)
            assertFalse(f.busy())
            assertTrue(f.actor.speakInteraction("네"))
            val interaction = f.runtime.spoken.last().id
            f.runtime.start(interaction)
            f.runtime.advanceBy(5_299L)
            assertTrue(f.busy())
            f.runtime.advanceBy(1L)
            assertFalse(f.busy())
        }
    }

    @Test fun realTrainingConsentAndConfirmationKeepTheirLongBudgetThroughTheMainBusyConsumer() {
        val entries: List<(AndroidFeedbackActuator, String) -> NavigationSpeechDispatchResult> = listOf(
            { actor, text -> actor.speakPriorityUserTraining(text, {}, {}) },
            { actor, text -> actor.speakConsentClause(text, {}, {}) },
            { actor, text -> actor.speakExplicitConfirmation(text, {}, {}) },
        )
        entries.forEach { entry -> Fixture().use { f ->
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, entry(f.actor, "안내".repeat(400)))
            val id = f.runtime.spoken.single().id
            assertTrue(id.startsWith("interaction-")) // Existing priority stays independent of its family.
            f.runtime.start(id)
            f.runtime.advanceBy(60_000L)
            assertTrue(f.busy())
            f.runtime.advanceBy(59_999L)
            assertTrue(f.busy())
            f.runtime.advanceBy(1L)
            assertFalse(f.busy())
        } }
    }

    @Test fun delayedNativeStartKeepsItsAdmissionFamilyBudgetWithoutExtendingInformationTtl() {
        Fixture().use { f ->
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                f.actor.speakConsentClause("안내".repeat(400), {}, {}))
            val id = f.runtime.spoken.single().id
            f.runtime.advanceBy(2_000L)
            f.runtime.start(id)
            f.runtime.advanceBy(119_999L)
            assertTrue(f.busy())
            f.runtime.advanceBy(1L)
            assertFalse(f.busy())
        }
        Fixture().use { f ->
            val expiry = f.runtime.now + 50L
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                f.actor.emit(FeedbackAction("risk-expiring", risk, MessageLevel.STOP, null, "family-hold", expiry),
                    null, null, { true }).speech)
            val id = f.runtime.spoken.single().id
            f.runtime.advanceBy(51L)
            f.runtime.start(id)
            assertFalse(f.busy())
        }
    }

    @Test fun actualActorCloseAndLateOldCallbacksCannotKeepTheMainBusyConsumerActive() {
        Fixture().use { f ->
            assertTrue(f.actor.speakInteraction("안내".repeat(400)))
            val old = f.runtime.spoken.single().id
            f.runtime.start(old)
            assertTrue(f.busy())
            f.actor.close()
            f.runtime.done(old)
            f.runtime.start(old)
            assertFalse(f.busy())
            assertFalse(f.busy(null))
        }
    }

    @Test fun actualPendingCommandRegistrySkipsActorClockAndAccessibilityObservations() {
        val main = MainActivity()
        // Same existing registry fixture boundary; register through the real registry API.
        val registry = MainActivityControllerTestFixture.field("commandSpeechResponseCallbacks")
            .apply { isAccessible = true }.get(main) as UtteranceCallbackRegistry
        registry.register("owned-response", {}, {})
        var actorReads = 0
        var clockReads = 0
        var accessibilityReads = 0
        assertTrue(main.isHandsFreeVoiceSpeechOutputActive(
            { actorReads++; null }, { clockReads++; 1_000L }, { accessibilityReads++; false },
        ))
        assertEquals(0, actorReads)
        assertEquals(0, clockReads)
        assertEquals(0, accessibilityReads)
        registry.cancel(listOf("owned-response"))
        assertFalse(main.isHandsFreeVoiceSpeechOutputActive(
            { actorReads++; null }, { clockReads++; 1_000L }, { accessibilityReads++; false },
        ))
        assertEquals(1, actorReads)
        assertEquals(1, clockReads)
        assertEquals(1, accessibilityReads)
    }

    @Test fun currentRealActorIsObservedOnceAfterRegistryWithoutReadingSdkFactsWhileActive() {
        Fixture().use { f ->
            var current: AndroidFeedbackActuator? = null
            val observations = mutableListOf<String>()
            val observeActor = { observations += "actor"; current }
            // A new owner is published before the observation; no captured old/null Actor value.
            current = f.actor
            assertTrue(f.actor.speakInteraction("네"))
            assertTrue(f.main.isHandsFreeVoiceSpeechOutputActive(
                observeActor, { observations += "clock"; f.runtime.now },
                { observations += "accessibility"; false },
            ))
            assertEquals(listOf("actor"), observations)
            assertEquals(1, f.runtime.spoken.size)
        }
    }

    @Test fun inactiveActorFallbackReadsActorThenOriginalClockThenAccessibilityExactlyOnce() {
        Fixture().use { f ->
            val observations = mutableListOf<String>()
            f.main.recordAcceptedTalkBackAnnouncement(risk,
                MainActivity.TalkBackAnnouncementPriority.RISK, f.runtime.now)
            assertTrue(f.main.isHandsFreeVoiceSpeechOutputActive(
                { observations += "actor"; f.actor }, { observations += "clock"; f.runtime.now },
                { observations += "accessibility"; true },
            ))
            assertEquals(listOf("actor", "clock", "accessibility"), observations)
            assertTrue(f.runtime.spoken.isEmpty())
        }
    }

    @Test fun sharedApiClampsNegativeAndHugeLengthsWithoutOverflowWhileLegacyRemainsAvailable() {
        val limits = listOf(SpeechMessageFamily.RISK to (5_000L to 8_000L),
            SpeechMessageFamily.NAVIGATION to (5_000L to 30_000L),
            SpeechMessageFamily.INTERACTION to (5_000L to 60_000L),
            SpeechMessageFamily.EDUCATION_OR_CONSENT to (10_000L to 120_000L))
        limits.forEach { (family, expected) ->
            assertEquals(expected.first, utteranceTerminalTimeoutMs(Int.MIN_VALUE, family))
            assertEquals(expected.second, utteranceTerminalTimeoutMs(Int.MAX_VALUE, family))
        }
        assertEquals(10_000L, utteranceTerminalTimeoutMs(Int.MIN_VALUE))
        assertEquals(120_000L, utteranceTerminalTimeoutMs(Int.MAX_VALUE))
    }

    @Test fun SOURCE_actualAnnounceAdmissionAndNativeCallerMeaningsRemainOutsideTheTimingLeaf() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val announce = MainActivityRefactorSource.function(source, "private fun announceForTalkBack(")
        listOf("message.isBlank() || !isStillValid()", "shouldSuppressFeedbackDuringVoiceRecognition(",
            "val target = presentation.accessibilityTargetOrNull() ?: return false", "val nowMs = scheduler.elapsedRealtimeMs()",
            "if (activeRiskHeld && !preemptsActiveRisk) return false", "message == lastRiskAnnouncement",
            "message == lastNavigationAnnouncement", "message == lastInteractionAnnouncement",
            "target.presentIfCurrent(message, priority)", "onDelivered?.invoke()").forEach {
            assertTrue(it, announce.contains(it))
        }
        assertEquals(4, Regex("recordAcceptedTalkBackAnnouncement\\(").findAll(announce).count())
        val composition = MainActivityRefactorSource.member(source, "createSpeechOutputController")
        assertTrue(composition.contains("presentation = voiceSpeechPresentation"))
        assertTrue(composition.contains("scheduler = voiceCallbacks"))
        val presentationBinding = MainActivityRefactorSource.property(source, "voiceSpeechPresentation")
        assertTrue(presentationBinding.contains("if (::accessibilityStatusText.isInitialized) AndroidVoiceTextTarget(accessibilityStatusText) else null"))
        val adapter = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/composition/VoicePresentationAdapters.kt").readText()
        val accessibility = MainActivityRefactorSource.member(adapter, "accessibilityTargetOrNull")
        assertTrue(accessibility.contains("val target = accessibilityTarget() ?: return null"))
        assertTrue(accessibility.contains("accessibilityTarget()?.identity !== target.identity || !target.visible"))
        assertTrue(accessibility.contains("target.text = message"))
        assertTrue(adapter.contains("get() = view.visibility == View.VISIBLE"))
        assertTrue(adapter.contains("set(value) { view.text = value }"))
        val callbacks = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/composition/AndroidVoiceCallbacks.kt").readText()
        assertTrue(callbacks.contains("private val clock: () -> Long = SystemClock::elapsedRealtime"))
        assertTrue(callbacks.contains("override fun elapsedRealtimeMs() = clock()"))
        assertTrue(MainActivityRefactorSource.member(MainActivityRefactorSource.speech, "hasPendingCommandResponse")
            .contains("commandSpeechResponseCallbacks.size() > 0"))
        val timing = MainActivityRefactorSource.function(source, "internal fun recordAcceptedTalkBackAnnouncement(")
        assertFalse(timing.contains("SystemClock."))
        assertFalse(timing.contains("announceForAccessibility"))
        val busy = source.substringAfter("private fun isHandsFreeVoiceSpeechOutputActive():")
            .substringBefore("internal fun externalFallbackSpeechOutputActive(")
        assertTrue(busy.contains("voiceInputController.isHandsFreeVoiceSpeechOutputActive()"))
        assertTrue(busy.contains("val currentActuator = observeCurrentActuator()"))
        assertTrue(busy.contains("currentActuator?.isAppSpeechActive() == true"))
        assertTrue(busy.indexOf("speechOutputController.hasPendingCommandResponse() || voiceInputController.gateway.hasPlayer()") <
            busy.indexOf("val currentActuator = observeCurrentActuator()"))
        assertTrue(busy.contains("nowElapsedRealtimeMs: () -> Long = { SystemClock.elapsedRealtime() }"))
        assertTrue(busy.contains("observeScreenReaderActive: () -> Boolean = ::isScreenReaderActive"))
        assertTrue(busy.indexOf("currentActuator?.isAppSpeechActive() == true") < busy.indexOf("return externalFallbackSpeechOutputActive(nowElapsedRealtimeMs(), observeScreenReaderActive())"))
        assertTrue(source.contains("actuator.speakConsentClause("))
        assertTrue(source.contains(".speakPriorityUserTraining("))
        assertTrue(source.contains(".speakExplicitConfirmation("))
        assertTrue(MainActivityRefactorSource.function(source, "private fun dispatchNavigationSpeech(")
            .contains("priority = SpeechAnnouncementPriority.NAVIGATION"))
    }

    private class Fixture : AutoCloseable {
        val main = MainActivity()
        val runtime = FakeRuntime()
        val actor = AndroidFeedbackActuator(runtime = runtime).also { runtime.ready() }
        // Native-only fixture has no external accessibility announcement. Main still evaluates its state.
        fun busy(current: AndroidFeedbackActuator? = actor) = main.isHandsFreeVoiceSpeechOutputActive(
            { current }, nowElapsedRealtimeMs = { runtime.now }, observeScreenReaderActive = { false },
        )
        override fun close() = actor.close()
    }

    private class FakeRuntime : FeedbackSpeechRuntime, FeedbackSpeechScheduler {
        data class Spoken(val message: String, val id: String)
        private data class Task(val due: Long, val order: Long, val runnable: Runnable)
        var now = 1_000L
        val spoken = mutableListOf<Spoken>()
        private var initialized: ((Int) -> Unit)? = null
        private var listener: FeedbackSpeechRuntime.Listener? = null
        private val tasks = mutableListOf<Task>()
        private var sequence = 0L
        override val scheduler: FeedbackSpeechScheduler get() = this
        override val engineInitialized: Boolean get() = initialized != null
        override fun elapsedRealtime(): Long = now
        override fun initialize(onInitialized: (Int) -> Unit) { initialized = onInitialized }
        override fun setLanguage(language: Locale): Int = TextToSpeech.LANG_AVAILABLE
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
        override fun stop() = Unit
        override fun shutdown() = Unit
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener) = true
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) = Unit
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = error("No progress tone in speech hold tests")
        override fun vibrate(patternMs: LongArray) = true
        override fun post(task: Runnable) = postDelayed(task, 0L)
        override fun postDelayed(task: Runnable, delayMs: Long): Boolean {
            tasks += Task(now + delayMs.coerceAtLeast(0L), sequence++, task)
            return true
        }
        override fun removeCallbacks(task: Runnable) { tasks.removeAll { it.runnable === task } }
        override fun removeCallbacksAndMessages(token: Any?) { tasks.clear() }
        fun ready() { requireNotNull(initialized).invoke(TextToSpeech.SUCCESS); advanceBy(0L) }
        fun start(id: String) { requireNotNull(listener).onStart(id); advanceBy(0L) }
        fun done(id: String) { requireNotNull(listener).onDone(id); advanceBy(0L) }
        fun advanceBy(deltaMs: Long) {
            require(deltaMs >= 0L)
            val target = now + deltaMs
            while (true) {
                val next = tasks.filter { it.due <= target }.minWithOrNull(compareBy<Task> { it.due }.thenBy { it.order }) ?: break
                tasks.remove(next)
                now = next.due
                next.runnable.run()
            }
            now = target
        }
    }
}
