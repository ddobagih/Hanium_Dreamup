package kr.co.hanium.dreamup.walksafe.feedback

internal data class RiskDeliveryRegistration(
    val owner: FeedbackDeliveryOwner,
    val engineGeneration: Long,
    val outputGeneration: Long,
)

internal data class AdvisoryDeliveryRegistration(
    val receipt: AdvisorySpeechReceipt,
    val engineGeneration: Long,
    val outputGeneration: Long,
)

/** One native utterance's lifecycle facts; callbacks retain their separate registry contract. */
internal data class UtteranceRecord(
    val id: String,
    val terminalBudgetMs: Long,
    val riskRank: Int? = null,
    val riskDelivery: RiskDeliveryRegistration? = null,
    val advisoryDelivery: AdvisoryDeliveryRegistration? = null,
    val startDeadlineMs: Long? = null,
    val startValidator: (() -> Boolean)? = null,
    val requiresExplicitTerminalCallback: Boolean = false,
    val started: Boolean = false,
    val watchdog: Runnable? = null,
)

/**
 * Ordered native ownership, guarded by the actuator's existing pendingUtterances monitor.
 * No effect or consumer runs here. Detach/drain return immutable records so cancellation can
 * release watchdogs and invoke detached callbacks after the actuator releases its locks.
 */
internal class UtteranceLedger : AbstractSet<String>() {
    private val records = linkedMapOf<String, UtteranceRecord>()

    override val size: Int get() = records.size
    override fun iterator(): Iterator<String> = records.keys.iterator()
    override fun contains(element: String): Boolean = records.containsKey(element)
    operator fun get(id: String): UtteranceRecord? = records[id]

    fun reserve(record: UtteranceRecord) {
        check(record.id !in records) { "Utterance already reserved" }
        records[record.id] = record
    }

    /** Only called for the platform's actual onStart, never for submission or timeout. */
    fun markStarted(id: String): UtteranceRecord? {
        val record = records[id] ?: return null
        return record.copy(started = true).also { records[id] = it }
    }

    fun replaceWatchdog(id: String, watchdog: Runnable?): Runnable? {
        val record = records[id] ?: return null
        records[id] = record.copy(watchdog = watchdog)
        return record.watchdog
    }

    fun firstRecord(): UtteranceRecord? = records.values.firstOrNull()

    fun explicitTerminalIds(): Set<String> = records.values
        .filter { it.requiresExplicitTerminalCallback }.mapTo(linkedSetOf()) { it.id }

    /** A successor start cannot prove a predecessor completed. The actuator reports failure. */
    fun detachPredecessors(id: String): List<UtteranceRecord> {
        if (id !in records) return emptyList()
        return records.keys.takeWhile { it != id }.mapNotNull(::detach)
    }

    /** The first terminal wins; later platform callbacks no longer own any lifecycle facts. */
    fun detach(id: String): UtteranceRecord? = records.remove(id)

    fun drain(): List<UtteranceRecord> = records.values.toList().also { records.clear() }
}
