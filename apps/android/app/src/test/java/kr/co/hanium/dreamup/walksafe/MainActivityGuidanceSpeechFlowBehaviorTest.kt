package kr.co.hanium.dreamup.walksafe

import org.junit.Assert.*
import org.junit.Test

/** Real Main callbacks with injected output; not microphone, GPS or acoustic evidence. */
class MainActivityGuidanceSpeechFlowBehaviorTest {
    @Test fun missingLocationWaitNeverSubmitsAutomaticSpeechOrInventsDone() {
        val main = MainActivity()
        val pending = install(main)
        for (now in listOf(4_999L, 5_000L, 6_000L, 60_000L)) {
            main.maybeSpeakGuidancePreparationWait(now, true, false,
                { _, _, _, _ -> fail("measurement wait must stay silent"); false }) { fail() }
        }
        assertFalse(pending.delivered)
        assertFalse(pending.exhausted)
        assertNull(pending.responseGeneration)
    }

    @Test fun anotherSentenceOrUserInputDoesNotCreateACompetingWaitResponse() {
        val main = MainActivity()
        val pending = install(main)
        for (busy in listOf(true, false)) repeat(20) {
            main.maybeSpeakGuidancePreparationWait(8_000L, true, busy,
                { _, _, _, _ -> fail("no automatic wait response"); false }) { fail("no synthetic failure") }
        }
        assertFalse(pending.delivered)
        assertNull(pending.responseGeneration)
    }

    @Test fun ordinaryRouteAndLocationPreparationBothRemainSilent() {
        val main = MainActivity()
        val pending = install(main)
        for (locationWaiting in listOf(false, true)) repeat(20) {
            main.maybeSpeakGuidancePreparationWait(5_000L + it * 1_000L, locationWaiting, false,
                { _, _, _, _ -> fail("preparation is silent"); false }) { fail() }
        }
        assertFalse(pending.delivered)
        assertFalse(pending.exhausted)
    }

    @Test fun locationRecoveryCannotLeaveAnEarlierAutomaticWaitQueued() {
        val main = MainActivity()
        val pending = install(main)
        var waiting = true
        val speak: (String, () -> Boolean, () -> Unit, () -> Unit) -> Boolean =
            { _, _, _, _ -> fail("no wait should reach READY or native START"); false }
        main.maybeSpeakGuidancePreparationWait(5_000L, true, false, speak,
            locationStillWaiting = { waiting }, publishFailure = { fail() })
        waiting = false
        main.maybeSpeakGuidancePreparationWait(8_000L, false, false, speak,
            locationStillWaiting = { waiting }, publishFailure = { fail() })
        assertFalse(pending.delivered)
        assertNull(pending.responseGeneration)
    }

