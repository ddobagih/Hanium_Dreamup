package kr.co.hanium.dreamup.walksafe.voice

import kr.co.hanium.dreamup.walksafe.navigation.AndroidVoiceAction
import kr.co.hanium.dreamup.walksafe.navigation.AndroidVoiceCommand
import kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchVoiceCommand
import kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchVoiceState
import kr.co.hanium.dreamup.walksafe.navigation.parseAndroidVoiceCommand

internal enum class PlatformVoiceCandidateDisposition {
    NOT_APPLICABLE, INVALID_FINAL, AMBIGUOUS, UNRECOGNIZED, PREVIEW_ONLY, CONFIRMATION_REQUIRED,
}

internal data class PlatformVoiceCandidateDecision(
    val disposition: PlatformVoiceCandidateDisposition,
    val previewAction: AndroidVoiceAction? = null,
    val candidate: AndroidVoiceCommand? = null,
)

/**
 * A provider's zero remains low confidence, not missing or trusted confidence.
 * Agreement permits only a non-committing preview; it does not establish ASR accuracy.
 * Existing scored selection and all navigation/permission/confirmation gates remain separate.
 * The caller may supply destinationDialogState only after validating the live HOME dialog
 * lease at final dispatch: actor, process/search generations, query, state identity and
 * foreground readiness must still match. Expired or unvalidated dialogs must pass null.
 * Candidate selection opens the existing destination confirmation, never a route start.
 */
internal fun assessPlatformVoiceCandidate(
    phrases: List<String>,
    confidenceScores: FloatArray?,
    backend: OfflineSpeechEngine?,
    isFinal: Boolean,
    isHomeContext: Boolean,
    allowBareDestinationIndex: Boolean = false,
    hasAdditionalAlternatives: Boolean = false,
    destinationDialogState: DestinationSearchVoiceState? = null,
): PlatformVoiceCandidateDecision {
    fun decision(value: PlatformVoiceCandidateDisposition) = PlatformVoiceCandidateDecision(value)
    if (backend != OfflineSpeechEngine.PLATFORM || !isFinal || !isHomeContext) {
        return decision(PlatformVoiceCandidateDisposition.NOT_APPLICABLE)
    }
    val scores = confidenceScores
        ?: return decision(PlatformVoiceCandidateDisposition.NOT_APPLICABLE)
    if (scores.firstOrNull() != 0f) {
        return decision(PlatformVoiceCandidateDisposition.NOT_APPLICABLE)
    }
    // Providers may return more candidates than requested; every supplied candidate must agree.
    if (phrases.isEmpty() || scores.size != phrases.size ||
        phrases.any { it.isBlank() } ||
        scores.any { !it.isFinite() || (it != -1f && it !in 0f..1f) }
    ) {
        return decision(PlatformVoiceCandidateDisposition.INVALID_FINAL)
    }
    if (hasAdditionalAlternatives) {
        return decision(PlatformVoiceCandidateDisposition.AMBIGUOUS)
    }
    val candidates = phrases.map { parseAndroidVoiceCommand(it, allowBareDestinationIndex) }
    if (candidates.all { it == null }) {
        return decision(PlatformVoiceCandidateDisposition.UNRECOGNIZED)
    }
    val candidate = candidates.firstOrNull()
        ?: return decision(PlatformVoiceCandidateDisposition.AMBIGUOUS)
    if (candidates.any { it == null || it != candidate }) {
        return decision(PlatformVoiceCandidateDisposition.AMBIGUOUS)
    }
    val preview = when (candidate) {
        AndroidVoiceCommand.OpenSettings -> AndroidVoiceAction.OpenSettings
        AndroidVoiceCommand.Help -> AndroidVoiceAction.SpeakVoiceHelp
        is AndroidVoiceCommand.SetDestination -> AndroidVoiceAction.SearchDestination(candidate.placeName)
        is AndroidVoiceCommand.SelectDestinationCandidate -> destinationDialogState
            ?.onCommand(DestinationSearchVoiceCommand.SelectCandidate(candidate.oneBasedIndex))
            ?.takeIf { it.accepted }
            ?.let { AndroidVoiceAction.SelectDestinationCandidate(candidate.oneBasedIndex) }
        AndroidVoiceCommand.RepeatDestinationCandidates -> destinationDialogState
            ?.onCommand(DestinationSearchVoiceCommand.RepeatPage)
            ?.takeIf { it.accepted }
            ?.let { AndroidVoiceAction.RepeatDestinationCandidates }
        AndroidVoiceCommand.HearMoreDestinationCandidates -> destinationDialogState
            ?.takeIf { it.results.isNotEmpty() && it.canHearMore }
            ?.let { AndroidVoiceAction.HearMoreDestinationCandidates }
        else -> null
    }
    return PlatformVoiceCandidateDecision(
        disposition = if (preview != null) PlatformVoiceCandidateDisposition.PREVIEW_ONLY
        else PlatformVoiceCandidateDisposition.CONFIRMATION_REQUIRED,
        previewAction = preview,
        candidate = candidate,
    )
}
