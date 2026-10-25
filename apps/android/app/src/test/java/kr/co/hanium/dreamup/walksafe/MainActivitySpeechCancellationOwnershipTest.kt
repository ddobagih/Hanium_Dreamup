package kr.co.hanium.dreamup.walksafe

import java.io.File
import kr.co.hanium.dreamup.walksafe.feedback.UtteranceCallbackRegistry
import kr.co.hanium.dreamup.walksafe.session.PriorityUserAgeBand
import kr.co.hanium.dreamup.walksafe.session.PriorityUserOnboardingPolicy
import kr.co.hanium.dreamup.walksafe.session.PriorityUserPractice
import org.junit.Assert.*
import org.junit.Test

/** Actual Main state cleanup methods, without launching Activity, native audio or microphone. */
class MainActivitySpeechCancellationOwnershipTest {
    @Test
    fun ownedCommandCancellationDropsItsWaitWithoutCallingFailureOrCompletion() {
        val main = MainActivity()
        val registry = field("commandSpeechResponseCallbacks").get(main) as UtteranceCallbackRegistry
        var callbacks = 0
        registry.register("command-response-7", { callbacks++ }, { callbacks++ })
        field("commandSpeechResponseGeneration").setLong(main, 7L)
        field("voiceCommandPromptPending").setBoolean(main, true)
        field("voiceCommandPromptReadyGeneration").set(main, 4)

        assertTrue(main.retireCommandSpeechResponseIfOwned("command-response-7", 7L))
        assertEquals(0, registry.size())
        assertFalse(field("voiceCommandPromptPending").getBoolean(main))
        assertNull(field("voiceCommandPromptReadyGeneration").get(main))
        assertEquals(0, callbacks)
        assertFalse(main.retireCommandSpeechResponseIfOwned("command-response-7", 7L))
        assertEquals(8L, field("commandSpeechResponseGeneration").getLong(main))
    }

    @Test
    fun lateNativeCancellationOfAnAlreadyRetiredWaitCannotInvalidateDeferredInputGeneration() {
        val main = MainActivity()
        val registry = field("commandSpeechResponseCallbacks").get(main) as UtteranceCallbackRegistry
        registry.register("command-response-7", {}, {})
        field("commandSpeechResponseGeneration").setLong(main, 7L)
        registry.cancel(listOf("command-response-7"))
        assertFalse(main.retireCommandSpeechResponseIfOwned("command-response-7", 7L, requireRegistered = true))
        assertEquals(7L, field("commandSpeechResponseGeneration").getLong(main))
    }

    @Test
    fun oldCommandCancellationCannotReleaseTheReplacementPrompt() {
        val main = MainActivity()
        val registry = field("commandSpeechResponseCallbacks").get(main) as UtteranceCallbackRegistry
        registry.register("command-response-7", {}, {})
        registry.register("command-response-8", {}, {})
        field("commandSpeechResponseGeneration").setLong(main, 8L)
        field("voiceCommandPromptPending").setBoolean(main, true)
        field("voiceCommandPromptReadyGeneration").set(main, 5)

        assertFalse(main.retireCommandSpeechResponseIfOwned("command-response-7", 7L))
        assertEquals(1, registry.size())
        assertTrue(field("voiceCommandPromptPending").getBoolean(main))
        assertEquals(5, field("voiceCommandPromptReadyGeneration").get(main))
        assertEquals(8L, field("commandSpeechResponseGeneration").getLong(main))
    }

    @Test
    fun educationCancellationReleasesUsagePlaybackAndKeepsCompletedEducation() {
        val policy = safetyConsentPolicy()
        val token = policy.beginAppUsageEducationPlayback()!!
        val before = policy.snapshot()
        val main = trainingOwner(policy)

        assertTrue(main.clearPriorityUserTrainingStateIfOwned(4L, "owner", policy, null))
        assertEquals(before, policy.finishAppUsageEducationPlayback(token, completed = true))
        assertEquals(before, policy.snapshot())
        assertFalse(field("priorityUserEducationInFlight").getBoolean(main))
        assertNotNull("A cancelled playback must not block an explicit retry", policy.beginAppUsageEducationPlayback())
        assertFalse(main.clearPriorityUserTrainingStateIfOwned(4L, "owner", policy, null))
    }

