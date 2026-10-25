package kr.co.hanium.dreamup.walksafe.voice

import android.app.Instrumentation
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kr.co.hanium.dreamup.walksafe.depth.MessageLevel
import kr.co.hanium.dreamup.walksafe.feedback.AndroidFeedbackActuator
import kr.co.hanium.dreamup.walksafe.feedback.FeedbackAction
import kr.co.hanium.dreamup.walksafe.feedback.NavigationSpeechDispatchResult
import kr.co.hanium.dreamup.walksafe.feedback.SpeechOutputEvent
import kr.co.hanium.dreamup.walksafe.feedback.SpeechOutputKind
import kr.co.hanium.dreamup.walksafe.feedback.SpeechOutputOutcome
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import kr.co.hanium.dreamup.walksafe.voice.output.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt in with allowSpeechOutputNativeContinuityTest=true: this test plays actual offline Korean TTS.
 * Production SpeechOutputController + AndroidFeedbackActuator + actual MainLooper Handler own
 * readiness, status waiting, priority cancellation and terminal callbacks. No native callback is
 * synthesized and no test resubmits a waiting status or advances a simulated clock.
 * Reader identity/readiness, navigation owner, risk and presentation are CONTROLLED component
 * inputs/ports. No Main.onCreate, login, HTTP, saved account/education/profile/preference, permission,
 * global setting or database change. START/DONE proves engine callbacks, not human audibility,
 * outdoor guidance, real obstacle detection or walking safety. A cancellation terminal confirms
 * the retired output owner; raw engine onStop for that retired owner is not exposed by the actuator.
 */
