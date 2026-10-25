package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S06-10/S06-11: voice, screen and failure recovery share one provider-based "more" rule. */
class MainActivityDestinationSearchPagingStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val search = MainActivityRefactorSource.function(source, "private fun performDestinationSearch(reset: Boolean)")

    @Test fun theOldUniqueCountGuessIsGoneFromEveryCaller() {
        assertFalse(MainActivityRefactorSource.destination.contains("destinationSearchResults.size >= destinationSearchPage * DESTINATION_SEARCH_PAGE_SIZE"))
        assertFalse(MainActivityRefactorSource.destination.contains("retainedCandidates.size >= destinationSearchPage * DESTINATION_SEARCH_PAGE_SIZE"))
        assertFalse(Regex("destinationSearchResults.size >=\\s+destinationSearchPage").containsMatchIn(MainActivityRefactorSource.destination))
        // hearMore, screen "more" button, retained-candidate recovery and the success page.
        val hearMore = MainActivityRefactorSource.function(source, "fun hearMoreVoiceDestinationCandidates()")
        // S06-9: hearMore no longer asks for a larger page; after the local pages it reads the provider answer.
        assertFalse(hearMore.contains("search().canLoadMore("))
        assertTrue(hearMore.contains("search().state.providerHasMore == true"))
        val ui = MainActivityRefactorSource.member(source, "updateDestinationSearchUi")
        assertTrue(ui.contains("canLoadMore = { destinationSearchController.canLoadMore() }"))
        assertTrue(search.contains("moreResultsAvailable = canLoadMore(retainedCandidates.size),"))
        assertEquals(1, Regex("moreResultsAvailable = canLoadMore\\(\\),").findAll(search).count())
    }

    @Test fun providerAnswerIsStoredPerListAndResetForANewQuery() {
        val reset = search.substringAfter("if (resetSearch) {").substringBefore("}")
        assertTrue(reset.contains("destinationSearchProviderHasMore = null"))
        val success = search.substringAfter("destinationSearchPage = requestedPage")
            .substringBefore("val merged")
        assertTrue(success.contains("destinationSearchProviderHasMore = result.moreResultsAvailable"))
        val rule = MainActivityRefactorSource.function(source, "private fun destinationSearchCanLoadMore(")
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
        val branch = MainActivityRefactorSource.function(source, "private fun performDestinationSearch(reset: Boolean)")
            .substringAfter("if (requestedPageIndex <= lastPageIndex) {")
            .substringBefore("actions.offerCandidates(state, lead + state.voicePrompt())")
        val fallback = branch.substringAfter("} else {")
        assertTrue(fallback.contains("val providerHasMore = canLoadMore()"))
        assertTrue(fallback.contains("moreResultsAvailable = providerHasMore,"))
        assertFalse(fallback.contains("moreResultsAvailable = false"))
        assertTrue(fallback.contains("\"더 안내할 목적지 후보가 없습니다. \""))
    }
}
