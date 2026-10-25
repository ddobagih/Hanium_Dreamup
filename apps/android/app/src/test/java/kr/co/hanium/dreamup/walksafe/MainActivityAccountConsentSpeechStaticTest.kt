package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityAccountConsentSpeechStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    private val accountPresenterSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/ui/AccountAccessPresenter.kt",
    ).readText()

    private val accountPanelSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/ui/AccountAccessPanel.kt",
    ).readText()

    private val consentOwnerSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/session/account/AccountConsentSpeechOwner.kt",
    ).readText()

    private val interactionOwnerSource = File(
        "src/main/java/kr/co/hanium/dreamup/walksafe/session/account/AccountAccessInteractionOwner.kt",
    ).readText()

    private fun ownerFunctionSource(name: String, owner: String = consentOwnerSource): String {
        val start = owner.indexOf("    fun $name(")
        check(start >= 0) { "Missing owner function: $name" }
        val next = owner.indexOf("\n    fun ", start + 1)
        return owner.substring(start, if (next < 0) owner.length else next)
    }

    private fun functionSource(name: String): String {
        val start = source.indexOf("    private fun $name(")
        check(start >= 0) { "Missing function: $name" }
        val next = source.indexOf("\n    private fun ", start + 1)
        return source.substring(start, if (next < 0) source.length else next)
    }

    private fun presenterFunctionSource(name: String): String {
        val start = accountPresenterSource.indexOf("    fun $name(")
        check(start >= 0) { "Missing presenter function: $name" }
        val next = accountPresenterSource.indexOf("\n    fun ", start + 1)
        return accountPresenterSource.substring(
            start,
            if (next < 0) accountPresenterSource.length else next,
        )
    }

    private fun assertOrdered(text: String, vararg fragments: String) {
        var cursor = 0
        fragments.forEach { fragment ->
            val index = text.indexOf(fragment, cursor)
            assertTrue("Missing or out-of-order fragment: $fragment", index >= 0)
            cursor = index + fragment.length
        }
    }

    @Test
    fun sameClauseButtonExposesPlayingCompletionAndFailureWithoutGrantingConsent() {
        val buttons = presenterFunctionSource("updateAccountConsentSpeechButtons")
        assertTrue(buttons.contains("val completed = facts.completed[key]"))
        assertTrue(buttons.contains("playing -> \"\$label 듣기 중지\""))
        assertTrue(buttons.contains("completed == true -> \"\$label 다시 듣기\""))
        assertTrue(buttons.contains("completed == false -> \"\$label 듣기 재시도\""))
        assertTrue(buttons.contains("이번 듣기는 아직 완료되지 않았습니다."))
        assertTrue(buttons.contains("이전 약관 듣기 완료."))
        assertTrue(buttons.contains("약관 듣기 미완료."))
        assertTrue(buttons.contains("button.contentDescription = button.text.toString()"))
        assertTrue(buttons.contains("청취만으로 동의되지 않습니다."))
        assertFalse(buttons.contains("isChecked ="))
        assertFalse(buttons.contains("acceptEducationConsent"))
        val wiring = functionSource("createAccountConsentSpeechOwner")
        assertTrue(source.contains("{ createAccountConsentSpeechOwner() }"))
        assertTrue(wiring.contains("AccountConsentSpeechOwner("))
        assertTrue(wiring.contains("accountAccessPresenter.updateAccountConsentSpeechButtons(facts)"))
        assertTrue(functionSource("updateAccountConsentSpeechButtons").contains(
            "accountConsentSpeechOwner.updateAccountConsentSpeechButtons()"))
        val facts = ownerFunctionSource("updateAccountConsentSpeechButtons")
        assertTrue(facts.contains("screen.updateSpeechButtons(AccountAccessPresenter.SpeechButtonFacts(playingKey, completedResults.toMap()))"))
    }

    @Test
    fun playbackRequiresTheActualVisibleClauseAndCurrentSignupConsentStep() {
        val visible = ownerFunctionSource("isAccountConsentClauseVisible")
        assertTrue(visible.contains("runtime.foreground()"))
        assertTrue(visible.contains("flow == FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4"))
        assertTrue(visible.contains("stage == FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT"))
        assertTrue(visible.contains("screen.signupExpanded() && screen.consentStep()"))
        assertTrue(visible.contains("screen.cardShown(key) && screen.listenButtonShown(key)"))
        val wiring = functionSource("createAccountConsentSpeechOwner")
        assertTrue(wiring.contains("override fun foreground() = isActivityForeground"))
        assertTrue(wiring.contains("override fun onboardingSnapshot() = firstRunOnboardingController.snapshot"))
        assertTrue(wiring.contains("override fun signupExpanded() = accountAccessPresenter.accountSignupExpanded"))
        assertTrue(wiring.contains("override fun consentStep() = accountAccessPresenter.accountSignupStep == AccountSignupStep.CONSENT"))
        assertTrue(wiring.contains("accountConsentCards[key]?.isShown == true"))
        assertTrue(wiring.contains("accountConsentListenButtons[key]?.isShown == true"))
        val play = ownerFunctionSource("playAccountConsentClause")
        assertFalse(play.contains("accountConsentStepControls.isShown"))
        assertOrdered(
            play,
            "refreshAccountConsentSpeechContext()",
            "if (playingKey == key)",
            "if (!isAccountConsentClauseVisible(key)",
            "val playbackGeneration = ++generation",
        )
        assertTrue(play.contains("runtime.walkActive()"))
        assertTrue(play.contains("runtime.educationState().educationInFlight"))
        assertTrue(play.contains("runtime.educationState().practiceInFlight != null"))
        assertTrue(wiring.contains("override fun walkActive() = isWalkSessionRuntimeActive()"))
        assertTrue(wiring.contains("override fun educationState() = educationInteractionController.presentationState()"))
    }

    @Test
    fun onlyCurrentActualCompletionCanRecordTheClauseResult() {
        val play = ownerFunctionSource("playAccountConsentClause")
        assertTrue(play.contains("playbackGeneration == generation"))
        assertTrue(play.contains("playingKey == key"))
        val finish = play.substringAfter("fun finish(completed: Boolean) {")
            .substringBefore("fun playChunk(")
        assertOrdered(
            finish,
            "if (!ownsPlayback()) return",
            "!runtime.feedbackCurrent(lifecycleGeneration)",
            "runtime.actorId() != actorId",
            "runtime.onboardingSnapshot().epoch != onboardingEpoch",
            "!isAccountConsentClauseVisible(key)",
            "stopAccountConsentSpeech()",
            "return",
            "completedResults[key] = completed",
            "generation += 1L",
            "playingKey = null",
            "updateAccountConsentSpeechButtons()",
        )
        assertTrue(finish.contains("runtime.walkActive()"))
        assertTrue(finish.contains("runtime.voiceRecognitionActive()"))
        assertEquals(1, Regex("finish\\(true\\)").findAll(play).count())
        val callback = play.substringAfter("onCompleted = {").substringBefore("onFailed =")
        assertOrdered(
            callback,
            "if (!ownsPlayback()) return@runOnUiThread",
            "if (index + 1 < chunks.size) playChunk(index + 1)",
            "runtime.actorId() == actorId",
            "runtime.onboardingSnapshot().epoch == onboardingEpoch",
            "isAccountConsentClauseVisible(key)) finish(true)",
        )
        assertTrue(play.contains("onFailed = { runtime.runOnUiThread { finish(false) } }"))
        assertTrue(play.contains("if (dispatch != NavigationSpeechDispatchResult.ACCEPTED) finish(false)"))
        assertFalse(play.contains("isChecked ="))
        assertFalse(play.contains("acceptEducationConsent"))
        val wiring = functionSource("createAccountConsentSpeechOwner")
        assertTrue(wiring.contains("override fun actorId() = reporterUserId"))
        assertTrue(wiring.contains("override fun feedbackGeneration() = feedbackLifecycleGeneration"))
        assertTrue(wiring.contains("override fun feedbackCurrent(generation: Int) = isFeedbackLifecycleCurrent(generation)"))
        assertTrue(wiring.contains("override fun voiceRecognitionActive() = this@MainActivity.voiceRecognitionActive"))
        assertTrue(wiring.contains("actuator.speakConsentClause(message, onCompleted, onFailed, onCancelled)"))
        assertTrue(wiring.contains("this@MainActivity.runOnUiThread(action)"))
    }

    @Test
    fun stoppingOrRestartingDoesNotManufactureOrEraseAnEarlierCompletion() {
        val stop = ownerFunctionSource("stopAccountConsentSpeech")
        assertOrdered(
            stop,
            "generation += 1L",
            "playingKey = null",
            "runtime.cancelExistingTrainingFeedback()",
            "updateAccountConsentSpeechButtons()",
        )
        assertFalse(stop.contains("completedResults"))
        assertFalse(stop.contains("isChecked ="))
        val beforeCompletion = ownerFunctionSource("playAccountConsentClause")
            .substringBefore("fun finish(")
        assertFalse(beforeCompletion.contains("completedResults["))
        assertFalse(beforeCompletion.contains("completedResults.clear()"))
        assertTrue(functionSource("stopAccountConsentSpeech").contains("accountConsentSpeechOwner.stopAccountConsentSpeech()"))
        assertTrue(functionSource("createAccountConsentSpeechOwner").contains(
            "override fun cancelExistingTrainingFeedback() { feedbackActuator?.cancelPriorityUserTrainingFeedback() }"))
    }

    @Test
    fun actorEpochChangesAndRebuiltCardsDiscardOnlyDisplayResults() {
        val context = ownerFunctionSource("refreshAccountConsentSpeechContext")
        assertOrdered(
            context,
            "runtime.actorId() to runtime.onboardingSnapshot().epoch.toString()",
            "if (resultContext == context) return",
            "stopAccountConsentSpeech()",
            "completedResults.clear()",
            "resultContext = context",
            "updateAccountConsentSpeechButtons()",
        )
        assertFalse(context.contains("isChecked ="))
        assertFalse(context.contains("emailEnrollmentStore"))
        val rebuild = functionSource("buildContentView")
        assertOrdered(rebuild, "accountConsentListenButtons.clear()", "resetAccountConsentSpeechContextAfterBinding()")
        assertTrue(functionSource("resetAccountConsentSpeechContextAfterBinding").contains(
            "accountConsentSpeechOwner.resetCompletedContextAfterBinding()"))
        assertOrdered(ownerFunctionSource("resetCompletedContextAfterBinding"),
            "completedResults.clear()", "resultContext = null")
        assertTrue(accountPanelSource.contains("val consentClauseStops"))
        assertTrue(accountPanelSource.contains("onClick = { actions.onListenConsent(key) }"))
        assertTrue(source.contains("onListenConsent = {"))
        assertTrue(rebuild.contains("this@MainActivity.accountConsentSpeechOwner.playAccountConsentClause(it)"))
        assertOrdered(
            functionSource("buildContentView"),
            "stopAccountConsentSpeech()",
            "resetAccountConsentSpeechContextAfterBinding()",
            "accountConsentListenButtons.putAll(accountPanel.accountConsentListenButtons)",
            "emailEnrollmentPartial?.selections?.let(::applyAccountConsentSelections)",
        )
        assertTrue(functionSource("updateEmailAccountAccessUi").contains(
            "accountAccessInteractionOwner.updateEmailAccountAccessUi(snapshot)"))
        val accessWiring = functionSource("createAccountAccessInteractionOwner")
        assertTrue(accessWiring.contains("refreshConsentSpeechContext = ::refreshAccountConsentSpeechContext"))
        assertTrue(functionSource("refreshAccountConsentSpeechContext").contains(
            "accountConsentSpeechOwner.refreshAccountConsentSpeechContext()"))
        assertTrue(accessWiring.contains("stopConsentSpeechOutsideStage = { accountConsentSpeechOwner.stopOutsideAccountStage(it) }"))
        assertOrdered(ownerFunctionSource("updateEmailAccountAccessUi", interactionOwnerSource),
            "refreshConsentSpeechContext()", "stopConsentSpeechOutsideStage(snapshot)")
        assertOrdered(ownerFunctionSource("stopOutsideAccountStage"),
            "snapshot.flow != FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4", "stopAccountConsentSpeech()")
    }
}
