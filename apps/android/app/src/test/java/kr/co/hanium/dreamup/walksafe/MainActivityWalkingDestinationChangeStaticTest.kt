package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * S06-40 (FP-026-DETAIL-01, DEC-VOICE-CONFIRMATION): a number spoken while walking selects the place
 * but does not request a route; only a separate "길안내 시작" for that selection does.
 */
class MainActivityWalkingDestinationChangeStaticTest {
    private val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private fun block(start: String, end: String) = source.substringAfter(start).substringBefore(end)
    private val selection = block(
        "private fun selectVoiceDestinationCandidate(oneBasedIndex: Int) {",
        "private fun walkingDestinationChangeOwnerOrNull()",
    )
    private val start = block(
        "private fun startPendingWalkingDestinationChange(): Boolean {",
        "private fun explainRouteCommandUnavailable(",
    )

    @Test fun walkingSelectionOnlyOffersTheSelectionAndKeepsTheRoute() {
        val walking = selection.substringAfterLast("openNativeDestinationConfirmation(selected)")
        assertTrue(walking.contains("walkingDestinationChange.offer(selected, owner, SystemClock.elapsedRealtime())"))
        assertFalse(walking.contains("onDestinationSelected("))
        assertFalse(walking.contains("requestRoute("))
        assertFalse(walking.contains("resetRouteState("))
        assertTrue(walking.contains("지금 길안내는 그대로 계속됩니다. 이 장소로 경로를 바꾸려면 길안내 시작이라고 말해 주세요."))
        assertTrue(walking.contains("이 장소로 길안내를 시작하려면 길안내 시작이라고 말해 주세요."))
    }

    @Test fun startNavigationConsumesTheWalkingSelectionFirst() {
        val dispatch = block("AndroidVoiceAction.StartNavigation -> {", "AndroidVoiceAction.SpeakCurrentGuidance -> {")
        assertTrue(
            dispatch.indexOf("startPendingWalkingDestinationChange()") in
                0 until dispatch.indexOf("startNativeDestinationGuidance()"),
        )
        val owner = block("private fun walkingDestinationChangeOwnerOrNull()", "private fun startPendingWalkingDestinationChange()")
        assertTrue(owner.contains("reporterUserId"))
        assertTrue(owner.contains("GatewaySessionProcessCoordinator.snapshot().generation"))
        assertTrue(owner.contains("walkSessionLifecycle.snapshot().epoch"))
        val consume = start.indexOf("walkingDestinationChange.consume(owner, SystemClock.elapsedRealtime())")
        assertTrue(consume >= 0)
        assertTrue(consume < start.indexOf("if (!onDestinationSelected(selected)) {"))
        // A stale or foreign selection says the route is unchanged and never falls through to a start.
        val expired = start.substringAfter("if (selected == null) {").substringBefore("}")
        assertTrue(expired.contains("지금 길안내는 그대로입니다."))
        assertTrue(expired.contains("return true"))
        assertFalse(expired.contains("onDestinationSelected("))
        // After the walk the home confirmation screen owns "안내 시작" again.
        assertTrue(start.substringAfter("if (nativeHomeFeatureContextAvailable()) {").substringBefore("}")
            .contains("walkingDestinationChange.clear()"))
    }

    /** S06-40 x S05-11 (FP-026 design_rules[1]): the waiting confirmation outranks route guidance. */
    @Test fun repeatRequestsWhileASelectionWaitsRepeatTheConfirmationPrompt() {
        val guidance = block("AndroidVoiceAction.SpeakCurrentGuidance -> {", "AndroidVoiceAction.SpeakVoiceHelp -> {")
        val pendingAt = guidance.indexOf("else if (repeatPendingWalkingDestinationChange()) Unit")
        assertTrue(pendingAt >= 0)
        assertTrue(pendingAt < guidance.indexOf("speakNextNavigationInstruction()"))
        val repeat = block("private fun repeatVoiceDestinationCandidates() {", "private fun activeVoiceCandidateDialogOrNull()")
        val repeatPendingAt = repeat.indexOf("if (repeatPendingWalkingDestinationChange()) return")
        assertTrue(repeatPendingAt >= 0)
        assertTrue(repeatPendingAt < repeat.indexOf("voiceStateForShownDestinationResultsOrNull()"))
        assertTrue(repeatPendingAt < repeat.indexOf("speakNextNavigationInstruction()"))
        val prompt = block(
            "private fun repeatPendingWalkingDestinationChange(): Boolean {",
            "private fun explainRouteCommandUnavailable(",
        )
        // Read-only: the selection stays for "길안내 시작"; nothing is requested or consumed here.
        assertTrue(prompt.contains("walkingDestinationChange.peek(owner, SystemClock.elapsedRealtime())"))
        assertFalse(prompt.contains(".consume("))
        assertFalse(prompt.contains("onDestinationSelected("))
        assertFalse(prompt.contains("requestRoute("))
        assertTrue(prompt.contains("if (!walkingDestinationChange.hasPending() || nativeHomeFeatureContextAvailable()) return false"))
        assertTrue(prompt.contains("이 장소로 경로를 바꾸려면 길안내 시작이라고 말해 주세요."))
    }

    @Test fun aNewSearchOrCancelDropsTheWaitingSelection() {
        val search = block("private fun startVoiceDestinationSearch(query: String) {", "private fun repeatVoiceDestinationCandidates()")
        assertTrue(search.contains("walkingDestinationChange.clear()"))
        val cancel = block("private fun cancelDestinationFromVoice() {", "private fun selectVoiceDestinationCandidate(")
        assertTrue(cancel.contains("walkingDestinationChange.clear()"))
    }
}
