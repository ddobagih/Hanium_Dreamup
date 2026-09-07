package kr.co.hanium.dreamup.walksafe.feedback

internal fun utteranceTerminalTimeoutMs(characterCount: Int): Long =
    (5_000L + characterCount.coerceAtLeast(0) * 300L).coerceIn(10_000L, 120_000L)

internal enum class CommandSpeechReadiness {
    WAITING,
    DISPATCH,
    CANCELLED,
    TIMED_OUT,
}

internal class UtteranceCallbackRegistry {
    private data class Callbacks(
        val onCompleted: (() -> Unit)?,
        val onFailed: (() -> Unit)?,
        val protectsFromFollowingSpeech: Boolean,
    )

    private val callbacks = mutableMapOf<String, Callbacks>()

    @Synchronized
    fun register(
        utteranceId: String,
        onCompleted: (() -> Unit)?,
        onFailed: (() -> Unit)?,
        protectsFromFollowingSpeech: Boolean = false,
    ) {
        if (onCompleted != null || onFailed != null) {
            callbacks[utteranceId] = Callbacks(onCompleted, onFailed, protectsFromFollowingSpeech)
        }
    }

    @Synchronized
    fun registerLatest(
        utteranceId: String,
        onCompleted: (() -> Unit)?,
        onFailed: (() -> Unit)?,
    ) {
        callbacks.clear()
        register(utteranceId, onCompleted, onFailed)
    }

    @Synchronized
    fun protectedUtteranceIds(): List<String> = callbacks
        .filterValues { it.protectsFromFollowingSpeech }.keys.toList()

    // Commands alone can be stopped: TextToSpeech has no per-utterance cancellation.
    @Synchronized
    fun exclusiveCommandUtteranceIds(
        pendingUtteranceIds: Collection<String>,
        explicitTerminalRequiredIds: Set<String>,
    ): List<String> {
        if (pendingUtteranceIds.any { utteranceId ->
                utteranceId !in explicitTerminalRequiredIds ||
                    callbacks[utteranceId]?.protectsFromFollowingSpeech != false
            }
        ) return emptyList()
        return pendingUtteranceIds.toList()
    }

    @Synchronized
    fun commandSpeechReadiness(
        utteranceId: String,
        requestCurrent: Boolean,
        speechInitializing: Boolean,
        nowMs: Long,
        deadlineMs: Long,
    ): CommandSpeechReadiness = when {
        !requestCurrent || utteranceId !in callbacks -> CommandSpeechReadiness.CANCELLED
        nowMs >= deadlineMs -> CommandSpeechReadiness.TIMED_OUT
        speechInitializing -> CommandSpeechReadiness.WAITING
        // Keep READY/FAILED/language/audio-focus decisions in the real actuator dispatch.
        else -> CommandSpeechReadiness.DISPATCH
    }

    @Synchronized
    fun takeTerminalCallback(
        utteranceId: String?,
        completed: Boolean,
        notifyFailure: Boolean,
    ): (() -> Unit)? {
        val registered = utteranceId?.let(callbacks::remove) ?: return null
        return if (completed) registered.onCompleted else registered.onFailed.takeIf { notifyFailure }
    }

    @Synchronized
    fun cancel(utteranceIds: Collection<String>) {
        utteranceIds.forEach(callbacks::remove)
    }

    @Synchronized
    fun clear() {
        callbacks.clear()
    }

    @Synchronized
    fun size(): Int = callbacks.size
}
