package kr.co.hanium.dreamup.walksafe.ui.workflow

import java.lang.reflect.Proxy
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckFeature
import kr.co.hanium.dreamup.walksafe.feedback.NavigationSpeechDispatchResult
import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

class EducationInteractionControllerTest {
    @Test fun constructorDoesNotQueryAuthoritySdkOrCreateFeedback() {
        fun <T> forbidden(type: Class<T>): T = requireNotNull(type.cast(Proxy.newProxyInstance(type.classLoader,
            arrayOf(type)) { _, method, _ -> error("Constructor called ${method.name}") }))
        val c = EducationInteractionController(forbidden(EducationInteractionController.Profiles::class.java),
            forbidden(EducationInteractionController.Admission::class.java),
            forbidden(EducationInteractionController.Runtime::class.java),
            forbidden(EducationInteractionController.Effects::class.java))
        assertFalse(c.presentationState().educationInFlight)
        assertNull(c.presentationState().practiceInFlight)
    }

    @Test fun acceptedDispatchAndPartialDoneNeverRecordWholeEducation() {
        val f = Fixture(usage = true)
        val baseline = f.policy.snapshot()
        f.c.playNativeSafetyEducation(false)
        assertEquals(1, f.spoken.size)
        assertEquals(baseline, f.policy.snapshot())
        assertEquals(0, f.persistCount)
        f.spoken.single().done()
        assertTrue(f.c.presentationState().educationInFlight)
        assertFalse(f.policy.snapshot().appUsageReviewed)
        assertEquals(0, f.persistCount)
        f.finishPlayback()
        assertTrue(f.policy.snapshot().appUsageReviewed)
        assertFalse(f.c.presentationState().educationInFlight)
        assertEquals(1, f.beginCount)
        assertEquals(1, f.persistCount)
        assertEquals(0, f.stageCompletionCount)
    }

    @Test fun repeatedSegmentDoneCannotQueueOrRecordTheNextSegmentTwice() {
        val f = Fixture(usage = true)
        f.c.playNativeSafetyEducation(false)
        val first = f.spoken.single()
        first.done()
        first.done()
        assertEquals(1, f.pending.size)
        assertEquals(0, f.persistCount)
        f.runNext()
        assertEquals(2, f.spoken.size)
        first.done()
        assertEquals(2, f.spoken.size)
        assertTrue(f.pending.isEmpty())
    }

    @Test fun sameKindStopPrecedesAdmissionAndPreservesPreviouslyCompletedEducation() {
        val f = Fixture(usage = true)
        val baseline = f.policy.snapshot()
        f.c.playNativeSafetyEducation(false)
        val old = f.spoken.single()
        f.trace.clear()
        f.cancelHook = {
            assertFalse(f.c.presentationState().educationInFlight)
            old.cancelled?.invoke()
        }
        f.c.playNativeSafetyEducation(false)
        assertFalse(f.trace.any { it.startsWith("admit:") })
        assertFalse(f.c.presentationState().educationInFlight)
        assertEquals(baseline, f.policy.snapshot())
        assertEquals(0, f.persistCount)
        assertEquals("교육 듣기 중지", f.status)
        assertNotNull(f.policy.beginAppUsageEducationPlayback())
    }

    @Test fun oldDoneFailureAndCancellationCannotMutateReplacementPlayback() {
        val f = Fixture(usage = true)
        f.c.playNativeSafetyEducation(false)
        val old = f.spoken.single()
        f.c.playNativeSafetyEducation(false)
        f.c.playNativeSafetyEducation(false)
        val baseline = f.policy.snapshot()
        val before = f.trace.toList()
        old.done(); old.failed(); old.cancelled?.invoke()
        assertEquals(before, f.trace.take(before.size))
        assertEquals(listOf("foreground", "deliveryAuthority", "deliveryAuthority"), f.trace.drop(before.size))
        assertEquals(baseline, f.policy.snapshot())
        assertTrue(f.c.presentationState().educationInFlight)
        assertEquals(2, f.spoken.size)
        assertEquals(0, f.persistCount)
        f.finishPlayback()
        assertTrue(f.policy.snapshot().appUsageReviewed)
        assertEquals(1, f.persistCount)
    }

