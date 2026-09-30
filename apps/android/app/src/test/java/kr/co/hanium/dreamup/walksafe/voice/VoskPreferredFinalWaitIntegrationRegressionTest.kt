package kr.co.hanium.dreamup.walksafe.voice

import android.os.Bundle
import android.speech.RecognitionListener
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VoskPreferredFinalWaitIntegrationRegressionTest {
    @Test
    fun endpointAtNineteenSecondsKeepsBothRealAdaptersAliveForFinalAtTwentyFiveSeconds() = withFixture { f ->
        val run = f.startRun()
        f.stream.ready(run)
        f.stream.transcript(run, "speaking", final = false)
        f.runtime.drain()
        assertEquals(listOf(20_000L, 20_000L), f.runtime.queuedDueMs())
        assertEquals(listOf("ready", "begin", "partial"), f.events.order)

        f.runtime.advanceBy(19_000L)
        f.stream.audioEnd(run)
        f.stream.audioEnd(run)
        f.runtime.drain()
        assertEquals(listOf(29_000L, 29_000L), f.runtime.queuedDueMs())
        assertEquals(1, f.events.order.count { it == "end" })

        f.runtime.advanceBy(1_000L)
        assertTrue(f.events.errors.isEmpty())
        assertEquals(listOf(29_000L, 29_000L), f.runtime.queuedDueMs())

        f.runtime.advanceBy(5_000L)
        f.stream.transcript(run, "destination", final = true)
        f.runtime.drain()
        assertEquals(listOf("ready", "begin", "partial", "end", "result"), f.events.order)
        assertTrue(f.events.errors.isEmpty())
        assertTrue(f.runtime.queuedDueMs().isEmpty())
        assertEquals(listOf(OfflineSpeechEngine.VOSK), f.annotatedEngines)
        assertEquals(1, f.stream.closeCalls)
        assertTrue(f.leaseIsFree())
        assertEquals(1, f.createdBackends)
    }

    @Test
    fun endpointWithNoPartialStillStartsFinalWaitAndDoesNotRepeatEndOnFinal() = withFixture { f ->
        val run = f.startRun()
        f.stream.ready(run)
        f.runtime.drain()
        f.stream.audioEnd(run)
        f.runtime.drain()
        assertEquals(listOf(10_000L, 10_000L), f.runtime.queuedDueMs())
        assertEquals(listOf("ready", "end"), f.events.order)

        f.runtime.advanceBy(5_000L)
        f.stream.transcript(run, "destination", final = true)
        f.runtime.drain()
        assertEquals(listOf("ready", "end", "begin", "result"), f.events.order)
        assertTrue(f.events.errors.isEmpty())
        assertEquals(listOf(OfflineSpeechEngine.VOSK), f.annotatedEngines)
        assertTrue(f.leaseIsFree())
    }

    @Test
    fun immediateFinalWithoutEndpointStillOrdersBeginEndAndResultOnce() = withFixture { f ->
        val run = f.startRun()
        f.stream.ready(run)
        f.stream.transcript(run, "destination", final = true)
        f.runtime.drain()

        assertEquals(listOf("ready", "begin", "end", "result"), f.events.order)
        assertTrue(f.events.errors.isEmpty())
        assertEquals(listOf(OfflineSpeechEngine.VOSK), f.annotatedEngines)
        assertTrue(f.leaseIsFree())
    }

    private fun withFixture(test: (Fixture) -> Unit) {
        val fixture = Fixture()
        try {
            test(fixture)
        } finally {
            fixture.adapter.destroy()
        }
    }

    private object Input : OfflineSpeechInput

    private class Fixture {
        val runtime = ManualRuntime()
        val events = Events()
        val annotatedEngines = mutableListOf<OfflineSpeechEngine>()
        private val lease = VoiceMicrophoneLease()
        lateinit var stream: FakeStream
            private set
        var createdBackends = 0
            private set
        private val platform = object : PreferredSpeechPlatform {
            override fun canQueryOnDeviceSupport(): Boolean = false
            override fun isOnDeviceRecognitionAvailable(): Boolean = false
            override fun hasMicrophonePermission(): Boolean = true
            override fun createPlatform(): PlatformSpeechBackend = error("platform must not be selected")
            override fun createVosk(directory: File): OneShotSpeechBackend {
                createdBackends += 1
                val recognizer = VoskSpeechRecognizer(
                    VoskSpeechDependencies(
                        runtime = runtime,
                        microphoneGranted = { true },
                        createStream = { listener -> FakeStream(listener).also { stream = it } },
                        readyBundle = { null },
                        transcriptResults = { null },
                    ),
                )
                return object : OneShotSpeechBackend {
                    override fun setRecognitionListener(listener: RecognitionListener) {
                        recognizer.setRecognitionListener(listener)
                    }

                    override fun startListening(input: OfflineSpeechInput) {
                        recognizer.startRequest()
                    }

                    override fun destroy() {
                        recognizer.destroy()
                    }
                }
            }
        }
        val adapter = PreferredOfflineSpeechRecognizer(
            modelDirectory = { File("fake-installed-vosk-model") },
            dependencies = PreferredSpeechDependencies(
                runtime = runtime,
                platform = platform,
                requests = PreferredOfflineSpeechRequest(lease),
                cooldown = PreferredPlatformCooldown(),
                emitDiagnostic = {},
                annotateResult = { _, engine -> annotatedEngines += engine; null },
            ),
        )

        init {
            adapter.setRecognitionListener(events)
        }

        fun startRun(): Long {
            adapter.startPreparedRequest({ Input })
            assertEquals(1, createdBackends)
            assertEquals(1, stream.startCalls)
            return stream.lastRunId
        }

        fun leaseIsFree(): Boolean {
            val owner = Any()
            val acquired = lease.acquire(owner)
            if (acquired) lease.release(owner)
            return acquired
        }
    }

    private class Events : RecognitionListener {
        val order = mutableListOf<String>()
        val errors = mutableListOf<Int>()
        override fun onReadyForSpeech(params: Bundle?) { order += "ready" }
        override fun onBeginningOfSpeech() { order += "begin" }
        override fun onPartialResults(partialResults: Bundle?) { order += "partial" }
        override fun onEndOfSpeech() { order += "end" }
        override fun onResults(results: Bundle?) { order += "result" }
        override fun onError(error: Int) { errors += error }
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private class FakeStream(private val listener: VoskStreamingListener) : VoskRequestStream {
        var lastRunId = 0L
            private set
        var startCalls = 0
            private set
        var closeCalls = 0
            private set

        override fun start(onRunReserved: (Long) -> Unit): Long? {
            startCalls += 1
            lastRunId += 1L
            onRunReserved(lastRunId)
            return lastRunId
        }

        override fun stop() = Unit
        override fun close() { closeCalls += 1 }
        fun ready(runId: Long) { listener.onReady(runId) }
        fun audioEnd(runId: Long) { listener.onAudioEnd(runId) }
        fun transcript(runId: Long, text: String, final: Boolean) {
            listener.onTranscript(runId, VoskTranscript(text = text, confidence = 0.9f, isFinal = final))
        }
    }

    private data class Scheduled(val task: Runnable, val dueMs: Long, val sequence: Long)

    private class ManualRuntime : VoiceCallbackRuntime {
        private var now = 0L
        private var sequence = 0L
        private val pending = mutableListOf<Scheduled>()

        override fun checkMainThread(owner: String) = Unit
        override fun nowMs(): Long = now
        override fun post(task: Runnable) { postDelayed(task, 0L) }
        override fun postDelayed(task: Runnable, delayMs: Long) {
            pending += Scheduled(task, now + delayMs, sequence++)
        }
        override fun removeCallbacks(task: Runnable) { pending.removeAll { it.task === task } }
        override fun removeAllCallbacks() { pending.clear() }
        fun queuedDueMs(): List<Long> = pending.map { it.dueMs }.sorted()
        fun drain() { advanceBy(0L) }

        fun advanceBy(deltaMs: Long) {
            val target = now + deltaMs
            while (true) {
                val next = pending.filter { it.dueMs <= target }
                    .minWithOrNull(compareBy<Scheduled> { it.dueMs }.thenBy { it.sequence })
                    ?: break
                pending.remove(next)
                now = next.dueMs
                next.task.run()
            }
            now = target
        }
    }
}
