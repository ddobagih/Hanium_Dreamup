package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S06-8: searching, out-of-range, expired/unspoken dialog and no results get different answers. */
class MainActivityDestinationSelectionRejectionStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val selection = source.substringAfter("private fun selectVoiceDestinationCandidate(oneBasedIndex: Int) {")
        .substringBefore("private fun explainRouteCommandUnavailable(")

    @Test fun rejectionBranchesAreDistinctAndRetryUsesTheMessage() {
        val missing = selection.substringAfter("if (selected == null) {").substringBefore("if (nativeHomeFeatureContextAvailable())")
        val searching = missing.indexOf("destinationSearchInFlight -> \"목적지 검색이 끝난 뒤 후보 번호를 말씀해 주세요.\"")
        val outOfRange = missing.indexOf("state != null -> state.selectionRejectionMessage(oneBasedIndex)")
        val expired = missing.indexOf("destinationSearchResults.isNotEmpty() ->")
        val none = missing.indexOf("else -> \"선택할 목적지 후보가 없습니다. 먼저 목적지를 검색해 주세요.\"")
        assertTrue(searching >= 0 && outOfRange > searching && expired > outOfRange && none > expired)
        assertTrue(missing.contains("다시 듣기라고 말씀하시면 현재 후보를 안내합니다."))
        assertTrue(missing.contains("if (!retryHomeDestinationVoiceDialog(message)) {"))
        assertFalse(missing.contains("\"선택할 \${oneBasedIndex}번 목적지 후보가 없습니다.\""))
        val retry = source.substringAfter("private fun retryHomeDestinationVoiceDialog(message: String): Boolean {")
            .substringBefore("private fun speakHomeDestinationVoicePrompt(")
        assertTrue(retry.contains("speakHomeDestinationVoicePrompt(\n            state,\n            message,\n        )"))
        assertFalse(retry.contains("인식하지 못하였습니다"))
    }

    @Test fun recognitionFailureKeepsItsOwnWording() {
        assertTrue(source.contains("if (!retryHomeDestinationVoiceDialog(\"말씀을 정확히 듣지 못했습니다.\")) {"))
    }
}
