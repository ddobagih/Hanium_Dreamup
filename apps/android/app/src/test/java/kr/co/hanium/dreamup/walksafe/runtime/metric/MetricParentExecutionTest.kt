package kr.co.hanium.dreamup.walksafe.runtime.metric

import kr.co.hanium.dreamup.walksafe.inference.composite.FixtureRuntimeConnectionLease
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MetricParentExecutionTest {
    @Test fun explicitCleanupRecheckRunsOutsideTheGlobalSupervisorLock() {
        val observed = CountDownLatch(1)
        lateinit var owner: MetricSupportPendingRelease
        owner = MetricSupportPendingRelease {
            val observer = Thread { MetricSupportSupervisor.owns(owner); observed.countDown() }
            observer.start()
            assertTrue(observed.await(2, TimeUnit.SECONDS))
            observer.join(2_000L)
        }
        assertTrue(MetricSupportSupervisor.acquire(owner))
        try { MetricSupportSupervisor.recheckRelease() }
        finally { MetricSupportSupervisor.release(owner, true) }
    }

    @Test fun everyAttemptedBindNeedsSuccessfulUnbindEvenWhenNoQueryWasEverSent() {
        for (bindReturn in listOf(true, false, null)) {
            val lease = FixtureRuntimeConnectionLease<String>()
            val transport = MetricParentTransport {}
            assertTrue(lease.beginBind())
            // null represents a synchronous SDK exception: it still entered bindService.
            lease.finishBind(bindReturn == true); lease.retire()
            assertTrue(lease.claimUnbind()); lease.finishUnbind(false)
            transport.outerFinished()
            assertFalse("bind=$bindReturn is native-free but its failed unbind cannot release the global owner",
                transport.claimRelease(nativeConfirmed = true, bindingConfirmed = false))
            assertEquals(0, transport.pendingTasks())
        }
        val neverInvoked = MetricParentTransport {}
        neverInvoked.outerFinished()
        assertTrue(neverInvoked.claimRelease(nativeConfirmed = true, bindingConfirmed = true))
    }

    @Test fun cancelledRunningBindCannotReleaseTheParentFenceBeforeItsActualReturn() {
        val execution = MetricParentExecution("metric-bind-test")
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val transport = MetricParentTransport {}
        val owner = Any()
        assertTrue(MetricSupportSupervisor.acquire(owner))
        try {
            val bind = execution.submit(MetricParentLane.IPC, transport, Callable {
                entered.countDown(); unblock.await(); true
            })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(bind.cancel(true))
            transport.outerFinished()
            assertTrue("Semantic cancellation is already observable", bind.isDone)
            assertFalse(transport.awaitDrained(20))
            assertFalse("NO_NATIVE_START does not prove a returned Android bind", transport.claimRelease(true))
            repeat(30) { assertFalse(MetricSupportSupervisor.acquire(Any())) }
            assertEquals(1, execution.poolSize(MetricParentLane.IPC))
            unblock.countDown()
            assertTrue(transport.awaitDrained(2_000))
            assertTrue(transport.claimRelease(true))
            assertTrue(MetricSupportSupervisor.release(owner, true))
        } finally { unblock.countDown(); execution.shutdownForTest(); MetricSupportSupervisor.release(owner, true) }
    }

    @Test fun cancelledQueuedHelloAndStartKeepTheirReservationsUntilTheWrappersActuallyRun() {
        for (operation in listOf("HELLO", "START")) {
            val execution = MetricParentExecution("metric-queued-$operation")
            val entered = CountDownLatch(1)
            val unblock = CountDownLatch(1)
            val transport = MetricParentTransport {}
            val invoked = AtomicBoolean(false)
            try {
                val blocker = execution.submit(MetricParentLane.IPC, action = Callable {
                    entered.countDown(); unblock.await()
                })
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                val queued = execution.submit(MetricParentLane.IPC, transport, Callable { invoked.set(true) })
                assertTrue(queued.cancel(false))
                transport.outerFinished()
                assertEquals(1, transport.pendingTasks())
                assertFalse(transport.claimRelease(true))
                unblock.countDown(); blocker.get(2, TimeUnit.SECONDS)
                assertTrue(transport.awaitDrained(2_000))
                assertFalse(invoked.get())
                assertTrue(transport.claimRelease(true))
            } finally { unblock.countDown(); execution.shutdownForTest() }
        }
    }

    @Test fun queuedStartWhichWasNotCancelledRechecksRetiredAuthorityBeforeTransport() {
        val execution = MetricParentExecution("metric-retired-start")
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val transport = MetricParentTransport {}
        val lease = FixtureRuntimeConnectionLease<String>()
        lease.beginBind(); lease.finishBind(true); lease.endpointConnected("child"); lease.authenticate("child")
        val invoked = AtomicBoolean(false)
        try {
            execution.submit(MetricParentLane.IPC, action = Callable { entered.countDown(); unblock.await() })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val start = execution.submit(MetricParentLane.IPC, transport, Callable {
                lease.dispatchStart("child", { true }) { invoked.set(true) }
            })
            lease.retire(); transport.outerFinished()
            assertFalse(transport.claimRelease(true))
            unblock.countDown()
            assertThrows(java.util.concurrent.CancellationException::class.java) { start.get(2, TimeUnit.SECONDS) }
            assertTrue(transport.awaitDrained(2_000))
            assertFalse(invoked.get()); assertFalse(lease.needsDeath)
            assertTrue(transport.claimRelease(true))
        } finally { unblock.countDown(); execution.shutdownForTest() }
    }

    @Test fun saturatedOrdinaryIpcCannotCreateThreadsOrPreventIndependentUnbindAndExit() {
        val execution = MetricParentExecution("metric-saturation")
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val transport = MetricParentTransport {}
        try {
            execution.submit(MetricParentLane.IPC, transport, Callable { entered.countDown(); unblock.await() })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            repeat(execution.queueCapacity(MetricParentLane.IPC)) {
                execution.submit(MetricParentLane.IPC, transport, Callable { Unit })
            }
            assertThrows(RejectedExecutionException::class.java) {
                execution.submit(MetricParentLane.IPC, transport, Callable { fail("Rejected work must never run") })
            }
            assertEquals(1, execution.poolSize(MetricParentLane.IPC))
            assertEquals(2, execution.queued(MetricParentLane.IPC))
            assertEquals(3, transport.pendingTasks())
            execution.submit(MetricParentLane.UNBIND, transport, Callable { true }).get(2, TimeUnit.SECONDS)
            execution.submit(MetricParentLane.EXIT, transport, Callable { true }).get(2, TimeUnit.SECONDS)
            transport.outerFinished()
            assertFalse("Even actual child death cannot drain a stuck parent transact", transport.claimRelease(true))
            unblock.countDown()
            assertTrue(transport.awaitDrained(2_000))
            assertTrue(transport.claimRelease(true))
            assertNull("A late endpoint cannot reopen completed parent execution", transport.reserve())
        } finally { unblock.countDown(); execution.shutdownForTest() }
    }

    @Test fun stoppedUnbindHasItsOwnBoundAndCannotReleaseFromNativeDeathAlone() {
        val execution = MetricParentExecution("metric-unbind")
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val transport = MetricParentTransport {}
        try {
            execution.submit(MetricParentLane.UNBIND, transport, Callable { entered.countDown(); unblock.await() })
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            execution.submit(MetricParentLane.UNBIND, transport, Callable { Unit })
            assertThrows(RejectedExecutionException::class.java) {
                execution.submit(MetricParentLane.UNBIND, transport, Callable { Unit })
            }
            execution.submit(MetricParentLane.EXIT, transport, Callable { Unit }).get(2, TimeUnit.SECONDS)
            transport.outerFinished()
            assertFalse(transport.claimRelease(true))
            assertEquals(1, execution.poolSize(MetricParentLane.UNBIND))
            assertEquals(1, execution.queued(MetricParentLane.UNBIND))
            unblock.countDown()
            assertTrue(transport.awaitDrained(2_000))
            assertFalse(transport.claimRelease(false))
            assertTrue(transport.claimRelease(true))
        } finally { unblock.countDown(); execution.shutdownForTest() }
    }

    @Test fun outerJoinIsSeparateFromTransportDrainAndReleaseIsClaimedOnlyOnce() {
        val transport = MetricParentTransport {}
        val work = requireNotNull(transport.reserve())
        work.finish(); work.finish()
        assertTrue(transport.awaitDrained(0))
        assertFalse("The worker must not await its own outer-finalized flag", transport.claimRelease(true))
        transport.outerFinished()
        assertTrue(transport.claimRelease(true))
        assertFalse(transport.claimRelease(true))
        transport.outerFinished()
        assertNull(transport.reserve())
    }

    @Test fun drainNotificationDoesNotHoldTheTransportLockWhileCallingAnotherOwner() {
        val outside = CountDownLatch(1)
        lateinit var transport: MetricParentTransport
        transport = MetricParentTransport {
            val observer = Thread { transport.pendingTasks(); outside.countDown() }
            observer.start()
            assertTrue("External completion cannot run under the transport lock", outside.await(2, TimeUnit.SECONDS))
            observer.join(2_000)
        }
        requireNotNull(transport.reserve()).finish()
    }
}
