package kr.co.hanium.dreamup.walksafe.navigation.session

import org.junit.Assert.*
import org.junit.Test

/** Domain delivery owner with controlled START validators and actual terminal callbacks. */
class RouteRecheckNoticeCoordinatorTest {
    @Test fun actionAndInformationReservationsPrecedeEnvironmentThenDirectionNotices() {
        val f = Fixture()
        f.reserved = true
        var deliveries = 0
        f.coordinator.offerDirection(1L) { true }
        f.coordinator.offerEnvironment("environment", { true }) { deliveries++ }
        assertTrue(f.sent.isEmpty())
        f.reserved = false
        f.coordinator.resumeAfterRouteTerminal()
        assertEquals(1L, f.pending.single().second)
        // INFO is enqueued after ACTION's completion returns in the same native callback stack.
        f.reserved = true
        f.fire()
        assertTrue(f.sent.isEmpty())
        f.reserved = false
        f.coordinator.resumeAfterRouteTerminal(); f.fire()
        assertEquals("environment", f.sent.single().message)
        assertTrue(f.sent.single().current())
        assertEquals(0, deliveries)
        f.sent.single().done(); f.sent.single().done()
        assertEquals(1, deliveries)
        f.fire()
        assertEquals("방향을 다시 확인하고 있습니다.", f.sent.last().message)
    }

    @Test fun eachBusyPortDefersExactlyOneTimerForTwoHundredFiftyMilliseconds() {
        for (port in listOf("command", "busy", "output", "input")) {
            val f = Fixture()
            f.blockedPort = port
            f.coordinator.offerEnvironment("environment", { true }, {})
            repeat(3) { f.coordinator.drain() }
            assertEquals(port, 250L, f.pending.single().second)
            assertTrue(f.sent.isEmpty())
            f.fire()
            assertEquals(250L, f.pending.single().second)
            f.blockedPort = null
            f.fire()
            assertEquals(1, f.sent.size)
        }
    }

    @Test fun failedOrRejectedAttemptPreservesRetryNotBeforeDespiteFrequentDrainAndLateDone() {
        for (rejected in listOf(false, true)) {
            val f = Fixture()
            var deliveries = 0
            f.accept = !rejected
            f.coordinator.offerEnvironment("environment", { true }) { deliveries++ }
            val failed = f.sent.single()
            if (!rejected) failed.failed()
            failed.done(); failed.failed()
            assertEquals(0, deliveries)
            assertEquals(2_000L, f.pending.single().second)
            f.now += 1_999L
            repeat(3) { f.coordinator.drain() }
            assertEquals(1, f.sent.size)
            f.now += 1L
            f.accept = true
            f.coordinator.drain()
            assertEquals(2, f.sent.size)
            val current = f.sent.last()
            assertTrue(current.current())
            failed.done(); failed.failed()
            assertTrue(current.current())
            current.done()
            assertEquals(1, deliveries)
        }
    }

    @Test fun staleActiveTerminalCannotDeliverOrCancelANewerEnvironmentOwner() {
        for (done in listOf(false, true)) {
            val f = Fixture()
            var oldDeliveries = 0
            var newDeliveries = 0
            f.coordinator.offerEnvironment("old", { true }) { oldDeliveries++ }
            val old = f.sent.single()
            f.binding++
            f.coordinator.offerEnvironment("new", { true }) { newDeliveries++ }
            assertFalse(old.current())
            assertTrue(f.coordinator.active)
            assertEquals(1, f.sent.size)
            if (done) old.done() else old.failed()
            f.fire()
            val replacement = f.sent.last()
            assertEquals("new", replacement.message)
            assertTrue(replacement.current())
            old.done(); old.failed()
            assertTrue(replacement.current())
            assertEquals(0, oldDeliveries)
            assertEquals(0, newDeliveries)
            replacement.done()
            assertEquals(1, newDeliveries)
        }
    }

    @Test fun recoveredDirectionEpisodeAndRetiredBindingCannotReuseQueuedOrActiveNotice() {
        val f = Fixture()
        var episode = 1L
        f.coordinator.offerDirection(1L) { episode == 1L }
        f.coordinator.drain()
        val old = f.sent.single()
        episode = 2L
        f.coordinator.retireDirection()
        f.coordinator.offerDirection(2L) { episode == 2L }
        assertFalse(old.current())
        old.done(); f.fire()
        assertEquals(2, f.sent.size)
        val replacement = f.sent.last()
        assertTrue(replacement.current())
        f.binding++
        assertFalse(replacement.current())
        replacement.done(); f.fire()
        f.coordinator.offerDirection(2L) { true }
        f.coordinator.drain()
        assertEquals("An old episode is not rebound to a replacement positioning collection", 2, f.sent.size)
    }

    @Test fun duplicateAndExpiredTimerCannotClearOrReviveAnotherScheduledAttempt() {
        val f = Fixture()
        var current = true
        f.blockedPort = "input"
        f.coordinator.offerEnvironment("environment", { current }, {})
        val late = f.pending.single().first
        f.fire()
        val next = f.pending.single()
        late.run()
        assertEquals(next, f.pending.single())
        current = false
        f.blockedPort = null
        f.fire(); late.run()
        assertTrue(f.sent.isEmpty())
        assertTrue(f.pending.isEmpty())
    }

    @Test fun synchronousFailureAndRejectedReturnStillScheduleOneAttemptAndOneNavigationWake() {
        val f = Fixture()
        f.accept = false
        f.failOnSubmit = true
        f.coordinator.offerEnvironment("environment", { true }, {})
        assertFalse(f.coordinator.active)
        assertEquals(1, f.pending.size)
        assertEquals(1, f.navigationWakes)
    }

    private data class Submission(val message: String, val current: () -> Boolean,
        val done: () -> Unit, val failed: () -> Unit)

    private class Fixture : RouteRecheckNoticeCoordinator.SpeechPort, RouteCallbackScheduler {
        var now = 1_000L
        var binding = 1
        var reserved = false
        var blockedPort: String? = null
        var accept = true
        var failOnSubmit = false
        var navigationWakes = 0
        val sent = mutableListOf<Submission>()
        val pending = mutableListOf<Pair<Runnable, Long>>()
        val coordinator = RouteRecheckNoticeCoordinator(this, this, { now }, { stillCurrent ->
            val captured = binding
            val current = { captured == binding && stillCurrent() }
            current
        }, {}, { reserved }, { 2_000L }, { navigationWakes++ })
        override val commandPending: Boolean get() = blockedPort == "command"
        override fun busy() = blockedPort == "busy"
        override fun outputBusy() = blockedPort == "output"
        override fun inputActive() = blockedPort == "input"
        override fun submitStatus(message: String, current: () -> Boolean,
            completed: () -> Unit, failed: (() -> Unit)?): Boolean {
            val submission = Submission(message, current, completed, requireNotNull(failed))
            sent += submission
            if (failOnSubmit) submission.failed()
            return accept
        }
        override fun post(task: Runnable) = postDelayed(task, 0L)
        override fun postDelayed(task: Runnable, delayMs: Long) { pending += task to delayMs }
        override fun remove(task: Runnable) { pending.removeAll { it.first === task } }
        fun fire() {
            val (task, delay) = pending.removeAt(0)
            now += delay
            task.run()
        }
    }
}
