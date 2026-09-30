package kr.co.hanium.dreamup.walksafe.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * voice-F6 (S06-31 06.txt:233; ㊲ 06.txt:237 shorter prompts): the "검색한 지 시간이 지나…" notice of an old
 * candidate list is said once per searched list, inside the page next to the distance basis - so an
 * answer spoken before the page ("명령을 이해하지 못했습니다.") stays first.
 */
class DestinationOriginAgeNoticeOnceTest {
    private val notice = "검색한 지 시간이 지나 지금 거리는 달라졌을 수 있습니다."
    private val oldOrigin = DestinationSearchOrigin(RoutePoint(37.5, 127.0), 10f, observedAtElapsedRealtimeMs = 0L)
    private val late = DESTINATION_ORIGIN_STALE_AFTER_MS + 1L

    @Test
    fun theNoticeIsSaidOncePerSearchedList() {
        val once = DestinationOriginAgeNoticeOnce()
        val results = (1..7).map { result("id$it", "후보$it") }
        val first = DestinationSearchVoiceState("카페", results, origin = oldOrigin)
        assertEquals(notice, once.noticeOrNull(first.origin, late))
        // A submitted page may fail or be interrupted before speech completion; it must still say it.
        assertEquals(notice, once.noticeOrNull(first.origin, late + 1L))
        once.markDelivered(first.origin)
        // Repeat, more and previous pages keep the same origin and do not say it again.
        assertNull(once.noticeOrNull(first.origin, late + 10_000L))
        assertNull(once.noticeOrNull(first.copy(pageIndex = 1).origin, late + 20_000L))
        assertNull(once.noticeOrNull(first.copy(pageIndex = 1).copy(pageIndex = 0).origin, late + 30_000L))
        // A new search has a new origin and may say it again; a recent or unknown fix time says nothing.
        val newOrigin = oldOrigin.copy(observedAtElapsedRealtimeMs = 1_000L)
        assertNull(once.noticeOrNull(newOrigin, 2_000L))
        assertEquals(notice, once.noticeOrNull(newOrigin, 1_000L + late))
        once.markDelivered(newOrigin)
        assertNull(once.noticeOrNull(newOrigin, 1_000L + late + 1L))
        assertNull(once.noticeOrNull(null, Long.MAX_VALUE))
        assertNull(DestinationOriginAgeNoticeOnce().noticeOrNull(oldOrigin.copy(observedAtElapsedRealtimeMs = null), late))
    }

    @Test
    fun theNoticeSitsInThePageNextToTheDistanceBasisNotBeforeTheAnswer() {
        val state = DestinationSearchVoiceState("카페", listOf(result("a", "스타벅스"), result("b", "투썸")), origin = oldOrigin)
        val plain = state.voicePrompt()
        val withNotice = state.voicePrompt(originAgeNotice = notice)
        assertFalse(plain.contains(notice))
        assertTrue(withNotice, withNotice.startsWith("카페 목적지 후보는 현재 1번부터 2번까지입니다. $notice "))
        assertTrue(withNotice.indexOf(notice) < withNotice.indexOf("1번 스타벅스"))
        assertEquals(plain, state.voicePrompt(originAgeNotice = null))
        // A re-heard page: the distance basis first, then the age notice.
        state.beginVoicePrompt()
        val reheard = state.voicePrompt(originAgeNotice = notice)
        assertTrue(reheard, reheard.contains("거리는 처음 검색한 위치 기준입니다. $notice "))
        // The caller's composition keeps its own answer first (MainActivity speakHomeDestinationVoicePrompt).
        val answer = "명령을 이해하지 못했습니다."
        val composed = "$answer ${state.voicePrompt(originAgeNotice = notice)}"
        assertTrue(composed.startsWith(answer))
        assertEquals(1, Regex(Regex.escape(notice)).findAll(composed).count())
    }

    private fun result(id: String, name: String) = DestinationSearchResult(
        id = id,
        name = name,
        point = RoutePoint(37.55, 126.97),
        address = null,
        roadAddress = null,
        category = null,
        distanceM = 300,
    )
}
