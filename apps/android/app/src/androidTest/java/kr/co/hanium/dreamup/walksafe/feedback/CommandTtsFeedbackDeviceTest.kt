package kr.co.hanium.dreamup.walksafe.feedback

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kr.co.hanium.dreamup.walksafe.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Emulator only, opt in with -e runCommandTtsDeviceTest true.
 * Requires an already installed offline Korean TTS voice and usable app foreground state.
 * Uses the real engine and callbacks; does not install voices, change permissions or enable gates.
 * Unexpected onError is a failure, but this test does not deliberately induce an engine error.
 */
@RunWith(AndroidJUnit4::class)
class CommandTtsFeedbackDeviceTest {
    @Test(timeout = 60_000L)
    fun realHomeResponsesCompleteAndCancellationStopsEngineWithoutLateCompletion() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assumeTrue(
            "Explicit emulator TTS opt-in is required",
            InstrumentationRegistry.getArguments().getString("runCommandTtsDeviceTest") == "true",
        )
        val emulatorHardware = Build.HARDWARE.lowercase(Locale.ROOT) in setOf("ranchu", "goldfish")
        val emulatorIdentity = Build.FINGERPRINT.startsWith("generic") ||
            Build.PRODUCT.lowercase(Locale.ROOT).contains("sdk") ||
            Build.MODEL.lowercase(Locale.ROOT).contains("sdk")
        assumeTrue("Physical devices must not run this test", emulatorHardware && emulatorIdentity)

        val deadlineMs = SystemClock.elapsedRealtime() + 55_000L
        val actuatorReference = AtomicReference<AndroidFeedbackActuator?>()
        var activity: Activity? = null
        try {
            activity = instrumentation.startActivitySync(
                Intent(instrumentation.targetContext, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            val foregroundActivity = checkNotNull(activity)
            waitUntil(deadlineMs, 5_000L, "MainActivity did not obtain real window focus") {
                onMain(deadlineMs) {
                    !foregroundActivity.isFinishing && !foregroundActivity.isDestroyed &&
                        foregroundActivity.hasWindowFocus()
                }
            }

            val initialized = CountDownLatch(1)
            val ready = AtomicBoolean(false)
            onMain(deadlineMs) {
                actuatorReference.set(
                    AndroidFeedbackActuator(
                        context = instrumentation.targetContext,
                        speechAllowed = { true },
                        hapticAllowed = { false },
                        onOfflineKoreanSpeechReady = {
                            ready.set(true)
                            initialized.countDown()
                        },
                        onOfflineKoreanSpeechUnavailable = { initialized.countDown() },
                    ),
                )
            }
            await(initialized, deadlineMs, 18_000L, "No real offline Korean TTS readiness callback")
            assertTrue("Offline Korean TTS is unavailable; no voice is downloaded by this test", ready.get())
            val actuator = checkNotNull(actuatorReference.get())

            val first = TerminalEvents()
            dispatch(actuator, "첫 번째 음성 응답 시험입니다.", first, deadlineMs)
            await(first.terminal, deadlineMs, 10_000L, "First response did not receive a terminal callback")
            assertEquals("First response requires actual onDone", 1, first.completed.get())
            assertEquals("First response failed", 0, first.failed.get())

            val second = TerminalEvents()
            dispatch(actuator, "두 번째 음성 응답도 정상적으로 완료되었습니다.", second, deadlineMs)
            await(second.terminal, deadlineMs, 10_000L, "Second response did not receive a terminal callback")
            assertEquals("Second response requires actual onDone", 1, second.completed.get())
            assertEquals("Second response failed", 0, second.failed.get())
            assertEquals("First response must not complete twice", 1, first.completed.get())
            Log.i(TAG, "two_home_responses_actual_onDone=true")

            val cancelled = TerminalEvents()
            dispatch(
                actuator,
                "이 음성은 취소 동작을 확인하는 시험 안내입니다. 취소하면 이 안내가 끝까지 재생되지 않아야 합니다. ".repeat(6),
                cancelled,
                deadlineMs,
            )
            // Read only: do not replace the engine, listener or terminal callbacks.
            val engine = onMain(deadlineMs) {
                val field = AndroidFeedbackActuator::class.java.getDeclaredField("textToSpeech")
                field.isAccessible = true
                field.get(actuator) as TextToSpeech
            }
            waitUntil(deadlineMs, 5_000L, "Cancellation requires the real engine to report speaking") {
                onMain(deadlineMs) { engine.isSpeaking }
            }
            assertEquals("Long response unexpectedly completed before cancellation", 0, cancelled.completed.get())
            onMain(deadlineMs) { actuator.cancelCommandInteraction() }
            await(cancelled.terminal, deadlineMs, 3_000L, "Cancellation did not release the failure callback")
            assertEquals("Cancellation must fail exactly once", 1, cancelled.failed.get())
            assertEquals("Cancellation must never count as onDone", 0, cancelled.completed.get())
            waitUntil(deadlineMs, 3_000L, "Cancellation did not stop the actual Android TTS engine") {
                onMain(deadlineMs) { !engine.isSpeaking && !actuator.isAppSpeechActive() }
            }

            val observationEndMs = SystemClock.elapsedRealtime() + 3_000L
            while (SystemClock.elapsedRealtime() < observationEndMs) {
                remainingMs(deadlineMs)
                assertEquals("Late onDone escaped cancellation", 0, cancelled.completed.get())
                assertEquals("Cancellation delivered duplicate failure", 1, cancelled.failed.get())
                assertFalse("Cancelled audio resumed", onMain(deadlineMs) { engine.isSpeaking })
                Thread.sleep(100L)
            }
            Log.i(TAG, "command_cancel_actual_engine_stopped=true late_done_observation_ms=3000")
        } finally {
            val activityToFinish = activity
            onMain(SystemClock.elapsedRealtime() + 3_000L) {
                try {
                    actuatorReference.getAndSet(null)?.close()
                } finally {
                    activityToFinish?.finish()
                }
            }
        }
    }

    private fun dispatch(
        actuator: AndroidFeedbackActuator,
        message: String,
        events: TerminalEvents,
        deadlineMs: Long,
    ) {
        val result = onMain(deadlineMs) {
            actuator.speakHomeCommandInteraction(
                message = message,
                onCompleted = {
                    events.completed.incrementAndGet()
                    events.terminal.countDown()
                },
                onFailed = {
                    events.failed.incrementAndGet()
                    events.terminal.countDown()
                },
            )
        }
        assertEquals("Actual TTS dispatch was not accepted", NavigationSpeechDispatchResult.ACCEPTED, result)
    }

    private fun await(latch: CountDownLatch, deadlineMs: Long, timeoutMs: Long, message: String) {
        assertTrue(message, latch.await(minOf(timeoutMs, remainingMs(deadlineMs)), TimeUnit.MILLISECONDS))
    }

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
        assertTrue("Main-thread action exceeded the TTS test deadline", done.await(remainingMs(deadlineMs), TimeUnit.MILLISECONDS))
        error.get()?.let { throw it }
        return result.get()
    }

    private fun remainingMs(deadlineMs: Long): Long =
        (deadlineMs - SystemClock.elapsedRealtime()).also { check(it > 0L) { "TTS test deadline exceeded" } }

    private class TerminalEvents {
        val terminal = CountDownLatch(1)
        val completed = AtomicInteger(0)
        val failed = AtomicInteger(0)
    }

    private companion object {
        const val TAG = "CommandTtsDeviceTest"
    }
}
