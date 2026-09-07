package kr.co.hanium.dreamup.walksafe.voice

import android.util.Log
import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.diagnostics.BoundedRuntimeDiagnosticLog
import kr.co.hanium.dreamup.walksafe.diagnostics.RuntimeDiagnosticDomain
import kr.co.hanium.dreamup.walksafe.diagnostics.RuntimeDiagnosticOrigin
import kr.co.hanium.dreamup.walksafe.diagnostics.formatRuntimeDiagnosticState

internal enum class VoiceDialogDiagnosticStage {
    CONTEXT_OBSERVED, FINAL_RECEIVED, COMMAND_CLASSIFIED, INPUT_ERROR,
    RETRY_REQUESTED, RETRY_SKIPPED, DIALOG_OPENED, DIALOG_CLEARED,
    FOLLOW_UP_REQUESTED, FOLLOW_UP_READY, GENERAL_COMMAND_RESET,
}

internal enum class VoiceDialogDiagnosticReason {
    NONE, UNKNOWN, NO_MATCH, SPEECH_TIMEOUT, EMPTY_FINAL, LOW_CONFIDENCE, AMBIGUOUS,
    INVALID_FINAL, CONFIRMATION_REQUIRED, NO_DIALOG_CONTEXT, STALE_DIALOG_CONTEXT,
    USER_CANCEL, PAGE_CHANGED, BACKGROUNDED, QUERY_CHANGED, NEW_SEARCH,
    OUTPUT_FAILED, OTHER_ERROR,
}

internal enum class VoiceDialogDiagnosticContext { VALID, ABSENT_OR_STALE, UNKNOWN }

/** Internal caller labels only; MICROPHONE_PATH is not a claim about who spoke. */
internal fun classifyVoiceDialogDiagnosticOrigin(source: String?): RuntimeDiagnosticOrigin = when (source) {
    "device_stt" -> RuntimeDiagnosticOrigin.MICROPHONE_PATH
    "synthetic_platform_zero_fixture" -> RuntimeDiagnosticOrigin.SYNTHETIC_FINAL
    else -> RuntimeDiagnosticOrigin.UNKNOWN
}

internal fun logVoiceDialogDiagnostic(
    stage: VoiceDialogDiagnosticStage,
    reason: VoiceDialogDiagnosticReason = VoiceDialogDiagnosticReason.NONE,
    dialogValid: Boolean? = null,
    pageIndex: Int? = null,
    origin: RuntimeDiagnosticOrigin = RuntimeDiagnosticOrigin.UNKNOWN,
) {
    if (!BuildConfig.DEBUG) return
    runCatching {
        val context = when (dialogValid) {
            true -> VoiceDialogDiagnosticContext.VALID
            false -> VoiceDialogDiagnosticContext.ABSENT_OR_STALE
            null -> VoiceDialogDiagnosticContext.UNKNOWN
        }
        val page = pageIndex.takeIf { dialogValid == true }
        Log.d(
            "WalkSafeVoiceInput",
            formatRuntimeDiagnosticState(
                RuntimeDiagnosticDomain.VOICE_DIALOG, stage, reason, context, origin, page,
            ),
        )
        BoundedRuntimeDiagnosticLog.recordState(
            RuntimeDiagnosticDomain.VOICE_DIALOG, stage, reason, context, origin, page,
        )
    }
}
