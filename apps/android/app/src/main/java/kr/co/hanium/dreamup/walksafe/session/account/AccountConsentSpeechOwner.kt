package kr.co.hanium.dreamup.walksafe.session.account

import kr.co.hanium.dreamup.walksafe.feedback.NavigationSpeechDispatchResult
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingFlow
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingSnapshot
import kr.co.hanium.dreamup.walksafe.session.FirstRunOnboardingStage
import kr.co.hanium.dreamup.walksafe.ui.AccountAccessPresenter
import kr.co.hanium.dreamup.walksafe.ui.workflow.EducationInteractionController

/** Owns clause replay and actual DONE evidence. Listening never grants consent. */
internal class AccountConsentSpeechOwner(
    private val runtime: Runtime,
    private val screen: Screen,
    private val initializationReadinessTimeoutMs: Long,
) {
    interface FeedbackChannel {
        fun isSpeechInitializing(): Boolean
        fun speakConsentClause(message: String, onCompleted: () -> Unit, onFailed: () -> Unit,
            onCancelled: () -> Unit): NavigationSpeechDispatchResult
    }
    interface Scheduler { fun postDelayed(action: () -> Unit, delayMs: Long) }
    interface Runtime {
        fun foreground(): Boolean
        fun actorId(): String?
        fun onboardingSnapshot(): FirstRunOnboardingSnapshot
        fun walkActive(): Boolean
        fun educationState(): EducationInteractionController.PresentationState
        fun voiceRecognitionActive(): Boolean
        fun cancelVoiceRecognition()
        fun feedbackGeneration(): Int
        fun feedbackCurrent(generation: Int): Boolean
        fun maxSpeechInputLength(): Int
        fun ensureFeedbackChannel(): FeedbackChannel
        fun newScheduler(): Scheduler
        fun elapsedRealtimeMs(): Long
        fun externalFallbackSpeechActive(): Boolean
        fun cancelExistingTrainingFeedback()
        fun runOnUiThread(action: () -> Unit)
    }
    interface Screen {
        fun signupExpanded(): Boolean
        fun consentStep(): Boolean
        fun cardShown(key: String): Boolean
        fun listenButtonShown(key: String): Boolean
        fun clauseText(key: String): String?
        fun updateSpeechButtons(facts: AccountAccessPresenter.SpeechButtonFacts)
        fun updateStatus(title: String, message: String)
    }

    private val completedResults = linkedMapOf<String, Boolean>()
    private var resultContext: Pair<String?, String>? = null
    private var playingKey: String? = null
    private var generation = 0L

    /** Called at the old binding reset point, after stopping any active playback. */
    fun resetCompletedContextAfterBinding() {
        completedResults.clear()
        resultContext = null
    }

    fun refreshAccountConsentSpeechContext() {
        val context = runtime.actorId() to runtime.onboardingSnapshot().epoch.toString()
        if (resultContext == context) return
        stopAccountConsentSpeech()
        completedResults.clear()
        resultContext = context
        updateAccountConsentSpeechButtons()
    }

    fun isAccountConsentClauseVisible(key: String): Boolean =
        runtime.foreground() &&
            runtime.onboardingSnapshot().flow == FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4 &&
            runtime.onboardingSnapshot().stage == FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT &&
            screen.signupExpanded() && screen.consentStep() &&
            screen.cardShown(key) && screen.listenButtonShown(key)

    fun updateAccountConsentSpeechButtons() {
        screen.updateSpeechButtons(AccountAccessPresenter.SpeechButtonFacts(playingKey, completedResults.toMap()))
    }

    fun stopAccountConsentSpeech() {
        if (playingKey == null) return
        generation += 1L
        playingKey = null
        runtime.cancelExistingTrainingFeedback()
        updateAccountConsentSpeechButtons()
    }

    fun stopOutsideAccountStage(snapshot: FirstRunOnboardingSnapshot) {
        if (playingKey != null && (snapshot.flow != FirstRunOnboardingFlow.EMAIL_ACCOUNT_V4 ||
                snapshot.stage != FirstRunOnboardingStage.EMAIL_OTP_ENROLLMENT ||
                !screen.signupExpanded() || !screen.consentStep())) stopAccountConsentSpeech()
    }

    fun playAccountConsentClause(key: String) {
        refreshAccountConsentSpeechContext()
        if (playingKey == key) {
            stopAccountConsentSpeech()
            screen.updateStatus("약관 듣기 중지", "듣기를 중지했습니다. 동의 항목은 변경되지 않았습니다.")
            return
        }
        if (!isAccountConsentClauseVisible(key) || runtime.walkActive() || runtime.educationState().educationInFlight ||
            runtime.educationState().practiceInFlight != null) return
        val message = screen.clauseText(key)?.takeIf { it.isNotBlank() } ?: return
        stopAccountConsentSpeech()
        if (runtime.voiceRecognitionActive()) runtime.cancelVoiceRecognition()
        val playbackGeneration = ++generation
        val actorId = runtime.actorId()
        val onboardingEpoch = runtime.onboardingSnapshot().epoch
        val lifecycleGeneration = runtime.feedbackGeneration()
        playingKey = key
        updateAccountConsentSpeechButtons()
        val chunks = message.chunked(runtime.maxSpeechInputLength())
        val actuator = runtime.ensureFeedbackChannel()
        val handler = runtime.newScheduler()
        val initializationDeadlineMs = runtime.elapsedRealtimeMs() + initializationReadinessTimeoutMs
        fun ownsPlayback(): Boolean = playbackGeneration == generation && playingKey == key
        fun finish(completed: Boolean) {
            if (!ownsPlayback()) return
            if (!runtime.feedbackCurrent(lifecycleGeneration) || runtime.actorId() != actorId ||
                runtime.onboardingSnapshot().epoch != onboardingEpoch || !isAccountConsentClauseVisible(key) ||
                runtime.walkActive() || runtime.voiceRecognitionActive()) {
                stopAccountConsentSpeech()
                return
            }
            completedResults[key] = completed
            generation += 1L
            playingKey = null
            updateAccountConsentSpeechButtons()
            screen.updateStatus(
                if (completed) "약관 듣기 완료" else "약관 듣기 미완료",
                if (completed) "내용을 확인한 뒤 해당 동의 항목을 직접 선택하세요. 청취는 동의가 아닙니다."
                else "안내를 끝까지 재생하지 못했습니다. 다시 듣거나 화면의 본문을 확인하세요. 동의는 변경되지 않았습니다.",
            )
        }
        fun playChunk(index: Int) {
            if (!ownsPlayback()) return
            if (!runtime.feedbackCurrent(lifecycleGeneration) || runtime.actorId() != actorId ||
                runtime.onboardingSnapshot().epoch != onboardingEpoch || !isAccountConsentClauseVisible(key) ||
                runtime.walkActive() || runtime.voiceRecognitionActive()) {
                stopAccountConsentSpeech()
                return
            }
            if (actuator.isSpeechInitializing()) {
                if (runtime.elapsedRealtimeMs() >= initializationDeadlineMs) finish(false)
                else handler.postDelayed({ playChunk(index) }, 100L)
                return
            }
            if (runtime.externalFallbackSpeechActive()) {
                finish(false)
                return
            }
            val dispatch = actuator.speakConsentClause(
                message = chunks[index],
                onCompleted = {
                    runtime.runOnUiThread {
                        if (!ownsPlayback()) return@runOnUiThread
                        if (index + 1 < chunks.size) playChunk(index + 1)
                        else if (runtime.feedbackCurrent(lifecycleGeneration) && runtime.actorId() == actorId &&
                            runtime.onboardingSnapshot().epoch == onboardingEpoch && isAccountConsentClauseVisible(key)) finish(true)
                        else stopAccountConsentSpeech()
                    }
                },
                onFailed = { runtime.runOnUiThread { finish(false) } },
                onCancelled = { runtime.runOnUiThread { finish(false) } },
            )
            if (dispatch != NavigationSpeechDispatchResult.ACCEPTED) finish(false)
        }
        playChunk(0)
    }
}
