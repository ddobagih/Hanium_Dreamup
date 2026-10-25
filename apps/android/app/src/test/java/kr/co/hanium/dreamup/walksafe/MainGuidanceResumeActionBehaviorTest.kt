package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.navigation.DestinationGuidanceResumeAction
import kr.co.hanium.dreamup.walksafe.navigation.start.NativeGuidanceCalibrationSession
import kr.co.hanium.dreamup.walksafe.device.WalkSafeStartupCapabilityDecision
import kr.co.hanium.dreamup.walksafe.device.WalkSafeStartupCapabilityTier
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController.NativePhoneMountingCheckRequest
import kr.co.hanium.dreamup.walksafe.network.GatewaySessionProcessCoordinator
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.session.runtime.*
import java.lang.reflect.Proxy
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController.NativeUiPage
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/** Actual Main action routing and actual lifecycle; the confirmation sink does not claim activation,
 * View rendering, onCreate, SDK readiness or native speech. Android journey tests exercise the click. */
class MainGuidanceResumeActionBehaviorTest {
    @After fun clearSession() {
        GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
    }

    @Test fun readyPausedGuidanceExposesExplicitConfirmationWithoutChangingTheLifecycle() {
        val f = Fixture()
        val before = f.base.lifecycle.snapshot()
        assertEquals(DestinationGuidanceResumeAction.CONFIRM, f.main.currentNativeGuidanceResumeAction())
        assertTrue(f.click())
        assertEquals(1, f.confirmations)
        assertEquals(before, f.base.lifecycle.snapshot())
    }

    @Test fun pendingNoticeAndPendingResumeSpeechDoNotAcceptAnotherClick() {
        val f = Fixture()
        f.base.set("startupCapabilityConfirmationPending", true)
        assertEquals(DestinationGuidanceResumeAction.PENDING, f.main.currentNativeGuidanceResumeAction())
        assertTrue(f.click())
        assertEquals(0, f.confirmations)
        f.base.set("startupCapabilityConfirmationPending", false)
        val owner = MainActivityControllerTestFixture.controller(f.main, "walkSessionResumeController") as WalkSessionResumeController
        owner.javaClass.getDeclaredField("walkSessionResumePromptPending").apply { isAccessible = true }.setBoolean(owner, true)
        assertEquals(DestinationGuidanceResumeAction.PENDING, f.main.currentNativeGuidanceResumeAction())
        assertTrue(f.click())
        assertEquals(0, f.confirmations)
        owner.clearResumeState()
        assertEquals(DestinationGuidanceResumeAction.CONFIRM, f.main.currentNativeGuidanceResumeAction())
    }

    @Test fun activityBackgroundConsumesAnOldVisibleActionWithoutConfirmation() {
        val f = Fixture()
        assertEquals(DestinationGuidanceResumeAction.CONFIRM, f.main.currentNativeGuidanceResumeAction())
        f.base.set("isActivityForeground", false)
        assertEquals(DestinationGuidanceResumeAction.NONE, f.main.currentNativeGuidanceResumeAction())
        assertTrue(f.click())
        assertEquals(0, f.confirmations)
    }

    @Test fun backgroundEpochAndNewRecheckRetireThePreviouslyVisibleConfirmation() {
        val f = Fixture()
        val previous = f.base.lifecycle.snapshot().confirmationToken
        f.base.lifecycle.handle(WalkSessionEvent.EnteredBackground)
        f.base.lifecycle.handle(WalkSessionEvent.EnteredForeground)
        assertNotEquals(previous?.epoch, f.base.lifecycle.snapshot().epoch)
        assertNull(f.base.lifecycle.snapshot().confirmationToken)
        assertEquals(DestinationGuidanceResumeAction.NONE, f.main.currentNativeGuidanceResumeAction())
        assertFalse(f.click())
        assertEquals(0, f.confirmations)
        f.ready()
        val current = f.base.lifecycle.snapshot().confirmationToken
        assertNotEquals(previous, current)
        assertTrue(f.click())
        assertEquals(1, f.confirmations)
        assertEquals(current, f.base.lifecycle.snapshot().confirmationToken)
    }

