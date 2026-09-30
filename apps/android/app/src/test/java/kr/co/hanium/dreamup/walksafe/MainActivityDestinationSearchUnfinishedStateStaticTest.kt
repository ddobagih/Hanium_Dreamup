package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S06-33 (06.txt:240) review P2: "검색 결과가 없습니다" is shown only for a finished search that really
 * returned nothing for the current query. A cancelled search or a late location answer after the input
 * changed (no search ran) says the search did not finish.
 */
class MainActivityDestinationSearchUnfinishedStateStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val search = source.substringAfter("private fun performDestinationSearch(reset: Boolean)")
        .substringBefore("private fun isDestinationSearchLeaseCurrent(")
    private val ui = source.substringAfter("private fun updateDestinationSearchUi()")
        .substringBefore("private fun updateRouteButtonText()")

    @Test
    fun theZeroResultTextNeedsAFinishedEmptySearchForThisQuery() {
        val empty = ui.substringAfter("if (destinationSearchResults.isEmpty())").substringBefore("val density")
        val searching = empty.indexOf("destinationSearchInFlight -> \"목적지를 검색하고 있습니다.")
        val unfinished = empty.indexOf("destinationSearchZeroResultQuery != destinationSearchQuery ->")
        val zero = empty.indexOf("else -> \"검색 결과가 없습니다. 다른 장소명으로 다시 검색해 주세요.\"")
        assertTrue(searching >= 0 && unfinished > searching && zero > unfinished)
        assertTrue(empty.contains("\"검색이 끝나지 않았습니다. 다시 검색하려면 검색을 누르거나 목적지를 말씀해 주세요.\""))
    }

    @Test
    fun onlyTheSuccessfulResponseRecordsAZeroResultAndANewSearchClearsIt() {
        val reset = search.substringAfter("if (resetSearch) {").substringBefore("val searchLocationLease")
        assertTrue(reset.contains("destinationSearchZeroResultQuery = null"))
        val success = search.substringAfter("destinationSearchResults.addAll(")
            .substringBefore("updateNavigationStatus(\"navigation=search_results")
        assertTrue(success.contains("destinationSearchZeroResultQuery = if (destinationSearchResults.isEmpty()) query else null"))
        assertTrue(success.indexOf("destinationSearchZeroResultQuery =") < success.indexOf("updateDestinationSearchUi()"))
        // Cancel, failure and a late location answer never mark a zero result.
        assertFalse(search.substringAfter("catch (error: Exception)").substringBefore("finally")
            .contains("destinationSearchZeroResultQuery"))
        val cancel = source.substringAfter("private fun clearDestinationSearchState(transportWasActive: Boolean) {")
            .substringBefore("private fun onDestinationSelected(")
        assertFalse(cancel.contains("destinationSearchZeroResultQuery"))
        val location = source.substringAfter("private fun requestDestinationSearchLocation(")
            .substringBefore("private fun performDestinationSearch(")
        assertFalse(location.contains("destinationSearchZeroResultQuery"))
        assertTrue(source.contains("private var destinationSearchZeroResultQuery: String? = null"))
    }
}
