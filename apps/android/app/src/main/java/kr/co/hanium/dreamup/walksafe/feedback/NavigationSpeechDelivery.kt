package kr.co.hanium.dreamup.walksafe.feedback

import java.util.concurrent.atomic.AtomicBoolean

internal fun shouldSuppressFeedbackDuringVoiceRecognition(
    voiceRecognitionActive: Boolean,
    isRisk: Boolean,
): Boolean = voiceRecognitionActive && !isRisk

internal fun dispatchNavigationSpeech(
    message: String,
    onCompleted: (() -> Unit)?,
    speakWithTts: (String, (() -> Unit)?, (() -> Unit)?) -> NavigationSpeechDispatchResult,
    fallBackToTalkBack: (String, (() -> Unit)?) -> Boolean,
): Boolean {
    val delivered = AtomicBoolean(false)
    val completeOnce = {
        if (delivered.compareAndSet(false, true)) onCompleted?.invoke()
        Unit
    }
    val onTtsFailure = {
        fallBackToTalkBack(message, completeOnce)
        Unit
    }
    return when (speakWithTts(message, completeOnce, onTtsFailure)) {
        NavigationSpeechDispatchResult.ACCEPTED -> true
        NavigationSpeechDispatchResult.SUPPRESSED -> false
        NavigationSpeechDispatchResult.UNAVAILABLE -> fallBackToTalkBack(message, completeOnce)
    }
}
