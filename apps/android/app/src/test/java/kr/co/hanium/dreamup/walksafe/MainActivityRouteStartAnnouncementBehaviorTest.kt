package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.navigation.InstalledRouteStartSpeechCoordinator

import org.junit.Assert.*
import org.junit.Test

/** Actual Main arbitration; injected admission/TTS callbacks are not device GPS or audio evidence. */
class MainActivityRouteStartAnnouncementBehaviorTest {
    @Test fun noStartSpeechOrInstructionsBeforeUsableLocation() {
        val main = MainActivity()
        install(main) { true }
        assertTrue(main.holdStartForTest(
            locationReady = { false }, submitSpeech = { _, _, _ -> fail("location is not ready"); false },
            resumeInstructions = { fail("location is not ready") }))
    }

    @Test fun usableStartIsSubmittedOnceAndInstructionsWaitForItsTerminalCallback() {
        val main = MainActivity()
        install(main) { true }
        var submissions = 0
        var resumes = 0
        var terminal: (() -> Unit)? = null
        val submit: (String, () -> Boolean, () -> Unit) -> Boolean = { _, current, done ->
            assertTrue(current()); submissions++; terminal = done; true
        }
        repeat(3) { assertTrue(main.holdStartForTest(
            { true }, submit, { resumes++ })) }
        assertEquals(1, submissions)
        assertEquals(0, resumes)
        terminal!!.invoke()
        terminal!!.invoke()
        assertEquals(1, resumes)
        assertFalse(main.holdStartForTest({ true }, submit, { resumes++ }))
    }

    @Test fun lateTerminalCannotResumeAfterLocationDegrades() {
        val main = MainActivity()
        install(main) { true }
        var ready = true
        var terminal: (() -> Unit)? = null
        main.holdStartForTest({ ready }, { _, _, done -> terminal = done; true },
            { fail("location degraded") })
        ready = false
        terminal!!.invoke()
    }

    @Test fun lateTerminalCannotClearAReplacementOrSpeakItsOldRoute() {
        val main = MainActivity()
        var current = true
        install(main) { current }
        var terminal: (() -> Unit)? = null
        var speechCurrent: (() -> Boolean)? = null
        main.holdStartForTest({ true }, { _, guard, done ->
            terminal = done; speechCurrent = guard; true
        }, { fail("old route") })
        current = false
        val replacement = install(main) { true }
        assertFalse(speechCurrent!!.invoke())
        terminal!!.invoke()
        assertSame(replacement, pending(main))
    }

    @Test fun rejectedSubmissionReleasesItsOwnPendingAndRetriesCurrentInstructionsOnce() {
        val main = MainActivity()
        install(main) { true }
        var resumes = 0
        assertTrue(main.holdStartForTest({ true }, { _, _, _ -> false }, { resumes++ }))
        assertNull(pending(main))
        assertEquals(1, resumes)
    }

    @Test fun changedOwnerDropsPendingBeforeAnyEffect() {
        val main = MainActivity()
        install(main) { false }
        assertFalse(main.holdStartForTest({ fail("changed owner"); false },
            { _, _, _ -> fail("changed owner"); false }, { fail("changed owner") }))
        assertNull(pending(main))
    }

    @Test fun ordinaryInstalledWaitStaysSilentAndPreservesStartUntilLocationIsUsable() {
        val main = MainActivity()
        val owner = install(main) { true }
        repeat(10) {
            assertTrue(main.holdStartForTest({ false },
                { _, _, _ -> fail("ordinary preparation must be silent"); false },
                { fail("location is not usable") }))
        }
        assertSame(owner, pending(main))
        var starts = 0
        var resumes = 0
        var done: (() -> Unit)? = null
        assertTrue(main.holdStartForTest({ true }, { _, current, terminal ->
            assertTrue(current()); starts++; done = terminal; true
        }, { resumes++ }))
        assertEquals(1, starts)
        assertEquals(0, resumes)
        done!!.invoke()
        assertEquals(1, resumes)
        assertNull(pending(main))
    }

    @Test fun silentInstalledWaitCannotFollowAnOldOwnerOrClearItsReplacement() {
        val main = MainActivity()
        var current = true
        install(main) { current }
        assertTrue(main.holdStartForTest({ false }, { _, _, _ -> fail("waiting"); false }))
        current = false
        assertFalse(main.holdStartForTest({ fail("old owner"); false },
            { _, _, _ -> fail("old owner"); false }))
        assertNull(pending(main))
        val replacement = install(main) { true }
        assertTrue(main.holdStartForTest({ false }, { _, _, _ -> fail("waiting"); false }))
        assertSame(replacement, pending(main))
    }

    @Test fun actualStartCompletionClearsOnlyItsOwnPreviousWaitingSpeechFailure() {
        val main = MainActivity()
        val owner = install(main) { true }
        assertTrue(coordinator(main).inheritWaitingFailure("waiting speech failed"))
        assertSame(owner, coordinator(main).failureOwner)
        set(main, "nativeGuidanceSpeechFailure", "waiting speech failed")
        var refreshed = 0
        assertTrue(main.clearWaitingRouteSpeechFailureIfCurrent({ true }, { refreshed++ }))
        assertNull(field(main, "nativeGuidanceSpeechFailure"))
        assertEquals(1, refreshed)
    }

