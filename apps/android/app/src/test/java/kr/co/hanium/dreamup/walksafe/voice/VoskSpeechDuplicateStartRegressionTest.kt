package kr.co.hanium.dreamup.walksafe.voice

import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VoskSpeechDuplicateStartRegressionTest {
    @Test
    fun duplicateDuringLoadingKeepsTheOriginalRunAndCapturedListener() = withFixture { fixture ->
        val originalRun = fixture.startRun()
        val loadingWatchdog = fixture.runtime.lastScheduled(LOAD_TIMEOUT_MS)
        val nextListener = Events()
        fixture.adapter.setRecognitionListener(nextListener)

        fixture.adapter.startRequest()
        assertEquals(1, fixture.permissionChecks)
        assertEquals(1, fixture.streamCreations)
        assertEquals(1, fixture.stream.startCalls)
        assertEquals(0, fixture.stream.stopCalls)
        assertEquals(0, fixture.stream.closeCalls)
        assertTrue(fixture.events.errors.isEmpty())
        assertTrue(nextListener.errors.isEmpty())
        assertSame(loadingWatchdog, fixture.runtime.lastScheduled(LOAD_TIMEOUT_MS))
        assertTrue(fixture.runtime.isQueued(loadingWatchdog.task))

        fixture.stream.ready(originalRun)
        assertEquals("the production listener must post rather than handle ready inline", 0, fixture.events.readyCount)
        assertEquals(0, nextListener.readyCount)
        assertEquals(0, fixture.readyBundleCalls)
        fixture.runtime.drain()
        assertEquals(1, fixture.events.readyCount)
        assertEquals("a duplicate cannot replace the captured request listener", 0, nextListener.readyCount)
        assertEquals(1, fixture.readyBundleCalls)
        assertFalse(fixture.runtime.isQueued(loadingWatchdog.task))
        val listeningWatchdog = fixture.runtime.lastScheduled(LISTEN_TIMEOUT_MS)

        fixture.adapter.cancel()
        assertEquals(1, fixture.stream.stopCalls)
        assertFalse(fixture.runtime.isQueued(listeningWatchdog.task))
        val nextRun = fixture.startRun()
        assertTrue(nextRun != originalRun)
        fixture.stream.ready(originalRun)
        fixture.stream.ready(nextRun)
        fixture.runtime.drain()
        assertEquals(1, fixture.events.readyCount)
        assertEquals("the new explicit request captures the newly installed listener", 1, nextListener.readyCount)
        assertEquals(2, fixture.readyBundleCalls)
        assertEquals(2, fixture.permissionChecks)
        assertEquals(2, fixture.stream.startCalls)
        assertEquals(1, fixture.stream.stopCalls)
        assertTrue(fixture.events.errors.isEmpty())
        assertTrue(nextListener.errors.isEmpty())
    }

    @Test
    fun duplicateDuringListeningKeepsTheWatchdogAndAcceptsTheOriginalError() = withFixture { fixture ->
        val originalRun = fixture.startRun()
        fixture.stream.ready(originalRun)
        fixture.runtime.drain()
        assertEquals(1, fixture.events.readyCount)
        val listeningWatchdog = fixture.runtime.lastScheduled(LISTEN_TIMEOUT_MS)

        fixture.adapter.startRequest()
        assertEquals(1, fixture.permissionChecks)
        assertEquals(1, fixture.streamCreations)
        assertEquals(1, fixture.stream.startCalls)
        assertEquals(0, fixture.stream.stopCalls)
        assertEquals(0, fixture.stream.closeCalls)
        assertTrue(fixture.events.errors.isEmpty())
        assertSame(listeningWatchdog, fixture.runtime.lastScheduled(LISTEN_TIMEOUT_MS))
        assertTrue(fixture.runtime.isQueued(listeningWatchdog.task))

        fixture.stream.error(originalRun, VoskStreamingError.AUDIO_READ_FAILED)
        assertTrue("engine errors must also traverse the production queue post", fixture.events.errors.isEmpty())
        assertEquals(0, fixture.stream.stopCalls)
        fixture.runtime.drain()
        assertEquals(listOf(SpeechRecognizer.ERROR_AUDIO), fixture.events.errors)
        assertEquals(1, fixture.stream.stopCalls)
        assertFalse(fixture.runtime.isQueued(listeningWatchdog.task))
        assertEquals(1, fixture.readyBundleCalls)

        val nextRun = fixture.startRun()
        assertTrue(nextRun != originalRun)
        fixture.stream.ready(nextRun)
        fixture.runtime.drain()
        assertEquals(2, fixture.events.readyCount)
        assertEquals(2, fixture.readyBundleCalls)
        assertEquals(2, fixture.permissionChecks)
        assertEquals(2, fixture.stream.startCalls)
        assertEquals(1, fixture.stream.stopCalls)
        assertEquals(listOf(SpeechRecognizer.ERROR_AUDIO), fixture.events.errors)
    }

    @Test
    fun duplicateDuringLoadingDoesNotExtendTheOriginalLoadDeadline() = withFixture { fixture ->
        val originalRun = fixture.startRun()
        val watchdog = fixture.runtime.lastScheduled(LOAD_TIMEOUT_MS)
        fixture.runtime.advanceBy(LOAD_TIMEOUT_MS - 1L)
        fixture.adapter.startRequest()
        assertEquals(1, fixture.permissionChecks)
        assertEquals(1, fixture.stream.startCalls)
        assertEquals(0, fixture.stream.stopCalls)
        assertTrue(fixture.events.errors.isEmpty())
        assertSame(watchdog, fixture.runtime.lastScheduled(LOAD_TIMEOUT_MS))
        assertTrue(fixture.runtime.isQueued(watchdog.task))

        fixture.runtime.advanceBy(1L)
        assertEquals(listOf(SpeechRecognizer.ERROR_CLIENT), fixture.events.errors)
        assertEquals(1, fixture.stream.stopCalls)
        assertEquals(1, fixture.stream.startCalls)
        assertFalse(fixture.runtime.isQueued(watchdog.task))
        fixture.stream.ready(originalRun)
        fixture.stream.error(originalRun, VoskStreamingError.AUDIO_READ_FAILED)
        fixture.runtime.drain()
        assertEquals(0, fixture.events.readyCount)
        assertEquals(0, fixture.readyBundleCalls)
        assertEquals(listOf(SpeechRecognizer.ERROR_CLIENT), fixture.events.errors)
        fixture.runtime.advanceBy(LOAD_TIMEOUT_MS)
        assertEquals(1, fixture.stream.startCalls)
        assertEquals(1, fixture.stream.stopCalls)
        assertEquals(listOf(SpeechRecognizer.ERROR_CLIENT), fixture.events.errors)
    }

    @Test
    fun duplicateDuringListeningDoesNotExtendTheOriginalListenDeadline() = withFixture { fixture ->
        val originalRun = fixture.startRun()
        fixture.stream.ready(originalRun)
        fixture.runtime.drain()
        val watchdog = fixture.runtime.lastScheduled(LISTEN_TIMEOUT_MS)
        assertEquals(1, fixture.events.readyCount)
        assertEquals(1, fixture.readyBundleCalls)
        fixture.runtime.advanceBy(LISTEN_TIMEOUT_MS - 1L)

        fixture.adapter.startRequest()
        assertEquals(1, fixture.permissionChecks)
        assertEquals(1, fixture.stream.startCalls)
        assertEquals(0, fixture.stream.stopCalls)
        assertTrue(fixture.events.errors.isEmpty())
        assertSame(watchdog, fixture.runtime.lastScheduled(LISTEN_TIMEOUT_MS))
        assertTrue(fixture.runtime.isQueued(watchdog.task))
        fixture.runtime.advanceBy(1L)
        assertEquals(listOf(SpeechRecognizer.ERROR_SPEECH_TIMEOUT), fixture.events.errors)
        assertEquals(1, fixture.stream.stopCalls)
        assertEquals(1, fixture.stream.startCalls)
        assertFalse(fixture.runtime.isQueued(watchdog.task))

        fixture.stream.ready(originalRun)
        fixture.stream.error(originalRun, VoskStreamingError.MODEL_LOAD_FAILED)
        fixture.runtime.drain()
        assertEquals(1, fixture.events.readyCount)
        assertEquals(1, fixture.readyBundleCalls)
        assertEquals(listOf(SpeechRecognizer.ERROR_SPEECH_TIMEOUT), fixture.events.errors)
        fixture.runtime.advanceBy(LISTEN_TIMEOUT_MS)
        assertEquals(1, fixture.stream.startCalls)
        assertEquals(1, fixture.stream.stopCalls)
        assertEquals(listOf(SpeechRecognizer.ERROR_SPEECH_TIMEOUT), fixture.events.errors)
    }

    private fun withFixture(test: (Fixture) -> Unit) {
        val fixture = Fixture()
        var failure: Throwable? = null
        try {
            test(fixture)
        } catch (caught: Throwable) {
            failure = caught
        }
        try {
            fixture.adapter.destroy()
        } catch (caught: Throwable) {
            val first = failure
            if (first == null) {
                failure = caught
            } else if (first !== caught) {
                runCatching { first.addSuppressed(caught) }
            }
        }
        failure?.let { throw it }
        assertEquals(1, fixture.stream.closeCalls)
        assertEquals(1, fixture.runtime.removeAllCalls)
    }

    private class Fixture {
        val runtime = ManualRuntime()
        val events = Events()
        var permissionChecks = 0
        var streamCreations = 0
        var readyBundleCalls = 0
        lateinit var stream: FakeStream

        val adapter = VoskSpeechRecognizer(
            dependencies = VoskSpeechDependencies(
                runtime = runtime,
                microphoneGranted = {
                    permissionChecks += 1
                    true
                },
                createStream = { listener ->
                    streamCreations += 1
                    FakeStream(listener).also { stream = it }
                },
                readyBundle = {
                    readyBundleCalls += 1
                    null
                },
            ),
        )

        init {
            adapter.setRecognitionListener(events)
        }

        fun startRun(): Long {
            val previousStarts = stream.startCalls
            adapter.startRequest()
            assertEquals(previousStarts + 1, stream.startCalls)
            assertEquals(1, streamCreations)
            return stream.lastReservedRunId
        }
    }

    private class Events : RecognitionListener {
        var readyCount = 0
        val errors = mutableListOf<Int>()

        override fun onReadyForSpeech(params: Bundle?) { readyCount += 1 }
        override fun onError(error: Int) { errors += error }
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onResults(results: Bundle?) = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    /** Run reservation and callback delivery only; request fencing stays in the adapter. */
    private class FakeStream(
        private val listener: VoskStreamingListener,
    ) : VoskRequestStream {
        var lastReservedRunId = 0L
            private set
        var startCalls = 0
            private set
        var stopCalls = 0
            private set
        var closeCalls = 0
            private set

        override fun start(onRunReserved: (Long) -> Unit): Long? {
            startCalls += 1
            lastReservedRunId += 1L
            onRunReserved(lastReservedRunId)
            return lastReservedRunId
        }

        override fun stop() {
            stopCalls += 1
        }

        override fun close() {
            closeCalls += 1
        }

        fun ready(runId: Long) {
            listener.onReady(runId)
        }

        fun error(runId: Long, error: VoskStreamingError) {
            listener.onError(runId, error)
        }
    }

    private data class Scheduled(
        val task: Runnable,
        val delayMs: Long,
        val dueMs: Long,
        val sequence: Long,
    )

    private class ManualRuntime : VoiceCallbackRuntime {
        private var currentMs = 0L
        private var sequence = 0L
        private val pending = mutableListOf<Scheduled>()
        private val scheduled = mutableListOf<Scheduled>()
        var removeAllCalls = 0
            private set

        override fun checkMainThread(owner: String) = Unit
        override fun nowMs(): Long = currentMs

        override fun post(task: Runnable) {
            postDelayed(task, 0L)
        }

        override fun postDelayed(task: Runnable, delayMs: Long) {
            require(delayMs >= 0L)
            val entry = Scheduled(task, delayMs, currentMs + delayMs, sequence++)
            scheduled += entry
            pending += entry
        }

        override fun removeCallbacks(task: Runnable) {
            pending.removeAll { it.task === task }
        }

        override fun removeAllCallbacks() {
            removeAllCalls += 1
            pending.clear()
        }

        fun lastScheduled(delayMs: Long): Scheduled = scheduled.last { it.delayMs == delayMs }
        fun isQueued(task: Runnable): Boolean = pending.any { it.task === task }

        fun drain() {
            advanceBy(0L)
        }

        fun advanceBy(deltaMs: Long) {
            require(deltaMs >= 0L)
            val target = currentMs + deltaMs
            while (true) {
                val next = pending
                    .filter { it.dueMs <= target }
                    .minWithOrNull(compareBy<Scheduled> { it.dueMs }.thenBy { it.sequence })
                    ?: break
                pending.remove(next)
                currentMs = next.dueMs
                next.task.run()
            }
            currentMs = target
        }
    }

    private companion object {
        const val LOAD_TIMEOUT_MS = 20_000L
        const val LISTEN_TIMEOUT_MS = 10_000L
    }
}
