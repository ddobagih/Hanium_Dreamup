package kr.co.hanium.dreamup.walksafe.feedback

internal fun utteranceTerminalTimeoutMs(characterCount: Int): Long =
    (5_000L + characterCount.coerceAtLeast(0) * 300L).coerceIn(10_000L, 120_000L)

/** Caller meaning, independent of TTS priority or diagnostic id prefixes. */
enum class SpeechMessageFamily(internal val minimumMs: Long, internal val maximumMs: Long) {
    RISK(2_000L, 8_000L),
    NAVIGATION(3_000L, 30_000L),
    INTERACTION(5_000L, 60_000L),
    /** Education, consent reading and explicit agreement/confirmation keep the long budget. */
    EDUCATION_OR_CONSENT(10_000L, 120_000L),
}

/** Completion budget after native onStart; excludes queue/start waiting and source validity. */
fun utteranceTerminalTimeoutMs(characterCount: Int, family: SpeechMessageFamily): Long =
    (5_000L + characterCount.coerceAtLeast(0) * 300L)
        .coerceIn(family.minimumMs, family.maximumMs)

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
        val onCancelled: (() -> Unit)?,
        val onCloseCancelled: (() -> Unit)?,
    )

    private val callbacks = mutableMapOf<String, Callbacks>()

    @Synchronized
    fun register(
        utteranceId: String,
        onCompleted: (() -> Unit)?,
        onFailed: (() -> Unit)?,
        protectsFromFollowingSpeech: Boolean = false,
        onCancelled: (() -> Unit)? = onFailed,
        onCloseCancelled: (() -> Unit)? = null,
    ) {
        if (onCompleted != null || onFailed != null || onCancelled != null || onCloseCancelled != null) {
            callbacks[utteranceId] = Callbacks(onCompleted, onFailed, protectsFromFollowingSpeech,
                onCancelled, onCloseCancelled)
        }
    }

    @Synchronized
    fun registerLatest(
        utteranceId: String,
        onCompleted: (() -> Unit)?,
        onFailed: (() -> Unit)?,
        onRetired: (() -> Unit)? = null,
    ) {
        callbacks.clear()
        register(utteranceId, onCompleted, onFailed, onCloseCancelled = onRetired)
    }

    /** Drain before replacement or explicit cancellation; the caller invokes after installing its new owner. */
    @Synchronized
    fun takeRetirementCallbacks(): List<() -> Unit> {
        val retired = callbacks.values.mapNotNull { it.onCloseCancelled }
        callbacks.clear()
        return retired
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
        cancelled: Boolean = false,
    ): (() -> Unit)? {
        val registered = utteranceId?.let(callbacks::remove) ?: return null
        return when {
            completed -> registered.onCompleted
            cancelled -> registered.onCancelled
            notifyFailure -> registered.onFailed
            else -> null
        }
    }

    /** Closing drains ownership but notifies only an explicitly supplied cancellation consumer. */
    @Synchronized
    fun takeCloseCancellationCallbacks(utteranceIds: Collection<String>): List<() -> Unit> =
        utteranceIds.mapNotNull { callbacks.remove(it)?.onCloseCancelled }

    @Synchronized
    fun cancel(utteranceIds: Collection<String>) {
        utteranceIds.forEach(callbacks::remove)
    }

    @Synchronized
    fun clear() {
        callbacks.clear()
    }

    @Synchronized
    fun contains(utteranceId: String): Boolean = utteranceId in callbacks

    @Synchronized
    fun size(): Int = callbacks.size
}
