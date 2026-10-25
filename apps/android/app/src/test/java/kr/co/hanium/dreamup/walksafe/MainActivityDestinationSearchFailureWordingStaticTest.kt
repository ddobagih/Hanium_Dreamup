package kr.co.hanium.dreamup.walksafe

import java.io.File
import kr.co.hanium.dreamup.walksafe.navigation.NavigationBackendErrorKind
import kr.co.hanium.dreamup.walksafe.navigation.destinationSearchUserMessage
import kr.co.hanium.dreamup.walksafe.navigation.formatDestinationSearchVoicePrompt
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S06-42: destination search failures use search wording with a next step, never route wording. */
class MainActivityDestinationSearchFailureWordingStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private val search = MainActivityRefactorSource.function(source, "private fun performDestinationSearch(reset: Boolean)")

    @Test fun searchFailureBranchUsesTheSearchMessageSet() {
        val failureBranch = search.substringAfter("catch (error: Exception)").substringBefore("finally")
        assertTrue(failureBranch.contains("classifyNavigationBackendFailure(error)"))
        assertTrue(failureBranch.contains("failure.kind.destinationSearchUserMessage"))
        assertFalse(failureBranch.contains("failure.kind.userMessage"))
        assertTrue(failureBranch.contains("destinationSearchLocationMessage = searchFailureMessage"))
        assertTrue(failureBranch.contains("actions.speak(searchFailureMessage)"))
    }

    @Test fun everySearchFailureMessageAvoidsRouteWordingAndNamesANextStep() {
        NavigationBackendErrorKind.entries.forEach { kind ->
            val message = kind.destinationSearchUserMessage
            assertFalse("${kind.name}: $message", message.contains("경로"))
            assertTrue(
                "${kind.name}: $message",
                message.contains("다시 검색") || message.contains("문의"),
            )
        }
    }

    @Test fun zeroResultVoicePromptOffersAnotherSearch() {
        val prompt = formatDestinationSearchVoicePrompt("가지마을", emptyList())
        assertTrue(prompt.startsWith("가지마을 목적지 검색 결과가 없습니다."))
        assertTrue(prompt.contains("다른 장소명으로 다시 말씀해 주세요."))
    }
}
