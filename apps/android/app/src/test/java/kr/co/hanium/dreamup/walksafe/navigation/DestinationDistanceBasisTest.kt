package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.network.GatewayFieldSession
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S06-30 (06.txt:232): the kind of a candidate distance is kept with it and said ("직선으로 약 300미터");
 * S06-31 (06.txt:233): the search origin keeps its fix time and an old list says its distances may have
 * changed; S06-7 (06.txt:165): a voice selection repeats the search-time distance basis;
 * S06-34 (06.txt:241): the numbers the voice dialog reads now, for the screen header.
 */
class DestinationDistanceBasisTest {
    @Test
    fun theDistanceKindIsParsedWhenPresentAndOlderResponsesStayValid() {
        assertEquals(DestinationDistanceSource.STRAIGHT_LINE, parse("\"straight_line\"")?.distanceSource)
        assertEquals(DestinationDistanceSource.TMAP_RADIUS, parse("\"tmap_radius\"")?.distanceSource)
        assertNull(parse(null)!!.distanceSource)
        assertNull(parse("null")!!.distanceSource)
        assertEquals(320, parse("\"straight_line\"")!!.distanceM)
    }

    @Test
    fun anUnknownKindOrAKindWithoutADistanceIsAnInvalidResponse() {
        for ((source, distance) in listOf("\"walking\"" to "320", "1" to "320", "\"straight_line\"" to "null")) {
            val error = assertThrows(GatewayResponseProtocolException::class.java) { parse(source, distance) }
            assertEquals("invalid gateway response: destination_results_invalid", error.message)
        }
    }

    @Test
    fun theKindIsSaidWithTheDistanceAndNeverAsAWalkingDistance() {
        assertEquals("직선으로 약 300미터", destinationDirectionDistanceKo(null, 320, DestinationDistanceSource.STRAIGHT_LINE))
        assertEquals("북쪽으로 직선 약 300미터", destinationDirectionDistanceKo("북쪽", 320, DestinationDistanceSource.STRAIGHT_LINE))
        // The provider radius is not guaranteed to equal the straight-line fallback.
        assertEquals("검색 위치 기준 반경 약 300미터, 도보 거리는 아닙니다", destinationDirectionDistanceKo(null, 320, DestinationDistanceSource.TMAP_RADIUS))
        assertEquals("검색 위치 기준 북쪽 방향 반경 약 300미터, 도보 거리는 아닙니다", destinationDirectionDistanceKo("북쪽", 320, DestinationDistanceSource.TMAP_RADIUS))
        // Unknown kind keeps the existing wording; a coarse origin still widens the step (S06-31).
        assertEquals("북쪽 약 300미터", destinationDirectionDistanceKo("북쪽", 320, null))
        assertEquals("직선으로 약 300미터", destinationDirectionDistanceKo(null, 320, DestinationDistanceSource.STRAIGHT_LINE, 80f))
        assertEquals("거리 미상", destinationDirectionDistanceKo(null, null, null))
        assertEquals("직선 거리", destinationDistanceKindKo(DestinationDistanceSource.STRAIGHT_LINE))
        assertEquals("검색 위치 기준 반경, 도보 거리 아님", destinationDistanceKindKo(DestinationDistanceSource.TMAP_RADIUS))
        assertNull(destinationDistanceKindKo(null))
        for (spoken in listOf(
            destinationDirectionDistanceKo("북쪽", 320, DestinationDistanceSource.STRAIGHT_LINE),
            destinationDirectionDistanceKo(null, 320, DestinationDistanceSource.TMAP_RADIUS),
        )) {
            assertTrue(spoken, !spoken.contains("걸어서"))
        }
        assertTrue(destinationDirectionDistanceKo(null, 320, DestinationDistanceSource.TMAP_RADIUS).contains("도보 거리는 아닙니다"))
        assertTrue(!destinationDirectionDistanceKo(null, 320, DestinationDistanceSource.TMAP_RADIUS).contains("직선"))
    }

    @Test
    fun theCandidatePageSaysTheKind() {
        val prompt = DestinationSearchVoiceState(
            query = "서울역",
            results = listOf(
                result("a", "서울역", 320, DestinationDistanceSource.STRAIGHT_LINE),
                result("b", "서울역 버스환승센터", 480, DestinationDistanceSource.TMAP_RADIUS),
                result("c", "서울역 광장", 90, null),
            ),
        ).voicePrompt()
        assertTrue(prompt, prompt.contains("1번 서울역, 직선으로 약 300미터."))
        assertTrue(prompt, prompt.contains("2번 서울역 버스환승센터, 검색 위치 기준 반경 약 500미터, 도보 거리는 아닙니다."))
        assertTrue(prompt, prompt.contains("3번 서울역 광장, 약 90미터."))
    }

