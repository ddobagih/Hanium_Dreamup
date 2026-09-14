package kr.co.hanium.dreamup.walksafe.report

import org.junit.Assert.*
import org.junit.Test

class ReportScreenPresentationTest {
    @Test fun disabledQueueNeverOffersSubmission() {
        val result = reportScreenPresentation(false, true, "reportCandidate=queued id=old")
        assertFalse(result.canCheckTarget)
        assertTrue(result.message.contains("꺼져"))
    }
    @Test fun localQueueReceiptIsNotServerReceipt() {
        val result = reportScreenPresentation(true, true, "reportCandidate=queued id=abc")
        assertTrue(result.message.contains("전송 대기"))
        assertTrue(result.message.contains("접수된 상태는 아닙니다"))
        assertFalse(result.canCheckTarget)
    }
    @Test fun inactiveWalkCannotCheckTarget() {
        assertFalse(reportScreenPresentation(true, false, "").canCheckTarget)
    }
    @Test fun noTargetExplainsWhyRetryIsNeeded() {
        val result = reportScreenPresentation(true, true, "reportCandidate=blocked:no_depth_object")
        assertTrue(result.message.contains("손상 점자블록이 없습니다"))
        assertTrue(result.canCheckTarget)
    }
    @Test fun gpsFailureHasSpecificRecovery() {
        assertTrue(reportScreenPresentation(true, true, "reportCandidate=blocked:gps_missing").message.contains("위치 서비스"))
    }
}
