package kr.co.hanium.dreamup.walksafe.session.runtime

import kr.co.hanium.dreamup.walksafe.session.*
import org.junit.Assert.*
import org.junit.Test

class WalkSessionResumeFreshEvidenceTest {
    @Test fun questionDoneAloneOpensTouchFallbackAndDuplicateOrLateTerminalCannotReopenInput() {
        val f = ResumeFixture(); f.paused(); f.controller.requestWalkSessionResumeConfirmation()
        assertFalse(f.controller.awaitingResponseToDeliveredPrompt)
        val done = f.done!!; val failed = f.failed!!
        done()
        assertTrue(f.controller.awaitingResponseToDeliveredPrompt)
        assertEquals(1, f.enabledButtons); assertEquals(1, f.microphones)
        done(); failed()
        assertEquals(1, f.microphones); assertTrue(f.controller.awaitingResponseToDeliveredPrompt)
        f.controller.confirmWalkSessionResumeFromButton()
        assertFalse(f.controller.awaitingResponseToDeliveredPrompt)
        val active = f.lifecycle.snapshot()
        f.controller.handleWalkSessionResumeRecognition(listOf("취소"))
        done(); failed()
        assertEquals(active, f.lifecycle.snapshot()); assertEquals(1, f.activations)
    }

    @Test fun olderQuestionCannotDeliverOrFailANewQuestionForTheSameToken() {
        val f = ResumeFixture(); f.paused(); f.controller.requestWalkSessionResumeConfirmation()
        val oldDone = f.done!!; val oldFailed = f.failed!!
        f.controller.prepareConfirmationRetry(); f.controller.requestWalkSessionResumeConfirmation()
        oldDone(); oldFailed()
        assertEquals(0, f.microphones); assertTrue(f.controller.promptPending)
        assertFalse(f.controller.awaitingResponseToDeliveredPrompt)
        f.done!!()
        assertTrue(f.controller.awaitingResponseToDeliveredPrompt); assertEquals(1, f.microphones)
    }

    @Test fun currentQuestionDeliveryCannotSurviveForegroundGenerationEpochOrCancellationChanges() {
        val changes: List<(ResumeFixture) -> Unit> = listOf(
            { it.foreground = false }, { it.generation++ },
            { it.lifecycle.handle(WalkSessionEvent.EnteredBackground) },
            { it.controller.clearResumeState() }, { it.controller.clearConfirmation() },
        )
        changes.forEach { change ->
            val f = ResumeFixture(); f.paused(); f.controller.requestWalkSessionResumeConfirmation()
            f.done!!(); assertTrue(f.controller.awaitingResponseToDeliveredPrompt)
            change(f); assertFalse(f.controller.awaitingResponseToDeliveredPrompt)
            f.done!!(); assertEquals(1, f.microphones)
        }
    }

    @Test fun touchUsesTheActualFreshCaptureWithoutRenewingTheOriginalConfirmation() {
        val f = ResumeFixture(); f.evidenceMaxAgeMs = 5_000L
        val token = f.paused(); f.now += 6_000L
        assertFalse(token.readiness.isReadyAt(f.now))
        f.controller.confirmWalkSessionResumeFromButton()
        assertEquals(1, f.activations)
        assertEquals(WalkSessionState.ACTIVE, f.lifecycle.snapshot().state)
        assertSame(f.lastCapture, f.lifecycle.snapshot().readiness)
        assertEquals(token.epoch, f.lifecycle.snapshot().epoch)
        assertEquals(1_000L, token.readiness.assessedAtElapsedRealtimeMs)
        assertEquals(f.now, f.lifecycle.snapshot().readiness!!.assessedAtElapsedRealtimeMs)
    }

    @Test fun explicitVoiceStartAlsoPublishesOnlyItsFreshCapture() {
        val f = ResumeFixture(); f.evidenceMaxAgeMs = 5_000L; f.paused()
        f.controller.requestWalkSessionResumeConfirmation(); f.done!!(); f.now += 6_000L
        f.controller.handleWalkSessionResumeRecognition(listOf("시작"), floatArrayOf(.99f))
        assertEquals(1, f.activations); assertSame(f.lastCapture, f.lifecycle.snapshot().readiness)
    }

