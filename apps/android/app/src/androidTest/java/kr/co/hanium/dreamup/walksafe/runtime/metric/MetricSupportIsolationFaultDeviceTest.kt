package kr.co.hanium.dreamup.walksafe.runtime.metric

import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kr.co.hanium.dreamup.walksafe.BuildConfig
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
    @Test(timeout = 90_000L)
    fun createQueryAndCloseHangsDieBeforeAnotherSupportAttemptIsAdmitted() {
        requireOptIn()
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val parentPid = Process.myPid()
        for (fault in listOf(MetricSupportProtocol.FAULT_CREATE_HANG,
                MetricSupportProtocol.FAULT_QUERY_HANG, MetricSupportProtocol.FAULT_CLOSE_HANG)) {
            val probe = RemoteMetricSupportProbe(context, { SystemClock.elapsedRealtime() + 15_000L }, fault, 500L)
            val completed = query(probe)
            assertTrue("A stuck operation cannot claim an API false/success", completed.isFailure)
            assertTrue(completed.exceptionOrNull() is MetricSupportProbeException)
            assertFalse("Actual Binder death and unbind must retire the owner", probe.releasePending())
            assertEquals("Only the private child may be killed", parentPid, Process.myPid())
            // A fresh private child is necessary: no hung native owner may be reused.
            val retry = RemoteMetricSupportProbe(context, fault = MetricSupportProtocol.FAULT_SUPPORTED, nativeBudgetMs = 1_000L)
            assertEquals(true, query(retry).getOrThrow())
            assertFalse(retry.releasePending())
        }
    }

    @Test(timeout = 40_000L)
    fun actualFalseAndQueryExceptionRemainDifferentAfterTheWorkerDies() {
        requireOptIn()
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val unsupported = RemoteMetricSupportProbe(context, fault = MetricSupportProtocol.FAULT_UNSUPPORTED, nativeBudgetMs = 1_000L)
        assertEquals(false, query(unsupported).getOrThrow())
        assertFalse(unsupported.releasePending())
        val failure = RemoteMetricSupportProbe(context, fault = MetricSupportProtocol.FAULT_QUERY_ERROR, nativeBudgetMs = 1_000L)
        val result = query(failure)
        assertEquals(MetricSupportFailure.NATIVE_FAILED, (result.exceptionOrNull() as? MetricSupportProbeException)?.reason)
        assertFalse(failure.releasePending())
    }

    @Test(timeout = 30_000L)
    fun cancelRetiresAuthorityAndStillDeliversOnlyOneCompletion() {
        requireOptIn()
        val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        val probe = RemoteMetricSupportProbe(context, fault = MetricSupportProtocol.FAULT_CREATE_HANG, nativeBudgetMs = 1_000L)
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
    private fun requireOptIn() {
        assumeTrue(BuildConfig.DEBUG && InstrumentationRegistry.getArguments()
            .getString("allowMetricSupportIsolationFaults") == "true")
    }
}
