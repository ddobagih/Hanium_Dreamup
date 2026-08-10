package kr.co.hanium.dreamup.walksafe.feedback

internal fun utteranceTerminalTimeoutMs(characterCount: Int): Long =
    (5_000L + characterCount.coerceAtLeast(0) * 300L).coerceIn(10_000L, 120_000L)

internal class UtteranceCallbackRegistry {
    private data class Callbacks(
        val onCompleted: (() -> Unit)?,
        val onFailed: (() -> Unit)?,
    )

    private val callbacks = mutableMapOf<String, Callbacks>()

    @Synchronized
    fun register(
        utteranceId: String,
        onCompleted: (() -> Unit)?,
        onFailed: (() -> Unit)?,
    ) {
        if (onCompleted != null || onFailed != null) {
            callbacks[utteranceId] = Callbacks(onCompleted, onFailed)
        }
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
