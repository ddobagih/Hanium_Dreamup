package kr.co.hanium.dreamup.walksafe.voice.composition

import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.concurrent.Executor
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.feedback.delivery.*
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController.NativeUiPage
import kr.co.hanium.dreamup.walksafe.voice.*
import kr.co.hanium.dreamup.walksafe.voice.input.*
import kr.co.hanium.dreamup.walksafe.voice.interaction.*
import kr.co.hanium.dreamup.walksafe.voice.output.*
import org.junit.Assert.*
import org.junit.Test

/** Runs real input/output owners and actuator; controlled effects are not physical output evidence. */
class VoiceCompositionBehaviorTest {
    @Test fun peerConstructionNeverResolvesAnOwnerOrCreatesNativeOutput() {
        var calls = 0
        val failInput = { calls++; error("input queried in construction") as VoiceInputSessionController }
        val failSpeech = { calls++; error("speech queried in construction") as SpeechOutputController }
        SpeechInputPeerAdapter(failInput, { calls++; error("interaction queried") })
        InteractionInputPeerAdapter(failInput)
        VoiceInputSpeechPeerAdapter(failSpeech, { calls++; error("risk queried") }, { calls++; 1 }, { calls++; false })
        InteractionSpeechPeerAdapter(failSpeech, { calls++; error("guidance queried") }, { calls++ })
        RiskVoicePeerAdapter(failSpeech, failInput)
        GuidanceSpeechPeerAdapter(failSpeech, failInput, { calls++; false }, { calls++ })
        val f = Fixture()
        assertEquals(0, calls); assertEquals(0, f.inputQueries)
        assertTrue(f.runtimes.isEmpty()); assertEquals(0, f.clock.pendingTasks)
    }

    @Test fun speechInputAdapterMutatesTheSameActualPromptOwner() {
        val f = Fixture(); val peer = SpeechInputPeerAdapter({ f.voiceInput }, { f.interactions })
        peer.promptState(CommandResponseToken("one", 1), CommandPromptEffect.PREPARING)
        assertTrue(f.voiceInput.state.promptPending)
        peer.promptState(CommandResponseToken("two", 2), CommandPromptEffect.ORDINARY_RESPONSE)
        assertFalse(f.voiceInput.state.promptPending)
    }

    @Test fun interactionInputAdapterUsesSameGenerationAndPromptReadyState() {
        val f = Fixture(); val peer = InteractionInputPeerAdapter { f.voiceInput }
        peer.completePreparedPrompt(4)
        assertEquals(4, f.voiceInput.state.promptReadyGeneration)
        assertEquals(f.voiceInput.state.recognitionGeneration, peer.generation())
        peer.clearPromptReady(); assertNull(f.voiceInput.state.promptReadyGeneration)
    }

    @Test fun preparingOutputDoesNotCompleteUntilActualNativeDone() {
        val f = Fixture(); val peer = VoiceInputSpeechPeerAdapter({ f.controller }, { error("risk not used") }, { f.clock.now }, { false })
        var completed = 0; var failed = 0
        assertTrue(peer.speakPreparingInput("마친 뒤 말씀해 주세요.", { completed++ }, { failed++ }))
        assertTrue(f.voiceInput.state.promptPending); assertEquals(0, completed)
        f.runtime.ready(); val utterance = f.runtime.spoken.single()
        f.runtime.start(utterance.id); assertEquals(0, completed)
        f.runtime.done(utterance.id); f.runtime.done(utterance.id)
        assertEquals(1, completed); assertEquals(0, failed)
    }

    @Test fun inputCleanupAfterCandidateDoneKeepsTheSameDialogAndHeardPageNumbers() {
        val f = Fixture(); val page = f.showCandidatePage()
        f.controller.beginCommandSpeech(21L)
        assertTrue(f.interactions.destinations.speakHomeDestinationVoicePrompt(page))
        f.completeCandidatePrompt()
        assertSame(page, f.interactions.destinations.currentVoiceDestinationDialogState())
        assertTrue(page.onCommand(DestinationSearchVoiceCommand.SelectCandidate(1)).accepted)
        assertEquals(CommandSpeechProgress.COMPLETED, f.controller.commandSpeechProgress(21L))
        val oldGeneration = f.controller.currentCommandGeneration()
        val last = f.runtime.spoken.last(); val count = f.runtime.spoken.size

        f.inputSpeechPeer().cancelCommandResponse()
        f.runtime.done(last.id); f.clock.advanceBy(1_000L)

        assertSame(page, f.interactions.destinations.currentVoiceDestinationDialogState())
        assertTrue(page.onCommand(DestinationSearchVoiceCommand.SelectCandidate(1)).accepted)
        assertEquals(oldGeneration + 1L, f.controller.currentCommandGeneration())
        assertFalse(f.controller.hasPendingCommandResponse())
        assertFalse(f.voiceInput.state.promptPending)
        assertNull(f.voiceInput.state.promptReadyGeneration)
        assertEquals(CommandSpeechProgress.COMPLETED, f.controller.commandSpeechProgress(21L))
        assertEquals(count, f.runtime.spoken.size)
    }

