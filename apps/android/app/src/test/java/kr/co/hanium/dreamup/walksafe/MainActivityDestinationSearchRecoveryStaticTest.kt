package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wiring guards; async behavior is also exercised by the source-extracted private Main harness. */
class MainActivityDestinationSearchRecoveryStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val search = MainActivityRefactorSource.function(source, "private fun performDestinationSearch(reset: Boolean)")

    @Test fun requestedPageIsCommittedOnlyAfterTheCurrentSuccessfulResponse() {
        assertTrue(search.contains("val requestedPage = if (resetSearch) 1 else destinationSearchPage + 1"))
        // S06-9: the one request asks for the whole cap instead of page * 3.
        assertTrue(search.contains("val queryLimit = DESTINATION_SEARCH_MAX_RESULTS"))
        assertFalse(search.contains("DESTINATION_SEARCH_PAGE_SIZE * requestedPage"))
        val success = search.indexOf("val result = searchCall.execute()")
        assertTrue(success >= 0)
        assertTrue(search.indexOf("destinationSearchPage = requestedPage") > success)
        assertFalse(search.contains("destinationSearchPage += 1"))
        assertTrue(search.contains("val merged = if (resetSearch) result.results"))
    }

    @Test fun appendRecoveryUsesCurrentOwnershipAndOffersTheRetainedPageAgain() {
        val recovery = search.substringAfter("fun recoverRetainedCandidates(")
            .substringBefore("val queryLimit")
        assertTrue(recovery.contains("resetSearch || retainedCandidates.isEmpty() || !isCurrentSearchRequest()"))
        assertTrue(recovery.contains("destinationSearchQuery != query"))
        assertTrue(recovery.contains("results = retainedCandidates"))
        assertTrue(recovery.contains("pageIndex = retainedVoicePageIndex"))
        assertTrue(recovery.contains("destinationSearchVoiceState = state"))
        assertTrue(recovery.contains("actions.offerCandidates(state"))
        val owner = search.substringAfter("fun isCurrentSearchRequest(): Boolean")
            .substringBefore("fun recoverRetainedCandidates(")
        assertTrue(owner.contains("isLeaseCurrent(expectedWalkEpoch, requestId)"))
        assertTrue(owner.contains("generation == expectedGatewaySessionGeneration"))
        assertTrue(owner.contains("facts.isSessionCurrent(gatewaySession)"))
        assertFalse(recovery.contains("destinationSearchGeneration ="))
    }

    @Test fun networkCancellationPendingAndExecutorFailureAllRetainCandidates() {
        assertTrue(search.substringAfter("revalidation.status ==").substringBefore("val result =")
            .contains("recoverRetainedCandidates("))
        assertTrue(search.substringAfter("catch (_: CancellationException)").substringBefore("catch (error: Exception)")
            .contains("recoverRetainedCandidates("))
        assertTrue(search.substringAfter("catch (error: Exception)").substringBefore("finally")
            .contains("recoverRetainedCandidates("))
        val rejected = search.substringAfter("catch (_: RejectedExecutionException)")
        assertTrue(rejected.contains("if (!isCurrentSearchRequest())"))
        assertTrue(rejected.contains("recoverRetainedCandidates("))
        assertFalse(rejected.contains("destinationSearchResults.removeAll"))
        assertFalse(rejected.contains("destinationSearchResults.clear"))
    }
}
