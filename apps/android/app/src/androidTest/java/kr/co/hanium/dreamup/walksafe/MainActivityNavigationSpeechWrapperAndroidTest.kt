package kr.co.hanium.dreamup.walksafe

import android.Manifest
import android.app.Application
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioManager
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.location.LocationCallback
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.feedback.*
import kr.co.hanium.dreamup.walksafe.fieldlog.NoopFieldSessionLog
import kr.co.hanium.dreamup.walksafe.navigation.*
import kr.co.hanium.dreamup.walksafe.navigation.session.GuidanceRecoveryTiming
import kr.co.hanium.dreamup.walksafe.navigation.session.LocationCollectionOwner
import kr.co.hanium.dreamup.walksafe.navigation.session.PositioningCollectionBinding
import kr.co.hanium.dreamup.walksafe.navigation.session.PositioningSessionController
import kr.co.hanium.dreamup.walksafe.navigation.session.RouteGuidanceController
import kr.co.hanium.dreamup.walksafe.navigation.session.RouteAcquisitionController
import kr.co.hanium.dreamup.walksafe.network.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.voice.KoreanOfflineVoiceSelection
import kr.co.hanium.dreamup.walksafe.voice.output.SpeechOutputController
import kr.co.hanium.dreamup.walksafe.voice.output.SpeechOutputActuatorFactory
import kr.co.hanium.dreamup.walksafe.ui.workflow.NativeWorkflowController.NativeUiPage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual Main NAV wrapper on Android, without Main.onCreate/onDestroy or any HTTP/login call.
 * Nine purpose-bound fields attach prepared policy state and a platform speech runtime. Main's
 * clock, Looper, Handler, OS observations, authorization, gates, reservation and completion remain
 * real. Synthetic onboarding/device/environment evidence is not a physical readiness measurement.
 * Synthetic Navigator endpoint evidence also enters Main's automatic arrival completion.
 * Gateway operations/publication and page transitions use the production owners without credential HTTP.
 * HOME-operated start, Main's location callback, relative turns, tactile/STOP and native TTS remain
 * outside this wrapper probe; runtime START/DONE/ERROR callbacks are simulated.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityNavigationSpeechWrapperAndroidTest {
    @Test(timeout = 20_000L)
    fun actualMainHandlerRetriesUnknownFacingAfterTheConfiguredGrace() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertNull("Never replace an existing process account", GatewaySessionProcessCoordinator.snapshot().session)
        assertNull("Never replace existing process onboarding", GatewaySessionProcessCoordinator.snapshot().restoredFirstRunSnapshot)

        var fixture: Fixture? = null
        try {
            fixture = onMain(instrumentation) { Fixture(instrumentation) }
            val f = fixture
            onMain(instrumentation) { f.prepare() }
            instrumentation.waitForIdleSync()
            val (firstCheckAt, waiting) = onMain(instrumentation) {
                assertEquals("tts=ready", f.actuator.statusText())
                f.assertActualGates()
                val now = SystemClock.elapsedRealtime()
                now to f.navigator.update(f.fix(now), now, false, facingObservation = null)
            }
            assertEquals("The real Main Navigator must wait for its direction recheck", "guidance_facing_recheck_wait", waiting.reason)
            assertNull(waiting.instruction)
            onMain(instrumentation) {
                f.dispatch(waiting)
                assertNotNull("Main must schedule its Android Handler retry", field(f.main, "navigationSpeechRetryRunnable"))
            }

            val grace = onMain(instrumentation) { requireNotNull(f.navigator.currentDirectionGraceSnapshot()) }
            val graceOnset = requireNotNull(grace.onsetElapsedRealtimeMs)
            val graceDeadline = requireNotNull(grace.deadlineElapsedRealtimeMs)
            assertEquals(firstCheckAt, graceOnset)
            assertEquals("Actual Main must use the production direction grace",
                GuidanceRecoveryTiming.DIRECTION_GRACE_MS, graceDeadline - graceOnset)
            val dispatchDeadline = minOf(graceDeadline + 750L, firstCheckAt + LocationTrustConfig().maxAgeMs)
            assertTrue("A deadline observation must fit within the original location freshness", graceDeadline < dispatchDeadline)
            var generalCompleted = 0
            var generalFailed = 0
            val general = onMain(instrumentation) {
                assertTrue(SystemClock.elapsedRealtime() < graceDeadline)
                assertEquals(NavigationSpeechDispatchResult.ACCEPTED, f.actuator.speakHomeCommandInteraction(
                    message = "현재 일반 안내를 끝까지 전달하는 중입니다.",
                    onCompleted = { generalCompleted++ }, onFailed = { generalFailed++ },
                ))
                f.runtime.spoken.single().also { f.runtime.start(it.id) }
            }
            instrumentation.waitForIdleSync()
            var lastRetry = onMain(instrumentation) { field(f.main, "navigationSpeechRetryRunnable") }
            var retryIdentityChanges = 0
            var retryNullObservations = 0
            // Hold a real output owner across the grace boundary. A persistent warning must
            // wait for this sentence's controlled DONE instead of flushing its native owner.
            while (SystemClock.elapsedRealtime() < graceDeadline + 25L) {
                Thread.sleep(50L)
                instrumentation.waitForIdleSync()
                val observed = onMain(instrumentation) {
                    assertEquals("Neither grace nor its expired warning may interrupt the current sentence", listOf(general), f.runtime.spoken)
                    assertEquals("No NAV may be requested while the current sentence owns output", 0, f.outcomes(SpeechOutputOutcome.REQUESTED))
                    assertNull(f.pendingCue())
                    assertNull(f.completedCue())
                    assertEquals(0, generalCompleted)
                    assertEquals(0, generalFailed)
                    assertEquals(0, f.runtime.stops)
                    field(f.main, "navigationSpeechRetryRunnable")
                }
                if (observed !== lastRetry) retryIdentityChanges += 1
                if (observed == null) retryNullObservations += 1
                lastRetry = observed
            }
            onMain(instrumentation) { f.runtime.done(general.id) }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                assertEquals(1, generalCompleted)
                assertEquals(0, generalFailed)
            }
            val warning = awaitMainSpeech(f, dispatchDeadline) { it.message == DIRECTION_PROBLEM_NOTICE }
            val action = completeDirectionWarningAndAwaitOverview(f, warning, graceDeadline, dispatchDeadline)
            val reservedCue = onMain(instrumentation) {
                assertEquals(3, f.runtime.spoken.size)
                assertTrue(action.message.contains("회전이 있습니다"))
                val routeRequest = f.events.single {
                    it.kind == SpeechOutputKind.NAVIGATION && it.outcome == SpeechOutputOutcome.REQUESTED
                }
                assertTrue("Main must not dispatch an unknown-facing overview before its actual grace deadline",
                    routeRequest.atElapsedRealtimeMs >= graceDeadline)
                assertTrue("Actual Handler must dispatch within the fresh-evidence observation bound",
                    routeRequest.atElapsedRealtimeMs <= dispatchDeadline)
                assertNull("Delivered overview must clear its retry", field(f.main, "navigationSpeechRetryRunnable"))
                assertEquals("Actual dispatch must reserve the pending cue", "guidance_in_flight",
                    f.navigator.retryGuidance(SystemClock.elapsedRealtime(), facingObservation = null)?.reason)
                val cue = f.pendingCue()
                assertNotNull("Actual Handler must own the reserved cue", cue)
                f.runtime.start(action.id) // Controlled platform callback; no native acoustic claim.
                cue
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                assertEquals(1, f.outcomes(SpeechOutputOutcome.STARTED))
                assertEquals(0, f.outcomes(SpeechOutputOutcome.DONE))
                f.runtime.done(action.id)
            }
            instrumentation.waitForIdleSync()
            val (requestsAfterDone, submissionsAfterDone) = onMain(instrumentation) {
                assertEquals("The actual action terminal is delivered exactly once", 1, f.outcomes(SpeechOutputOutcome.DONE))
                val completed = RouteNavigator::class.java.getDeclaredField("completedSpeechCue").apply { isAccessible = true }.get(f.navigator)
                assertEquals("Only the actual reserved cue may be acknowledged", reservedCue, completed)
                assertFalse(f.navigator.hasPendingInstruction())
                val counts = f.outcomes(SpeechOutputOutcome.REQUESTED) to f.runtime.spoken.size
                f.runtime.done(action.id) // A duplicate platform terminal must not replay the action.
                counts
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                assertEquals(1, f.outcomes(SpeechOutputOutcome.DONE))
                assertEquals(requestsAfterDone, f.outcomes(SpeechOutputOutcome.REQUESTED))
                assertEquals(submissionsAfterDone, f.runtime.spoken.size)
                assertEquals(1, f.runtime.spoken.count { it.message == action.message })
                assertEquals(1, f.runtime.spoken.count { it.message == DIRECTION_PROBLEM_NOTICE })
                assertEquals(0, f.runtime.stops)
                Log.i(TAG, "PASS actualHandler=true directionGraceSilent=true currentSentenceCompletedBeforeWarning=true " +
                    "warningRequested=1 warningStarted=1 warningDone=1 overviewAcknowledgedOnce=true " +
                    "retryIdentityChanges=$retryIdentityChanges retryNullObservations=$retryNullObservations nativeTts=false")
            }
        } finally {
            fixture?.let { f -> onMain(instrumentation) { f.close() } }
        }
    }

    @Test(timeout = 30_000L)
    fun actualMainAutomaticArrivalEndsWalkAfterFinalSpeech() = withAutomaticArrival { f, finalRuntime, finalId ->
        onMain(f.instrumentation) {
            finalRuntime.start(finalId)
            finalRuntime.done(finalId)
        }
        f.instrumentation.waitForIdleSync()
        onMain(f.instrumentation) {
            assertEquals(1, f.events.count { it.kind == SpeechOutputKind.INTERACTION && it.outcome == SpeechOutputOutcome.DONE })
            assertFalse(f.navigator.hasRoute())
            assertEquals(WalkSessionState.ENDED,
                (field(f.main, "walkSessionLifecycle") as WalkSessionLifecycle).snapshot().state)
            assertEquals("HOME", field(f.main, "nativeUiPage").toString())
            Log.i(TAG, "PASS automaticArrival=true routeCleared=true walkEnded=true home=true " +
                "endInteractionDone=1 activityOnCreate=false syntheticLocation=true")
        }
    }

    @Test(timeout = 30_000L)
    fun actualMainArrivalDoneEndsAfterSameSessionGeneralOperationAndStorageRetirement() {
        for (republishSameSession in listOf(false, true)) {
            withAutomaticArrival { f, finalRuntime, finalId ->
                onMain(f.instrumentation) { finalRuntime.start(finalId) }
                f.instrumentation.waitForIdleSync()
                onMain(f.instrumentation) {
                    assertEquals(1, finalRuntime.events.count { it.kind == SpeechOutputKind.INTERACTION && it.outcome == SpeechOutputOutcome.STARTED })
                    assertTrue(f.actuator.isAppSpeechActive())
                    val before = GatewaySessionProcessCoordinator.snapshot()
                    assertSame(finalRuntime, f.runtime)
                    val stopsBeforeOperation = finalRuntime.stops
                    val operation = f.beginGeneralOperation()
                    val pending = GatewaySessionProcessCoordinator.snapshot()
                    assertSame("The real pending credential operation must retain this verified session", before.session, pending.session)
                    assertTrue(pending.generation > before.generation)
                    assertEquals(operation.generation, pending.generation)
                    assertEquals(operation.operationId, pending.inFlightOperationId)
                    // Main.onCreate is outside this wrapper, so invoke the storage-retirement hook
                    // its gateway observer calls. Native START has already admitted the utterance.
                    f.invoke("invalidateSpeechOutputStorageBinding")
                    if (republishSameSession) {
                        // A normal busy/retry rollback re-publishes the same verified credential with
                        // its original onboarding binding through the owned process operation.
                        f.republishSameSession(operation, before)
                        val republished = GatewaySessionProcessCoordinator.snapshot()
                        assertSame(before.session, republished.session)
                        assertSame(before.restoredFirstRunSnapshot, republished.restoredFirstRunSnapshot)
                        assertEquals(before.generation + 2L, republished.generation)
                        assertNull(republished.inFlightOperationId)
                        f.invoke("invalidateSpeechOutputStorageBinding")
                    }
                    assertEquals("A same-session operation after START must not force native TTS to stop",
                        stopsBeforeOperation, finalRuntime.stops)
                    assertSame(finalRuntime, f.runtime)
                    assertTrue(f.actuator.isAppSpeechActive())
                    assertEquals(WalkSessionState.PAUSED,
                        (field(f.main, "walkSessionLifecycle") as WalkSessionLifecycle).snapshot().state)
                    finalRuntime.done(finalId)
                }
                f.instrumentation.waitForIdleSync()
                onMain(f.instrumentation) {
                    val process = GatewaySessionProcessCoordinator.snapshot()
                    val device = field(f.main, "postLoginDeviceCheckSnapshot") as PostLoginDeviceCheckSnapshot
                    val actor = f.invoke("currentReporterUserId")
                    val arrival = field(f.main, "arrivalEndingOwner") as? RouteGuidanceController.ArrivalCompletion
                    assertEquals("sameSessionRepublished=$republishSameSession processGeneration=${process.generation} " +
                        "deviceGeneration=${device.bindingOrNull?.sessionGeneration} currentActor=$actor " +
                        "arrivalOwnerCurrent=${arrival?.isCurrent()}", WalkSessionState.ENDED,
                        (field(f.main, "walkSessionLifecycle") as WalkSessionLifecycle).snapshot().state)
                    assertEquals("HOME", field(f.main, "nativeUiPage").toString())
                    assertFalse(f.navigator.hasRoute())
                    assertFalse("The current output owner must have no active speech after END",
                        (field(f.main, "speechOutputController") as SpeechOutputController)
                            .currentActuatorOrNull()?.isAppSpeechActive() == true)
                    assertEquals(1, finalRuntime.spoken.count { it.message == "도착했습니다. 안내를 종료합니다" })
                    Log.i(TAG, "PASS automaticArrival=true sameSessionGeneralOperation=true sameSessionRepublished=$republishSameSession " +
                        "storageRetiredAfterStart=true " +
                        "nativeStopBeforeDone=false walkEnded=true home=true activityOnCreate=false syntheticLocation=true")
                }
            }
        }
    }

    @Test(timeout = 30_000L)
    fun actualMainArrivalFirstFailureTerminalsEndWalkAndIgnoreLateDone() {
        for (terminal in listOf("ERROR_SYNTHESIS", "STOP", "DISPATCH_ERROR", "ERROR_SERVICE")) {
            withAutomaticArrival(rejectFinalDispatch = terminal == "DISPATCH_ERROR") { f, finalRuntime, finalId ->
                val output = onMain(f.instrumentation) { field(f.main, "speechOutputController") as SpeechOutputController }
                if (terminal != "DISPATCH_ERROR") {
                    onMain(f.instrumentation) {
                        assertEquals(WalkSessionState.PAUSED,
                            (field(f.main, "walkSessionLifecycle") as WalkSessionLifecycle).snapshot().state)
                        assertNotNull(field(f.main, "arrivalEndingOwner"))
                        finalRuntime.start(finalId)
                    }
                    f.instrumentation.waitForIdleSync()
                    onMain(f.instrumentation) {
                        assertTrue("The actual final response must admit START before its first terminal", f.actuator.isAppSpeechActive())
                        assertEquals(1, finalRuntime.events.count {
                            it.kind == SpeechOutputKind.INTERACTION && it.outcome == SpeechOutputOutcome.STARTED
                        })
                        assertEquals(0, finalRuntime.events.count { it.outcome == SpeechOutputOutcome.DONE })
                        when (terminal) {
                            "ERROR_SYNTHESIS" -> finalRuntime.error(finalId)
                            "STOP" -> finalRuntime.stopped(finalId)
                            "ERROR_SERVICE" -> finalRuntime.serviceError(finalId)
                        }
                    }
                }
                f.instrumentation.waitForIdleSync()
                val ended = onMain(f.instrumentation) {
                    val walk = (field(f.main, "walkSessionLifecycle") as WalkSessionLifecycle).snapshot()
                    assertEquals("$terminal must finish this arrival without another question", WalkSessionState.ENDED, walk.state)
                    assertEquals(NativeUiPage.HOME, field(f.main, "nativeUiPage"))
                    assertFalse(f.navigator.hasRoute())
                    assertNull(f.navigator.pendingDecisionToken())
                    assertNull(field(f.main, "arrivalEndingOwner"))
                    assertNull(field(f.main, "navigationSpeechRetryRunnable"))
                    assertNull("Final speech must relinquish Main's actuator", field(f.main, "feedbackActuator"))
                    assertFalse(output.hasPendingCommandResponse())
                    assertEquals("The final native runtime is released once", 1, finalRuntime.shutdowns)
                    assertTrue(finalRuntime.stops > 0)
                    assertEquals(1, finalRuntime.spoken.size)
                    assertEquals(0, finalRuntime.events.count { it.outcome == SpeechOutputOutcome.DONE })
                    if (terminal == "STOP") {
                        assertEquals(1, finalRuntime.events.count { it.outcome == SpeechOutputOutcome.STOPPED })
                        assertEquals(0, finalRuntime.events.count { it.outcome == SpeechOutputOutcome.ENGINE_ERROR })
                    } else {
                        val failure = finalRuntime.events.single { it.outcome == SpeechOutputOutcome.ENGINE_ERROR }
                        assertEquals(if (terminal == "ERROR_SYNTHESIS") TextToSpeech.ERROR_SYNTHESIS else TextToSpeech.ERROR_SERVICE,
                            failure.errorCode)
                    }
                    if (terminal == "DISPATCH_ERROR") {
                        assertEquals(TextToSpeech.ERROR, finalRuntime.speakResult)
                        assertEquals(0, finalRuntime.events.count { it.outcome == SpeechOutputOutcome.STARTED })
                    }
                    if (terminal == "ERROR_SERVICE") {
                        assertEquals("Inline arrival END must retire this engine before an unavailable notification", 0,
                            finalRuntime.unavailableNotifications)
                    }
                    listOf(walk, output.currentCommandGeneration(), finalRuntime.spoken.size,
                        finalRuntime.stops, finalRuntime.shutdowns, finalRuntime.unavailableNotifications, finalRuntime.events.toList())
                }
                // Native callbacks still travel through the retired actuator's real Handler fence.
                onMain(f.instrumentation) { finalRuntime.done(finalId); finalRuntime.done(finalId) }
                f.instrumentation.waitForIdleSync()
                onMain(f.instrumentation) {
                    assertEquals(ended, listOf((field(f.main, "walkSessionLifecycle") as WalkSessionLifecycle).snapshot(),
                        output.currentCommandGeneration(), finalRuntime.spoken.size,
                        finalRuntime.stops, finalRuntime.shutdowns, finalRuntime.unavailableNotifications, finalRuntime.events.toList()))
                    assertEquals(NativeUiPage.HOME, field(f.main, "nativeUiPage"))
                    assertFalse(f.navigator.hasRoute())
                    assertNull(field(f.main, "arrivalEndingOwner"))
                    assertNull(field(f.main, "feedbackActuator"))
                    assertFalse(output.hasPendingCommandResponse())
                    Log.i(TAG, "PASS automaticArrival=true firstTerminal=$terminal walkEnded=true home=true " +
                        "finalRuntimeReleased=true lateDuplicateDoneIgnored=true safeStop=false " +
                        "activityOnCreate=false nativeTts=false")
                }
            }
        }
    }

    @Test(timeout = 30_000L)
    fun actualMainSettingsAndNewStatusSurviveLateArrivalDoneAndError() = withAutomaticArrival { f, finalRuntime, finalId ->
        onMain(f.instrumentation) { finalRuntime.start(finalId) }
        f.instrumentation.waitForIdleSync()
        val output = onMain(f.instrumentation) { field(f.main, "speechOutputController") as SpeechOutputController }
        val newMessage = "설정 화면에서 개인정보 동의를 확인해 주세요."
        val (ended, beforeNewResponse) = onMain(f.instrumentation) {
            assertEquals(1, finalRuntime.events.count { it.kind == SpeechOutputKind.INTERACTION && it.outcome == SpeechOutputOutcome.STARTED })
            val lifecycle = field(f.main, "walkSessionLifecycle") as WalkSessionLifecycle
            val oldWalkId = lifecycle.snapshot().epoch.walkSessionId
            // Retire the outgoing arrival synchronously before publishing SETTINGS.
            MainActivity::class.java.getDeclaredMethod("showNativeUiPage", NativeUiPage::class.java)
                .apply { isAccessible = true }.invoke(f.main, NativeUiPage.SETTINGS)
            assertEquals(WalkSessionState.ENDED, lifecycle.snapshot().state)
            assertEquals(oldWalkId, lifecycle.snapshot().epoch.walkSessionId)
            assertEquals("SETTINGS", field(f.main, "nativeUiPage").toString())
            val ended = lifecycle.snapshot()
            val beforeNewResponse = output.currentCommandGeneration()
            assertEquals(true, MainActivity::class.java.getDeclaredMethod("speakStatusExplanation", String::class.java)
                .apply { isAccessible = true }.invoke(f.main, newMessage))
            ended to beforeNewResponse
        }
        // The replacement factory posts native READY; the real owner resumes this queued response.
        f.instrumentation.waitForIdleSync()
        val (replacement, owner) = onMain(f.instrumentation) {
            assertTrue("The real READY continuation must submit the new status", f.runtime.spoken.isNotEmpty())
            val replacement = f.runtime.spoken.last()
            assertEquals(newMessage, replacement.message)
            assertNotEquals(finalId, replacement.id)
            assertTrue(output.currentCommandGeneration() > beforeNewResponse)
            val owner = f.main.captureCommandSpeechResponseOwner()
            assertTrue(owner.isCurrent(f.main))
            f.runtime.start(replacement.id)
            replacement to owner
        }
        f.instrumentation.waitForIdleSync()
        val replacementGeneration = onMain(f.instrumentation) { output.currentCommandGeneration() }
        val replacementRuntime = onMain(f.instrumentation) { f.runtime }
        val (stopsBeforeLateCallbacks, submissionsBeforeLateCallbacks) = onMain(f.instrumentation) {
            assertTrue(owner.isCurrent(f.main))
            assertTrue(f.actuator.isAppSpeechActive())
            assertTrue(output.hasPendingCommandResponse())
            assertSame(replacementRuntime, f.runtime)
            val observations = replacementRuntime.stops to replacementRuntime.spoken.size
            // Only the existing controlled native port delivers these late callbacks.
            finalRuntime.done(finalId)
            finalRuntime.error(finalId)
            observations
        }
        f.instrumentation.waitForIdleSync()
        val doneBeforeReplacement = onMain(f.instrumentation) {
            val lifecycle = field(f.main, "walkSessionLifecycle") as WalkSessionLifecycle
            assertEquals(ended, lifecycle.snapshot())
            assertEquals("SETTINGS", field(f.main, "nativeUiPage").toString())
            assertEquals(replacementGeneration, output.currentCommandGeneration())
            assertTrue(owner.isCurrent(f.main))
            assertTrue(output.hasPendingCommandResponse())
            assertTrue(f.actuator.isAppSpeechActive())
            assertSame(replacementRuntime, f.runtime)
            assertEquals(stopsBeforeLateCallbacks, replacementRuntime.stops)
            assertEquals(submissionsBeforeLateCallbacks, replacementRuntime.spoken.size)
            val done = replacementRuntime.events.count { it.kind == SpeechOutputKind.INTERACTION && it.outcome == SpeechOutputOutcome.DONE }
            replacementRuntime.done(replacement.id)
            done
        }
        f.instrumentation.waitForIdleSync()
        onMain(f.instrumentation) {
            assertFalse(output.hasPendingCommandResponse())
            assertFalse(f.actuator.isAppSpeechActive())
            assertEquals(doneBeforeReplacement + 1,
                replacementRuntime.events.count { it.kind == SpeechOutputKind.INTERACTION && it.outcome == SpeechOutputOutcome.DONE })
            assertEquals(ended, (field(f.main, "walkSessionLifecycle") as WalkSessionLifecycle).snapshot())
            assertEquals("SETTINGS", field(f.main, "nativeUiPage").toString())
            assertEquals(1, finalRuntime.spoken.count { it.message == "도착했습니다. 안내를 종료합니다" })
            Log.i(TAG, "PASS automaticArrival=true productionSettingsTransition=true oldWalkEnded=true " +
                "lateDoneAndErrorIgnored=true newStatusDone=true settingsPreserved=true activityOnCreate=false")
        }
    }

    @Test(timeout = 30_000L)
    fun actualMainLateArrivalTerminalsPreserveRevokedOrReplacementOwners() {
        for (change in listOf("authentication", "actor_session", "walk")) {
            withAutomaticArrival { f, finalRuntime, finalId ->
                onMain(f.instrumentation) { finalRuntime.start(finalId) }
                f.instrumentation.waitForIdleSync()
                onMain(f.instrumentation) {
                    assertEquals(1, finalRuntime.events.count {
                        it.kind == SpeechOutputKind.INTERACTION && it.outcome == SpeechOutputOutcome.STARTED
                    })
                    assertTrue(f.actuator.isAppSpeechActive())
                    val lifecycle = field(f.main, "walkSessionLifecycle") as WalkSessionLifecycle
                    assertEquals(WalkSessionState.PAUSED, lifecycle.snapshot().state)
                    when (change) {
                        "authentication" -> {
                            val permissions = field(f.main, "permissionSessionPolicy") as PermissionSessionPolicy
                            permissions.authenticationExpired()
                            assertEquals(AuthenticationState.REAUTHENTICATION_REQUIRED, permissions.snapshot().authentication)
                        }
                        "actor_session" -> {
                            val previous = GatewaySessionProcessCoordinator.snapshot()
                            val replacement = f.publishReplacementSession("22222222-2222-4222-8222-222222222222")
                            assertNotSame(previous.session, replacement)
                            assertNotEquals(previous.session?.actorId, replacement.actorId)
                            assertSame(replacement, GatewaySessionProcessCoordinator.snapshot().session)
                            assertTrue(GatewaySessionProcessCoordinator.snapshot().generation > previous.generation)
                        }
                        "walk" -> {
                            val previous = lifecycle.snapshot()
                            MainActivity::class.java.getDeclaredMethod("transitionWalkSession", WalkSessionEvent::class.java)
                                .apply { isAccessible = true }.invoke(f.main, WalkSessionEvent.NewWalkRequested)
                            assertNotEquals(previous.epoch.walkSessionId, lifecycle.snapshot().epoch.walkSessionId)
                            assertEquals(WalkSessionState.READY, lifecycle.snapshot().state)
                        }
                    }
                    assertEquals(NativeUiPage.GUIDANCE, field(f.main, "nativeUiPage"))
                    if (change == "actor_session") {
                        MainActivity::class.java.getDeclaredMethod("renderMainUi")
                            .apply { isAccessible = true }.invoke(f.main)
                    }
                }
                f.instrumentation.waitForIdleSync()
                val (lifecycle, session, destination) = onMain(f.instrumentation) {
                    Triple(field(f.main, "walkSessionLifecycle"), GatewaySessionProcessCoordinator.snapshot().session,
                        field(f.main, "pendingUiDestination"))
                }
                fun stateImage(): List<Any?> {
                    val process = GatewaySessionProcessCoordinator.snapshot()
                    val acquisition = MainActivityControllerTestFixture.controller(f.main, "routeAcquisitionController")
                        as RouteAcquisitionController
                    return listOf((field(f.main, "walkSessionLifecycle") as WalkSessionLifecycle).snapshot(),
                        process.generation, process.restoredFirstRunSnapshot, process.inFlightOperationId,
                        process.storageBlocked, process.deletionRecoveryOnly, process.session?.let { it.isUsableFor(it.actorId) },
                        field(f.main, "nativeUiPage"), field(f.main, "currentDestination"), acquisition.requestGeneration,
                        f.routeState(), (field(f.main, "permissionSessionPolicy") as PermissionSessionPolicy).snapshot(),
                        field(f.main, "reporterUserId"))
                }
                val before = onMain(f.instrumentation) { stateImage() }
                for (terminal in listOf("DONE", "ERROR")) {
                    onMain(f.instrumentation) {
                        if (terminal == "DONE") finalRuntime.done(finalId) else finalRuntime.error(finalId)
                    }
                    f.instrumentation.waitForIdleSync()
                    onMain(f.instrumentation) {
                        assertSame("$change/$terminal must retain the current lifecycle", lifecycle,
                            field(f.main, "walkSessionLifecycle"))
                        assertSame("$change/$terminal must retain the current process session", session,
                            GatewaySessionProcessCoordinator.snapshot().session)
                        assertSame("$change/$terminal must retain the selected destination", destination,
                            field(f.main, "pendingUiDestination"))
                        assertEquals("$change/$terminal must not end, navigate or reset the current owner", before, stateImage())
                    }
                }
                Log.i(TAG, "PASS automaticArrival=true retiredOwner=$change lateDoneAndErrorIgnored=true " +
                    "currentWalkSessionPageDestinationAndRoutePreserved=true activityOnCreate=false")
            }
        }
    }

    @Test(timeout = 30_000L)
    fun actualMainOverviewIsReservedCompletedAndNotReplayed() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertFalse("Fixture must use normal onboarding gates", BuildConfig.DEVELOPMENT_QUICK_START)
        assertFalse("Fixture must not bypass runtime gates", BuildConfig.WALKSAFE_DEBUG_GUIDANCE_START_BYPASS)
        assertTrue("Device binding gate must remain enabled", BuildConfig.WALKSAFE_POST_LOGIN_DEVICE_CHECK_REQUIRED)
        assertEquals("OS fine-location permission is an environment prerequisite",
            PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))
        assertTrue("OS location service is an environment prerequisite",
            requireNotNull(context.getSystemService(LocationManager::class.java)).isLocationEnabled)
        assertNull("Never replace an existing process account", GatewaySessionProcessCoordinator.snapshot().session)
        assertNull("Never replace existing process onboarding", GatewaySessionProcessCoordinator.snapshot().restoredFirstRunSnapshot)
        assertFalse("Never reset deletion recovery", AccountDeletionProcessCoordinator.shared.stateMachine.processingBlocked())

        var fixture: Fixture? = null
        try {
            fixture = onMain(instrumentation) { Fixture(instrumentation) }
            val f = fixture
            onMain(instrumentation) { f.prepare() }
            instrumentation.waitForIdleSync()
            val firstCheck = onMain(instrumentation) {
                assertEquals("tts=ready", f.actuator.statusText())
                f.assertActualGates()
                val now = SystemClock.elapsedRealtime()
                f.navigator.update(f.fix(now), now, false, facingObservation = null)
            }
            assertEquals("A new route waits through its direction recheck window", "guidance_facing_recheck_wait", firstCheck.reason)
            assertNull(firstCheck.instruction)
            val grace = onMain(instrumentation) { requireNotNull(f.navigator.currentDirectionGraceSnapshot()) }
            val graceOnset = requireNotNull(grace.onsetElapsedRealtimeMs)
            val graceDeadline = requireNotNull(grace.deadlineElapsedRealtimeMs)
            assertEquals("Actual Main must use the production direction grace",
                GuidanceRecoveryTiming.DIRECTION_GRACE_MS, graceDeadline - graceOnset)
            val observationDeadline = minOf(graceDeadline + 750L, graceOnset + LocationTrustConfig().maxAgeMs)
            assertTrue("The original location must remain fresh after the actual grace", graceDeadline < observationDeadline)
            val waitMs = graceDeadline - SystemClock.elapsedRealtime()
            if (waitMs > 0L) Thread.sleep(waitMs)
            onMain(instrumentation) {
                val retryAt = SystemClock.elapsedRealtime()
                assertTrue("Overview observation must stay within the actual grace and location bounds",
                    retryAt in graceDeadline..observationDeadline)
                assertEquals(0, f.runtime.spoken.size)
                assertEquals(0, f.outcomes(SpeechOutputOutcome.REQUESTED))
                val update = requireNotNull(f.navigator.retryGuidance(retryAt, facingObservation = null))
                f.cue = update
                assertNotNull(
                    "Real Navigator must offer the overview: reason=${update.reason}, instruction=${update.instruction}",
                    update.speechCueToken,
                )
                assertTrue(requireNotNull(update.instruction).contains("회전이 있습니다"))
                assertFalse(requireNotNull(update.speechCueToken).requiresFacingEvidence)
                assertNotNull("Real first cue retains its separate route summary", update.infoUtterance)
                assertTrue(f.navigator.isInstructionCurrent(update, SystemClock.elapsedRealtime(), null))
                f.routeBefore = f.routeState()
                assertEquals("", f.resultText.text.toString())

                f.dispatch(update)
                assertEquals("Persistent direction warning is the first output", 1, f.runtime.spoken.size)
                assertEquals(DIRECTION_PROBLEM_NOTICE, f.runtime.spoken.single().message)
                assertEquals("Warning must not reserve or acknowledge the offered NAV", 0, f.outcomes(SpeechOutputOutcome.REQUESTED))
                assertNull(f.pendingCue())
                assertNull(f.completedCue())
                f.dispatch(update)
                assertEquals("An in-flight warning cannot be submitted again", 1, f.runtime.spoken.size)
            }
            val warning = onMain(instrumentation) { f.runtime.spoken.single() }
            val action = completeDirectionWarningAndAwaitOverview(f, warning, graceDeadline, observationDeadline)
            onMain(instrumentation) {
                assertEquals("The actual Handler submits one overview after warning DONE", 2, f.runtime.spoken.size)
                assertEquals(f.cue.instruction, action.message)
                assertEquals("The Handler must reserve the offered cue without replacing its identity", f.cue.speechCueToken, f.pendingCue())
                assertFalse("Actual Main owns the reservation", f.navigator.reserveInstruction(f.cue))
                assertEquals("Completion UI must retain the warning until NAV DONE", "마지막 안내: ${warning.message}", f.resultText.text.toString())
                f.dispatch(f.cue)
                assertEquals("Reserved NAV cannot be submitted again", 2, f.runtime.spoken.size)
                f.runtime.start(action.id)
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                assertEquals(1, f.outcomes(SpeechOutputOutcome.STARTED))
                assertEquals(0, f.outcomes(SpeechOutputOutcome.DONE))
                assertTrue(f.actuator.isAppSpeechActive())
                assertEquals("마지막 안내: ${warning.message}", f.resultText.text.toString())
                f.runtime.done(action.id)
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                assertEquals("Action completion enables exactly one INFO", 2, f.outcomes(SpeechOutputOutcome.REQUESTED))
                assertEquals(1, f.outcomes(SpeechOutputOutcome.STARTED))
                assertEquals(1, f.outcomes(SpeechOutputOutcome.DONE))
                assertEquals(0, f.runtime.stops)
                assertTrue("The captured INFO is now pending", f.actuator.isAppSpeechActive())
                assertEquals(3, f.runtime.spoken.size)
                assertEquals(requireNotNull(f.cue.infoUtterance).message, f.runtime.spoken.last().message)
                assertEquals(f.routeBefore, f.routeState())
                assertEquals("마지막 안내: ${f.cue.instruction}", f.resultText.text.toString())
                assertEquals(View.VISIBLE, f.resultText.visibility)
                assertEquals(f.resultText.text.toString(), f.resultText.contentDescription.toString())
                assertFalse("Actual completion acknowledged the cue", f.navigator.reserveInstruction(f.cue))
                assertNotNull("Actual completion armed Main's Android retry", field(f.main, "navigationSpeechRetryRunnable"))
                f.invoke("cancelNavigationSpeechRetry")
                assertNull(field(f.main, "navigationSpeechRetryRunnable"))
                f.dispatch(f.cue)
                assertEquals("Completed action cannot replay while INFO is pending", 3, f.runtime.spoken.size)
                f.runtime.start(f.runtime.spoken.last().id)
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                assertEquals(2, f.outcomes(SpeechOutputOutcome.STARTED))
                assertEquals(1, f.outcomes(SpeechOutputOutcome.DONE))
                f.runtime.done(f.runtime.spoken.last().id)
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                assertEquals(2, f.outcomes(SpeechOutputOutcome.DONE))
                assertFalse(f.actuator.isAppSpeechActive())
                f.dispatch(f.cue)
                assertEquals("Completed action and INFO cannot replay", 3, f.runtime.spoken.size)
                // The actuator receives a duplicate platform terminal; Main must not replay or
                // change the route when the already-consumed callback arrives again.
                f.runtime.done(action.id)
                f.runtime.done(warning.id)
            }
            instrumentation.waitForIdleSync()
            onMain(instrumentation) {
                assertEquals(2, f.outcomes(SpeechOutputOutcome.DONE))
                assertEquals(3, f.runtime.spoken.size)
                assertEquals(1, f.runtime.spoken.count { it.message == f.cue.instruction })
                assertEquals(1, f.runtime.spoken.count { it.message == requireNotNull(f.cue.infoUtterance).message })
                assertEquals(1, f.runtime.spoken.count { it.message == DIRECTION_PROBLEM_NOTICE })
                assertEquals(1, f.events.count { it.kind == SpeechOutputKind.INTERACTION && it.outcome == SpeechOutputOutcome.DONE })
                assertEquals(f.routeBefore, f.routeState())
                assertEquals("마지막 안내: ${f.cue.instruction}", f.resultText.text.toString())
                Log.i(TAG, "PASS actualMainEntries=${f.entries} warningRequested=1 warningStarted=1 warningDone=1 navRequested=2 navStarted=2 navDone=2 " +
                    "actionSubmitted=1 infoSubmitted=1 repeatedSubmission=0 routeIdentityProgressDecisionUnchanged=true " +
                    "mainOnCreateInvokedByTest=false nativeTts=false")
            }
        } finally {
            fixture?.let { f -> onMain(instrumentation) { f.close() } }
        }
    }

    private fun awaitMainSpeech(
        f: Fixture,
        deadlineMs: Long,
        matches: (RecordingSpeechRuntime.Spoken) -> Boolean,
    ): RecordingSpeechRuntime.Spoken {
        while (SystemClock.elapsedRealtime() <= deadlineMs) {
            onMain(f.instrumentation) { f.runtime.spoken.lastOrNull(matches) }?.let { return it }
            Thread.sleep(10L)
            f.instrumentation.waitForIdleSync()
        }
        val diagnostic = onMain(f.instrumentation) {
            f.retryDiagnostic(0, 0, field(f.main, "navigationSpeechRetryRunnable") != null)
        }
        throw AssertionError("Actual Main Handler did not submit current speech within original GPS freshness: $diagnostic")
    }

    /** Real Main/Handler ownership; START/DONE/late ERROR are controlled Android-runtime inputs. */
    private fun completeDirectionWarningAndAwaitOverview(
        f: Fixture,
        warning: RecordingSpeechRuntime.Spoken,
        graceDeadlineMs: Long,
        observationDeadlineMs: Long,
    ): RecordingSpeechRuntime.Spoken {
        val (interactionBefore, originalGpsAt) = onMain(f.instrumentation) {
            assertEquals(DIRECTION_PROBLEM_NOTICE, warning.message)
            assertTrue(warning.id.startsWith("interaction-"))
            val request = f.events.last { it.kind == SpeechOutputKind.INTERACTION && it.outcome == SpeechOutputOutcome.REQUESTED }
            assertTrue("Persistent warning starts only after the original grace", request.atElapsedRealtimeMs >= graceDeadlineMs)
            assertTrue("Warning must be observed before the original GPS expires", request.atElapsedRealtimeMs <= observationDeadlineMs)
            assertEquals(0, f.outcomes(SpeechOutputOutcome.REQUESTED))
            assertNull("Warning cannot reserve the route action", f.pendingCue())
            assertNull("Warning cannot acknowledge the route action", f.completedCue())
            assertFalse(f.navigator.hasPendingInformation())
            assertEquals("마지막 안내: ${warning.message}", f.resultText.text.toString())
            assertNotNull("The current warning must leave the actual navigation retry armed", field(f.main, "navigationSpeechRetryRunnable"))
            val counts = f.events.count { it.kind == SpeechOutputKind.INTERACTION && it.outcome == SpeechOutputOutcome.STARTED } to
                f.events.count { it.kind == SpeechOutputKind.INTERACTION && it.outcome == SpeechOutputOutcome.DONE }
            val gpsAt = requireNotNull(f.rawRouteMeasurementAtMs())
            f.runtime.start(warning.id)
            counts to gpsAt
        }
        f.instrumentation.waitForIdleSync()
        onMain(f.instrumentation) {
            assertEquals(interactionBefore.first + 1, f.events.count { it.kind == SpeechOutputKind.INTERACTION && it.outcome == SpeechOutputOutcome.STARTED })
            assertEquals(interactionBefore.second, f.events.count { it.kind == SpeechOutputKind.INTERACTION && it.outcome == SpeechOutputOutcome.DONE })
            assertEquals(0, f.outcomes(SpeechOutputOutcome.REQUESTED))
            assertNull(f.pendingCue())
            assertNull(f.completedCue())
            assertEquals(0, f.runtime.stops)
            f.runtime.done(warning.id)
        }
        f.instrumentation.waitForIdleSync()
        val action = awaitMainSpeech(f, observationDeadlineMs) { it.id.startsWith("nav-") }
        val reserved = onMain(f.instrumentation) {
            assertTrue("The resumed route action remains an unknown-facing overview", action.message.contains("회전이 있습니다"))
            assertEquals(1, f.outcomes(SpeechOutputOutcome.REQUESTED))
            assertEquals(0, f.outcomes(SpeechOutputOutcome.STARTED))
            assertEquals(0, f.outcomes(SpeechOutputOutcome.DONE))
            assertEquals(interactionBefore.second + 1, f.events.count { it.kind == SpeechOutputKind.INTERACTION && it.outcome == SpeechOutputOutcome.DONE })
            assertEquals(1, f.runtime.spoken.count { it.message == DIRECTION_PROBLEM_NOTICE })
            val request = f.events.single { it.kind == SpeechOutputKind.NAVIGATION && it.outcome == SpeechOutputOutcome.REQUESTED }
            assertTrue("The real Handler must retain the existing observation bound", request.atElapsedRealtimeMs <= observationDeadlineMs)
            assertTrue("Speech retry must not renew the source GPS time", request.atElapsedRealtimeMs - originalGpsAt <= LocationTrustConfig().maxAgeMs)
            assertEquals(originalGpsAt, f.rawRouteMeasurementAtMs())
            val token = requireNotNull(f.pendingCue() as? RouteSpeechCueToken)
            assertFalse(token.requiresFacingEvidence)
            assertNull(f.completedCue())
            assertEquals("A queued route action cannot update completed-route UI", "마지막 안내: ${warning.message}", f.resultText.text.toString())
            // Retired status callbacks cannot acknowledge, cancel or replace the reserved NAV.
            f.runtime.done(warning.id)
            f.runtime.error(warning.id)
            token
        }
        f.instrumentation.waitForIdleSync()
        onMain(f.instrumentation) {
            assertEquals(reserved, f.pendingCue())
            assertNull(f.completedCue())
            assertEquals(1, f.outcomes(SpeechOutputOutcome.REQUESTED))
            assertEquals(0, f.outcomes(SpeechOutputOutcome.STARTED))
            assertEquals(0, f.outcomes(SpeechOutputOutcome.DONE))
            assertEquals(interactionBefore.second + 1, f.events.count { it.kind == SpeechOutputKind.INTERACTION && it.outcome == SpeechOutputOutcome.DONE })
            assertFalse(f.events.any { it.outcome == SpeechOutputOutcome.ENGINE_ERROR })
            assertEquals(0, f.runtime.stops)
            assertEquals(originalGpsAt, f.rawRouteMeasurementAtMs())
        }
        return action
    }

    /** Prepared Main and route ownership are real; location samples and native TTS callbacks are controlled. */
    private fun withAutomaticArrival(rejectFinalDispatch: Boolean = false,
        test: (Fixture, RecordingSpeechRuntime, String) -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertNull("Never replace an existing process account", GatewaySessionProcessCoordinator.snapshot().session)
        assertNull("Never replace existing process onboarding", GatewaySessionProcessCoordinator.snapshot().restoredFirstRunSnapshot)
        var fixture: Fixture? = null
        try {
            fixture = onMain(instrumentation) { Fixture(instrumentation,
                replacementSpeechResult = if (rejectFinalDispatch) TextToSpeech.ERROR else TextToSpeech.SUCCESS) }
            val f = fixture
            onMain(instrumentation) {
                f.prepareAutomaticArrivalScreen()
                setField(f.main, "nativeUiPage", NativeUiPage.GUIDANCE)
                val destination = DestinationSearchResult(
                    "arrival", "목적지", point(400.0), null, null, null, null)
                setField(f.main, "pendingUiDestination", destination)
                setField(f.main, "currentDestination", destination.point)
                val acquisition = MainActivityControllerTestFixture.controller(f.main, "routeAcquisitionController")
                    as RouteAcquisitionController
                acquisition.installRequestedRoute(f.route, destination.point,
                    f.fix(0.0, SystemClock.elapsedRealtime()), null, "user_destination")
            }
            instrumentation.waitForIdleSync()

            val arrivalUpdate = onMain(instrumentation) {
                var at = SystemClock.elapsedRealtime()
                var candidate: RouteNavigatorUpdate? = null
                var last: RouteNavigatorUpdate? = null
                for (eastM in 10..400 step 10) {
                    at += 3_000L
                    val update = f.navigator.update(f.fix(eastM.toDouble(), at), at, false, facingObservation = null)
                    last = update
                    if (update.arrivalCandidate) {
                        candidate = update
                        break
                    }
                }
                repeat(3) {
                    if (candidate == null) {
                        at += 3_000L
                        val update = f.navigator.update(f.fix(400.0, at), at, false, facingObservation = null)
                        last = update
                        if (update.arrivalCandidate) candidate = update
                    }
                }
                val sampleCount = RouteNavigator::class.java.getDeclaredField("arrivalSampleCount").apply { isAccessible = true }.getInt(f.navigator)
                val recheckArmed = RouteNavigator::class.java.getDeclaredField("arrivalRecheckArmed").apply { isAccessible = true }.getBoolean(f.navigator)
                assertEquals("Arrival still requires Navigator endpoint evidence and dwell",
                    "arrival_confirmation_required", candidate?.reason ?: "no candidate; last=${last?.reason}; match=${f.navigator.currentRouteMatch()}; arrivalSamples=$sampleCount recheckArmed=$recheckArmed")
                assertTrue(f.navigator.hasRoute())
                requireNotNull(candidate)
            }
            val (retiredActor, retiredRuntime) = onMain(instrumentation) { f.currentActuator to f.runtime }
            val endingWalkId = onMain(instrumentation) {
                MainActivity::class.java.getDeclaredMethod("applyRouteDeviationSafetyUpdate", RouteNavigatorUpdate::class.java)
                    .apply { isAccessible = true }.invoke(f.main, arrivalUpdate)
                assertFalse("Automatic arrival must retire its Navigator", f.navigator.hasRoute())
                assertNull(f.navigator.currentRouteId())
                assertNull(f.navigator.pendingDecisionToken())
                assertEquals(WalkSessionState.PAUSED,
                    (field(f.main, "walkSessionLifecycle") as WalkSessionLifecycle).snapshot().state)
                (field(f.main, "walkSessionLifecycle") as WalkSessionLifecycle).snapshot().epoch.walkSessionId
            }
            // Cleanup closed the initial actor. Let the replacement's controlled engine READY
            // travel through the production factory callbacks and real Handler continuation.
            instrumentation.waitForIdleSync()
            val (finalRuntime, finalId) = onMain(instrumentation) {
                assertEquals(endingWalkId,
                    (field(f.main, "walkSessionLifecycle") as WalkSessionLifecycle).snapshot().epoch.walkSessionId)
                assertTrue("The old native runtime must be shut down", retiredRuntime.shutdowns > 0)
                val finalRuntime = if (rejectFinalDispatch) requireNotNull(f.lastReplacementRuntime) else {
                    assertNotSame("Arrival cleanup must replace its retired actor through the real output owner",
                        retiredActor, f.currentActuator)
                    assertEquals("tts=ready", f.currentActuator.statusText())
                    assertEquals(0, f.runtime.shutdowns)
                    f.runtime
                }
                assertEquals("The replacement actor must submit one final response", 1, finalRuntime.spoken.size)
                assertEquals("도착했습니다. 안내를 종료합니다", finalRuntime.spoken.single().message)
                finalRuntime to finalRuntime.spoken.single().id
            }
            test(f, finalRuntime, finalId)
        } finally {
            fixture?.let { f -> onMain(instrumentation) { f.close() } }
        }
    }

    private class Fixture(val instrumentation: Instrumentation,
        private val replacementSpeechResult: Int = TextToSpeech.SUCCESS) {
        val main: MainActivity
        private val initialRuntime = RecordingSpeechRuntime()
        private val initialActuator: AndroidFeedbackActuator
        private val actorRuntimes = java.util.IdentityHashMap<AndroidFeedbackActuator, RecordingSpeechRuntime>()
        var lastReplacementRuntime: RecordingSpeechRuntime? = null
            private set
        val currentActuator: AndroidFeedbackActuator
            get() = requireNotNull(field(main, "feedbackActuator") as? AndroidFeedbackActuator)
        val actuator: AndroidFeedbackActuator get() = currentActuator
        val runtime: RecordingSpeechRuntime get() = checkNotNull(actorRuntimes[currentActuator])
        val navigator: RouteNavigator
        val route = WalkingRoute("PROBE", WalkingRouteSummary(400, 400),
            listOf(point(0.0), point(400.0)),
            listOf(WalkingRouteGuidePoint(0, point(0.0), "출발", 0, 400, turnType = 200, pointType = "SP"),
                WalkingRouteGuidePoint(1, point(200.0), "안내", 200, 200, turnType = 13)))
        val resultText: TextView
        val entries = mutableListOf<String>()
        val events = mutableListOf<SpeechOutputEvent>()
        lateinit var cue: RouteNavigatorUpdate
        lateinit var routeBefore: List<Any?>
        private val lifecycle = WalkSessionLifecycle(elapsedRealtimeClock = SystemClock::elapsedRealtime)
        private val renewalHandler = Handler(Looper.getMainLooper())
        private val walkTransport = RejectingWalkTransport()
        private var ownedGatewayGeneration: Long? = null
        private var ownedPositioningBinding: PositioningCollectionBinding? = null

        init {
            check(Looper.myLooper() == Looper.getMainLooper())
            val context = instrumentation.targetContext
            val component = ComponentName(context, MainActivity::class.java)
            main = instrumentation.newActivity(MainActivity::class.java, context, Binder(),
                context.applicationContext as Application, Intent().setComponent(component),
                context.packageManager.getActivityInfo(component, 0), "Main NAV wrapper probe", null, null, null) as MainActivity
            setField(main, "gatewayWalkRenewalHandler", renewalHandler)
            setField(main, "gatewayWalkSessionClient", GatewayWalkSessionClient(walkTransport))
            navigator = field(main, "routeNavigator") as RouteNavigator
            resultText = TextView(main).apply { visibility = View.INVISIBLE }
            initialActuator = AndroidFeedbackActuator(runtime = initialRuntime,
                speechAllowed = { invoke("authorizeActuatorSpeechForHomeWakeInput") as Boolean })
            actorRuntimes[initialActuator] = initialRuntime
            initialActuator.guidanceDiagnosticListener = { event ->
                initialRuntime.events += event
                events += event
            }
        }

        fun prepare() {
            val onboarding = completedOnboarding()
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
            ownedGatewayGeneration = operation.generation
            val session = GatewayFieldSession.verified("https://gateway.example.test", ACTOR,
                "walksafe_field_session=synthetic_nav_probe", System.currentTimeMillis() + 60_000L)
            check(GatewaySessionProcessCoordinator.publishVerified(operation, session, onboarding))
            val generation = GatewaySessionProcessCoordinator.snapshot().generation
            ownedGatewayGeneration = generation
            val device = completedDeviceCheck(generation)
            lifecycle.handle(WalkSessionEvent.EnteredForeground)
            val epoch = lifecycle.snapshot().epoch
            val now = SystemClock.elapsedRealtime()
            val plan = WalkSessionReadinessPlan(WalkSessionAction.START_WALK, WalkSessionMode.FULL)
            val readiness = WalkSessionReadinessCollector(epoch, plan).apply {
                plan.requiredRequirements.forEach { record(WalkSessionReadinessObservation(
                    epoch, it, WalkSessionReadinessStatus.READY, "synthetic prepared input")) }
            }.build(now)
            lifecycle.handle(WalkSessionEvent.InitialCheckCompleted(readiness))
            lifecycle.handle(WalkSessionEvent.StartRequested(requireNotNull(lifecycle.snapshot().confirmationToken)))
            check(lifecycle.snapshot().state == WalkSessionState.ACTIVE)
            val authority = field(main, "gatewayWalkAuthorityController") as GatewayWalkAuthorityController
            val start = requireNotNull(authority.beginStart(epoch))
            check(authority.completeStart(start, GatewayWalkStartResult.Granted(GatewayWalkLease(
                "started", ACTOR, session.deviceId, epoch.walkSessionId, "synthetic-lease", 1L,
                System.currentTimeMillis(), System.currentTimeMillis() + 60_000L,
                System.currentTimeMillis(), now + 60_000L))) == GatewayWalkAuthorityCompletion.LEASE_ACTIVE)
            val profile = WalkSafeEnvironmentProfiles.testCandidate.officialEnvironment
            val guard = OfficialEnvironmentRuntimeGuard(epoch, profile)
            val gps = OfficialEnvironmentPolicy.assessGpsQuality(epoch, now,
                GpsQualityObservation(epoch, now, true, 2.0), profile)
            val camera = MeasuredEnvironmentEvidence(OfficialEnvironmentFactor.CAMERA_QUALITY, epoch,
                now, EnvironmentEvidenceStatus.PASS, profile.cameraQualityProfileId,
                profile.maximumMeasuredEvidenceAgeMs, "synthetic prepared camera evidence")
            val confirmation = OfficialEnvironmentUserConfirmation(epoch,
                EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS,
                EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS, EnvironmentEvidenceStatus.PASS,
                EnvironmentEvidenceStatus.PASS)
            val assessment = OfficialEnvironmentPolicy.assess(epoch, now, gps, camera, confirmation, profile)
            check(assessment.canStartWalk)
            check(guard.onAssessment(assessment, now).navigationOutputsAllowed)

            setField(main, "firstRunOnboardingSnapshot", onboarding)
            setField(main, "postLoginDeviceCheckSnapshot", device)
            setField(main, "walkSessionLifecycle", lifecycle)
            setField(main, "isActivityForeground", true)
            // Production activation begins this owner before environment and navigation work.
            val safety = main.beginWalkRuntimeSafetyEpoch(epoch)
            assertEquals("Safety admission must bind the active lifecycle epoch", epoch, safety.epoch)
            assertNotEquals("Prepared runtime must not own a latched safety stop",
                WalkRuntimeSafetyDisposition.SAFE_STOP_LATCHED, safety.disposition)
            setField(main, "officialEnvironmentOutputsAllowed", assessment.canStartWalk)
            setField(main, "officialEnvironmentRuntimeGuard", guard)
            setField(main, "isRouteActive", true)
            setField(main, "feedbackActuator", initialActuator)
            setField(main, "walkLastResultText", resultText)
            assertEquals("SDK fixture requires actual fine-location permission", PackageManager.PERMISSION_GRANTED,
                main.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))
            assertTrue("SDK fixture requires the real location service",
                requireNotNull(main.getSystemService(LocationManager::class.java)).isLocationEnabled)
            val owner = requireNotNull(invoke("currentLocationCollectionOwnerOrNull") as? LocationCollectionOwner)
            assertEquals(ACTOR, owner.actorId)
            assertSame(session, owner.gatewaySession)
            assertEquals(generation, owner.gatewayGeneration)
            val positioning = MainActivityControllerTestFixture.controller(main, "positioningSessionController") as PositioningSessionController
            assertNull(positioning.binding)
            assertNull(positioning.collectionOwner)
            assertNull(positioning.callback)
            val binding = PositioningCollectionBinding(positioning.coordinator,
                positioning.coordinator.currentInputGeneration(), owner, positioning.callbackGeneration, now)
            ownedPositioningBinding = binding
            setField(main, "locationCollectionOwner", owner)
            setField(main, "positioningCollectionBinding", binding)
            setField(main, "locationCallback", object : LocationCallback() {}) // Controlled transport, never registered with Fused.
            assertSame(binding, positioning.binding)
            assertTrue("Actual collection must own the speech retry", positioning.isPositioningCollectionCurrent(binding))
            assertTrue(lifecycle.isRuntimeEpochCurrent(epoch))
            // A real departure guide enables the route summary through Navigator.setRoute;
            // the next maneuver at 200 m still provides the safe unknown-facing overview.
            navigator.setRoute(route)
            runtime.ready()
        }

        fun prepareAutomaticArrivalScreen() {
            prepare()
            useControlledReplacementFactory()
            // onCreate restores the account-bound education owner before screen composition.
            // Use the existing SDK fixture's policy transitions, without persisted or physical training evidence.
            setField(main, "reporterUserId", ACTOR)
            setField(main, "priorityUserOnboardingActorId", ACTOR)
            setField(main, "priorityUserOnboardingPolicy", completedPriorityEducation())
            (field(main, "permissionSessionPolicy") as PermissionSessionPolicy).authenticated(ACTOR)
            setField(main, "fieldSessionLog", NoopFieldSessionLog())
            assertEquals("Prepared login must expose the current Main actor", ACTOR, invoke("currentReporterUserId"))
            val session = requireNotNull(GatewaySessionProcessCoordinator.snapshot().session)
            assertEquals("Published session must pass Main's authenticated session gate", true,
                MainActivity::class.java.getDeclaredMethod("isGatewaySessionReadyForCurrentActor", GatewayFieldSession::class.java)
                    .apply { isAccessible = true }.invoke(main, session))
            assertActualGates()
            assertEquals("Prepared account, onboarding and education must pass Main's actual HOME gate",
                true, invoke("shouldShowNativeHome"))
            MainActivity::class.java.getDeclaredMethod("buildContentView").apply { isAccessible = true }.invoke(main)
        }

        fun assertActualGates() {
            for (name in listOf("firstRunOnboardingComplete", "postLoginDeviceCheckPassesFeatureGate",
                "isWalkSessionRuntimeActive", "navigationEnvironmentOutputsAllowed",
                "authorizeActuatorSpeechForHomeWakeInput")) assertEquals("Actual Main gate $name", true, invoke(name))
            assertTrue((field(main, "feedbackPolicy") as WalkSafeFeedbackPolicy).canSpeakNavigation(SystemClock.elapsedRealtime()))
            val binding = requireNotNull(ownedPositioningBinding)
            val positioning = MainActivityControllerTestFixture.controller(main, "positioningSessionController") as PositioningSessionController
            assertSame(binding, positioning.binding)
            assertTrue("Actual positioning owner must remain current", positioning.isPositioningCollectionCurrent(binding))
        }

        fun retryDiagnostic(identityChanges: Int, nullObservations: Int, lastRetryPresent: Boolean): String {
            fun read(receiver: Any?, name: String): Any? = receiver?.javaClass?.getDeclaredField(name)
                ?.apply { isAccessible = true }?.get(receiver)
            val now = SystemClock.elapsedRealtime()
            val guidance = MainActivityControllerTestFixture.controller(main, "routeGuidanceController") as RouteGuidanceController
            val scope = read(guidance, "scope") as RouteGuidanceController.ScopeReader
            val speech = read(guidance, "speech") as RouteGuidanceController.SpeechPort
            val positioning = MainActivityControllerTestFixture.controller(main, "positioningSessionController") as PositioningSessionController
            val output = read(main, "speechOutputController") as SpeechOutputController
            val binding = ownedPositioningBinding
            val walk = lifecycle.snapshot()
            val authority = field(main, "gatewayWalkAuthorityController") as GatewayWalkAuthorityController
            val held = read(authority, "held")
            val lease = read(held, "lease") as? GatewayWalkLease
            val start = read(guidance, "installedRouteStartSpeechCoordinator") as InstalledRouteStartSpeechCoordinator
            val context = read(navigator, "latestGuidanceContext")
            val raw = read(context, "rawLocation") as? TrustedLocation
            val filtered = read(context, "guidanceLocation") as? TrustedLocation
            val instruction = read(context, "instruction")
            val grace = navigator.currentDirectionGraceSnapshot()
            val navState = listOf("latestUpdateNowMs", "lastDirectionCheckedAtMs", "initialRouteJoinPending",
                "latestGuidanceSourceAllowed", "positioningEvidenceInterrupted", "nextGuideIndex",
                "speechRetryNotBeforeMs").joinToString { "$it=${read(navigator, it)}" }
            val outcomes = events.groupingBy { "${it.kind}/${it.outcome}" }.eachCount()
            return "retryIdentityChanges=$identityChanges retryNullObservations=$nullObservations lastRetryPresent=$lastRetryPresent " +
                "pendingRetry=${field(main, "navigationSpeechRetryRunnable") != null} " +
                "bindingSame=${binding != null && positioning.binding === binding} " +
                "positioningCurrent=${binding != null && positioning.isPositioningCollectionCurrent(binding)} " +
                "routeActive=${field(main, "isRouteActive")} requestPending=${scope.requestPending} " +
                "requestGeneration=${scope.requestGeneration} feedbackGeneration=${scope.feedbackGeneration} " +
                "feedbackCurrent=${scope.feedbackCurrent(scope.feedbackGeneration)} navigationAllowed=${scope.navigationAllowed} " +
                "walkState=${walk.state} walkForeground=${walk.isForeground} epochCurrent=${lifecycle.isRuntimeEpochCurrent(walk.epoch)} " +
                "leaseEpochSame=${read(held, "epoch") == walk.epoch} leaseRemainingMs=${lease?.localDeadlineElapsedMs?.minus(now)} " +
                "positionPaused=${guidance.positionPaused} recheckActive=${guidance.recheckNoticeActive} " +
                "speechBusy=${speech.busy()} canSpeakNavigation=${speech.canSpeakNavigation(now)} " +
                "sameActuator=${output.currentActuatorOrNull() === actuator} actuator=${actuator.statusText()} outcomes=$outcomes " +
                "installedStartPending=${start.pending != null} trustedAgeMs=${positioning.trustedLocation?.elapsedRealtimeMs?.let { now - it }} " +
                "rawAgeMs=${positioning.rawRouteLocation?.elapsedRealtimeMs?.let { now - it }} contextPresent=${context != null} " +
                "contextRawAgeMs=${raw?.elapsedRealtimeMs?.let { now - it }} contextFilteredAgeMs=${filtered?.elapsedRealtimeMs?.let { now - it }} " +
                "guideIndex=${read(instruction, "guideIndex")} guideDistanceM=${read(instruction, "distanceM")} " +
                "pendingInstruction=${navigator.hasPendingInstruction()} pendingInformation=${navigator.hasPendingInformation()} " +
                "graceMode=${grace?.mode} graceOnset=${grace?.onsetElapsedRealtimeMs} graceDeadline=${grace?.deadlineElapsedRealtimeMs} " +
                "nowMs=$now navState=[$navState]"
        }

        fun invoke(name: String): Any? {
            entries += name
            return MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(main)
        }

        fun dispatch(update: RouteNavigatorUpdate) {
            entries += "tryDispatchRouteInstruction"
            MainActivity::class.java.getDeclaredMethod("tryDispatchRouteInstruction",
                RouteNavigatorUpdate::class.java, WalkRuntimeEpoch::class.java).apply { isAccessible = true }
                .invoke(main, update, lifecycle.snapshot().epoch)
        }

        fun fix(now: Long) = fix(50.0, now)
        fun fix(eastM: Double, now: Long) = point(eastM).let { TrustedLocation(it.latitude, it.longitude, 2f, now) }
        fun routeState(): List<Any?> = listOf(navigator.currentRouteId(), navigator.currentTactileRouteProgress(),
            navigator.currentRouteMatch()?.geometricProgressM, navigator.pendingDecisionToken())
        fun outcomes(outcome: SpeechOutputOutcome) = events.count { it.kind == SpeechOutputKind.NAVIGATION && it.outcome == outcome }
        fun pendingCue(): Any? = RouteNavigator::class.java.getDeclaredField("pendingSpeechCue").apply { isAccessible = true }.get(navigator)
        fun completedCue(): Any? = RouteNavigator::class.java.getDeclaredField("completedSpeechCue").apply { isAccessible = true }.get(navigator)
        fun rawRouteMeasurementAtMs(): Long? {
            val context = RouteNavigator::class.java.getDeclaredField("latestGuidanceContext").apply { isAccessible = true }.get(navigator)
                ?: return null
            val location = context.javaClass.getDeclaredField("rawLocation").apply { isAccessible = true }.get(context) as? TrustedLocation
            return location?.elapsedRealtimeMs
        }

        /** Only the Android native leaf is controlled; the production output owner creates and binds replacements. */
        fun useControlledReplacementFactory() {
            val output = field(main, "speechOutputController") as SpeechOutputController
            SpeechOutputController::class.java.getDeclaredField("actuatorFactory").apply {
                isAccessible = true
                set(output, SpeechOutputActuatorFactory { unavailable, ready ->
                    val native = RecordingSpeechRuntime(replacementSpeechResult)
                    lastReplacementRuntime = native
                    AndroidFeedbackActuator(runtime = native,
                        onOfflineKoreanSpeechUnavailable = { native.unavailableNotifications++; unavailable() },
                        onOfflineKoreanSpeechReady = ready,
                        speechAllowed = { invoke("authorizeActuatorSpeechForHomeWakeInput") as Boolean },
                    ).also { created ->
                        actorRuntimes[created] = native
                        created.guidanceDiagnosticListener = { event ->
                            native.events += event
                            events += event
                        }
                        // Ensure the real factory owner has published createdActuator before READY.
                        native.postReady()
                    }
                })
            }
        }

        fun beginGeneralOperation(): GatewaySessionOperation {
            val before = GatewaySessionProcessCoordinator.snapshot()
            check(before.generation == ownedGatewayGeneration)
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginGeneralSessionOperationIfCurrent(
                before.generation, requireNotNull(before.session)))
            ownedGatewayGeneration = operation.generation
            return operation
        }

        fun republishSameSession(operation: GatewaySessionOperation, source: GatewaySessionProcessSnapshot) {
            check(operation.generation == ownedGatewayGeneration)
            check(GatewaySessionProcessCoordinator.publishVerified(operation, requireNotNull(source.session),
                requireNotNull(source.restoredFirstRunSnapshot)))
            ownedGatewayGeneration = GatewaySessionProcessCoordinator.snapshot().generation
        }

        fun publishReplacementSession(actorId: String): GatewayFieldSession {
            check(GatewaySessionProcessCoordinator.snapshot().generation == ownedGatewayGeneration)
            val operation = requireNotNull(GatewaySessionProcessCoordinator.beginOperation())
            ownedGatewayGeneration = operation.generation
            val replacement = GatewayFieldSession.verified("https://gateway.example.test", actorId,
                "walksafe_field_session=synthetic_replacement_probe", System.currentTimeMillis() + 60_000L)
            check(GatewaySessionProcessCoordinator.publishVerified(operation, replacement, completedOnboarding(actorId)))
            ownedGatewayGeneration = GatewaySessionProcessCoordinator.snapshot().generation
            return replacement
        }

        fun close() {
            renewalHandler.removeCallbacksAndMessages(null)
            setField(main, "isRouteActive", false)
            setField(main, "isActivityForeground", false)
            invoke("cancelNavigationSpeechRetry")
            actorRuntimes.keys.toList().forEach { it.close() }
            ownedPositioningBinding?.let {
                setField(main, "locationCallback", null)
                setField(main, "positioningCollectionBinding", null)
                setField(main, "locationCollectionOwner", null)
            }
            ownedPositioningBinding = null
            ownedGatewayGeneration?.let { ownedGeneration ->
                val cleared = checkNotNull(GatewaySessionProcessCoordinator.clear(ownedGeneration))
                check(cleared.session == null && cleared.inFlightOperationId == null)
            }
            ownedGatewayGeneration = null
        }
    }

    /** This local callback probe never sends HTTP; real walk admission and teardown stay in Main. */
    private class RejectingWalkTransport : GatewaySessionTransport {
        val attempts = java.util.concurrent.CopyOnWriteArrayList<String>()
        private fun reject(method: String): Nothing {
            attempts += method
            kotlin.error("Unexpected $method in the local arrival callback probe")
        }
        override fun postJson(url: String, body: String): GatewayHttpResponse = reject("POST")
        override fun postJson(url: String, headers: Map<String, String>, body: String): GatewayHttpResponse = reject("authenticated POST")
        override fun get(url: String, headers: Map<String, String>): GatewayHttpResponse = reject("GET")
        override fun delete(url: String, headers: Map<String, String>): GatewayHttpResponse = reject("DELETE")
        override fun deleteJson(url: String, body: String): GatewayHttpResponse = reject("DELETE JSON")
    }

    private class RecordingSpeechRuntime(val speakResult: Int = TextToSpeech.SUCCESS) : FeedbackSpeechRuntime {
        data class Spoken(val message: String, val id: String)
        private val handler = Handler(Looper.getMainLooper())
        override val scheduler = object : FeedbackSpeechScheduler {
            override fun post(task: Runnable) = handler.post(task)
            override fun postDelayed(task: Runnable, delayMs: Long) = handler.postDelayed(task, delayMs)
            override fun removeCallbacks(task: Runnable) = handler.removeCallbacks(task)
            override fun removeCallbacksAndMessages(token: Any?) = handler.removeCallbacksAndMessages(token)
        }
        val spoken = mutableListOf<Spoken>()
        val events = mutableListOf<SpeechOutputEvent>()
        var stops = 0
        var shutdowns = 0
        var unavailableNotifications = 0
        private var initialized: ((Int) -> Unit)? = null
        private var listener: FeedbackSpeechRuntime.Listener? = null
        override val engineInitialized get() = initialized != null
        override fun elapsedRealtime() = SystemClock.elapsedRealtime()
        override fun initialize(onInitialized: (Int) -> Unit) { initialized = onInitialized }
        override fun setLanguage(language: Locale) = TextToSpeech.LANG_AVAILABLE
        override fun selectOfflineKoreanVoice() = KoreanOfflineVoiceSelection.SELECTED
        override fun configureAudioAttributes() = Unit
        override fun setListener(listener: FeedbackSpeechRuntime.Listener): Int { this.listener = listener; return TextToSpeech.SUCCESS }
        override fun speak(message: String, queueMode: Int, utteranceId: String): Int {
            spoken += Spoken(message, utteranceId)
            return speakResult
        }
        override fun stop() { stops++ }
        override fun shutdown() { shutdowns++ }
        override fun requestAudioFocus(isRisk: Boolean, listener: AudioManager.OnAudioFocusChangeListener) = true
        override fun abandonAudioFocus(listener: AudioManager.OnAudioFocusChangeListener) = Unit
        override fun createProgressTone(volumePercent: Int): ProgressToneEffects = kotlin.error("NAV probe must not create a progress tone")
        fun ready() { requireNotNull(initialized)(TextToSpeech.SUCCESS) }
        fun postReady() { handler.post { ready() } }
        fun start(id: String) { requireNotNull(listener).onStart(id) }
        fun done(id: String) { requireNotNull(listener).onDone(id) }
        fun error(id: String) { requireNotNull(listener).onError(id, TextToSpeech.ERROR_SYNTHESIS) }
        fun stopped(id: String) { requireNotNull(listener).onStop(id, false) }
        fun serviceError(id: String) { requireNotNull(listener).onError(id, TextToSpeech.ERROR_SERVICE) }
    }

    companion object {
        private const val TAG = "MainNavWrapperProbe"
        private const val ACTOR = "11111111-1111-4111-8111-111111111111"
        private const val DIRECTION_PROBLEM_NOTICE = "방향 측정이 계속 불안정해 방향 안내를 잠시 멈춥니다. " +
            "안전한 곳에 멈춰 휴대폰을 고정해 주세요. 방향이 확인되면 안내를 이어갑니다."
        private fun point(eastM: Double) = RoutePoint(0.0, eastM / 111_194.92664455874)
        private fun field(main: MainActivity, name: String): Any? =
            MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.get(main)
        private fun setField(main: MainActivity, name: String, value: Any?) =
            MainActivityControllerTestFixture.field(name).apply { isAccessible = true }.set(main, value)
        private fun <T> onMain(instrumentation: Instrumentation, block: () -> T): T {
            val task = FutureTask(Callable(block))
            instrumentation.runOnMainSync(task)
            return task.get() // Fixture/Assertion exceptions propagate; no exception becomes PASS.
        }

        private fun completedOnboarding(actorId: String = ACTOR): FirstRunOnboardingSnapshot {
            val evidence = listOf(FirstRunOnboardingEvidence.VerifiedLogin(FirstRunOpaqueActorBinding.fromProvider(actorId), receipt(1)),
                FirstRunOnboardingEvidence.PurposeAndSafety(receipt(2)), FirstRunOnboardingEvidence.Fp004Training(receipt(3)))
            val restored = FirstRunOnboardingPolicy.restoreVerifiedEmailReceiptPrefix(1L, evidence,
                FirstRunOnboardingEvidenceVerifier { token, value -> value in evidence && token.stage == value.stage })
            check(restored.fullyRestored && restored.restoredEvidenceCount == evidence.size)
            val jit = FirstRunOnboardingPolicy.recordEmailJitPermissionObservation(restored.snapshot,
                restored.snapshot.epoch, restored.snapshot.revision)
            check(jit.accepted)
            val device = FirstRunOnboardingPolicy.recordEmailDeviceCheckPassed(jit.current, jit.current.epoch, jit.current.revision)
            check(device.accepted && device.current.mayEnterWalk)
            return device.current
        }

        private fun completedDeviceCheck(generation: Long): PostLoginDeviceCheckSnapshot {
            val bound = PostLoginDeviceCheckPolicy.bindSession(PostLoginDeviceCheckPolicy.initial(), ACTOR, generation)
            val started = PostLoginDeviceCheckPolicy.beginFromUserAction(bound, ACTOR, generation, true)
            val binding = requireNotNull(started.bindingOrNull)
            val running = PostLoginDeviceCheckPolicy.beginRunning(started, binding, true)
            val ready = PostLoginDeviceCheckSignal.READY
            return PostLoginDeviceCheckPolicy.evaluate(running, binding, true, PostLoginDeviceCheckObservation(
                ready, ready, ready, ready, ready, ready, ready, ready, ready, ready, ready,
                metricDepth = PostLoginMetricDepthState.SUPPORTED)).also { check(it.state == PostLoginDeviceCheckState.FULL) }
        }

        private fun completedPriorityEducation() = PriorityUserOnboardingPolicy().apply {
            selectAgeBand(PriorityUserAgeBand.ADULT_18_PLUS)
            reviewSafetyEducation(true)
            reviewPracticeNecessity(true)
            acceptEducationConsent()
            acknowledgeUsageConditions()
            finishAppUsageEducationPlayback(requireNotNull(beginAppUsageEducationPlayback()), completed = true)
            confirmSafePracticePlace()
            PriorityUserPractice.entries.forEach { practice ->
                val token = requireNotNull(beginPractice(practice))
                PriorityUserPracticeDeliverySignal.entries.forEach { recordPracticeDelivery(token, it) }
                if (practice == PriorityUserPractice.HAZARD_ALERT) confirmHazardResponse(token)
            }
            acceptAppUsageEducation()
            check(snapshot().nativeEducationComplete)
        }

        private fun receipt(value: Int) = FirstRunReceiptHash.fromSha256Hex(value.toString(16).padStart(64, '0'))
    }
}