    @Test
    fun hapticOnlyPracticeCancellationReleasesTheAttemptWithoutCompletingIt() {
        val policy = safetyConsentPolicy().apply { confirmSafePracticePlace() }
        val token = policy.beginPractice(PriorityUserPractice.HAZARD_ALERT)!!
        val main = trainingOwner(policy)
        field("priorityUserPracticeInFlight").set(main, PriorityUserPractice.HAZARD_ALERT)
        field("priorityUserPracticeAttemptToken").set(main, token)

        assertTrue(main.clearPriorityUserTrainingStateIfOwned(4L, "owner", policy, token))
        assertNull(field("priorityUserPracticeInFlight").get(main))
        assertNull(field("priorityUserPracticeAttemptToken").get(main))
        assertTrue(policy.snapshot().completedPractices.isEmpty())
        assertNotNull(policy.beginPractice(PriorityUserPractice.HAZARD_ALERT))
    }

    @Test
    fun staleTrainingCancellationCannotClearANewGenerationActorOrPolicy() {
        val policy = safetyConsentPolicy()
        val token = policy.beginAppUsageEducationPlayback()!!
        val main = trainingOwner(policy)

        assertFalse(main.clearPriorityUserTrainingStateIfOwned(3L, "owner", policy, null))
        assertFalse(main.clearPriorityUserTrainingStateIfOwned(4L, "other", policy, null))
        assertFalse(main.clearPriorityUserTrainingStateIfOwned(4L, "owner", safetyConsentPolicy(), null))
        assertTrue(field("priorityUserEducationInFlight").getBoolean(main))
        assertTrue(policy.finishAppUsageEducationPlayback(token, completed = true).appUsageReviewed)
    }

    @Test
    fun commandAndDestinationCancellationNeverOpenAnswerWindowOrRestartInput() {
        val source = source()
        val command = section(source, "private fun speakCommandResponse(")
        val cancel = command.substringAfter("val cancelled: () -> Unit = {")
            .substringBefore("val actuator = ensureFeedbackActuator()")
        assertTrue(cancel.contains("retireCommandSpeechResponseIfOwned(responseId, responseGeneration, requireRegistered = true)"))
        assertTrue(cancel.contains("onCancelled?.invoke()"))
        assertFalse(cancel.contains("onFailed?.invoke()"))
        assertFalse(cancel.contains("startVoiceCommandRecognition("))
        assertFalse(cancel.contains("refreshForegroundHomeWakeListening()"))
        assertTrue(command.contains("cancelled = { if (partTerminal.compareAndSet(false, true)) cancelled() }"))
        assertTrue(section(source, "internal fun dispatchCommandSpeechResponse(")
            .contains("onCancelled = cancelled"))
        val destination = section(source, "private fun speakHomeDestinationVoicePrompt(")
            .substringAfter("onCancelled = {")
        assertTrue(destination.contains("state.failVoicePrompt(attempt)"))
        assertTrue(destination.contains("lease?.close()"))
        assertTrue(destination.contains("homeDestinationVoiceDialogLease === lease"))
        assertFalse(destination.contains("openAnswerWindow()"))
        assertFalse(destination.contains("startVoiceCommandRecognition("))
    }

