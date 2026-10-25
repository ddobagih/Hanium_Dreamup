package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.voice.CurrentStateSpeechLease
import kr.co.hanium.dreamup.walksafe.voice.CurrentStateSpeechReader

import android.media.AudioManager
import android.speech.tts.TextToSpeech
import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.util.Locale
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckSnapshot
import kr.co.hanium.dreamup.walksafe.device.RuntimeMeasuredQualityGate
import kr.co.hanium.dreamup.walksafe.device.WalkSafeEnvironmentProfiles
import kr.co.hanium.dreamup.walksafe.device.PostLoginDeviceCheckState
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.depth.DepthSource
import kr.co.hanium.dreamup.walksafe.depth.MessagePolicy
import kr.co.hanium.dreamup.walksafe.depth.MetricDepthDecision
import kr.co.hanium.dreamup.walksafe.depth.Trend
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After

/** Main production owner/dispatch bodies + real Navigator/Actor; platform speech/readiness inputs are doubles. */
class MainActivityCurrentStateSpeechBehaviorTest {
    @Test fun statusWithoutStateLeaseRejectsStaleRequestAtNativeStart() {
        Fixture().use { f ->
            var current = true
            var completed = 0
            var cancelled = 0
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                f.main.dispatchCommandSpeechResponse(f.actor, "센서 상태를 확인해 주세요.", true, null,
                    isCurrent = { current }, completed = { completed++ }, failed = {}, cancelled = { cancelled++ }))
            val id = f.runtime.spoken.single().id
            current = false
            f.runtime.start(id)
            f.runtime.done(id)
            assertEquals(0, completed)
            assertEquals(1, cancelled)
            assertEquals(1, f.runtime.stops)
        }
    }

    @Test fun statusCanExplainClosedWalkingGateButRequiresRealDone() {
        val main = MainActivity()
        val runtime = FakeRuntime()
        val actor = AndroidFeedbackActuator(runtime = runtime, speechAllowed = { false })
        try {
            runtime.ready()
            var completed = 0
            assertFalse(actor.speakInteraction("일반 길안내"))
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                main.dispatchCommandSpeechResponse(actor, "휴대전화를 세워 주세요.", true, null,
                    isCurrent = { true }, completed = { completed++ }, failed = {}, cancelled = {}))
            val id = runtime.spoken.single().id
            assertEquals(0, completed)
            runtime.start(id)
            assertEquals(0, completed)
            runtime.done(id)
            assertEquals(1, completed)
        } finally { actor.close() }
    }

    @Before fun clearGateway() { GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation) }
    @After fun releaseGateway() { GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation) }

    @Test fun readyWaitRejectsTheSameRouteIdReinstalledAfterStateWasRead() {
        Fixture(ready = false).use { f ->
            val source = CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext())
            val owner = f.main.captureCommandSpeechResponseOwner(source)
            f.register()
            assertEquals(CommandSpeechReadiness.WAITING, f.readiness(owner))
            val beforeId = f.navigator.currentRouteId()
            val beforeRevision = f.navigator.currentTactileRouteProgress().routeVersion
            f.navigator.setRoute(f.route)
            assertEquals(beforeId, f.navigator.currentRouteId())
            assertNotEquals(beforeRevision, f.navigator.currentTactileRouteProgress().routeVersion)
            f.runtime.ready()
            assertEquals("An answer captured before route reinstall must not reach speech dispatch",
                CommandSpeechReadiness.CANCELLED, f.readiness(owner))
            assertEquals(0, f.runtime.spoken.size)
        }
    }

    @Test fun acceptedStateAnswerIsRejectedAtLateNativeStartAfterRouteReinstall() {
        Fixture().use { f ->
            val source = CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext())
            val owner = f.main.captureCommandSpeechResponseOwner(source)
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.dispatch(source, owner))
            val id = f.runtime.spoken.single().id
            f.navigator.setRoute(f.route)
            f.runtime.start(id)
            f.runtime.done(id)
            assertEquals(1, f.cancelled)
            assertEquals(0, f.completed)
            assertEquals(1, f.runtime.stops)
            assertFalse(f.actor.isAppSpeechActive())
        }
    }

    @Test fun normalStateAnswerUsesInteractionAndKeepsRouteAndWalkUnchangedThroughDone() {
        Fixture().use { f ->
            val walk = f.lifecycle.snapshot()
            val routeId = f.navigator.currentRouteId()
            val progress = f.navigator.currentTactileRouteProgress()
            val source = CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext())
            val owner = f.main.captureCommandSpeechResponseOwner(source)
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.dispatch(source, owner))
            val spoken = f.runtime.spoken.single()
            assertEquals("현재 경로 상태", spoken.message)
            assertTrue(spoken.id.startsWith("interaction-"))
            assertTrue(f.actor.isAppSpeechActive())
            f.runtime.start(spoken.id); f.runtime.done(spoken.id)
            assertEquals(1, f.completed); assertEquals(0, f.failed); assertEquals(0, f.cancelled)
            assertFalse(f.actor.isAppSpeechActive())
            assertEquals(walk, f.lifecycle.snapshot())
            assertEquals(routeId, f.navigator.currentRouteId())
            assertEquals(progress, f.navigator.currentTactileRouteProgress())
        }
    }

    @Test fun routeRequestDestinationAndRealProgressChangesInvalidateOnlyTheStateAnswer() {
        val changes: List<(Fixture) -> Unit> = listOf(
            { f -> set(f.main, "routeRequestGeneration", 1) },
            { f -> (field(f.main, "routeRequestInFlight") as java.util.concurrent.atomic.AtomicBoolean).set(true) },
            { f -> set(f.main, "currentDestination", f.route.polyline.first()) },
            { f ->
                val before = f.navigator.currentTactileRouteProgress()
                f.navigator.update(TrustedLocation(37.0005, 127.0008, 1f, 60_000L), 60_000L, false)
                assertNotEquals(before, f.navigator.currentTactileRouteProgress())
            },
        )
        changes.forEach { change -> Fixture().use { f ->
            val state = CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext())
            val stateOwner = f.main.captureCommandSpeechResponseOwner(state)
            val legacyOwner = f.main.captureCommandSpeechResponseOwner()
            change(f)
            assertFalse(stateOwner.isCurrent(f.main))
            assertTrue("Ordinary command responses keep their original owner policy", legacyOwner.isCurrent(f.main))
        } }
    }

    @Test fun endedPausedAndReplacementLifecycleCannotReuseTheCapturedStateAnswer() {
        val changes: List<(Fixture) -> Unit> = listOf(
            { it.lifecycle.handle(WalkSessionEvent.EndRequested) },
            { it.pause() },
            { f -> set(f.main, "walkSessionLifecycle", activeLifecycle("replacement", f.runtime)) },
        )
        changes.forEach { change -> Fixture().use { f ->
            val source = CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext())
            val owner = f.main.captureCommandSpeechResponseOwner(source)
            change(f)
            assertFalse(owner.isCurrent(f.main))
            assertEquals(NavigationSpeechDispatchResult.SUPPRESSED, f.dispatch(source, owner))
            assertEquals(0, f.runtime.spoken.size)
        } }
    }

    @Test fun foregroundRecognitionActorPageAndGatewayOwnershipStillFailClosed() {
        val changes: List<(Fixture) -> Unit> = listOf(
            { f -> set(f.main, "isActivityForeground", false) },
            { f -> set(f.main, "feedbackLifecycleGeneration", 1) },
            { f -> set(f.main, "voiceRecognitionGeneration", 1) },
            { f -> set(f.main, "voiceRecognitionActive", true) },
            { f -> set(f.main, "reporterUserId", "replacement") },
            { f -> setPage(f.main, "HOME") },
        )
        changes.forEach { change -> Fixture().use { f ->
            val source = CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext())
            val owner = f.main.captureCommandSpeechResponseOwner(source)
            change(f)
            assertFalse(owner.isCurrent(f.main))
        } }
        Fixture().use { f ->
            val source = CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext())
            val owner = f.main.captureCommandSpeechResponseOwner(source)
            GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
            assertFalse(owner.isCurrent(f.main))
        }
        Fixture().use { f ->
            val source = CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext())
            val owner = f.main.captureCommandSpeechResponseOwner(source)
            val other = AndroidFeedbackActuator(runtime = FakeRuntime())
            try {
                f.setActor(other)
                assertEquals(NavigationSpeechDispatchResult.SUPPRESSED, f.dispatch(source, owner))
                assertEquals(0, f.runtime.spoken.size)
            } finally { other.close() }
        }
    }

    @Test fun stoppedRiskSuppressesStatusWithoutStoppingTheCurrentRiskSpeech() {
        Fixture().use { f ->
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                f.actor.emit(FeedbackAction("current-risk", "멈춰 주세요", MessageLevel.STOP, null,
                    "current-state-risk", 200_000L), null, null,
                    { f.lifecycle.isRuntimeEpochCurrent(f.lifecycle.snapshot().epoch) && f.navigator.hasRoute() }).speech)
            val risk = f.runtime.spoken.single().id
            f.runtime.start(risk)
            val source = CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext())
            val owner = f.main.captureCommandSpeechResponseOwner(source)
            assertEquals(NavigationSpeechDispatchResult.SUPPRESSED, f.dispatch(source, owner))
            assertEquals(1, f.runtime.spoken.size)
            assertEquals(0, f.runtime.stops)
            assertTrue(f.actor.isAppSpeechActive())
            f.runtime.done(risk)
            assertFalse(f.actor.isAppSpeechActive())
        }
    }

    @Test fun interruptedResponseCallbackCannotRecaptureANewerResponseOrLifecycleGeneration() {
        Fixture(manualNavigatorClock = true).use { f ->
            f.failReroute()
            val token = requireNotNull(f.navigator.pendingDecisionToken())
            assertTrue(f.main.speakCurrentRouteDecision("새 경로를 선택해 주세요.", token,
                isScopeCurrent = { true }, submitSpeech = { _, current, _, _ ->
                    assertTrue(current())
                    val previous = field(f.main, "commandSpeechResponseGeneration") as Long
                    set(f.main, "commandSpeechResponseGeneration", previous + 1L)
                    true
                }, enqueueRetry = { error("The fixture question should be accepted") }, outputBusy = { false }))
            val interruptedGeneration = requireNotNull(
                MainActivityControllerTestFixture.decisionSpeechState(f.main).responseGeneration)
            val responseGeneration = interruptedGeneration + 1L
            set(f.main, "commandSpeechResponseGeneration", responseGeneration)
            val lifecycleGeneration = field(f.main, "feedbackLifecycleGeneration") as Int
            val source = CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext())
            assertTrue(f.main.captureCommandSpeechResponseOwner(source, responseGeneration, lifecycleGeneration).isCurrent(f.main))
            var callbackCalls = 0
            f.registry.registerLatest("command-response-$interruptedGeneration", null, {
                callbackCalls++
                assertTrue(f.main.retireCommandSpeechResponseIfOwned("retired-during-failure", responseGeneration))
                set(f.main, "feedbackLifecycleGeneration", lifecycleGeneration + 1)
            })
            val output = field(f.main, "speechOutputController")!!
            val interruption = output.javaClass.getDeclaredField("readyEffects").apply { isAccessible = true }
                .get(output) as kr.co.hanium.dreamup.walksafe.voice.output.SpeechOutputReadyEffects
            interruption.beforeCommandReplacement(interruptedGeneration)
            val mainSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
            val factory = MainActivityRefactorSource.member(mainSource, "createSpeechOutputController")
            assertTrue(factory.contains("override fun beforeCommandReplacement(previousGeneration: Long) = " +
                "this@MainActivity.routeGuidanceController.failInterruptedRouteDecisionSpeech(previousGeneration)"))
            val routeSource = File("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/RouteGuidanceController.kt").readText()
            val fail = MainActivityRefactorSource.member(routeSource, "failInterruptedRouteDecisionSpeech")
            assertTrue(fail.contains("decisionSpeech.failInterruptedSpeech(previousResponseGeneration)"))
            assertEquals(1, callbackCalls)
            assertSame(output, field(f.main, "speechOutputController"))
            assertSame(f.actor, field(f.main, "feedbackActuator"))
            val originalOwner = f.main.captureCommandSpeechResponseOwner(source, responseGeneration, lifecycleGeneration)
            assertFalse(originalOwner.isCurrent(f.main))
            assertTrue(f.main.captureCommandSpeechResponseOwner(source).isCurrent(f.main))
        }
    }

    @Test fun homeStatusReadsActualRouteStateWithoutAdvancingNavigation() {
        Fixture().use { f ->
            val before = f.navigator.currentTactileRouteProgress()
            assertEquals("길안내: 목적지까지의 경로가 설정되었습니다.", f.homeStatus())
            (field(f.main, "routeRequestInFlight") as java.util.concurrent.atomic.AtomicBoolean).set(true)
            assertEquals("길안내: 목적지까지의 경로를 찾고 있습니다.", f.homeStatus())
            (field(f.main, "routeRequestInFlight") as java.util.concurrent.atomic.AtomicBoolean).set(false)
            f.navigator.clear(); set(f.main, "isRouteActive", false); set(f.main, "currentDestination", null)
            assertEquals("길안내: 목적지를 선택해 주세요.", f.homeStatus())
            assertNotNull(before.routeVersion)
            assertNull(f.navigator.currentTactileRouteProgress().routeVersion)
            assertEquals(WalkSessionState.ACTIVE, f.lifecycle.snapshot().state)
        }
    }

    @Test fun freshActivePausedRecoveryAndEndedMessagesUseOnlyTheirExistingReadSources() {
        Fixture().use { f ->
            var homeReads = 0; var guidanceReads = 0
            fun message() = f.main.currentStateSpeechMessage(f.lifecycle.snapshot(), null,
                { homeReads++; f.homeStatus() }, { guidanceReads++; "기존 길안내 상태\n카메라 상태" },
                { "안전 안내 일시중지" }, { "자동 안전정지" })
            val before = f.lifecycle.snapshot()
            assertEquals("기존 길안내 상태\n카메라 상태", message())
            assertEquals(before, f.lifecycle.snapshot())
            assertEquals(0, homeReads); assertEquals(1, guidanceReads)
            f.pause()
            val paused = f.lifecycle.snapshot()
            assertTrue(message().contains("필수 기능을 다시 확인한 뒤 보행 재개를 확인합니다."))
            assertEquals(paused, f.lifecycle.snapshot())
            val plan = WalkSessionReadinessPlan(WalkSessionAction.RESUME_WALK, WalkSessionMode.FULL)
            val collector = WalkSessionReadinessCollector(paused.epoch, plan)
            plan.requiredRequirements.forEach { collector.record(WalkSessionReadinessObservation(paused.epoch, it,
                WalkSessionReadinessStatus.READY)) }
            f.lifecycle.handle(WalkSessionEvent.RecoveryRecheckCompleted(collector.build(f.runtime.now)))
            assertEquals(WalkSessionRecoveryStage.AWAITING_RESUME_CONFIRMATION, f.lifecycle.snapshot().recoveryStage)
            assertTrue(message().contains("재검사를 통과했습니다."))
            assertEquals(1, guidanceReads)
            f.lifecycle.handle(WalkSessionEvent.EndRequested)
            assertTrue(message().startsWith("보행을 종료했습니다."))
            assertEquals(WalkSessionState.ENDED, f.lifecycle.snapshot().state)
            assertEquals(1, homeReads)
        }
    }

    @Test fun readyAndOwnedSafetySummaryFormattingDoesNotStartOrResumeAWalk() {
        val main = MainActivity()
        val lifecycle = WalkSessionLifecycle(sessionIdFactory = { "read-only-ready" }, clock = { 1_000L })
        val before = lifecycle.snapshot()
        fun message(summary: String?) = main.currentStateSpeechMessage(before, summary,
            { "길안내: 목적지를 선택해 주세요." }, { error("ACTIVE-only presentation") },
            { error("PAUSED-only summary") }, { "자동 안전정지" })
        assertEquals("길안내: 목적지를 선택해 주세요.", message(null))
        assertEquals("보행 안전 중지\n기존 소유 안전정지 요약", message("기존 소유 안전정지 요약"))
        assertEquals(before, lifecycle.snapshot())
        // Text formatter only. The real owned safety-stop getter is asserted at the production call below.
    }

    @Test fun sourceTypedActionKeepsHomeAndPausedReadGatesAndTheExistingRepeatGuidanceBranch() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val execute = MainActivityRefactorSource.function(source, "private fun executeVoiceAction(")
        assertTrue(MainActivityRefactorSource.member(source, "handleVoiceCommandPhrases")
            .contains("voiceInteractionControllers.commands.handleVoiceCommandPhrases("))
        val interactionPorts = MainActivityRefactorSource.composition(source, "voiceInteractionControllers.commands")
        assertTrue(interactionPorts.contains(
            "output = InteractionSpeechPeerAdapter({ speechOutputController }, { routeGuidanceController }, { speakCurrentState() })"))
        val peers = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/composition/VoiceControllerPeerAdapters.kt").readText()
        val output = ReportStaticSourceInspector.blockAfter(peers, "internal class InteractionSpeechPeerAdapter")
        assertTrue(MainActivityRefactorSource.member(output, "speakCurrentState").contains("currentState()"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(execute,
            "AndroidVoiceAction.SpeakCurrentState,", "AndroidVoiceAction.ConfirmArrival,",
            "AndroidVoiceAction.RejectArrival -> output.speakCurrentState()"))
        assertTrue(interactionPorts.contains("ui = VoiceInteractionPresentationAdapter(voiceUiEffects, { nativeWorkflowController },"))
        val presentations = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/composition/VoicePresentationAdapters.kt").readText()
        val presentation = ReportStaticSourceInspector.blockAfter(presentations, "internal class VoiceInteractionPresentationAdapter")
        assertTrue(MainActivityRefactorSource.member(presentation, "showCommandPage")
            .contains("action == AndroidVoiceAction.SpeakCurrentState ||"))
        val home = execute.substringBefore("ui.showCommandPage(action)")
        assertTrue(home.contains("AndroidVoiceAction.SpeakCurrentState,"))
        val paused = MainActivityRefactorSource.function(source, "private fun handleVoiceCommandPhrases(")
            .substringAfter("if (snapshot.state == WalkSessionState.PAUSED &&").substringBefore("if (action == null)")
        assertTrue(paused.contains("AndroidVoiceAction.SpeakCurrentState"))
        val repeat = execute.substringAfter("AndroidVoiceAction.SpeakCurrentGuidance -> {").substringBefore("AndroidVoiceAction.SpeakVoiceHelp ->")
        assertTrue(repeat.contains("activeVoiceCandidateDialogOrNull()"))
        assertTrue(repeat.contains("repeatPendingWalkingDestinationChange()"))
        assertTrue(repeat.contains("speakNextNavigationInstruction()"))
    }

    @Test fun retiredRoutePrefixDoesNotReadDecisionGpsOrHomeState() {
        Fixture().use { f ->
            val captured = f.main.captureCurrentStateSpeechContext()
            val lease = CurrentStateSpeechLease.capture(captured)
            var decisionReads = 0
            var gpsReads = 0
            var homeReads = 0
            val reader = object : CurrentStateSpeechReader by captured {
                override val routeGeneration: Int get() = captured.routeGeneration + 1
                override val decisionToken get() = captured.decisionToken.also { decisionReads++ }
                override val runtimeGpsState get() = captured.runtimeGpsState.also { gpsReads++ }
                override val homeRouteMessage get() = captured.homeRouteMessage.also { homeReads++ }
            }
            assertFalse(lease.isCurrent(reader))
            assertEquals(0, decisionReads)
            assertEquals(0, gpsReads)
            assertEquals(0, homeReads)
        }
    }

    @Test fun sourceFreshStateAndGuardedDispatchAreUsedByTheRealResponseEntry() {
        val source = File("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt").readText()
        val state = source.substringAfter("private fun speakCurrentState()").substringBefore("private fun speakCommandResponse(")
        assertTrue(state.contains("CurrentStateSpeechLease.capture(captureCurrentStateSpeechContext())"))
        assertTrue(state.contains("walkSessionLifecycle.snapshot()"))
        assertTrue(state.contains("currentOwnedWalkRuntimeSafetyStopStatus()?.userFacingSummary()"))
        assertTrue(state.contains("::homeRouteStatusMessage"))
        assertTrue(state.contains("nativeGuidanceStatusMessage(currentNativeGuidancePresentation())"))
        val status = source.substringAfter("private fun nativeGuidanceStatusMessage(")
            .substringBefore("private fun updateNativeGuidanceStatus(")
        assertTrue(status.contains("officialEnvironmentFailureSpeech(currentOfficialEnvironmentAssessment(),"))
        assertTrue(status.contains("setOf(OfficialEnvironmentFactor.GPS_QUALITY)"))
        assertTrue(state.contains("statusExplanation = true, stateSource = source"))
        assertTrue(state.indexOf("CurrentStateSpeechLease.capture(captureCurrentStateSpeechContext())") <
            state.indexOf("nativeGuidanceStatusMessage(currentNativeGuidancePresentation())"))
        assertTrue(source.contains("private val currentStateSpeechElapsedRealtime: () -> Long = SystemClock::elapsedRealtime"))
        val gpsState = source.substringAfter("private fun currentRuntimeGpsSpeechState(")
            .substringBefore("internal fun captureCurrentStateSpeechContext(")
        assertTrue(gpsState.contains("val nowMs = currentStateSpeechElapsedRealtime()"))
        for (forbidden in listOf("SystemClock", "currentOfficialEnvironmentAssessment(",
            "currentNativeGuidancePresentation(", "runtimeGpsQualityGate.current(", "BoundedRuntimeDiagnosticLog")) {
            assertFalse("The source projection must only read published meaning: $forbidden", gpsState.contains(forbidden))
        }
        val lease = File("src/main/java/kr/co/hanium/dreamup/walksafe/voice/CurrentStateSpeechLease.kt").readText()
        assertFalse(lease.contains("MainActivity"))
        assertTrue(lease.contains("current.pendingDestination === captured.pendingDestination"))
        assertTrue(lease.contains("current.lifecycle === captured.lifecycle"))
        assertTrue(lease.contains("current.runtimeGpsState == captured.runtimeGpsState"))
        val capture = source.substringAfter("internal fun captureCurrentStateSpeechContext(")
            .substringBefore("internal class CommandSpeechResponseOwner")
        assertTrue(capture.contains("currentRuntimeGpsSpeechState(walk)"))
        assertTrue(capture.contains("routeNavigator.pendingDecisionToken()"))
        val reader = source.substringAfter("internal fun currentStateSpeechReader(")
            .substringBefore("internal class CommandSpeechResponseOwner")
        assertTrue(reader.contains("override val decisionToken: RouteNavigatorDecisionToken? get() = routeNavigator.pendingDecisionToken()"))
        assertTrue(reader.contains("override val runtimeGpsState: RuntimeGpsSpeechState? get() = currentRuntimeGpsSpeechState(walk)"))
        assertTrue(reader.contains("override val homeRouteMessage: String get() = homeRouteStatusMessage()"))
        assertFalse(state.contains("speakNextNavigationInstruction"))
        val presentation = source.substringAfter("private fun currentNativeGuidancePresentation()")
            .substringBefore("internal fun currentRouteGuidanceDisplayInput(")
        assertTrue(presentation.contains("val routeInput = currentRouteGuidanceDisplayInput(runtimeActive)"))
        assertTrue(presentation.contains("route = routeInput.copy(preparationRunning = routeInput.preparationRunning || measuring)"))
        val routeInput = source.substringAfter("internal fun currentRouteGuidanceDisplayInput(")
            .substringBefore("private fun currentOwnedWalkRuntimeSafetyStopStatus()")
        assertTrue(routeInput.contains("runtimeActive: Boolean = isWalkSessionRuntimeActive()"))
        assertTrue(routeInput.contains("collectionAllowsWork: () -> Boolean = ::currentNavigationCollectionAllowsWork"))
        assertTrue(routeInput.contains("trustedLocationAvailable: () -> Boolean = { currentRouteLocationOrNull() != null }"))
        assertTrue(routeInput.contains("locationPermissionReady: () -> Boolean = ::isRouteLocationPermissionReady"))
        assertTrue(routeInput.contains("locationServiceEnabled: () -> Boolean = ::isLocationServiceEnabledForDeviceCheck"))
        assertFalse(routeInput.contains("requestRoute("))
        assertFalse(routeInput.contains("routeNavigator.update("))
        assertFalse(routeInput.contains("rerouteRequestFailed("))
        val response = MainActivityRefactorSource.function(source, "private fun speakCommandResponse(")
        assertTrue(response.contains("responseOwner.isCurrent(this)"))
        assertTrue(response.contains("stateSource, isCurrent = { partIndex == index && responseCurrent() && feedbackActuator === actuator }"))
        val dispatch = MainActivityRefactorSource.function(source, "internal fun dispatchCommandSpeechResponse(")
        assertTrue(dispatch.contains("isCurrent() && reader.stateSourceIsCurrent(stateSource)"))
        assertTrue(MainActivityRefactorSource.member(source, "createSpeechOutputController")
            .contains("source.isCurrent(currentStateSpeechReader())"))
        assertTrue(dispatch.contains("speakGuardedHomeCommandInteraction"))
        assertTrue(dispatch.contains("speakGuardedInteraction"))
    }

    /** Controlled five-minute input; actual Main home summary/owner/dispatcher, not ACTIVE SDK presentation. */
    @Test fun fiveMinutesOfProducedCautionAllowAFreshMainStatusResponseWithoutChangingTheWalk() {
        Fixture().use { f ->
            val beforeWalk = f.lifecycle.snapshot()
            val beforeRoute = f.navigator.currentRouteId()
            val beforeProgress = f.navigator.currentTactileRouteProgress()
            val risk = ProducedRiskInput(f)
            val startedAt = f.runtime.now
            risk.advanceCautionFor(5 * 60_000L)
            assertEquals(startedAt + 5 * 60_000L, f.runtime.now)
            assertEquals(listOf(MessageLevel.CAUTION), risk.actions.map { it.level })
            assertEquals(1, risk.confirmed)
            assertTrue(risk.policy.canSpeakNavigation(f.runtime.now))
            val source = CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext())
            val owner = f.main.captureCommandSpeechResponseOwner(source)
            val message = f.homeStatus()
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                dispatchCapturedStatus(f, f.actor, message, source, owner))
            val reply = f.runtime.spoken.last()
            assertEquals(message, reply.message)
            assertTrue(reply.id.startsWith("interaction-"))
            f.runtime.start(reply.id); f.runtime.done(reply.id)
            for (outcome in listOf(SpeechOutputOutcome.REQUESTED, SpeechOutputOutcome.STARTED, SpeechOutputOutcome.DONE))
                assertEquals(1, risk.output.count(SpeechOutputKind.INTERACTION, outcome))
            assertEquals(1, f.completed); assertEquals(0, f.failed + f.cancelled)
            assertEquals(0, f.runtime.stops)
            assertEquals(beforeWalk, f.lifecycle.snapshot())
            assertEquals(beforeRoute, f.navigator.currentRouteId())
            assertEquals(beforeProgress, f.navigator.currentTactileRouteProgress())
        }
    }

    @Test fun producedStopAfterLongCautionProtectsItsExactOwnerThenAFreshAllowedRequestCompletes() {
        Fixture().use { f ->
            val risk = ProducedRiskInput(f)
            risk.advanceCautionFor(5 * 60_000L)
            val walk = f.lifecycle.snapshot()
            val routeId = f.navigator.currentRouteId()
            val progress = f.navigator.currentTactileRouteProgress()
            val stop = requireNotNull(risk.offer(.90f, .6f, MessageLevel.STOP, completeLater = false))
            assertNotNull(stop.deliveryOwner)
            assertTrue(risk.policy.hasClaimedFeedbackDelivery(stop))
            val active = f.runtime.spoken.last()
            val beforeSpeech = f.runtime.spoken.size
            val source = CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext())
            val owner = f.main.captureCommandSpeechResponseOwner(source)
            assertEquals(NavigationSpeechDispatchResult.SUPPRESSED,
                dispatchCapturedStatus(f, f.actor, f.homeStatus(), source, owner))
            assertEquals(beforeSpeech, f.runtime.spoken.size)
            assertEquals(0, risk.output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.STARTED))
            assertTrue(f.actor.isAppSpeechActive())
            assertTrue(risk.policy.hasClaimedFeedbackDelivery(stop))
            assertEquals(0, f.runtime.stops)
            assertEquals(0, risk.failed + risk.cancelled + f.cancelled)
            val confirmedBeforeStopDone = risk.confirmed
            val riskDoneBefore = risk.output.count(SpeechOutputKind.RISK, SpeechOutputOutcome.DONE)
            f.runtime.done(active.id)
            assertEquals(confirmedBeforeStopDone + 1, risk.confirmed)
            assertEquals(riskDoneBefore + 1, risk.output.count(SpeechOutputKind.RISK, SpeechOutputOutcome.DONE))
            assertEquals(0, risk.failed + risk.cancelled)
            assertFalse(risk.policy.hasClaimedFeedbackDelivery(stop))
            // A real non-alert observation retires the producer's active hysteresis, not the feedback owner.
            risk.observeFarMetric()
            risk.advanceCautionFor(FeedbackPolicyConfig().riskNavigationCooldownMs * 3L)
            assertTrue(risk.policy.canSpeakNavigation(f.runtime.now))
            assertFalse(f.actor.isAppSpeechActive())
            val fresh = CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext())
            val freshOwner = f.main.captureCommandSpeechResponseOwner(fresh)
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                dispatchCapturedStatus(f, f.actor, f.homeStatus(), fresh, freshOwner))
            val reply = f.runtime.spoken.last()
            f.runtime.start(reply.id); f.runtime.done(reply.id)
            assertEquals(1, risk.output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.STARTED))
            assertEquals(1, risk.output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.DONE))
            assertEquals(1, f.completed); assertEquals(0, f.cancelled)
            assertEquals(0, f.runtime.stops)
            assertEquals(walk, f.lifecycle.snapshot())
            assertEquals(routeId, f.navigator.currentRouteId())
            assertEquals(progress, f.navigator.currentTactileRouteProgress())
        }
    }

    @Test fun longRiskThenOrderedReadyConsumesOnlyTheStillOwnedStatusRequest() {
        for (change in listOf("unchanged", "reinstall", "actor")) Fixture().use { f ->
            ProducedRiskInput(f).advanceCautionFor(5 * 60_000L)
            // A new initializing owner is created at the new request time, not five minutes earlier.
            val waitingRuntime = FakeRuntime().apply { now = f.runtime.now }
            val waitingActor = AndroidFeedbackActuator(runtime = waitingRuntime)
            var replacement: AndroidFeedbackActuator? = null
            try {
                f.setActor(waitingActor)
                val output = SpeechOutputDiagnostics()
                waitingActor.speechOutputListener = output::record
                val source = CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext())
                val owner = f.main.captureCommandSpeechResponseOwner(source)
                val message = f.homeStatus()
                val requestAt = waitingRuntime.now
                val deadline = requestAt + AndroidFeedbackActuator.INITIALIZATION_READINESS_TIMEOUT_MS
                val generation = field(f.main, "commandSpeechResponseGeneration") as Long
                val lifecycleGeneration = field(f.main, "feedbackLifecycleGeneration") as Int
                val responseId = "long-state-$change"
                f.registry.registerLatest(responseId, { f.completed++ }, { f.failed++ })
                fun current() = owner.isCurrent(f.main) && field(f.main, "feedbackActuator") === waitingActor
                assertEquals(CommandSpeechReadiness.WAITING, f.registry.commandSpeechReadiness(responseId,
                    current(), waitingActor.isSpeechInitializing(), waitingRuntime.now, deadline))
                var resumes = 0; var retired = 0; var pollCancelled = 0
                f.main.rememberCommandSpeechReadyContinuation(responseId, generation, waitingActor, lifecycleGeneration,
                    resume = {
                        resumes++
                        when (f.registry.commandSpeechReadiness(responseId, current(), waitingActor.isSpeechInitializing(),
                            waitingRuntime.now, deadline)) {
                            CommandSpeechReadiness.DISPATCH -> f.main.dispatchCommandSpeechResponse(waitingActor, message,
                                true, source, isCurrent = ::current,
                                completed = { f.registry.takeTerminalCallback(responseId, true, true)?.invoke() },
                                failed = { f.registry.takeTerminalCallback(responseId, false, true)?.invoke() },
                                cancelled = { f.cancelled++ }) == NavigationSpeechDispatchResult.ACCEPTED
                            else -> { f.registry.cancel(listOf(responseId)); retired++; false }
                        }
                    }, cancelPoll = { pollCancelled++ }, onRetired = { retired++ })
                waitingRuntime.advanceBy(500L)
                if (change == "reinstall") f.navigator.setRoute(f.route)
                var readyActor = waitingActor
                var readyRuntime = waitingRuntime
                if (change == "actor") {
                    readyRuntime = FakeRuntime().apply { now = waitingRuntime.now }
                    readyActor = AndroidFeedbackActuator(runtime = readyRuntime)
                    replacement = readyActor
                    f.setActor(readyActor)
                }
                val beforeWalk = f.lifecycle.snapshot()
                val beforeId = f.navigator.currentRouteId()
                val beforeProgress = f.navigator.currentTactileRouteProgress()
                val order = mutableListOf<String>()
                readyRuntime.ready()
                assertTrue(f.main.handleOfflineSpeechReady(readyActor, lifecycleGeneration, readyRuntime.now,
                    refreshReadyUi = { order += "ready-ui" }, retryCurrentNavigation = { order += "navigation-recheck" }))
                assertEquals(listOf("ready-ui", "navigation-recheck"), order)
                assertEquals(1, pollCancelled)
                assertTrue(readyRuntime.now < deadline)
                if (change == "unchanged") {
                    assertEquals(1, resumes); assertEquals(0, retired)
                    val reply = waitingRuntime.spoken.single()
                    assertEquals(message, reply.message)
                    waitingRuntime.start(reply.id); waitingRuntime.done(reply.id)
                    assertEquals(1, f.completed)
                    assertEquals(1, output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.STARTED))
                    assertEquals(1, output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.DONE))
                } else {
                    assertEquals(1, retired)
                    assertEquals(0, waitingRuntime.spoken.size)
                    assertEquals(0, readyRuntime.spoken.size)
                    assertEquals(0, output.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.STARTED))
                    assertEquals(0, f.completed)
                }
                assertEquals(0, waitingRuntime.stops + readyRuntime.stops)
                assertEquals(beforeWalk, f.lifecycle.snapshot())
                assertEquals(beforeId, f.navigator.currentRouteId())
                assertEquals(beforeProgress, f.navigator.currentTactileRouteProgress())
                f.main.clearCommandSpeechReadyContinuationIfOwned(responseId, generation)
            } finally {
                try { replacement?.close() } finally { waitingActor.close() }
            }
        }
    }

    @Test fun currentStateReportsRerouteFailureWhileTheDecisionIsStillPending() {
        Fixture(manualNavigatorClock = true).use { f ->
            f.prepareRoutePresentation()
            f.failReroute()
            assertEquals(RouteNavigatorUserDecision.REROUTE, f.navigator.pendingUserDecision())
            assertTrue("CurrentState must explain failed reroute, not preparation",
                f.routeStateMessage().contains("새 경로를 찾지 못해 방향 안내가 멈춰 있습니다."))
        }
    }

    @Test fun currentStateReportsRerouteFailureAfterTheDecisionExpires() {
        Fixture(manualNavigatorClock = true).use { f ->
            f.prepareRoutePresentation()
            f.failReroute()
            f.runtime.advanceBy(30_001L)
            assertNull(f.navigator.pendingDecisionToken())
            val message = f.routeStateMessage()
            assertTrue("Expired choice must not erase the failed-provider state",
                message.contains("새 경로를 찾지 못해 방향 안내가 멈춰 있습니다."))
            val source = CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext())
            val owner = f.main.captureCommandSpeechResponseOwner(source)
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                dispatchCapturedStatus(f, f.actor, message, source, owner))
            assertEquals(message, f.runtime.spoken.single().message)
        }
    }

    @Test fun initialJoinPositionRecoveryRetiresTheOldStateAnswerAtNativeStart() {
        Fixture(manualNavigatorClock = true).use { f ->
            f.prepareRoutePresentation()
            f.navigator.setRoute(f.route, destination = f.route.polyline.last(), waitForInitialRouteJoin = true)
            set(f.main, "positionGuidancePaused", true)
            val progress = f.navigator.currentTactileRouteProgress()
            val home = f.homeStatus()
            assertNull(progress.segmentIndex)
            val message = f.routeStateMessage()
            assertTrue(message.contains("현재 위치를 확인하지 못해 방향 안내가 잠시 멈춰 있습니다."))
            val source = CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext())
            val owner = f.main.captureCommandSpeechResponseOwner(source)
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                dispatchCapturedStatus(f, f.actor, message, source, owner))
            val id = f.runtime.spoken.single().id
            set(f.main, "positionGuidancePaused", false)
            assertEquals(progress, f.navigator.currentTactileRouteProgress())
            assertEquals(home, f.homeStatus())
            assertTrue(f.routeStateMessage().contains("현재 위치와 경로 연결을 확인하고 있습니다."))
            f.runtime.start(id)
            f.runtime.done(id)
            assertEquals("A changed position state must cancel the accepted old answer before START", 1, f.cancelled)
            assertEquals(0, f.completed)
            assertEquals(1, f.runtime.stops)
            val currentSource = CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext())
            assertTrue(currentSource.isCurrent(f.main.currentStateSpeechReader()))
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, dispatchCapturedStatus(f, f.actor,
                f.routeStateMessage(), currentSource, f.main.captureCommandSpeechResponseOwner(currentSource)))
            val currentId = f.runtime.spoken.last().id
            f.runtime.start(currentId); f.runtime.done(currentId)
            assertEquals(1, f.completed)
        }
    }

    @Test fun permissionsAndCurrentLocationLimitsOutrankTheOldProviderFailure() {
        Fixture(manualNavigatorClock = true).use { f ->
            f.prepareRoutePresentation(); f.failReroute()
            set(f.main, "positionGuidancePaused", true)
            assertTrue(f.routeStateMessage(permission = false, service = false, fresh = false)
                .contains("정확한 위치 권한이 없습니다."))
            assertTrue(f.routeStateMessage(service = false, fresh = false)
                .contains("Android 위치 서비스를 켜야"))
            assertTrue(f.routeStateMessage(fresh = false).contains("현재 위치를 확인하지 못해 방향 안내가 잠시 멈춰"))
            set(f.main, "positionGuidancePaused", false)
            assertTrue(f.routeStateMessage(fresh = false).contains("정확한 현재 위치를 기다리고"))
            assertTrue(f.routeStateMessage().contains("새 경로를 찾지 못해 방향 안내가 멈춰"))
        }
    }

    @Test fun freshLocationIsRequiredBeforeExplainingInitialJoin() {
        Fixture(manualNavigatorClock = true).use { f ->
            f.prepareRoutePresentation()
            f.navigator.setRoute(f.route, destination = f.route.polyline.last(), waitForInitialRouteJoin = true)
            assertTrue(f.routeStateMessage(fresh = false).contains("정확한 현재 위치를 기다리고"))
            assertTrue(f.routeStateMessage().contains("현재 위치와 경로 연결을 확인하고"))
        }
    }

    @Test fun anApprovedNewRequestReportsPreparationWithoutResumingTheOldRoute() {
        Fixture(manualNavigatorClock = true).use { f ->
            f.prepareRoutePresentation(); f.failReroute()
            val token = requireNotNull(f.navigator.pendingDecisionToken())
            assertTrue(f.navigator.selectDeviationChoice(RouteDeviationChoice.NEW_ROUTE, token).shouldReroute)
            (field(f.main, "routeRequestInFlight") as java.util.concurrent.atomic.AtomicBoolean).set(true)
            val progress = f.navigator.currentTactileRouteProgress()
            val requestInput = f.routeInput()
            assertTrue(requestInput.preparationRunning)
            assertFalse(requestInput.outputRunning)
            assertNull(requestInput.unavailableReasonKo)
            val message = f.routeStateMessage()
            assertTrue(message.contains("현재 위치와 TMAP 경로를 준비하고 있습니다."))
            assertFalse(message.contains("새 경로를 찾지 못해"))
            assertEquals("tmap_unavailable", field(f.main, "directionGuidancePauseReason"))
            assertEquals(false, field(f.main, "latestTmapOnRoute"))
            assertEquals(progress, f.navigator.currentTactileRouteProgress())
            assertNull(f.navigator.pendingDecisionToken())
            assertTrue(f.routeStateMessage(fresh = false).contains("정확한 현재 위치를 기다리고"))
            set(f.main, "positionGuidancePaused", true)
            assertTrue(f.routeStateMessage().contains("현재 위치를 확인하지 못해 방향 안내가 잠시 멈춰"))
        }
    }

    @Test fun actualNavigatorChoicesAreExplainedWithoutConsumingOrApprovingThem() {
        for (decision in RouteNavigatorUserDecision.values()) Fixture(manualNavigatorClock = true).use { f ->
            f.prepareRoutePresentation()
            val expectedText = when (decision) {
                RouteNavigatorUserDecision.REROUTE -> {
                    f.failReroute(); set(f.main, "directionGuidancePauseReason", "off_route_confirmed")
                    "새 경로, 위치 다시 확인, 길안내 종료 중 선택해 주세요."
                }
                RouteNavigatorUserDecision.LOCATION_RECHECK -> {
                    f.navigator.onUntrustedLocation()
                    assertTrue(f.navigator.canResumeAfterPositionRecovery())
                    "현재 위치를 다시 확인하고 있습니다. 확인되면 안내를 자동으로 이어갑니다."
                }
                RouteNavigatorUserDecision.ARRIVAL_CONFIRMATION -> {
                    f.navigator.setRoute(f.route, destination = f.route.polyline.last())
                    val end = f.route.polyline.last()
                    for ((from, to) in f.route.polyline.zipWithNext()) {
                        for (part in 1..10) {
                            if (f.navigator.pendingUserDecision() == decision) break
                            f.runtime.advanceBy(3_000L)
                            val fraction = part / 10.0
                            f.navigator.update(TrustedLocation(
                                from.latitude + (to.latitude - from.latitude) * fraction,
                                from.longitude + (to.longitude - from.longitude) * fraction,
                                1f, f.runtime.now), f.runtime.now, false)
                        }
                    }
                    repeat(4) {
                        if (f.navigator.pendingUserDecision() != decision) {
                            f.runtime.advanceBy(3_000L)
                            f.navigator.update(TrustedLocation(end.latitude, end.longitude, 1f, f.runtime.now),
                                f.runtime.now, false)
                        }
                    }
                    assertEquals(decision, f.navigator.pendingUserDecision())
                    "도착했습니다. 안내를 종료합니다."
                }
            }
            val token = requireNotNull(f.navigator.pendingDecisionToken())
            assertEquals(decision, token.decision)
            val progress = f.navigator.currentTactileRouteProgress()
            val walk = f.lifecycle.snapshot()
            assertTrue(f.routeStateMessage().contains(expectedText))
            assertEquals(token, f.navigator.pendingDecisionToken())
            assertEquals(progress, f.navigator.currentTactileRouteProgress())
            assertEquals(walk, f.lifecycle.snapshot())
            assertFalse((field(f.main, "routeRequestInFlight") as java.util.concurrent.atomic.AtomicBoolean).get())
        }
    }

    @Test fun aChangedDirectionReasonRetiresTheWaitingAnswerBeforeReady() {
        Fixture(ready = false, manualNavigatorClock = true).use { f ->
            f.prepareRoutePresentation(); f.failReroute()
            val source = CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext())
            val owner = f.main.captureCommandSpeechResponseOwner(source)
            val ordinary = f.main.captureCommandSpeechResponseOwner()
            val progress = f.navigator.currentTactileRouteProgress()
            val home = f.homeStatus()
            assertTrue(source.isCurrent(f.main.currentStateSpeechReader()))
            assertTrue(f.routeStateMessage().contains("새 경로를 찾지 못해"))
            f.register()
            assertEquals(CommandSpeechReadiness.WAITING, f.readiness(owner))
            set(f.main, "directionGuidancePauseReason", "off_route_recheck")
            assertEquals(progress, f.navigator.currentTactileRouteProgress())
            assertEquals(home, f.homeStatus())
            assertTrue(ordinary.isCurrent(f.main))
            f.runtime.ready()
            assertEquals(CommandSpeechReadiness.CANCELLED, f.readiness(owner))
            assertTrue(CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext()).isCurrent(f.main.currentStateSpeechReader()))
            assertEquals(0, f.runtime.spoken.size)
        }
    }

    @Test fun decisionExpiryRetiresTheAcceptedAnswerAtNativeStart() {
        Fixture(manualNavigatorClock = true).use { f ->
            f.prepareRoutePresentation(); f.failReroute()
            set(f.main, "directionGuidancePauseReason", "off_route_confirmed")
            val token = requireNotNull(f.navigator.pendingDecisionToken())
            f.runtime.advanceBy(29_500L)
            val source = CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext())
            val owner = f.main.captureCommandSpeechResponseOwner(source)
            val progress = f.navigator.currentTactileRouteProgress()
            val home = f.homeStatus()
            assertTrue(source.isCurrent(f.main.currentStateSpeechReader()))
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                dispatchCapturedStatus(f, f.actor, f.routeStateMessage(), source, owner))
            val id = f.runtime.spoken.single().id
            f.runtime.advanceBy(501L)
            // Do not call the token getter first: source validation itself must expire the decision.
            assertFalse(source.isCurrent(f.main.currentStateSpeechReader()))
            assertEquals(progress, f.navigator.currentTactileRouteProgress())
            assertEquals(home, f.homeStatus())
            f.runtime.start(id); f.runtime.done(id)
            assertEquals(1, f.cancelled); assertEquals(0, f.completed)
            assertEquals("route_decision_stale", f.navigator.selectDeviationChoice(RouteDeviationChoice.NEW_ROUTE, token).reason)
            assertNull(f.navigator.pendingDecisionToken())
            assertNotNull(f.navigator.rerouteRequestFailed())
            assertNotEquals(token, f.navigator.pendingDecisionToken())
            assertFalse(source.isCurrent(f.main.currentStateSpeechReader()))
            val fresh = CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext())
            assertTrue(fresh.isCurrent(f.main.currentStateSpeechReader()))
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, dispatchCapturedStatus(f, f.actor,
                f.routeStateMessage(), fresh, f.main.captureCommandSpeechResponseOwner(fresh)))
            val freshId = f.runtime.spoken.last().id
            f.runtime.start(freshId); f.runtime.done(freshId)
            assertEquals(1, f.completed)
        }
    }

    @Test fun changedRuntimeGpsMeaningRetiresTheStateAnswerBeforeReady() {
        Fixture(ready = false, manualNavigatorClock = true).use { f ->
            f.prepareLimitedRuntimeGps()
            val before = f.gpsStateMessage()
            assertTrue(before.contains("위치 오차가 커서"))
            val source = CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext())
            val owner = f.main.captureCommandSpeechResponseOwner(source)
            f.register()
            assertEquals(CommandSpeechReadiness.WAITING, f.readiness(owner))
            val stable = f.stableStateIdentity()
            f.runtime.advanceBy(1L)
            f.observeRuntimeGps(trusted = false, accuracy = null)
            val after = f.gpsStateMessage()
            assertTrue(after.contains("현재 위치를 확인하지 못했습니다."))
            assertNotEquals(before, after)
            assertEquals(stable, f.stableStateIdentity())
            f.runtime.ready()
            assertEquals("A GPS meaning change must retire the old current-state answer before READY",
                CommandSpeechReadiness.CANCELLED, f.readiness(owner))
            assertEquals(0, f.runtime.spoken.size)
        }
    }

    @Test fun changedRuntimeGpsMeaningCancelsTheAcceptedAnswerAtNativeStart() {
        Fixture(manualNavigatorClock = true).use { f ->
            f.prepareLimitedRuntimeGps()
            val message = f.gpsStateMessage()
            assertTrue(message.contains("위치 오차가 커서"))
            val source = CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext())
            val owner = f.main.captureCommandSpeechResponseOwner(source)
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                dispatchCapturedStatus(f, f.actor, message, source, owner))
            val id = f.runtime.spoken.single().id
            val stable = f.stableStateIdentity()
            f.runtime.advanceBy(1L)
            f.observeRuntimeGps(trusted = false, accuracy = null)
            assertTrue(f.gpsStateMessage().contains("현재 위치를 확인하지 못했습니다."))
            assertEquals(stable, f.stableStateIdentity())
            f.runtime.start(id); f.runtime.done(id)
            assertEquals("Changed GPS meaning must reject the accepted answer at native START", 1, f.cancelled)
            assertEquals(0, f.completed)
            assertEquals(1, f.runtime.stops)
            val freshSource = CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext())
            val freshMessage = f.gpsStateMessage()
            assertTrue(freshSource.isCurrent(f.main.currentStateSpeechReader()))
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, dispatchCapturedStatus(f, f.actor,
                freshMessage, freshSource, f.main.captureCommandSpeechResponseOwner(freshSource)))
            val freshId = f.runtime.spoken.last().id
            assertEquals(freshMessage, f.runtime.spoken.last().message)
            f.runtime.start(freshId); f.runtime.done(freshId)
            assertEquals(1, f.completed)
        }
    }

    @Test fun aNewSampleWithTheSameGpsFailureMeaningKeepsTheStateAnswerCurrent() {
        Fixture(manualNavigatorClock = true).use { f ->
            f.prepareLimitedRuntimeGps()
            val oldEvidence = field(f.main, "officialEnvironmentGpsEvidence") as MeasuredEnvironmentEvidence
            val message = f.gpsStateMessage()
            val source = CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext())
            val owner = f.main.captureCommandSpeechResponseOwner(source)
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                dispatchCapturedStatus(f, f.actor, message, source, owner))
            val id = f.runtime.spoken.single().id
            f.runtime.advanceBy(1L)
            val fresh = f.observeRuntimeGps(trusted = true, accuracy = 31.0)
            assertNotEquals(oldEvidence.observedAtElapsedRealtimeMs, fresh.observedAtElapsedRealtimeMs)
            assertEquals(oldEvidence.status, fresh.status)
            assertEquals(oldEvidence.detail, fresh.detail)
            assertEquals(message, f.gpsStateMessage())
            assertTrue(source.isCurrent(f.main.currentStateSpeechReader()))
            f.runtime.start(id); f.runtime.done(id)
            assertEquals(1, f.completed)
            assertEquals(0, f.cancelled)
            assertEquals(0, f.runtime.stops)
        }
    }

    @Test fun differentUnknownGpsCodesKeepTheSameWaitingAnswerCurrent() {
        Fixture(manualNavigatorClock = true).use { f ->
            f.prepareLimitedRuntimeGps()
            f.runtime.advanceBy(1L)
            val first = f.observeRuntimeGps(trusted = null, accuracy = null)
            assertEquals(EnvironmentEvidenceStatus.UNKNOWN, first.status)
            val message = f.gpsStateMessage()
            assertTrue(message.contains("현재 위치를 확인할 수 없어 방향 안내를 잠시 멈춥니다. 새 위치를 기다려 주세요."))
            val source = CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext())
            val owner = f.main.captureCommandSpeechResponseOwner(source)
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                dispatchCapturedStatus(f, f.actor, message, source, owner))
            f.runtime.advanceBy(1L)
            val next = f.observeRuntimeGps(trusted = true, accuracy = null)
            assertEquals(EnvironmentEvidenceStatus.UNKNOWN, next.status)
            assertNotEquals(first.detail, next.detail)
            assertEquals(message, f.gpsStateMessage())
            assertTrue(source.isCurrent(f.main.currentStateSpeechReader()))
            val id = f.runtime.spoken.single().id
            f.runtime.start(id); f.runtime.done(id)
            assertEquals(1, f.completed)
            assertEquals(0, f.cancelled)
        }
    }

    @Test fun unownedGpsMetadataCannotKeepAnActiveFailureExplanationCurrent() {
        val replacements: List<(MeasuredEnvironmentEvidence) -> MeasuredEnvironmentEvidence> = listOf(
            { it.copy(epoch = WalkRuntimeEpoch("other-gps-walk", 1L)) },
            { it.copy(measurementProfileId = "other-gps-profile") },
            { it.copy(factor = OfficialEnvironmentFactor.CAMERA_QUALITY) },
        )
        for (replace in replacements) Fixture(manualNavigatorClock = true).use { f ->
            f.prepareLimitedRuntimeGps()
            val source = CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext())
            val before = f.gpsStateMessage()
            set(f.main, "officialEnvironmentGpsEvidence",
                replace(field(f.main, "officialEnvironmentGpsEvidence") as MeasuredEnvironmentEvidence))
            assertNotEquals(before, f.gpsStateMessage())
            assertTrue(f.gpsStateMessage().contains("현재 위치를 확인할 수 없어 방향 안내를 잠시 멈춥니다. 새 위치를 기다려 주세요."))
            assertFalse(source.isCurrent(f.main.currentStateSpeechReader()))
            assertTrue(CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext()).isCurrent(f.main.currentStateSpeechReader()))
        }
    }

    @Test fun gpsEvidenceDoesNotFenceInactiveStateAnswersOrOrdinaryCommands() {
        Fixture(manualNavigatorClock = true).use { f ->
            f.prepareLimitedRuntimeGps()
            val ordinary = f.main.captureCommandSpeechResponseOwner()
            f.runtime.advanceBy(1L)
            f.observeRuntimeGps(trusted = false, accuracy = null)
            assertTrue(ordinary.isCurrent(f.main))
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                f.main.dispatchCommandSpeechResponse(f.actor, "일반 응답", true, null,
                    isCurrent = { ordinary.isCurrent(f.main) }, completed = { f.completed++ },
                    failed = { f.failed++ }, cancelled = { f.cancelled++ }))
            val id = f.runtime.spoken.single().id
            f.runtime.start(id); f.runtime.done(id)
            assertEquals(1, f.completed)
        }
        Fixture(manualNavigatorClock = true).use { f ->
            f.prepareLimitedRuntimeGps()
            f.pause()
            val source = CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext())
            val owner = f.main.captureCommandSpeechResponseOwner(source)
            val message = f.gpsStateMessage()
            assertEquals("보행 일시정지\n\n재점검 뒤 진행하던 보행을 이어갑니다.\n\n" +
                "필수 기능을 다시 확인한 뒤 보행 재개를 확인합니다.", message)
            set(f.main, "officialEnvironmentGpsEvidence", null)
            assertEquals(message, f.gpsStateMessage())
            assertTrue(source.isCurrent(f.main.currentStateSpeechReader()))
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                dispatchCapturedStatus(f, f.actor, message, source, owner))
            val id = f.runtime.spoken.single().id
            f.runtime.start(id); f.runtime.done(id)
            assertEquals(1, f.completed)
        }
    }

    @Test fun gpsEvidencePromotionBetweenCaptureAndMessageRequiresANewStateRequest() {
        Fixture(manualNavigatorClock = true).use { f ->
            f.prepareRoutePresentation()
            assertEquals(EnvironmentEvidenceStatus.PASS, f.observeRuntimeGps(true, 3.0).status)
            f.runtime.advanceBy(100L)
            assertEquals(EnvironmentEvidenceStatus.PASS, f.observeRuntimeGps(true, 30.0).status)
            val oldSource = CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext())
            val oldOwner = f.main.captureCommandSpeechResponseOwner(oldSource)
            assertTrue(oldSource.isCurrent(f.main.currentStateSpeechReader()))
            f.runtime.advanceBy(3_000L)
            // The existing assessment reader publishes gate.current; no SDK or full Activity call is simulated.
            val gate = field(f.main, "runtimeGpsQualityGate") as RuntimeMeasuredQualityGate
            val promoted = requireNotNull(gate.current(f.runtime.now))
            assertEquals(EnvironmentEvidenceStatus.FAIL, promoted.status)
            set(f.main, "officialEnvironmentGpsEvidence", promoted)
            val message = f.gpsStateMessage()
            assertTrue(message.contains("위치 오차가 커서"))
            assertFalse(oldSource.isCurrent(f.main.currentStateSpeechReader()))
            assertEquals(NavigationSpeechDispatchResult.SUPPRESSED,
                dispatchCapturedStatus(f, f.actor, message, oldSource, oldOwner))
            assertEquals(0, f.runtime.spoken.size)
            val fresh = CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext())
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                dispatchCapturedStatus(f, f.actor, message, fresh, f.main.captureCommandSpeechResponseOwner(fresh)))
            val id = f.runtime.spoken.single().id
            f.runtime.start(id); f.runtime.done(id)
            assertEquals(1, f.completed)
        }
    }

    @Test fun runtimeGpsEvidenceExpiryRetiresTheStateAnswerBeforeReady() {
        Fixture(manualNavigatorClock = true).use { f ->
            f.prepareLimitedRuntimeGps()
            val evidence = f.ageRuntimeGpsToBeforeExpiry()
            val message = f.gpsStateMessage()
            assertTrue(message.contains("위치 오차가 커서"))
            // Start initialization at this request, after aging GPS, so its watchdog is unrelated.
            val waitingRuntime = FakeRuntime().apply { now = f.runtime.now }
            val waitingActor = AndroidFeedbackActuator(runtime = waitingRuntime)
            try {
                f.setActor(waitingActor)
                assertEquals(f.runtime.now, waitingRuntime.now)
                val source = CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext())
                val owner = f.main.captureCommandSpeechResponseOwner(source)
                f.register()
                val readinessDeadlineMs = waitingRuntime.now + 5_000L
                fun readiness() = f.registry.commandSpeechReadiness("state-response",
                    owner.isCurrent(f.main) && field(f.main, "feedbackActuator") === waitingActor,
                    waitingActor.isSpeechInitializing(), waitingRuntime.now, readinessDeadlineMs)
                assertEquals(CommandSpeechReadiness.WAITING, readiness())
                val identity = f.stableStateIdentity()
                f.runtime.advanceBy(200L)
                waitingRuntime.advanceBy(200L)
                assertEquals(f.runtime.now, waitingRuntime.now)
                assertTrue(f.gpsStateMessage().contains("현재 위치를 확인할 수 없어 방향 안내를 잠시 멈춥니다. 새 위치를 기다려 주세요."))
                assertEquals(identity, f.stableStateIdentity())
                assertSame(evidence, field(f.main, "officialEnvironmentGpsEvidence"))
                assertSame(evidence, (field(f.main, "runtimeGpsQualityGate") as RuntimeMeasuredQualityGate).current(f.runtime.now))
                waitingRuntime.ready()
                assertEquals("Time-expired GPS evidence must retire the old explanation before READY",
                    CommandSpeechReadiness.CANCELLED, readiness())
                assertEquals(0, waitingRuntime.spoken.size)
            } finally { waitingActor.close() }
        }
    }

    @Test fun runtimeGpsEvidenceExpiryCancelsTheAcceptedAnswerAtNativeStart() {
        Fixture(manualNavigatorClock = true).use { f ->
            f.prepareLimitedRuntimeGps()
            val evidence = f.ageRuntimeGpsToBeforeExpiry()
            val message = f.gpsStateMessage()
            assertTrue(message.contains("위치 오차가 커서"))
            val source = CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext())
            val owner = f.main.captureCommandSpeechResponseOwner(source)
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                dispatchCapturedStatus(f, f.actor, message, source, owner))
            val id = f.runtime.spoken.single().id
            val identity = f.stableStateIdentity()
            // Only 200 ms after submit: far inside Actor's independent 8 s START timeout.
            f.runtime.advanceBy(200L)
            assertTrue(f.gpsStateMessage().contains("현재 위치를 확인할 수 없어 방향 안내를 잠시 멈춥니다. 새 위치를 기다려 주세요."))
            assertEquals(identity, f.stableStateIdentity())
            assertSame(evidence, field(f.main, "officialEnvironmentGpsEvidence"))
            assertSame(evidence, (field(f.main, "runtimeGpsQualityGate") as RuntimeMeasuredQualityGate).current(f.runtime.now))
            f.runtime.start(id); f.runtime.done(id)
            assertEquals("Time-expired GPS evidence must cancel the accepted answer before native START", 1, f.cancelled)
            assertEquals(0, f.completed)
            assertEquals(1, f.runtime.stops)
            val fresh = CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext())
            val waitingMessage = f.gpsStateMessage()
            assertTrue(fresh.isCurrent(f.main.currentStateSpeechReader()))
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, dispatchCapturedStatus(f, f.actor,
                waitingMessage, fresh, f.main.captureCommandSpeechResponseOwner(fresh)))
            val waitingId = f.runtime.spoken.last().id
            assertEquals(waitingMessage, f.runtime.spoken.last().message)
            f.runtime.start(waitingId); f.runtime.done(waitingId)
            assertEquals(1, f.completed)
        }
    }

    @Test fun runtimeGpsAnswerStillStartsImmediatelyBeforeEvidenceExpiry() {
        Fixture(manualNavigatorClock = true).use { f ->
            f.prepareLimitedRuntimeGps()
            f.ageRuntimeGpsToBeforeExpiry()
            val message = f.gpsStateMessage()
            val source = CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext())
            val owner = f.main.captureCommandSpeechResponseOwner(source)
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                dispatchCapturedStatus(f, f.actor, message, source, owner))
            f.runtime.advanceBy(99L)
            assertEquals(message, f.gpsStateMessage())
            assertTrue(source.isCurrent(f.main.currentStateSpeechReader()))
            val id = f.runtime.spoken.single().id
            f.runtime.start(id); f.runtime.done(id)
            assertEquals(1, f.completed)
            assertEquals(0, f.cancelled)
        }
    }

    @Test fun aFreshSameMeaningGpsSampleKeepsTheAnswerAlivePastTheOriginalExpiry() {
        Fixture(manualNavigatorClock = true).use { f ->
            f.prepareLimitedRuntimeGps()
            val original = f.ageRuntimeGpsToBeforeExpiry()
            val message = f.gpsStateMessage()
            val source = CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext())
            val owner = f.main.captureCommandSpeechResponseOwner(source)
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                dispatchCapturedStatus(f, f.actor, message, source, owner))
            f.runtime.advanceBy(50L)
            val refreshed = f.observeRuntimeGps(trusted = true, accuracy = 31.0)
            assertEquals(original.status, refreshed.status)
            assertEquals(original.detail, refreshed.detail)
            assertNotEquals(original.observedAtElapsedRealtimeMs, refreshed.observedAtElapsedRealtimeMs)
            f.runtime.advanceBy(150L)
            assertEquals(message, f.gpsStateMessage())
            assertTrue(source.isCurrent(f.main.currentStateSpeechReader()))
            val id = f.runtime.spoken.single().id
            f.runtime.start(id); f.runtime.done(id)
            assertEquals(1, f.completed)
            assertEquals(0, f.cancelled)
        }
    }

    @Test fun runtimeGpsLifetimeUsesTheInclusiveMinimumOfProfileAndObservationTtl() {
        val profileTtl = requireNotNull(WalkSafeEnvironmentProfiles.active(BuildConfig.DEBUG))
            .officialEnvironment.maximumMeasuredEvidenceAgeMs
        for (observationTtl in listOf(profileTtl / 2L, Long.MAX_VALUE)) Fixture(manualNavigatorClock = true).use { f ->
            f.prepareLimitedRuntimeGps()
            val original = field(f.main, "officialEnvironmentGpsEvidence") as MeasuredEnvironmentEvidence
            val bounded = original.copy(maximumEvidenceAgeMs = observationTtl)
            val gate = field(f.main, "runtimeGpsQualityGate") as RuntimeMeasuredQualityGate
            set(f.main, "officialEnvironmentGpsEvidence", gate.observe(bounded, f.runtime.now))
            f.ageRuntimeGpsToBeforeExpiry(marginMs = 0L)
            val message = f.gpsStateMessage()
            assertTrue(message.contains("위치 오차가 커서"))
            val source = CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext())
            assertTrue("Evidence remains valid at the inclusive TTL boundary", source.isCurrent(f.main.currentStateSpeechReader()))
            f.runtime.advanceBy(1L)
            assertTrue(f.gpsStateMessage().contains("현재 위치를 확인할 수 없어 방향 안내를 잠시 멈춥니다. 새 위치를 기다려 주세요."))
            assertFalse("One millisecond beyond the shorter TTL must retire the old meaning", source.isCurrent(f.main.currentStateSpeechReader()))
            assertSame(bounded, field(f.main, "officialEnvironmentGpsEvidence"))
        }
    }

    @Test fun runtimeGpsSpeechClockRejectsFutureNegativeAndExtremeAges() {
        for (case in listOf("future", "negative-clock", "negative-observation", "negative-ttl", "very-old", "overflow")) {
            Fixture(manualNavigatorClock = true).use { f ->
                f.prepareLimitedRuntimeGps()
                val source = CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext())
                val original = field(f.main, "officialEnvironmentGpsEvidence") as MeasuredEnvironmentEvidence
                val observedAt = when (case) {
                    "future" -> f.runtime.now + 1L
                    "negative-observation" -> -1L
                    "very-old" -> 0L
                    "overflow" -> Long.MAX_VALUE
                    else -> requireNotNull(original.observedAtElapsedRealtimeMs)
                }
                val now = when (case) {
                    "negative-clock" -> -1L
                    "very-old" -> Long.MAX_VALUE
                    "overflow" -> Long.MIN_VALUE
                    else -> f.runtime.now
                }
                val evidence = original.copy(observedAtElapsedRealtimeMs = observedAt,
                    maximumEvidenceAgeMs = if (case == "negative-ttl") -1L else original.maximumEvidenceAgeMs)
                val profile = requireNotNull(WalkSafeEnvironmentProfiles.active(BuildConfig.DEBUG)).officialEnvironment
                val assessment = OfficialEnvironmentPolicy.assess(f.lifecycle.snapshot().epoch, now,
                    evidence, null, null, profile)
                assertEquals(case, EnvironmentEvidenceStatus.UNKNOWN,
                    assessment.factorStatuses[OfficialEnvironmentFactor.GPS_QUALITY])
                set(f.main, "officialEnvironmentGpsEvidence", evidence)
                set(f.main, "currentStateSpeechElapsedRealtime", { now })
                assertFalse("The lease must agree with policy for $case", source.isCurrent(f.main.currentStateSpeechReader()))
                assertTrue(CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext()).isCurrent(f.main.currentStateSpeechReader()))
                assertSame(evidence, field(f.main, "officialEnvironmentGpsEvidence"))
            }
        }
    }

    @Test fun theGpsSpeechClockIsNotReadForMissingEvidenceInactiveAnswersOrOrdinaryCommands() {
        Fixture(manualNavigatorClock = true).use { f ->
            f.prepareRoutePresentation()
            set(f.main, "currentStateSpeechElapsedRealtime", { error("No owned GPS evidence needs a clock") })
            val missing = CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext())
            assertTrue(missing.isCurrent(f.main.currentStateSpeechReader()))
            f.pause()
            val inactive = CurrentStateSpeechLease.capture(f.main.captureCurrentStateSpeechContext())
            assertTrue(inactive.isCurrent(f.main.currentStateSpeechReader()))
        }
        Fixture(manualNavigatorClock = true).use { f ->
            f.prepareLimitedRuntimeGps()
            set(f.main, "currentStateSpeechElapsedRealtime", { error("Ordinary replies have no state lease") })
            val owner = f.main.captureCommandSpeechResponseOwner()
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED,
                f.main.dispatchCommandSpeechResponse(f.actor, "일반 응답", true, null,
                    isCurrent = { owner.isCurrent(f.main) }, completed = { f.completed++ },
                    failed = { f.failed++ }, cancelled = { f.cancelled++ }))
            val id = f.runtime.spoken.single().id
            f.runtime.start(id); f.runtime.done(id)
            assertEquals(1, f.completed)
            assertEquals(0, f.cancelled)
        }
    }

    private fun dispatchCapturedStatus(f: Fixture, actor: AndroidFeedbackActuator, message: String,
        source: CurrentStateSpeechLease, owner: MainActivity.CommandSpeechResponseOwner) =
        f.main.dispatchCommandSpeechResponse(actor, message, true, source,
            isCurrent = { owner.isCurrent(f.main) && field(f.main, "feedbackActuator") === actor },
            completed = { f.completed++ }, failed = { f.failed++ }, cancelled = { f.cancelled++ })

    /** Original MessagePolicy inputs and exact owner completion, applied to this Main's existing policy/Actor. */
    private class ProducedRiskInput(private val f: Fixture) {
        val policy = field(f.main, "feedbackPolicy") as WalkSafeFeedbackPolicy
        val output = SpeechOutputDiagnostics()
        val actions = mutableListOf<FeedbackAction>()
        var confirmed = 0; var failed = 0; var cancelled = 0
        private val messagePolicy = MessagePolicy().forFeedbackQueue()
        private val config = FeedbackPolicyConfig()
        private val firstAt = f.runtime.now
        private var current: FeedbackCandidate? = null
        init { f.actor.speechOutputListener = output::record }
        fun observeFarMetric() {
            val facing = messagePolicy.evaluate(MetricDepthDecision("person", DepthSource.ARCORE_FULL_DEPTH,
                4f, trend = Trend.STABLE, confidenceFinal = .90f, trackKey = "persistent-person"), f.runtime.now).userFacing
            assertEquals(MessageLevel.NONE, facing.messageLevel)
            assertNull(facing.message)
        }
        fun advanceCautionFor(durationMs: Long) {
            val until = f.runtime.now + durationMs
            while (f.runtime.now < until) {
                offer(.57f, 2.7f, MessageLevel.CAUTION)
                f.runtime.advanceBy(100L)
            }
        }
        fun offer(confidence: Float, distanceM: Float, level: MessageLevel, completeLater: Boolean = true): FeedbackAction? {
            val at = f.runtime.now
            val facing = messagePolicy.evaluate(MetricDepthDecision("person", DepthSource.ARCORE_FULL_DEPTH,
                distanceM, trend = Trend.APPROACHING, confidenceFinal = confidence, trackKey = "persistent-person"), at).userFacing
            assertEquals(level, facing.messageLevel)
            val candidate = FeedbackCandidate("persistent-person", requireNotNull(facing.message), facing.messageLevel,
                DepthSource.ARCORE_FULL_DEPTH, confidence, at + 50_000L,
                10 + ((at - firstAt) / 100L).toInt(), 1_000L + at - firstAt, false,
                validUntilMs = at + config.maxObservationAgeMs, riskDistanceM = distanceM,
                observedAtElapsedRealtimeMs = at, observationMaxAgeMs = config.maxObservationAgeMs)
            assertTrue("The actual producer candidate remains eligible for feedback", candidate.confidence >= config.minConfidence)
            current = candidate
            val action = policy.evaluate(candidate, true, at) ?: return null
            assertTrue(policy.claimFeedbackDelivery(action, at, at))
            assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.actor.emit(action,
                onSpeechCompleted = {
                    if (policy.confirmFeedbackDelivery(action, at, f.runtime.now)) confirmed++
                }, onSpeechFailed = { failed++; policy.rejectUndeliveredFeedback(action, at) },
                onSpeechCancelled = { cancelled++; policy.rejectUndeliveredFeedback(action, at) },
                isStillValidAtStart = { policy.hasClaimedFeedbackDelivery(action) && f.runtime.now <= action.validUntilMs &&
                    action.deliveryKey in policy.activeFeedbackDeliveryKeys(listOf(requireNotNull(current)), true, f.runtime.now) }
            ).speech)
            actions += action
            val id = f.runtime.spoken.last().id
            f.runtime.start(id)
            if (completeLater) f.runtime.postDelayed(Runnable { f.runtime.done(id) }, 1_000L)
            return action
        }
    }

    private class Fixture(ready: Boolean = true, manualNavigatorClock: Boolean = false) : AutoCloseable {
        val main = MainActivity()
        // These owner/TTL tests supply a confirmed failure after one measured period.
        // Main's three-period GPS recovery window is exercised by the GPS/Main integration tests.
        private val representationGpsGate = RuntimeMeasuredQualityGate(
            maximumTransientFailureMs = 3_000L, gpsFailureConfirmationPeriods = 1,
        ).also { set(main, "runtimeGpsQualityGate", it) }
        val runtime = FakeRuntime()
        val actor = AndroidFeedbackActuator(runtime = runtime)
        val lifecycle = activeLifecycle("state-answer", runtime)
        val navigator = if (manualNavigatorClock) RouteNavigator(RouteNavigatorConfig(offRouteConfirmSamples = 1),
            nowElapsedRealtimeMs = { runtime.now }) else field(main, "routeNavigator") as RouteNavigator
        val route = WalkingRoute("STAIR_AVOID", WalkingRouteSummary(150, 180),
            listOf(RoutePoint(37.0, 127.0), RoutePoint(37.0005, 127.0), RoutePoint(37.0005, 127.001)),
            listOf(WalkingRouteGuidePoint(0, RoutePoint(37.0005, 127.0), "원 경로", 55, 95, turnType = 13)),
            providerRouteId = "same-route")
        val registry = field(main, "commandSpeechResponseCallbacks") as UtteranceCallbackRegistry
        var completed = 0; var failed = 0; var cancelled = 0
        init {
            set(main, "currentStateSpeechElapsedRealtime", { runtime.now })
            if (manualNavigatorClock) set(main, "routeNavigator", navigator)
            val session = GatewayFieldSession.verified("https://gateway.example.test", "actor_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "walksafe_field_session=v2.current.state.cookie", Long.MAX_VALUE)
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
            check(GatewaySessionProcessCoordinator.publishVerified(operation, session, FirstRunOnboardingPolicy.initial()))
            set(main, "reporterUserId", session.actorId)
            (field(main, "permissionSessionPolicy") as PermissionSessionPolicy).authenticated(session.actorId)
            set(main, "walkSessionLifecycle", lifecycle)
            set(main, "isActivityForeground", lifecycle.snapshot().isForeground)
            setPage(main, "GUIDANCE")
            navigator.setRoute(route)
            navigator.update(TrustedLocation(37.0, 127.0, 1f, 1_000L), 1_000L, false)
            set(main, "isRouteActive", navigator.hasRoute())
            set(main, "currentDestination", route.polyline.last())
            setActor(actor)
            if (ready) runtime.ready()
        }
        fun prepareRoutePresentation() {
            val session = requireNotNull(GatewaySessionProcessCoordinator.snapshot().session)
            val onboarding = FirstRunOnboardingPolicy.initialEmailAccount().copy(
                stage = FirstRunOnboardingStage.COMPLETE, ageBand = FirstRunAgeBand.VERIFIED_14_PLUS,
                completedReceiptHashes = listOf(FirstRunOnboardingStage.VERIFIED_LOGIN,
                    FirstRunOnboardingStage.PURPOSE_AND_SAFETY, FirstRunOnboardingStage.FP004_TRAINING)
                    .associateWith { FirstRunReceiptHash.fromSha256Hex("a".repeat(64)) },
                verifiedActorBinding = FirstRunOpaqueActorBinding.fromProvider(session.actorId))
            assertTrue(GatewaySessionProcessCoordinator.publishVerified(
                requireNotNull(GatewaySessionProcessCoordinator.beginOperation()), session, onboarding))
            set(main, "firstRunOnboardingSnapshot", onboarding)
            set(main, "postLoginDeviceCheckSnapshot", PostLoginDeviceCheckSnapshot(PostLoginDeviceCheckState.FULL,
                session.actorId, GatewaySessionProcessCoordinator.snapshot().generation, 1L))
            val epoch = lifecycle.snapshot().epoch
            val authority = GatewayWalkAuthorityController(elapsedClock = { runtime.now })
            assertEquals(GatewayWalkAuthorityCompletion.LEASE_ACTIVE, authority.completeStart(
                requireNotNull(authority.beginStart(epoch)), GatewayWalkStartResult.Granted(GatewayWalkLease(
                    "GRANTED", session.actorId, "fixture-device", epoch.walkSessionId, "fixture-lease", 1L,
                    runtime.now, 1_000_000L, runtime.now, 1_000_000L))))
            set(main, "gatewayWalkAuthorityController", authority)
        }
        fun failReroute() {
            navigator.setRoute(route, destination = route.polyline.last())
            navigator.update(TrustedLocation(37.0, 127.01, 5f, runtime.now), runtime.now, false)
            assertTrue(navigator.approveReroute().shouldReroute)
            assertNotNull(navigator.rerouteRequestFailed())
            // Main's failed-provider state after the diagnostic reroute_failed text is overwritten.
            set(main, "directionGuidancePauseReason", "tmap_unavailable")
            set(main, "latestNavigationState", "navigation=direction_paused")
            set(main, "latestTmapOnRoute", false)
        }
        // SDK observations are controlled; runtime/session/route/decision and output policy remain real.
        fun routeInput(permission: Boolean = true, service: Boolean = true, fresh: Boolean = true) =
            main.currentRouteGuidanceDisplayInput(collectionAllowsWork = { true },
                trustedLocationAvailable = { fresh }, locationPermissionReady = { permission },
                locationServiceEnabled = { service })
        fun routeStateMessage(permission: Boolean = true, service: Boolean = true, fresh: Boolean = true): String {
            val presentation = DestinationGuidancePresentationPolicy.present(routeInput(permission, service, fresh),
                GuidanceFeatureDisplayInput(true, false, null), retryAvailable = true)
            val guidance = MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
                .findVirtual(MainActivity::class.java, "nativeGuidanceStatusMessage",
                    MethodType.methodType(String::class.java, DestinationGuidancePresentation::class.java,
                        Boolean::class.javaPrimitiveType!!))
                .bindTo(main).invokeWithArguments(presentation, false) as String
            return main.currentStateSpeechMessage(lifecycle.snapshot(), null, ::homeStatus,
                { guidance }, { "보행 일시정지" }, { "보행 안전 중지" })
        }
        private val gpsProfile get() = requireNotNull(WalkSafeEnvironmentProfiles.active(BuildConfig.DEBUG)).officialEnvironment
        private val gpsGate get() = field(main, "runtimeGpsQualityGate") as RuntimeMeasuredQualityGate
        fun prepareLimitedRuntimeGps() {
            prepareRoutePresentation()
            assertEquals(EnvironmentEvidenceStatus.PASS, observeRuntimeGps(true, 3.0).status)
            runtime.advanceBy(100L)
            assertEquals(EnvironmentEvidenceStatus.PASS, observeRuntimeGps(true, 30.0).status)
            runtime.advanceBy(3_000L)
            assertEquals(EnvironmentEvidenceStatus.FAIL, observeRuntimeGps(true, 30.0).status)
            val guard = OfficialEnvironmentRuntimeGuard(lifecycle.snapshot().epoch, gpsProfile)
            val decision = guard.onAssessment(gpsAssessment(), runtime.now)
            assertEquals(OfficialEnvironmentRuntimeAction.CONTINUE, decision.action)
            assertFalse(decision.navigationOutputsAllowed)
            assertTrue(decision.cameraOutputsAllowed)
            set(main, "officialEnvironmentRuntimeGuard", guard)
            set(main, "lastOfficialEnvironmentUnavailableFactors", decision.unavailableFactors)
            set(main, "officialEnvironmentOutputsAllowed", true)
            set(main, "directionGuidancePauseReason", "environment_gps_quality")
            set(main, "latestTmapOnRoute", false)
        }
        fun ageRuntimeGpsToBeforeExpiry(marginMs: Long = 100L): MeasuredEnvironmentEvidence {
            val evidence = field(main, "officialEnvironmentGpsEvidence") as MeasuredEnvironmentEvidence
            val maximumAge = minOf(gpsProfile.maximumMeasuredEvidenceAgeMs,
                requireNotNull(evidence.maximumEvidenceAgeMs))
            val target = requireNotNull(evidence.observedAtElapsedRealtimeMs) + maximumAge - marginMs
            runtime.advanceBy(target - runtime.now)
            return evidence
        }
        fun observeRuntimeGps(trusted: Boolean?, accuracy: Double?): MeasuredEnvironmentEvidence {
            val candidate = OfficialEnvironmentPolicy.assessGpsQuality(lifecycle.snapshot().epoch, runtime.now,
                GpsQualityObservation(lifecycle.snapshot().epoch, runtime.now, trusted, accuracy), gpsProfile)
            val evidence = gpsGate.observe(candidate, runtime.now)
            set(main, "officialEnvironmentGpsEvidence", evidence)
            (field(main, "officialEnvironmentRuntimeGuard") as OfficialEnvironmentRuntimeGuard?)?.let { guard ->
                val before = guard.decision()
                val after = guard.onAssessment(gpsAssessment(), runtime.now)
                assertEquals(OfficialEnvironmentRuntimeAction.CONTINUE, after.action)
                assertEquals(before.unavailableFactors, after.unavailableFactors)
            }
            return evidence
        }
        private fun gpsAssessment(): OfficialEnvironmentAssessment {
            val epoch = lifecycle.snapshot().epoch
            val pass = EnvironmentEvidenceStatus.PASS
            val camera = MeasuredEnvironmentEvidence(OfficialEnvironmentFactor.CAMERA_QUALITY, epoch,
                runtime.now, pass, gpsProfile.cameraQualityProfileId, gpsProfile.maximumMeasuredEvidenceAgeMs, "PASSED")
            return OfficialEnvironmentPolicy.assess(epoch, runtime.now,
                field(main, "officialEnvironmentGpsEvidence") as MeasuredEnvironmentEvidence?, camera,
                OfficialEnvironmentUserConfirmation(epoch, pass, pass, pass, pass, pass, pass, pass), gpsProfile)
        }
        // Actual GPS detail formatter + CurrentState selector; Android callback/whole SDK presentation is not run.
        fun gpsStateMessage(): String {
            val measured = main.officialEnvironmentFailureSpeech(gpsAssessment(),
                setOf(OfficialEnvironmentFactor.GPS_QUALITY))
            return main.currentStateSpeechMessage(lifecycle.snapshot(), null, ::homeStatus,
                { measured }, { "보행 일시정지" }, { "보행 안전 중지" })
        }
        fun stableStateIdentity(): List<Any?> = listOf(
            lifecycle.snapshot(), navigator.currentRouteId(), navigator.currentTactileRouteProgress(),
            navigator.pendingDecisionToken(), homeStatus(), field(main, "positionGuidancePaused"),
            field(main, "directionGuidancePauseReason"), field(main, "feedbackActuator"),
            field(main, "feedbackLifecycleGeneration"), field(main, "routeRequestGeneration"),
            field(main, "voiceRecognitionGeneration"), field(main, "commandSpeechResponseGeneration"),
            GatewaySessionProcessCoordinator.snapshot().generation,
        )
        fun setActor(value: AndroidFeedbackActuator) = MainActivityControllerTestFixture.replaceActuator(main, value)
        fun register() = registry.registerLatest("state-response", {}, {})
        fun readiness(owner: MainActivity.CommandSpeechResponseOwner) = registry.commandSpeechReadiness("state-response",
            owner.isCurrent(main) && field(main, "feedbackActuator") === actor, actor.isSpeechInitializing(), runtime.now, 6_000L)
        fun dispatch(source: CurrentStateSpeechLease?, owner: MainActivity.CommandSpeechResponseOwner) =
            main.dispatchCommandSpeechResponse(actor, "현재 경로 상태", true, source,
                isCurrent = { owner.isCurrent(main) && field(main, "feedbackActuator") === actor },
                completed = { completed++ }, failed = { failed++ }, cancelled = { cancelled++ })
        fun pause() { lifecycle.handle(WalkSessionEvent.RecheckRequested) }
        fun homeStatus(): String = MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
            .findVirtual(MainActivity::class.java, "homeRouteStatusMessage", MethodType.methodType(String::class.java))
            .bindTo(main).invokeWithArguments() as String
        override fun close() {
            actor.close()
            GatewaySessionProcessCoordinator.clear(GatewaySessionProcessCoordinator.snapshot().generation)
        }
    }

    companion object {
        private fun field(main: MainActivity, name: String): Any? = MainActivityControllerTestFixture.field(name)
            .apply { isAccessible = true }.get(main)
        private fun set(main: MainActivity, name: String, value: Any?) = MainActivityControllerTestFixture.field(name)
            .apply { isAccessible = true }.set(main, value)
        private fun setPage(main: MainActivity, name: String) {
            val type = Class.forName("kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController\$NativeUiPage")
            val page = type.enumConstants.first { (it as Enum<*>).name == name }
            MethodHandles.privateLookupIn(MainActivity::class.java, MethodHandles.lookup())
                .findVirtual(MainActivity::class.java, "setNativeUiPage", MethodType.methodType(Void.TYPE, type))
                .bindTo(main).invokeWithArguments(page)
        }
        private fun activeLifecycle(id: String, runtime: FakeRuntime): WalkSessionLifecycle {
            val lifecycle = WalkSessionLifecycle(sessionIdFactory = { id }, clock = { runtime.now }, elapsedRealtimeClock = { runtime.now })
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            val epoch = lifecycle.snapshot().epoch
            val plan = WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL)
            val collector = WalkSessionReadinessCollector(epoch, plan)
            plan.requiredRequirements.forEach { collector.record(WalkSessionReadinessObservation(epoch, it, WalkSessionReadinessStatus.READY)) }
            lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(collector.build(runtime.now)))
            lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
            return lifecycle
        }
    }

    private class FakeRuntime : FeedbackSpeechRuntime, FeedbackSpeechScheduler {
        data class Spoken(val message: String, val id: String)
        private data class Task(val due: Long, val order: Long, val runnable: Runnable)
        var now = 1_000L
        var stops = 0
        val spoken = mutableListOf<Spoken>()
        private var initialized: ((Int) -> Unit)? = null
        private var listener: FeedbackSpeechRuntime.Listener? = null
        private val tasks = mutableListOf<Task>()
        private var sequence = 0L
        override val scheduler: FeedbackSpeechScheduler get() = this
        override val engineInitialized: Boolean get() = initialized != null
        override fun elapsedRealtime(): Long = now
        override fun initialize(onInitialized: (Int) -> Unit) { initialized = onInitialized }
        override fun setLanguage(language: Locale): Int = TextToSpeech.LANG_AVAILABLE
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
        override fun stop() { stops++ }
        override fun shutdown() = Unit
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener) = true
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) = Unit
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = error("No progress tone in speech hold tests")
        override fun vibrate(patternMs: LongArray) = true
        override fun post(task: Runnable) = postDelayed(task, 0L)
        override fun postDelayed(task: Runnable, delayMs: Long): Boolean {
            tasks += Task(now + delayMs.coerceAtLeast(0L), sequence++, task)
            return true
        }
        override fun removeCallbacks(task: Runnable) { tasks.removeAll { it.runnable === task } }
        override fun removeCallbacksAndMessages(token: Any?) { tasks.clear() }
        fun ready() { requireNotNull(initialized).invoke(TextToSpeech.SUCCESS); advanceBy(0L) }
        fun start(id: String) { requireNotNull(listener).onStart(id); advanceBy(0L) }
        fun done(id: String) { requireNotNull(listener).onDone(id); advanceBy(0L) }
        fun advanceBy(deltaMs: Long) {
            require(deltaMs >= 0L)
            val target = now + deltaMs
            while (true) {
                val next = tasks.filter { it.due <= target }.minWithOrNull(compareBy<Task> { it.due }.thenBy { it.order }) ?: break
                tasks.remove(next)
                now = next.due
                next.runnable.run()
            }
            now = target
        }
    }
}