    @Test fun freshEvidenceCannotExtendThirtySecondsOrSurviveAStaleCapture() {
        for (expireToken in listOf(true, false)) {
            val f = ResumeFixture(); f.evidenceMaxAgeMs = 5_000L; val token = f.paused()
            if (expireToken) f.now += 30_001L else f.afterCapture = { f.now += 5_001L }
            f.controller.confirmWalkSessionResumeFromButton()
            assertEquals(0, f.activations); assertEquals(WalkSessionState.PAUSED, f.lifecycle.snapshot().state)
            assertEquals(WalkSessionRecoveryStage.RECHECK_REQUIRED, f.lifecycle.snapshot().recoveryStage)
            assertNotEquals(token.epoch, f.lifecycle.snapshot().epoch)
        }
    }

    @Test fun missingReadinessOrChangedNoticePermissionLeaseAndForegroundNeverResume() {
        val changes: List<(ResumeFixture) -> Unit> = listOf(
            { it.readinessOpen = false }, { it.confirmed = false }, { it.afterCapture = { it.confirmed = false } },
            { it.permissionAllowed = false }, { it.leasePresent = false },
            { it.revalidationPending = true }, { it.foreground = false },
        )
        changes.forEach { change ->
            val f = ResumeFixture(); f.paused(); change(f)
            f.controller.confirmWalkSessionResumeFromButton()
            assertEquals(0, f.activations); assertNotEquals(WalkSessionState.ACTIVE, f.lifecycle.snapshot().state)
        }
    }

    @Test fun changedCompletePlanOrEpochCannotReplaceTheOriginalConfirmation() {
        for (changeEpoch in listOf(true, false)) {
            val f = ResumeFixture(); f.paused()
            f.captureTransform = { original ->
                val epoch = if (changeEpoch) original.epoch.copy(recoveryGeneration = original.epoch.recoveryGeneration + 1) else original.epoch
                val plan = if (changeEpoch) original.plan else original.plan.copy(checkEntryPermissions = !original.plan.checkEntryPermissions)
                val collector = WalkSessionReadinessCollector(epoch, plan)
                plan.requiredRequirements.forEach { collector.record(WalkSessionReadinessObservation(epoch, it, WalkSessionReadinessStatus.READY)) }
                collector.build(f.now)
            }
            f.controller.confirmWalkSessionResumeFromButton()
            assertEquals(0, f.activations); assertEquals(listOf("resume_readiness_changed"), f.rechecks)
        }
    }

    @Test fun nonStartResponsesKeepTheOriginalConservativeExpiryAndNeverCaptureFreshEvidence() {
        for (phrase in listOf("취소", "", "모름")) {
            val f = ResumeFixture(); f.evidenceMaxAgeMs = 5_000L; f.paused()
            f.controller.requestWalkSessionResumeConfirmation(); f.done!!(); f.now += 6_000L
            f.controller.handleWalkSessionResumeRecognition(listOf(phrase), floatArrayOf(.99f))
            assertEquals(0, f.captures); assertEquals(0, f.activations)
            assertEquals(WalkSessionRecoveryStage.RECHECK_REQUIRED, f.lifecycle.snapshot().recoveryStage)
        }
    }

    @Test fun legacyResumeEventWithoutFreshEvidenceStillRejectsExpiredOriginalSamples() {
        val f = ResumeFixture(); f.evidenceMaxAgeMs = 5_000L; val token = f.paused(); f.now += 6_000L
        f.lifecycle.handle(WalkSessionEvent.ResumeConfirmationReceived(WalkSessionResumeConfirmation.START, token))
        assertEquals(WalkSessionRecoveryStage.RECHECK_REQUIRED, f.lifecycle.snapshot().recoveryStage)
    }
}
