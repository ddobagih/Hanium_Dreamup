package kr.co.hanium.dreamup.walksafe.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoskWakePhraseProbeTest {
    @Test
    fun becomesAvailableOnlyAfterReadyAndTrustedFinalWakePhrase() {
        val harness = ProbeHarness()

        assertTrue(harness.probe.start())
        harness.listener.onTranscript(7L, transcript("길라잡이", confidence = 0.95f))
        harness.listener.onReady(7L)
        harness.listener.onTranscript(
            7L,
            transcript("길라잡이", confidence = 0.95f, isFinal = false),
        )
        harness.listener.onTranscript(7L, transcript("길라잡이", confidence = 0.59f))
        harness.listener.onTranscript(7L, transcript("오늘 날씨 알려줘", confidence = 0.99f))

        assertTrue(harness.results.isEmpty())
        assertEquals(0, harness.stream.stopCount)

        harness.listener.onTranscript(7L, transcript("길라잡이", confidence = 0.95f))

        assertTrue(harness.results.isEmpty())
        assertEquals(1, harness.stream.stopCount)
        assertEquals(1, harness.stream.closeCount)
        harness.dispatcher.runPosted()
        assertEquals(
            listOf(VoskWakePhraseProbeResult.available()),
            harness.results,
        )
        assertTrue(harness.resultCallbacksWereOnMain.single())
    }

    @Test
    fun wakePhraseFollowedByACommandAlsoProvesAvailability() {
        val harness = ProbeHarness()

        harness.probe.start()
        harness.listener.onReady(11L)
        harness.listener.onTranscript(
            11L,
            transcript("길라 잡이 서울역으로 안내해줘", confidence = 0.91f),
        )
        harness.dispatcher.runPosted()

        assertEquals(VoskWakePhraseProbeAvailability.AVAILABLE, harness.results.single().availability)
        assertNull(harness.results.single().failure)
    }

    @Test
    fun duplicateReadyCannotReplaceTheFirstActiveRun() {
        val harness = ProbeHarness()

        harness.probe.start()
        harness.listener.onReady(13L)
        harness.listener.onReady(17L)
        harness.listener.onTranscript(17L, transcript("길라잡이", confidence = 0.99f))

        assertTrue(harness.results.isEmpty())

        harness.listener.onTranscript(13L, transcript("길라잡이", confidence = 0.99f))
        harness.dispatcher.runPosted()

        assertEquals(VoskWakePhraseProbeAvailability.AVAILABLE, harness.results.single().availability)
        assertEquals(1, harness.readyCallbacks)
    }

    @Test
    fun readyCueIsDeliveredOnceOnTheDispatcher() {
        val harness = ProbeHarness()

        harness.probe.start()
        harness.listener.onReady(29L)
        harness.listener.onReady(31L)

        assertEquals(0, harness.readyCallbacks)
        harness.dispatcher.runPosted()
        assertEquals(1, harness.readyCallbacks)
        assertTrue(harness.readyCallbackWasOnMain)
    }

    @Test
    fun timeoutBeforeReadyFailsClosedAndRejectsEveryLateCallback() {
        val harness = ProbeHarness()

        harness.probe.start()
        harness.dispatcher.fireTimeout()
        harness.listener.onReady(19L)
        harness.listener.onTranscript(19L, transcript("길라잡이", confidence = 0.99f))
        harness.listener.onError(19L, VoskStreamingError.INFERENCE_FAILED)
        harness.probe.cancel()
        harness.probe.close()
        harness.dispatcher.runPosted()

        assertEquals(1, harness.results.size)
        assertEquals(VoskWakePhraseProbeAvailability.UNAVAILABLE, harness.results.single().availability)
        assertEquals(VoskWakePhraseProbeFailure.TIMEOUT, harness.results.single().failure)
        assertEquals(1, harness.stream.stopCount)
        assertEquals(1, harness.stream.closeCount)
        assertFalse(harness.probe.start())
    }

    @Test
    fun streamErrorAfterReadyIsReportedOnceWithItsBoundedCategory() {
        val harness = ProbeHarness()

        harness.probe.start()
        harness.listener.onReady(23L)
        harness.listener.onError(23L, VoskStreamingError.AUDIO_READ_FAILED)
        harness.listener.onError(23L, VoskStreamingError.INFERENCE_FAILED)
        harness.listener.onTranscript(23L, transcript("길라잡이", confidence = 0.99f))
        harness.dispatcher.fireTimeout()
        harness.probe.cancel()
        harness.dispatcher.runPosted()

        val result = harness.results.single()
        assertEquals(VoskWakePhraseProbeAvailability.UNAVAILABLE, result.availability)
        assertEquals(VoskWakePhraseProbeFailure.STREAM_ERROR, result.failure)
        assertEquals(VoskStreamingError.AUDIO_READ_FAILED, result.streamingError)
        assertEquals(1, harness.stream.stopCount)
        assertEquals(1, harness.stream.closeCount)
    }

    @Test
    fun cancelAndCloseAreFailClosedAndIdempotent() {
        val cancelled = ProbeHarness()
        cancelled.probe.start()
        cancelled.probe.cancel()
        cancelled.probe.cancel()
        cancelled.probe.close()
        cancelled.dispatcher.runPosted()

        assertEquals(VoskWakePhraseProbeFailure.CANCELLED, cancelled.results.single().failure)
        assertEquals(1, cancelled.stream.stopCount)
        assertEquals(1, cancelled.stream.closeCount)

        val closed = ProbeHarness()
        closed.probe.close()
        closed.probe.close()
        closed.dispatcher.runPosted()

        assertEquals(VoskWakePhraseProbeFailure.CLOSED, closed.results.single().failure)
        assertEquals(0, closed.factory.createCount)
        assertFalse(closed.probe.start())
    }

    @Test
    fun synchronousStartFailureClosesResourcesAndPostsOneUnavailableResult() {
        val harness = ProbeHarness(startFailure = IllegalStateException("start failed"))

        assertFalse(harness.probe.start())
        assertTrue(harness.results.isEmpty())
        harness.dispatcher.runPosted()

        assertEquals(VoskWakePhraseProbeFailure.START_FAILED, harness.results.single().failure)
        assertEquals(1, harness.stream.stopCount)
        assertEquals(1, harness.stream.closeCount)
        assertTrue(harness.resultCallbacksWereOnMain.single())
    }

    private class ProbeHarness(
        startFailure: RuntimeException? = null,
    ) {
        val dispatcher = FakeDispatcher()
        val stream = FakeStream(startFailure)
        val factory = FakeStreamFactory(stream)
        val results = mutableListOf<VoskWakePhraseProbeResult>()
        val resultCallbacksWereOnMain = mutableListOf<Boolean>()
        var readyCallbacks = 0
        var readyCallbackWasOnMain = false
        val probe = VoskWakePhraseProbe(
            streamFactory = factory,
            dispatcher = dispatcher,
            timeoutMs = 4_000L,
            onResult = { result ->
                results += result
                resultCallbacksWereOnMain += dispatcher.runningPostedTask
            },
            onReady = {
                readyCallbacks += 1
                readyCallbackWasOnMain = dispatcher.runningPostedTask
            },
        )

        val listener: VoskStreamingListener
            get() = factory.listener
    }

    private class FakeStreamFactory(
        private val stream: FakeStream,
    ) : VoskWakePhraseStreamFactory {
        var createCount = 0
        lateinit var listener: VoskStreamingListener

        override fun create(listener: VoskStreamingListener): VoskWakePhraseStream {
            createCount += 1
            this.listener = listener
            return stream
        }
    }

    private class FakeStream(
        private val startFailure: RuntimeException?,
    ) : VoskWakePhraseStream {
        var startCount = 0
        var stopCount = 0
        var closeCount = 0

        override fun start() {
            startCount += 1
            startFailure?.let { throw it }
        }

        override fun stop() {
            stopCount += 1
        }

        override fun close() {
            closeCount += 1
        }
    }

    private class FakeDispatcher : VoskWakePhraseProbeDispatcher {
        private val posted = ArrayDeque<() -> Unit>()
        private var timeout: ScheduledTask? = null
        var runningPostedTask = false
            private set

        override fun post(task: () -> Unit) {
            posted += task
        }

        override fun postDelayed(
            delayMs: Long,
            task: () -> Unit,
        ): VoskWakePhraseProbeCancellation {
            check(delayMs > 0L)
            return ScheduledTask(task).also { timeout = it }
        }

        fun fireTimeout() {
            timeout?.runUnlessCancelled()
        }

        fun runPosted() {
            while (posted.isNotEmpty()) {
                runningPostedTask = true
                try {
                    posted.removeFirst().invoke()
                } finally {
                    runningPostedTask = false
                }
            }
        }
    }

    private class ScheduledTask(
        private val task: () -> Unit,
    ) : VoskWakePhraseProbeCancellation {
        private var cancelled = false

        override fun cancel() {
            cancelled = true
        }

        fun runUnlessCancelled() {
            if (!cancelled) task()
        }
    }

    private fun transcript(
        text: String,
        confidence: Float,
        isFinal: Boolean = true,
    ) = VoskTranscript(
        text = text,
        confidence = confidence,
        isFinal = isFinal,
    )
}