    @Test fun startCompletionDoesNotClearAChangedOwnerOrAnotherFailure() {
        val main = MainActivity()
        val old = install(main) { true }
        assertTrue(coordinator(main).inheritWaitingFailure("keep this failure"))
        assertSame(old, coordinator(main).failureOwner)
        set(main, "nativeGuidanceSpeechFailure", "keep this failure")
        install(main) { true }
        assertFalse(main.clearWaitingRouteSpeechFailureIfCurrent({ true }, { fail() }))
        assertEquals("keep this failure", field(main, "nativeGuidanceSpeechFailure"))
        coordinator(main).clearFailureOwner()
        assertFalse(main.clearWaitingRouteSpeechFailureIfCurrent({ true }, { fail() }))
        val current = pending(main)!!
        assertTrue(coordinator(main).inheritWaitingFailure("a different waiting failure"))
        assertSame(current, coordinator(main).failureOwner)
        assertFalse(main.clearWaitingRouteSpeechFailureIfCurrent({ true }, { fail() }))
        assertEquals("keep this failure", field(main, "nativeGuidanceSpeechFailure"))
    }

    @Test fun successfulRouteCueClearsOwnedWaitFailureAfterInterruptedStartHasRetired() {
        val main = MainActivity()
        val owner = install(main) { true }
        assertTrue(coordinator(main).inheritWaitingFailure("waiting speech failed"))
        assertSame(owner, coordinator(main).failureOwner)
        set(main, "nativeGuidanceSpeechFailure", "waiting speech failed")
        var terminal: (() -> Unit)? = null
        main.holdStartForTest({ true }, { _, _, done -> terminal = done; true })
        terminal!!.invoke()
        assertNull(pending(main))
        assertTrue(main.clearWaitingRouteSpeechFailureIfCurrent({ true }, {}))
        assertNull(field(main, "nativeGuidanceSpeechFailure"))
    }

    @Test fun initialJoiningDoesNotBlockTheNavigatorsStartPointReference() {
        val main = MainActivity()
        val pending = install(main) { true }
        repeat(3) {
            assertFalse(main.holdStartForTest(
                locationReady = { false },
                submitSpeech = { _, _, _ -> fail("unjoined route summary must wait"); false },
                resumeInstructions = { fail("location is not ready") },
                initialJoinPending = true,
            ))
        }
        assertSame(pending, pending(main))
        var submitted = 0
        assertTrue(main.holdStartForTest(
            { true }, { _, _, _ -> submitted++; true }, { fail("not completed") }, initialJoinPending = false))
        assertEquals(1, submitted)
    }

    @Test fun changedOwnerCannotBypassTheStartGateForAnOldJoinReference() {
        val main = MainActivity()
        install(main) { false }
        assertFalse(main.holdStartForTest(
            locationReady = { fail("old owner"); false },
            submitSpeech = { _, _, _ -> fail("old owner"); false },
            initialJoinPending = true,
        ))
        assertNull(pending(main))
    }

    @Test fun preparationWaitFailureClearsOnlyAfterTheActualInstalledStartSucceeds() {
        val main = MainActivity()
        val preparation = kr.co.hanium.dreamup.walksafe.navigation.start.GuidancePreparationController.PendingGuidancePreparationSpeech(0L) { true }
        preparation.failureMessage = "old preparation wait failure"
        set(main, "nativeGuidancePreparationSpeechFailureOwner", preparation)
        set(main, "nativeGuidanceSpeechFailure", preparation.failureMessage)
        install(main) { true }
        main.inheritGuidancePreparationSpeechFailure(preparation)
        assertEquals(preparation.failureMessage, field(main, "nativeGuidanceSpeechFailure"))
        assertTrue(main.clearWaitingRouteSpeechFailureIfCurrent({ true }, {}))
        assertNull(field(main, "nativeGuidanceSpeechFailure"))
    }

    @Test fun preparationWaitFailureCannotBeTransferredFromAnOldOwnerOrOverwriteAnotherFailure() {
        val main = MainActivity()
        val preparation = kr.co.hanium.dreamup.walksafe.navigation.start.GuidancePreparationController.PendingGuidancePreparationSpeech(0L) { true }
        preparation.failureMessage = "wait failure"
        val old = kr.co.hanium.dreamup.walksafe.navigation.start.GuidancePreparationController.PendingGuidancePreparationSpeech(0L) { true }
        set(main, "nativeGuidancePreparationSpeechFailureOwner", old)
        set(main, "nativeGuidanceSpeechFailure", preparation.failureMessage)
        install(main) { true }
        main.inheritGuidancePreparationSpeechFailure(preparation)
        assertNull(coordinator(main).failureOwner)
        set(main, "nativeGuidancePreparationSpeechFailureOwner", preparation)
        set(main, "nativeGuidanceSpeechFailure", "another output failure")
        main.inheritGuidancePreparationSpeechFailure(preparation)
        assertNull(coordinator(main).failureOwner)
        assertEquals("another output failure", field(main, "nativeGuidanceSpeechFailure"))
    }