    @Test fun inputCleanupBeforeReadyOrBetweenCandidatePartsCannotGrantNumbersOrResumeLateDone() {
        val partCount = Fixture().showCandidatePage().voicePromptParts().size
        for (partsDone in -1 until partCount) {
            val f = Fixture(); val page = f.showCandidatePage()
            f.controller.beginCommandSpeech(22L)
            assertTrue(f.interactions.destinations.speakHomeDestinationVoicePrompt(page))
            if (partsDone >= 0) {
                f.runtime.ready()
                repeat(partsDone) { f.runtime.done(f.runtime.spoken.last().id) }
            }
            assertTrue(f.controller.hasPendingCommandResponse())
            assertFalse(page.onCommand(DestinationSearchVoiceCommand.SelectCandidate(1)).accepted)
            val late = f.runtime.spoken.lastOrNull(); val count = f.runtime.spoken.size

            f.inputSpeechPeer().cancelCommandResponse()
            if (partsDone < 0) f.runtime.ready() else f.runtime.done(late!!.id)
            f.clock.advanceBy(1_000L)

            assertNull(f.interactions.destinations.currentVoiceDestinationDialogState())
            assertFalse(page.onCommand(DestinationSearchVoiceCommand.SelectCandidate(1)).accepted)
            assertFalse(f.controller.hasPendingCommandResponse())
            assertEquals(CommandSpeechProgress.FAILED, f.controller.commandSpeechProgress(22L))
            assertEquals(count, f.runtime.spoken.size)
        }
    }

    @Test fun absentOrFailedOutputIsNotProofThatCandidateNumbersWereHeard() {
        for (failed in listOf(false, true)) {
            val f = Fixture(); val page = f.showCandidatePage()
            if (failed) {
                assertTrue(f.interactions.destinations.speakHomeDestinationVoicePrompt(page))
                f.runtime.ready(); f.runtime.failUtterance(f.runtime.spoken.single().id)
                assertSame(page, f.interactions.destinations.currentVoiceDestinationDialogState())
            }
            assertFalse(f.controller.hasPendingCommandResponse())
            f.inputSpeechPeer().cancelCommandResponse()
            assertFalse(page.onCommand(DestinationSearchVoiceCommand.SelectCandidate(1)).accepted)
            assertFalse(f.voiceInput.state.promptPending)
            if (failed) assertSame(page, f.interactions.destinations.currentVoiceDestinationDialogState())
            else assertNull(f.interactions.destinations.currentVoiceDestinationDialogState())
        }
    }

    @Test fun completedDialogStillClosesOnFullCancelPageExitBackgroundOwnerRetirementAndExpiry() {
        for (retirement in listOf("full_cancel", "page_exit", "background", "actor", "process", "expiry")) {
            val f = Fixture(); val page = f.showCandidatePage()
            assertTrue(f.interactions.destinations.speakHomeDestinationVoicePrompt(page))
            f.completeCandidatePrompt(); f.inputSpeechPeer().cancelCommandResponse()
            assertSame(page, f.interactions.destinations.currentVoiceDestinationDialogState())
            assertTrue(page.onCommand(DestinationSearchVoiceCommand.SelectCandidate(1)).accepted)
            when (retirement) {
                "full_cancel" -> f.controller.cancelCommandSpeechResponse()
                "page_exit" -> f.interactions.destinations.clearHomeDestinationVoiceDialog()
                "background" -> {
                    f.lifetime = f.lifetime.copy(foreground = false)
                    f.interactions.destinations.clearHomeDestinationVoiceDialog()
                }
                "actor" -> f.context = f.context.copy(actorId = "replacement")
                "process" -> f.processGeneration++
                "expiry" -> f.clock.advanceBy(30_000L)
            }
            assertNull(retirement, f.interactions.destinations.currentVoiceDestinationDialogState())
            assertFalse(retirement, page.onCommand(DestinationSearchVoiceCommand.SelectCandidate(1)).accepted)
            assertSame(retirement, page, f.search.state.voicePage)
        }
    }

    @Test fun actualDoneCompletesOriginalInteractionCallbackOnlyOnce() {
        val f = Fixture(); val peer = InteractionSpeechPeerAdapter({ f.controller }, { error("route not used") }, {})
        var completed = 0
        assertTrue(peer.speakCommandResponse("응답", false, { completed++ }, null, null))
        f.runtime.ready(); val utterance = f.runtime.spoken.single()
        f.runtime.done(utterance.id); f.runtime.done(utterance.id)
        assertEquals(1, completed); assertEquals(f.controller.currentCommandGeneration(), peer.responseGeneration())
    }

