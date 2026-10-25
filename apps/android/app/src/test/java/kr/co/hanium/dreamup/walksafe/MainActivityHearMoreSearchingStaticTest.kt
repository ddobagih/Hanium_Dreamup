package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S06-17: "still searching" and "no more candidates" are different answers to 더 듣기. */
class MainActivityHearMoreSearchingStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val hearMore = MainActivityRefactorSource.function(source, "private fun hearMoreVoiceDestinationCandidates(")

    @Test fun inFlightAndExhaustedHaveSeparateMessages() {
        val inFlight = hearMore.indexOf("if (search().state.inFlight) {")
        val searching = hearMore.indexOf("추가 후보를 아직 찾고 있습니다.")
        // S06-9: after the in-flight answer the list is exhausted (no second request exists).
        val exhausted = hearMore.indexOf("if (search().state.providerHasMore == true) {")
        val none = hearMore.indexOf("더 안내할 목적지 후보가 없습니다.")
        assertTrue(inFlight >= 0 && searching > inFlight && exhausted > searching && none > exhausted)
        assertFalse(hearMore.contains("!canLoadMore || destinationSearchInFlight"))
    }

    @Test fun localPagingStillRunsBeforeEitherMessage() {
        val local = hearMore.indexOf("state.onCommand(DestinationSearchVoiceCommand.HearMore)")
        assertTrue(local >= 0 && local < hearMore.indexOf("if (search().state.inFlight) {"))
        // S06-9: "더 듣기" pages the one fetched list; it never requests or merges another page.
        assertFalse(hearMore.contains("performDestinationSearch("))
    }
}