    @Test
    fun anOldSearchOriginSaysOnceThatDistancesMayHaveChanged() {
        val origin = DestinationSearchOrigin(RoutePoint(37.5, 127.0), 10f, observedAtElapsedRealtimeMs = 10_000L)
        assertNull(destinationOriginAgeNoticeKo(origin, 10_000L + DESTINATION_ORIGIN_STALE_AFTER_MS))
        assertEquals(
            "검색한 지 시간이 지나 지금 거리는 달라졌을 수 있습니다.",
            destinationOriginAgeNoticeKo(origin, 10_001L + DESTINATION_ORIGIN_STALE_AFTER_MS),
        )
        assertNull(destinationOriginAgeNoticeKo(origin.copy(observedAtElapsedRealtimeMs = null), Long.MAX_VALUE))
        assertNull(destinationOriginAgeNoticeKo(origin, null))
        assertNull(destinationOriginAgeNoticeKo(origin, 9_999L))
        assertNull(destinationOriginAgeNoticeKo(null, 1_000_000L))
    }

    @Test
    fun aVoiceSelectionRepeatsTheSearchTimeDistanceBasis() {
        val origin = DestinationSearchOrigin(RoutePoint(37.5, 127.0), 10f, observedAtElapsedRealtimeMs = 0L)
        val chosen = result("a", "서울역", 320, DestinationDistanceSource.STRAIGHT_LINE)
        assertEquals("검색 시점 기준 직선으로 약 300미터 거리입니다.", selectedDestinationDistanceSentenceKo(chosen, origin, 1_000L))
        assertEquals(
            "검색 시점의 검색 위치 기준 반경 약 300미터입니다. 도보 거리가 아닙니다.",
            selectedDestinationDistanceSentenceKo(chosen.copy(distanceSource = DestinationDistanceSource.TMAP_RADIUS), origin, 1_000L),
        )
        assertEquals(
            "검색 시점 기준 약 300미터 거리입니다.",
            selectedDestinationDistanceSentenceKo(chosen.copy(distanceSource = null), null),
        )
        assertEquals(
            "검색 시점 기준 직선으로 약 300미터 거리입니다. 현재 위치 오차가 커서 거리는 대략입니다. " +
                "검색한 지 시간이 지나 지금 거리는 달라졌을 수 있습니다.",
            selectedDestinationDistanceSentenceKo(chosen, origin.copy(accuracyM = 80f), 200_000L),
        )
        assertNull(selectedDestinationDistanceSentenceKo(chosen.copy(distanceM = null, distanceSource = null), origin))
    }

    @Test
    fun theVoicePageRangeLabelUsesTheGlobalNumbers() {
        val results = (1..7).map { result("id$it", "후보$it", 100 * it, null) }
        assertEquals("음성 안내 중: 1번~3번", DestinationSearchVoiceState("카페", results).voicePageRangeLabelKo())
        assertEquals("음성 안내 중: 4번~6번", DestinationSearchVoiceState("카페", results, pageIndex = 1).voicePageRangeLabelKo())
        assertEquals("음성 안내 중: 7번", DestinationSearchVoiceState("카페", results, pageIndex = 2).voicePageRangeLabelKo())
        assertNull(DestinationSearchVoiceState("카페", emptyList()).voicePageRangeLabelKo())
    }

    private fun result(id: String, name: String, distanceM: Int?, source: DestinationDistanceSource?) =
        DestinationSearchResult(
            id = id,
            name = name,
            point = RoutePoint(37.55, 126.97),
            address = null,
            roadAddress = null,
            category = null,
            distanceM = distanceM,
            distanceSource = source,
        )

    /** [source] is the raw JSON value of distance_source, or null to leave the field out. */
    private fun parse(source: String?, distance: String = "320"): DestinationSearchResult? {
        val item = """{"id":"poi-1","name":"서울역","point":{"latitude":37.5547,"longitude":126.9706},""" +
            """"address":null,"road_address":"서울 중구 한강대로 405","category":"교통","result_type":"poi",""" +
            """"distance_m":$distance""" + (source?.let { ""","distance_source":$it""" } ?: "") + "}"
        val payload = """{"schema_version":"walksafe.destination_search.v1","provider":"tmap_poi",""" +
            """"query":"서울역","results":[$item]}"""
        JSONObject(payload)
        val client = BackendWalkingRouteClient(
            transport = object : WalkingRouteTransport {
                override fun post(url: String, body: String, session: GatewayFieldSession): String =
                    error("POST should not be called by destination search")

                override fun get(url: String, session: GatewayFieldSession): String = payload
            },
        )
        val session = GatewayFieldSession.verified(
            gatewayBaseUrl = "https://field.example",
            actorId = "search-tester",
            cookiePair = "walksafe_field_session=v2.test.session.cookie",
            expiresAtEpochMs = Long.MAX_VALUE,
        )
        return client.searchDestinations(session, "서울역", limit = 5, origin = null).results.singleOrNull()
    }
}
