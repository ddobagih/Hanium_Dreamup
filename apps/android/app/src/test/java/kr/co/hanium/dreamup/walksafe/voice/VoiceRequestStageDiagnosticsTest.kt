package kr.co.hanium.dreamup.walksafe.voice

import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S04-32: "why was it not heard" is distinguishable without utterance, destination or account data. */
class VoiceRequestStageDiagnosticsTest {
    @Test
    fun requestFailuresAreDistinguishedByStage() {
        assertEquals(VoiceRequestOutcome.MODEL_LOAD_FAILED, run { f -> f.error(VoskStreamingError.MODEL_LOAD_FAILED) }.outcome)
        assertEquals(VoiceRequestOutcome.INPUT_SILENCED, run { f -> f.ready(); f.error(VoskStreamingError.AUDIO_SILENCED) }.outcome)
        assertEquals(VoiceRequestOutcome.AUDIO_FAILED, run { f -> f.ready(); f.error(VoskStreamingError.AUDIO_READ_FAILED) }.outcome)
        val noSpeech = run { f -> f.ready(); f.advance(10_000L) }
        assertEquals(VoiceRequestOutcome.NO_SPEECH, noSpeech.outcome)
        assertEquals(VoiceStageDurationBucket.AT_LEAST_10_S, noSpeech.speechWait)
        val delayed = run { f -> f.ready(); f.transcript("부분", final = false); f.advance(500L); f.audioEnd(); f.advance(10_000L) }
        assertEquals(VoiceRequestOutcome.RESULT_DELAYED, delayed.outcome)
        assertEquals(VoiceStageDurationBucket.AT_LEAST_10_S, delayed.finalLatency)
        val lowConfidence = run { f -> f.ready(); f.transcript("목적지", final = true, confidence = 0.40f) }
        assertEquals(VoiceRequestOutcome.LOW_CONFIDENCE_FINAL, lowConfidence.outcome)
        assertEquals(VoiceInputConfidenceCategory.BELOW_055, lowConfidence.confidence)
        val loadTimeout = run { f -> f.advance(20_000L) }
        assertEquals(VoiceRequestOutcome.MODEL_LOAD_TIMEOUT, loadTimeout.outcome)
        assertEquals(VoiceStageDurationBucket.AT_LEAST_10_S, loadTimeout.modelLoad)
        val ok = run { f -> f.advance(300L); f.ready(); f.advance(1_200L); f.transcript("안내 중지", final = true, confidence = 0.9f) }
        assertEquals(VoiceRequestOutcome.FINAL_RECEIVED, ok.outcome)
        assertEquals(VoiceStageDurationBucket.UNDER_500_MS, ok.modelLoad)
        assertEquals(VoiceStageDurationBucket.UNDER_2_S, ok.speechWait)
        // Output-wait failure comes from the hands-free controller's existing terminal failure.
        assertEquals(
            VoiceRequestOutcome.OUTPUT_WAIT_FAILED,
            voiceRequestStageSummaryOrNull(HandsFreeVoiceTerminalFailure.OutputTimeout)?.outcome,
        )
        assertEquals(
            VoiceRequestOutcome.INPUT_SILENCED,
            voiceRequestStageSummaryOrNull(HandsFreeVoiceTerminalFailure.Transcriber(VoskStreamingError.AUDIO_SILENCED))?.outcome,
        )
    }

    @Test
    fun summaryNeverCarriesTextAndCancelIsItsOwnOutcome() {
        val summary = run { f -> f.ready(); f.transcript("서울역으로 안내해줘 user@example.com", final = true, confidence = 0.95f) }
        val line = summary.format()
        assertFalse(line.contains("서울역"))
        assertFalse(line.contains("@"))
        assertTrue(line.matches(Regex("event=VOICE_REQUEST_STAGES( [a-z_]+=[A-Z0-9_]+)+")))
        val fixture = Fixture()
        fixture.recognizer.startRequest()
        fixture.recognizer.cancel()
        assertEquals(listOf(VoiceRequestOutcome.CANCELLED), fixture.summaries.map { it.outcome })
    }

