package kr.co.hanium.dreamup.walksafe.navigation
import org.junit.Assert.*
import org.junit.Test
class TeammateVoiceDestinationTest {
    @Test fun explicitHelpOpenAndCandidateDetailKeepScoredNegationAndAmbiguityProtection() {
        assertEquals(AndroidVoiceAction.OpenHelp, selectAndroidVoiceAction(listOf("도움말 열어줘"), floatArrayOf(.9f)))
        assertEquals(AndroidVoiceAction.SpeakDestinationCandidateDetail(1), selectAndroidVoiceAction(listOf("1번 상세"), floatArrayOf(.9f)))
        for (phrase in listOf("1번 상세 말고 2번 선택", "1 2번 상세", "1번 상세 그리고 안내 시작", "도움말 열지 마", "도움말 열고 신고해"))
            assertNull(phrase, parseAndroidVoiceCommand(phrase))
        assertNull(selectAndroidVoiceAction(listOf("1번 상세"), floatArrayOf(Float.NaN)))
        assertNull(selectAndroidVoiceAction(listOf("1번 상세"), floatArrayOf(1.1f)))
        assertEquals(AndroidVoiceAction.ListLocalQuarantinedReports,selectAndroidVoiceAction(listOf("기기 신고 목록"),floatArrayOf(.9f)))
        assertEquals(AndroidVoiceAction.SelectLocalQuarantinedReport(1),selectAndroidVoiceAction(listOf("기기 신고 1번 선택"),floatArrayOf(.9f)))
        assertEquals(AndroidVoiceAction.ConfirmLocalQuarantinedReportDelete,selectAndroidVoiceAction(listOf("기기 신고 삭제 확인"),floatArrayOf(.9f)))
        assertNull(selectAndroidVoiceAction(listOf("기기 신고 삭제 확인"),null))
        assertNull(parseAndroidVoiceCommand("기기 신고 삭제 확인 하지 마"))
        assertNull(parseAndroidVoiceCommand("기기 신고 1번 선택하고 삭제 확인"))
    }
    @Test fun addressPronunciationDoesNotChangeProviderOrDisplayAndPartsDoNotAuthorizeAnUnheardPage() {
        val result = DestinationSearchResult("id", "장소", RoutePoint(37.0,127.0), "서울동 12-3", null, null, 1200)
        assertEquals("서울동 12-3", result.displayAddressOrNull()); assertEquals("서울동 12의 3",result.speechAddressOrNull())
        val state = DestinationSearchVoiceState("장소",listOf(result))
        assertTrue(state.voicePromptParts().size >= 3)
        assertFalse(state.onCommand(DestinationSearchVoiceCommand.SelectCandidate(1)).accepted)
        val attempt = state.beginVoicePrompt(); assertTrue(state.completeVoicePrompt(attempt))
        assertTrue(state.onCommand(DestinationSearchVoiceCommand.SelectCandidate(1)).accepted)
        assertEquals("약 1.2킬로미터", approximateDestinationDistanceKo(1200))
    }
    @Test fun longLotCoreIsPreservedBeforeNativePronunciationChangesItsHyphen() {
        val address="경상북도 울릉군 울릉읍 섬 일주도로 해안 산책로 입구 공영 주차장 옆 매표소 건물 뒤편 공터 도동리 123-45 일원"
        val first=DestinationSearchResult("first","동일 이름",RoutePoint(37.0,127.0),address,null,null,100)
        val state=DestinationSearchVoiceState("매표소",listOf(first,first.copy(id="second",address="다른동 8-9")))
        assertTrue(state.voicePrompt().contains("도동리 123-45"))
        assertTrue(state.voicePromptParts().any { it.contains("도동리 123의 45") })
        assertEquals(address,first.displayAddressOrNull())
    }

    @Test fun latestLogicalConsumerReusesPhysicalRequestAndCancelledOrRevokedResultsHaveNoConsumer() {
        var callback: ((DestinationSearchLocationFix?) -> Unit)? = null; var requests = 0; var cancellations = 0
        val owner = DestinationSearchLocationController(DestinationSearchLocationSource { completion ->
            requests++; callback = completion; { cancellations++ }
        }, { 1000L })
        val lease = DestinationSearchLocationLease("actor",1L)
        var allowed = true; var old = 0; var latest = 0
        owner.acquire(lease,{allowed}) { old++ }; val first = owner.consumerGeneration
        owner.acquire(lease,{allowed}) { latest++ }; assertTrue(owner.consumerGeneration > first); assertEquals(1,requests)
        callback!!(DestinationSearchLocationFix(37.0,127.0,5f,1000L)); assertEquals(0,old); assertEquals(1,latest)
        owner.cancel(); owner.acquire(lease,{allowed}) { latest++ }; allowed=false
        callback!!(DestinationSearchLocationFix(37.0,127.0,5f,1000L)); assertEquals(1,latest)
        allowed=true; owner.acquire(lease,{allowed}) { latest++ }; val late=callback!!; owner.cancel(); late(null)
        assertEquals(1,latest); assertFalse(owner.isAcquiring); assertTrue(cancellations >= 2)
    }
}
