package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S06-33: locating, searching, results, real zero result and failure are separate screen states. */
class MainActivityDestinationSearchProgressStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val search = source.substringAfter("private fun performDestinationSearch(reset: Boolean)")
        .substringBefore("private fun isDestinationSearchLeaseCurrent(")
    private val ui = source.substringAfter("private fun updateDestinationSearchUi()")
        .substringBefore("private fun updateRouteButtonText()")

    @Test fun inFlightEmptyListIsShownAsSearchingBeforeTheZeroResultText() {
        val empty = ui.substringAfter("if (destinationSearchResults.isEmpty())")
            .substringBefore("val density")
        val location = empty.indexOf("destinationSearchLocationMessage != null ->")
        val searching = empty.indexOf("destinationSearchInFlight -> \"목적지를 검색하고 있습니다.")
        val zero = empty.indexOf("else -> \"검색 결과가 없습니다. 다른 장소명으로 다시 검색해 주세요.\"")
        assertTrue(location >= 0 && searching > location && zero > searching)
        assertFalse(empty.contains("else -> \"검색 결과 없음\""))
    }

    @Test fun screenIsRefreshedAfterTheRequestIsMarkedInFlight() {
        val start = search.indexOf("destinationSearchInFlight = true")
        val refresh = search.indexOf("updateDestinationSearchUi()", start)
        val call = search.indexOf("walkingRouteClient.searchDestinationsCall(")
        assertTrue(start >= 0 && refresh > start && refresh < call)
    }

    @Test fun failureAndLocationWaitStillUseTheirOwnMessage() {
        assertTrue(search.contains("destinationSearchLocationMessage = null"))
        assertTrue(source.contains("destinationSearchLocationMessage = \"현재 위치를 확인하고 있습니다."))
        val failureBranch = search.substringAfter("catch (error: Exception)").substringBefore("finally")
        assertTrue(failureBranch.contains("destinationSearchLocationMessage ="))
    }
}
