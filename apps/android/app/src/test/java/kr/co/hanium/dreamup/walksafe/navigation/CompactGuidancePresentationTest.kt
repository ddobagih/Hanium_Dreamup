package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CompactGuidancePresentationTest {
    @Test fun riskOverridesRouteAndPause() {
        val result = CompactGuidancePresentationPolicy.present("50m 앞에서 오른쪽으로 이동하세요.", risk = true, paused = true)
        assertEquals("!", result.symbol)
        assertEquals("장애물 주의", result.text)
    }
    @Test fun routeKeepsDistanceAndDropsCommandHints() {
        val result = CompactGuidancePresentationPolicy.present("50m 앞에서 오른쪽으로 이동하세요.\n\n남은 거리 850 m\n\n경로를 따라 안내 중입니다.\n\n다시 말해줘 · 보행 종료")
        assertEquals("50m 앞 우회전\n\n남은 거리 850 m", result.text)
        assertEquals("↱", result.symbol)
    }
    @Test fun unfamiliarRouteIsNotReinterpreted() {
        val result = CompactGuidancePresentationPolicy.present("횡단보도를 건너지 말고 왼쪽 보행로로 이동하세요.")
        assertEquals("횡단보도를 건너지 말고 왼쪽 보행로로 이동하세요.", result.headline)
    }
    @Test fun failureKeepsActionableCauses() {
        val result = CompactGuidancePresentationPolicy.present("점검이 종료됐습니다. 아래 원인을 해결한 뒤 다시 시도를 눌러 주세요.\n위치 요청 실패.\n카메라 렌즈가 가려져 있습니다.")
        assertEquals("점검 필요\n\n위치 설정 확인\n렌즈 가림 확인", result.text)
    }
    @Test fun pausedDoesNotShowOldRouteOrDistance() {
        val result = CompactGuidancePresentationPolicy.present("50m 앞에서 오른쪽으로 이동하세요.\n남은 거리 850 m", paused = true)
        assertEquals("안내 일시정지", result.text)
        assertFalse(result.text.contains("850"))
    }
}
