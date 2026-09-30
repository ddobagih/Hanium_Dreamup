package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S06-10/S06-11: voice, screen and failure recovery share one provider-based "more" rule. */
class MainActivityDestinationSearchPagingStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val search = source.substringAfter("private fun performDestinationSearch(reset: Boolean)")
        .substringBefore("private fun isDestinationSearchLeaseCurrent(")

    @Test fun theOldUniqueCountGuessIsGoneFromEveryCaller() {
        assertFalse(source.contains("destinationSearchResults.size >= destinationSearchPage * DESTINATION_SEARCH_PAGE_SIZE"))
        assertFalse(source.contains("retainedCandidates.size >= destinationSearchPage * DESTINATION_SEARCH_PAGE_SIZE"))
        assertFalse(Regex("destinationSearchResults.size >=\\s+destinationSearchPage").containsMatchIn(source))
        // hearMore, screen "more" button, retained-candidate recovery and the success page.
        val hearMore = source.substringAfter("private fun hearMoreVoiceDestinationCandidates() {")
            .substringBefore("private fun cancelDestinationFromVoice() {")
        // S06-9: hearMore no longer asks for a larger page; after the local pages it reads the provider answer.
        assertFalse(hearMore.contains("destinationSearchCanLoadMore()"))
        assertTrue(hearMore.contains("destinationSearchProviderHasMore == true"))
        val ui = source.substringAfter("private fun updateDestinationSearchUi()")
            .substringBefore("private fun updateRouteButtonText()")
        assertTrue(ui.contains("val canLoadMore = destinationSearchCanLoadMore()"))
        assertTrue(search.contains("moreResultsAvailable = destinationSearchCanLoadMore(retainedCandidates.size),"))
        assertEquals(1, Regex("moreResultsAvailable = destinationSearchCanLoadMore\\(\\),").findAll(search).count())
    }

    @Test fun providerAnswerIsStoredPerListAndResetForANewQuery() {
        val reset = search.substringAfter("if (resetSearch) {").substringBefore("}")
        assertTrue(reset.contains("destinationSearchProviderHasMore = null"))
        val success = search.substringAfter("destinationSearchPage = requestedPage")
            .substringBefore("val merged")
        assertTrue(success.contains("destinationSearchProviderHasMore = result.moreResultsAvailable"))
        val rule = source.substringAfter("private fun destinationSearchCanLoadMore(")
            .substringBefore("private fun isDestinationSearchLeaseCurrent(")
        assertTrue(rule.contains("destinationSearchCanRequestMore("))
        assertTrue(rule.contains("providerMoreAvailable = destinationSearchProviderHasMore"))
        assertTrue(rule.contains("maxResults = DESTINATION_SEARCH_MAX_RESULTS"))
    }
}

/** S06-11 (w17 review P2): a "more" merge without a new unique candidate keeps the provider answer. */
class MainActivityDestinationSearchEmptyMergeStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    @Test
    fun emptyMoreMergeUsesTheProviderAnswerInsteadOfEndingTheVoiceDialog() {
        val branch = source.substringAfter("if (requestedPageIndex <= lastPageIndex) {")
            .substringBefore("speakHomeDestinationVoicePrompt(state, lead + state.voicePrompt())")
        val fallback = branch.substringAfter("} else {")
        assertTrue(fallback.contains("val providerHasMore = destinationSearchCanLoadMore()"))
        assertTrue(fallback.contains("moreResultsAvailable = providerHasMore,"))
        assertFalse(fallback.contains("moreResultsAvailable = false"))
        assertTrue(fallback.contains("\"더 안내할 목적지 후보가 없습니다. \""))
    }
}