    @Test fun oldReadinessPollCannotDispatchOrClearTheNewUsageOwner() {
        val f = Fixture(usage = true)
        f.initializing = true
        f.c.playNativeSafetyEducation(false)
        assertEquals(1, f.pending.size)
        f.c.playNativeSafetyEducation(false)
        f.c.playNativeSafetyEducation(false)
        assertEquals(2, f.pending.size)
        f.initializing = false
        f.runNext()
        assertTrue(f.spoken.isEmpty())
        assertTrue(f.c.presentationState().educationInFlight)
        f.runNext()
        assertEquals(1, f.spoken.size)
        assertEquals(0, f.persistCount)
    }

    @Test fun refusalFailureCancellationAndReadinessTimeoutDoNotRecordListening() {
        for (terminal in listOf("refused", "failed", "cancelled", "timeout")) {
            val f = Fixture(usage = true)
            val baseline = f.policy.snapshot()
            if (terminal == "refused") f.dispatch = NavigationSpeechDispatchResult.UNAVAILABLE
            if (terminal == "timeout") f.initializing = true
            f.c.playNativeSafetyEducation(false)
            when (terminal) {
                "failed" -> f.spoken.single().failed()
                "cancelled" -> f.spoken.single().cancelled?.invoke()
                "timeout" -> { f.now = 100_000L; f.runNext() }
            }
            assertEquals(terminal, baseline, f.policy.snapshot())
            assertFalse(terminal, f.c.presentationState().educationInFlight)
            assertEquals(terminal, 0, f.persistCount)
            assertNotNull(terminal, f.policy.beginAppUsageEducationPlayback())
        }
    }

    @Test fun changedActorPolicyForegroundStageAndDeviceRejectRealDone() {
        for (change in listOf("actor", "policy", "foreground", "stage", "device")) {
            val f = Fixture(usage = true)
            f.c.playNativeSafetyEducation(false)
            val captured = f.policy
            when (change) {
                "actor" -> f.actor = "B"
                "policy" -> f.policy = consentPolicy()
                "foreground" -> f.foreground = false
                "stage" -> f.trainingStage = false
                "device" -> f.deviceReady = false
            }
            f.spoken.single().done()
            assertFalse(change, captured.snapshot().appUsageReviewed)
            assertEquals(change, 0, f.persistCount)
            assertTrue(change, f.pending.isEmpty())
        }
    }

    @Test fun stateOnlyCancelChecksGenerationActorAndExactPolicyWithoutDeliveryAuthority() {
        val f = Fixture(usage = true)
        f.c.playNativeSafetyEducation(false)
        f.foreground = false; f.deviceReady = false
        f.trace.clear()
        assertFalse(f.c.clearPriorityUserTrainingStateIfOwned(0L, "A", f.policy, null))
        assertTrue(f.trace.isEmpty())
        assertFalse(f.c.clearPriorityUserTrainingStateIfOwned(1L, "B", f.policy, null))
        assertFalse(f.c.clearPriorityUserTrainingStateIfOwned(1L, "A", consentPolicy(), null))
        assertTrue(f.c.clearPriorityUserTrainingStateIfOwned(1L, "A", f.policy, null))
        assertFalse(f.c.presentationState().educationInFlight)
        assertFalse(f.trace.contains("deliveryAuthority"))
        assertFalse(f.trace.contains("foreground"))
        assertNotNull(f.policy.beginAppUsageEducationPlayback())
    }

    @Test fun screenReaderReviewUsesNoActuatorSchedulerOrUsagePlaybackToken() {
        val f = Fixture(usage = true)
        f.screenReader = true
        f.c.playNativeSafetyEducation(false)
        assertEquals(2, f.screenReaderReads)
        assertTrue(f.spoken.isEmpty())
        assertFalse(f.trace.contains("ensureFeedback"))
        assertFalse(f.trace.contains("newScheduler"))
        assertTrue(f.policy.snapshot().appUsageReviewed)
        assertFalse(f.c.presentationState().educationInFlight)
        assertNotNull(f.policy.beginAppUsageEducationPlayback())
    }

    @Test fun markerAndPersistFailureKeepCompletionClosed() {
        for (failure in listOf("marker", "persist")) {
            val f = Fixture(usage = true)
            f.beginAllowed = failure != "marker"
            f.persistAllowed = failure != "persist"
            f.c.playNativeSafetyEducation(false)
            f.finishPlayback()
            assertFalse(f.c.presentationState().educationInFlight)
            assertFalse(f.policy.snapshot().appUsageReviewed)
            assertEquals(0, f.stageCompletionCount)
            if (failure == "marker") assertEquals(0, f.persistCount)
        }
    }

