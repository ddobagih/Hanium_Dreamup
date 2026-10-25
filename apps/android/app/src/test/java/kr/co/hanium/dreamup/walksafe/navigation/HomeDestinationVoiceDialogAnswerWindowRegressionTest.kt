package kr.co.hanium.dreamup.walksafe.navigation

import java.io.File
import kr.co.hanium.dreamup.walksafe.MainActivityRefactorSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeDestinationVoiceDialogAnswerWindowRegressionTest {
    private var now = 0L

    @Test
    fun aPagePromptLongerThanTheTimeoutStillLeavesTheWholeAnswerWindow() {
        val state = page()
        val lease = lease(state)
        val attempt = state.beginVoicePrompt()
        now = 31_000L // a three-candidate page can take this long to read aloud
        assertSame("the dialog must survive its own prompt", state, current(lease, state))
        assertTrue(state.completeVoicePrompt(attempt))
        lease.openAnswerWindow()
        now = 32_000L
        assertSame(state, current(lease, state))
        assertTrue(select(state, 1).accepted)
        now = 60_999L
        assertSame(state, current(lease, state))
        now = 61_000L
        assertNull(current(lease, state))
        assertFalse("expiry revokes the page's selection authority", select(state, 1).accepted)
    }

    @Test
    fun aFailedPromptStartsTheSameExpiryAndCannotBeExtended() {
        val state = page()
        val lease = lease(state)
        state.failVoicePrompt(state.beginVoicePrompt())
        now = 5_000L
        lease.openAnswerWindow()
        now = 20_000L
        lease.openAnswerWindow()
        now = 34_999L
        assertSame(state, current(lease, state))
        now = 35_000L
        assertNull(current(lease, state))
        lease.openAnswerWindow()
        now = 35_001L
        assertNull("a closed dialog cannot be reopened", current(lease, state))
    }

    @Test
    fun clockGoingBackwardsBeforeOrAfterTheAnswerWindowOpensClosesTheDialog() {
        for (openFirst in listOf(false, true)) {
            now = 1_000L
            val state = page()
            val lease = lease(state)
            now = 10_000L
            if (openFirst) lease.openAnswerWindow()
            now = if (openFirst) 9_999L else 999L
            assertNull("openFirst=$openFirst", current(lease, state))
        }
    }

    @Test
    fun withoutThePromptOptionTheCreationClockIsUnchanged() {
        val state = page()
        val lease = HomeDestinationVoiceDialogLease("actor", 1L, 1, state, { now })
        lease.openAnswerWindow() // cannot restart a clock that already runs
        now = 29_999L
        assertSame(state, current(lease, state))
        now = 30_000L
        assertNull(current(lease, state))
    }

    @Test
    fun mainActivityOpensTheAnswerWindowOnlyFromThePromptTerminalCallbacks() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/interaction/DestinationVoiceDialogController.kt").readText()
        val prompt = MainActivityRefactorSource.member(source, "speakHomeDestinationVoicePrompt")
        val creation = prompt.substringAfter("HomeDestinationVoiceDialogLease(").substringBefore(") else null")
        assertTrue(creation.contains("answerWindowStartsAfterPrompt = true"))
        assertEquals(2, Regex("""lease\?\.openAnswerWindow\(\)""").findAll(prompt).count())
        val completed = prompt.substringAfter("onCompleted = {").substringBefore("onFailed = {")
        val failed = prompt.substringAfter("onFailed = {")
        assertTrue(completed.indexOf("state.completeVoicePrompt(attempt)") < completed.indexOf("lease?.openAnswerWindow()"))
        assertTrue(failed.indexOf("state.failVoicePrompt(attempt)") < failed.indexOf("lease?.openAnswerWindow()"))
        assertEquals("no other caller may start the answer time", 2,
            Regex("""openAnswerWindow\(\)""").findAll(source).count())
    }

    private fun lease(state: DestinationSearchVoiceState) =
        HomeDestinationVoiceDialogLease("actor", 1L, 1, state, { now }, answerWindowStartsAfterPrompt = true)

    private fun current(lease: HomeDestinationVoiceDialogLease, state: DestinationSearchVoiceState) =
        lease.currentState("actor", 1L, 1, state.query, state, homeContextAvailable = true, voicePage = true)

    private fun select(state: DestinationSearchVoiceState, index: Int) =
        state.onCommand(DestinationSearchVoiceCommand.SelectCandidate(index))

    private fun page() = DestinationSearchVoiceState(
        "후보",
        (1..3).map { DestinationSearchResult("$it", "후보 $it", RoutePoint(37.0, 127.0), null, null, null, null) },
    )
}
