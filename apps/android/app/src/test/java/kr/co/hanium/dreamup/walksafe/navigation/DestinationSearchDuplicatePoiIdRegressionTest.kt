package kr.co.hanium.dreamup.walksafe.navigation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinationSearchDuplicatePoiIdRegressionTest {
    @Test
    fun sharedProviderIdAcrossDistinctCandidatesStillProducesInvalidResponse() {
        // Mirrors the observed live contract violation using synthetic data only.
        val error = runCatching {
            parseDestinationSearchResponse(payload("shared-id", "shared-id"), "campus fixture", 5)
        }.exceptionOrNull()

        assertTrue(error is GatewayResponseProtocolException)
        assertEquals("invalid gateway response: destination_results_invalid", error?.message)
        assertEquals(
            NavigationBackendErrorKind.INVALID_RESPONSE,
            classifyNavigationBackendFailure(requireNotNull(error)).kind,
        )
    }

    @Test
    fun uniqueOpaqueCandidateIdsPreserveBothChoices() {
        val response = parseDestinationSearchResponse(
            payload("tmap-poi:v1:" + "a".repeat(64), "tmap-poi:v1:" + "b".repeat(64)),
            "campus fixture",
            5,
        )

        assertEquals(listOf("Campus Fixture", "Gate Fixture"), response.results.map { it.name })
        assertEquals(2, response.results.map { it.id }.distinct().size)
        assertTrue(response.results[0].point != response.results[1].point)
    }

    private fun payload(firstId: String, secondId: String): JSONObject = JSONObject()
        .put("schema_version", "walksafe.destination_search.v1")
        .put("provider", "tmap_poi")
        .put("query", "campus fixture")
        .put(
            "results",
            JSONArray()
                .put(candidate(firstId, "Campus Fixture", 36.1, 128.3))
                .put(candidate(secondId, "Gate Fixture", 36.1001, 128.3001)),
        )

    private fun candidate(id: String, name: String, latitude: Double, longitude: Double): JSONObject =
        JSONObject()
            .put("id", id)
            .put("name", name)
            .put("point", JSONObject().put("latitude", latitude).put("longitude", longitude))
            .put("result_type", "poi")
            .put("distance_m", 10)
}