    @Test fun homeAndActiveGuidanceKeepTheOrdinaryStartPath() {
        val f = Fixture()
        f.base.set("nativeUiPage", NativeUiPage.HOME)
        assertEquals(DestinationGuidanceResumeAction.NONE, f.main.currentNativeGuidanceResumeAction())
        assertFalse(f.click())
        f.base.set("nativeUiPage", NativeUiPage.GUIDANCE)
        val token = requireNotNull(f.base.lifecycle.snapshot().confirmationToken)
        f.base.lifecycle.handle(WalkSessionEvent.ResumeConfirmationReceived(WalkSessionResumeConfirmation.START, token))
        assertEquals(WalkSessionState.ACTIVE, f.base.lifecycle.snapshot().state)
        assertEquals(DestinationGuidanceResumeAction.NONE, f.main.currentNativeGuidanceResumeAction())
        assertFalse(f.click())
        assertEquals(0, f.confirmations)
    }

    @Test fun currentReadinessTokenDoesNotOfferResumeBeforeActualCalibrationAndNoticeCompletion() {
        val f = Fixture(calibrationReady = false, noticeConfirmed = false)
        assertNotNull(f.base.lifecycle.snapshot().confirmationToken)
        assertEquals(DestinationGuidanceResumeAction.PREPARING, f.main.currentNativeGuidanceResumeAction())
        assertTrue(f.click()); assertEquals(0, f.confirmations)
        f.done!!()
        f.base.now += 3_000L
        // Presentation cannot promote OBSERVING to READY merely because its clock has advanced.
        repeat(3) { assertEquals(DestinationGuidanceResumeAction.PREPARING, f.main.currentNativeGuidanceResumeAction()) }
        assertEquals(1, f.prompts)
        assertTrue(f.session.advance(f.request))
        assertEquals(DestinationGuidanceResumeAction.NOTICE, f.main.currentNativeGuidanceResumeAction())
        assertTrue(f.click()); assertEquals(1, f.confirmations)
        f.confirmNotice()
        assertEquals(DestinationGuidanceResumeAction.CONFIRM, f.main.currentNativeGuidanceResumeAction())
        assertTrue(f.click()); assertEquals(2, f.confirmations)
        assertEquals(WalkSessionState.PAUSED, f.base.lifecycle.snapshot().state)
    }

    @Test fun retiredCalibrationOrMissingRequestCannotConsumeTheOrdinaryPreparationRetry() {
        val f = Fixture()
        f.base.set("nativePhoneMountingCheckRequest", null)
        assertEquals(DestinationGuidanceResumeAction.NONE, f.main.currentNativeGuidanceResumeAction())
        assertFalse(f.click())
        assertEquals(0, f.confirmations)
        f.base.set("nativePhoneMountingCheckRequest", f.request.copy())
        assertEquals(DestinationGuidanceResumeAction.PREPARING, f.main.currentNativeGuidanceResumeAction())
        assertTrue(f.click())
        assertEquals(0, f.confirmations)
    }

    @Test fun actualMainResumeOwnerExposesTouchOnlyAfterItsCurrentQuestionDone() {
        val f = Fixture()
        val owner = MainActivityControllerTestFixture.controller(f.main, "walkSessionResumeController") as WalkSessionResumeController
        val environmentField = owner.javaClass.getDeclaredField("environment").apply { isAccessible = true }
        val actualEnvironment = environmentField.get(owner)
        environmentField.set(owner, Proxy.newProxyInstance(WalkResumeEnvironment::class.java.classLoader,
            arrayOf(WalkResumeEnvironment::class.java)) { _, method, args ->
            if (method.name == "voiceConfirmationAvailable") true else method.invoke(actualEnvironment, *(args ?: emptyArray()))
        })
        var questionDone: (() -> Unit)? = null
        var microphones = 0
        val effects = cleanupPort<WalkResumeEffects> { name, args ->
            when (name) {
                "speakResume" -> { @Suppress("UNCHECKED_CAST")
                    questionDone = args[1] as () -> Unit; true }
                "runOnUiThread" -> { @Suppress("UNCHECKED_CAST")
                    (args[0] as () -> Unit)(); Unit }
                "startResumeRecognition" -> { microphones++; true }
                else -> Unit
            }
        }
        owner.javaClass.getDeclaredField("effects").apply { isAccessible = true }.set(owner, effects)
        owner.requestWalkSessionResumeConfirmation()
        assertEquals(DestinationGuidanceResumeAction.PENDING, f.main.currentNativeGuidanceResumeAction())
        assertTrue(f.click()); assertEquals(0, f.confirmations)
        questionDone!!()
        assertEquals(1, microphones)
        assertTrue(owner.promptPending)
        assertTrue(owner.awaitingResponseToDeliveredPrompt)
        assertEquals(DestinationGuidanceResumeAction.CONFIRM, f.main.currentNativeGuidanceResumeAction())
        assertTrue(f.click()); assertEquals(1, f.confirmations)
        f.base.set("startupCapabilityConfirmationPending", true)
        assertEquals(DestinationGuidanceResumeAction.PENDING, f.main.currentNativeGuidanceResumeAction())
        f.base.set("startupCapabilityConfirmationPending", false)
        f.base.set("isActivityForeground", false)
        assertFalse(owner.awaitingResponseToDeliveredPrompt)
        assertEquals(DestinationGuidanceResumeAction.NONE, f.main.currentNativeGuidanceResumeAction())
    }

