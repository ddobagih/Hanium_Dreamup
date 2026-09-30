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
    return dispatchCurrentNavigationSpeech(message, onCompleted, { true }, speakWithTts, fallBackToTalkBack)
}

/** The owner must invalidate this predicate on route replacement, cancellation or walk teardown. */
internal fun dispatchCurrentNavigationSpeech(
    message: String,
    onCompleted: (() -> Unit)?,
    isStillValid: () -> Boolean,
    speakWithTts: (String, (() -> Unit)?, (() -> Unit)?) -> NavigationSpeechDispatchResult,
    fallBackToTalkBack: (String, (() -> Unit)?) -> Boolean,
    onFailed: (() -> Unit)? = null,
): Boolean {
    val delivered = AtomicBoolean(false)
    val fallbackAttempted = AtomicBoolean(false)
    val failed = AtomicBoolean(false)
    val current = { runCatching(isStillValid).getOrDefault(false) }
    val completeOnce = {
        if (current() && !failed.get() && delivered.compareAndSet(false, true)) onCompleted?.invoke()
        Unit
    }
    val fallback = {
        if (!current() || delivered.get() || !fallbackAttempted.compareAndSet(false, true)) {
            false
        } else {
            val accepted = fallBackToTalkBack(message, completeOnce)
            if (!accepted && !delivered.get() && failed.compareAndSet(false, true)) onFailed?.invoke()
            accepted
        }
    }
    if (!current()) return false
    return when (speakWithTts(message, completeOnce, { fallback(); Unit })) {
        NavigationSpeechDispatchResult.ACCEPTED -> true
        NavigationSpeechDispatchResult.SUPPRESSED -> false
        NavigationSpeechDispatchResult.UNAVAILABLE -> fallback()
    }
}
