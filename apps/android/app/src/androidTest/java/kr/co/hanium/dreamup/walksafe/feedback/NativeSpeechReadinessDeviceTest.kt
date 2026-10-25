package kr.co.hanium.dreamup.walksafe.feedback

import android.app.Instrumentation
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.Callable
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Native Korean engine initialization only, using the public production constructor and close.
 * No speech/haptic request, injected runtime, Activity, account, permission/settings change or
 * voice-data installation is performed. Native READY is not synthesis, playback or audibility.
 * Initialization-failure haptic gate visits are observed without changing the default allowance;
 * the resulting hardware vibration and native resource-release acknowledgement are not measured.
 */
@RunWith(AndroidJUnit4::class)
class NativeSpeechReadinessDeviceTest {
    @Test(timeout = 35_000L)
    fun nativeOfflineKoreanEngineBecomesReadyWithoutOutputAndCloses() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val readyCallbacks = AtomicInteger()
        val unavailableCallbacks = AtomicInteger()
        val callbackAtMs = AtomicLong(-1L)
        val speechGateVisits = AtomicInteger()
        val hapticGateVisits = AtomicInteger()
        val initialized = CountDownLatch(1)
        val events = CopyOnWriteArrayList<SpeechOutputEvent>()
        val startedAtMs = SystemClock.elapsedRealtime()
        var actor: AndroidFeedbackActuator? = null
        var callbackReceived = false
        var observedStatus = "NOT_CREATED"
        var observedInitializing = true
        var observedSpeechActive = false
        var cleanupReturned = false

        try {
            onMain(instrumentation) {
                check(Looper.myLooper() == Looper.getMainLooper())
                actor = AndroidFeedbackActuator(
                    context = instrumentation.targetContext,
                    speechAllowed = { speechGateVisits.incrementAndGet(); true },
                    hapticAllowed = { hapticGateVisits.incrementAndGet(); true },
                    onOfflineKoreanSpeechReady = {
                        readyCallbacks.incrementAndGet()
                        callbackAtMs.compareAndSet(-1L, SystemClock.elapsedRealtime())
                        Log.i(TAG, "callback=READY atMs=${SystemClock.elapsedRealtime()}")
                        initialized.countDown()
                    },
                    onOfflineKoreanSpeechUnavailable = {
                        unavailableCallbacks.incrementAndGet()
                        callbackAtMs.compareAndSet(-1L, SystemClock.elapsedRealtime())
                        Log.i(TAG, "callback=UNAVAILABLE atMs=${SystemClock.elapsedRealtime()}")
                        initialized.countDown()
                    },
                ).also { it.speechOutputListener = { event -> events.add(event) } }
                Log.i(TAG, "stage=CREATED status=${checkNotNull(actor).statusText()} " +
                    "atMs=${SystemClock.elapsedRealtime()} injectedRuntime=false outputRequests=0")
            }
            callbackReceived = initialized.await(
                AndroidFeedbackActuator.INITIALIZATION_READINESS_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS,
            )
            instrumentation.waitForIdleSync()
            // A bounded observation window after initialization, without any output request.
            SystemClock.sleep(250L)
            onMain(instrumentation) {
                val owned = checkNotNull(actor)
                observedStatus = owned.statusText()
                observedInitializing = owned.isSpeechInitializing()
                observedSpeechActive = owned.isAppSpeechActive()
                Log.i(TAG, "stage=INITIALIZATION_OBSERVED callbackReceived=$callbackReceived " +
                    "readyCallbacks=${readyCallbacks.get()} unavailableCallbacks=${unavailableCallbacks.get()} " +
                    "callbackAtMs=${callbackAtMs.get()} observedAtMs=${SystemClock.elapsedRealtime()} " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs} status=$observedStatus " +
                    "initializing=$observedInitializing speechActive=$observedSpeechActive " +
                    "speechEvents=${events.size} speechGateVisits=${speechGateVisits.get()} " +
                    "initFailureHapticGateVisits=${hapticGateVisits.get()} outputRequests=0")
            }
        } finally {
            onMain(instrumentation) {
                actor?.let { owned ->
                    owned.close()
                    cleanupReturned = true
                    Log.i(TAG, "stage=CLEANUP closeReturned=true status=${owned.statusText()} " +
                        "initializing=${owned.isSpeechInitializing()} speechActive=${owned.isAppSpeechActive()} " +
                        "atMs=${SystemClock.elapsedRealtime()} speechEvents=${events.size} " +
                        "initFailureHapticGateVisits=${hapticGateVisits.get()} nativeReleaseAck=UNOBSERVED")
                    assertEquals("Owned component must be retired", "tts=closed", owned.statusText())
                    assertFalse(owned.isSpeechInitializing())
                    assertFalse(owned.isAppSpeechActive())
                }
            }
        }

        assertTrue("Owned native component cleanup must return", cleanupReturned)
        assertTrue("ENV_NATIVE_TTS_UNAVAILABLE: initialization callback timed out; status=$observedStatus", callbackReceived)
        assertEquals("ENV_NATIVE_TTS_UNAVAILABLE: native Korean/offline/listener preparation status=$observedStatus " +
            "ready=${readyCallbacks.get()} unavailable=${unavailableCallbacks.get()}", "tts=ready", observedStatus)
        assertEquals(1, readyCallbacks.get())
        assertEquals(0, unavailableCallbacks.get())
        assertTrue("Readiness must be observed on the actual monotonic clock", callbackAtMs.get() >= startedAtMs)
        assertFalse(observedInitializing)
        assertFalse(observedSpeechActive)
        assertEquals("No output request may enter speech admission", 0, speechGateVisits.get())
        assertEquals("Successful initialization must not visit failure haptic admission", 0, hapticGateVisits.get())
        assertEquals("No requested/start/terminal speech event is expected", 0, events.size)
        Log.i(TAG, "PASS nativeReadiness=READY readyCallbacks=1 unavailableCallbacks=0 speechEvents=0 " +
            "outputRequests=0 closeReturned=true mainActivityLaunched=false nativePlayback=NOT_TESTED")
    }

    companion object {
        private const val TAG = "NativeSpeechReadiness"

        private fun <T> onMain(instrumentation: Instrumentation, block: () -> T): T {
            val task = FutureTask(Callable(block))
            instrumentation.runOnMainSync(task)
            return task.get()
        }
    }
}
