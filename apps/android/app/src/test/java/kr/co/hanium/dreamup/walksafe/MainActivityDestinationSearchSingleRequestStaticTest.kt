package kr.co.hanium.dreamup.walksafe

import java.io.File
import kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchResult
import kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchVoiceCommand
import kr.co.hanium.dreamup.walksafe.navigation.DestinationSearchVoiceState
import kr.co.hanium.dreamup.walksafe.navigation.RoutePoint
import kr.co.hanium.dreamup.walksafe.navigation.destinationSearchCanRequestMore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S06-9 (S06-13, S06-14): a destination search asks once for the whole candidate cap and "더 듣기"
 * pages that one list locally. No second request means no merge that keeps a stale distance and no
 * list that mixes two search origins.
 */
class MainActivityDestinationSearchSingleRequestStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val search = source.substringAfter("private fun performDestinationSearch(reset: Boolean)")
        .substringBefore("private fun isDestinationSearchLeaseCurrent(")
    private val hearMore = source.substringAfter("private fun hearMoreVoiceDestinationCandidates() {")
        .substringBefore("private fun cancelDestinationFromVoice() {")

    @Test fun oneSearchRequestsTheWholeCandidateCap() {
        assertTrue(source.contains("const val DESTINATION_SEARCH_MAX_RESULTS = 10"))
        assertTrue(search.contains("val queryLimit = DESTINATION_SEARCH_MAX_RESULTS"))
        assertTrue(search.contains("limit = queryLimit,"))
    }

    @Test fun hearMoreNeverStartsASearchOrALocationRequest() {
        assertFalse(hearMore.contains("performDestinationSearch("))
        assertFalse(hearMore.contains("requestDestinationSearchLocation("))
        assertFalse(hearMore.contains("destinationSearchLocationLeaseOrNull("))
        assertFalse(hearMore.contains("pendingVoiceDestinationPageIndex = state.pageIndex + 1"))
        assertTrue(hearMore.contains("state.onCommand(DestinationSearchVoiceCommand.HearMore)"))
        // The provider may still have more than the cap: say how to find others instead of fetching.
        assertTrue(hearMore.contains("if (destinationSearchProviderHasMore == true) {"))
        assertTrue(hearMore.contains("더 자세한 장소명으로"))
    }

    @Test fun anAppendRequestIsRefusedBeforeLocationOrNetwork() {
        val guard = search.indexOf("if (!resetSearch) return false")
        assertTrue(guard >= 0)
        assertTrue(guard < search.indexOf("destinationSearchLocationLeaseOrNull()"))
        assertTrue(guard < search.indexOf("walkingRouteClient.searchDestinationsCall("))
        // A new search (voice or keyboard) replaces the list and its voice state as a new version.
        val reset = search.substringAfter("if (resetSearch) {").substringBefore("}")
        assertTrue(reset.contains("destinationSearchResults.clear()"))
        assertTrue(reset.contains("destinationSearchVoiceState = null"))
    }

    @Test fun theMoreRuleKnowsTheCapWasAlreadyRequested() {
        val rule = source.substringAfter("private fun destinationSearchCanLoadMore(")
            .substringBefore("private fun isDestinationSearchLeaseCurrent(")
        assertTrue(rule.contains("requestedCount = DESTINATION_SEARCH_MAX_RESULTS,"))
        assertFalse(rule.contains("destinationSearchPage * DESTINATION_SEARCH_PAGE_SIZE"))
        // Whatever the provider says, a cap-sized request leaves nothing larger to ask for.
        listOf(true, false, null).forEach { providerMore ->
            (0..10).forEach { count ->
                assertFalse(destinationSearchCanRequestMore(count, 10, 10, providerMore))
            }
        }
    }

    @Test fun tenFetchedCandidatesArePagedThreeThreeThreeOneWithoutARequest() {
        val results = (1..10).map { index ->
            DestinationSearchResult(
                id = "poi-$index", name = "장소 $index", point = RoutePoint(37.5 + index * 0.001, 127.0),
                address = null, roadAddress = null, category = null, distanceM = index * 100,
            )
        }
        var state = DestinationSearchVoiceState(query = "장소", results = results)
        val pages = mutableListOf(state.currentPageResults.map { it.id })
        while (true) {
            val next = state.onCommand(DestinationSearchVoiceCommand.HearMore)
            if (!next.accepted) break
            state = next.state
            pages += state.currentPageResults.map { it.id }
        }
        assertEquals(listOf(3, 3, 3, 1), pages.map { it.size })
        assertEquals(results.map { it.id }, pages.flatten())
        assertEquals(3, state.pageIndex)
        assertFalse(state.canHearMore)
        // Every page reads the same list: numbers and distances never change between pages.
        assertEquals(results, state.results)
        assertTrue(state.voicePrompt().contains("10번"))
    }
}
