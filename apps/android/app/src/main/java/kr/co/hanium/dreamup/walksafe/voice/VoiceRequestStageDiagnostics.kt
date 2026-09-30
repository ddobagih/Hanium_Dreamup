package kr.co.hanium.dreamup.walksafe.voice

import android.speech.SpeechRecognizer
import android.util.Log
import kr.co.hanium.dreamup.walksafe.BuildConfig

/**
 * S04-32: why a voice request did not produce a usable command, as typed stages only. No
 * utterance, transcript, destination, account, audio or request token can enter this summary.
 *
 * Operational (release) collection is not enabled: the current integrated consent document
 * (FP-013-RAW-1.1.0) describes detection/performance metadata and raw v2 chunk fields only, so
 * voice-stage metadata would be a change of collected items (FP-013-DETAIL-02). Until that
 * document is revised, summaries go only to the existing DEBUG-only local diagnostic channel.
 */
internal enum class VoiceRequestOutcome {
    FINAL_RECEIVED,
    LOW_CONFIDENCE_FINAL,
    MODEL_LOAD_TIMEOUT,
    MODEL_LOAD_FAILED,
    DECODER_FAILED,
    INPUT_SILENCED,
    AUDIO_FAILED,
    NO_SPEECH,
    RESULT_DELAYED,
    OUTPUT_WAIT_FAILED,
    RECOGNIZER_BUSY,
    CANCELLED,
    OTHER_ERROR,
}

/** Diagnostic bins, never a timeout or execution threshold. */
internal enum class VoiceStageDurationBucket {
    UNDER_500_MS,
    UNDER_2_S,
    UNDER_5_S,
    UNDER_10_S,
    AT_LEAST_10_S,
}

internal fun voiceStageDurationBucket(durationMs: Long?): VoiceStageDurationBucket? = when {
    durationMs == null || durationMs < 0L -> null
    durationMs < 500L -> VoiceStageDurationBucket.UNDER_500_MS
    durationMs < 2_000L -> VoiceStageDurationBucket.UNDER_2_S
    durationMs < 5_000L -> VoiceStageDurationBucket.UNDER_5_S
    durationMs < 10_000L -> VoiceStageDurationBucket.UNDER_10_S
    else -> VoiceStageDurationBucket.AT_LEAST_10_S
}

internal data class VoiceRequestStageSummary(
    val outcome: VoiceRequestOutcome,
    /** request start → decoder ready (model load and microphone start). */
    val modelLoad: VoiceStageDurationBucket? = null,
    /** decoder ready → first transcript (how long the user was waited for). */
    val speechWait: VoiceStageDurationBucket? = null,
    /** decoder endpoint → final result, or → timeout when the final never came. */
    val finalLatency: VoiceStageDurationBucket? = null,
    val confidence: VoiceInputConfidenceCategory? = null,
) {
    fun format(): String = "event=VOICE_REQUEST_STAGES outcome=${outcome.name} " +
        "model_load=${modelLoad?.name ?: "NA"} speech_wait=${speechWait?.name ?: "NA"} " +
        "final_latency=${finalLatency?.name ?: "NA"} confidence=${confidence?.name ?: "NA"}"
}

/** Per-request stage clock for one button voice request; main-thread use only. */
internal class VoiceRequestStageTracker(private val nowMs: () -> Long) {
    private var startedAtMs: Long? = null
    private var readyAtMs: Long? = null
    private var speechAtMs: Long? = null
    private var audioEndAtMs: Long? = null
    private var streamingError: VoskStreamingError? = null

    fun begin() {
        startedAtMs = nowMs()
        readyAtMs = null
        speechAtMs = null
        audioEndAtMs = null
        streamingError = null
    }

    fun ready() {
        if (readyAtMs == null) readyAtMs = nowMs()
    }

    fun speechStarted() {
        if (speechAtMs == null) speechAtMs = nowMs()
    }

    fun audioEnded() {
        if (audioEndAtMs == null) audioEndAtMs = nowMs()
    }

    fun streamingError(error: VoskStreamingError) {
        if (streamingError == null) streamingError = error
    }

