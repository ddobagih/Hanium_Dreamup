package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.*
import org.junit.Test

class DestinationGuidanceApproachPresentationTest {
    private val approach = "가장 가까운 경로 지점의 방향과 거리를 안내하고 있습니다."

    @Test fun anAvailableApproachDoesNotRemainInPreparationBecauseCameraIsStillStarting() {
        val result = DestinationGuidancePresentationPolicy.present(
            GuidanceFeatureDisplayInput(true, false, null, approach),
            GuidanceFeatureDisplayInput(false, true, "객체인식 실행 준비 중"), false,
        )
        assertEquals(DestinationGuidanceDisplayStage.PARTIALLY_AVAILABLE, result.stage)
        assertTrue(result.routeMessageKo.contains(approach))
        assertFalse(result.routeMessageKo.contains("현재 위치와 TMAP 경로를 준비"))
        assertFalse(result.retryAvailable)
    }

    @Test fun approachDescriptionCannotHideRevokedLocationEvidence() {
        val result = DestinationGuidancePresentationPolicy.present(
            GuidanceFeatureDisplayInput(true, false, "현재 위치가 만료되었습니다.", approach),
            GuidanceFeatureDisplayInput(false, false, "카메라 권한 없음"), true,
        )
        assertEquals(DestinationGuidanceDisplayStage.BLOCKED, result.stage)
        assertTrue(result.routeMessageKo.contains("현재 위치가 만료"))
        assertFalse(result.routeMessageKo.contains(approach))
    }

    @Test fun aDescriptionAloneCannotClaimApproachOutputIsAvailable() {
        val result = DestinationGuidancePresentationPolicy.present(
            GuidanceFeatureDisplayInput(false, true, null, approach),
            GuidanceFeatureDisplayInput(false, true, null), false,
        )
        assertEquals(DestinationGuidanceDisplayStage.PREPARING, result.stage)
        assertFalse(result.routeMessageKo.contains(approach))
    }
}
