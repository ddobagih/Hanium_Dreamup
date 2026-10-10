package kr.co.hanium.dreamup.walksafe.voice

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class VoiceInputRouteGuardTest {
    @Test fun sameDeviceAndInitialUnknownAreNotChanges() {
        val guard = VoiceInputRouteGuard()
        assertFalse(guard.observe(null))
        assertFalse(guard.observe(null))
        assertFalse(guard.observe(7))
        assertFalse(guard.observe(7))
    }

    @Test fun switchingOrLosingDeviceInvalidatesWholeCaptureEvenIfItReturns() {
        for (next in listOf(8, null)) {
            val guard = VoiceInputRouteGuard()
            assertFalse(guard.observe(7))
            assertTrue(guard.observe(next))
            assertTrue(guard.observe(7))
            assertFalse("explicit new capture has a new baseline", VoiceInputRouteGuard().observe(8))
        }
    }

    @Test fun routeChangeHasDistinctErrorAndBlocksPassiveRestart() {
        assertEquals(VoskStreamingError.AUDIO_ROUTE_CHANGED, voskAudioInputFailure(PCM_INPUT_ROUTE_CHANGED_CODE))
        val recovery = WalkVoiceStartRecovery()
        val presentation = recovery.recordFailure(WalkVoiceStartFailure.INPUT_ROUTE_CHANGED, true)
        assertEquals(MICROPHONE_INPUT_ROUTE_CHANGED_GUIDANCE, presentation.guidance)
        assertTrue(recovery.blocksAutomaticRetry)
        recovery.onForegroundReturn()
        assertTrue(recovery.blocksAutomaticRetry)
        recovery.onStopped()
        recovery.onForegroundReturn()
        assertFalse(recovery.blocksAutomaticRetry)
        assertEquals(VoiceRequestOutcome.INPUT_ROUTE_CHANGED,
            voiceRequestStageSummaryOrNull(HandsFreeVoiceTerminalFailure.Transcriber(VoskStreamingError.AUDIO_ROUTE_CHANGED))?.outcome)
    }

    @Test fun routeFailureReleasesCaptureOnceAndOldCallbackCannotCancelNewCapture() {
        val loader = Executors.newSingleThreadExecutor()
        val callbacks = mutableListOf<(Int) -> Unit>()
        val ready = java.util.concurrent.LinkedBlockingQueue<Long>()
        val errors = java.util.concurrent.LinkedBlockingQueue<VoskStreamingError>()
        val stops = AtomicInteger()
        val transcriber = VoskStreamingTranscriber(null, File("unused"), { false }, object : VoskStreamingListener {
            override fun onReady(runId: Long) { ready.add(runId) }
            override fun onTranscript(runId: Long, transcript: VoskTranscript) = fail("no transcript expected")
            override fun onError(runId: Long, error: VoskStreamingError) { errors.add(error) }
        }, loader = loader, preparedRunFactory = { callback ->
            callbacks.add(callback)
            VoskPreparedRun(object : VoskPcmSource {
                override fun start() = Unit
                override fun stop() { stops.incrementAndGet() }
            }, null, null)
        })
        try {
            transcriber.start()
            assertNotNull(ready.poll(3, TimeUnit.SECONDS))
            callbacks[0](PCM_INPUT_ROUTE_CHANGED_CODE)
            callbacks[0](PCM_INPUT_ROUTE_CHANGED_CODE)
            assertEquals(VoskStreamingError.AUDIO_ROUTE_CHANGED, errors.poll(3, TimeUnit.SECONDS))
            assertTrue(errors.isEmpty())
            assertFalse(transcriber.isRunning())
            VoskStreamingWorker.process.submit {}.get(3, TimeUnit.SECONDS)
            assertEquals(1, stops.get())
            transcriber.start()
            assertNotNull(ready.poll(3, TimeUnit.SECONDS))
            callbacks[0](PCM_INPUT_ROUTE_CHANGED_CODE)
            assertTrue(transcriber.isRunning())
            assertTrue(errors.isEmpty())
        } finally {
            transcriber.close()
            loader.awaitTermination(3, TimeUnit.SECONDS)
            VoskStreamingWorker.process.submit {}.get(3, TimeUnit.SECONDS)
        }
        assertEquals(2, stops.get())
    }

    @Test fun routeChangeDuringStartDoesNotPublishReady() {
        val loader = Executors.newSingleThreadExecutor()
        val ready = AtomicInteger()
        val failed = CountDownLatch(1)
        val transcriber = VoskStreamingTranscriber(null, File("unused"), { false }, object : VoskStreamingListener {
            override fun onReady(runId: Long) { ready.incrementAndGet() }
            override fun onTranscript(runId: Long, transcript: VoskTranscript) = Unit
            override fun onError(runId: Long, error: VoskStreamingError) {
                assertEquals(VoskStreamingError.AUDIO_ROUTE_CHANGED, error)
                failed.countDown()
            }
        }, loader = loader, preparedRunFactory = { callback ->
            VoskPreparedRun(object : VoskPcmSource {
                override fun start() = callback(PCM_INPUT_ROUTE_CHANGED_CODE)
                override fun stop() = Unit
            }, null, null)
        })
        try {
            transcriber.start()
            assertTrue(failed.await(3, TimeUnit.SECONDS))
            assertEquals(0, ready.get())
            assertFalse(transcriber.isRunning())
        } finally {
            transcriber.close()
            loader.awaitTermination(3, TimeUnit.SECONDS)
            VoskStreamingWorker.process.submit {}.get(3, TimeUnit.SECONDS)
        }
    }
}
