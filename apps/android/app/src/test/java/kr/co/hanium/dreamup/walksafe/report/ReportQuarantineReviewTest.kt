package kr.co.hanium.dreamup.walksafe.report

import org.junit.Assert.*
import org.junit.Test

class ReportQuarantineReviewTest {
    private fun item(id: String) = QuarantinedReportSummary(id, "a".repeat(64), 123, 1_000, ReportQueuePriority.AUTOMATIC)

    @Test fun deletionNeedsASeparateConfirmationAndNavigationCancelsIt() {
        val first = item("first")
        val second = item("second")
        val review = ReportQuarantineReview(listOf(first, second))
        assertNull(review.deletionTarget())
        review.requestDelete()
        assertEquals(first, review.deletionTarget())
        review.move(1)
        assertEquals(second, review.current)
        assertNull(review.deletionTarget())
        review.requestDelete()
        review.keep()
        assertNull(review.deletionTarget())
        review.requestDelete()
        review.finishDelete(second, false)
        assertEquals(2, review.count)
        assertNull(review.deletionTarget())
        review.requestDelete()
        review.finishDelete(second, true)
        assertEquals(1, review.position)
        assertEquals(first, review.current)
        review.requestDelete()
        review.finishDelete(first, true)
        assertEquals(0, review.position)
        assertNull(review.current)
        review.requestDelete()
        assertNull(review.deletionTarget())
    }

    @Test fun ambiguousOrLowConfidenceVoiceNeverConfirmsDeletion() {
        fun parse(phrases: List<String>, confidence: Float? = .9f, extra: Boolean = false) =
            ReportQuarantineReview.voiceAction(phrases, confidence, extra)
        assertEquals(QuarantineAction.DELETE, parse(listOf("삭제")))
        assertEquals(QuarantineAction.KEEP, parse(listOf("취소")))
        assertEquals(QuarantineAction.CONFIRM_DELETE, parse(listOf("삭제 확인", "보관본 삭제 확인")))
        for (score in listOf(null, Float.NaN, .54f)) assertNull(parse(listOf("삭제 확인"), score))
        assertNull(parse(listOf("삭제 확인", "유지")))
        assertNull(parse(listOf("삭제 확인"), extra = true))
        assertNull(parse(listOf("확인")))
        assertNull(parse(emptyList()))
        assertEquals(QuarantineAction.REPEAT, parse(listOf("다시 듣기"), null))
    }
}
