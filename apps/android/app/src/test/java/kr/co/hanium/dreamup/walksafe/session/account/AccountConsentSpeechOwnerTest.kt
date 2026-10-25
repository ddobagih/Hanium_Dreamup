package kr.co.hanium.dreamup.walksafe.session.account

import java.util.ArrayDeque
import kr.co.hanium.dreamup.walksafe.feedback.NavigationSpeechDispatchResult
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingPolicy
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingSnapshot
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingStage
import kr.co.hanium.dreamup.walksafe.session.FirstRunOpaqueActorBinding
import kr.co.hanium.dreamup.walksafe.session.FirstRunReceiptHash
import kr.co.hanium.dreamup.walksafe.session.PriorityUserPractice
import kr.co.hanium.dreamup.walksafe.ui.AccountAccessPresenter
import kr.co.hanium.dreamup.walksafe.ui.workflow.EducationInteractionController
import org.junit.Assert.*
import org.junit.Test

class AccountConsentSpeechOwnerTest {
    @Test fun constructionHasNoSdkProviderOrPlaybackEffects() { val f = Fixture(); assertTrue(f.events.isEmpty()); assertTrue(f.dispatches.isEmpty()) }
    @Test fun acceptedDispatchDoesNotRecordListeningDoneUntilTheActualCompletion() {
        val f = Fixture(); f.owner.playAccountConsentClause(KEY)
        assertEquals(KEY, f.buttons.playingKey); assertTrue(f.buttons.completed.isEmpty()); assertTrue(f.statuses.isEmpty())
        f.dispatches.single().done(); assertTrue(f.statuses.isEmpty()); f.runUi()
        assertNull(f.buttons.playingKey); assertEquals(true, f.buttons.completed[KEY]); assertEquals("약관 듣기 완료", f.statuses.last().first)
        assertTrue(f.statuses.last().second.contains("청취는 동의가 아닙니다"))
    }
    @Test fun chunksRequireAllActualDoneCallbacksAndNeverSyntheticDispatchSuccess() {
        val f = Fixture(); f.text = "abcdefghij"; f.maxLength = 4; f.owner.playAccountConsentClause(KEY)
        assertEquals(listOf("abcd"), f.dispatches.map { it.text }); f.dispatches[0].done(); f.runUi()
        assertEquals(listOf("abcd", "efgh"), f.dispatches.map { it.text }); assertTrue(f.buttons.completed.isEmpty())
        f.dispatches[1].done(); f.runUi(); assertEquals("ij", f.dispatches[2].text); assertTrue(f.buttons.completed.isEmpty())
        f.dispatches[2].done(); f.runUi(); assertEquals(true, f.buttons.completed[KEY])
    }
    @Test fun pressingThePlayingClauseStopsWithoutGrantingOrRecordingCompletion() {
        val f = Fixture(); f.owner.playAccountConsentClause(KEY); val stale = f.dispatches.single(); f.owner.playAccountConsentClause(KEY)
        assertNull(f.buttons.playingKey); assertTrue(f.buttons.completed.isEmpty()); assertEquals("약관 듣기 중지", f.statuses.last().first)
        stale.done(); f.runUi(); assertTrue(f.buttons.completed.isEmpty()); assertEquals(1, f.cancelCount)
    }
    @Test fun actorEpochFeedbackGenerationForegroundAndVisibilityChangesRejectLateDone() {
        val mutations: List<(Fixture) -> Unit> = listOf({ it.actor = OTHER_ACTOR }, { it.snapshot = FirstRunOnboardingPolicy.initialEmailAccount(8L) },
            { it.feedbackGeneration++ }, { it.foreground = false }, { it.cardVisible = false }, { it.listenVisible = false }, { it.expanded = false }, { it.consentStep = false })
        mutations.forEach { mutate -> val f = Fixture(); f.owner.playAccountConsentClause(KEY); val done = f.dispatches.single(); done.done(); mutate(f); f.runUi()
            assertTrue(f.buttons.completed.isEmpty()); assertNull(f.buttons.playingKey); assertTrue(f.statuses.isEmpty()); assertEquals(1, f.cancelCount) }
    }
    @Test fun walkingOrVoiceStartedBeforeDoneCannotMarkClauseComplete() {
        listOf<(Fixture) -> Unit>({ it.walking = true }, { it.voice = true }).forEach { mutate ->
            val f = Fixture(); f.owner.playAccountConsentClause(KEY); f.dispatches.single().done(); mutate(f); f.runUi()
            assertTrue(f.buttons.completed.isEmpty()); assertNull(f.buttons.playingKey); assertTrue(f.statuses.isEmpty())
        }
    }
    @Test fun changingStageWhileRefreshingAccessStopsThePlayingClause() {
        val f = Fixture(); f.owner.playAccountConsentClause(KEY); val snapshot = FirstRunOnboardingPolicy.recordVerifiedEmailLogin(f.snapshot,
            FirstRunOpaqueActorBinding.fromProvider(ACTOR), FirstRunReceiptHash.fromSha256Hex("a".repeat(64))).current
        f.owner.stopOutsideAccountStage(snapshot); assertNull(f.buttons.playingKey); assertEquals(1, f.cancelCount)
    }
    @Test fun unsupportedUiStageAndRuntimeAdmissionNeverAcquireAnActuator() {
        val mutations: List<(Fixture) -> Unit> = listOf({ it.foreground = false }, { it.expanded = false }, { it.consentStep = false },
            { it.cardVisible = false }, { it.listenVisible = false }, { it.walking = true }, { it.education = true }, { it.practice = PriorityUserPractice.entries.first() })
        mutations.forEach { mutate -> val f = Fixture(); mutate(f); f.owner.playAccountConsentClause(KEY); assertTrue(f.dispatches.isEmpty()); assertFalse(f.events.contains("ensure-channel")) }
    }
    @Test fun emptyClauseDoesNotCancelVoiceOrAcquireActuator() {
        val f = Fixture(); f.text = "  "; f.voice = true; f.owner.playAccountConsentClause(KEY)
        assertTrue(f.dispatches.isEmpty()); assertTrue(f.voice); assertFalse(f.events.contains("cancel-voice")); assertFalse(f.events.contains("ensure-channel"))
    }
    @Test fun currentVoiceIsCancelledBeforePlaybackLifetimeIsCapturedAndChannelAcquired() {
        val f = Fixture(); f.voice = true; f.owner.playAccountConsentClause(KEY)
        assertFalse(f.voice); assertTrue(f.events.indexOf("cancel-voice") < f.events.indexOf("feedback-generation-query"))
        assertTrue(f.events.indexOf("max-length-query") < f.events.indexOf("ensure-channel")); assertTrue(f.events.indexOf("ensure-channel") < f.events.indexOf("new-scheduler"))
    }
    @Test fun initializingSpeechRetriesTheSameChunkAndTimeoutRecordsIncompleteOnlyWhileCurrent() {
        val f = Fixture(); f.initializing = true; f.owner.playAccountConsentClause(KEY)
        assertTrue(f.dispatches.isEmpty()); assertEquals(listOf(100L), f.delays)
        f.now = 1099L; f.runDelayed(); assertTrue(f.dispatches.isEmpty()); assertEquals(1, f.scheduled.size)
        f.now = 1100L; f.runDelayed(); assertEquals(false, f.buttons.completed[KEY]); assertEquals("약관 듣기 미완료", f.statuses.last().first)
    }
    @Test fun readinessRetryAfterActorChangeStopsWithoutWritingResultOrDispatchingSpeech() {
        val f = Fixture(); f.initializing = true; f.owner.playAccountConsentClause(KEY); f.actor = OTHER_ACTOR; f.runDelayed()
        assertTrue(f.dispatches.isEmpty()); assertTrue(f.buttons.completed.isEmpty()); assertNull(f.buttons.playingKey)
    }
    @Test fun aReadyRetryKeepsOriginalChunkAndUsesOriginalInitializationDeadline() {
        val f = Fixture(); f.initializing = true; f.owner.playAccountConsentClause(KEY); f.initializing = false; f.now = 1005L; f.runDelayed()
        assertEquals("clause", f.dispatches.single().text); assertTrue(f.buttons.completed.isEmpty()); assertEquals(1, f.events.count { it == "ensure-channel" })
    }
    @Test fun externalFallbackBlocksTtsAndRecordsIncompleteWithoutConsentMutation() {
        val f = Fixture(); f.externalFallback = true; f.owner.playAccountConsentClause(KEY)
        assertTrue(f.dispatches.isEmpty()); assertEquals(false, f.buttons.completed[KEY]); assertTrue(f.statuses.last().second.contains("동의는 변경되지 않았습니다"))
    }
    @Test fun rejectedDispatchFailedAndCancelledCallbacksNeverCountAsDone() {
        val f = Fixture(); f.accepted = false; f.owner.playAccountConsentClause(KEY); assertEquals(false, f.buttons.completed[KEY])
        listOf<(Dispatch) -> Unit>({ it.failed() }, { it.cancelled() }).forEach { terminal -> val g = Fixture(); g.owner.playAccountConsentClause(KEY); terminal(g.dispatches.single()); g.runUi(); assertEquals(false, g.buttons.completed[KEY]) }
    }
    @Test fun switchingClauseFencesOldTerminalCallbacksAndRetainsNewPlayback() {
        val f = Fixture(); f.owner.playAccountConsentClause(KEY); val old = f.dispatches.single(); f.owner.playAccountConsentClause("privacy")
        old.failed(); old.done(); old.cancelled(); f.runUi(); assertEquals("privacy", f.buttons.playingKey); assertTrue(f.buttons.completed.isEmpty())
        f.dispatches.last().done(); f.runUi(); assertEquals(true, f.buttons.completed["privacy"]); assertFalse(f.buttons.completed.containsKey(KEY))
    }
    @Test fun replayStopKeepsPreviouslyCompletedListeningInSameContext() {
        val f = Fixture(); f.owner.playAccountConsentClause(KEY); f.dispatches.single().done(); f.runUi()
        f.owner.playAccountConsentClause(KEY); f.owner.stopAccountConsentSpeech(); assertEquals(true, f.buttons.completed[KEY]); assertNull(f.buttons.playingKey)
    }
    @Test fun contextChangeClearsOldEvidenceWhileSameContextRefreshIsNoOp() {
        val f = Fixture(); f.owner.playAccountConsentClause(KEY); f.dispatches.single().done(); f.runUi()
        val updates = f.buttonUpdates; f.owner.refreshAccountConsentSpeechContext(); assertEquals(updates, f.buttonUpdates)
        f.actor = OTHER_ACTOR; f.owner.refreshAccountConsentSpeechContext(); assertTrue(f.buttons.completed.isEmpty())
    }
    @Test fun bindingResetClearsOnlyContextEvidenceAtTheOriginalPostStopPoint() {
        val f = Fixture(); f.owner.playAccountConsentClause(KEY); f.dispatches.single().done(); f.runUi(); val updates = f.buttonUpdates
        f.owner.stopAccountConsentSpeech(); f.owner.resetCompletedContextAfterBinding(); assertEquals(updates, f.buttonUpdates)
        f.owner.updateAccountConsentSpeechButtons(); assertTrue(f.buttons.completed.isEmpty()); f.owner.refreshAccountConsentSpeechContext(); assertEquals(updates + 2, f.buttonUpdates)
    }
    @Test fun duplicateDoneOrLateFailedAfterCompletionCannotOverwriteActualDone() {
        val f = Fixture(); f.owner.playAccountConsentClause(KEY); val d = f.dispatches.single(); d.done(); f.runUi(); d.done(); d.failed(); d.cancelled(); f.runUi()
        assertEquals(true, f.buttons.completed[KEY]); assertEquals(1, f.statuses.size)
    }
    private data class Dispatch(val text: String, val done: () -> Unit, val failed: () -> Unit, val cancelled: () -> Unit)
    private class Fixture : AccountConsentSpeechOwner.Runtime, AccountConsentSpeechOwner.Screen, AccountConsentSpeechOwner.FeedbackChannel {
        val events = mutableListOf<String>(); val dispatches = mutableListOf<Dispatch>(); val statuses = mutableListOf<Pair<String,String>>()
        val ui = ArrayDeque<() -> Unit>(); val scheduled = ArrayDeque<() -> Unit>(); val delays = mutableListOf<Long>()
        var actor: String? = ACTOR; var snapshot = FirstRunOnboardingPolicy.initialEmailAccount(7L)
        var foreground = true; var walking = false; var education = false; var practice: PriorityUserPractice? = null
        var voice = false; var feedbackGeneration = 9; var now = 1000L; var initializing = false; var externalFallback = false; var accepted = true
        var text: String? = "clause"; var maxLength = 100; var expanded = true; var consentStep = true; var cardVisible = true; var listenVisible = true
        var buttons = AccountAccessPresenter.SpeechButtonFacts(null, emptyMap()); var buttonUpdates = 0; var cancelCount = 0
        val owner = AccountConsentSpeechOwner(this, this, 100L)
        override fun foreground() = foreground
        override fun actorId() = actor
        override fun onboardingSnapshot() = snapshot
        override fun walkActive() = walking
        override fun educationState() = EducationInteractionController.PresentationState(education, false, practice)
        override fun voiceRecognitionActive() = voice
        override fun cancelVoiceRecognition() { events.add("cancel-voice"); voice = false }
        override fun feedbackGeneration(): Int { events.add("feedback-generation-query"); return feedbackGeneration }
        override fun feedbackCurrent(generation: Int) = foreground && generation == feedbackGeneration
        override fun maxSpeechInputLength(): Int { events.add("max-length-query"); return maxLength }
        override fun ensureFeedbackChannel(): AccountConsentSpeechOwner.FeedbackChannel { events.add("ensure-channel"); return this }
        override fun newScheduler(): AccountConsentSpeechOwner.Scheduler { events.add("new-scheduler"); return object : AccountConsentSpeechOwner.Scheduler { override fun postDelayed(action: () -> Unit, delayMs: Long) { scheduled.add(action); delays.add(delayMs) } } }
        override fun elapsedRealtimeMs() = now
        override fun externalFallbackSpeechActive() = externalFallback
        override fun cancelExistingTrainingFeedback() { cancelCount++ }
        override fun runOnUiThread(action: () -> Unit) { ui.add(action) }
        override fun signupExpanded() = expanded
        override fun consentStep() = consentStep
        override fun cardShown(key: String) = cardVisible
        override fun listenButtonShown(key: String) = listenVisible
        override fun clauseText(key: String) = text
        override fun updateSpeechButtons(facts: AccountAccessPresenter.SpeechButtonFacts) { buttons = facts; buttonUpdates++ }
        override fun updateStatus(title: String, message: String) { statuses.add(title to message) }
        override fun isSpeechInitializing() = initializing
        override fun speakConsentClause(message: String, onCompleted: () -> Unit, onFailed: () -> Unit, onCancelled: () -> Unit): NavigationSpeechDispatchResult {
            dispatches.add(Dispatch(message, onCompleted, onFailed, onCancelled)); return if (accepted) NavigationSpeechDispatchResult.ACCEPTED else NavigationSpeechDispatchResult.UNAVAILABLE
        }
        fun runUi() { while (!ui.isEmpty()) ui.removeFirst()() }
        fun runDelayed() { scheduled.removeFirst()() }
    }
    companion object { private const val KEY = "terms_of_service"; private const val ACTOR = "actor_abcdef0123456789abcdef0123456789"; private const val OTHER_ACTOR = "actor_abcdef0123456789abcdef0123456788" }
}