    /** [errorCode] null means a final result arrived with [finalConfidence]. */
    fun finish(errorCode: Int?, finalConfidence: Float? = null): VoiceRequestStageSummary {
        val now = nowMs()
        val outcome = classifyVoiceRequestOutcome(
            errorCode = errorCode,
            streamingError = streamingError,
            ready = readyAtMs != null,
            speechStarted = speechAtMs != null,
            confidence = finalConfidence,
        )
        return VoiceRequestStageSummary(
            outcome = outcome,
            modelLoad = voiceStageDurationBucket(elapsed(startedAtMs, readyAtMs ?: now.takeIf { outcome.isLoadStage() })),
            speechWait = voiceStageDurationBucket(elapsed(readyAtMs, speechAtMs ?: now.takeIf { outcome == VoiceRequestOutcome.NO_SPEECH })),
            finalLatency = voiceStageDurationBucket(elapsed(audioEndAtMs, now)),
            confidence = if (errorCode == null) classifyVoiceInputConfidence(finalConfidence) else null,
        ).also { begin() }
    }

    fun cancel(): VoiceRequestStageSummary = VoiceRequestStageSummary(VoiceRequestOutcome.CANCELLED).also { begin() }

    private fun elapsed(from: Long?, to: Long?): Long? = if (from == null || to == null) null else to - from

    private fun VoiceRequestOutcome.isLoadStage(): Boolean =
        this == VoiceRequestOutcome.MODEL_LOAD_TIMEOUT || this == VoiceRequestOutcome.MODEL_LOAD_FAILED
}

internal fun classifyVoiceRequestOutcome(
    errorCode: Int?,
    streamingError: VoskStreamingError?,
    ready: Boolean,
    speechStarted: Boolean,
    confidence: Float?,
): VoiceRequestOutcome = when {
    errorCode == null -> when (classifyVoiceInputConfidence(confidence)) {
        VoiceInputConfidenceCategory.BELOW_055,
        VoiceInputConfidenceCategory.FROM_055_TO_060,
        -> VoiceRequestOutcome.LOW_CONFIDENCE_FINAL
        else -> VoiceRequestOutcome.FINAL_RECEIVED
    }
    streamingError == VoskStreamingError.AUDIO_SILENCED -> VoiceRequestOutcome.INPUT_SILENCED
    streamingError == VoskStreamingError.MODEL_UNAVAILABLE ||
        streamingError == VoskStreamingError.MODEL_LOAD_FAILED -> VoiceRequestOutcome.MODEL_LOAD_FAILED
    streamingError == VoskStreamingError.INFERENCE_FAILED -> VoiceRequestOutcome.DECODER_FAILED
    streamingError == VoskStreamingError.AUDIO_START_FAILED ||
        streamingError == VoskStreamingError.AUDIO_READ_FAILED -> VoiceRequestOutcome.AUDIO_FAILED
    errorCode == SpeechRecognizer.ERROR_CLIENT && !ready -> VoiceRequestOutcome.MODEL_LOAD_TIMEOUT
    errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
        if (speechStarted) VoiceRequestOutcome.RESULT_DELAYED else VoiceRequestOutcome.NO_SPEECH
    errorCode == SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> VoiceRequestOutcome.RECOGNIZER_BUSY
    else -> VoiceRequestOutcome.OTHER_ERROR
}

/** Hands-free output timeout is the "output wait failed" stage of the same diagnostic. */
internal fun voiceRequestStageSummaryOrNull(failure: HandsFreeVoiceTerminalFailure): VoiceRequestStageSummary? =
    when (failure) {
        HandsFreeVoiceTerminalFailure.OutputTimeout -> VoiceRequestStageSummary(VoiceRequestOutcome.OUTPUT_WAIT_FAILED)
        is HandsFreeVoiceTerminalFailure.Transcriber -> VoiceRequestStageSummary(
            classifyVoiceRequestOutcome(
                errorCode = SpeechRecognizer.ERROR_CLIENT,
                streamingError = failure.error,
                ready = true,
                speechStarted = false,
                confidence = null,
            ),
        )
        else -> null
    }

/** DEBUG-only local channel (see the class comment); release builds record nothing. */
internal fun logVoiceRequestStageSummary(summary: VoiceRequestStageSummary) {
    if (!BuildConfig.DEBUG) return
    runCatching { Log.d("WalkSafeVoiceInput", summary.format()) }
}
