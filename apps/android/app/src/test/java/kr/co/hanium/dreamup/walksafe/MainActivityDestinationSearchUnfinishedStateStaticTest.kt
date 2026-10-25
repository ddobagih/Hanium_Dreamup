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
    private val search = MainActivityRefactorSource.function(source, "private fun performDestinationSearch(reset: Boolean)")
    private val ui = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/DestinationSearchPresenter.kt").readText().also {
        assertTrue(MainActivityRefactorSource.member(source, "updateDestinationSearchUi")
            .contains("destinationSearchPresenter.renderSearch("))
        assertTrue(MainActivityRefactorSource.property(source, "destinationSearchPresenter")
            .contains("= DestinationSearchPresenter()"))
    }

    @Test
    fun theZeroResultTextNeedsAFinishedEmptySearchForThisQuery() {
        val empty = ui.substringAfter("if (state.results.isEmpty())").substringBefore("val density")
        val searching = empty.indexOf("state.inFlight -> \"목적지를 검색하고 있습니다.")
        val unfinished = empty.indexOf("state.zeroResultQuery != state.query ->")
        val zero = empty.indexOf("else -> \"검색 결과가 없습니다. 다른 장소명으로 다시 검색해 주세요.\"")
        assertTrue(searching >= 0 && unfinished > searching && zero > unfinished)
        assertTrue(empty.contains("\"검색이 끝나지 않았습니다. 다시 검색하려면 검색을 누르거나 목적지를 말씀해 주세요.\""))
    }

    @Test
    fun onlyTheSuccessfulResponseRecordsAZeroResultAndANewSearchClearsIt() {
        val reset = search.substringAfter("if (resetSearch) {").substringBefore("val searchLocationLease")
        assertTrue(reset.contains("destinationSearchZeroResultQuery = null"))
        val success = search.substringAfter("destinationSearchResults.addAll(")
            .substringBefore("actions.navigationStatus(\"navigation=search_results")
        assertTrue(success.contains("destinationSearchZeroResultQuery = if (destinationSearchResults.isEmpty()) query else null"))
        assertTrue(success.indexOf("destinationSearchZeroResultQuery =") < success.indexOf("actions.render()"))
        // Cancel, failure and a late location answer never mark a zero result.
        assertFalse(search.substringAfter("catch (error: Exception)").substringBefore("finally")
            .contains("destinationSearchZeroResultQuery"))
        val cancel = MainActivityRefactorSource.function(source, "private fun clearDestinationSearchState(transportWasActive: Boolean) {")
        assertFalse(cancel.contains("destinationSearchZeroResultQuery"))
        val location = MainActivityRefactorSource.function(source, "private fun requestDestinationSearchLocation(")
        assertFalse(location.contains("destinationSearchZeroResultQuery"))
        assertTrue(MainActivityRefactorSource.destination.contains("private var destinationSearchZeroResultQuery: String? = null"))
    }
}
