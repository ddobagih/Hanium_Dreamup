package kr.co.hanium.dreamup.walksafe.device.postlogin

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicInteger
import kr.co.hanium.dreamup.walksafe.CameraRuntimeHandoff
import kr.co.hanium.dreamup.walksafe.device.*
import org.junit.Assert.*
import org.junit.Test

/** Real production teardown/registry with controlled ordering, without an Activity or model factory. */
class DeviceRuntimeLeaseRecoveryTest {
    private class Captured {
        val native = CompletableFuture<Unit>()
        val available = CompletableFuture<Unit>()
    }
    private class Fixture(known: Boolean = true, throwingSink: Boolean = false) {
        val registry = CameraRuntimeHandoff.Registry()
        val original = requireNotNull(registry.begin())
        val gone = CompletableFuture<CompletionStage<Boolean>>()
        val host = CompletableFuture<Unit>()
        val primary = CompletableFuture<Unit>()
        val auxiliary = CompletableFuture<Boolean>()
        val hostReceipt = CompletableFuture<Unit>()
        val consumer = CompletableFuture<Unit>()
        val consumerReleased = CompletableFuture<Unit>()
        val oldOwner = Any()
        val captured = Captured()
        var latest: Captured? = if (known) captured else null
        var queries = 0
        var exits = 0
        val released = AtomicInteger()
        val events = mutableListOf<DeviceCheckTraceEvent>()
        val settled = DeviceRuntimeLeaseTeardown.afterOwnerGone(gone, host, primary, oldOwner,
            snapshot = { owner -> assertSame(oldOwner, owner); queries++; latest },
            knownRetirement = captured.takeIf { known }, actualReleased = { it.native },
            allocationAvailable = { it.available }, requestExit = { assertSame(captured, it); exits++ },
            hostDrained = hostReceipt, consumerDrained = consumer, consumerReleased = consumerReleased,
            trace = DeviceCheckTraceSink { if (throwingSink) error("diagnostic sink failed") else events += it }, flowId = 17L)
            .whenComplete { _, error -> if (error == null) { original.complete(); released.incrementAndGet() } }
        val proofs = listOf<() -> Unit>({ host.complete(Unit) }, { primary.complete(Unit) },
            { auxiliary.complete(true) }, { captured.native.complete(Unit) },
            { captured.available.complete(Unit) }, { consumerReleased.complete(Unit) })
    }

    @Test fun ownerDestructionAtEveryWaitReturnsTheOriginalClaimOnlyAfterAllCapturedProofs() {
        for (destroyAt in 0..6) {
            val f = Fixture()
            f.proofs.take(destroyAt).forEach { it() }
            assertFalse(f.settled.isDone)
            val destroyedParticipant = f.registry.joinRetirement()
            destroyedParticipant.complete() // Other teardown does not own the original claim.
            assertNull(f.registry.begin())
            assertTrue(f.gone.complete(f.auxiliary))
            f.proofs.drop(destroyAt).forEachIndexed { index, complete ->
                if (index < 6 - destroyAt) assertNull(f.registry.begin())
                complete()
            }
            assertEquals(Unit, f.settled.getNow(null))
            assertEquals(Unit, f.hostReceipt.getNow(null))
            assertEquals(Unit, f.consumer.getNow(null))
            assertEquals(1, f.released.get())
            val next = f.registry.begin()
            assertNotNull("A fresh explicit attempt can acquire after real cleanup", next)
            assertNull("No duplicate allocation", f.registry.begin())
            next!!.complete()
            assertFalse(f.gone.complete(CompletableFuture.completedFuture(true)))
            assertEquals(1, f.released.get())
        }
    }

    @Test fun aLateOldCreationIsCapturedAfterTheHostDrainAndExitPrecedesPhysicalClose() {
        val f = Fixture(known = false)
        f.gone.complete(f.auxiliary)
        assertEquals(0, f.queries); assertEquals(0, f.exits)
        f.latest = f.captured
        f.host.complete(Unit)
        assertEquals(1, f.queries); assertEquals(1, f.exits)
        assertFalse(f.primary.isDone)
        f.proofs.drop(1).forEach { it() }
        assertEquals(Unit, f.settled.getNow(null))
        assertEquals(1, f.released.get())
    }

