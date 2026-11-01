package kr.co.hanium.dreamup.walksafe.runtime.metric

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.co.hanium.dreamup.walksafe.BuildConfig
import kr.co.hanium.dreamup.walksafe.device.*
import java.util.UUID
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Future explicit opt-in only. Debug fake-native paths never open/resume a camera or clear data. */
@RunWith(AndroidJUnit4::class)
class MetricSupportIsolationFaultDeviceTest {
    @Test(timeout = 45_000L)
    fun stoppedParentBindReturnsOneFailureButKeepsAllRetriesFencedUntilItsActualDrain() {
        requireOptIn()
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val reservation = reserveDiagnosticOwner()
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val unbound = CountDownLatch(1)
        // A test-only parent system-call boundary, not an ARCore/GPU driver hang.
        val stoppedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun bindService(intent: Intent, connection: ServiceConnection, flags: Int): Boolean {
                entered.countDown(); unblock.await(); return false
            }
            override fun unbindService(connection: ServiceConnection) { unbound.countDown() }
        }
        val probe = RemoteMetricSupportProbe(stoppedContext, fault = MetricSupportProtocol.FAULT_SUPPORTED,
            nativeBudgetMs = 1_000L, ownerRef = { reservation.second }, authority = reservation.first)
        val completed = CountDownLatch(1)
        val results = java.util.concurrent.CopyOnWriteArrayList<Result<Boolean>>()
        try {
            probe.query { results += it; completed.countDown() }
            assertTrue("Cancelling a caller's receipt view is not a physical release", probe.actualReleasedFuture().cancel(true))
            val physical = probe.actualReleasedFuture()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(completed.await(15, TimeUnit.SECONDS))
            assertEquals(1, results.size)
            assertEquals(MetricSupportFailure.RELEASE_UNCONFIRMED,
                (results.single().exceptionOrNull() as? MetricSupportProbeException)?.reason)
            assertTrue(probe.releasePending()); assertFalse(physical.isDone)
            repeat(30) {
                val retry = RemoteMetricSupportProbe(context, fault = MetricSupportProtocol.FAULT_SUPPORTED,
                    nativeBudgetMs = 1_000L, ownerRef = { reservation.second }, authority = reservation.first)
                assertEquals(MetricSupportFailure.BUSY, (query(retry).exceptionOrNull() as? MetricSupportProbeException)?.reason)
            }
            assertEquals(1, MetricParentExecution.process().poolSize(MetricParentLane.IPC))
            unblock.countDown()
            assertTrue(unbound.await(5, TimeUnit.SECONDS))
            physical.get(5, TimeUnit.SECONDS)
            awaitAvailable(probe)
            assertFalse(probe.releasePending())
            assertEquals(1, results.size)
        } finally {
            unblock.countDown(); probe.cancel()
            probe.actualReleasedFuture().get(10, TimeUnit.SECONDS)
            awaitAvailable(probe)
        }
    }

    @Test(timeout = 45_000L)
    fun stoppedParentUnbindCannotTurnChildDeathIntoAReleasedSupportProbe() {
        requireOptIn()
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val reservation = reserveDiagnosticOwner()
        val entered = CountDownLatch(1)
        val unblock = CountDownLatch(1)
        val stoppedContext = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun unbindService(connection: ServiceConnection) {
                entered.countDown(); unblock.await(); super.unbindService(connection)
            }
        }
        val probe = RemoteMetricSupportProbe(stoppedContext, fault = MetricSupportProtocol.FAULT_SUPPORTED,
            nativeBudgetMs = 1_000L, ownerRef = { reservation.second }, authority = reservation.first)
        val completed = CountDownLatch(1)
        val results = java.util.concurrent.CopyOnWriteArrayList<Result<Boolean>>()
        try {
            probe.query { results += it; completed.countDown() }
            val physical = probe.actualReleasedFuture()
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            assertTrue(completed.await(15, TimeUnit.SECONDS))
            assertEquals(1, results.size); assertTrue(results.single().isFailure)
            assertTrue(probe.releasePending()); assertFalse(physical.isDone)
            val retry = RemoteMetricSupportProbe(context, ownerRef = { reservation.second }, authority = reservation.first)
            assertEquals(MetricSupportFailure.BUSY, (query(retry).exceptionOrNull() as? MetricSupportProbeException)?.reason)
            assertEquals(1, MetricParentExecution.process().poolSize(MetricParentLane.UNBIND))
            unblock.countDown(); physical.get(10, TimeUnit.SECONDS)
            awaitAvailable(probe)
            assertFalse(probe.releasePending()); assertEquals(1, results.size)
            val afterJournalAck = RemoteMetricSupportProbe(context, fault = MetricSupportProtocol.FAULT_SUPPORTED,
                nativeBudgetMs = 1_000L, ownerRef = { reservation.second }, authority = reservation.first)
            assertEquals("A late physical ACK also needs the captured journal cleanup before a new native query",
                true, query(afterJournalAck).getOrThrow())
        } finally {
            unblock.countDown(); probe.cancel()
            probe.actualReleasedFuture().get(10, TimeUnit.SECONDS)
            awaitAvailable(probe)
        }
    }

    @Test(timeout = 90_000L)
    fun createQueryAndCloseHangsDieBeforeAnotherSupportAttemptIsAdmitted() {
        requireOptIn()
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val reservation = reserveDiagnosticOwner()
        val parentPid = Process.myPid()
        for (fault in listOf(MetricSupportProtocol.FAULT_CREATE_HANG,
                MetricSupportProtocol.FAULT_QUERY_HANG, MetricSupportProtocol.FAULT_CLOSE_HANG)) {
            val probe = RemoteMetricSupportProbe(context, { SystemClock.elapsedRealtime() + 15_000L }, fault, 500L,
                { reservation.second }, reservation.first)
            val completed = query(probe)
            assertTrue("A stuck operation cannot claim an API false/success", completed.isFailure)
            assertTrue(completed.exceptionOrNull() is MetricSupportProbeException)
            assertFalse("Actual Binder death and unbind must retire the owner", probe.releasePending())
            assertEquals("Only the private child may be killed", parentPid, Process.myPid())
            // A fresh private child is necessary: no hung native owner may be reused.
            val retry = RemoteMetricSupportProbe(context, fault = MetricSupportProtocol.FAULT_SUPPORTED, nativeBudgetMs = 1_000L,
                ownerRef = { reservation.second }, authority = reservation.first)
            assertEquals(true, query(retry).getOrThrow())
            assertFalse(retry.releasePending())
        }
    }

    @Test(timeout = 40_000L)
    fun actualFalseAndQueryExceptionRemainDifferentAfterTheWorkerDies() {
        requireOptIn()
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val reservation = reserveDiagnosticOwner()
        val unsupported = RemoteMetricSupportProbe(context, fault = MetricSupportProtocol.FAULT_UNSUPPORTED, nativeBudgetMs = 1_000L,
            ownerRef = { reservation.second }, authority = reservation.first)
        assertEquals(false, query(unsupported).getOrThrow())
        assertFalse(unsupported.releasePending())
        val failure = RemoteMetricSupportProbe(context, fault = MetricSupportProtocol.FAULT_QUERY_ERROR, nativeBudgetMs = 1_000L,
            ownerRef = { reservation.second }, authority = reservation.first)
        val result = query(failure)
        assertEquals(MetricSupportFailure.NATIVE_FAILED, (result.exceptionOrNull() as? MetricSupportProbeException)?.reason)
        assertFalse(failure.releasePending())
    }

    @Test(timeout = 30_000L)
    fun cancelRetiresAuthorityAndStillDeliversOnlyOneCompletion() {
        requireOptIn()
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val reservation = reserveDiagnosticOwner()
        val probe = RemoteMetricSupportProbe(context, fault = MetricSupportProtocol.FAULT_CREATE_HANG, nativeBudgetMs = 1_000L,
            ownerRef = { reservation.second }, authority = reservation.first)
        val done = CountDownLatch(1)
        val results = java.util.concurrent.CopyOnWriteArrayList<Result<Boolean>>()
        probe.query { results += it; done.countDown() }
        probe.cancel(); probe.cancel()
        assertTrue(done.await(15, TimeUnit.SECONDS))
        assertEquals(1, results.size)
        assertTrue(results.single().isFailure)
        assertFalse(probe.releasePending())
    }

    private fun query(probe: RemoteMetricSupportProbe): Result<Boolean> {
        val done = CountDownLatch(1)
        val result = AtomicReference<Result<Boolean>>()
        probe.query { result.set(it); done.countDown() }
        assertTrue("Bounded support query must complete", done.await(15, TimeUnit.SECONDS))
        return requireNotNull(result.get())
    }
    private fun awaitAvailable(probe: RemoteMetricSupportProbe) {
        val deadline = SystemClock.elapsedRealtime() + 10_000L
        while (probe.releasePending() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(5L)
        assertFalse("Actual transport and the captured journal must both settle", probe.releasePending())
    }
    private fun requireOptIn() {
        assumeTrue(BuildConfig.DEBUG && InstrumentationRegistry.getArguments()
            .getString("allowMetricSupportIsolationFaults") == "true")
    }
    private fun reserveDiagnosticOwner(): Pair<AndroidDeviceCheckAuthority, DeviceCheckOwnerRef> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val nonce = UUID.randomUUID().toString()
        val authority = AndroidDeviceCheckAuthority.forDiagnostics(context, nonce)
        val binding = DeviceCheckAuthorityBinding("a".repeat(64), "b".repeat(64), "c".repeat(64), "d".repeat(64))
        val now = SystemClock.elapsedRealtime()
        val outcome = authority.beginAttempt(binding, UUID.randomUUID().toString(), now, now + 120_000L)
            .toCompletableFuture().get(10, TimeUnit.SECONDS)
        val attempt = (outcome as DeviceCheckAuthorityOutcome.Committed).value
        return authority to requireNotNull(authority.registerOwner("metric-test-$nonce", attempt, 1))
    }
}
