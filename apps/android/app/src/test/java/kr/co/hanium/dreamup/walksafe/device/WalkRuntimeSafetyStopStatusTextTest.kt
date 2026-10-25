package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalkRuntimeSafetyStopStatusTextTest {
    private fun status(complete: Boolean, exhausted: Boolean) = WalkRuntimeSafetyStopStatus(
        stop = WalkRuntimeSafetyStop(WalkRuntimeEpoch("walk", 0L), setOf(WalkRuntimeSafetyStopCause.CAMERA_TRUST_LOST, WalkRuntimeSafetyStopCause.THERMAL_CRITICAL)),
        initialCauses = setOf(WalkRuntimeSafetyStopCause.CAMERA_TRUST_LOST),
        cleanupComplete = complete,
        cleanupExhausted = exhausted,
    )

    @Test
    fun addedCausesDoNotReplaceTheInitialReasonAndPendingCleanupDoesNotOfferResume() {
        val text = status(false, false).userFacingSummary()
        assertTrue(text.contains("처음 중지한 이유: 카메라 영상 확인 불가"))
        assertTrue(text.contains("추가 확인된 이유: 과도한 발열"))
        assertTrue(text.contains("안전 정리 중"))
        assertTrue(text.contains("새 보행을 시작하지 마세요"))
        assertFalse(text.contains("앱을 다시 시작"))
    }

    @Test
    fun completedCleanupStillRequiresRecheckAndAnExplicitNewWalk() {
        val text = status(true, false).userFacingSummary()
        assertTrue(text.contains("기기 점검을 다시 통과한 뒤 새 보행"))
        assertTrue(text.contains("자동으로 재개하지 않습니다"))
        assertFalse(text.contains("안전 정리 중"))
    }

    @Test
    fun exhaustedCleanupKeepsRestartInstructionEvenWithNewCausesOrConflictingCompletionFlag() {
        for (complete in listOf(false, true)) {
            val text = status(complete, true).userFacingSummary()
            assertTrue(text.contains("추가 확인된 이유: 과도한 발열"))
            assertTrue(text.contains("앱을 다시 시작한 뒤 기기 점검"))
            assertFalse(text.contains("자동으로 재개하지 않습니다"))
        }
    }
}
