package kr.co.hanium.dreamup.walksafe.navigation

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S06-37 / S06-31 (FP-022-DETAIL-01): each candidate is read as number, name, kind, rough direction and
 * rough distance; the address only separates same-name places; distances are never finer than the
 * search origin's accuracy.
 */
class DestinationCandidateSpeechTest {
    private val origin = DestinationSearchOrigin(RoutePoint(37.5, 127.0), accuracyM = 10f)
    private val coarseOrigin = DestinationSearchOrigin(RoutePoint(37.5, 127.0), accuracyM = 80f)

    @Test fun distanceIsRoundedToThePolicyApproximation() {
        assertEquals("약 10미터 이내", approximateDestinationDistanceKo(7))
        assertEquals("약 40미터", approximateDestinationDistanceKo(36))
        assertEquals("약 150미터", approximateDestinationDistanceKo(149))
        assertEquals("약 300미터", approximateDestinationDistanceKo(320))
        assertEquals("약 1킬로미터", approximateDestinationDistanceKo(990))
        assertEquals("약 1.2킬로미터", approximateDestinationDistanceKo(1_234))
        assertEquals("약 12킬로미터", approximateDestinationDistanceKo(11_960))
        assertEquals("거리 미상", approximateDestinationDistanceKo(null))
        assertEquals("거리 미상", approximateDestinationDistanceKo(-1))
        assertFalse(approximateDestinationDistanceKo(Int.MAX_VALUE).contains("-"))
    }

    @Test fun aCoarseOriginWidensTheStepAndIsAnnounced() {
        assertEquals("약 100미터", approximateDestinationDistanceKo(149, originAccuracyM = 80f))
        assertEquals("약 100미터 이내", approximateDestinationDistanceKo(30, originAccuracyM = 80f))
        assertEquals("약 150미터", approximateDestinationDistanceKo(149, originAccuracyM = 50f))
        assertEquals("현재 위치 오차가 커서 거리는 대략입니다.", destinationOriginUncertaintyNoticeKo(coarseOrigin))
        assertNull(destinationOriginUncertaintyNoticeKo(origin))
        assertNull(destinationOriginUncertaintyNoticeKo(null))
    }

    @Test fun directionIsAnEightPointCompassOnlyOutsideTheOriginUncertainty() {
        val north = RoutePoint(37.5 + 300.0 / 111_320.0, 127.0)
        val east = RoutePoint(37.5, 127.0 + 300.0 / (111_320.0 * Math.cos(Math.toRadians(37.5))))
        val southWest = RoutePoint(37.5 - 0.002, 127.0 - 0.0025)
        assertEquals("북쪽", approximateDestinationDirectionKo(origin, north))
        assertEquals("동쪽", approximateDestinationDirectionKo(origin, east))
        assertEquals("남서쪽", approximateDestinationDirectionKo(origin, southWest))
        // Too close for any direction, or inside twice the origin error: not said.
        assertNull(approximateDestinationDirectionKo(origin, RoutePoint(37.5 + 20.0 / 111_320.0, 127.0)))
        assertNull(approximateDestinationDirectionKo(coarseOrigin, RoutePoint(37.5 + 150.0 / 111_320.0, 127.0)))
        assertEquals("북쪽", approximateDestinationDirectionKo(coarseOrigin, north))
        assertNull(approximateDestinationDirectionKo(null, north))
        assertNull(approximateDestinationDirectionKo(origin.copy(accuracyM = null), north))
    }

    @Test fun pageReadsNameKindDirectionAndDistanceWithoutTheFullAddress() {
        val results = listOf(
            candidate("1", "서울역", category = "교통편의>기차역", road = "서울 중구 한강대로 405", north = 320.0, distanceM = 320),
            candidate("2", "남대문시장", category = "시장", road = "서울 중구 남대문시장4길 21", north = -610.0, distanceM = 612),
            candidate("3", "숭례문", category = null, road = "서울 중구 세종대로 40", north = 5.0, distanceM = 5),
        )
        val prompt = DestinationSearchVoiceState("서울", results, origin = origin).voicePrompt()
        assertTrue(prompt, prompt.contains("1번 서울역, 기차역, 북쪽 약 300미터"))
        assertTrue(prompt, prompt.contains("2번 남대문시장, 시장, 남쪽 약 600미터"))
        assertTrue(prompt, prompt.contains("3번 숭례문, 약 10미터 이내"))
        listOf("한강대로 405", "남대문시장4길", "세종대로 40", "320m", "612m", "5m").forEach { exact ->
            assertFalse(exact, prompt.contains(exact))
        }
        assertFalse(prompt.contains("오차가 커서"))
    }