    @Test fun changedActorRetiresWaitingResponseWithoutCompletion() {
        val f = Fixture(); val peer = InteractionSpeechPeerAdapter({ f.controller }, { error("route not used") }, {})
        var completed = 0; var cancelled = 0
        peer.speakCommandResponse("응답", true, { completed++ }, null, { cancelled++ })
        f.context = f.context.copy(actorId = "replacement")
        f.clock.advanceBy(100)
        assertEquals(0, completed); assertEquals(1, cancelled)
        assertFalse(f.voiceInput.state.promptPending); assertTrue(f.runtime.spoken.isEmpty())
    }

    @Test fun nativeCancellationCannotClearReplacementPromptThroughNewAdapter() {
        val f = Fixture(); f.controller.ensureFeedbackActuator(); f.runtime.ready()
        f.controller.speakCommandResponse("이전", preparingInput = true)
        var completed = 0
        f.controller.speakCommandResponse("새 안내", preparingInput = true, onCompleted = { completed++ })
        val generation = f.controller.currentCommandGeneration()
        f.clock.advanceBy(0)
        assertEquals(generation, f.controller.currentCommandGeneration())
        assertTrue(f.voiceInput.state.promptPending)
        assertTrue(f.controller.hasPendingCommandResponse())
        f.runtime.done(f.runtime.spoken.last().id)
        assertEquals(1, completed)
    }

    @Test fun nativeBusyPathDoesNotReadFallbackClockOrTalkBackState() {
        val f = Fixture(); var clockReads = 0; var talkBackReads = 0
        val peer = VoiceInputSpeechPeerAdapter({ f.controller }, { error("risk not used") }, { clockReads++; f.clock.now }, { talkBackReads++; true })
        f.controller.speakCommandResponse("출력 중"); f.runtime.ready(); f.runtime.start(f.runtime.spoken.single().id)
        assertTrue(peer.nativeOrAccessibilitySpeechActive())
        assertEquals(0, clockReads); assertEquals(0, talkBackReads)
    }

    @Test fun fallbackReadsClockThenTalkBackOnceWithoutCreatingAnActor() {
        val f = Fixture(); val events = mutableListOf<String>()
        val peer = VoiceInputSpeechPeerAdapter({ f.controller }, { error("risk not used") }, { events += "clock"; 1000 }, { events += "talkback"; false })
        assertFalse(peer.nativeOrAccessibilitySpeechActive())
        assertEquals(listOf("clock", "talkback"), events); assertTrue(f.runtimes.isEmpty())
    }

    @Test fun currentStateCommandShortCircuitsBeforePageOrViewQueries() {
        var workflowReads = 0
        val adapter = VoiceInteractionPresentationAdapter(noop(), { workflowReads++; error("page not needed") },
            { error("screen not needed") }, { error("search UI not needed") }, { error("search not needed") }, { false }, noop(), {})
        adapter.showCommandPage(AndroidVoiceAction.SpeakCurrentState)
        assertEquals(0, workflowReads)
    }

    @Test fun destinationConfirmationRemainsOutsideOriginalVoicePageSet() {
        assertFalse(NativeUiPage.DESTINATION_CONFIRM.isVoicePage())
        assertTrue(NativeUiPage.DESTINATION_SEARCH.isVoicePage())
        assertTrue(NativeUiPage.VOICE_COMMAND.isVoicePage())
        assertFalse(NativeUiPage.GUIDANCE.isVoicePage())
    }

    @Test fun commandPageSelectionUsesSameActualWorkflowOwnerAndPreservesVoiceInteraction() {
        val calls = mutableListOf<String>()
        val workflow = NativeWorkflowController(noop(), noop { name, _ -> calls += name; null }, noop(), noop())
        val adapter = VoiceInteractionPresentationAdapter(noop(), { workflow }, { error("no views") },
            { error("no search UI") }, { error("no search") }, { false }, noop(), {})
        adapter.showCommandPage(AndroidVoiceAction.SpeakCurrentState)
        assertTrue(calls.isEmpty())
        workflow.nativeUiPage = NativeUiPage.GUIDANCE
        calls.clear()
        adapter.showCommandPage(AndroidVoiceAction.SpeakCurrentGuidance)
        assertEquals(NativeUiPage.GUIDANCE, workflow.nativeUiPage)
        assertFalse(calls.contains("displayPage"))
    }
    @Test fun routeAndCommandAdapterConstructorsDoNotResolveExistingDomainOwners() {
        var calls = 0
        VoiceRoutePeerAdapter({ calls++; error("acquisition") }, { calls++; error("archive") },
            { calls++; error("guidance") }, { calls++; error("compass") }, { calls++; error("position") },
            { calls++; error("entry") }, noop(), noop())
        VoiceInputCommandPeerAdapter({ calls++; error("interaction") }, { calls++; error("search") },
            { calls++; error("resume") }, { calls++; error("gateway walk") }, noop(),
            { calls++; error("speech") }, { calls++; false }, noop())
        assertEquals(0, calls)
    }

