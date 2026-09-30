package kr.co.hanium.dreamup.walksafe.navigation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** S06-10/S06-11: "more candidates" follows the provider's paging answer, not the unique count. */
class DestinationSearchPagingMetadataTest {
    @Test fun providerAnswerDecidesEvenWhenDeduplicationShrankThePage() {
        // Provider returned 3, normalization kept 2; the provider still has more.
        assertTrue(destinationSearchCanRequestMore(2, requestedCount = 3, maxResults = 10, providerMoreAvailable = true))
        // Exactly 3 results and the provider has nothing more: not "more".
        assertFalse(destinationSearchCanRequestMore(3, requestedCount = 3, maxResults = 10, providerMoreAvailable = false))
    }

    @Test fun unknownProviderAnswerKeepsTheFullPageGuess() {
        assertTrue(destinationSearchCanRequestMore(3, requestedCount = 3, maxResults = 10, providerMoreAvailable = null))
        assertFalse(destinationSearchCanRequestMore(2, requestedCount = 3, maxResults = 10, providerMoreAvailable = null))
    }

    @Test fun neverPastTheCandidateLimit() {
        assertFalse(destinationSearchCanRequestMore(10, requestedCount = 12, maxResults = 10, providerMoreAvailable = true))
        // A request already at the limit cannot fetch anything new, even with fewer unique candidates.
        assertFalse(destinationSearchCanRequestMore(9, requestedCount = 12, maxResults = 10, providerMoreAvailable = true))
        assertTrue(destinationSearchCanRequestMore(8, requestedCount = 9, maxResults = 10, providerMoreAvailable = true))
    }

    @Test fun pagingMetadataIsParsed() {
        val response = parseDestinationSearchResponse(
            payload().put("total_count", 25).put("more_results_available", true), "campus fixture", 3,
        )
        assertEquals(25, response.totalCount)
        assertEquals(true, response.moreResultsAvailable)
    }

    @Test fun absentOrNullPagingMetadataIsUnknown() {
        val absent = parseDestinationSearchResponse(payload(), "campus fixture", 3)
        assertNull(absent.totalCount)
        assertNull(absent.moreResultsAvailable)
        val nulls = parseDestinationSearchResponse(
            payload().put("total_count", JSONObject.NULL).put("more_results_available", JSONObject.NULL),
            "campus fixture",
            3,
        )
        assertNull(nulls.totalCount)
        assertNull(nulls.moreResultsAvailable)
    }

    @Test fun malformedPagingMetadataIsAProtocolError() {
        listOf(
            payload().put("total_count", -1) to "destination_total_count_invalid",
            payload().put("total_count", 2.5) to "destination_total_count_invalid",
            payload().put("total_count", "25") to "destination_total_count_invalid",
            payload().put("more_results_available", "true") to "destination_more_results_invalid",
            payload().put("more_results_available", 1) to "destination_more_results_invalid",
        ).forEach { (json, reason) ->
            val error = runCatching { parseDestinationSearchResponse(json, "campus fixture", 3) }.exceptionOrNull()
            assertTrue(error is GatewayResponseProtocolException)
            assertEquals("invalid gateway response: $reason", error?.message)
        }
    }

    private fun payload(): JSONObject = JSONObject()
        .put("schema_version", "walksafe.destination_search.v1")
        .put("provider", "tmap_poi")
        .put("query", "campus fixture")
        .put(
            "results",
            JSONArray().put(
                JSONObject()
                    .put("id", "tmap-poi:v1:" + "a".repeat(64))
                    .put("name", "Campus Fixture")
                    .put("point", JSONObject().put("latitude", 37.5).put("longitude", 127.1))
                    .put("result_type", "poi"),
            ),
        )
}
