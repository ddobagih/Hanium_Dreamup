package kr.co.hanium.dreamup.walksafe.navigation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** S07-38: provider coordinate families preserve provenance, not verified walkable entrances. */
class DestinationCoordinateEvidenceRegressionTest {
    private val selectedPoint = RoutePoint(37.5547, 126.9706, "서울역")
    private val frontPointQ = RoutePoint(37.5548, 126.9708)
    private val frontPointR = RoutePoint(37.5546, 126.9704)

    @Test
    fun differentProviderCoordinateCandidatesRemainDistinctAfterParsing() {
        val withQ = parseDestinationSearchResponse(
            payload("noor", JSONArray().put(candidate("noor", selectedPoint)).put(candidate("front", frontPointQ))),
            query = "서울역",
        )
        val withR = parseDestinationSearchResponse(
            payload("noor", JSONArray().put(candidate("noor", selectedPoint)).put(candidate("front", frontPointR))),
            query = "서울역",
        )

        assertNormalSelectedPoint(withQ)
        assertNormalSelectedPoint(withR)
        assertEquals(withQ.results.single().point, withR.results.single().point)
        assertNotEquals(
            "different front-coordinate candidates must survive parsing despite the same selected noor point",
            withQ.results.single(),
            withR.results.single(),
        )
    }

    @Test
    fun sameSelectedPointWithDifferentProviderCoordinateKindsRemainsDistinctAfterParsing() {
        val noor = parseDestinationSearchResponse(
            payload("noor", JSONArray().put(candidate("noor", selectedPoint))),
            query = "서울역",
        )
        val front = parseDestinationSearchResponse(
            payload("front", JSONArray().put(candidate("front", selectedPoint))),
            query = "서울역",
        )

        assertNormalSelectedPoint(noor)
        assertNormalSelectedPoint(front)
        assertEquals(noor.results.single().point, front.results.single().point)
        assertNotEquals(
            "identical coordinates from noor and front must retain their different provider provenance",
            noor.results.single(),
            front.results.single(),
        )
    }

    @Test
    fun legacyResponseWithoutCoordinateEvidenceRemainsAccepted() {
        val response = parseDestinationSearchResponse(payload(), query = "서울역")

        assertNormalSelectedPoint(response)
        assertEquals(
            DestinationSearchResult(
                id = "poi-1",
                name = "서울역",
                point = selectedPoint,
                address = null,
                roadAddress = "서울 중구 한강대로 405",
                category = "교통",
                distanceM = 120,
                resultType = "poi",
            ),
            response.results.single(),
        )
    }

    @Test
    fun allFourProviderFamiliesPreserveTheirOrderCoordinatesAndOptionalPointNames() {
        val newPoint = RoutePoint(37.5549, 126.9709)
        val candidates = JSONArray()
            .put(candidate("noor", selectedPoint))
            .put(candidate("front", frontPointQ))
            .put(candidate("lat", frontPointR).apply { getJSONObject("point").put("name", "다른 좌표") })
            .put(candidate("new", newPoint))
        val response = parseDestinationSearchResponse(payload("noor", candidates), query = "서울역")

        assertNormalSelectedPoint(response)
        assertEquals(
            DestinationCoordinateEvidence(
                DestinationCoordinateKind.NOOR,
                listOf(
                    DestinationCoordinateCandidate(DestinationCoordinateKind.NOOR, selectedPoint.copy(name = null)),
                    DestinationCoordinateCandidate(DestinationCoordinateKind.FRONT, frontPointQ),
                    DestinationCoordinateCandidate(DestinationCoordinateKind.LAT, frontPointR.copy(name = "다른 좌표")),
                    DestinationCoordinateCandidate(DestinationCoordinateKind.NEW, newPoint),
                ),
            ),
            response.results.single().coordinateEvidence,
        )
    }

    @Test
    fun selectedKindOnlyAndCandidateOnlyFactsAreKeptWithoutInventingTheMissingHalf() {
        for (kind in DestinationCoordinateKind.entries) {
            val kindOnly = parseDestinationSearchResponse(payload(kind.wireName), query = "서울역")
            assertNormalSelectedPoint(kindOnly)
            assertEquals(DestinationCoordinateEvidence(kind, emptyList()), kindOnly.results.single().coordinateEvidence)
        }

        val candidatesOnly = parseDestinationSearchResponse(
            payload(coordinateCandidates = JSONArray().put(candidate("front", frontPointQ))),
            query = "서울역",
        )
        assertNormalSelectedPoint(candidatesOnly)
        assertEquals(
            DestinationCoordinateEvidence(null,
                listOf(DestinationCoordinateCandidate(DestinationCoordinateKind.FRONT, frontPointQ))),
            candidatesOnly.results.single().coordinateEvidence,
        )
    }