    @Test fun pendingDestinationQueryShowsWaitingStatusWithoutResolvingDialogSpeechOrHomeFacts() {
        val f = Fixture(); f.search.requestVoiceResults("도서관")
        var queries = 0; val statuses = mutableListOf<String>()
        val peer = VoiceInputCommandPeerAdapter({ queries++; error("dialog not needed") }, { f.search },
            { error("resume not needed") }, { error("takeover not needed") },
            noop { name, args -> if(name == "voiceStatus") statuses += args[0] as String; null },
            { queries++; error("speech not needed") }, { queries++; false }, noop())
        assertTrue(peer.handleDestinationPromptIfPresent())
        assertEquals(0, queries)
        assertEquals(listOf("목적지 검색이 끝나면 후보 안내를 듣고 말씀해 주세요."), statuses)
    }

    @Test fun absentDestinationDialogDoesNotReadHomeEligibilityOrCreateOutput() {
        val f = Fixture(); var homeReads = 0
        val peer = VoiceInputCommandPeerAdapter({ f.interactions }, { f.search }, { error("resume not needed") },
            { error("takeover not needed") }, noop(), { f.controller }, { homeReads++; false }, noop())
        assertFalse(peer.handleDestinationPromptIfPresent())
        assertEquals(0, homeReads); assertTrue(f.runtimes.isEmpty())
    }

    @Test fun readOnlyAnswerOnDestinationConfirmationUsesOriginalCommandPageFallback() {
        val calls = mutableListOf<String>()
        val workflow = NativeWorkflowController(noop(), noop { name, _ -> calls += name; null }, noop(), noop())
        workflow.nativeUiPage = NativeUiPage.DESTINATION_CONFIRM; calls.clear()
        val adapter = VoiceInteractionPresentationAdapter(noop(), { workflow }, { error("no views") },
            { error("no search UI") }, { error("no search") }, { false }, noop(), {})
        adapter.showCommandPage(AndroidVoiceAction.SpeakCurrentGuidance)
        assertEquals(NativeUiPage.VOICE_COMMAND, workflow.nativeUiPage)
        assertTrue(calls.contains("displayPage")); assertFalse(calls.contains("retireOutgoingVoiceInteraction"))
    }

    @Test fun guidanceReadsCurrentOutputAndActuatorWithoutCreatingOrCachingEither() {
        val first = Fixture(); val replacement = Fixture(); var current = first
        val peer = GuidanceSpeechPeerAdapter({ current.controller }, { current.voiceInput }, { true }, {})
        assertEquals(0L, peer.commandGeneration); assertFalse(peer.commandPending)
        assertFalse(peer.validatingStarted); assertNull(peer.statusText)
        peer.cancelNavigation()
        assertTrue(first.runtimes.isEmpty())

        assertTrue(peer.submitStatus("첫 안내", { true }, {}, null))
        assertTrue(peer.commandPending); assertEquals(1L, peer.commandGeneration)
        assertEquals("tts=initializing", peer.statusText)
        current = replacement
        assertFalse(peer.commandPending); assertEquals(0L, peer.commandGeneration)
        assertNull(peer.statusText); assertTrue(replacement.runtimes.isEmpty())
        replacement.controller.ensureFeedbackActuator(); replacement.runtime.ready()
        assertEquals("tts=ready", peer.statusText)
        replacement.controller.closeActuator()
        assertNull(peer.statusText)
        replacement.controller.ensureFeedbackActuator()
        assertEquals("tts=initializing", peer.statusText)
        assertEquals(2, replacement.runtimes.size)
        assertTrue(first.controller.hasPendingCommandResponse())
    }