    @Test
    fun educationConsentAndRouteConnectExplicitCancellationWithoutFallback() {
        val source = source()
        for (signature in listOf("private fun playNativeSafetyEducation(", "private fun reviewPriorityUserSafetyEducation(", "private fun performPriorityUserPractice(")) {
            val education = section(source, signature)
            assertTrue(education.contains("onCancelled = {"))
            assertTrue(education.contains("clearPriorityUserTrainingDeliveryIfOwned("))
        }
        val educationOwner = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/workflow/EducationInteractionController.kt").readText()
        assertEquals(3, Regex("onCancelled = \\{\\s*runtime\\.runOnUiThread \\{ clearPriorityUserTrainingDeliveryIfOwned").findAll(educationOwner).count())
        assertTrue(MainActivityRefactorSource.composition(source, "educationInteractionController")
            .contains("override fun runOnUiThread(action: () -> Unit)"))
        val consent = section(source, "private fun playAccountConsentClause(")
        assertTrue(consent.contains("onCancelled = { runtime.runOnUiThread { finish(false) } }"))
        val content = MainActivityRefactorSource.member(source, "buildContentView")
        assertTrue(content.contains("val accountPanel = AccountAccessPanel("))
        val accountPanel = content.substringAfter("val accountPanel = AccountAccessPanel(")
            .substringBefore("onConsentContinue =")
        assertTrue(accountPanel.contains("actions = AccountAccessPanel.Actions("))
        val listenConsent = ReportStaticSourceInspector.blockAfter(accountPanel, "onListenConsent =")
        assertTrue(listenConsent.contains("this@MainActivity.accountConsentSpeechOwner.playAccountConsentClause(it)"))
        val accountPanelOwner = File("src/main/java/kr/co/hanium/dreamup/walksafe/ui/AccountAccessPanel.kt").readText()
        assertTrue(accountPanelOwner.contains("onClick = { actions.onListenConsent(key) }"))
        val consentPorts = MainActivityRefactorSource.composition(source, "accountConsentSpeechOwner")
        assertTrue(consentPorts.contains("override fun runOnUiThread(action: () -> Unit) { this@MainActivity.runOnUiThread(action) }"))
        assertTrue(consentPorts.contains("actuator.speakConsentClause(message, onCompleted, onFailed, onCancelled)"))
        assertFalse(consent.substringAfter("onCancelled = {").substringBefore("},").contains("finish(true)"))
        val route = section(source, "private fun dispatchNavigationSpeech(")
        assertTrue(route.contains("onCancelled"))
        assertTrue(route.contains("cancelled.set(true)"))
        val stop = section(source, "private fun cancelWalkSessionOutputsInternal(")
        assertTrue(stop.indexOf("feedback.cancelPriorityTraining()") in 0 until stop.indexOf("feedback.closeActuator()"))
        val cleanupPorts = MainActivityRefactorSource.composition(source, "walkSessionCleanupController")
        assertTrue(cleanupPorts.contains("override fun cancelPriorityTraining() = cancelPendingPriorityUserTrainingFeedback()"))
        assertTrue(cleanupPorts.contains("override fun closeActuator() = speechOutputController.closeActuator()"))
        val pause = source.substringAfter("internal fun pauseWalkSafeRuntime()").substringBefore("private fun invalidateFrameStateForPause()")
        assertTrue(pause.indexOf("cancelPendingPriorityUserTrainingFeedback()") < pause.indexOf("speechOutputController.closeActuator()"))
    }

    private fun trainingOwner(policy: PriorityUserOnboardingPolicy) = MainActivity().apply {
        field("priorityUserTrainingGeneration").setLong(this, 4L)
        field("priorityUserOnboardingActorId").set(this, "owner")
        field("priorityUserOnboardingPolicy").set(this, policy)
        field("priorityUserEducationInFlight").setBoolean(this, true)
    }

    private fun safetyConsentPolicy() = PriorityUserOnboardingPolicy().apply {
        selectAgeBand(PriorityUserAgeBand.ADULT_18_PLUS)
        reviewSafetyEducation(voicePlaybackCompleted = true)
        reviewPracticeNecessity(voicePlaybackCompleted = true)
        acceptEducationConsent()
    }

    private fun field(name: String) = MainActivityControllerTestFixture.field(name).apply { isAccessible = true }
    private fun source() = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
    private fun section(source: String, signature: String): String =
        MainActivityRefactorSource.function(source, signature)
}