    @Test
    fun absentNullAndEmptyCoordinateMetadataStayUnknownInLegacyResponses() {
        val unknownPayloads = listOf(
            payload(),
            payload().apply { getJSONArray("results").getJSONObject(0).put("coordinate_kind", JSONObject.NULL) },
            payload().apply { getJSONArray("results").getJSONObject(0).put("coordinate_candidates", JSONObject.NULL) },
            payload(coordinateCandidates = JSONArray()),
            payload(coordinateCandidates = JSONArray()).apply {
                getJSONArray("results").getJSONObject(0).put("coordinate_kind", JSONObject.NULL)
            },
            payload().apply {
                getJSONArray("results").getJSONObject(0)
                    .put("coordinate_kind", JSONObject.NULL).put("coordinate_candidates", JSONObject.NULL)
            },
        )
        for (unknownPayload in unknownPayloads) {
            val response = parseDestinationSearchResponse(unknownPayload, query = "서울역")
            assertNormalSelectedPoint(response)
            assertNull(response.results.single().coordinateEvidence)
        }
    }

    @Test
    fun unknownOrNonStringCoordinateKindsAreRejectedInsteadOfBecomingEntranceEvidence() {
        for (invalidKind in listOf("entrance", "NOOR", "", 1, true)) {
            assertInvalidEvidence(payload().apply {
                getJSONArray("results").getJSONObject(0).put("coordinate_kind", invalidKind)
            })
            assertInvalidEvidence(payload("noor", JSONArray().put(candidate("front", frontPointQ)
                .put("kind", invalidKind))))
        }
        assertInvalidEvidence(payload("noor", JSONArray().put(candidate("front", frontPointQ).apply { remove("kind") })))
        assertInvalidEvidence(payload("noor", JSONArray().put(candidate("front", frontPointQ).put("kind", JSONObject.NULL))))
    }

    @Test
    fun malformedCandidateArraysObjectsAndMoreThanFourCandidatesAreRejected() {
        for (invalidArray in listOf(JSONObject(), "front", 1, true)) {
            assertInvalidEvidence(payload("noor").apply {
                getJSONArray("results").getJSONObject(0).put("coordinate_candidates", invalidArray)
            })
        }
        for (invalidCandidate in listOf("front", 1, true, JSONObject.NULL)) {
            assertInvalidEvidence(payload("noor", JSONArray().put(invalidCandidate)))
        }
        for (invalidPoint in listOf(JSONArray(), "37.5548,126.9708", JSONObject.NULL)) {
            assertInvalidEvidence(payload("noor", JSONArray().put(candidate("front", frontPointQ).put("point", invalidPoint))))
        }
        val tooMany = JSONArray()
        repeat(5) { tooMany.put(candidate("noor", selectedPoint)) }
        assertInvalidEvidence(payload("noor", tooMany))
    }

    @Test
    fun invalidCandidateCoordinatesAndPointNamesRejectTheWholeSearchResponse() {
        val invalidPoints = listOf(
            JSONObject().put("latitude", 91.0).put("longitude", 126.9708),
            JSONObject().put("latitude", 37.5548).put("longitude", -181.0),
            JSONObject().put("latitude", "NaN").put("longitude", 126.9708),
            JSONObject().put("latitude", 37.5548).put("longitude", "Infinity"),
            JSONObject().put("longitude", 126.9708),
            JSONObject().put("latitude", JSONObject.NULL).put("longitude", 126.9708),
            JSONObject().put("latitude", 37.5548).put("longitude", 126.9708).put("name", 42),
        )
        for (invalidPoint in invalidPoints) {
            assertInvalidEvidence(payload("noor", JSONArray()
                .put(candidate("noor", selectedPoint))
                .put(candidate("front", frontPointQ).put("point", invalidPoint))))
        }
    }

    private fun assertInvalidEvidence(payload: JSONObject) {
        val error = assertThrows(GatewayResponseProtocolException::class.java) {
            parseDestinationSearchResponse(payload, query = "서울역")
        }
        assertEquals("destination_results_invalid", error.reason)
    }

    private fun assertNormalSelectedPoint(response: DestinationSearchResponse) {
        assertEquals("tmap_poi", response.provider)
        assertEquals("서울역", response.query)
        assertEquals(1, response.results.size)
        val result = response.results.single()
        assertEquals("poi-1", result.id)
        assertEquals("서울역", result.name)
        assertEquals(selectedPoint, result.point)
    }

    private fun payload(
        coordinateKind: String? = null,
        coordinateCandidates: JSONArray? = null,
    ): JSONObject = JSONObject(
        """
        {
          "schema_version":"walksafe.destination_search.v1",
          "provider":"tmap_poi",
          "query":"서울역",
          "results":[
            {
              "id":"poi-1",
              "name":"서울역",
              "point":{"latitude":37.5547,"longitude":126.9706,"name":"서울역"},
              "address":null,
              "road_address":"서울 중구 한강대로 405",
              "category":"교통",
              "result_type":"poi",
              "distance_m":120
            }
          ]
        }
        """.trimIndent(),
    ).apply {
        val result = getJSONArray("results").getJSONObject(0)
        if (coordinateKind != null) result.put("coordinate_kind", coordinateKind)
        if (coordinateCandidates != null) result.put("coordinate_candidates", coordinateCandidates)
    }

    private fun candidate(kind: String, point: RoutePoint): JSONObject = JSONObject()
        .put("kind", kind)
        .put("point", JSONObject().put("latitude", point.latitude).put("longitude", point.longitude))
}