    @Test fun guidanceBusyShortCircuitsForDeferredInputAndKeepsOutputSeparateFromRecognitionBlocking() {
        val calls = mutableListOf<String>()
        var nativeSpeechActive = false
        val input = VoiceInputSessionController(
            environment = noop { name, _ -> when (name) {
                "identity" -> VoiceInputIdentity("actor", 3L)
                "walkOrNull" -> WalkSessionSnapshot(WalkRuntimeEpoch("walk", 0L), WalkSessionState.READY,
                    WalkSessionMode.FULL, null, true, null)
                "page" -> VoiceInputPage(Page.VOICE, true, true, false, false, false, true)
                "commandWork" -> VoiceInputCommandWork(0, false, 0, false)
                "confirmation" -> VoiceInputConfirmation(false, null, false, null, null)
                else -> null
            } },
            commands = noop(), presentation = noop(), applicationEffects = noop(),
            output = noop { name, _ ->
                calls += name
                when (name) {
                    "nativeOrAccessibilitySpeechActive" -> nativeSpeechActive
                    "nativeRecognitionBlocked" -> true
                    else -> null
                }
            },
            scheduler = noop(), platform = noop(), wakePlatform = noop(), gatewayPlatform = noop(),
            gatewayEnvironment = noop(), diagnostics = noop(),
        )
        val peer = GuidanceSpeechPeerAdapter({ error("guidance must query input's output owner") }, { input }, { true }, {})
        assertTrue(input.deferVoiceRecognitionUntilOutputIdle(VoiceRecognitionPurpose.COMMAND, null))
        calls.clear()
        assertTrue(peer.busy()); assertTrue(peer.inputActive()); assertTrue(calls.isEmpty())
        input.clearDeferredVoiceRecognitionStart(notifyFailure = false)
        assertFalse(peer.inputActive()); assertFalse(peer.busy()); assertFalse(peer.outputBusy())
        assertFalse(calls.contains("nativeRecognitionBlocked"))
        assertTrue(input.isHandsFreeVoiceRecognitionBlocked())
        nativeSpeechActive = true
        assertTrue(peer.outputBusy()); assertTrue(peer.busy()); assertFalse(peer.inputActive())
    }

    @Test fun guidanceNavigationAdmissionUsesCurrentPolicyForEachRequestedTime() {
        var permitted = false; val requestedTimes = mutableListOf<Long>()
        val peer = GuidanceSpeechPeerAdapter({ error("output not needed") }, { error("input not needed") },
            { requestedTimes += it; permitted }, {})
        assertFalse(peer.canSpeakNavigation(12L))
        permitted = true
        assertTrue(peer.canSpeakNavigation(28L))
        assertEquals(listOf(12L, 28L), requestedTimes)
    }

    @Test fun guidanceStatusWaitsForNativeDoneAndReflectsStartValidationOnlyDuringValidation() {
        val f = Fixture(); val peer = f.guidancePeer()
        var completed = 0; var failed = 0
        val validationStates = mutableListOf<Boolean>()
        assertTrue(peer.submitStatus("경로 안내 상태", { validationStates += peer.validatingStarted; true },
            { completed++ }, { failed++ }))
        f.runtime.ready(); val id = f.runtime.spoken.single().id
        assertEquals(0, completed); assertTrue(peer.commandPending)
        validationStates.clear()
        f.runtime.start(id)
        assertTrue(validationStates.contains(true)); assertFalse(peer.validatingStarted)
        assertEquals(0, completed)
        f.runtime.done(id); f.runtime.done(id)
        assertEquals(1, completed); assertEquals(0, failed); assertFalse(peer.commandPending)
    }

    @Test fun guidanceStatusMapsNativeFailureCancellationAndReplacementToOneFailureEach() {
        for (terminal in listOf("error", "stop", "replacement", "actor_changed", "cancel_command")) {
            val f = Fixture(); val peer = f.guidancePeer(); var completed = 0; var failed = 0
            assertTrue(peer.submitStatus("경로 상태", { true }, { completed++ }, { failed++ }))
            if (terminal == "actor_changed") {
                f.context = f.context.copy(actorId = "replacement")
                f.clock.advanceBy(100L)
            } else {
                f.runtime.ready(); val id = f.runtime.spoken.single().id
                when (terminal) {
                    "error" -> f.runtime.failUtterance(id)
                    "stop" -> f.runtime.stop(id)
                    "replacement" -> f.controller.speakCommandResponse("다음 안내")
                    "cancel_command" -> peer.cancelCommand()
                }
                f.clock.advanceBy(0L)
                f.runtime.done(id)
            }
            assertEquals(terminal, 0, completed); assertEquals(terminal, 1, failed)
        }
    }

    @Test fun guidanceStatusWithoutFailureHandlerDoesNotCompleteWhenRetired() {
        val f = Fixture(); val peer = f.guidancePeer(); var completed = 0
        assertTrue(peer.submitStatus("이전 경로 상태", { true }, { completed++ }, null))
        f.runtime.ready(); val old = f.runtime.spoken.single().id
        assertTrue(peer.submitStatus("새 경로 상태", { true }, { completed++ }, null))
        f.clock.advanceBy(0L); f.runtime.done(old)
        assertEquals(0, completed); assertTrue(peer.commandPending)
        f.runtime.done(f.runtime.spoken.last().id)
        assertEquals(1, completed)
    }

    @Test fun installedStartClearsOnlyItsWaitingFailureBeforeActualCompletionTerminal() {
        val f = Fixture(); val events = mutableListOf<String>(); val current = { true }
        val peer = f.guidancePeer { supplied -> assertSame(current, supplied); events += "clear" }
        assertTrue(peer.submitInstalledStart("길 안내를 시작합니다.", current) { events += "terminal" })
        f.runtime.ready(); val id = f.runtime.spoken.single().id
        f.runtime.start(id); assertTrue(events.isEmpty())
        f.runtime.done(id); f.runtime.done(id)
        assertEquals(listOf("clear", "terminal"), events)
    }