    private class Fixture(val calibrationReady: Boolean = true, val noticeConfirmed: Boolean = true) {
        val base = MainActivityContinuousLocationStartBehaviorTest.Fixture()
        val main = base.main
        var confirmations = 0
        var prompts = 0
        var done: (() -> Unit)? = null
        lateinit var request: NativePhoneMountingCheckRequest
        val decision = WalkSafeStartupCapabilityDecision(WalkSafeStartupCapabilityTier.FULL, emptyList(), emptyList(), "준비 확인")
        val session: NativeGuidanceCalibrationSession by lazy {
            (MainActivity::class.java.getDeclaredField("nativeGuidanceCalibrationSession\$delegate")
                .apply { isAccessible = true }.get(main) as Lazy<*>).value as NativeGuidanceCalibrationSession
        }
        init {
            base.activate()
            base.set("nativeUiPage", NativeUiPage.GUIDANCE)
            base.lifecycle.handle(WalkSessionEvent.EnteredBackground)
            base.lifecycle.handle(WalkSessionEvent.EnteredForeground)
            ready()
        }
        fun ready() {
            val epoch = base.lifecycle.snapshot().epoch
            val collector = WalkSessionReadinessCollector(epoch,
                WalkSessionReadinessPlan(WalkSessionAction.RESUME_WALK, WalkSessionMode.FULL))
            collector.plan.requiredRequirements.forEach {
                collector.record(WalkSessionReadinessObservation(epoch, it, WalkSessionReadinessStatus.READY))
            }
            base.lifecycle.handle(WalkSessionEvent.RecoveryRecheckCompleted(collector.build(base.now)))
            assertEquals(WalkSessionRecoveryStage.AWAITING_RESUME_CONFIRMATION, base.lifecycle.snapshot().recoveryStage)
            assertNotNull(base.lifecycle.snapshot().confirmationToken)
            base.nativeCheck()
            val previous = base.field("nativePhoneMountingCheckRequest") as NativePhoneMountingCheckRequest
            request = previous.copy(checkRequest = previous.checkRequest.copy(epoch = epoch))
            base.set("nativePhoneMountingCheckRequest", request)
            base.set("nativePrewalkStartEpoch", epoch)
            base.set("startupCapabilityDecision", decision)
            base.set("confirmedStartupCapabilityDecision", null)
            replace("elapsedRealtimeMs", { base.now })
            replace("callbacks", object : NativeGuidanceCalibrationSession.Callbacks {
                override fun postDelayed(action: Runnable, delayMs: Long) { }
                override fun remove(action: Runnable) { }
            })
            replace("speech", object : NativeGuidanceCalibrationSession.Speech {
                override fun pending() = false
                override fun commandGeneration() = 1L
                override fun submit(message: String, current: () -> Boolean, completed: () -> Unit, failed: () -> Unit): Boolean {
                    assertTrue(current()); prompts++; done = completed; return true
                }
                override fun cancel() { }
            })
            replace("effects", object : NativeGuidanceCalibrationSession.Effects {
                override fun recordStage(stage: String) { }
                override fun reportVisibleFailure(message: String) { }
                override fun visibleFailure(): String? = null
                override fun clearVisibleFailure() { }
                override fun render() { }
                override fun continuePrewalk() { }
                override fun recoverPreparation(request: NativePhoneMountingCheckRequest) { }
            })
            assertFalse(session.advance(request))
            if (calibrationReady) {
                requireNotNull(done)()
                base.now += 3_000L
                assertTrue(session.advance(request))
            }
            if (noticeConfirmed) confirmNotice()
        }
        private fun replace(name: String, value: Any) {
            NativeGuidanceCalibrationSession::class.java.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
        }
        fun confirmNotice() { base.set("confirmedStartupCapabilityDecision", decision) }
        fun click() = main.handleNativeGuidanceResumeAction { confirmations++ }
    }
}