    @Test fun legacySafetyPlaybackRecordsOnlyActualDoneAndOwnsCancellation() {
        val f = Fixture(usage = false)
        f.nativeFlow = false
        f.policy = adultPolicy()
        f.c.reviewPriorityUserSafetyEducation()
        assertFalse(f.policy.snapshot().educationReviewed)
        assertTrue(f.c.presentationState().educationInFlight)
        f.spoken.single().done()
        assertTrue(f.policy.snapshot().educationReviewed)
        assertEquals(1, f.persistCount)
        assertFalse(f.trace.contains("newScheduler"))
    }

    @Test fun bothDeliverySignalsStillRequireSeparateCurrentHazardUserResponse() {
        val f = Fixture(usage = false)
        f.policy = consentPolicy().apply { confirmSafePracticePlace() }
        f.c.performPriorityUserPractice(PriorityUserPractice.HAZARD_ALERT)
        f.vibration.single().invoke()
        assertFalse(f.c.hazardResponseReady(f.policy))
        f.spoken.single().done()
        assertTrue(f.c.hazardResponseReady(f.policy))
        assertTrue(f.policy.snapshot().completedPractices.isEmpty())
        assertEquals(PriorityUserPractice.HAZARD_ALERT, f.c.presentationState().practiceInFlight)
        f.c.performPriorityUserPractice(PriorityUserPractice.HAZARD_ALERT)
        assertTrue(f.policy.snapshot().hazardResponseConfirmed)
        assertTrue(PriorityUserPractice.HAZARD_ALERT in f.policy.snapshot().completedPractices)
        assertNull(f.c.presentationState().practiceInFlight)
    }

    @Test fun cancelledPracticeCallbacksCannotSubmitSignalsForReplacementAttempt() {
        val f = Fixture(usage = false)
        f.policy = consentPolicy().apply { confirmSafePracticePlace() }
        f.c.performPriorityUserPractice(PriorityUserPractice.HAZARD_ALERT)
        val oldSpeech = f.spoken.single()
        val oldVibration = f.vibration.single()
        f.c.cancelPendingPriorityUserTrainingFeedback()
        f.c.performPriorityUserPractice(PriorityUserPractice.HAZARD_ALERT)
        val before = f.policy.snapshot()
        val trace = f.trace.toList()
        oldSpeech.done(); oldVibration(); oldSpeech.failed(); oldSpeech.cancelled?.invoke()
        assertEquals(before, f.policy.snapshot())
        assertEquals(trace, f.trace.take(trace.size))
        assertEquals(listOf("deliveryAuthority", "deliveryAuthority", "deliveryAuthority"), f.trace.drop(trace.size))
        assertFalse(f.c.hazardResponseReady(f.policy))
        assertEquals(PriorityUserPractice.HAZARD_ALERT, f.c.presentationState().practiceInFlight)
    }

    private data class Speech(val done: () -> Unit, val failed: () -> Unit, val cancelled: (() -> Unit)?)