    @Test fun sameNamePlacesAreToldApartByTheirRoadAddressOnly() {
        val results = listOf(
            candidate("1", "스타벅스", category = "카페", road = "서울 강남구 테헤란로 152", north = 200.0, distanceM = 200),
            candidate("2", "스타 벅스", category = "카페", road = "서울 강남구 역삼로 180", north = 450.0, distanceM = 450),
            candidate("3", "투썸플레이스", category = "카페", road = "서울 강남구 논현로 508", north = 700.0, distanceM = 700),
        )
        val prompt = DestinationSearchVoiceState("카페", results, origin = origin).voicePrompt()
        assertTrue(prompt, prompt.contains("1번 스타벅스, 카페, 서울 강남구 테헤란로 152, 북쪽 약 200미터"))
        assertTrue(prompt, prompt.contains("2번 스타 벅스, 카페, 서울 강남구 역삼로 180, 북쪽 약 450미터"))
        assertTrue(prompt, prompt.contains("3번 투썸플레이스, 카페, 북쪽 약 700미터"))
        assertFalse(prompt.contains("논현로"))
    }

    @Test fun coarseOriginPageSaysOnceThatDistancesAreRough() {
        val results = listOf(
            candidate("1", "도서관", category = "도서관", road = null, north = 149.0, distanceM = 149),
            candidate("2", "공원", category = "공원", road = null, north = 420.0, distanceM = 420),
        )
        val prompt = DestinationSearchVoiceState("도서관", results, origin = coarseOrigin).voicePrompt()
        assertEquals(1, prompt.windowed(9).count { it == "오차가 커서 거리" })
        assertTrue(prompt, prompt.contains("1번 도서관, 도서관, 약 100미터"))
        assertTrue(prompt, prompt.contains("2번 공원, 공원, 북쪽 약 400미터"))
    }

    @Test fun kindLabelUsesTheLastProviderLevelAndSkipsLongValues() {
        assertEquals("한식", destinationCategoryLabelKo("음식점 > 한식"))
        assertEquals("카페", destinationCategoryLabelKo(" 카페 "))
        assertNull(destinationCategoryLabelKo(null))
        assertNull(destinationCategoryLabelKo(" > "))
        assertNull(destinationCategoryLabelKo("가".repeat(21)))
    }

    @Test fun theSearchControllerKeepsTheAcceptedFixAccuracy() {
        var now = 1_000L
        val lease = DestinationSearchLocationLease("actor", 1L)
        val controller = DestinationSearchLocationController(
            source = { callback ->
                callback(DestinationSearchLocationFix(37.5, 127.0, accuracyM = 64f, elapsedRealtimeMs = now))
                ({})
            },
            nowElapsedRealtimeMs = { now },
        )
        controller.acquire(lease, stillAllowed = { true }) { }
        // w25 S06-31 (06.txt:233 "검색 기준 위치의 시각·정확도를 보존"): the fix time is kept with the accuracy.
        assertEquals(
            DestinationSearchOrigin(RoutePoint(37.5, 127.0), 64f, observedAtElapsedRealtimeMs = 1_000L),
            controller.searchOriginOrNull(lease),
        )
        now += DestinationSearchLocationPolicy.MAX_AGE_MS + 1
        assertNull(controller.searchOriginOrNull(lease))
    }

    @Test fun mainActivityCarriesTheOriginWithTheList() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val search = source.substringAfter("private fun performDestinationSearch(reset: Boolean)")
            .substringBefore("private fun isDestinationSearchLeaseCurrent(")
        assertTrue(search.contains("searchOriginOrNull(lease)"))
        assertTrue(search.contains("if (resetSearch) destinationSearchOrigin = searchOrigin"))
        assertTrue(search.contains("origin = destinationSearchOrigin,"))
        val shown = source.substringAfter("private fun voiceStateForShownDestinationResultsOrNull()")
            .substringBefore("private fun noVoiceDestinationCandidatesMessage()")
        assertTrue(shown.contains("origin = destinationSearchOrigin,"))
        val clear = source.substringAfter("private fun clearNativeDestinationSearchState()")
            .substringBefore("private fun cancelNativeDestinationSearchAndReturnHome()")
        assertTrue(clear.contains("destinationSearchOrigin = null"))
    }

    private fun candidate(
        id: String,
        name: String,
        category: String?,
        road: String?,
        north: Double,
        distanceM: Int,
    ) = DestinationSearchResult(
        id = id,
        name = name,
        point = RoutePoint(37.5 + north / 111_320.0, 127.0),
        address = null,
        roadAddress = road,
        category = category,
        distanceM = distanceM,
    )
}