    @Test fun installedStartWaitsForMicrophoneOrPreviousSpeechWithoutConsumingItsMetadata() {
        val main = MainActivity()
        val owner = install(main) { true }
        var busy = true
        var retries = 0
        var submissions = 0
        val submit: (String, () -> Boolean, () -> Unit) -> Boolean = { _, _, _ -> submissions++; true }
        assertTrue(main.holdStartForTest(
            { true }, submit, { retries++ }, speechBusy = { busy }, deferInstructions = { retries++ }))
        assertSame(owner, pending(main))
        assertEquals(0, submissions)
        assertEquals(1, retries)
        busy = false
        main.holdStartForTest({ true }, submit, { retries++ }, speechBusy = { busy })
        assertEquals(1, submissions)
    }

    @Test fun busyStartDefersPollingAndResumesImmediatelyOnlyAfterTheActualTerminal() {
        val main = MainActivity()
        install(main) { true }
        var busy = true
        var deferred = 0
        var resumed = 0
        var terminal: (() -> Unit)? = null
        val submit: (String, () -> Boolean, () -> Unit) -> Boolean = { _, _, done -> terminal = done; true }
        repeat(2) { main.holdStartForTest({ true }, submit, { resumed++ },
            speechBusy = { busy }, deferInstructions = { deferred++ }) }
        assertEquals(2, deferred)
        assertEquals(0, resumed)
        assertNull(terminal)
        busy = false
        main.holdStartForTest({ true }, submit, { resumed++ }, speechBusy = { busy }, deferInstructions = { fail() })
        assertEquals(0, resumed)
        terminal!!.invoke()
        assertEquals(1, resumed)
    }

    @Test fun actualStartArbitrationContainsNoAutomaticWaitingNarration() {
        val source = java.io.File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val adapter = MainActivityRefactorSource.function(source, "internal fun holdRouteInstructionsForStartAnnouncement(")
        assertTrue(adapter.contains("installedRouteStartSpeechCoordinator.hold("))
        assertTrue(adapter.contains("onStage = effects::preparationStage"))
        val ports = MainActivityRefactorSource.composition(source, "routeGuidanceController")
        assertTrue(ports.contains("val activityEffects = guides.effects()"))
        assertTrue(ports.contains("override fun preparationStage(stage: String) = activityEffects.preparationStage(stage)"))
        val assembly = MainActivityRefactorSource.factory(source, "createNavigationSessions")
        val effects = ReportStaticSourceInspector.blockAfter(
            assembly.substringAfter("guides = GuidanceAssemblyBindings("), "effects =")
        assertTrue(effects.contains("override fun preparationStage(stage: String) = recordGuidancePreparationStage(stage)"))
        val arbitration = MainActivityRefactorSource.member(java.io.File(
            "src/main/java/kr/co/hanium/dreamup/walksafe/navigation/InstalledRouteStartSpeechCoordinator.kt").readText(), "hold")
        assertTrue(arbitration.contains("if (!ready())"))
        assertFalse(arbitration.contains("notifyInstalledRouteWaiting"))
        assertFalse(arbitration.contains("현재 위치와 경로 연결을 확인하고 있습니다."))
        assertFalse(source.contains("현재 위치와 경로 연결을 확인하고 있습니다. 확인되면 길안내를 시작합니다."))
        assertFalse(source.contains("internal fun notifyInstalledRouteWaiting("))
        assertTrue(arbitration.contains("submitSpeech(owner.message, ::ready, terminal)"))
        assertTrue(arbitration.contains("if (ready()) resumeInstructions()"))
    }

    /** Fixed platform observations only; all arbitration and callbacks execute in the real Main. */
    private fun MainActivity.holdStartForTest(
        locationReady: () -> Boolean,
        submitSpeech: (String, () -> Boolean, () -> Unit) -> Boolean,
        resumeInstructions: () -> Unit = {},
        initialJoinPending: Boolean = false,
        speechBusy: () -> Boolean = { false },
        deferInstructions: () -> Unit = {},
    ): Boolean = holdRouteInstructionsForStartAnnouncement(
        locationReady, submitSpeech, resumeInstructions, initialJoinPending, speechBusy, deferInstructions,
    )

    private fun set(main: MainActivity, name: String, value: Any?) = MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.set(main, value)
    private fun field(main: MainActivity, name: String): Any? = MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.get(main)

    private fun coordinator(main: MainActivity): InstalledRouteStartSpeechCoordinator =
        field(main, "installedRouteStartSpeechCoordinator") as InstalledRouteStartSpeechCoordinator
    private fun install(main: MainActivity, current: () -> Boolean): InstalledRouteStartSpeechCoordinator.PendingStart =
        coordinator(main).install("route start", current)
    private fun pending(main: MainActivity): InstalledRouteStartSpeechCoordinator.PendingStart? = coordinator(main).pending
}
