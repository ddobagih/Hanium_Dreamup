package kr.co.hanium.dreamup.walksafe.voice

import kr.co.hanium.dreamup.walksafe.session.WalkSessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandsFreeVoiceEligibilityOwnershipRegressionTest {
    @Test
    fun replacedEligibilityPollCannotEndOrRescheduleIntoTheNewRun() {
        EligibilityReturn.entries.forEach { result ->
            withFixture { fixture ->
                val oldRun = fixture.startRun()
                val oldWatchdog = checkNotNull(fixture.latestTask(MODEL_LOAD_TIMEOUT_MS))
                val oldPoll = checkNotNull(fixture.latestTask(ELIGIBILITY_POLL_MS))
                var injectedCalls = 0
                var replacementAccepted: Boolean? = null
                var replacementRun: Long? = null
                var replacementWatchdog: Scheduled? = null
                var replacementPoll: Scheduled? = null

                fixture.nextEligibility = {
                    // Observe the reentrant replacement here; assert outside production runCatching.
                    injectedCalls += 1
                    fixture.controller.stop()
                    replacementAccepted = fixture.controller.start()
                    replacementRun = fixture.stream.lastReservedRunId
                    replacementWatchdog = fixture.latestTask(MODEL_LOAD_TIMEOUT_MS)
                    replacementPoll = fixture.latestTask(ELIGIBILITY_POLL_MS)
                    suppliedEligibility(result)
                }

                fixture.runtime.advanceTo(oldPoll.dueMs - 1L)
                assertEquals(0, injectedCalls)
                assertTrue(fixture.runtime.isQueued(oldPoll.task))
                assertTrue(fixture.runtime.isQueued(oldWatchdog.task))

                fixture.runtime.advanceTo(oldPoll.dueMs)
                assertEquals(result.name, 1, injectedCalls)
                assertEquals(result.name, true, replacementAccepted)
                assertTrue(replacementRun != null && replacementRun != oldRun)
                val watchdog = checkNotNull(replacementWatchdog)
                val poll = checkNotNull(replacementPoll)
                assertTrue(watchdog.task !== oldWatchdog.task)
                assertTrue(poll.task !== oldPoll.task)
                assertEquals(oldPoll.dueMs + MODEL_LOAD_TIMEOUT_MS, watchdog.dueMs)
                assertEquals(oldPoll.dueMs + ELIGIBILITY_POLL_MS, poll.dueMs)
                assertFalse(fixture.runtime.isQueued(oldWatchdog.task))
                assertFalse(fixture.runtime.isQueued(oldPoll.task))
                assertTrue(fixture.runtime.isQueued(watchdog.task))
                assertTrue(fixture.runtime.isQueued(poll.task))
                assertEquals(1, fixture.runtime.scheduled.count { it.task === oldPoll.task })
                assertEquals(1, fixture.streamCreations)
                assertEquals(2, fixture.stream.startCalls)
                assertEquals(1, fixture.stream.stopCalls)
                assertEquals(0, fixture.sessionEnds)
                assertFalse(fixture.statuses.contains("voice_hands_free=blocked eligibility_changed"))
                assertTrue(fixture.failures.isEmpty())

                val registrations = fixture.runtime.scheduled.size
                oldPoll.task.run()
                assertEquals(registrations, fixture.runtime.scheduled.size)
                assertEquals(1, injectedCalls)
                assertTrue(fixture.runtime.isQueued(watchdog.task))
                assertTrue(fixture.runtime.isQueued(poll.task))
                assertEquals(1, fixture.stream.stopCalls)
                assertEquals(0, fixture.sessionEnds)

                // No ready callback for B: its original production watchdog must still fire.
                fixture.runtime.advanceTo(watchdog.dueMs - 1L)
                assertTrue(fixture.failures.isEmpty())
                assertTrue(fixture.runtime.isQueued(watchdog.task))
                assertEquals(1, fixture.stream.stopCalls)
                assertEquals(0, fixture.sessionEnds)

                fixture.runtime.advanceTo(watchdog.dueMs)
                assertEquals(listOf(modelLoadFailure()), fixture.failures)
                assertEquals(1, fixture.statuses.count { it == "voice_hands_free=error_model_load_failed" })
                assertEquals(2, fixture.stream.stopCalls)
                assertEquals(2, fixture.stream.startCalls)
                assertEquals(0, fixture.sessionEnds)
                assertEquals(0, fixture.waitingWakeCount())
                assertEquals(0, fixture.acknowledgementPlays)
                assertTrue(fixture.commands.isEmpty())
                assertFalse(fixture.runtime.isQueued(watchdog.task))
                assertEquals(1, fixture.runtime.scheduled.count { it.task === oldPoll.task })
            }
        }
    }

    @Test
    fun currentEligibilityDenialAndExceptionStillEndOnlyThatSession() {
        listOf(EligibilityReturn.DENIED, EligibilityReturn.THROWS).forEach { result ->
            withFixture { fixture ->
                val oldRun = fixture.startRun()
                val oldWatchdog = checkNotNull(fixture.latestTask(MODEL_LOAD_TIMEOUT_MS))
                val oldPoll = checkNotNull(fixture.latestTask(ELIGIBILITY_POLL_MS))
                var injectedCalls = 0
                fixture.nextEligibility = {
                    injectedCalls += 1
                    suppliedEligibility(result)
                }

                fixture.runtime.advanceTo(oldPoll.dueMs - 1L)
                assertEquals(0, injectedCalls)
                assertEquals(0, fixture.stream.stopCalls)
                assertEquals(0, fixture.sessionEnds)

                fixture.runtime.advanceTo(oldPoll.dueMs)
                assertEquals(result.name, 1, injectedCalls)
                assertEquals(1, fixture.sessionEnds)
                assertEquals(1, fixture.stream.stopCalls)
                assertEquals(1, fixture.stream.startCalls)
                assertEquals(1, fixture.statuses.count { it == "voice_hands_free=blocked eligibility_changed" })
                assertTrue(fixture.failures.isEmpty())
                assertFalse(fixture.runtime.isQueued(oldWatchdog.task))
                assertFalse(fixture.runtime.isQueued(oldPoll.task))

                val newRun = fixture.startRun()
                val newWatchdog = checkNotNull(fixture.latestTask(MODEL_LOAD_TIMEOUT_MS))
                val newPoll = checkNotNull(fixture.latestTask(ELIGIBILITY_POLL_MS))
                assertTrue(newRun != oldRun)
                assertTrue(newWatchdog.task !== oldWatchdog.task)
                assertTrue(newPoll.task !== oldPoll.task)
                val registrations = fixture.runtime.scheduled.size
                oldPoll.task.run()
                oldWatchdog.task.run()
                assertEquals(registrations, fixture.runtime.scheduled.size)
                assertEquals(1, injectedCalls)
                assertEquals(1, fixture.sessionEnds)
                assertEquals(1, fixture.stream.stopCalls)
                assertTrue(fixture.runtime.isQueued(newWatchdog.task))
                assertTrue(fixture.runtime.isQueued(newPoll.task))

                fixture.stream.ready(newRun)
                assertEquals("ready must pass through the production post", 0, fixture.waitingWakeCount())
                assertTrue(fixture.runtime.isQueued(newWatchdog.task))
                fixture.runtime.drain()
                assertEquals(1, fixture.waitingWakeCount())
                assertFalse(fixture.runtime.isQueued(newWatchdog.task))
                assertTrue(fixture.runtime.isQueued(newPoll.task))
                assertEquals(2, fixture.stream.startCalls)
                assertEquals(1, fixture.stream.stopCalls)
                assertEquals(1, fixture.sessionEnds)
                assertEquals(0, fixture.acknowledgementPlays)
                assertTrue(fixture.commands.isEmpty())
                assertTrue(fixture.failures.isEmpty())
            }
        }
    }

    private enum class EligibilityReturn {
        ALLOWED,
        DENIED,
        THROWS,
    }

    private fun suppliedEligibility(result: EligibilityReturn): HandsFreeVoiceEligibility {
        if (result == EligibilityReturn.THROWS) {
            throw IllegalStateException("injected eligibility failure")
        }
        return HandsFreeVoiceEligibility(
            walkState = WalkSessionState.ACTIVE,
            microphoneGranted = true,
            disclosureAccepted = result == EligibilityReturn.ALLOWED,
            modelReady = true,
        )
    }

    private fun modelLoadFailure(): HandsFreeVoiceTerminalFailure =
        HandsFreeVoiceTerminalFailure.Transcriber(VoskStreamingError.MODEL_LOAD_FAILED)

    private fun withFixture(test: (Fixture) -> Unit) {
        val fixture = Fixture()
        try {
            test(fixture)
        } catch (failure: Throwable) {
            try {
                fixture.controller.close()
            } catch (cleanup: Throwable) {
                if (cleanup !== failure) runCatching { failure.addSuppressed(cleanup) }
            }
            throw failure
        }
        fixture.controller.close()
        assertEquals(1, fixture.stream.closeCalls)
        assertEquals(1, fixture.acknowledgementCloses)
        assertEquals("shared Handler work must not be cleared", 0, fixture.runtime.removeAllCalls)
    }

    private class Fixture {
        val runtime = ManualRuntime()
        var nextEligibility: (() -> HandsFreeVoiceEligibility)? = null
        val statuses = mutableListOf<String>()
        val failures = mutableListOf<HandsFreeVoiceTerminalFailure>()
        val commands = mutableListOf<String>()
        var sessionEnds = 0
        var acknowledgementPlays = 0
        var acknowledgementCloses = 0
        var streamCreations = 0
        lateinit var stream: FakeStream

        val controller = HandsFreeVoiceController(
            dependencies = HandsFreeVoiceDependencies(
                runtime = runtime,
                createStream = { listener, _, _ ->
                    streamCreations += 1
                    FakeStream(listener).also { stream = it }
                },
                playAcknowledgement = { _ -> acknowledgementPlays += 1 },
                cancelAcknowledgement = {},
                closeAcknowledgement = { acknowledgementCloses += 1 },
            ),
            eligibility = {
                val callback = nextEligibility
                nextEligibility = null
                if (callback != null) {
                    callback()
                } else {
                    HandsFreeVoiceEligibility(
                        walkState = WalkSessionState.ACTIVE,
                        microphoneGranted = true,
                        disclosureAccepted = true,
                        modelReady = true,
                    )
                }
            },
            isAppSpeechActive = { false },
            onCommand = { text, _ -> commands += text },
            onStatus = { statuses += it },
            onTerminalFailure = { failures += it },
            onSessionEnded = { sessionEnds += 1 },
        )

        fun startRun(): Long {
            assertTrue(controller.start())
            assertEquals(1, streamCreations)
            return stream.lastReservedRunId
        }

        fun latestTask(delayMs: Long): Scheduled? =
            runtime.scheduled.lastOrNull { it.delayMs == delayMs }

        fun waitingWakeCount(): Int =
            statuses.count { it == "voice_hands_free=waiting_wake_phrase" }
    }

    /** Stream reservation and callback delivery only; no controller decisions. */
    private class FakeStream(
        private val listener: VoskStreamingListener,
    ) : HandsFreeStreamingPort {
        var lastReservedRunId = 0L
            private set
        var startCalls = 0
            private set
        var stopCalls = 0
            private set
        var closeCalls = 0
            private set
        private var running = false

        override fun start(onRunReserved: (Long) -> Unit): Long? {
            startCalls += 1
            lastReservedRunId += 1L
            running = true
            onRunReserved(lastReservedRunId)
            return lastReservedRunId
        }

        override fun isRunning(): Boolean = running

        override fun stop() {
            stopCalls += 1
            running = false
        }

        override fun close() {
            closeCalls += 1
            running = false
        }

        fun ready(runId: Long) {
            listener.onReady(runId)
        }
    }

    private data class Scheduled(
        val task: Runnable,
        val delayMs: Long,
        val dueMs: Long,
        val sequence: Long,
    )

    /** Registered Runnable identities and FIFO virtual time, not a copied lifecycle. */
    private class ManualRuntime : VoiceCallbackRuntime {
        private var currentMs = 0L
        private var nextSequence = 0L
        private val pending = mutableListOf<Scheduled>()
        val scheduled = mutableListOf<Scheduled>()
        var removeAllCalls = 0
            private set

        override fun checkMainThread(owner: String) = Unit

        override fun nowMs(): Long = currentMs

        override fun post(task: Runnable) {
            postDelayed(task, 0L)
        }

        override fun postDelayed(task: Runnable, delayMs: Long) {
            require(delayMs >= 0L)
            val entry = Scheduled(task, delayMs, currentMs + delayMs, nextSequence++)
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

        fun isQueued(task: Runnable): Boolean = pending.any { it.task === task }

        fun drain() {
            advanceTo(currentMs)
        }

        fun advanceTo(targetMs: Long) {
            require(targetMs >= currentMs)
            while (true) {
                val next = pending
                    .filter { it.dueMs <= targetMs }
                    .minWithOrNull(compareBy<Scheduled> { it.dueMs }.thenBy { it.sequence })
                    ?: break
                pending.remove(next)
                currentMs = next.dueMs
                next.task.run()
            }
            currentMs = targetMs
        }
    }

    private companion object {
        const val MODEL_LOAD_TIMEOUT_MS = 20_000L
        const val ELIGIBILITY_POLL_MS = 250L
    }
}
