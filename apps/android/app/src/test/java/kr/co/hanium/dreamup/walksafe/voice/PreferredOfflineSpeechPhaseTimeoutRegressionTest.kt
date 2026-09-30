package kr.co.hanium.dreamup.walksafe.voice

import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PreferredOfflineSpeechPhaseTimeoutRegressionTest {
    @Test
    fun noSpeechKeepsTheReadyTenSecondBudgetAndDoesNotTriggerCooldownOrRetry() = withFixture { f ->
        val backend = f.startPlatform()
        backend.ready()
        f.runtime.advanceBy(9_999L)
        assertTrue(f.events.errors.isEmpty())
        assertFalse(f.leaseIsFree())

        f.runtime.advanceBy(1L)

        assertEquals(listOf(SpeechRecognizer.ERROR_SPEECH_TIMEOUT), f.events.errors)
        assertEquals(1, backend.cancelCalls)
        assertEquals(1, backend.destroyCalls)
        assertEquals(listOf(true, true), f.platform.cleanupHeldLease)
        assertTrue(f.leaseIsFree())
        assertEquals(0L, f.cooldown.retryAfterMs)
        assertEquals(1, f.platform.platforms.size)
        assertTrue(f.platform.fallbacks.isEmpty())
        backend.results()
        assertTrue(f.annotatedEngines.isEmpty())

        val next = f.startPlatform()
        next.ready()
        assertEquals(2, f.events.readyCount)
        assertEquals(2, f.platform.platforms.size)
    }

    @Test
    fun firstBeginningReplacesSpeechWaitOnceAndBoundsALongUtterance() = withFixture { f ->
        val backend = f.startPlatform()
        backend.ready()
        val waiting = f.runtime.lastScheduled()
        f.runtime.advanceBy(9_999L)
        backend.begin()
        val speaking = f.runtime.lastScheduled()
        assertEquals(29_999L, speaking.dueMs)
        assertFalse(f.runtime.isQueued(waiting.task))
        waiting.task.run()
        assertTrue(f.runtime.isQueued(speaking.task))

        backend.ready()
        backend.begin()
        backend.partial()
        assertSame(speaking, f.runtime.lastScheduled())
        assertEquals(1, f.events.readyCount)
        assertEquals(2, f.events.beginningCount)
        f.runtime.advanceBy(1L)
        assertTrue(f.events.errors.isEmpty())
        f.runtime.advanceBy(19_998L)
        backend.begin()
        assertSame(speaking, f.runtime.lastScheduled())
        assertTrue(f.events.errors.isEmpty())

        f.runtime.advanceBy(1L)
        speaking.task.run()

        assertEquals(listOf(SpeechRecognizer.ERROR_SPEECH_TIMEOUT), f.events.errors)
        assertEquals(1, backend.cancelCalls)
        assertEquals(1, backend.destroyCalls)
        assertEquals(1, f.platform.platforms.size)
        assertTrue(f.platform.fallbacks.isEmpty())
        assertEquals(0L, f.cooldown.retryAfterMs)
    }

    @Test
    fun lateEndStartsFinalWaitAndALateFinalRetiresBeforeAnnotation() = withFixture { f ->
        val backend = f.startPlatform()
        backend.ready()
        f.runtime.advanceBy(9_000L)
        backend.begin()
        val speaking = f.runtime.lastScheduled()
        f.runtime.advanceBy(19_999L)
        backend.end()
        val waitingFinal = f.runtime.lastScheduled()
        assertEquals(38_999L, waitingFinal.dueMs)
        speaking.task.run()
        assertTrue(f.runtime.isQueued(waitingFinal.task))
        assertTrue(f.events.errors.isEmpty())

        f.runtime.advanceBy(9_999L)
        backend.results()

        assertEquals(1, f.events.resultCount)
        assertEquals(1, f.events.endCount)
        assertEquals(listOf(OfflineSpeechEngine.PLATFORM), f.annotatedEngines)
        assertEquals(listOf(true), f.annotationLeaseFree)
        assertEquals(1, backend.cancelCalls)
        assertEquals(1, backend.destroyCalls)
        assertTrue(f.leaseIsFree())
        assertFalse(f.runtime.isQueued(waitingFinal.task))
        f.runtime.advanceBy(1L)
        waitingFinal.task.run()
        backend.results()
        assertEquals(1, f.events.resultCount)
        assertEquals(1, f.annotatedEngines.size)
        assertTrue(f.events.errors.isEmpty())
        assertEquals(1, f.platform.platforms.size)
    }

    @Test
    fun repeatedAndBackwardCallbacksCannotExtendFinalWaitOrReviveALateResult() = withFixture { f ->
        val backend = f.startPlatform()
        backend.ready()
        f.runtime.advanceBy(100L)
        backend.begin()
        val speaking = f.runtime.lastScheduled()
        f.runtime.advanceBy(100L)
        backend.end()
        val waitingFinal = f.runtime.lastScheduled()
        f.runtime.advanceBy(9_999L)
        backend.end()
        backend.begin()
        backend.ready()
        backend.partial()
        assertSame(waitingFinal, f.runtime.lastScheduled())
        assertEquals(2, f.events.endCount)
        assertEquals(2, f.events.beginningCount)
        assertEquals(1, f.events.readyCount)
        speaking.task.run()
        assertTrue(f.runtime.isQueued(waitingFinal.task))

        f.runtime.advanceBy(1L)
        backend.results()
        waitingFinal.task.run()

        assertEquals(listOf(SpeechRecognizer.ERROR_SPEECH_TIMEOUT), f.events.errors)
        assertEquals(0, f.events.resultCount)
        assertTrue(f.annotatedEngines.isEmpty())
        assertEquals(1, backend.cancelCalls)
        assertEquals(1, backend.destroyCalls)
        assertEquals(1, f.platform.platforms.size)
        assertEquals(0L, f.cooldown.retryAfterMs)
    }

    @Test
    fun beginningAndEndReentrancyCannotOverwriteTheReplacementRequestTimers() {
        for (useEnd in listOf(false, true)) withFixture { f ->
            val old = f.startPlatform()
            old.ready()
            val oldWaiting = f.runtime.lastScheduled()
            var phaseBeforeCallback: Scheduled? = null
            var replacementSupport: Scheduled? = null
            val replace = {
                phaseBeforeCallback = f.runtime.lastScheduled()
                f.adapter.cancel()
                f.adapter.startPreparedRequest({ Input }, preferPlatform = true)
                replacementSupport = f.runtime.lastScheduled()
            }
            if (useEnd) f.events.endAction = replace else f.events.beginAction = replace

            if (useEnd) old.end() else old.begin()

            f.events.beginAction = {}
            f.events.endAction = {}
            val oldPhase = checkNotNull(phaseBeforeCallback)
            val support = checkNotNull(replacementSupport)
            assertNotSame(oldWaiting, oldPhase)
            assertEquals(if (useEnd) 10_000L else 20_000L, oldPhase.delayMs)
            assertEquals(3_000L, support.delayMs)
            assertSame(support, f.runtime.lastScheduled())
            assertTrue(f.runtime.isQueued(support.task))
            assertEquals(2, f.platform.platforms.size)
            assertEquals(1, old.cancelCalls)
            assertEquals(1, old.destroyCalls)
            oldWaiting.task.run()
            oldPhase.task.run()
            old.ready()
            old.begin()
            old.end()
            assertSame(support, f.runtime.lastScheduled())
            assertTrue(f.runtime.isQueued(support.task))

            val replacement = f.platform.platforms.last()
            replacement.support()
            val start = f.runtime.lastScheduled()
            assertEquals(20_000L, start.delayMs)
            support.task.run()
            oldPhase.task.run()
            assertTrue(f.runtime.isQueued(start.task))
            replacement.ready()
            val listening = f.runtime.lastScheduled()
            start.task.run()
            assertTrue(f.runtime.isQueued(listening.task))
            assertEquals(2, f.events.readyCount)
            assertTrue(f.events.errors.isEmpty())
            assertEquals(0, replacement.cancelCalls)
            assertEquals(0, replacement.destroyCalls)
            assertFalse(f.leaseIsFree())
        }
    }

    @Test
    fun earlySpeechKeepsStartupWatchdogAndReadyUsesOnlyTheOriginalRemainingBudget() {
        for (mode in listOf("speaking", "end", "begin_end", "expired_end")) withFixture { f ->
            val backend = f.startPlatform()
            val startup = f.runtime.lastScheduled()
            assertEquals(20_000L, startup.delayMs)
            f.runtime.advanceBy(1_000L)
            if (mode == "speaking" || mode == "begin_end") backend.begin() else backend.end()
            val deadline = when (mode) {
                "speaking" -> 21_000L
                "begin_end" -> {
                    f.runtime.advanceBy(1_000L)
                    backend.end()
                    12_000L
                }
                else -> 11_000L
            }
            f.runtime.advanceBy(3_000L - f.runtime.nowMs())
            backend.begin()
            if (mode != "speaking") backend.end()
            assertSame(startup, f.runtime.lastScheduled())
            assertTrue(f.runtime.isQueued(startup.task))
            assertTrue(f.events.errors.isEmpty())
            val readyAt = if (mode == "expired_end") 12_000L else 5_000L
            f.runtime.advanceBy(readyAt - f.runtime.nowMs())

            backend.ready()

            val phase = f.runtime.lastScheduled()
            val remaining = (deadline - readyAt).coerceAtLeast(0L)
            assertEquals(remaining, phase.delayMs)
            assertFalse(f.runtime.isQueued(startup.task))
            assertTrue(f.runtime.isQueued(phase.task))
            startup.task.run()
            backend.ready()
            assertSame(phase, f.runtime.lastScheduled())
            assertEquals(1, f.events.readyCount)
            assertTrue(f.events.errors.isEmpty())
            if (remaining > 0L) {
                f.runtime.advanceBy(remaining - 1L)
                assertTrue(f.events.errors.isEmpty())
                f.runtime.advanceBy(1L)
            } else {
                f.runtime.drain()
            }
            assertEquals(listOf(SpeechRecognizer.ERROR_SPEECH_TIMEOUT), f.events.errors)
            assertEquals(1, backend.cancelCalls)
            assertEquals(1, backend.destroyCalls)
            assertEquals(1, f.platform.platforms.size)
            assertEquals(0L, f.cooldown.retryAfterMs)
        }
    }

    @Test
    fun beginningPredicateCannotStealTheReplacementSupportWatchdog() {
        assertPredicateReplacementKeepsSupportWatchdog(useEnd = false)
    }

    @Test
    fun endPredicateCannotStealTheReplacementSupportWatchdog() {
        assertPredicateReplacementKeepsSupportWatchdog(useEnd = true)
    }

    @Test
    fun currentRequestFalseAndThrowPredicatesStillCancelOnlyTheirOwnRequest() {
        for (useEnd in listOf(false, true)) {
            for (outcome in listOf("false", "throw")) withFixture { f ->
                var reject = false
                var predicateCalls = 0
                f.adapter.startPreparedRequest(
                    prepareInput = { Input },
                    preferPlatform = true,
                    isRequestCurrent = {
                        predicateCalls += 1
                        if (!reject) true
                        else if (outcome == "false") false
                        else throw IllegalStateException("current predicate failed")
                    },
                )
                val old = f.platform.platforms.single()
                old.support()
                old.ready()
                val waiting = f.runtime.lastScheduled()
                val callsBefore = predicateCalls
                reject = true

                if (useEnd) old.end() else old.begin()

                assertEquals(callsBefore + 1, predicateCalls)
                assertEquals(0, f.events.beginningCount)
                assertEquals(0, f.events.endCount)
                assertFalse(f.runtime.isQueued(waiting.task))
                assertEquals(1, old.cancelCalls)
                assertEquals(1, old.destroyCalls)
                assertTrue(f.leaseIsFree())
                assertTrue(f.events.errors.isEmpty())
                waiting.task.run()
                f.runtime.advanceBy(10_000L)
                assertEquals(callsBefore + 1, predicateCalls)
                assertEquals(1, f.platform.platforms.size)
                assertTrue(f.platform.fallbacks.isEmpty())
                assertEquals(1, old.cancelCalls)
                assertEquals(1, old.destroyCalls)

                val next = f.startPlatform()
                next.ready()
                assertEquals(2, f.events.readyCount)
                assertEquals(2, f.platform.platforms.size)
                assertTrue(f.events.errors.isEmpty())
            }
        }
    }

    private fun assertPredicateReplacementKeepsSupportWatchdog(useEnd: Boolean) {
        for (outcome in listOf("true", "false", "throw")) withFixture { f ->
            var replaceOnNextPredicate = false
            var predicateCalls = 0
            var replacements = 0
            var replacementSupport: Scheduled? = null
            f.adapter.startPreparedRequest(
                prepareInput = { Input },
                preferPlatform = true,
                isRequestCurrent = {
                    predicateCalls += 1
                    if (!replaceOnNextPredicate) {
                        true
                    } else {
                        replaceOnNextPredicate = false
                        replacements += 1
                        f.adapter.cancel()
                        f.adapter.startPreparedRequest({ Input }, preferPlatform = true)
                        replacementSupport = f.runtime.lastScheduled()
                        when (outcome) {
                            "true" -> true
                            "false" -> false
                            else -> throw IllegalStateException("replaced predicate failed")
                        }
                    }
                },
            )
            val old = f.platform.platforms.single()
            val oldSupport = f.runtime.lastScheduled()
            old.support()
            val oldStartup = f.runtime.lastScheduled()
            old.ready()
            val oldWaiting = f.runtime.lastScheduled()
            val callsBefore = predicateCalls
            replaceOnNextPredicate = true

            if (useEnd) old.end() else old.begin()

            val support = checkNotNull(replacementSupport)
            val replacement = f.platform.platforms.last()
            assertEquals(1, replacements)
            assertEquals(callsBefore + 1, predicateCalls)
            assertEquals(2, f.platform.platforms.size)
            assertNotSame(old, replacement)
            assertEquals(3_000L, support.delayMs)
            assertSame(support, f.runtime.lastScheduled())
            assertTrue(f.runtime.isQueued(support.task))
            assertFalse(f.runtime.isQueued(oldWaiting.task))
            assertEquals(0, f.events.beginningCount)
            assertEquals(0, f.events.endCount)
            assertEquals(1, f.events.readyCount)
            assertEquals(1, old.cancelCalls)
            assertEquals(1, old.destroyCalls)
            assertEquals(0, replacement.startCalls)
            assertEquals(0, replacement.cancelCalls)
            assertEquals(0, replacement.destroyCalls)
            assertTrue(f.events.errors.isEmpty())

            for (previous in listOf(oldSupport, oldStartup, oldWaiting)) previous.task.run()
            assertSame(support, f.runtime.lastScheduled())
            assertTrue(f.runtime.isQueued(support.task))
            assertEquals(callsBefore + 1, predicateCalls)
            f.runtime.advanceBy(2_999L)
            assertTrue(f.runtime.isQueued(support.task))
            assertTrue(f.platform.fallbacks.isEmpty())
            assertEquals(0, replacement.cancelCalls)
            assertEquals(0, replacement.destroyCalls)
            assertTrue(f.events.errors.isEmpty())

            // No support callback: the original B watchdog must still perform selection.
            f.runtime.advanceBy(1L)

            assertFalse(f.runtime.isQueued(support.task))
            assertEquals(1, replacement.cancelCalls)
            assertEquals(1, replacement.destroyCalls)
            assertEquals(1, f.platform.fallbacks.size)
            val fallback = f.platform.fallbacks.single()
            assertEquals(1, fallback.startCalls)
            fallback.ready()
            val listening = f.runtime.lastScheduled()
            assertEquals(10_000L, listening.delayMs)
            assertEquals(2, f.events.readyCount)
            support.task.run()
            oldWaiting.task.run()
            assertTrue(f.runtime.isQueued(listening.task))
            assertEquals(0, fallback.destroyCalls)
            assertTrue(f.events.errors.isEmpty())
            assertEquals(2, f.platform.platforms.size)
            assertEquals(1, f.platform.fallbacks.size)
            assertEquals(0L, f.cooldown.retryAfterMs)
        }
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
            if (first == null) failure = caught
            else if (first !== caught) runCatching { first.addSuppressed(caught) }
        }
        failure?.let { throw it }
        assertTrue(fixture.leaseIsFree())
        assertEquals(1, fixture.runtime.removeAllCalls)
    }

    private object Input : OfflineSpeechInput

    private class Fixture {
        val runtime = ManualRuntime()
        val lease = VoiceMicrophoneLease()
        val cooldown = PreferredPlatformCooldown()
        val events = Events()
        val annotatedEngines = mutableListOf<OfflineSpeechEngine>()
        val annotationLeaseFree = mutableListOf<Boolean>()
        val platform = FakePlatform { !leaseIsFree() }
        val adapter = PreferredOfflineSpeechRecognizer(
            modelDirectory = { File("fake-installed-vosk-model") },
            dependencies = PreferredSpeechDependencies(
                runtime = runtime,
                platform = platform,
                requests = PreferredOfflineSpeechRequest(lease),
                cooldown = cooldown,
                emitDiagnostic = {},
                annotateResult = { _, engine ->
                    annotatedEngines += engine
                    annotationLeaseFree += leaseIsFree()
                    null
                },
            ),
        )

        init {
            adapter.setRecognitionListener(events)
        }

        fun startPlatform(): FakeBackend {
            val count = platform.platforms.size
            adapter.startPreparedRequest({ Input }, preferPlatform = true)
            assertEquals(count + 1, platform.platforms.size)
            return platform.platforms.last().also { it.support() }
        }

        fun leaseIsFree(): Boolean {
            val owner = Any()
            val acquired = lease.acquire(owner)
            if (acquired) lease.release(owner)
            return acquired
        }
    }

    private class Events : RecognitionListener {
        var readyCount = 0
        var beginningCount = 0
        var endCount = 0
        var resultCount = 0
        val errors = mutableListOf<Int>()
        var beginAction: () -> Unit = {}
        var endAction: () -> Unit = {}

        override fun onReadyForSpeech(params: Bundle?) { readyCount += 1 }
        override fun onBeginningOfSpeech() { beginningCount += 1; beginAction() }
        override fun onEndOfSpeech() { endCount += 1; endAction() }
        override fun onResults(results: Bundle?) { resultCount += 1 }
        override fun onError(error: Int) { errors += error }
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onPartialResults(partialResults: Bundle?) = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    /** Supplies OS events only. Selection and request ownership stay in production. */
    private class FakePlatform(private val isLeaseHeld: () -> Boolean) : PreferredSpeechPlatform {
        val platforms = mutableListOf<FakeBackend>()
        val fallbacks = mutableListOf<FakeBackend>()
        val cleanupHeldLease = mutableListOf<Boolean>()
        override fun canQueryOnDeviceSupport(): Boolean = true
        override fun isOnDeviceRecognitionAvailable(): Boolean = true
        override fun hasMicrophonePermission(): Boolean = true
        override fun createPlatform(): PlatformSpeechBackend =
            FakeBackend { cleanupHeldLease += isLeaseHeld() }.also { platforms += it }
        override fun createVosk(directory: File): OneShotSpeechBackend =
            FakeBackend {}.also { fallbacks += it }
    }

    private class FakeBackend(private val cleanup: () -> Unit) : PlatformSpeechBackend {
        private var callback: RecognitionListener? = null
        private var supportCallback: ((() -> List<String>) -> Unit)? = null
        var startCalls = 0
        var cancelCalls = 0
        var destroyCalls = 0

        override fun checkRecognitionSupport(
            input: OfflineSpeechInput,
            onInstalledLanguages: (languages: () -> List<String>) -> Unit,
            onError: (Int) -> Unit,
        ) {
            supportCallback = onInstalledLanguages
        }
        override fun setRecognitionListener(listener: RecognitionListener) { callback = listener }
        override fun startListening(input: OfflineSpeechInput) { startCalls += 1 }
        override fun cancel() { cancelCalls += 1; cleanup() }
        override fun destroy() { destroyCalls += 1; cleanup() }
        fun support() { checkNotNull(supportCallback).invoke { listOf("ko-KR") } }
        fun ready() { checkNotNull(callback).onReadyForSpeech(null) }
        fun begin() { checkNotNull(callback).onBeginningOfSpeech() }
        fun end() { checkNotNull(callback).onEndOfSpeech() }
        fun results() { checkNotNull(callback).onResults(null) }
        fun partial() { checkNotNull(callback).onPartialResults(null) }
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
        override fun post(task: Runnable) { postDelayed(task, 0L) }
        override fun postDelayed(task: Runnable, delayMs: Long) {
            require(delayMs >= 0L)
            val entry = Scheduled(task, delayMs, currentMs + delayMs, sequence++)
            scheduled += entry
            pending += entry
        }
        override fun removeCallbacks(task: Runnable) { pending.removeAll { it.task === task } }
        override fun removeAllCallbacks() { removeAllCalls += 1; pending.clear() }
        fun lastScheduled(): Scheduled = scheduled.last()
        fun isQueued(task: Runnable): Boolean = pending.any { it.task === task }
        fun drain() { advanceBy(0L) }

        fun advanceBy(deltaMs: Long) {
            require(deltaMs >= 0L)
            val target = currentMs + deltaMs
            while (true) {
                val next = pending.filter { it.dueMs <= target }
                    .minWithOrNull(compareBy<Scheduled> { it.dueMs }.thenBy { it.sequence })
                    ?: break
                pending.remove(next)
                currentMs = next.dueMs
                next.task.run()
            }
            currentMs = target
        }
    }
}