    @Test fun actualPreparationAndFailureCallersUseActionsWithoutProgressNarration() {
        val source = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val preparation = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/start/GuidancePreparationController.kt").readText()
        assertTrue(MainActivityRefactorSource.member(source, "beginGuidancePreparationSpeech")
            .contains("guidancePreparationController.begin(destination)"))
        val preparationPorts = MainActivityRefactorSource.composition(source, "guidancePreparationController")
        assertTrue(preparationPorts.contains("override fun locationWaiting() = positionGuidancePaused || currentRouteLocationOrNull() == null"))
        val begin = MainActivityRefactorSource.member(preparation, "begin")
        assertTrue(begin.contains("maybeSpeakWait("))
        val mainWaiting = MainActivityRefactorSource.member(source, "maybeSpeakGuidancePreparationWait")
        assertTrue(mainWaiting.contains("positionGuidancePaused || currentRouteLocationOrNull() == null"))
        assertTrue(mainWaiting.contains("guidancePreparationController.maybeSpeakWait("))
        val waiting = MainActivityRefactorSource.member(preparation, "maybeSpeakWait")
        assertTrue(waiting.contains(") = Unit"))
        assertFalse(waiting.contains("submitSpeech(message"))
        assertFalse(waiting.contains("delivery.begin("))
        assertFalse(begin.contains("speakCommandResponse("))
        for (entry in listOf(begin, waiting)) {
            assertFalse(entry.contains("현재 위치를 더 정확히 확인하고 있습니다."))
            assertFalse(entry.contains("현재 위치를 확인하는 동안 방향 안내"))
        }
        assertFalse(source.contains("현재 위치를 더 정확히 확인하고 있습니다."))
        assertFalse(source.contains("현재 위치를 확인하는 동안 방향 안내"))
        val prewalk = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/start/NativePrewalkController.kt").readText()
        val prewalkPorts = MainActivityRefactorSource.composition(source, "nativePrewalkController")
        assertTrue(MainActivityRefactorSource.member(source, "announceNativePrewalkAdjustmentIfNeeded")
            .contains("nativePrewalkController.announceAdjustment(request)"))
        val adjustment = MainActivityRefactorSource.member(prewalk, "announceAdjustment")
        assertTrue(adjustment.contains("val detail = checks.cameraAdjustmentDetail()"))
        assertTrue(adjustment.contains("speech.submit(detail, ::current,"))
        assertTrue(adjustment.contains("adjustmentSpeech.complete(attempt)"))
        assertTrue(prewalkPorts.contains("override fun cameraAdjustmentDetail() = officialEnvironmentMeasurementDetail(currentOfficialEnvironmentAssessment(), false)"))
        val submit = ReportStaticSourceInspector.blockAfter(prewalkPorts, "object : NativePrewalkController.Speech")
        assertTrue(submit.contains("message = message, statusExplanation = true, additionalCurrent = current"))
        assertTrue(submit.contains("onCompleted = completed, onFailed = failed, onCancelled = cancelled, onRetired = retired"))
        assertFalse(adjustment.contains("SHAKE_OUTSIDE_APPROVED_RANGE"))
        assertTrue(MainActivityRefactorSource.member(source, "announceNativePrewalkFailureIfNeeded")
            .contains("nativePrewalkController.announceFailure(request)"))
        val failure = MainActivityRefactorSource.member(prewalk, "announceFailure")
        assertTrue(prewalkPorts.contains("officialEnvironmentFailureSpeech(currentOfficialEnvironmentAssessment())"))
        assertTrue(failure.contains("val reason = checks.failureSpeech()"))
        assertTrue(failure.contains("speech.submit(reason, ::current,"))
        assertTrue(failure.contains("failureSpeech.complete(attempt)"))
        val mountingPorts = MainActivityRefactorSource.property(source, "phoneMountingSession")
        assertTrue(mountingPorts.contains("PhoneMountingSessionController("))
        assertTrue(MainActivityRefactorSource.member(source, "applyPhoneMountingRuntimeAssessment")
            .contains("phoneMountingSession.applyPhoneMountingRuntimeAssessment("))
        val correction = ReportStaticSourceInspector.functionBlock(mountingPorts, "override fun correction")
        val unusable = ReportStaticSourceInspector.functionBlock(mountingPorts, "override fun unusable")
        assertEquals(1, Regex("phoneMountingFailureSpeech\\(assessment\\)").findAll(correction).count())
        assertEquals(1, Regex("phoneMountingFailureSpeech\\(assessment\\)").findAll(unusable).count())
        val runtime = MainActivityRefactorSource.function(source, "private fun applyOfficialEnvironmentRuntimeDecision(")
        val runtimePorts = MainActivityRefactorSource.composition(source, "officialEnvironmentRuntimeController")
        assertTrue(runtimePorts.contains("officialEnvironmentFailureSpeech(assessment, factors)"))
        assertTrue(runtimePorts.contains("enterWalkSessionSafetyStopAndCancelOutputs(reason)"))
        assertTrue(runtime.contains("if (OfficialEnvironmentFactor.GPS_QUALITY in newlyUnavailable)"))
        assertTrue(runtime.contains("effects.restrictGpsNavigationAndCancelFeedback()"))
        assertTrue(runtime.contains("val notWarned = (warningFactors - deliveredWarningFactors)"))
        assertTrue(runtime.contains("effects.failureSpeech(facts.currentAssessment(facts.lifecycle().epoch, now), notWarned)"))
        assertTrue(runtime.contains("effects.speakRecheckExplanation(message, stillLimited"))
        val stop = runtime.substringAfter("OfficialEnvironmentRuntimeAction.SAFE_STOP ->")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(stop,
            "val speech = effects.failureSpeech(", "effects.enterSafetyStop(",
            "effects.speakStatusExplanation(speech + \" 보행 안내를 중지했습니다.",
        ))
        val startCamera = MainActivityRefactorSource.function(source, "private fun startOfficialEnvironmentCameraPreflight(")
        assertTrue(startCamera.contains("effects.observeUnavailableCamera(epoch, nowMs(), null, failure)"))
        val cameraPorts = MainActivityRefactorSource.composition(source, "officialEnvironmentPreflightController")
        assertTrue(cameraPorts.contains("observeOfficialEnvironmentCameraFrame(epoch, observedAtMs, frameAvailable, diagnosticReasonOverride = reason)"))
        assertFalse(startCamera.contains("officialEnvironmentCameraDiagnosticReason = startFailure"))
        val mainObserver = MainActivityRefactorSource.member(source, "observeOfficialEnvironmentCameraFrame")
        assertTrue(mainObserver.contains("officialEnvironmentCameraObserver.observe(observation, expectedCameraPreflightGeneration, diagnosticReasonOverride)"))
        val observationSource = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/device/environment/OfficialEnvironmentCameraObserver.kt").readText()
        val observer = MainActivityRefactorSource.member(observationSource, "observe")
        val observerPorts = MainActivityRefactorSource.composition(source, "officialEnvironmentCameraObserver")
        assertTrue(observerPorts.contains("override fun currentEpoch() = walkSessionLifecycle.snapshot().epoch"))
        val diagnostic = observer.indexOf("evidence.recordCameraDiagnostic(observation, admitted, diagnosticReasonOverride)")
        assertTrue(diagnostic >= 0)
        assertTrue(diagnostic < observer.indexOf("runOnUiThread {"))
        assertTrue(observer.indexOf("facts.currentEpoch() != epoch") in 0 until diagnostic)
        assertTrue(observer.indexOf("observationGeneration != mounting.observationGeneration()") in 0 until diagnostic)
        val wrapper = MainActivityRefactorSource.member(source, "observeOfficialEnvironmentCameraFrame", occurrence = 1)
        assertTrue(wrapper.contains("diagnosticReasonOverride: String? = null"))
        assertTrue(wrapper.contains("diagnosticReasonOverride = diagnosticReasonOverride"))


    }

    @Test fun routeInstallationOrOwnerReplacementCannotCreateALateWaitCallback() {
        val main = MainActivity()
        var current = true
        val old = install(main) { current }
        main.maybeSpeakGuidancePreparationWait(5_000L, true, false,
            { _, _, _, _ -> fail("no callback is registered"); false }) { fail() }
        current = false
        val replacement = install(main)
        main.maybeSpeakGuidancePreparationWait(6_000L, false, false,
            { _, _, _, _ -> fail("replacement remains silent"); false }) { fail() }
        assertFalse(old.delivered)
        assertFalse(replacement.delivered)
        assertNull(old.responseGeneration)
        assertNull(replacement.responseGeneration)
    }

    @Test fun repeatedWaitingCannotProduceAFalseSpeechFailureOrDone() {
        val main = MainActivity()
        val pending = install(main)
        var attempts = 0
        var failures = 0
        repeat(8) { main.maybeSpeakGuidancePreparationWait(5_000L, true, false,
            { _, _, _, _ -> attempts++; false }) { failures++ } }
        assertEquals(0, attempts)
        assertEquals(0, failures)
        assertFalse(pending.delivered)
        assertFalse(pending.exhausted)
    }

    @Test fun recoveryIsInTheActionAndOnlyItsActualCompletionCommitsOnce() {
        val main = MainActivity()
        var notices = 0
        var actions = 0
        var done: (() -> Unit)? = null
        val recovery = kr.co.hanium.dreamup.walksafe.navigation.session.RouteGuidanceController.RouteRecoverySpeech({ true }) { notices++ }
        assertTrue(main.dispatchRouteActionWithRecovery("3시 방향으로 맞춰 주세요.", recovery,
            { true }, { actions++ }, { fail() }) { text, valid, completed, _ ->
            assertEquals("안내를 이어갑니다. 3시 방향으로 맞춰 주세요.", text)
            assertTrue(valid())
            done = completed
            true
        })
        assertEquals(0, notices)
        done!!.invoke()
        done!!.invoke()
        assertEquals(1, notices)
        assertEquals(1, actions)
    }

    @Test fun retiredRecoveryDoesNotCommitOrChangeTheStillCurrentAction() {
        val main = MainActivity()
        var currentRecovery = true
        var done: (() -> Unit)? = null
        var actions = 0
        val recovery = kr.co.hanium.dreamup.walksafe.navigation.session.RouteGuidanceController.RouteRecoverySpeech({ currentRecovery }) { fail("old episode") }
        main.dispatchRouteActionWithRecovery("12시 방향입니다.", recovery, { true }, { actions++ }, { fail() }) {
            _, _, completed, _ -> done = completed; true
        }
        currentRecovery = false
        done!!.invoke()
        assertEquals(1, actions)
        main.dispatchRouteActionWithRecovery("9시 방향입니다.", recovery, { true }, { actions++ }, { fail() }) {
            text, _, completed, _ -> assertEquals("9시 방향입니다.", text); completed(); true
        }
        assertEquals(2, actions)
    }

    @Test fun cancelledOrRejectedRecoveryActionCannotCommitADoneThatArrivesLater() {
        val main = MainActivity()
        var failures = 0
        var lateDone: (() -> Unit)? = null
        val recovery = kr.co.hanium.dreamup.walksafe.navigation.session.RouteGuidanceController.RouteRecoverySpeech({ true }) { fail("not delivered") }
        assertFalse(main.dispatchRouteActionWithRecovery("경로 방향입니다.", recovery,
            { true }, { fail("failed action") }, { failures++ }) { _, _, done, failed ->
            lateDone = done; failed(); false
        })
        lateDone!!.invoke()
        assertEquals(1, failures)
    }

    @Test fun recoverySurvivesPresentationTicksWhileBusyUntilTheLatestActionActuallyCompletes() {
        val main = MainActivity()
        var episodeCurrent = true
        var delivered = 0
        val recovery = kr.co.hanium.dreamup.walksafe.navigation.session.RouteGuidanceController.RouteRecoverySpeech({ episodeCurrent }) { delivered++; episodeCurrent = false }
        main.updateRouteRecoverySpeech(true) { recovery }
        repeat(10) { main.updateRouteRecoverySpeech(false) { fail("presentation must not create notices"); null } }
        val pending = MainActivityControllerTestFixture.field("pendingRouteRecoverySpeech").apply { isAccessible = true }
        assertSame(recovery, pending.get(main))
        var done: (() -> Unit)? = null
        main.dispatchRouteActionWithRecovery("3시 방향입니다.", pending.get(main) as kr.co.hanium.dreamup.walksafe.navigation.session.RouteGuidanceController.RouteRecoverySpeech,
            { true }, {}, { fail() }) { text, _, completed, _ ->
            assertEquals("안내를 이어갑니다. 3시 방향입니다.", text)
            done = completed; true
        }
        assertEquals(0, delivered)
        done!!.invoke()
        main.updateRouteRecoverySpeech(false) { fail(); null }
        assertNull(pending.get(main))
        assertEquals(1, delivered)
    }

    @Test fun presentationTickDropsAnOldOwnerAndNeverInventsAnInitialRecovery() {
        val main = MainActivity()
        var current = true
        val recovery = kr.co.hanium.dreamup.walksafe.navigation.session.RouteGuidanceController.RouteRecoverySpeech({ current }) { fail("old owner") }
        main.updateRouteRecoverySpeech(true) { recovery }
        current = false
        main.updateRouteRecoverySpeech(false) { fail(); null }
        val pending = MainActivityControllerTestFixture.field("pendingRouteRecoverySpeech").apply { isAccessible = true }
        assertNull(pending.get(main))
        main.updateRouteRecoverySpeech(false) { fail("initial notice must come from a producer"); recovery }
        assertNull(pending.get(main))
    }

    @Test fun actualSpeechRecognitionAndDeferredInputWaitBeforeAnyOutputObservation() {
        val main = MainActivity()
        val input = MainActivityControllerTestFixture.field("voiceRecognitionActive").apply { isAccessible = true }
        input.setBoolean(main, true)
        assertTrue(main.isGuidanceSpeechInputOrOutputBusy { fail("actual recording owns input"); false })
        input.setBoolean(main, false)
        val deferred = MainActivityControllerTestFixture.field("deferredVoiceRecognitionStart").apply { isAccessible = true }
        deferred.set(main, Runnable {})
        assertTrue(main.isGuidanceSpeechInputOrOutputBusy { fail("pending input owns the next turn"); false })
        deferred.set(main, null)
        assertTrue(main.isGuidanceSpeechInputOrOutputBusy { true })
        assertFalse(main.isGuidanceSpeechInputOrOutputBusy { false })
    }

    @Test fun silentWaitingDoesNotAcquireOrReleaseAnotherResponseGeneration() {
        val main = MainActivity()
        val pending = install(main)
        val generation = MainActivityControllerTestFixture.field("commandSpeechResponseGeneration")
            .apply { isAccessible = true }
        generation.setLong(main, 19L)
        main.maybeSpeakGuidancePreparationWait(5_000L, true, false,
            { _, _, _, _ -> fail(); false }) { fail() }
        assertEquals(19L, generation.getLong(main))
        assertFalse(pending.delivered)
        assertNull(pending.responseGeneration)
    }

    @Test fun retiringAnOwnedWaitLeavesTheUsersDeferredMicrophoneContinuationIntact() {
        val main = MainActivity()
        val pending = install(main)
        main.maybeSpeakGuidancePreparationWait(5_000L, true, false, { _, _, _, _ -> true }) { fail() }
        val deferred = MainActivityControllerTestFixture.field("deferredVoiceRecognitionStart").apply { isAccessible = true }
        val userInput = Runnable {}
        deferred.set(main, userInput)
        main.cancelGuidancePreparationSpeech()
        assertNull(pending.responseGeneration)
        assertSame(userInput, deferred.get(main))
    }

    @Test fun waitRetirementKeepsTheGenerationCapturedByTheRealDeferredInputFence() {
        val main = MainActivity()
        install(main)
        main.maybeSpeakGuidancePreparationWait(5_000L, true, false, { _, _, _, _ -> true }) { fail() }
        val generation = MainActivityControllerTestFixture.field("commandSpeechResponseGeneration").apply { isAccessible = true }
        val capturedGeneration = generation.getLong(main)
        val deferred = MainActivityControllerTestFixture.field("deferredVoiceRecognitionStart").apply { isAccessible = true }
        var currentAtNextPoll = false
        val poll = Runnable { currentAtNextPoll = generation.getLong(main) == capturedGeneration }
        deferred.set(main, poll)
        main.cancelGuidancePreparationSpeech()
        (deferred.get(main) as Runnable).run()
        assertTrue("wait retirement must preserve the existing input poll's response-generation fence", currentAtNextPoll)
    }

    @Test fun confirmedPauseRecoveryRequestsOneFreshCueButNotADecisionOrAnOldOwner() {
        val main = MainActivity()
        val resume = MainActivityControllerTestFixture.field("routeGuidanceResumePending").apply { isAccessible = true }
        resume.setBoolean(main, true)
        val waiting = kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorUpdate(
            null, false, false, false, "guidance_already_completed")
        val fresh = waiting.copy(instruction = "경로 시작점은 3시 방향입니다.", reason = "route_guidance")
        assertSame(fresh, main.routeSpeechUpdateForRecovery(waiting, { true }) { fresh })
        assertSame(waiting, main.routeSpeechUpdateForRecovery(waiting, { false }) { fail("old epoch"); fresh })
        val decision = waiting.copy(pendingUserDecision = kr.co.hanium.dreamup.walksafe.navigation.RouteNavigatorUserDecision.REROUTE)
        assertSame(decision, main.routeSpeechUpdateForRecovery(decision, { true }) { fail("user choice owns flow"); fresh })
        resume.setBoolean(main, false)
        assertSame(waiting, main.routeSpeechUpdateForRecovery(waiting, { true }) { fail("recovery already delivered"); fresh })
    }

    private fun install(main: MainActivity, current: () -> Boolean = { true }): kr.co.hanium.dreamup.walksafe.navigation.start.GuidancePreparationController.PendingGuidancePreparationSpeech =
        kr.co.hanium.dreamup.walksafe.navigation.start.GuidancePreparationController.PendingGuidancePreparationSpeech(1_000L, current).also { pending ->
            MainActivityControllerTestFixture.field("pendingGuidancePreparationSpeech").apply { isAccessible = true }
                .set(main, pending)
            MainActivityControllerTestFixture.field("positionGuidancePaused").apply { isAccessible = true }
                .setBoolean(main, true)
        }
}
