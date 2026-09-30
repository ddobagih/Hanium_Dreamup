package kr.co.hanium.dreamup.walksafe.voice

import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferredOfflineSpeechStartFailureRegressionTest {
    @Test
    fun missingPlatformReadyRetiresOnceAndNextExplicitRequestStartsVosk() = withFixture { fixture ->
        val client = fixture.initial
        val platform = fixture.startPlatform(client)
        val watchdog = fixture.startWatchdog(client)
        assertFalse(fixture.leaseIsAvailable())
        fixture.scheduler.advanceBy(START_TIMEOUT_MS - 1L)
        assertTrue(client.events.errors.isEmpty())
        assertEquals(0, platform.cancelCalls)

        fixture.scheduler.advanceBy(1L)
        assertEquals(listOf(SpeechRecognizer.ERROR_CLIENT), client.events.errors)
        assertEquals(1, platform.cancelCalls)
        assertEquals(1, platform.calls.destroyCalls)
        assertEquals(listOf("cancel:true", "destroy:true"), fixture.platform.cleanupObservations)
        assertTrue(fixture.leaseIsAvailable())
        assertFalse(fixture.scheduler.isQueued(watchdog.task))
        assertTrue("a failed request must not secretly start a second recorder", fixture.platform.fallbacks.isEmpty())

        fixture.request(client)
        assertEquals(1, fixture.platform.platforms.size)
        assertEquals(1, fixture.platform.fallbacks.size)
        val fallback = fixture.platform.fallbacks.single()
        assertEquals(1, fallback.calls.inputs.size)
        assertEquals(listOf(SpeechRecognizer.ERROR_CLIENT), client.events.errors)
        fallback.calls.ready()
        assertEquals(1, client.events.readyCount)
    }

    @Test
    fun synchronousPlatformStartFailureUsesTheSameCooldownWithoutAutomaticRetry() = withFixture { fixture ->
        val client = fixture.initial
        fixture.platform.throwNextPlatformStart = true
        val platform = fixture.startPlatform(client)
        val watchdog = fixture.startWatchdog(client)

        assertEquals(listOf(SpeechRecognizer.ERROR_CLIENT), client.events.errors)
        assertEquals(1, platform.calls.inputs.size)
        assertEquals(1, platform.cancelCalls)
        assertEquals(1, platform.calls.destroyCalls)
        assertEquals(listOf("cancel:true", "destroy:true"), fixture.platform.cleanupObservations)
        assertTrue(fixture.leaseIsAvailable())
        assertFalse(fixture.scheduler.isQueued(watchdog.task))
        assertTrue(fixture.platform.fallbacks.isEmpty())

        fixture.scheduler.advanceBy(START_TIMEOUT_MS)
        assertEquals(listOf(SpeechRecognizer.ERROR_CLIENT), client.events.errors)
        assertEquals(1, fixture.platform.platforms.size)
        assertTrue(fixture.platform.fallbacks.isEmpty())
        fixture.request(client)
        assertEquals(1, fixture.platform.platforms.size)
        assertEquals(1, fixture.platform.fallbacks.single().calls.inputs.size)
    }

    @Test
    fun oldPlatformCallbacksAndWatchdogCannotRetireTheNewVoskRequest() = withFixture { fixture ->
        val client = fixture.initial
        val oldPlatform = fixture.startPlatform(client)
        val oldWatchdog = fixture.startWatchdog(client)
        fixture.scheduler.advanceBy(START_TIMEOUT_MS)
        fixture.request(client)
        val fallback = fixture.platform.fallbacks.single()
        val newWatchdog = fixture.startWatchdog(client)
        assertTrue(oldWatchdog.task !== newWatchdog.task)

        fixture.scheduler.advanceBy(SUPPORT_TIMEOUT_MS)
        oldPlatform.calls.ready()
        oldPlatform.calls.error(SpeechRecognizer.ERROR_CLIENT)
        // This is the actual prior request's Runnable, not a copied timeout predicate.
        oldWatchdog.task.run()
        assertEquals(listOf(SpeechRecognizer.ERROR_CLIENT), client.events.errors)
        assertEquals(0, client.events.readyCount)
        assertEquals(0, fallback.calls.destroyCalls)
        assertEquals(1, oldPlatform.cancelCalls)
        assertEquals(1, oldPlatform.calls.destroyCalls)
        assertTrue(fixture.scheduler.isQueued(newWatchdog.task))

        fallback.calls.ready()
        assertEquals("positive control: the current backend's ready is accepted", 1, client.events.readyCount)
        assertFalse(fixture.scheduler.isQueued(newWatchdog.task))
        assertEquals(listOf(SpeechRecognizer.ERROR_CLIENT), client.events.errors)
        assertEquals(1, fixture.platform.platforms.size)
        assertEquals(1, fixture.platform.fallbacks.size)
    }

    @Test
    fun timelyPlatformReadyReplacesTheStartWatchdogWithoutAFalseStartFailure() = withFixture { fixture ->
        val client = fixture.initial
        val platform = fixture.startPlatform(client)
        val startWatchdog = fixture.startWatchdog(client)
        fixture.scheduler.advanceBy(START_TIMEOUT_MS - 1L)
        platform.calls.ready()

        assertEquals(1, client.events.readyCount)
        assertTrue(client.events.errors.isEmpty())
        assertFalse(fixture.scheduler.isQueued(startWatchdog.task))
        val listening = fixture.scheduler.scheduled.last {
            it.owner === client.runtime && it.delayMs == LISTEN_TIMEOUT_MS
        }
        assertTrue(fixture.scheduler.isQueued(listening.task))
        fixture.scheduler.advanceBy(1L)
        assertTrue("the old start deadline must not become ERROR_CLIENT", client.events.errors.isEmpty())
        assertEquals(0, platform.cancelCalls)
        assertEquals(0, platform.calls.destroyCalls)
        assertFalse(fixture.leaseIsAvailable())
        assertTrue(fixture.platform.fallbacks.isEmpty())
    }

    @Test
    fun cooldownIsSharedAcrossAdaptersUntilButNotIncludingItsExpiry() = withFixture { fixture ->
        val first = fixture.initial
        val firstPlatform = fixture.startPlatform(first)
        fixture.scheduler.advanceBy(START_TIMEOUT_MS)
        assertEquals(listOf(SpeechRecognizer.ERROR_CLIENT), first.events.errors)
        first.adapter.destroy()

        fixture.scheduler.advanceBy(PLATFORM_RETRY_DELAY_MS - 1L)
        val beforeExpiry = fixture.newClient()
        fixture.request(beforeExpiry)
        assertEquals(1, fixture.platform.platforms.size)
        assertEquals(1, fixture.platform.fallbacks.size)
        assertEquals(1, fixture.platform.fallbacks.single().calls.inputs.size)
        assertEquals(1, firstPlatform.calls.destroyCalls)
        beforeExpiry.adapter.cancel()
        assertEquals(1, fixture.platform.fallbacks.single().calls.destroyCalls)

        fixture.scheduler.advanceBy(1L)
        val atExpiry = fixture.newClient()
        fixture.request(atExpiry)
        assertEquals(2, fixture.platform.platforms.size)
        assertEquals(1, fixture.platform.fallbacks.size)
        val nextPlatform = fixture.platform.platforms.last()
        assertEquals(1, nextPlatform.supportChecks)
        assertTrue("support must still precede actual platform selection", nextPlatform.calls.inputs.isEmpty())
        nextPlatform.support(listOf("KO_kr"))
        assertEquals(1, nextPlatform.calls.inputs.size)
        assertFalse(fixture.leaseIsAvailable())
        nextPlatform.calls.ready()
        assertEquals(1, atExpiry.events.readyCount)
        assertTrue(beforeExpiry.events.errors.isEmpty())
        assertTrue(atExpiry.events.errors.isEmpty())
    }

    @Test
    fun duplicateWhileSupportIsPendingKeepsTheOriginalProbeAndAllowsANewRequestAfterCancel() = withFixture { fixture ->
        val client = fixture.initial
        fixture.request(client)
        val platform = fixture.platform.platforms.single()
        val supportWatchdog = fixture.scheduler.scheduled.last {
            it.owner === client.runtime && it.delayMs == SUPPORT_TIMEOUT_MS
        }
        var duplicatePreparations = 0

        client.adapter.startPreparedRequest(
            prepareInput = {
                duplicatePreparations += 1
                FakeInput(-1)
            },
            preferPlatform = true,
        )

        assertEquals(0, duplicatePreparations)
        assertEquals(1, fixture.platform.platforms.size)
        assertTrue(fixture.platform.fallbacks.isEmpty())
        assertEquals(1, platform.supportChecks)
        assertTrue(platform.calls.inputs.isEmpty())
        assertEquals(0, platform.cancelCalls)
        assertEquals(0, platform.calls.destroyCalls)
        assertTrue(client.events.errors.isEmpty())
        assertTrue(fixture.scheduler.isQueued(supportWatchdog.task))
        assertSame(
            supportWatchdog,
            fixture.scheduler.scheduled.last {
                it.owner === client.runtime && it.delayMs == SUPPORT_TIMEOUT_MS
            },
        )

        platform.support(listOf("ko-KR"))
        assertEquals(1, platform.calls.inputs.size)
        assertSame(platform.supportInput, platform.calls.inputs.single())
        platform.calls.ready()
        assertEquals("the original support callback must still reach a live request", 1, client.events.readyCount)
        assertTrue(client.events.errors.isEmpty())
        assertFalse(fixture.scheduler.isQueued(supportWatchdog.task))

        client.adapter.cancel()
        assertEquals(1, platform.cancelCalls)
        assertEquals(1, platform.calls.destroyCalls)
        val next = fixture.startPlatform(client)
        next.calls.ready()
        assertEquals(2, fixture.platform.platforms.size)
        assertEquals(2, client.events.readyCount)
        assertTrue(client.events.errors.isEmpty())
    }

    @Test
    fun duplicateWhilePlatformIsListeningKeepsItsWatchdogAndCurrentCallbacks() = withFixture { fixture ->
        val client = fixture.initial
        val platform = fixture.startPlatform(client)
        platform.calls.ready()
        assertEquals(1, client.events.readyCount)
        val listeningWatchdog = fixture.scheduler.scheduled.last {
            it.owner === client.runtime && it.delayMs == LISTEN_TIMEOUT_MS
        }
        var duplicatePreparations = 0

        client.adapter.startPreparedRequest(
            prepareInput = {
                duplicatePreparations += 1
                FakeInput(-1)
            },
            preferPlatform = true,
        )

        assertEquals(0, duplicatePreparations)
        assertEquals(1, fixture.platform.platforms.size)
        assertTrue(fixture.platform.fallbacks.isEmpty())
        assertEquals(1, platform.calls.inputs.size)
        assertEquals(1, platform.supportChecks)
        assertEquals(0, platform.cancelCalls)
        assertEquals(0, platform.calls.destroyCalls)
        assertTrue(client.events.errors.isEmpty())
        assertTrue(fixture.scheduler.isQueued(listeningWatchdog.task))
        assertSame(
            listeningWatchdog,
            fixture.scheduler.scheduled.last {
                it.owner === client.runtime && it.delayMs == LISTEN_TIMEOUT_MS
            },
        )
        checkNotNull(platform.calls.listener).onBeginningOfSpeech()
        assertEquals("the current listener must remain accepted after the duplicate", 1, client.events.beginningCount)

        client.adapter.cancel()
        assertEquals(1, platform.cancelCalls)
        assertEquals(1, platform.calls.destroyCalls)
        assertFalse(fixture.scheduler.isQueued(listeningWatchdog.task))
        val next = fixture.startPlatform(client)
        next.calls.ready()
        assertEquals(2, fixture.platform.platforms.size)
        assertEquals(1, next.calls.inputs.size)
        assertEquals(2, client.events.readyCount)
        assertTrue(client.events.errors.isEmpty())
    }

    @Test
    fun duplicateWhileVoskIsListeningKeepsItsWatchdogAndCurrentCallbacks() = withFixture { fixture ->
        val client = fixture.initial
        client.adapter.startPreparedRequest(
            prepareInput = { FakeInput(1) },
            preferPlatform = false,
        )
        val fallback = fixture.platform.fallbacks.single()
        fallback.calls.ready()
        assertEquals(1, client.events.readyCount)
        val listeningWatchdog = fixture.scheduler.scheduled.last {
            it.owner === client.runtime && it.delayMs == LISTEN_TIMEOUT_MS
        }
        var duplicatePreparations = 0

        client.adapter.startPreparedRequest(
            prepareInput = {
                duplicatePreparations += 1
                FakeInput(-1)
            },
            preferPlatform = false,
        )

        assertEquals(0, duplicatePreparations)
        assertTrue(fixture.platform.platforms.isEmpty())
        assertEquals(1, fixture.platform.fallbacks.size)
        assertEquals(1, fallback.calls.inputs.size)
        assertEquals(0, fallback.calls.destroyCalls)
        assertTrue(client.events.errors.isEmpty())
        assertTrue(fixture.scheduler.isQueued(listeningWatchdog.task))
        assertSame(
            listeningWatchdog,
            fixture.scheduler.scheduled.last {
                it.owner === client.runtime && it.delayMs == LISTEN_TIMEOUT_MS
            },
        )
        checkNotNull(fallback.calls.listener).onBeginningOfSpeech()
        assertEquals("the selected Vosk callback must still belong to the live request", 1, client.events.beginningCount)

        client.adapter.cancel()
        assertEquals(1, fallback.calls.destroyCalls)
        assertFalse(fixture.scheduler.isQueued(listeningWatchdog.task))
        client.adapter.startPreparedRequest(
            prepareInput = { FakeInput(2) },
            preferPlatform = false,
        )
        val next = fixture.platform.fallbacks.last()
        next.calls.ready()
        assertTrue(fixture.platform.platforms.isEmpty())
        assertEquals(2, fixture.platform.fallbacks.size)
        assertEquals(1, next.calls.inputs.size)
        assertEquals(2, client.events.readyCount)
        assertTrue(client.events.errors.isEmpty())
    }

    private fun withFixture(test: (Fixture) -> Unit) {
        val fixture = Fixture()
        var failure: Throwable? = null
        try {
            test(fixture)
        } catch (caught: Throwable) {
            failure = caught
        }
        // Independently clean every real adapter even when the first assertion has failed.
        for (client in fixture.clients.asReversed()) {
            try {
                client.adapter.destroy()
            } catch (caught: Throwable) {
                val first = failure
                if (first == null) {
                    failure = caught
                } else if (first !== caught) {
                    runCatching { first.addSuppressed(caught) }
                }
            }
        }
        failure?.let { throw it }
    }

    private class Fixture {
        val scheduler = VirtualScheduler()
        val lease = VoiceMicrophoneLease()
        val cooldown = PreferredPlatformCooldown()
        val platform = FakePlatform { action ->
            val probe = Any()
            val acquired = lease.acquire(probe)
            if (acquired) lease.release(probe)
            "$action:" + !acquired
        }
        val clients = mutableListOf<Client>()
        private var nextInputId = 0
        val initial = newClient()

        fun newClient(): Client {
            val runtime = OwnedRuntime(scheduler)
            val events = Events()
            val adapter = PreferredOfflineSpeechRecognizer(
                modelDirectory = { File("fake-installed-vosk-model") },
                dependencies = PreferredSpeechDependencies(
                    runtime = runtime,
                    platform = platform,
                    requests = PreferredOfflineSpeechRequest(lease),
                    cooldown = cooldown,
                    emitDiagnostic = { _ -> },
                ),
            )
            val client = Client(adapter, runtime, events)
            clients += client
            adapter.setRecognitionListener(events)
            return client
        }

        fun request(client: Client) {
            client.adapter.startPreparedRequest(
                prepareInput = { FakeInput(++nextInputId) },
                preferPlatform = true,
                isRequestCurrent = { true },
            )
        }

        fun startPlatform(client: Client): FakePlatformBackend {
            val count = platform.platforms.size
            request(client)
            assertEquals(count + 1, platform.platforms.size)
            val backend = platform.platforms.last()
            assertEquals(1, backend.supportChecks)
            assertTrue(backend.calls.inputs.isEmpty())
            backend.support(listOf("ko-KR"))
            assertEquals(1, backend.calls.inputs.size)
            assertSame(backend.supportInput, backend.calls.inputs.single())
            return backend
        }

        fun startWatchdog(client: Client): Scheduled =
            scheduler.scheduled.last { it.owner === client.runtime && it.delayMs == START_TIMEOUT_MS }

        fun leaseIsAvailable(): Boolean {
            val owner = Any()
            val acquired = lease.acquire(owner)
            if (acquired) lease.release(owner)
            return acquired
        }
    }

    private data class Client(
        val adapter: PreferredOfflineSpeechRecognizer,
        val runtime: OwnedRuntime,
        val events: Events,
    )

    private data class FakeInput(val id: Int) : OfflineSpeechInput

    private class Events : RecognitionListener {
        var readyCount = 0
        var beginningCount = 0
        val errors = mutableListOf<Int>()

        override fun onReadyForSpeech(params: Bundle?) { readyCount += 1 }
        override fun onError(error: Int) { errors += error }
        override fun onBeginningOfSpeech() { beginningCount += 1 }
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onResults(results: Bundle?) = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    /** Records backend calls and emits callbacks; it does not choose an engine. */
    private class EngineCalls {
        val inputs = mutableListOf<OfflineSpeechInput>()
        var listener: RecognitionListener? = null
        var destroyCalls = 0

        fun ready() {
            checkNotNull(listener).onReadyForSpeech(null)
        }

        fun error(error: Int) {
            checkNotNull(listener).onError(error)
        }
    }

    private class FakePlatform(
        private val observeCleanup: (String) -> String,
    ) : PreferredSpeechPlatform {
        val platforms = mutableListOf<FakePlatformBackend>()
        val fallbacks = mutableListOf<FakeVoskBackend>()
        val cleanupObservations = mutableListOf<String>()
        var throwNextPlatformStart = false

        override fun canQueryOnDeviceSupport(): Boolean = true
        override fun isOnDeviceRecognitionAvailable(): Boolean = true
        override fun hasMicrophonePermission(): Boolean = true

        override fun createPlatform(): PlatformSpeechBackend {
            val failStart = throwNextPlatformStart
            throwNextPlatformStart = false
            return FakePlatformBackend(failStart) { action ->
                // Record observations outside assertions: production cleanup catches failures.
                cleanupObservations += observeCleanup(action)
            }.also { platforms += it }
        }

        override fun createVosk(directory: File): OneShotSpeechBackend =
            FakeVoskBackend().also { fallbacks += it }
    }

    private class FakePlatformBackend(
        private val failStart: Boolean,
        private val onCleanup: (String) -> Unit,
    ) : PlatformSpeechBackend {
        val calls = EngineCalls()
        var supportChecks = 0
        var supportInput: OfflineSpeechInput? = null
        var cancelCalls = 0
        private var supportCallback: ((() -> List<String>) -> Unit)? = null

        override fun checkRecognitionSupport(
            input: OfflineSpeechInput,
            onInstalledLanguages: (languages: () -> List<String>) -> Unit,
            onError: (Int) -> Unit,
        ) {
            supportChecks += 1
            supportInput = input
            supportCallback = onInstalledLanguages
        }

        fun support(languages: List<String>) {
            checkNotNull(supportCallback).invoke { languages }
        }

        override fun setRecognitionListener(listener: RecognitionListener) {
            calls.listener = listener
        }

        override fun startListening(input: OfflineSpeechInput) {
            calls.inputs += input
            if (failStart) throw IllegalStateException("platform start failed")
        }

        override fun cancel() {
            cancelCalls += 1
            onCleanup("cancel")
        }

        override fun destroy() {
            calls.destroyCalls += 1
            onCleanup("destroy")
        }
    }

    private class FakeVoskBackend : OneShotSpeechBackend {
        val calls = EngineCalls()

        override fun setRecognitionListener(listener: RecognitionListener) {
            calls.listener = listener
        }

        override fun startListening(input: OfflineSpeechInput) {
            calls.inputs += input
        }

        override fun destroy() {
            calls.destroyCalls += 1
        }
    }

    private data class Scheduled(
        val owner: OwnedRuntime,
        val task: Runnable,
        val delayMs: Long,
        val dueMs: Long,
        val sequence: Long,
    )

    /** Shared monotonic time, but cancellation remains scoped to each Handler owner. */
    private class VirtualScheduler {
        var nowMs = 0L
            private set
        private var sequence = 0L
        private val pending = mutableListOf<Scheduled>()
        val scheduled = mutableListOf<Scheduled>()

        fun post(owner: OwnedRuntime, task: Runnable, delayMs: Long) {
            require(delayMs >= 0L)
            val entry = Scheduled(owner, task, delayMs, nowMs + delayMs, sequence++)
            scheduled += entry
            pending += entry
        }

        fun remove(owner: OwnedRuntime, task: Runnable) {
            pending.removeAll { it.owner === owner && it.task === task }
        }

        fun removeAll(owner: OwnedRuntime) {
            pending.removeAll { it.owner === owner }
        }

        fun isQueued(task: Runnable): Boolean = pending.any { it.task === task }

        fun advanceBy(deltaMs: Long) {
            require(deltaMs >= 0L)
            val target = nowMs + deltaMs
            while (true) {
                val next = pending
                    .filter { it.dueMs <= target }
                    .minWithOrNull(compareBy<Scheduled> { it.dueMs }.thenBy { it.sequence })
                    ?: break
                pending.remove(next)
                nowMs = next.dueMs
                next.task.run()
            }
            nowMs = target
        }
    }

    private class OwnedRuntime(
        private val scheduler: VirtualScheduler,
    ) : VoiceCallbackRuntime {
        override fun checkMainThread(owner: String) = Unit
        override fun nowMs(): Long = scheduler.nowMs
        override fun post(task: Runnable) { scheduler.post(this, task, 0L) }
        override fun postDelayed(task: Runnable, delayMs: Long) { scheduler.post(this, task, delayMs) }
        override fun removeCallbacks(task: Runnable) { scheduler.remove(this, task) }
        override fun removeAllCallbacks() { scheduler.removeAll(this) }
    }

    private companion object {
        const val SUPPORT_TIMEOUT_MS = 3_000L
        const val START_TIMEOUT_MS = 20_000L
        const val LISTEN_TIMEOUT_MS = 10_000L
        const val PLATFORM_RETRY_DELAY_MS = 30_000L
    }
}