    @Test fun installedStartFailureCancellationAndRetirementNeverClearWaitingFailure() {
        for (terminal in listOf("error", "stop", "replacement", "stale_done")) {
            val f = Fixture(); val events = mutableListOf<String>(); var current = true
            val peer = f.guidancePeer { events += "clear" }
            assertTrue(peer.submitInstalledStart("길 안내를 시작합니다.", { current }) { events += "terminal" })
            f.runtime.ready(); val id = f.runtime.spoken.single().id
            when (terminal) {
                "error" -> f.runtime.failUtterance(id)
                "stop" -> f.runtime.stop(id)
                "replacement" -> f.controller.speakCommandResponse("다음 안내")
                "stale_done" -> current = false
            }
            f.clock.advanceBy(0L); f.runtime.done(id); f.runtime.done(id)
            assertEquals(terminal, listOf("terminal"), events)
        }
    }

    @Test fun guidanceFailureGenerationCannotConsumeTheReplacementCommand() {
        val f = Fixture(); val peer = f.guidancePeer(); var previousFailed = 0; var currentFailed = 0
        peer.submitStatus("이전 상태", { true }, {}, { previousFailed++ })
        val oldGeneration = peer.commandGeneration
        peer.submitStatus("현재 상태", { true }, {}, { currentFailed++ })
        val generation = peer.commandGeneration
        f.clock.advanceBy(0L)
        assertEquals(1, previousFailed)
        peer.failCommand(oldGeneration)
        assertTrue(peer.commandPending); assertEquals(0, currentFailed)
        peer.failCommand(generation); peer.failCommand(generation)
        assertEquals(1, currentFailed); assertFalse(peer.commandPending)
    }

    @Test fun guidanceNavigationUsesActualNativeCompletionAndRejectsStaleStart() {
        for (validAtStart in listOf(true, false)) {
            val f = Fixture(); val peer = f.guidancePeer(); var current = true
            var completed = 0; var failed = 0
            f.controller.ensureFeedbackActuator(); f.runtime.ready()
            assertTrue(peer.submitNavigation("앞으로 이동하세요.", { current }, { completed++ }, { failed++ }))
            val id = f.runtime.spoken.single().id
            assertEquals(0, completed)
            current = validAtStart
            f.runtime.start(id); assertEquals(0, completed)
            f.runtime.done(id); f.runtime.done(id)
            assertEquals(if (validAtStart) 1 else 0, completed)
            assertEquals(if (validAtStart) 0 else 1, failed)
        }
    }

    @Test fun guidanceNavigationCancellationDoesNotCompleteItsInstruction() {
        val f = Fixture(); val peer = f.guidancePeer(); var completed = 0; var failed = 0
        f.controller.ensureFeedbackActuator(); f.runtime.ready()
        assertTrue(peer.submitNavigation("앞으로 이동하세요.", { true }, { completed++ }, { failed++ }))
        val id = f.runtime.spoken.single().id
        peer.cancelNavigation(); f.runtime.done(id)
        assertEquals(0, completed); assertEquals(1, failed)
    }

    private enum class Page { HOME, VOICE, SETTINGS }

