package kr.co.hanium.dreamup.walksafe.feedback

import kr.co.hanium.dreamup.walksafe.MainActivityControllerTestFixture

import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.MainActivity
import kr.co.hanium.dreamup.walksafe.session.DeviceTestAccountUiLogin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt in with runMainCommandSpeechColdStartDeviceTest=true. Physical devices additionally require
 * allowPhysicalMainCommandSpeechColdStartDeviceTest=true. A real signed-in, education-complete
 * home context is required; this test never constructs account/education/permission evidence.
 * Exercises Main's cold-start response and page cancellation, not microphone or walking gates.
 */
@RunWith(AndroidJUnit4::class)
class MainCommandSpeechColdStartDeviceTest {
    @Test(timeout = 60_000L)
    fun realMainColdStartWaitsForDoneAndPageExitCancelsBeforeLateReadiness() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "Explicit Main cold-start TTS opt-in is required",
            arguments.getString("runMainCommandSpeechColdStartDeviceTest") == "true",
        )
        val emulator = Build.HARDWARE.lowercase(Locale.ROOT) in setOf("ranchu", "goldfish") &&
            (Build.FINGERPRINT.startsWith("generic") || Build.PRODUCT.lowercase(Locale.ROOT).contains("sdk"))
        assumeTrue(
            "Physical Main testing requires a separate explicit opt-in",
            emulator || arguments.getString("allowPhysicalMainCommandSpeechColdStartDeviceTest") == "true",
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadlineMs = SystemClock.elapsedRealtime() + 55_000L
        var activity: MainActivity? = null
        val cancelledResponse = TerminalEvents()
        try {
            activity = instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            ) as MainActivity
            val foregroundActivity = checkNotNull(activity)
            waitUntil(deadlineMs, 5_000L, "MainActivity did not obtain real window focus") {
                onMain(deadlineMs) {
                    !foregroundActivity.isFinishing && !foregroundActivity.isDestroyed &&
                        foregroundActivity.hasWindowFocus()
                }
            }
            DeviceTestAccountUiLogin.ensureLoggedIn(instrumentation, foregroundActivity, deadlineMs)
            waitUntil(
                deadlineMs,
                15_000L,
                "Required precondition unavailable after 15 seconds: actual foreground account and completed education",
            ) {
                onMain(deadlineMs) {
                    foregroundActivity.hasWindowFocus() &&
                        invokeBoolean(foregroundActivity, "firstRunOnboardingComplete") &&
                        invokeBoolean(foregroundActivity, "nativeHomeFeatureContextAvailable")
                }
            }
            onMain(deadlineMs) {
                assertTrue(
                    "Actual account or completed education changed before the cold-start request",
                    invokeBoolean(foregroundActivity, "firstRunOnboardingComplete") &&
                        invokeBoolean(foregroundActivity, "nativeHomeFeatureContextAvailable"),
                )
                val recording = MainActivityControllerTestFixture.field("voiceRecognitionActive").apply {
                    isAccessible = true
                }.getBoolean(foregroundActivity)
                assertFalse("Do not interfere with a real recording", recording)
                showPage(foregroundActivity, "VOICE_COMMAND")
                assertTrue(
                    "Voice page lost the actual home feature context",
                    invokeBoolean(foregroundActivity, "nativeHomeFeatureContextAvailable"),
                )
            }
            waitUntil(deadlineMs, 10_000L, "Existing app speech did not finish before the cold-start test") {
                onMain(deadlineMs) { currentActuator(foregroundActivity)?.isAppSpeechActive() != true }
            }

            val completedResponse = TerminalEvents()
            onMain(deadlineMs) {
                requestColdResponse(
                    foregroundActivity,
                    "음성 준비가 끝난 뒤 전달된 명령 응답 시험입니다.",
                    completedResponse,
                    leavePage = false,
                )
            }
            assertTrue(
                "Main did not deliver a real terminal callback after cold initialization",
                completedResponse.terminal.await(minOf(20_000L, remainingMs(deadlineMs)), TimeUnit.MILLISECONDS),
            )
            assertEquals("Main requires actual TTS onDone", 1, completedResponse.completed.get())
            assertEquals("Cold-start response failed", 0, completedResponse.failed.get())
            Log.i(TAG, "main_cold_start_actual_onDone=true")

            val cancelledActuator = onMain(deadlineMs) {
                requestColdResponse(
                    foregroundActivity,
                    "이 안내는 화면을 떠났으므로 재생되면 안 됩니다.",
                    cancelledResponse,
                    leavePage = true,
                )
            }
            waitUntil(deadlineMs, 8_000L, "Cancelled request never reached real late engine readiness") {
                onMain(deadlineMs) {
                    cancelledResponse.assertOutputObserverCurrent(cancelledActuator)
                    checkNotNull(
                        AndroidFeedbackActuator::class.java.getDeclaredField("ttsState").apply {
                            isAccessible = true
                        }.get(cancelledActuator),
                    ) { "Missing actual TTS state" }.toString() == "READY"
                }
            }
            val engine = onMain(deadlineMs) {
                AndroidFeedbackActuator::class.java.getDeclaredField("speechRuntime").apply {
                    isAccessible = true
                }.get(cancelledActuator) as AndroidFeedbackSpeechRuntime
            }
            val observationEndMs = SystemClock.elapsedRealtime() + 3_000L
            var globalEngineBusyObserved = false
            while (SystemClock.elapsedRealtime() < observationEndMs) {
                remainingMs(deadlineMs)
                assertEquals("Page exit allowed a late success callback", 0, cancelledResponse.completed.get())
                assertTrue("Cancellation delivered duplicate terminal failures", cancelledResponse.failed.get() <= 1)
                val globalEngineBusy: Boolean = onMain(deadlineMs) {
                    cancelledResponse.assertNoOwnedSpeechOutput(cancelledActuator)
                    assertFalse("Page exit allowed queued app speech", cancelledActuator.isAppSpeechActive())
                    engine.isSpeaking()
                }
                globalEngineBusyObserved = globalEngineBusyObserved || globalEngineBusy
                Thread.sleep(100L)
            }
            onMain(deadlineMs) {
                cancelledResponse.assertNoOwnedSpeechOutput(cancelledActuator)
                assertFalse("Page exit allowed queued app speech", cancelledActuator.isAppSpeechActive())
            }
            assertEquals("Page exit allowed a late success callback", 0, cancelledResponse.completed.get())
            assertTrue("Cancellation delivered duplicate terminal failures", cancelledResponse.failed.get() <= 1)
            Log.i(TAG, "main_page_exit_before_readiness_no_speech=true observation_ms=3000 " +
                "owned_requested=0 owned_started=0 owned_done=0 global_engine_busy_observed=$globalEngineBusyObserved")
        } finally {
            val activityToFinish = activity
            if (activityToFinish != null) onMain(SystemClock.elapsedRealtime() + 3_000L) {
                try {
                    cancelledResponse.restoreSpeechOutputObserver()
                } finally {
                    try {
                        invokeNoArgs(activityToFinish, "cancelCommandSpeechResponse")
                        val field = actuatorField()
                        try {
                            (field.get(activityToFinish) as? AndroidFeedbackActuator)?.close()
                        } finally {
                            field.set(activityToFinish, null)
                        }
                    } finally {
                        activityToFinish.finish()
                    }
                }
            }
        }
    }

    /** Only the real output dependency is reset; account, policy and safety fields are untouched. */
    private fun requestColdResponse(
        activity: MainActivity,
        message: String,
        events: TerminalEvents,
        leavePage: Boolean,
    ): AndroidFeedbackActuator {
        invokeNoArgs(activity, "cancelCommandSpeechResponse")
        val field = actuatorField()
        (field.get(activity) as? AndroidFeedbackActuator)?.close()
        field.set(activity, null)
        val onCompleted: () -> Unit = {
            events.completed.incrementAndGet()
            events.terminal.countDown()
        }
        val onFailed: () -> Unit = {
            events.failed.incrementAndGet()
            events.terminal.countDown()
        }
        val response = MainActivity::class.java.getDeclaredMethod(
            "speakCommandResponse",
            String::class.java,
            java.lang.Boolean.TYPE,
            kotlin.jvm.functions.Function0::class.java,
            kotlin.jvm.functions.Function0::class.java,
            java.lang.Boolean.TYPE,
            kotlin.jvm.functions.Function0::class.java,
            kr.co.hanium.dreamup.walksafe.voice.CurrentStateSpeechLease::class.java,
            kotlin.jvm.functions.Function0::class.java,
            kotlin.jvm.functions.Function0::class.java,
        ).apply { isAccessible = true }
        assertTrue(
            "Main rejected the pending cold-start response before readiness",
            response.invoke(activity, message, false, onCompleted, onFailed, false,
                null, null, { true }, null) as Boolean,
        )
        val actuator = checkNotNull(field.get(activity) as? AndroidFeedbackActuator)
        assertTrue("The test must request speech before the real onInit callback", actuator.isSpeechInitializing())
        assertEquals("Dispatch is not completion", 0, events.completed.get())
        if (leavePage) {
            events.observeSpeechOutput(actuator)
            showPage(activity, "HOME")
        }
        return actuator
    }

    private fun showPage(activity: MainActivity, pageName: String) {
        val page = MainActivityControllerTestFixture.field("nativeUiPage").apply {
            isAccessible = true
        }.get(activity) as Enum<*>
        val constants = checkNotNull(page.javaClass.enumConstants) { "Native page type is not an enum" }
        val target = constants.first { (it as Enum<*>).name == pageName }
        MainActivity::class.java.getDeclaredMethod("showNativeUiPage", page.javaClass).apply {
            isAccessible = true
        }.invoke(activity, target)
    }

    private fun actuatorField() = MainActivityControllerTestFixture.field("feedbackActuator").apply {
        isAccessible = true
    }

    private fun currentActuator(activity: MainActivity) = actuatorField().get(activity) as? AndroidFeedbackActuator

    private fun invokeBoolean(activity: MainActivity, name: String) = invokeNoArgs(activity, name) as Boolean

    private fun invokeNoArgs(activity: MainActivity, name: String): Any? =
        MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)

    private fun waitUntil(deadlineMs: Long, timeoutMs: Long, message: String, condition: () -> Boolean) {
        val untilMs = minOf(deadlineMs, SystemClock.elapsedRealtime() + timeoutMs)
        while (SystemClock.elapsedRealtime() < untilMs) {
            if (condition()) return
            Thread.sleep(50L)
        }
        assertTrue(message, condition())
    }

    private fun <T> onMain(deadlineMs: Long, action: () -> T): T {
        val result = AtomicReference<T>()
        val error = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        assertTrue("Main-thread dispatch failed", Handler(Looper.getMainLooper()).post {
            try {
                result.set(action())
            } catch (failure: Throwable) {
                error.set(failure)
            } finally {
                done.countDown()
            }
        })
        assertTrue("Main-thread action exceeded the test deadline", done.await(remainingMs(deadlineMs), TimeUnit.MILLISECONDS))
        error.get()?.let { throw it }
        return result.get()
    }

    private fun remainingMs(deadlineMs: Long): Long =
        (deadlineMs - SystemClock.elapsedRealtime()).also { check(it > 0L) { "Main cold-start test deadline exceeded" } }

    private class TerminalEvents {
        val terminal = CountDownLatch(1)
        val completed = AtomicInteger(0)
        val failed = AtomicInteger(0)
        private val requested = AtomicInteger(0)
        private val started = AtomicInteger(0)
        private val done = AtomicInteger(0)
        private var observedActuator: AndroidFeedbackActuator? = null
        private var previousListener: ((SpeechOutputEvent) -> Unit)? = null
        private var observingListener: ((SpeechOutputEvent) -> Unit)? = null

        fun observeSpeechOutput(actuator: AndroidFeedbackActuator) {
            check(observedActuator == null)
            val previous = actuator.speechOutputListener
            val observer: (SpeechOutputEvent) -> Unit = { event ->
                when (event.outcome) {
                    SpeechOutputOutcome.REQUESTED -> requested.incrementAndGet()
                    SpeechOutputOutcome.STARTED -> started.incrementAndGet()
                    SpeechOutputOutcome.DONE -> done.incrementAndGet()
                    else -> Unit
                }
                previous?.invoke(event)
            }
            observedActuator = actuator
            previousListener = previous
            observingListener = observer
            actuator.speechOutputListener = observer
        }

        fun assertOutputObserverCurrent(actuator: AndroidFeedbackActuator) {
            assertTrue("Speech observation must belong to the exact cancelled actuator", observedActuator === actuator)
            assertTrue("Cancelled actuator output observation was replaced",
                actuator.speechOutputListener === checkNotNull(observingListener))
        }

        fun assertNoOwnedSpeechOutput(actuator: AndroidFeedbackActuator) {
            assertOutputObserverCurrent(actuator)
            assertEquals("Page exit allowed an owned native speech request", 0, requested.get())
            assertEquals("Page exit allowed an owned native speech start", 0, started.get())
            assertEquals("Page exit allowed an owned native speech completion", 0, done.get())
        }

        fun restoreSpeechOutputObserver() {
            observedActuator?.let { actuator ->
                if (actuator.speechOutputListener === observingListener) {
                    actuator.speechOutputListener = previousListener
                }
            }
            observedActuator = null
            previousListener = null
            observingListener = null
        }
    }

    private companion object {
        const val TAG = "MainCommandTtsTest"
    }
}
