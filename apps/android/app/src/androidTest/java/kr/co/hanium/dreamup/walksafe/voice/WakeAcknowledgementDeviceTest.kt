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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in actual tone API checks, without opening MainActivity or changing user data.
 * The scheduled terminal callback does not establish speaker audibility or recognition success.
 */
@RunWith(AndroidJUnit4::class)
class WakeAcknowledgementDeviceTest {
    @Test(timeout = 10_000L)
    fun boundedCueReleasesBeforeItsSingleCompletionCallback() {
        val instrumentation = optIn()
        val terminal = CountDownLatch(1)
        val result = AtomicReference<WakeAcknowledgementResult?>(null)
        val count = AtomicInteger(0)
        var player: WakeAcknowledgementPlayer? = null
        var startedAtMs = 0L
        var finishedAtMs = 0L
        var playingInCallback = true
        try {
            instrumentation.runOnMainSync {
                val current = WakeAcknowledgementPlayer(instrumentation.targetContext, isAppSpeechActive = { false })
                player = current
                startedAtMs = SystemClock.elapsedRealtime()
                current.play {
                    finishedAtMs = SystemClock.elapsedRealtime()
                    playingInCallback = current.isPlaying()
                    result.set(it)
                    count.incrementAndGet()
                    terminal.countDown()
                }
            }
            assertTrue("WAKE_ACK_CALLBACK_TIMEOUT", terminal.await(3_000L, TimeUnit.MILLISECONDS))
            report(instrumentation, "result=${result.get()} human_audibility=NOT_TESTED")
            assertEquals(WakeAcknowledgementResult.COMPLETED, result.get())
            assertTrue(finishedAtMs - startedAtMs >= WakeAcknowledgementPlayer.TONE_DURATION_MS)
            assertFalse(playingInCallback)
            instrumentation.runOnMainSync { player?.close() }
            assertEquals(1, count.get())
        } finally {
            instrumentation.runOnMainSync { player?.close() }
        }
    }

    @Test(timeout = 10_000L)
    fun duplicatePlayAndRepeatedCancelCannotReplayOrCompleteTheRetiredCue() {
        val instrumentation = optIn()
        val drained = CountDownLatch(1)
        val original = mutableListOf<WakeAcknowledgementResult>()
        val duplicate = mutableListOf<WakeAcknowledgementResult>()
        var player: WakeAcknowledgementPlayer? = null
        try {
            instrumentation.runOnMainSync {
                val current = WakeAcknowledgementPlayer(instrumentation.targetContext, isAppSpeechActive = { false })
                player = current
                current.play { original.add(it) }
                assertTrue("WAKE_ACK_START_REJECTED", current.isPlaying())
                current.play { duplicate.add(it) }
                assertTrue(current.isPlaying())
                current.cancel()
                current.cancel()
                assertFalse(current.isPlaying())
                Handler(Looper.getMainLooper()).postDelayed({ drained.countDown() }, 400L)
            }
            assertTrue(drained.await(2_000L, TimeUnit.MILLISECONDS))
            assertEquals(listOf(WakeAcknowledgementResult.CANCELLED), original)
            assertEquals(listOf(WakeAcknowledgementResult.FAILED), duplicate)
            report(instrumentation, "cancel=ONCE duplicate=REJECTED retired_completion=NONE")
        } finally {
            instrumentation.runOnMainSync { player?.close() }
        }
    }

    @Test(timeout = 10_000L)
    fun activeSpeechAndClosedOwnerRejectCueWithoutKeepingWork() {
        val instrumentation = optIn()
        val results = mutableListOf<WakeAcknowledgementResult>()
        instrumentation.runOnMainSync {
            WakeAcknowledgementPlayer(instrumentation.targetContext, isAppSpeechActive = { true }).use { player ->
                player.play { results.add(it) }
                assertFalse(player.isPlaying())
                player.close()
                player.play { results.add(it) }
                assertFalse(player.isPlaying())
            }
        }
        assertEquals(List(2) { WakeAcknowledgementResult.FAILED }, results)
    }

    private fun optIn(): Instrumentation {
        assumeTrue(InstrumentationRegistry.getArguments().getString("allowWakeAcknowledgementTest") == "true")
        return InstrumentationRegistry.getInstrumentation()
    }

    private fun report(instrumentation: Instrumentation, message: String) {
        instrumentation.sendStatus(0, Bundle().apply {
            putString(Instrumentation.REPORT_KEY_STREAMRESULT, "\nWAKE_ACKNOWLEDGEMENT $message\n")
        })
    }
}
