package kr.co.hanium.dreamup.walksafe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainActivityAccountConsentSpeechStaticTest {
    private val source =
        File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()

    private fun functionSource(name: String): String {
        val start = source.indexOf("    private fun $name(")
        check(start >= 0) { "Missing function: $name" }
        val next = source.indexOf("\n    private fun ", start + 1)
        return source.substring(start, if (next < 0) source.length else next)
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
        val buttons = functionSource("updateAccountConsentSpeechButtons")
        assertTrue(buttons.contains("val completed = accountConsentSpeechResults[key]"))
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
    }

    @Test
    fun playbackRequiresTheActualVisibleClauseAndCurrentSignupConsentStep() {
        val visible = functionSource("isAccountConsentClauseVisible")
        assertTrue(visible.contains("isActivityForeground"))
        assertTrue(visible.contains("flow == FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4"))
        assertTrue(visible.contains("stage == FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT"))
        assertTrue(visible.contains("accountSignupExpanded && accountSignupStep == AccountSignupStep.CONSENT"))
        assertTrue(visible.contains("accountConsentCards[key]?.isShown == true"))
        assertTrue(visible.contains("accountConsentListenButtons[key]?.isShown == true"))
        val play = functionSource("playAccountConsentClause")
        assertFalse(play.contains("accountConsentStepControls.isShown"))
        assertOrdered(
            play,
            "refreshAccountConsentSpeechContext()",
            "if (accountConsentSpeechKey == key)",
            "if (!isAccountConsentClauseVisible(key)",
            "val generation = ++accountConsentSpeechGeneration",
        )
        assertTrue(play.contains("isWalkSessionRuntimeActive()"))
        assertTrue(play.contains("priorityUserEducationInFlight"))
        assertTrue(play.contains("priorityUserPracticeInFlight != null"))
    }

    @Test
    fun onlyCurrentActualCompletionCanRecordTheClauseResult() {
        val play = functionSource("playAccountConsentClause")
        assertTrue(play.contains("generation == accountConsentSpeechGeneration"))
        assertTrue(play.contains("accountConsentSpeechKey == key"))
        val finish = play.substringAfter("fun finish(completed: Boolean) {")
            .substringBefore("fun playChunk(")
        assertOrdered(
            finish,
            "if (!ownsPlayback()) return",
            "!isFeedbackLifecycleCurrent(lifecycleGeneration)",
            "reporterUserId != actorId",
            "firstRunOnboardingSnapshot.epoch != onboardingEpoch",
            "!isAccountConsentClauseVisible(key)",
            "stopAccountConsentSpeech()",
            "return",
            "accountConsentSpeechResults[key] = completed",
            "accountConsentSpeechGeneration += 1L",
            "accountConsentSpeechKey = null",
            "updateAccountConsentSpeechButtons()",
        )
        assertTrue(finish.contains("isWalkSessionRuntimeActive()"))
        assertTrue(finish.contains("voiceRecognitionActive"))
        assertEquals(1, Regex("finish\\(true\\)").findAll(play).count())
        val callback = play.substringAfter("onCompleted = {").substringBefore("onFailed =")
        assertOrdered(
            callback,
            "if (!ownsPlayback()) return@runOnUiThread",
            "if (index + 1 < chunks.size) playChunk(index + 1)",
            "reporterUserId == actorId",
            "firstRunOnboardingSnapshot.epoch == onboardingEpoch",
            "isAccountConsentClauseVisible(key)) finish(true)",
        )
        assertTrue(play.contains("onFailed = { runOnUiThread { finish(false) } }"))
        assertTrue(play.contains("if (dispatch != NavigationSpeechDispatchResult.ACCEPTED) finish(false)"))
        assertFalse(play.contains("isChecked ="))
        assertFalse(play.contains("acceptEducationConsent"))
    }

    @Test
    fun stoppingOrRestartingDoesNotManufactureOrEraseAnEarlierCompletion() {
        val stop = functionSource("stopAccountConsentSpeech")
        assertOrdered(
            stop,
            "accountConsentSpeechGeneration += 1L",
            "accountConsentSpeechKey = null",
            "cancelPriorityUserTrainingFeedback()",
            "updateAccountConsentSpeechButtons()",
        )
        assertFalse(stop.contains("accountConsentSpeechResults"))
        assertFalse(stop.contains("isChecked ="))
        val beforeCompletion = functionSource("playAccountConsentClause")
            .substringBefore("fun finish(")
        assertFalse(beforeCompletion.contains("accountConsentSpeechResults["))
        assertFalse(beforeCompletion.contains("accountConsentSpeechResults.clear()"))
    }

    @Test
    fun actorEpochChangesAndRebuiltCardsDiscardOnlyDisplayResults() {
        val context = functionSource("refreshAccountConsentSpeechContext")
        assertOrdered(
            context,
            "reporterUserId to firstRunOnboardingSnapshot.epoch.toString()",
            "if (accountConsentSpeechResultContext == context) return",
            "stopAccountConsentSpeech()",
            "accountConsentSpeechResults.clear()",
            "accountConsentSpeechResultContext = context",
            "updateAccountConsentSpeechButtons()",
        )
        assertFalse(context.contains("isChecked ="))
        assertFalse(context.contains("emailEnrollmentStore"))
        val rebuild = source.substringAfter("accountConsentChecks.clear()")
            .substringBefore("val consentClauseStops")
        assertOrdered(
            rebuild,
            "accountConsentListenButtons.clear()",
            "accountConsentSpeechResults.clear()",
            "accountConsentSpeechResultContext = null",
        )
        assertOrdered(
            functionSource("updateEmailAccountAccessUi"),
            "refreshAccountConsentSpeechContext()",
            "snapshot.flow != FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4",
            "stopAccountConsentSpeech()",
        )
    }
}