    private class Fixture(val usage: Boolean) {
        var actor: String? = "A"
        var profileActor: String? = "A"
        var policy = if (usage) consentPolicy() else adultPolicy()
        var deviceReady = true
        var foreground = true
        var trainingStage = true
        var nativeFlow = true
        var screenReader = false
        var screenReaderReads = 0
        var now = 0L
        var initializing = false
        var beginAllowed = true
        var persistAllowed = true
        var beginCount = 0
        var persistCount = 0
        var stageCompletionCount = 0
        var status = ""
        var cancelHook: (() -> Unit)? = null
        var dispatch = NavigationSpeechDispatchResult.ACCEPTED
        val trace = mutableListOf<String>()
        val spoken = mutableListOf<Speech>()
        val vibration = mutableListOf<() -> Unit>()
        val pending = mutableListOf<() -> Unit>()
        private val channel = object : EducationInteractionController.FeedbackChannel {
            override fun isSpeechInitializing() = initializing
            override fun speakPriorityUserTraining(message: String, onCompleted: () -> Unit, onFailed: () -> Unit,
                onCancelled: (() -> Unit)?): NavigationSpeechDispatchResult {
                spoken += Speech(onCompleted, onFailed, onCancelled)
                return dispatch
            }
            override fun playPriorityUserTrainingVibration(onWindowElapsed: () -> Unit, onFailed: () -> Unit): Boolean {
                vibration += onWindowElapsed
                return true
            }
        }
        val c = EducationInteractionController(
            object : EducationInteractionController.Profiles {
                override fun nativeAccountOrNull() = actor?.let { EducationInteractionController.Account(it, policy) }
                override fun legacyAccount() = EducationInteractionController.LegacyAccount(actor, policy, policy.accountBlockReason())
                override fun policy() = policy
                override fun initializedPolicyOrNull() = policy
                override fun beginMutation(): Boolean { beginCount += 1; trace += "beginMutation"; if (!beginAllowed) policy = adultPolicy(); return beginAllowed }
                override fun persistOrFailClosed(): Boolean { persistCount += 1; trace += "persist"; if (!persistAllowed) policy = adultPolicy(); return persistAllowed }
                override fun actorBound(actorId: String?) = actorId == profileActor
                override fun sameStateOwner(actorId: String, policy: PriorityUserOnboardingPolicy) = actorId == profileActor && policy === this@Fixture.policy
                override fun deliveryCurrent(generation: Long, currentGeneration: Long, actorId: String,
                    policy: PriorityUserOnboardingPolicy): Boolean {
                    trace += "deliveryAuthority"
                    return foreground && generation == currentGeneration && actorId == actor && actorId == profileActor &&
                        policy === this@Fixture.policy && trainingStage && deviceReady
                }
            },
            object : EducationInteractionController.Admission {
                override fun showPhonePosture(): Boolean { trace += "admit:posture"; return usage }
                override fun showSafetyEducation(): Boolean { trace += "admit:education"; return !usage }
                override fun requireDeviceCheck(): Boolean { trace += "admit:device"; return deviceReady }
                override fun screenReaderActive(): Boolean { screenReaderReads += 1; return screenReader }
                override fun nativeEducationFlow() = nativeFlow
                override fun practiceActorId() = actor
                override fun supportEnvironment() = PriorityUserSupportEnvironment(false, false, false, true, true)
                override fun featureEnabled(feature: PostLoginDeviceCheckFeature) = true
            },
            object : EducationInteractionController.Runtime {
                override fun runOnUiThread(action: () -> Unit) { action() }
                override fun activityClosing() = false
                override fun foreground(): Boolean { trace += "foreground"; return foreground }
                override fun elapsedRealtimeMs() = now
                override fun newPlaybackScheduler(): EducationInteractionController.PlaybackScheduler {
                    trace += "newScheduler"
                    return object : EducationInteractionController.PlaybackScheduler {
                        override fun post(action: () -> Unit) { pending += action }
                        override fun postDelayed(action: () -> Unit, delayMs: Long) { assertEquals(100L, delayMs); pending += action }
                    }
                }
                override fun ensureFeedbackChannel(): EducationInteractionController.FeedbackChannel { trace += "ensureFeedback"; return channel }
                override fun cancelExistingFeedback() { trace += "cancelFeedback"; cancelHook?.invoke() }
            },
            object : EducationInteractionController.Effects {
                override fun showStatus(status: String, detail: String) { this@Fixture.status = status; trace += "status:$status" }
                override fun refresh(presentation: EducationInteractionController.Presentation) { trace += "refresh:$presentation" }
                override fun refreshPriorityIfBound() { trace += "refreshIfBound" }
                override fun refreshStartupIfInitialized() { trace += "startupIfBound" }
                override fun speakInteraction(message: String) { trace += "interaction:$message" }
                override fun stopAccountConsentSpeech() { trace += "stopAccountConsent" }
                override fun cancelRecognitionIfActive() { trace += "cancelRecognition" }
                override fun publishUsageConditionsAcknowledged() { trace += "usageAcknowledged" }
                override fun publishEducationAccepted(usageEducation: Boolean, actorId: String) { stageCompletionCount += 1 }
                override fun completeFp004IfReady(actorId: String) { stageCompletionCount += 1 }
            },
        )

        fun runNext() { pending.removeAt(0).invoke() }
        fun finishPlayback() {
            var turns = 0
            while (c.presentationState().educationInFlight) {
                check(++turns < 50) { "Playback did not finish" }
                if (pending.isNotEmpty()) runNext() else spoken.last().done()
            }
        }
    }

    companion object {
        private fun adultPolicy() = PriorityUserOnboardingPolicy().apply { selectAgeBand(PriorityUserAgeBand.ADULT_18_PLUS) }
        private fun consentPolicy() = adultPolicy().apply {
            reviewSafetyEducation(true); reviewPracticeNecessity(true); acceptEducationConsent(); acknowledgeUsageConditions()
        }
    }
}
