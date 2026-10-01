package kr.co.hanium.dreamup.walksafe.report

import org.json.JSONArray

/** Preserve unfinished queue revocations independently of the current account/consent response. */
internal class AutomaticReportWithdrawalRecovery(
    readPending: () -> String?,
    private val writePending: (String?) -> Boolean,
    private val revoke: (String) -> Boolean,
) {
    @Volatile
    private var pending: Set<String>? = runCatching {
        readPending()?.let { raw ->
            require(raw.length <= 32_768)
            val array = JSONArray(raw)
            require(array.length() <= 256)
            (0 until array.length()).map { index ->
                (array.get(index) as? String)?.also { require(REPORT_SHA256_HEX.matches(it)) }
                    ?: error("Invalid pending automatic withdrawal")
            }.toSet()
        } ?: emptySet()
    }.getOrNull()

    // Drain context reads this while holding the coordinator lock; do not take the cleanup lock.
    fun isReady(): Boolean = pending?.isEmpty() == true

    @Synchronized
    fun recordWithdrawal(receipt: String): Boolean {
        if (!REPORT_SHA256_HEX.matches(receipt)) {
            pending = null
            return false
        }
        val previous = pending ?: return false
        if (previous.size >= 256 && receipt !in previous) return false
        pending = previous + receipt
        return recover()
    }

    /** Only resume automatic reporting after both durable queue cleanup and marker removal. */
    @Synchronized
    fun recover(): Boolean {
        val receipts = pending ?: return false
        if (receipts.isEmpty()) return true
        val marker = JSONArray(receipts.sorted()).toString()
        if (!runCatching { writePending(marker) }.getOrDefault(false)) return false
        if (!receipts.all { runCatching { revoke(it) }.getOrDefault(false) }) return false
        if (!runCatching { writePending(null) }.getOrDefault(false)) return false
        pending = emptySet()
        return true
    }
}