    @Test
    fun releaseStaysOutOfOperationalCollectionUntilTheConsentDocumentCoversIt() {
        val diagnostics = source("voice/VoiceRequestStageDiagnostics.kt")
        val logger = diagnostics.substringAfter("internal fun logVoiceRequestStageSummary(")
        assertTrue(logger.contains("if (!BuildConfig.DEBUG) return"))
        assertFalse(diagnostics.contains("RawCollection"))
        assertFalse(diagnostics.contains("upload", ignoreCase = true))
        val consent = source("session/IntegratedConsentPolicy.kt")
        // If this document starts describing voice-stage metadata, revisit S04-32's release gate.
        assertFalse(consent.contains("음성 단계"))
        val recognizer = source("voice/VoskSpeechRecognizer.kt")
        assertTrue(recognizer.contains("stageSummarySink = ::logVoiceRequestStageSummary"))
    }

    private fun run(script: (Fixture) -> Unit): VoiceRequestStageSummary {
        val fixture = Fixture()
        fixture.recognizer.startRequest()
        script(fixture)
        return fixture.summaries.single()
    }

    private class Fixture {
        val runtime = ManualRuntime()
        val summaries = mutableListOf<VoiceRequestStageSummary>()
        private var listener: VoskStreamingListener? = null
        private var runId = 0L
        val recognizer = VoskSpeechRecognizer(
            VoskSpeechDependencies(
                runtime = runtime,
                microphoneGranted = { true },
                createStream = { l ->
                    listener = l
                    object : VoskRequestStream {
                        override fun start(onRunReserved: (Long) -> Unit): Long? {
                            runId += 1L
                            onRunReserved(runId)
                            return runId
                        }
                        override fun stop() = Unit
                        override fun close() = Unit
                    }
                },
                readyBundle = { null },
                transcriptResults = { null },
                stageSummarySink = { summaries += it },
            ),
        ).also { it.setRecognitionListener(NoopListener) }

        fun ready() { listener!!.onReady(runId); runtime.advance(0L) }
        fun audioEnd() { listener!!.onAudioEnd(runId); runtime.advance(0L) }
        fun error(error: VoskStreamingError) { listener!!.onError(runId, error); runtime.advance(0L) }
        fun transcript(text: String, final: Boolean, confidence: Float? = null) {
            listener!!.onTranscript(runId, VoskTranscript(text = text, isFinal = final, confidence = confidence))
            runtime.advance(0L)
        }
        fun advance(ms: Long) = runtime.advance(ms)
    }

    private object NoopListener : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onError(error: Int) = Unit
        override fun onResults(results: Bundle?) = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private class ManualRuntime : VoiceCallbackRuntime {
        private var nowMs = 0L
        private var sequence = 0L
        private val pending = mutableListOf<Triple<Long, Long, Runnable>>()
        override fun checkMainThread(owner: String) = Unit
        override fun nowMs(): Long = nowMs
        override fun post(task: Runnable) = postDelayed(task, 0L)
        override fun postDelayed(task: Runnable, delayMs: Long) { pending += Triple(nowMs + delayMs, sequence++, task) }
        override fun removeCallbacks(task: Runnable) { pending.removeAll { it.third === task } }
        override fun removeAllCallbacks() = pending.clear()
        fun advance(deltaMs: Long) {
            val target = nowMs + deltaMs
            while (true) {
                val next = pending.filter { it.first <= target }.minWithOrNull(compareBy({ it.first }, { it.second })) ?: break
                pending.remove(next)
                nowMs = next.first
                next.third.run()
            }
            nowMs = target
        }
    }

    private fun source(relative: String): String = sequenceOf(
        File("src/main/java/kr/co/hanium/dreamup/walksafe/$relative"),
        File("app/src/main/java/kr/co/hanium/dreamup/walksafe/$relative"),
    ).first { it.isFile }.readText()

    @Suppress("unused")
    private val speechTimeout = SpeechRecognizer.ERROR_SPEECH_TIMEOUT
}
