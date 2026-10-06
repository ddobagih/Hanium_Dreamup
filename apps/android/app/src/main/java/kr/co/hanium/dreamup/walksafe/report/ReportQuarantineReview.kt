package kr.co.hanium.dreamup.walksafe.report

/** Metadata only; never carry a decrypted photo into the UI. */
internal data class QuarantinedReportSummary(
    val reportId: String,
    val payloadSha256: String,
    val payloadBytes: Long,
    val createdAtEpochMs: Long,
    val priority: ReportQueuePriority,
)

internal enum class QuarantineAction { NEXT, PREVIOUS, REPEAT, REFRESH, DELETE, CONFIRM_DELETE, KEEP, CLOSE }

/** A delete confirmation belongs to one selected entry and is cancelled by navigation. */
internal class ReportQuarantineReview(reports: List<QuarantinedReportSummary>) {
    private val entries = reports.toMutableList()
    private var index = 0
    var confirmingDelete = false
        private set
    val count: Int get() = entries.size
    val position: Int get() = if (entries.isEmpty()) 0 else index + 1
    val current: QuarantinedReportSummary? get() = entries.getOrNull(index)

    fun move(delta: Int) {
        confirmingDelete = false
        if (entries.isNotEmpty()) index = (index + delta).coerceIn(0, entries.lastIndex)
    }
    fun requestDelete() { confirmingDelete = current != null }
    fun keep() { confirmingDelete = false }
    fun deletionTarget(): QuarantinedReportSummary? = current.takeIf { confirmingDelete }
    fun finishDelete(target: QuarantinedReportSummary, deleted: Boolean) {
        confirmingDelete = false
        if (deleted) {
            entries.remove(target)
            index = index.coerceAtMost((entries.size - 1).coerceAtLeast(0))
        }
    }

    companion object {
        const val QUARANTINED_NOTICE =
            "서버가 받을 수 없는 신고를 기기에 보관했습니다. 신고 창의 보관된 신고 확인에서 듣거나 삭제할 수 있습니다."

        fun voiceAction(phrases: List<String>, confidence: Float?, extraAlternatives: Boolean): QuarantineAction? {
            fun parse(raw: String): QuarantineAction? = when (raw.replace(Regex("[\\s.!?。]"), "")) {
                "다음", "다음신고" -> QuarantineAction.NEXT
                "이전", "이전신고" -> QuarantineAction.PREVIOUS
                "다시듣기", "다시말해줘" -> QuarantineAction.REPEAT
                "다시확인", "목록다시확인", "새로고침" -> QuarantineAction.REFRESH
                "삭제", "삭제하기", "이신고삭제" -> QuarantineAction.DELETE
                "삭제확인", "보관본삭제확인" -> QuarantineAction.CONFIRM_DELETE
                "유지", "유지하기", "삭제취소", "취소" -> QuarantineAction.KEEP
                "닫기", "닫아줘", "신고닫기" -> QuarantineAction.CLOSE
                else -> null
            }
            val action = phrases.firstOrNull()?.let(::parse) ?: return null
            if (extraAlternatives || phrases.any { parse(it) != action }) return null
            // Voice alone must not irreversibly delete when recognition confidence is missing.
            if (action == QuarantineAction.CONFIRM_DELETE &&
                (confidence == null || !confidence.isFinite() || confidence < 0.55f)) return null
            return action
        }
    }
}