    private class Fixture {
        val clock = Clock()
        var lifetime = SpeechOutputLifetime(7, true)
        var context = CommandSpeechContext(3, "actor", 11L, WalkRuntimeEpoch("walk", 0L),
            Page.VOICE, true, true, false, false)
        var navigationCurrent = true
        var screenReader = false
        val promptEffects = mutableListOf<Pair<CommandResponseToken, CommandPromptEffect>>()
        val readyTrace = mutableListOf<String>()
        val diagnosticTrace = mutableListOf<String>()
        val storageTrace = mutableListOf<String>()
        val accessibilityMessages = mutableListOf<String>()
        val runtimes = mutableListOf<NativeRuntime>()
        val factoryReadyCallbacks = mutableListOf<() -> Unit>()
        val runtime get() = runtimes.last()
        var onRisk: ((AndroidFeedbackActuator, Int, Long) -> Unit)? = null
        var inputQueries = 0
        val voiceInput = VoiceInputSessionController(noop<VoiceInputEnvironment> { name, _ -> if(name == "foreground" || name == "microphoneGranted") false else null },
            noop(), noop(), noop(), noop(), noop(), noop(), noop(), noop(), noop(), noop())
        val search = DestinationSearchController(noop(), noop(), noop(), AndroidNavigationRequestCoordinator(), Executor { }, {}, { null })
        var processGeneration = 2L
        var candidateRange: DestinationSearchVoiceState? = null
        val interactions = VoiceInteractionControllers({ search }, { error("route not needed") }, noop(), noop(),
            noop { name, _ -> when (name) {
                "actorId" -> context.actorId
                "processGeneration" -> processGeneration
                "feedbackLifetimeGeneration" -> lifetime.generation
                else -> null
            } },
            noop { name, args -> when (name) {
                "homeContextAvailable" -> lifetime.foreground
                "voiceInteractionPage" -> context.voiceInteractionPage
                "destinationRangeState" -> candidateRange
                "refreshDestinationRange" -> { candidateRange = args[0] as DestinationSearchVoiceState?; null }
                else -> null
            } },
            InteractionInputPeerAdapter { voiceInput },
            InteractionSpeechPeerAdapter({ controller }, { error("route not needed") }, {}),
            noop { name, args -> when (name) {
                "nowMs" -> clock.now
                "postDialogExpiry" -> { clock.post(this, Runnable { (args[0] as () -> Unit).invoke() }, args[1] as Long); null }
                else -> null
            } }, noop())
        val callbacks = AndroidVoiceCallbacks(noop(), object : VoiceCallbackQueue {
            override fun post(task: Runnable) = clock.post(this, task, 0)
            override fun postDelayed(task: Runnable, delayMs: Long) = clock.post(this, task, delayMs)
            override fun remove(task: Runnable) = clock.remove(this, task)
        }, { it() }, { clock.now }, { true }, readinessFactory = { object : VoiceCallbackQueue {
            override fun post(task: Runnable) = clock.post(this, task, 0)
            override fun postDelayed(task: Runnable, delayMs: Long) = clock.post(this, task, delayMs)
            override fun remove(task: Runnable) = clock.remove(this, task)
        } })
        val controller: SpeechOutputController = SpeechOutputController(
            debugDiagnostic = { diagnosticTrace += it },
            reader = object : SpeechOutputReader {
                override fun lifetime() = this@Fixture.lifetime
                override fun commandContext() = context
                override fun navigationOwner() = NavigationSpeechOwner(context.walkEpoch, "route", null)
                override fun navigationOwnerIsCurrent(owner: NavigationSpeechOwner) = navigationCurrent
                override fun homeFeatureContextAvailable() = true
                override fun stateSourceIsCurrent(source: CurrentStateSpeechLease) = true
                override fun guidanceVoiceInputActive() = context.recognitionActive
                override fun screenReaderActive() = screenReader
            },
            input = SpeechInputPeerAdapter({ inputQueries++; voiceInput }, { interactions }),
            presentation = object : SpeechOutputPresentation {
                override fun showLastResult(message: String) = Unit
                override fun showVoiceStatus(message: String) = Unit
                override fun refreshVoiceControls() = Unit
                override fun navigationStatus(message: String) = Unit
                override fun commandTerminalDiagnostic(completed: Boolean) = Unit
                override fun accessibilityTargetOrNull() = SpeechAccessibilityTarget { message, _ ->
                    accessibilityMessages += message
                    true
                }
                override fun canScheduleInteraction() = true
                override fun postInteraction(task: Runnable, delayMs: Long) = clock.post(this, task, delayMs)
                override fun removeInteraction(task: Runnable) = clock.remove(this, task)
            },
            readyEffects = object : SpeechOutputReadyEffects {
                override fun refreshReadyUi() { readyTrace += "ui" }
                override fun reevaluateCurrentRisk(actuator: AndroidFeedbackActuator, lifecycleGeneration: Int, nowMs: Long) {
                    readyTrace += "risk"
                    onRisk?.invoke(actuator, lifecycleGeneration, nowMs)
                }
                override fun retryCurrentNavigation(nowMs: Long) { readyTrace += "navigation" }
                override fun offlineSpeechUnavailable(lifecycleGeneration: Int, actuator: AndroidFeedbackActuator) = Unit
                override fun beforeCommandReplacement(previousGeneration: Long) = Unit
            },
            storage = object : kr.co.hanium.dreamup.walksafe.voice.output.SpeechOutputStorageBinding {
                override fun invalidate() { storageTrace += "invalidate" }
                override fun refresh(actuator: AndroidFeedbackActuator) { storageTrace += "refresh" }
            },
            actuatorFactory = SpeechOutputActuatorFactory { unavailable, ready ->
                val native = NativeRuntime(clock).also(runtimes::add)
                factoryReadyCallbacks += ready
                AndroidFeedbackActuator(runtime = native, onOfflineKoreanSpeechUnavailable = unavailable,
                    onOfflineKoreanSpeechReady = ready)
            },
            scheduler = callbacks,
        )
        fun inputSpeechPeer() = VoiceInputSpeechPeerAdapter({ controller }, { error("risk not used") }, { clock.now }, { false })
        fun showCandidatePage(): DestinationSearchVoiceState {
            val results = (1..3).map { number ->
                DestinationSearchResult("candidate-$number", "후보 $number", RoutePoint(37.0, 127.0 + number * 0.001),
                    null, null, null, number * 100)
            }
            return DestinationSearchVoiceState("", results).also(search::showVoicePage)
        }
        fun completeCandidatePrompt() {
            runtime.ready()
            var parts = 0
            while (controller.hasPendingCommandResponse()) {
                assertTrue("후보 안내가 종료되지 않았습니다.", ++parts <= 20)
                runtime.done(runtime.spoken.last().id)
            }
        }
        fun guidancePeer(clearWaitingFailure: (() -> Boolean) -> Unit = {}) = GuidanceSpeechPeerAdapter(
            { controller }, { voiceInput }, { true }, clearWaitingFailure,
        )
    }