    @Test fun anyUnknownHostAuxiliaryNativeJournalOrRegistryProofKeepsTheOriginalClaim() {
        for (failed in 0..5) {
            val f = Fixture()
            f.gone.complete(f.auxiliary)
            when (failed) {
                0 -> f.host.completeExceptionally(IllegalStateException("host"))
                1 -> f.primary.completeExceptionally(IllegalStateException("local close"))
                2 -> f.auxiliary.complete(false)
                3 -> f.captured.native.completeExceptionally(IllegalStateException("native"))
                4 -> f.captured.available.completeExceptionally(IllegalStateException("journal"))
                else -> f.consumerReleased.completeExceptionally(IllegalStateException("registry"))
            }
            f.proofs.forEach { it() }
            assertTrue(f.settled.isCompletedExceptionally)
            assertEquals(0, f.released.get())
            assertNull(f.registry.begin())
        }
    }

    @Test fun physicalReleaseAloneDoesNotMarkConsumersDrainedOrReturnTheBudget() {
        val f = Fixture()
        f.gone.complete(f.auxiliary)
        f.host.complete(Unit); f.primary.complete(Unit); f.auxiliary.complete(true)
        f.captured.native.complete(Unit)
        assertFalse(f.hostReceipt.isDone); assertFalse(f.consumer.isDone)
        assertNull(f.registry.begin())
        f.captured.available.complete(Unit)
        assertEquals(Unit, f.hostReceipt.getNow(null)); assertEquals(Unit, f.consumer.getNow(null))
        assertFalse(f.settled.isDone)
        f.consumerReleased.complete(Unit)
        assertEquals(Unit, f.settled.getNow(null))
    }

    @Test fun proofLogsIdentifyThePendingStageAndCannotChangeCleanupWhenTheSinkThrows() {
        val f = Fixture()
        f.gone.complete(f.auxiliary)
        f.proofs.forEach { it() }
        assertTrue(f.events.any { it.stage == DeviceCheckTraceStage.ALLOCATION_AVAILABLE && it.outcome == DeviceCheckTraceOutcome.WAITING })
        assertTrue(f.events.any { it.stage == DeviceCheckTraceStage.CONSUMER_RELEASE && it.outcome == DeviceCheckTraceOutcome.SUCCEEDED })
        assertTrue(f.events.all { it.flowId == 17L })
        val throwing = Fixture(throwingSink = true)
        throwing.gone.complete(throwing.auxiliary); throwing.proofs.forEach { it() }
        assertEquals(Unit, throwing.settled.getNow(null))
        assertEquals(1, throwing.released.get())
    }

    @Test fun staleCompletionAndAMereResumeAuthorizationKeepTheExactQuiescedCapture() {
        val old = Any(); val newer = Any()
        for (current in listOf(false, true)) for (closing in listOf(false, true)) {
            val kept = DeviceRuntimeLeaseRecovery.retirementAfterCompletion(old, old, current, closing)
            if (current && !closing) assertNull(kept) else assertSame(old, kept)
            assertSame(newer, DeviceRuntimeLeaseRecovery.retirementAfterCompletion(newer, old, current, closing))
        }
        assertSame(old, DeviceRuntimeLeaseRecovery.retirementAfterCompletion(old, old, true, true))
        assertSame(old, DeviceRuntimeLeaseRecovery.retirementAfterReservation(old, old, false, true))
        assertSame(old, DeviceRuntimeLeaseRecovery.retirementAfterReservation(old, old, true, true))
        assertNull(DeviceRuntimeLeaseRecovery.retirementAfterReservation(old, old, true, false))
        assertSame(newer, DeviceRuntimeLeaseRecovery.retirementAfterReservation(newer, old, true, false))
    }
}
