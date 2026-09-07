package kr.co.hanium.dreamup.walksafe.voice

import android.util.Log
import kr.co.hanium.dreamup.walksafe.BuildConfig

/** Diagnostic bins, never an execution policy or a replacement recognition score. */
internal enum class VoiceInputConfidenceCategory {
    MISSING, UNAVAILABLE_MINUS_ONE, ZERO, NONFINITE, OUT_OF_RANGE,
    BELOW_055, FROM_055_TO_060, AT_LEAST_060,
}

internal enum class VoiceInputDiagnosticEvent {
    ONE_SHOT_REQUESTED, VOSK_ONLY_REQUESTED, PLATFORM_UNAVAILABLE, PLATFORM_COOLDOWN,
    PLATFORM_SUPPORT_READY, PLATFORM_SUPPORT_NOT_INSTALLED, PLATFORM_SUPPORT_ERROR,
    PLATFORM_SUPPORT_TIMEOUT, ENGINE_SELECTED, READY, PARTIAL_NONEMPTY, FINAL_RECEIVED,
    ERROR, WATCHDOG, CANCELLED, CLOSED, REQUEST_STALE,
    WAKE_START_REQUESTED, WAKE_READY, WAKE_PARTIAL_FIRST, WAKE_FINAL_RECEIVED,
    WAKE_FINAL_UNTRUSTED, WAKE_NOT_ADDRESSED, WAKE_ADDRESSED, WAKE_INPUT_SUPPRESSED,
    WAKE_INPUT_UNSUPPRESSED, WAKE_AVAILABLE, WAKE_TIMEOUT, WAKE_STREAM_ERROR,
    WAKE_START_FAILED, WAKE_CANCELLED, WAKE_CLOSED, WAKE_UI_READY, WAKE_UI_RESULT,
    ONE_SHOT_UI_READY, ONE_SHOT_UI_ERROR, ONE_SHOT_UI_FINAL,
    WALK_DECISION, COMMAND_CANDIDATE_PARSED, COMMAND_SELECTED, COMMAND_GATE_BLOCKED,
    COMMAND_EXECUTION_REQUESTED, OUTPUT_REQUESTED, OUTPUT_DISPATCH, OUTPUT_DONE, OUTPUT_FAILED,
}

internal fun classifyVoiceInputConfidence(score: Float?): VoiceInputConfidenceCategory = when {
    score == null -> VoiceInputConfidenceCategory.MISSING
    !score.isFinite() -> VoiceInputConfidenceCategory.NONFINITE
    score == -1f -> VoiceInputConfidenceCategory.UNAVAILABLE_MINUS_ONE
    score == 0f -> VoiceInputConfidenceCategory.ZERO
    score < 0f || score > 1f -> VoiceInputConfidenceCategory.OUT_OF_RANGE
    score < 0.55f -> VoiceInputConfidenceCategory.BELOW_055
    score < 0.60f -> VoiceInputConfidenceCategory.FROM_055_TO_060
    else -> VoiceInputConfidenceCategory.AT_LEAST_060
}

/** Accepts only typed states and numeric metadata; no text, audio, identity, or request token. */
internal fun formatVoiceInputDiagnostic(
    event: VoiceInputDiagnosticEvent,
    backend: OfflineSpeechEngine? = null,
    confidence: Float? = null,
    resultCount: Int? = null,
    errorCode: Int? = null,
    matched: Boolean? = null,
    selection: OfflineSpeechSelection? = null,
    failure: VoskWakePhraseProbeFailure? = null,
    streamingError: VoskStreamingError? = null,
): String = "event=${event.name} backend=${backend?.name ?: "NA"} " +
    "confidence=${classifyVoiceInputConfidence(confidence).name} " +
    "result_count=${resultCount?.coerceAtLeast(0)?.toString() ?: "NA"} " +
    "error_code=${errorCode?.toString() ?: "NA"} matched=${matched?.toString() ?: "NA"} " +
    "selection=${selection?.name ?: "NA"} failure=${failure?.name ?: "NA"} " +
    "stream_error=${streamingError?.name ?: "NA"}"

internal fun logVoiceInputDiagnostic(
    event: VoiceInputDiagnosticEvent,
    backend: OfflineSpeechEngine? = null,
    confidence: Float? = null,
    resultCount: Int? = null,
    errorCode: Int? = null,
    matched: Boolean? = null,
    selection: OfflineSpeechSelection? = null,
    failure: VoskWakePhraseProbeFailure? = null,
    streamingError: VoskStreamingError? = null,
) {
    if (!BuildConfig.DEBUG) return
    Log.d(
        "WalkSafeVoiceInput",
        formatVoiceInputDiagnostic(
            event, backend, confidence, resultCount, errorCode, matched, selection, failure, streamingError,
        ),
    )
    kr.co.hanium.dreamup.walksafe.diagnostics.BoundedRuntimeDiagnosticLog.recordVoiceInput(
        event, backend, confidence, resultCount, errorCode, matched, selection, failure, streamingError,
    )
}
