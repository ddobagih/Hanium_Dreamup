package kr.co.hanium.dreamup.walksafe.feedback

import org.junit.Assert.*
import org.junit.Test

class UtteranceLedgerTest {
    @Test
    fun reservationAndWatchdogDoNotManufactureAPlatformStart() {
        val ledger = UtteranceLedger()
        var validations = 0
        val validator = { validations++; true }
        val startWatchdog = Runnable { error("Ledger must not run effects") }
        ledger.reserve(UtteranceRecord("nav", 30_000L, startDeadlineMs = 800L, startValidator = validator))
        ledger.replaceWatchdog("nav", startWatchdog)

        assertFalse(requireNotNull(ledger["nav"]).started)
        assertSame(validator, ledger["nav"]?.startValidator)
        assertEquals(800L, ledger["nav"]?.startDeadlineMs)
        assertEquals(0, validations)
        val started = requireNotNull(ledger.markStarted("nav"))
        assertTrue(started.started)
        assertEquals(30_000L, started.terminalBudgetMs)
        assertSame(startWatchdog, started.watchdog)
    }

    @Test
    fun successorStartDetachesOnlyItsPredecessorsInAdmissionOrder() {
        val ledger = UtteranceLedger()
        val firstWatchdog = Runnable {}
        ledger.reserve(UtteranceRecord("first", 2_000L, watchdog = firstWatchdog))
        ledger.reserve(UtteranceRecord("second", 3_000L, requiresExplicitTerminalCallback = true))
        ledger.reserve(UtteranceRecord("started", 4_000L))
        ledger.reserve(UtteranceRecord("queued", 5_000L))
        ledger.markStarted("first")

        val detached = ledger.detachPredecessors("started")
        assertEquals(listOf("first", "second"), detached.map { it.id })
        assertTrue(detached[0].started)
        assertFalse(detached[1].started)
        assertSame(firstWatchdog, detached[0].watchdog)
        assertEquals(listOf("started", "queued"), ledger.toList())
        assertTrue(ledger.explicitTerminalIds().isEmpty())
        assertEquals("started", ledger.firstRecord()?.id)
        assertFalse(requireNotNull(ledger["started"]).started)
        ledger.markStarted("started")
        assertTrue(requireNotNull(ledger["started"]).started)
        assertFalse(requireNotNull(ledger["queued"]).started)
        assertTrue(ledger.detachPredecessors("late-unknown").isEmpty())
        assertEquals(listOf("started", "queued"), ledger.toList())
    }

    @Test
    fun detachWinsOnceAndLateStartOrWatchdogCannotRestoreTheOwner() {
        val ledger = UtteranceLedger()
        val startWatchdog = Runnable {}
        val terminalWatchdog = Runnable {}
        ledger.reserve(UtteranceRecord("risk", 8_000L, riskRank = 5, watchdog = startWatchdog))
        ledger.markStarted("risk")
        assertSame(startWatchdog, ledger.replaceWatchdog("risk", terminalWatchdog))
        val detached = requireNotNull(ledger.detach("risk"))

        assertSame(terminalWatchdog, detached.watchdog)
        assertEquals(5, detached.riskRank)
        assertNull(ledger.detach("risk"))
        assertNull(ledger.markStarted("risk"))
        assertNull(ledger.replaceWatchdog("risk", startWatchdog))
        assertTrue(ledger.isEmpty())
        assertSame(terminalWatchdog, detached.watchdog)
    }

    @Test
    fun detachedCloseCallbackCanReenterWithoutTheOldDrainErasingTheNewOwner() {
        val ledger = UtteranceLedger()
        val callbacks = UtteranceCallbackRegistry()
        var retired = 0
        var completed = 0
        ledger.reserve(UtteranceRecord("old", 5_000L, requiresExplicitTerminalCallback = true))
        callbacks.register("old", { completed++ }, { error("Close is not failure") },
            onCloseCancelled = {
                assertFalse(Thread.holdsLock(ledger))
                retired++
                synchronized(ledger) { ledger.reserve(UtteranceRecord("new", 8_000L)) }
            })
        val detachedCallbacks = synchronized(ledger) {
            val closeCallbacks = callbacks.takeCloseCancellationCallbacks(ledger)
            assertEquals(listOf("old"), ledger.drain().map { it.id })
            closeCallbacks
        }
        detachedCallbacks.forEach { it() }

        assertEquals(1, retired)
        assertEquals(0, completed)
        assertEquals(listOf("new"), ledger.toList())
        assertNull(ledger.detach("old"))
        assertNull(callbacks.takeTerminalCallback("old", completed = true, notifyFailure = true))
        assertTrue(callbacks.takeCloseCancellationCallbacks(listOf("old")).isEmpty())
    }

    @Test
    fun duplicateReservationCannotReplaceTheCurrentLifecycleFacts() {
        val ledger = UtteranceLedger()
        val current = UtteranceRecord("same", 3_000L, startDeadlineMs = 700L)
        ledger.reserve(current)
        try {
            ledger.reserve(UtteranceRecord("same", 8_000L, startDeadlineMs = 900L))
            fail("Duplicate reservation must preserve its current owner")
        } catch (_: IllegalStateException) {
            assertSame(current, ledger["same"])
            assertEquals(listOf("same"), ledger.toList())
        }
    }
}
