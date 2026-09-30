package kr.co.hanium.dreamup.walksafe.voice

import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real controller/loader ownership; native Model and Recognizer are intentionally absent. */
class HandsFreeVoskLoadingIntegrationRegressionTest {
    @Test
    fun loadingTimeoutStopsActualTranscriberBeforeLatePreparedAudioCanStart() = withFixture { f ->
        f.startBlockedLoad()
        f.runtime.advanceBy(19_999L)
        assertTrue(f.failures.isEmpty())
        assertEquals(0, f.portStopCount)
        f.runtime.advanceBy(1L)
        assertEquals(listOf(modelLoadFailure()), f.failures)
        assertEquals(1, f.portStopCount)
        assertFalse(f.transcriber.isRunning())

        f.releaseOldLoadAndAwaitCleanup()
        assertEquals(0, f.oldAudio.starts.get())
        assertEquals(1, f.oldAudio.stops.get())
        assertTrue(f.readyRuns.isEmpty())
        assertTrue(f.streamErrors.isEmpty())
        assertTrue(f.commands.isEmpty())
        assertFalse(f.statuses.contains("voice_hands_free=waiting_wake_phrase"))
        assertEquals(0, f.sessionEnds)
        f.runtime.advanceBy(20_000L)
        assertEquals(listOf(modelLoadFailure()), f.failures)
        assertEquals(1, f.portStartCount)
    }

    @Test
    fun consentRevocationDuringLoadingStopsActualTranscriberAndDisposesLateAudio() = withFixture { f ->
        f.startBlockedLoad()
        f.disclosureAccepted = false
        f.runtime.advanceBy(249L)
        assertEquals(0, f.portStopCount)
        f.runtime.advanceBy(1L)
        assertEquals(1, f.portStopCount)
        assertEquals(1, f.sessionEnds)
        assertTrue(f.failures.isEmpty())
        assertFalse(f.transcriber.isRunning())

        f.releaseOldLoadAndAwaitCleanup()
        assertEquals(0, f.oldAudio.starts.get())
        assertEquals(1, f.oldAudio.stops.get())
        assertTrue(f.readyRuns.isEmpty())
        assertTrue(f.streamErrors.isEmpty())
        assertTrue(f.commands.isEmpty())
        assertTrue(f.statuses.contains("voice_hands_free=blocked eligibility_changed"))
        assertFalse(f.statuses.contains("voice_hands_free=waiting_wake_phrase"))
        f.runtime.advanceBy(20_000L)
        assertEquals(1, f.sessionEnds)
        assertTrue(f.failures.isEmpty())
        assertEquals(1, f.portStartCount)
    }

    @Test
    fun replacementRunOnSameTranscriberStartsOnlyAfterTimedOutLoadIsDisposed() = withFixture { f ->
        val originalTranscriber = f.transcriber
        f.startBlockedLoad()
        val oldRun = f.reservedRuns.single()
        f.runtime.advanceBy(20_000L)
        assertEquals(listOf(modelLoadFailure()), f.failures)
        assertTrue(f.controller.start())
        val newRun = f.reservedRuns.last()
        assertTrue(oldRun != newRun)
        assertTrue(originalTranscriber === f.transcriber)
        assertEquals(1, f.streamCreations)
        assertEquals(0, f.replacementAudio.starts.get())

        f.releaseOldLoadAndAwaitCleanup()
        assertTrue("replacement prepared run must become ready", f.readyDelivered.await(2, TimeUnit.SECONDS))
        f.awaitWorkerIdle()
        f.runtime.drain()
        assertEquals(0, f.oldAudio.starts.get())
        assertEquals(1, f.oldAudio.stops.get())
        assertEquals(1, f.replacementAudio.starts.get())
        assertEquals(0, f.replacementAudio.stops.get())
        assertEquals(listOf("old:stop", "replacement:start"), f.audioEvents.toList())
        assertEquals(listOf(newRun), f.readyRuns.toList())
        assertEquals(1, f.statuses.count { it == "voice_hands_free=waiting_wake_phrase" })
        assertTrue(f.transcriber.isRunning())
        f.runtime.advanceBy(20_000L)
        assertEquals("the successful run must cancel its loading watchdog", listOf(modelLoadFailure()), f.failures)
        assertEquals(1, f.portStopCount)
        assertEquals(2, f.portStartCount)
        assertTrue(f.streamErrors.isEmpty())
        assertTrue(f.commands.isEmpty())
    }

    private fun modelLoadFailure(): HandsFreeVoiceTerminalFailure =
        HandsFreeVoiceTerminalFailure.Transcriber(VoskStreamingError.MODEL_LOAD_FAILED)

    private fun withFixture(test: (Fixture) -> Unit) {
        val fixture = Fixture()
        try {
            test(fixture)
        } finally {
            fixture.close()
        }
    }

    private class Fixture {
        val runtime = ManualRuntime()
        val audioEvents = ConcurrentLinkedQueue<String>()
        val oldAudio = ObservedAudio("old", audioEvents)
        val replacementAudio = ObservedAudio("replacement", audioEvents)
        val readyRuns = ConcurrentLinkedQueue<Long>()
        val streamErrors = ConcurrentLinkedQueue<VoskStreamingError>()
        val readyDelivered = CountDownLatch(1)
        val reservedRuns = mutableListOf<Long>()
        val statuses = mutableListOf<String>()
        val failures = mutableListOf<HandsFreeVoiceTerminalFailure>()
        val commands = mutableListOf<String>()
        var disclosureAccepted = true
        var sessionEnds = 0
        var portStopCount = 0
        var portStartCount = 0
        var streamCreations = 0
        private val loader = Executors.newSingleThreadExecutor()
        private val oldLoadEntered = CountDownLatch(1)
        private val allowOldLoad = CountDownLatch(1)
        private val preparations = AtomicInteger()
        lateinit var transcriber: VoskStreamingTranscriber

