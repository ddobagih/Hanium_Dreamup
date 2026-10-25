package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** S06-12: merging an added page keeps announced numbers and never exceeds the candidate cap. */
class MainActivityDestinationSearchMergeCapStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val search = MainActivityRefactorSource.function(source, "private fun performDestinationSearch(reset: Boolean)")

    @Test fun mergedListIsCappedAfterDeduplicationWithExistingCandidatesFirst() {
        val merge = search.substringAfter("val merged = if (resetSearch) result.results else {")
            .substringBefore("actions.render()")
        assertTrue(merge.contains("destinationSearchResults + result.results"))
        val distinct = merge.indexOf("merged.distinctBy {")
        val cap = merge.indexOf(".take(DESTINATION_SEARCH_MAX_RESULTS)")
        assertTrue(distinct >= 0 && cap > distinct)
        assertTrue(MainActivityRefactorSource.destination.contains("const val DESTINATION_SEARCH_MAX_RESULTS = 10"))
    }
}
