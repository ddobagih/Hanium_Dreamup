package kr.co.hanium.dreamup.walksafe.feedback

import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.util.Locale
import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.MessagePolicy
import kr.co.hanium.dreamup.walksafe.depth.MetricDepthDecision
import kr.co.hanium.dreamup.walksafe.depth.Trend
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S13-18: MessagePolicy -> current candidate -> exact policy claim -> actual Actor START/DONE.
 * Time and platform effects use a controlled FeedbackSpeechRuntime; no Android TTS is exercised.
 * Every 100 ms observation is refreshed before risk evaluation, which precedes route requests.
 */
class WalkSafeFeedbackLongRiskDeliveryBehaviorTest {
    @Test
    fun fiveMinutesOfProducedSameCautionDeliverOneRiskAndRealActorNavigation() {
        Fixture().use { f ->
            f.run({ Input(0.57f, 2.7f, MessageLevel.CAUTION) }, requestNavigation = true)

            assertEquals(listOf(MessageLevel.CAUTION), f.risks.map { it.action.level })
            f.assertDeliveredRiskAndNavigation(expectedRisks = 1)
            assertTrue("navigation actually completed in the final minute", f.navigationDoneAt.any {
                it > START_MS + FOUR_MINUTES_MS
            })
        }
    }

    @Test
    fun fiveMinuteProducedCautionApproachGetsOneNewDeliveryThenNavigationResumes() {
        Fixture().use { f ->
            f.run({ elapsed -> Input(0.57f, if (elapsed < APPROACH_AT_MS) 2.7f else 2.2f,
                MessageLevel.CAUTION) }, requestNavigation = true)

            assertEquals(listOf(MessageLevel.CAUTION, MessageLevel.CAUTION), f.risks.map { it.action.level })
            assertEquals("weak depth keeps the same qualitative phrase", f.risks[0].action.message,
                f.risks[1].action.message)
            assertTrue(f.risks[1].atMs >= START_MS + APPROACH_AT_MS)
            assertTrue(f.risks[0].action.deliveryOwner !== f.risks[1].action.deliveryOwner)
            f.assertDeliveredRiskAndNavigation(expectedRisks = 2)
            assertTrue("route speech resumes after the approach delivery", f.navigationDoneAt.any {
                it > f.risks[1].doneAtMs!!
            })
            assertTrue(f.navigationDoneAt.any { it > START_MS + FOUR_MINUTES_MS })
        }
    }

