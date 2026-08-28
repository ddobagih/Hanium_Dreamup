package kr.co.hanium.dreamup.walksafe.feedback

internal enum class SpeechPriority {
    ADVISORY,
    NAVIGATION,
    INTERACTION,
    RISK,
}

internal data class PendingSpeech(
    val message: String,
    val priority: SpeechPriority,
    val riskRank: Int? = null,
)

/** Keeps the latest initialization-time message for each bounded priority class. */
internal class PendingSpeechQueue(
    private val capacity: Int = 3,
) {
    private val pending = linkedMapOf<SpeechPriority, PendingSpeech>()

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    @Synchronized
    fun offer(message: String, priority: SpeechPriority, riskRank: Int? = null): Boolean {
        if (message.isBlank()) return false
        val existing = pending[priority]
        if (existing != null) {
            if (existing.message == message && existing.riskRank == riskRank) return false
            if (
                priority == SpeechPriority.RISK &&
                existing.riskRank != null &&
                riskRank != null &&
                riskRank <= existing.riskRank
            ) return false
            pending[priority] = PendingSpeech(message, priority, riskRank)
            return true
        }
        if (pending.size >= capacity) {
            val lowest = pending.keys.minByOrNull(SpeechPriority::ordinal) ?: return false
            if (priority.ordinal <= lowest.ordinal) return false
            pending.remove(lowest)
        }
        pending[priority] = PendingSpeech(message, priority, riskRank)
        return true
    }

    @Synchronized
    fun hasPriority(priority: SpeechPriority): Boolean = priority in pending

    @Synchronized
    fun removePriority(priority: SpeechPriority): Boolean = pending.remove(priority) != null

    @Synchronized
    fun clear() {
        pending.clear()
    }

    @Synchronized
    fun drainPriorityOrder(): List<PendingSpeech> {
        val result = pending.values.sortedByDescending { it.priority.ordinal }
        pending.clear()
        return result
    }
}