@RunWith(AndroidJUnit4::class)
class SpeechOutputNativeContinuityDeviceTest {
    @Test(timeout = 90_000L)
    fun actualNavigationDoneAutomaticallyReleasesOneWaitingStatusThroughTheRealMainHandler() {
        val route = Terminal()
        val status = Terminal()
        withReadyController { f ->
            f.onMain {
                assertTrue(f.controller.dispatchNavigationSpeech(
                    "지금부터 길 안내 연속성 시험을 진행합니다. 현재 경로를 따라 삼십 미터 이동한 뒤, 오른쪽으로 회전하세요.",
                    onCompleted = route::complete, onFailed = route::fail,
                ))
            }
            f.trace.await(SpeechOutputKind.NAVIGATION, SpeechOutputOutcome.STARTED, 1)
            f.onMain {
                assertEquals("Status must arrive during real route playback", 0, route.completed.get())
                assertTrue(f.controller.speakCommandResponse(
                    "연결 상태가 복구되었습니다. 길 안내를 계속합니다.",
                    statusExplanation = true, onCompleted = status::complete,
                    onFailed = status::fail, onCancelled = status::cancel,
                ))
                assertTrue(f.controller.hasPendingCommandResponse())
                assertEquals(1, f.trace.count(SpeechOutputKind.NAVIGATION, SpeechOutputOutcome.REQUESTED))
                assertEquals(0, f.trace.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.REQUESTED))
                assertEquals(0, route.failed.get())
                assertEquals(0, status.completed.get())
            }

            // Only the actual engine and the production readiness poll can make progress now.
            route.await("native_route")
            status.await("automatically_resumed_status")
            route.assertCompletedOnce()
            status.assertCompletedOnce()
            f.onMain {
                assertFalse(f.controller.hasPendingCommandResponse())
                assertFalse(f.actuator.isAppSpeechActive())
            }
            assertTrue("Automatic status waiting must schedule the real Main Handler", f.scheduler.readinessPosts.get() > 0)
            f.trace.assertCounts(SpeechOutputKind.NAVIGATION, requested = 1, started = 1, done = 1)
            f.trace.assertCounts(SpeechOutputKind.INTERACTION, requested = 1, started = 1, done = 1)
            f.trace.assertBefore(SpeechOutputKind.NAVIGATION, SpeechOutputOutcome.DONE, 1,
                SpeechOutputKind.INTERACTION, SpeechOutputOutcome.REQUESTED, 1)
            f.trace.assertNoInterruptedOrFailedOutput()
            report("scenario=navigation_then_status native_nav_done=1 native_status_done=1 status_api_requests=1 manual_resubmit=0 actual_handler_posts=${f.scheduler.readinessPosts.get()} native_interruption=0")
        }
        route.assertCompletedOnce()
        status.assertCompletedOnce()
    }

    @Test(timeout = 90_000L)
    fun actualStopRiskCancelsTheCommandAndItsWaitingStatusResumesAutomaticallyAfterRiskDone() {
        val command = Terminal()
        val status = Terminal()
        val risk = Terminal()
        withReadyController { f ->
            f.onMain {
                assertTrue(f.controller.speakCommandResponse(
                    "요청하신 사용자 명령의 결과를 안내합니다. 현재 안내 문장이 끝날 때까지 순서대로 내용을 전달하겠습니다. 안전한 곳에서 안내를 들어 주세요.",
                    onCompleted = command::complete, onFailed = command::fail, onCancelled = command::cancel,
                ))
            }
            f.trace.await(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.STARTED, 1)
            f.onMain {
                assertEquals(0, command.completed.get())
                assertTrue(f.controller.speakCommandResponse(
                    "현재 위치 연결이 복구되어 안내를 다시 이어갑니다.",
                    statusExplanation = true, onCompleted = status::complete,
                    onFailed = status::fail, onCancelled = status::cancel,
                ))
                assertEquals("Deferred status must not enter native TTS before the command finishes", 1,
                    f.trace.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.REQUESTED))
                val result = f.actuator.emit(
                    action = FeedbackAction(
                        trackId = "controlled-native-continuity-stop",
                        message = "멈추세요. 바로 앞에 장애물이 있습니다.",
                        level = MessageLevel.STOP,
                        vibrationPatternMs = null,
                        reason = "CONTROLLED_COMPONENT_RISK",
                        validUntilMs = SystemClock.elapsedRealtime() + 10_000L,
                    ),
                    onSpeechCompleted = risk::complete, onSpeechFailed = risk::fail,
                    onSpeechCancelled = risk::cancel, isStillValidAtStart = { true },
                )
                assertEquals(NavigationSpeechDispatchResult.ACCEPTED, result.speech)
            }
            command.await("priority_cancelled_command")
            command.assertCancelledOnce()
            f.trace.await(SpeechOutputKind.RISK, SpeechOutputOutcome.STARTED, 1)
            f.onMain {
                assertEquals("Status must also wait for the real urgent sentence", 1,
                    f.trace.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.REQUESTED))
                assertEquals(0, status.completed.get())
                assertEquals(0, command.completed.get())
            }
            risk.await("native_stop_risk")
            status.await("status_after_native_risk_done")
            command.assertCancelledOnce()
            risk.assertCompletedOnce()
            status.assertCompletedOnce()
            f.onMain {
                assertFalse(f.controller.hasPendingCommandResponse())
                assertFalse(f.actuator.isAppSpeechActive())
            }
            f.trace.assertCounts(SpeechOutputKind.INTERACTION, requested = 2, started = 2, done = 1)
            f.trace.assertCounts(SpeechOutputKind.RISK, requested = 1, started = 1, done = 1)
            assertEquals(1, f.trace.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.CANCELLED_BY_PRIORITY))
            f.trace.assertBefore(SpeechOutputKind.RISK, SpeechOutputOutcome.DONE, 1,
                SpeechOutputKind.INTERACTION, SpeechOutputOutcome.REQUESTED, 2)
            f.trace.assertNoEngineFailure()
            assertTrue(f.scheduler.readinessPosts.get() > 0)
            report("scenario=command_status_stop command_native_started=1 priority_cancel_terminal=1 command_done=0 risk_native_done=1 status_native_done=1 status_api_requests=1 manual_resubmit=0 raw_retired_engine_stop_ack=NOT_EXPOSED")
        }
        command.assertCancelledOnce()
        risk.assertCompletedOnce()
        status.assertCompletedOnce()
    }

    @Test(timeout = 90_000L)
    fun onlyTheLatestDeferredStatusReachesActualNativeSpeechAfterTheCommandDone() {
        val command = Terminal()
        val obsolete = Terminal()
        val latest = Terminal()
        withReadyController { f ->
            f.onMain {
                assertTrue(f.controller.speakCommandResponse(
                    "현재 요청을 처리한 결과를 안내합니다. 명령 응답을 먼저 끝까지 전달하고, 그 뒤에 최신 상태를 안내하겠습니다.",
                    onCompleted = command::complete, onFailed = command::fail, onCancelled = command::cancel,
                ))
            }
            f.trace.await(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.STARTED, 1)
            f.onMain {
                assertEquals(0, command.completed.get())
                val commandGeneration = f.controller.currentCommandGeneration()
                assertTrue(f.controller.speakCommandResponse(
                    "이전 연결 상태를 다시 확인하고 있습니다.", statusExplanation = true,
                    onCompleted = obsolete::complete, onFailed = obsolete::fail, onCancelled = obsolete::cancel,
                ))
                assertTrue(f.controller.speakCommandResponse(
                    "최신 연결 상태가 확인되었습니다. 안내를 계속합니다.", statusExplanation = true,
                    onCompleted = latest::complete, onFailed = latest::fail, onCancelled = latest::cancel,
                ))
                assertEquals(1, f.trace.count(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.REQUESTED))
                obsolete.assertCancelledOnce()
                assertEquals("Deferred status replacement must not retire the command owner", commandGeneration,
                    f.controller.currentCommandGeneration())
                assertEquals(0, command.cancelled.get())
            }
            command.await("native_command_before_latest_status")
            latest.await("automatically_resumed_latest_status")
            command.assertCompletedOnce()
            obsolete.assertCancelledOnce()
            latest.assertCompletedOnce()
            f.trace.assertCounts(SpeechOutputKind.INTERACTION, requested = 2, started = 2, done = 2)
            f.trace.assertBefore(SpeechOutputKind.INTERACTION, SpeechOutputOutcome.DONE, 1,
                SpeechOutputKind.INTERACTION, SpeechOutputOutcome.REQUESTED, 2)
            f.trace.assertNoInterruptedOrFailedOutput()
            f.onMain {
                assertFalse(f.controller.hasPendingCommandResponse())
                assertFalse(f.actuator.isAppSpeechActive())
            }
            report("scenario=latest_waiting_status command_native_done=1 obsolete_status_native_dispatch=0 obsolete_owner_retired=1 latest_status_native_done=1 manual_resubmit=0")
        }
        command.assertCompletedOnce()
        obsolete.assertCancelledOnce()
        latest.assertCompletedOnce()
    }

    private fun withReadyController(test: (NativeFixture) -> Unit) {
        assumeTrue("Requires explicit allowSpeechOutputNativeContinuityTest=true because actual Korean speech is played",
            InstrumentationRegistry.getArguments().getString("allowSpeechOutputNativeContinuityTest") == "true")
        val f = NativeFixture(InstrumentationRegistry.getInstrumentation())
        report("scope=DEVICE_COMPONENT controller=PRODUCTION tts=ACTUAL_OFFLINE_KOREAN scheduler=ACTUAL_MAIN_HANDLER clock=ACTUAL_SYSTEMCLOCK context_and_risk=CONTROLLED main_on_create=false human_audibility=NOT_TESTED field_safety=NOT_TESTED")
        try {
            f.onMain { f.controller.ensureFeedbackActuator() }
            assertTrue("Native offline Korean TTS initialization timed out", f.initialized.await(25_000L, TimeUnit.MILLISECONDS))
            assertEquals("Opted-in missing native Korean TTS is a failure", "READY", f.readiness.get())
            f.onMain {
                assertNotNull(f.actuator.readySpeechEngineGenerationOrNull())
                assertSame(f.actuator, f.controller.currentActuatorOrNull())
            }
            test(f)
        } finally {
            try { f.onMain { f.foreground = false; f.controller.closeActuator() } }
            finally {
                f.scheduler.close()
                report("native_owned_events=${f.trace.summary()} actual_handler_readiness_posts=${f.scheduler.readinessPosts.get()}")
            }
        }
    }

    private class Terminal {
        private val terminal = CountDownLatch(1)
        val completed = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val cancelled = AtomicInteger(0)
        fun complete() { completed.incrementAndGet(); terminal.countDown() }
        fun fail() { failed.incrementAndGet(); terminal.countDown() }
        fun cancel() { cancelled.incrementAndGet(); terminal.countDown() }
        fun await(label: String) { assertTrue("Actual native terminal timeout: $label", terminal.await(25_000L, TimeUnit.MILLISECONDS)) }
        fun assertCompletedOnce() {
            assertEquals(1, completed.get()); assertEquals(0, failed.get()); assertEquals(0, cancelled.get())
        }
        fun assertCancelledOnce() {
            assertEquals(0, completed.get()); assertEquals(0, failed.get()); assertEquals(1, cancelled.get())
        }
    }

    private class NativeFixture(val instrumentation: Instrumentation) {
        private enum class Page { GUIDANCE }
        var foreground = true
        val scheduler = MainScheduler()
        val trace = NativeTrace()
        val initialized = CountDownLatch(1)
        val readiness = AtomicReference<String?>()
        private var createdActuator: AndroidFeedbackActuator? = null
        val actuator: AndroidFeedbackActuator get() = checkNotNull(createdActuator)
        private val epoch = WalkRuntimeEpoch("controlled-native-output-session", 1L)
        private val owner = NavigationSpeechOwner(epoch, "controlled-route", null)
        private val context = CommandSpeechContext(1, "controlled-component-actor", 1L, epoch,
            Page.GUIDANCE, true, true, false, false)
        val controller = SpeechOutputController(
            debugDiagnostic = {},
            reader = object : SpeechOutputReader {
                override fun lifetime() = SpeechOutputLifetime(1, foreground)
                override fun commandContext() = context
                override fun navigationOwner() = owner
                override fun navigationOwnerIsCurrent(owner: NavigationSpeechOwner) = foreground && owner == this@NativeFixture.owner
                override fun homeFeatureContextAvailable() = true
                override fun stateSourceIsCurrent(source: CurrentStateSpeechLease) = foreground
                override fun guidanceVoiceInputActive() = false
                override fun screenReaderActive() = false
            },
            input = object : SpeechOutputInputEffects {
                override fun invalidateHomeWakeInput() = Unit
                override fun promptState(token: CommandResponseToken, effect: CommandPromptEffect) = Unit
                override fun cancelDeferredInput() = Unit
                override fun refreshHomeWakeListening() = Unit
                override fun cancelHomeWakeListening() = Unit
                override fun cancelCommandDialogs(token: CommandResponseToken) = Unit
            },
            presentation = object : SpeechOutputPresentation {
                override fun showLastResult(message: String) = Unit
                override fun showVoiceStatus(message: String) = Unit
                override fun refreshVoiceControls() = Unit
                override fun navigationStatus(message: String) = Unit
                override fun commandTerminalDiagnostic(completed: Boolean) = Unit
                override fun accessibilityTargetOrNull(): SpeechAccessibilityTarget? = null
                override fun canScheduleInteraction() = true
                override fun postInteraction(task: Runnable, delayMs: Long) { scheduler.postReadiness(task, delayMs) }
                override fun removeInteraction(task: Runnable) { scheduler.removeReadiness(task) }
            },
            readyEffects = object : SpeechOutputReadyEffects {
                override fun refreshReadyUi() = Unit
                override fun reevaluateCurrentRisk(actuator: AndroidFeedbackActuator, lifecycleGeneration: Int, nowMs: Long) = Unit
                override fun retryCurrentNavigation(nowMs: Long) = Unit
                override fun offlineSpeechUnavailable(lifecycleGeneration: Int, actuator: AndroidFeedbackActuator) = Unit
                override fun beforeCommandReplacement(previousGeneration: Long) = Unit
            },
            storage = object : SpeechOutputStorageBinding {
                override fun invalidate() = Unit
                override fun refresh(actuator: AndroidFeedbackActuator) = Unit
            },
            actuatorFactory = SpeechOutputActuatorFactory { unavailable, ready ->
                AndroidFeedbackActuator(
                    context = instrumentation.targetContext,
                    hapticAllowed = { false },
                    onOfflineKoreanSpeechReady = {
                        ready()
                        if (readiness.compareAndSet(null, "READY")) initialized.countDown()
                    },
                    onOfflineKoreanSpeechUnavailable = {
                        unavailable()
                        if (readiness.compareAndSet(null, "UNAVAILABLE")) initialized.countDown()
                    },
                ).also {
                    createdActuator = it
                    it.speechOutputListener = trace::record
                }
            },
            scheduler = scheduler,
        )

        fun <T> onMain(action: () -> T): T {
            val result = AtomicReference<T>()
            val failure = AtomicReference<Throwable?>()
            instrumentation.runOnMainSync {
                try { result.set(action()) } catch (error: Throwable) { failure.set(error) }
            }
            failure.get()?.let { throw it }
            return result.get()
        }
    }

    private class MainScheduler : SpeechOutputScheduler {
        private val handler = Handler(Looper.getMainLooper())
        val readinessPosts = AtomicInteger(0)
        override fun elapsedRealtimeMs() = SystemClock.elapsedRealtime()
        override fun isMainThread() = Looper.myLooper() == Looper.getMainLooper()
        override fun runOnMain(action: () -> Unit) {
            if (isMainThread()) action() else assertTrue(handler.post { action() })
        }
        override fun postReadiness(task: Runnable, delayMs: Long) {
            readinessPosts.incrementAndGet()
            assertTrue(handler.postDelayed(task, delayMs))
        }
        override fun removeReadiness(task: Runnable) { handler.removeCallbacks(task) }
        override fun postRetired(action: () -> Unit) { assertTrue(handler.post { action() }) }
        fun close() { handler.removeCallbacksAndMessages(null) }
    }

    private class NativeTrace {
        private val lock = ReentrantLock()
        private val changed = lock.newCondition()
        private val events = mutableListOf<SpeechOutputEvent>()
        fun record(event: SpeechOutputEvent) = lock.withLock { events += event; changed.signalAll() }
        fun count(kind: SpeechOutputKind, outcome: SpeechOutputOutcome): Int = lock.withLock {
            events.count { it.kind == kind && it.outcome == outcome }
        }
        fun await(kind: SpeechOutputKind, outcome: SpeechOutputOutcome, expected: Int) = lock.withLock {
            var remaining = TimeUnit.SECONDS.toNanos(12L)
            while (count(kind, outcome) < expected && remaining > 0L) remaining = changed.awaitNanos(remaining)
            assertEquals("Actual engine event missing: $kind/$outcome; ${summary()}", expected, count(kind, outcome))
        }
        fun assertCounts(kind: SpeechOutputKind, requested: Int, started: Int, done: Int) {
            assertEquals(requested, count(kind, SpeechOutputOutcome.REQUESTED))
            assertEquals(started, count(kind, SpeechOutputOutcome.STARTED))
            assertEquals(done, count(kind, SpeechOutputOutcome.DONE))
        }
        fun assertBefore(firstKind: SpeechOutputKind, firstOutcome: SpeechOutputOutcome, firstOrdinal: Int,
            secondKind: SpeechOutputKind, secondOutcome: SpeechOutputOutcome, secondOrdinal: Int) = lock.withLock {
            fun position(kind: SpeechOutputKind, outcome: SpeechOutputOutcome, ordinal: Int) =
                events.indices.filter { events[it].kind == kind && events[it].outcome == outcome }.getOrNull(ordinal - 1)
                    ?: throw AssertionError("Missing $kind/$outcome #$ordinal; ${summary()}")
            assertTrue("Native output order is incorrect: ${summary()}",
                position(firstKind, firstOutcome, firstOrdinal) < position(secondKind, secondOutcome, secondOrdinal))
        }
        fun assertNoInterruptedOrFailedOutput() = lock.withLock {
            assertNoEngineFailure()
            assertFalse("Unexpected interruption: ${summary()}", events.any { it.outcome in setOf(
                SpeechOutputOutcome.CANCELLED_BY_PRIORITY, SpeechOutputOutcome.CANCELLED_STALE_NAVIGATION,
                SpeechOutputOutcome.INTERRUPTED, SpeechOutputOutcome.STOPPED,
            ) })
        }
        fun assertNoEngineFailure() = lock.withLock {
            assertFalse("Native engine failed: ${summary()}", events.any { it.outcome in setOf(
                SpeechOutputOutcome.ENGINE_ERROR, SpeechOutputOutcome.TERMINAL_TIMEOUT,
                SpeechOutputOutcome.FOCUS_DENIED, SpeechOutputOutcome.FOCUS_LOST,
            ) })
        }
        fun summary(): String = lock.withLock { events.joinToString(",") { "${it.kind}.${it.outcome}@${it.atElapsedRealtimeMs}" } }
    }

    private companion object {
        fun report(message: String) {
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "SPEECH_OUTPUT_NATIVE_CONTINUITY $message\n")
            })
        }
    }
}