    @Test
    fun fiveMinuteProducedWarningAndStopProtectEachPendingRiskFromOtherSpeech() {
        for (input in listOf(Input(0.70f, 2.0f, MessageLevel.WARNING),
            Input(0.90f, 0.6f, MessageLevel.STOP))) Fixture().use { f ->
            f.run({ input }, probeProtectedSpeech = true)

            assertTrue("${input.level} repeats during the final minute", f.risks.count {
                it.atMs > START_MS + FOUR_MINUTES_MS
            } > 1)
            assertTrue(f.risks.all { it.action.level == input.level })
            assertTrue(f.protectedFrames > f.risks.size)
            assertEquals(f.protectedFrames, f.suppressedInteractions)
            assertEquals(f.protectedFrames, f.suppressedNavigation)
            assertEquals(0, f.output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.REQUESTED))
            assertEquals(0, f.output.count(SpeechOutputKind.NAVIGATION, SpeechOutputOutcome.REQUESTED))
            assertEquals(f.risks.size, f.runtime.spoken.size)
            assertEquals(f.risks.size, f.riskCompleted)
            assertEquals(0, f.failed + f.cancelled)
            assertEquals(0, f.suppressedCompleted)
            assertTrue(f.risks.all { it.confirmed && it.doneAtMs != null && it.startAtMs == it.atMs })
            assertEquals(f.risks.size, f.output.count(SpeechOutputKind.RISK, SpeechOutputOutcome.STARTED))
            assertEquals(f.risks.size, f.output.count(SpeechOutputKind.RISK, SpeechOutputOutcome.DONE))
            assertFalse(f.actor.isAppSpeechActive())
            assertEquals(0, f.runtime.stopCount)
            // Only pending-risk probes are made. This does not prohibit responses between risks.
        }
    }

    private data class Input(val confidence: Float, val distanceM: Float, val level: MessageLevel)
    private data class RiskDelivery(val action: FeedbackAction, val atMs: Long,
        var startAtMs: Long? = null, var doneAtMs: Long? = null, var confirmed: Boolean = false)

    private class Fixture : AutoCloseable {
        val runtime = ControlledRuntime()
        val policy = WalkSafeFeedbackPolicy()
        val actor = AndroidFeedbackActuator(runtime = runtime)
        val output = SpeechOutputDiagnostics()
        val risks = mutableListOf<RiskDelivery>()
        val navigationDoneAt = mutableListOf<Long>()
        var riskCompleted = 0
        var failed = 0
        var cancelled = 0
        var protectedFrames = 0
        var suppressedInteractions = 0
        var suppressedNavigation = 0
        var suppressedCompleted = 0
        private val messagePolicy = MessagePolicy().forFeedbackQueue()
        private val observationAgeMs = FeedbackPolicyConfig().maxObservationAgeMs
        private lateinit var current: FeedbackCandidate
        private var nextNavigationAtMs = START_MS + ROUTE_REQUEST_EVERY_MS

        init {
            actor.speechOutputListener = output::record
            runtime.ready()
        }

        fun run(inputAt: (Long) -> Input, requestNavigation: Boolean = false,
            probeProtectedSpeech: Boolean = false) {
            while (runtime.nowMs <= START_MS + FIVE_MINUTES_MS) {
                val atMs = runtime.nowMs
                val input = inputAt(atMs - START_MS)
                val facing = messagePolicy.evaluate(MetricDepthDecision(
                    className = "person", source = DepthSource.ARCORE_FULL_DEPTH,
                    riskDistanceM = input.distanceM, trend = Trend.APPROACHING,
                    confidenceFinal = input.confidence, trackKey = TRACK,
                ), atMs).userFacing
                assertEquals("producer at=$atMs distance=${input.distanceM}", input.level, facing.messageLevel)
                current = FeedbackCandidate(
                    trackId = TRACK, message = requireNotNull(facing.message), level = facing.messageLevel,
                    source = DepthSource.ARCORE_FULL_DEPTH, confidence = input.confidence,
                    timestampMs = atMs + CAMERA_CLOCK_OFFSET_MS,
                    trackAgeFrames = 10 + ((atMs - START_MS) / FRAME_MS).toInt(),
                    trackStableMs = 1_000L + atMs - START_MS, stale = false,
                    validUntilMs = atMs + observationAgeMs, riskDistanceM = input.distanceM,
                    observedAtElapsedRealtimeMs = atMs, observationMaxAgeMs = observationAgeMs,
                )
                policy.evaluate(current, true, atMs)?.let { action ->
                    assertTrue(policy.claimFeedbackDelivery(action, atMs, atMs))
                    val delivery = RiskDelivery(action, atMs)
                    assertEquals(NavigationSpeechDispatchResult.ACCEPTED, actor.emit(
                        action = action,
                        onSpeechCompleted = {
                            riskCompleted += 1
                            delivery.doneAtMs = runtime.nowMs
                            delivery.confirmed = policy.confirmFeedbackDelivery(action, atMs, runtime.nowMs)
                        },
                        onSpeechFailed = { failed += 1; policy.rejectUndeliveredFeedback(action, atMs) },
                        onSpeechCancelled = { cancelled += 1; policy.rejectUndeliveredFeedback(action, atMs) },
                        isStillValidAtStart = {
                            if (actor.validatingStartedSpeech) delivery.startAtMs = runtime.nowMs
                            policy.hasClaimedFeedbackDelivery(action) && runtime.nowMs <= action.validUntilMs &&
                                action.deliveryKey in policy.activeFeedbackDeliveryKeys(listOf(current), true,
                                    runtime.nowMs)
                        },
                    ).speech)
                    risks += delivery
                    val id = runtime.spoken.last().id
                    assertEquals(TextToSpeech.QUEUE_FLUSH, runtime.spoken.last().queueMode)
                    runtime.start(id)
                    runtime.completeAfter(id, SPEECH_DURATION_MS)
                }
                if (probeProtectedSpeech && policy.hasClaimedFeedbackDelivery(TRACK, input.level)) {
                    probeWhileRiskPending()
                }
                if (requestNavigation && atMs >= nextNavigationAtMs &&
                    !actor.isAppSpeechActive() && policy.canSpeakNavigation(atMs)) {
                    requestRouteSpeech()
                    nextNavigationAtMs = atMs + ROUTE_REQUEST_EVERY_MS
                }
                runtime.advanceBy(FRAME_MS)
            }
            runtime.advanceBy(SPEECH_DURATION_MS)
            assertEquals("tts=ready", actor.statusText())
            assertEquals(1, runtime.engineCount)
        }

        private fun requestRouteSpeech() {
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, actor.speakNavigation(
                message = "현재 경로로 계속 이동하세요.",
                onCompleted = { navigationDoneAt += runtime.nowMs },
                onFailed = { failed += 1 }, onCancelled = { cancelled += 1 },
                requiresExplicitTerminalCallback = true,
                isStillValidAtStart = { policy.canSpeakNavigation(runtime.nowMs) },
            ))
            val id = runtime.spoken.last().id
            assertEquals(TextToSpeech.QUEUE_ADD, runtime.spoken.last().queueMode)
            runtime.start(id)
            runtime.completeAfter(id, SPEECH_DURATION_MS)
        }

        private fun probeWhileRiskPending() {
            protectedFrames += 1
            val before = runtime.spoken.size
            val active = runtime.activeSpeechId
            assertEquals(NavigationSpeechDispatchResult.SUPPRESSED, actor.speakInteraction(
                "현재 경로 상태 요청 응답", onCompleted = { suppressedCompleted += 1 },
                onFailed = { failed += 1 }, onCancelled = { cancelled += 1 },
            ))
            suppressedInteractions += 1
            assertEquals(NavigationSpeechDispatchResult.SUPPRESSED, actor.speakNavigation(
                "현재 경로로 계속 이동하세요.", onCompleted = { suppressedCompleted += 1 },
                onFailed = { failed += 1 }, onCancelled = { cancelled += 1 },
                requiresExplicitTerminalCallback = true,
            ))
            suppressedNavigation += 1
            assertEquals(before, runtime.spoken.size)
            assertEquals(active, runtime.activeSpeechId)
            assertEquals(0, suppressedCompleted)
        }

        fun assertDeliveredRiskAndNavigation(expectedRisks: Int) {
            assertEquals(expectedRisks, riskCompleted)
            assertTrue(risks.all { it.confirmed && it.doneAtMs != null && it.startAtMs == it.atMs })
            assertTrue(navigationDoneAt.isNotEmpty())
            for (outcome in listOf(SpeechOutputOutcome.REQUESTED, SpeechOutputOutcome.STARTED,
                SpeechOutputOutcome.DONE)) {
                assertEquals(expectedRisks, output.count(SpeechOutputKind.RISK, outcome))
                assertEquals(navigationDoneAt.size, output.count(SpeechOutputKind.NAVIGATION, outcome))
            }
            assertEquals(expectedRisks + navigationDoneAt.size, runtime.spoken.size)
            assertEquals(0, failed + cancelled)
            assertEquals(0, runtime.stopCount)
            assertFalse(actor.isAppSpeechActive())
        }

        override fun close() = actor.close()
    }

    /** Same fake scheduler/platform boundary used by the started-risk delivery regression tests. */
    private class ControlledRuntime : FeedbackSpeechRuntime, FeedbackSpeechScheduler {
        data class Spoken(val atMs: Long, val id: String, val queueMode: Int)
        private data class Task(val atMs: Long, val order: Long, val runnable: Runnable)
        private val tasks = mutableListOf<Task>()
        private var sequence = 0L
        private var initialized: ((Int) -> Unit)? = null
        private lateinit var listener: FeedbackSpeechRuntime.Listener
        var nowMs = START_MS
            private set
        var engineCount = 0
            private set
        var stopCount = 0
            private set
        var activeSpeechId: String? = null
            private set
        val spoken = mutableListOf<Spoken>()
        override val scheduler: FeedbackSpeechScheduler get() = this
        override val engineInitialized: Boolean get() = initialized != null
        override fun elapsedRealtime(): Long = nowMs
        override fun initialize(onInitialized: (Int) -> Unit) { engineCount += 1; initialized = onInitialized }
        override fun setLanguage(language: Locale): Int = TextToSpeech.LANG_AVAILABLE
        override fun selectOfflineKoreanVoice(): KoreanOfflineVoiceSelection = KoreanOfflineVoiceSelection.SELECTED
        override fun configureAudioAttributes() = Unit
        override fun setListener(listener: FeedbackSpeechRuntime.Listener): Int {
            this.listener = listener
            return TextToSpeech.SUCCESS
        }
        override fun speak(message: String, queueMode: Int, utteranceId: String): Int {
            spoken += Spoken(nowMs, utteranceId, queueMode)
            if (queueMode == TextToSpeech.QUEUE_FLUSH || activeSpeechId == null) activeSpeechId = utteranceId
            return TextToSpeech.SUCCESS
        }
        override fun stop() { stopCount += 1; activeSpeechId = null }
        override fun shutdown() { activeSpeechId = null }
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener): Boolean = true
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) = Unit
        override fun vibrate(patternMs: LongArray): Boolean = true
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = kotlin.error("unused")
        override fun post(task: Runnable): Boolean = postDelayed(task, 0L)
        override fun postDelayed(task: Runnable, delayMs: Long): Boolean {
            tasks += Task(nowMs + delayMs.coerceAtLeast(0L), sequence++, task)
            return true
        }
        override fun removeCallbacks(task: Runnable) { tasks.removeAll { it.runnable === task } }
        override fun removeCallbacksAndMessages(token: Any?) { tasks.clear() }
        fun ready() { checkNotNull(initialized)(TextToSpeech.SUCCESS); advanceBy(0L) }
        fun start(id: String) { listener.onStart(id); advanceBy(0L) }
        fun completeAfter(id: String, durationMs: Long) {
            postDelayed(Runnable {
                if (activeSpeechId == id) activeSpeechId = null
                listener.onDone(id)
            }, durationMs)
        }
        fun advanceBy(deltaMs: Long) {
            require(deltaMs >= 0L)
            val targetMs = nowMs + deltaMs
            while (true) {
                val next = tasks.filter { it.atMs <= targetMs }
                    .minWithOrNull(compareBy<Task> { it.atMs }.thenBy { it.order }) ?: break
                tasks.remove(next)
                nowMs = next.atMs
                next.runnable.run()
            }
            nowMs = targetMs
        }
    }

    private companion object {
        const val TRACK = "persistent-person"
        const val START_MS = 1_000L
        const val FRAME_MS = 100L
        const val SPEECH_DURATION_MS = 1_000L
        const val FIVE_MINUTES_MS = 5 * 60_000L
        const val FOUR_MINUTES_MS = 4 * 60_000L
        const val APPROACH_AT_MS = 150_000L
        const val ROUTE_REQUEST_EVERY_MS = 30_000L
        const val CAMERA_CLOCK_OFFSET_MS = 50_000L
    }
}