    private class Clock : SpeechOutputScheduler {
        private data class Task(val owner: Any, val due: Long, val order: Long, val task: Runnable)
        private val tasks = mutableListOf<Task>()
        private var sequence = 0L
        var now = 1_000L
        val pendingTasks get() = tasks.size
        override fun elapsedRealtimeMs() = now
        override fun isMainThread() = true
        override fun runOnMain(action: () -> Unit) = action()
        override fun postReadiness(task: Runnable, delayMs: Long) = post(this, task, delayMs)
        override fun removeReadiness(task: Runnable) = remove(this, task)
        override fun postRetired(action: () -> Unit) = post(this, Runnable(action), 0L)
        fun post(owner: Any, task: Runnable, delayMs: Long) {
            tasks += Task(owner, now + delayMs.coerceAtLeast(0L), sequence++, task)
        }
        fun remove(owner: Any, task: Runnable) { tasks.removeAll { it.owner === owner && it.task === task } }
        fun clear(owner: Any) { tasks.removeAll { it.owner === owner } }
        fun advanceBy(deltaMs: Long) {
            val target = now + deltaMs
            while (true) {
                val next = tasks.filter { it.due <= target }
                    .minWithOrNull(compareBy<Task> { it.due }.thenBy { it.order }) ?: break
                tasks.remove(next)
                now = maxOf(now, next.due)
                next.task.run()
            }
            now = target
        }
    }

    private class NativeRuntime(private val clock: Clock) : FeedbackSpeechRuntime, FeedbackSpeechScheduler {
        data class Spoken(val message: String, val id: String)
        val spoken = mutableListOf<Spoken>()
        var shutdowns = 0
        private var initialized: ((Int) -> Unit)? = null
        private var listener: FeedbackSpeechRuntime.Listener? = null
        override val scheduler: FeedbackSpeechScheduler get() = this
        override val engineInitialized get() = initialized != null
        override fun elapsedRealtime() = clock.now
        override fun initialize(onInitialized: (Int) -> Unit) { initialized = onInitialized }
        override fun setLanguage(language: Locale) = TextToSpeech.LANG_AVAILABLE
        override fun selectOfflineKoreanVoice() = KoreanOfflineVoiceSelection.SELECTED
        override fun configureAudioAttributes() = Unit
        override fun setListener(listener: FeedbackSpeechRuntime.Listener): Int {
            this.listener = listener
            return TextToSpeech.SUCCESS
        }
        override fun speak(message: String, queueMode: Int, utteranceId: String): Int {
            spoken += Spoken(message, utteranceId)
            return TextToSpeech.SUCCESS
        }
        override fun stop() = Unit
        override fun shutdown() { shutdowns++ }
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener) = true
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) = Unit
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = error("No tone requested")
        override fun post(task: Runnable): Boolean = postDelayed(task, 0L)
        override fun postDelayed(task: Runnable, delayMs: Long): Boolean { clock.post(this, task, delayMs); return true }
        override fun removeCallbacks(task: Runnable) = clock.remove(this, task)
        override fun removeCallbacksAndMessages(token: Any?) = clock.clear(this)
        fun ready() { requireNotNull(initialized).invoke(TextToSpeech.SUCCESS); clock.advanceBy(0L) }
        fun failInitialization() { requireNotNull(initialized).invoke(TextToSpeech.ERROR); clock.advanceBy(0L) }
        fun start(id: String) { requireNotNull(listener).onStart(id); clock.advanceBy(0L) }
        fun done(id: String) { requireNotNull(listener).onDone(id); clock.advanceBy(0L) }
        fun failUtterance(id: String) { requireNotNull(listener).onError(id, TextToSpeech.ERROR_INVALID_REQUEST); clock.advanceBy(0L) }
        fun stop(id: String) { requireNotNull(listener).onStop(id, true); clock.advanceBy(0L) }
    }
}

internal inline fun <reified T> noop(crossinline action: (String, Array<out Any?>) -> Any? = { _, _ -> null }): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
        action(method.name, args ?: emptyArray()) ?: when(method.returnType) {
            java.lang.Boolean.TYPE -> false; java.lang.Integer.TYPE -> 0; java.lang.Long.TYPE -> 0L; else -> null
        }
    } as T
