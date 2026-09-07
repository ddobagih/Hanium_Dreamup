package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationGuidancePresentationPolicyTest {
    private fun input(running: Boolean = false, preparing: Boolean = false, reason: String? = null) =
        GuidanceFeatureDisplayInput(running, preparing, reason)

    @Test
    fun noSensorOrRuntimeEvidenceStillProducesGuidancePreparation() {
        val result = DestinationGuidancePresentationPolicy.present(input(), input(), false)
        assertEquals(DestinationGuidanceDisplayStage.PREPARING, result.stage)
        assertTrue(result.routeMessageKo.isNotBlank())
        assertTrue(result.cameraMessageKo.isNotBlank())
        assertFalse(result.retryAvailable)
    }

    @Test
    fun groundFacingCameraReasonRemainsVisibleWhileMeasurementsContinue() {
        val reason = "합성 fixture: 카메라가 아래를 향해 전방을 확인할 수 없습니다."
        val result = DestinationGuidancePresentationPolicy.present(
            input(preparing = true, reason = "합성 fixture: 정확한 위치 대기"),
            input(preparing = true, reason = reason),
            false,
        )
        assertEquals(DestinationGuidanceDisplayStage.PREPARING, result.stage)
        assertTrue(result.cameraMessageKo.contains(reason))
        assertFalse(result.cameraMessageKo.contains("실제 객체인식 안내를 사용할 수 있습니다."))
    }

    @Test
    fun oneUnavailableFeatureDoesNotHideTheOtherObservedFeature() {
        val result = DestinationGuidancePresentationPolicy.present(
            input(reason = "합성 fixture: 위치 정확도 부족"), input(running = true), true,
        )
        assertEquals(DestinationGuidanceDisplayStage.PARTIALLY_AVAILABLE, result.stage)
        assertTrue(result.cameraMessageKo.contains("실제 객체인식 안내를 사용할 수 있습니다."))
        assertTrue(result.routeMessageKo.contains("위치 정확도 부족"))
    }

    @Test
    fun actualBlockReasonTakesPrecedenceOverAnOldRunningFlag() {
        val result = DestinationGuidancePresentationPolicy.present(
            input(running = true, reason = "합성 fixture: 위치 만료"),
            input(running = true, reason = "합성 fixture: 카메라 권한 해제"),
            true,
        )
        assertEquals(DestinationGuidanceDisplayStage.BLOCKED, result.stage)
        assertTrue(result.retryAvailable)
        assertFalse(result.routeMessageKo.contains("경로가 준비되었습니다."))
    }

    @Test
    fun onlyBothActualOutputSignalsWithoutBlocksProduceAvailablePresentation() {
        val result = DestinationGuidancePresentationPolicy.present(input(running = true), input(running = true), false)
        assertEquals(DestinationGuidanceDisplayStage.ACTIVE, result.stage)
    }

    @Test
    fun timeoutAndExplicitRetryArePresentedWithoutCreatingReadyEvidence() {
        val stopped = DestinationGuidancePresentationPolicy.present(
            input(reason = "합성 fixture: 위치 시간 초과"), input(reason = "합성 fixture: 영상 없음"), true,
        )
        val restarted = DestinationGuidancePresentationPolicy.present(
            input(preparing = true), input(preparing = true), false,
        )
        assertEquals(DestinationGuidanceDisplayStage.BLOCKED, stopped.stage)
        assertTrue(stopped.retryAvailable)
        assertEquals(DestinationGuidanceDisplayStage.PREPARING, restarted.stage)
        assertFalse(restarted.retryAvailable)
    }
}

