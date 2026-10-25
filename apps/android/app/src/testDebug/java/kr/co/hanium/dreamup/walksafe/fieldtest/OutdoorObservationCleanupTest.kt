package kr.co.hanium.dreamup.walksafe.fieldtest

import com.google.android.gms.location.LocationCallback
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import java.io.File
import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class OutdoorObservationCleanupTest {
    @Test fun oneThrowingSensorCannotSkipAnyOtherCleanupAndOnlyFailedOwnersAreRetried() {
        val events = mutableListOf<String>()
        var failSteps = true
        var qualityPending = true
        val cleanup = OutdoorObservationCleanup(
            { events += "location"; true },
            { events += "steps"; if (failSteps) throw IllegalStateException("unregister"); true },
            { events += "motion"; true },
            { events += "quality"; !qualityPending },
            { events += "orientation"; true },
        )
        assertFalse(cleanup.retry())
        assertEquals(listOf("location", "steps", "motion", "quality", "orientation"), events)
        assertEquals(setOf("steps", "quality"), cleanup.pendingSources)
        events.clear()
        failSteps = false
        qualityPending = false
        assertTrue(cleanup.retry())
        assertEquals(listOf("steps", "quality"), events)
        assertFalse(cleanup.isPending)
        assertTrue(cleanup.retry())
        assertEquals(2, events.size)
    }

    @Test fun pendingRegistrationNeedsAnotherRemovalAfterItsLateSuccess() {
        val callback = object : LocationCallback() {}
        val registration = TaskCompletionSource<Void>()
        val firstRemoval = TaskCompletionSource<Void>()
        val secondRemoval = TaskCompletionSource<Void>()
        val removed = mutableListOf<LocationCallback>()
        val release = OutdoorLocationRelease(callback, registration.task, {
            removed += it
            if (removed.size == 1) firstRemoval.task else secondRemoval.task
        }, Executor { it.run() }, {})
        assertFalse(release.retry())
        firstRemoval.setResult(null)
        assertFalse(release.retry())
        assertEquals(1, removed.size)
        registration.setResult(null)
        assertFalse(release.retry())
        assertEquals(listOf(callback, callback), removed)
        secondRemoval.setResult(null)
        assertTrue(release.retry())
        assertTrue(release.retry())
        assertEquals(2, removed.size)
    }

    @Test fun removalStartedBeforeRegistrationCompletionCannotStandInForThePostRegistrationRemoval() {
        val registration = TaskCompletionSource<Void>()
        val removals = mutableListOf<TaskCompletionSource<Void>>()
        val release = OutdoorLocationRelease(object : LocationCallback() {}, registration.task, {
            TaskCompletionSource<Void>().also { removals += it }.task
        }, Executor { it.run() }, {})
        assertFalse(release.retry())
        registration.setResult(null)
        assertFalse(release.retry())
        assertEquals(1, removals.size)
        removals[0].setResult(null)
        assertFalse(release.retry())
        assertEquals(2, removals.size)
        removals[1].setResult(null)
        assertTrue(release.retry())
    }

    @Test fun failedRemovalRetainsTheExactCallbackAndOnlySuccessfulTaskUnblocksRestart() {
        val callback = object : LocationCallback() {}
        val removed = mutableListOf<LocationCallback>()
        var fail = true
        val release = OutdoorLocationRelease(callback, Tasks.forResult(null), {
            removed += it
            if (fail) Tasks.forException(IllegalStateException("native remove")) else Tasks.forResult(null)
        }, Executor { it.run() }, {})
        assertFalse(release.retry())
        fail = false
        assertTrue(release.retry())
        assertEquals(listOf(callback, callback), removed)
    }

    @Test fun synchronousRemovalFailureRetainsTheCallbackForRetry() {
        val callback = object : LocationCallback() {}
        var calls = 0
        val release = OutdoorLocationRelease(callback, Tasks.forResult(null), {
            assertSame(callback, it)
            calls++
            if (calls == 1) throw SecurityException("permission revoked")
            Tasks.forResult(null)
        }, Executor { it.run() }, {})
        assertFalse(release.retry())
        assertTrue(release.retry())
        assertEquals(2, calls)
    }

    @Test fun actualOutdoorStopRetiresRawBeforeSensorRetriesAndTraceWaitsForRawCompletion() {
        val source = File("src/debug/java/kr/co/hanium/dreamup/walksafe/fieldtest/OutdoorPositionTestActivity.kt").readText()
        val stop = source.substringAfter("private fun stopEverything(reason: String) {")
            .substringBefore("private fun retainObservationCleanup()")
        assertTrue(stop.indexOf("collecting = false") < stop.indexOf("raw.stop"))
        assertTrue(stop.indexOf("generation++") < stop.indexOf("raw.stop"))
        assertTrue(stop.contains("raw.stop { rawStopped -> finishStoppedSession(active, raw, reason, rawStopped) }"))
        assertTrue(stop.indexOf("raw.stop") < stop.lastIndexOf("retryObservationCleanup()"))
        val finish = source.substringAfter("private fun finishStoppedSession(").substringBefore("private fun retryFinalization()")
        assertTrue(finish.contains("recorder.stop(active)"))
        assertTrue(finish.contains("if (raw?.status?.cleanupPending != true) rawRecorder = null"))
        assertTrue(source.contains("if (nativeCleanupPending)"))
        assertTrue(source.contains("quality?.cleanupPending != true"))
        assertTrue(source.contains("steps?.operationalHealth?.cleanupPending != true"))
        assertTrue(source.contains("motion?.operationalHealth?.cleanupPending != true"))
        assertTrue(source.contains("if (orientationFailed) false"))
        assertTrue(source.contains("registration.addOnCompleteListener(Executor"))
    }
}
