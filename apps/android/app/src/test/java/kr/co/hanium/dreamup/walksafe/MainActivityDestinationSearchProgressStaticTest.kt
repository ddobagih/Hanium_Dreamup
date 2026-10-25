package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S06-33: locating, searching, results, real zero result and failure are separate screen states. */
class MainActivityDestinationSearchProgressStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val search = MainActivityRefactorSource.function(source, "private fun performDestinationSearch(reset: Boolean)")
    private val ui = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/DestinationSearchPresenter.kt").readText().also {
        assertTrue(MainActivityRefactorSource.member(source, "updateDestinationSearchUi")
            .contains("destinationSearchPresenter.renderSearch("))
        assertTrue(MainActivityRefactorSource.property(source, "destinationSearchPresenter")
            .contains("= DestinationSearchPresenter()"))
    }

    @Test fun inFlightEmptyListIsShownAsSearchingBeforeTheZeroResultText() {
        val empty = ui.substringAfter("if (state.results.isEmpty())")
            .substringBefore("val density")
        val location = empty.indexOf("state.locationMessage != null ->")
        val searching = empty.indexOf("state.inFlight -> \"목적지를 검색하고 있습니다.")
        val zero = empty.indexOf("else -> \"검색 결과가 없습니다. 다른 장소명으로 다시 검색해 주세요.\"")
        assertTrue(location >= 0 && searching > location && zero > searching)
        assertFalse(empty.contains("else -> \"검색 결과 없음\""))
    }

    @Test fun screenIsRefreshedAfterTheRequestIsMarkedInFlight() {
        val start = search.indexOf("destinationSearchInFlight = true")
        val refresh = search.indexOf("actions.render()", start)
        val call = search.indexOf("backend.searchCall(")
        assertTrue(start >= 0 && refresh > start && refresh < call)
    }

    @Test fun failureAndLocationWaitStillUseTheirOwnMessage() {
        assertTrue(search.contains("destinationSearchLocationMessage = null"))
        assertTrue(MainActivityRefactorSource.destination.contains("destinationSearchLocationMessage = \"현재 위치를 확인하고 있습니다."))
        val failureBranch = search.substringAfter("catch (error: Exception)").substringBefore("finally")
        assertTrue(failureBranch.contains("destinationSearchLocationMessage ="))
    }
}