        val controller = HandsFreeVoiceController(
            dependencies = HandsFreeVoiceDependencies(
                runtime = runtime,
                createStream = { listener, suppressed, inputGeneration ->
                    streamCreations += 1
                    transcriber = VoskStreamingTranscriber(
                        context = null,
                        modelDirectory = File("unused-test-model"),
                        isInputSuppressed = suppressed,
                        listener = object : VoskStreamingListener by listener {
                            override fun onReady(runId: Long) {
                                readyRuns.add(runId)
                                listener.onReady(runId)
                                readyDelivered.countDown()
                            }

                            override fun onError(runId: Long, error: VoskStreamingError) {
                                streamErrors.add(error)
                                listener.onError(runId, error)
                            }
                        },
                        loader = loader,
                        inputGeneration = inputGeneration,
                        preparedRunFactory = {
                            val audio = when (preparations.incrementAndGet()) {
                                1 -> {
                                    oldLoadEntered.countDown()
                                    check(allowOldLoad.await(5, TimeUnit.SECONDS))
                                    oldAudio
                                }
                                2 -> replacementAudio
                                else -> error("unexpected extra preparation")
                            }
                            VoskPreparedRun(audioSource = audio, recognizer = null, model = null)
                        },
                    )
                    object : HandsFreeStreamingPort {
                        override fun start(onRunReserved: (Long) -> Unit): Long? {
                            portStartCount += 1
                            return transcriber.start { runId ->
                                reservedRuns += runId
                                onRunReserved(runId)
                            }
                        }
                        override fun stop() { portStopCount += 1; transcriber.stop() }
                        override fun isRunning(): Boolean = transcriber.isRunning()
                        override fun close() { transcriber.close() }
                    }
                },
                playAcknowledgement = { error("no wake transcript was supplied") },
                cancelAcknowledgement = {},
                closeAcknowledgement = {},
            ),
            eligibility = {
                HandsFreeVoiceEligibility(WalkSessionState.ACTIVE, true, disclosureAccepted, true)
            },
            isAppSpeechActive = { false },
            onCommand = { text, _ -> commands += text },
            onStatus = { statuses += it },
            onTerminalFailure = { failures += it },
            onSessionEnded = { sessionEnds += 1 },
        )

        fun startBlockedLoad() {
            assertTrue(controller.start())
            assertTrue("the real loader must enter preparation", oldLoadEntered.await(2, TimeUnit.SECONDS))
            assertEquals(0, oldAudio.starts.get())
        }

        fun releaseOldLoadAndAwaitCleanup() {
            allowOldLoad.countDown()
            assertTrue("late prepared source must be cleaned", oldAudio.stopped.await(2, TimeUnit.SECONDS))
            awaitWorkerIdle()
            runtime.drain()
        }

        fun awaitWorkerIdle() {
            VoskStreamingWorker.process.submit {}.get(2, TimeUnit.SECONDS)
        }

        fun close() {
            allowOldLoad.countDown()
            controller.close()
            assertTrue(loader.awaitTermination(2, TimeUnit.SECONDS))
            awaitWorkerIdle()
        }
    }

    private class ObservedAudio(private val name: String, private val events: ConcurrentLinkedQueue<String>) : VoskPcmSource {
        val starts = AtomicInteger()
        val stops = AtomicInteger()
        val stopped = CountDownLatch(1)
        override fun start() { starts.incrementAndGet(); events.add("$name:start") }
        override fun stop() { stops.incrementAndGet(); events.add("$name:stop"); stopped.countDown() }
    }

    /** The worker may post concurrently; only the test thread runs the queued callbacks. */
    private class ManualRuntime : VoiceCallbackRuntime {
        private data class Scheduled(val task: Runnable, val dueMs: Long, val sequence: Long)
        private val owner = Thread.currentThread()
        private val lock = Any()
        private val pending = mutableListOf<Scheduled>()
        private var now = 0L
        private var sequence = 0L
        override fun checkMainThread(owner: String) { check(Thread.currentThread() === this.owner) }
        override fun nowMs(): Long = synchronized(lock) { now }
        override fun post(task: Runnable) { postDelayed(task, 0L) }
        override fun postDelayed(task: Runnable, delayMs: Long) {
            synchronized(lock) { pending += Scheduled(task, now + delayMs, sequence++) }
        }
        override fun removeCallbacks(task: Runnable) {
            synchronized(lock) { pending.removeAll { it.task === task } }
        }
        override fun removeAllCallbacks() { synchronized(lock) { pending.clear() } }
        fun drain() = advanceBy(0L)
        fun advanceBy(deltaMs: Long) {
            val target = nowMs() + deltaMs
            while (true) {
                val next = synchronized(lock) {
                    val first = pending.filter { it.dueMs <= target }
                        .minWithOrNull(compareBy<Scheduled> { it.dueMs }.thenBy { it.sequence })
                    if (first != null) {
                        pending.remove(first)
                        now = first.dueMs
                    } else {
                        now = target
                    }
                    first
                } ?: return
                next.task.run()
            }
        }
    }
}
