package kr.co.hanium.dreamup.walksafe.navigation

import android.content.Context
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.reflect.Field
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real Tracker registrations and RV callbacks; only app-owned fields are read through reflection.
 * Query evaluation may be conservative. No valid-heading, slow-registration, or field claim.
 * The same test binary uses public APIs and unchanged fields on both the 8f and 88 Tracker versions.
 */
@RunWith(AndroidJUnit4::class)
class AndroidEarthOrientationQueryClockPlatformTest {
    @Test
    fun historicalHeadingQueryCannotReregisterAHealthyNativeOrientationTracker() =
        checkQueryClock("historical", historical = true)

    @Test
    fun futureHeadingQueryCannotConsumeNativeReregistrationAttempts() =
        checkQueryClock("future", historical = false)

    @Test
    fun stalledNativeDeliveryDoesNotConsumeAnotherRegistrationWithinRecoveryGrace() {
        val case = "delivery_stall"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tracker = AndroidEarthOrientationTracker(context)
        val fields = try {
            TrackerFields()
        } catch (failure: Throwable) {
            try {
                report(case, "NATIVE_CONTROL_OR_LINKAGE_FAILED", "failure_type=${failure.javaClass.simpleName} " +
                    "detail=${failure.message}")
            } catch (reportFailure: Throwable) {
                failure.addSuppressed(reportFailure)
            }
            throw failure
        }
        // Existing production watchdog values, distinct from the observation/cleanup budgets.
        val firstEventDeadlineMs = 1_000L
        val minimumReregistrationIntervalMs = 30_000L
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val exited = CountDownLatch(1)
        val blockerFailure = AtomicReference<Throwable?>()
        var primaryFailure: Throwable? = null
        var ownedThread: HandlerThread? = null
        var blockerHandler: Handler? = null
        var blocker: Runnable? = null
        try {
            val startedAtMs = SystemClock.elapsedRealtime()
            precondition(tracker.start(), "Tracker did not register a usable native configuration")
            ownedThread = synchronized(tracker) { fields.sensorThread.get(tracker) as? HandlerThread }
            precondition(ownedThread != null, "Native Tracker callback thread was not observable after registration")
            val initial = awaitNativeProgress(tracker, fields, startedAtMs - 1L, 0L)
            precondition(initial.attempts == 0, "A prior recovery already consumed the initial registration budget")
            precondition(SystemClock.elapsedRealtime() > minimumReregistrationIntervalMs,
                "Actual uptime must already exceed the existing 30000ms recovery interval")

            val handler = Handler(requireNotNull(ownedThread).looper)
            blockerHandler = handler
            blocker = Runnable {
                entered.countDown()
                try {
                    if (!release.await(DELIVERY_WAIT_MS, TimeUnit.MILLISECONDS)) {
                        blockerFailure.compareAndSet(null, AssertionError(
                            "NATIVE_PRECONDITION_NOT_ESTABLISHED: own delivery blocker observation budget expired"))
                    }
                } catch (failure: Throwable) {
                    blockerFailure.compareAndSet(null, failure)
                    if (failure is InterruptedException) Thread.currentThread().interrupt()
                } finally {
                    exited.countDown()
                }
            }
            precondition(handler.post(requireNotNull(blocker)), "Own delivery blocker could not be posted")
            // This wait must never hold Tracker's monitor. No SensorEvent enters this Runnable.
            precondition(entered.await(DELIVERY_WAIT_MS, TimeUnit.MILLISECONDS),
                "Own native delivery thread did not enter the blocker")
            val blocked = synchronized(tracker) {
                val value = fields.snapshot(tracker)
                precondition(value.hasFreshNativeRotation(SystemClock.elapsedRealtime()) && value.attempts == 0,
                    "Fresh/current-generation real RV evidence and unused recovery budget were not established")
                value
            }
            val latestReceiptOrGraceAtMs = maxOf(blocked.graceAtMs, requireNotNull(blocked.receipts.values.maxOrNull()))
            val stallObservationDeadlineMs = SystemClock.elapsedRealtime() + DELIVERY_WAIT_MS
            while (SystemClock.elapsedRealtime() - latestReceiptOrGraceAtMs <= MAXIMUM_EVENT_GAP_MS) {
                precondition(exited.count == 1L && SystemClock.elapsedRealtime() <= stallObservationDeadlineMs,
                    "Own delivery blocker did not remain active until the existing 2000ms watchdog gap elapsed")
                SystemClock.sleep(POLL_MS)
            }

            var firstQueryStartedAtMs = 0L
            var firstQueryCompletedAtMs = 0L
            var actualQueryAtMs = 0L
            var comparisonCompletedAtMs = 0L
            var firstReason: RouteCompassHeadingReason? = null
            var actualReason: RouteCompassHeadingReason? = null
            val (first, afterActual) = synchronized(tracker) {
                precondition(exited.count == 1L && blockerFailure.get() == null && ownedThread?.isAlive == true,
                    "Own delivery blocker or native thread stopped before the query comparison")
                val before = fields.snapshot(tracker)
                precondition(blocked.hasSameResources(before),
                    "Registration resources or actual receipts changed while the delivery queue was blocked")
                firstQueryStartedAtMs = SystemClock.elapsedRealtime()
                precondition(firstQueryStartedAtMs > minimumReregistrationIntervalMs &&
                    firstQueryStartedAtMs - latestReceiptOrGraceAtMs > MAXIMUM_EVENT_GAP_MS,
                    "Actual uptime or all-stream watchdog stall prerequisite was not established")
                firstReason = tracker.latestRouteCompassHeading(0L).reason
                val recovered = fields.snapshot(tracker)
                firstQueryCompletedAtMs = SystemClock.elapsedRealtime()
                actualQueryAtMs = SystemClock.elapsedRealtime()
                actualReason = tracker.latestRouteCompassHeading(actualQueryAtMs).reason
                val compared = fields.snapshot(tracker)
                comparisonCompletedAtMs = SystemClock.elapsedRealtime()
                precondition(comparisonCompletedAtMs >= firstQueryStartedAtMs &&
                    comparisonCompletedAtMs - firstQueryStartedAtMs <= firstEventDeadlineMs,
                    "Two-query comparison exceeded the existing 1000ms first-event grace; " +
                        "slow registration/scheduling is not a query-clock regression verdict")
                precondition(exited.count == 1L && blockerFailure.get() == null &&
                    fields.sensorThread.get(tracker) === ownedThread,
                    "The same own native delivery queue did not remain blocked throughout recovery")
                recovered to compared
            }
            report(case, "NATIVE_STALL_PRECONDITION_READY", "actual_start_ms=$firstQueryStartedAtMs " +
                "gap_ms=${firstQueryStartedAtMs - latestReceiptOrGraceAtMs} before={${blocked.summary()}}")
            report(case, "STALL_RECOVERY_RESOURCE_OBSERVED", "historical_query_ms=0 " +
                "first_completed_actual_ms=$firstQueryCompletedAtMs actual_query_ms=$actualQueryAtMs " +
                "completed_actual_ms=$comparisonCompletedAtMs historical_reason=$firstReason actual_reason=$actualReason " +
                "first={${first.summary()}} second={${afterActual.summary()}}")
            precondition(first.generation == blocked.generation + 1 && first.stateGeneration == first.generation &&
                first.listener != null && first.listener !== blocked.listener && first.attempts == 1 &&
                first.registeredStreams == first.stateRegisteredStreams &&
                RouteCompassStream.ROTATION_VECTOR in first.registeredStreams,
                "First historical query did not establish one usable native re-registration; " +
                    "this normal recovery control is not the second-query clock assertion")

            // Primary counter: 8f spends a second SDK registration; 88 retains the first recovery.
            assertEquals("DELIVERY_STALL_RECOVERY_REGRESSION: actual query changed generation within recovery grace",
                first.generation, afterActual.generation)
            assertEquals("DELIVERY_STALL_RECOVERY_REGRESSION: actual query changed State generation",
                first.stateGeneration, afterActual.stateGeneration)
            assertSame("DELIVERY_STALL_RECOVERY_REGRESSION: actual query replaced the native listener",
                first.listener, afterActual.listener)
            assertEquals("DELIVERY_STALL_RECOVERY_REGRESSION: actual query consumed another recovery attempt",
                first.attempts, afterActual.attempts)
            assertEquals("DELIVERY_STALL_RECOVERY_REGRESSION: actual query changed the recovery anchor",
                first.lastAttemptAtMs, afterActual.lastAttemptAtMs)
            assertEquals("DELIVERY_STALL_RECOVERY_REGRESSION: actual query changed the registration grace",
                first.graceAtMs, afterActual.graceAtMs)
            assertEquals("DELIVERY_STALL_RECOVERY_REGRESSION: actual query changed the registration epoch",
                first.registrationAtNanos, afterActual.registrationAtNanos)
            assertEquals("DELIVERY_STALL_RECOVERY_REGRESSION: actual query changed registered streams",
                first.registeredStreams, afterActual.registeredStreams)
            assertEquals("DELIVERY_STALL_RECOVERY_REGRESSION: actual query changed State registered streams",
                first.stateRegisteredStreams, afterActual.stateRegisteredStreams)
            assertEquals("DELIVERY_STALL_RECOVERY_REGRESSION: actual query changed blocked actual receipts",
                first.receipts, afterActual.receipts)
            // Auxiliary clock facts follow the resource assertion so baseline fails on real retry use.
            assertTrue("NATIVE_RECOVERY_CLOCK_CONTROL: completion grace is outside the actual first-call interval",
                first.graceAtMs in firstQueryStartedAtMs..firstQueryCompletedAtMs)
            assertEquals("NATIVE_RECOVERY_CLOCK_CONTROL: retry and first-event grace anchors differ",
                first.graceAtMs, requireNotNull(first.lastAttemptAtMs))
            assertEquals("NATIVE_RECOVERY_CONTROL: first registration changed receipts while delivery was blocked",
                blocked.receipts, first.receipts)

            release.countDown()
            precondition(exited.await(THREAD_FINISH_WAIT_MS, TimeUnit.MILLISECONDS) && blockerFailure.get() == null,
                "Own blocker did not finish cleanly after release")
            // SDK may discard retired queued events. Only new real current-generation progress is observed.
            val continued = awaitNativeProgress(tracker, fields,
                requireNotNull(blocked.measurementAtMs), requireNotNull(blocked.rotationReceiptAtMs))
            assertEquals("NATIVE_CONTINUATION_CONTROL: generation changed during fresh native progress",
                afterActual.generation, continued.generation)
            assertSame("NATIVE_CONTINUATION_CONTROL: listener changed during fresh native progress",
                afterActual.listener, continued.listener)
            assertEquals("NATIVE_CONTINUATION_CONTROL: fresh delivery consumed another recovery attempt",
                afterActual.attempts, continued.attempts)
            report(case, "DELIVERY_STALL_RECOVERY_REGRESSION_PASSED", "continued={${continued.summary()}}")
        } catch (failure: Throwable) {
            primaryFailure = failure
            val marker = when {
                failure.message?.startsWith("DELIVERY_STALL_RECOVERY_REGRESSION:") == true ->
                    "DELIVERY_STALL_RECOVERY_ASSERTION_FAILED"
                failure.message?.startsWith("NATIVE_PRECONDITION_NOT_ESTABLISHED:") == true ->
                    "NATIVE_PRECONDITION_NOT_ESTABLISHED"
                else -> "NATIVE_CONTROL_OR_LINKAGE_FAILED"
            }
            try {
                report(case, marker, "failure_type=${failure.javaClass.simpleName} detail=${failure.message}")
            } catch (reportFailure: Throwable) {
                failure.addSuppressed(reportFailure)
            }
            throw failure
        } finally {
            // Release before any monitor acquisition, stop, or join; remove only our own Runnable.
            release.countDown()
            try {
                var cleanupFailure: Throwable? = null
                fun retainCleanupFailure(failure: Throwable) {
                    if (failure === primaryFailure) return
                    val first = cleanupFailure
                    if (first == null) cleanupFailure = failure else if (first !== failure) first.addSuppressed(failure)
                }
                try {
                    blocker?.let { blockerHandler?.removeCallbacks(it) }
                    if (entered.count == 0L && !exited.await(THREAD_FINISH_WAIT_MS, TimeUnit.MILLISECONDS)) {
                        retainCleanupFailure(AssertionError("NATIVE_CLEANUP_FAILED: own blocker did not exit"))
                    }
                    blockerFailure.get()?.let { retainCleanupFailure(it) }
                } catch (blockerCleanupFailure: Throwable) {
                    retainCleanupFailure(blockerCleanupFailure)
                }
                var listener: SensorEventListener? = null
                var thread = ownedThread
                try {
                    listener = synchronized(tracker) { fields.currentListener.get(tracker) as? SensorEventListener }
                    if (thread == null) thread = synchronized(tracker) { fields.sensorThread.get(tracker) as? HandlerThread }
                } catch (cleanupReadFailure: Throwable) {
                    retainCleanupFailure(cleanupReadFailure)
                }
                try {
                    tracker.stop()
                } catch (stopFailure: Throwable) {
                    retainCleanupFailure(stopFailure)
                    try {
                        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
                        listener?.let(manager::unregisterListener)
                    } catch (unregisterFailure: Throwable) {
                        stopFailure.addSuppressed(unregisterFailure)
                    }
                } finally {
                    try {
                        thread?.quitSafely()
                    } catch (quitFailure: Throwable) {
                        retainCleanupFailure(quitFailure)
                    }
                    try {
                        thread?.join(THREAD_FINISH_WAIT_MS)
                    } catch (joinFailure: Throwable) {
                        retainCleanupFailure(joinFailure)
                    }
                }
                cleanupFailure?.let { throw it }
                assertFalse("NATIVE_CLEANUP_FAILED: Tracker callback thread did not finish", thread?.isAlive == true)
                synchronized(tracker) {
                    assertNull("NATIVE_CLEANUP_FAILED: listener remains after stop", fields.currentListener.get(tracker))
                    assertFalse("NATIVE_CLEANUP_FAILED: Tracker remains started", fields.started.getBoolean(tracker))
                    val stopped = tracker.latestRouteCompassHeading(SystemClock.elapsedRealtime())
                    assertEquals(RouteCompassHeadingReason.ORIENTATION_MISSING, stopped.reason)
                    assertNull(stopped.observation)
                    assertNull(stopped.checkEvidence)
                }
                report(case, "NATIVE_CLEANUP_FINISHED", "blocker_released=true blocker_finished=${exited.count == 0L} " +
                    "listener_cleared=true thread_finished=${thread?.isAlive?.not()}")
            } catch (cleanupFailure: Throwable) {
                try {
                    report(case, "NATIVE_CLEANUP_FAILED", "failure_type=${cleanupFailure.javaClass.simpleName} " +
                        "detail=${cleanupFailure.message}")
                } catch (reportFailure: Throwable) {
                    cleanupFailure.addSuppressed(reportFailure)
                }
                if (primaryFailure != null) {
                    if (primaryFailure !== cleanupFailure) primaryFailure.addSuppressed(cleanupFailure)
                } else {
                    throw cleanupFailure
                }
            }
        }
    }

    private fun checkQueryClock(case: String, historical: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tracker = AndroidEarthOrientationTracker(context)
        val fields = try {
            TrackerFields()
        } catch (failure: Throwable) {
            try {
                report(case, "NATIVE_CONTROL_OR_LINKAGE_FAILED", "failure_type=${failure.javaClass.simpleName} " +
                    "detail=${failure.message}")
            } catch (reportFailure: Throwable) {
                failure.addSuppressed(reportFailure)
            }
            throw failure
        }
        var primaryFailure: Throwable? = null
        var ownedThread: HandlerThread? = null
        try {
            val startedAtMs = SystemClock.elapsedRealtime()
            precondition(tracker.start(), "Tracker did not register a usable native configuration")
            ownedThread = synchronized(tracker) { fields.sensorThread.get(tracker) as? HandlerThread }
            precondition(ownedThread != null, "Native Tracker callback thread was not observable after registration")
            awaitNativeProgress(tracker, fields, startedAtMs - 1L, 0L)

            // Reflection is prepared before entry. No waits or instrumentation output hold this lock.
            val observation = synchronized(tracker) {
                val controlNowMs = SystemClock.elapsedRealtime()
                val beforeControl = fields.snapshot(tracker)
                precondition(beforeControl.hasFreshNativeRotation(controlNowMs),
                    "Fresh/current-generation native RV prerequisite expired before control")
                val control = tracker.latestRouteCompassHeading(controlNowMs)
                val afterControl = fields.snapshot(tracker)
                precondition(beforeControl.hasSameResources(afterControl),
                    "Actual-time control changed registration resources; query comparison not established")
                precondition(control.checkEvidence?.source == RouteFacingSource.ROTATION_VECTOR &&
                    control.checkEvidence?.observedAtElapsedRealtimeMs == afterControl.measurementAtMs,
                    "Actual-time control did not expose the accepted native RV measurement; reason=${control.reason}")

                val actualNowMs = SystemClock.elapsedRealtime()
                val beforeQuery = fields.snapshot(tracker)
                precondition(beforeQuery.hasFreshNativeRotation(actualNowMs),
                    "Fresh/current-generation native RV prerequisite expired before query")
                precondition(beforeQuery.graceAtMs > 0L && beforeQuery.receipts.values.all { it > 0L },
                    "Historical zero must precede the registration grace and every recorded receipt")
                precondition(beforeQuery.lastAttemptAtMs?.let { it <= actualNowMs } != false,
                    "Existing recovery anchor is later than the actual observation clock")
                val queryAtMs = if (historical) 0L else actualNowMs + FUTURE_QUERY_OFFSET_MS
                val result = tracker.latestRouteCompassHeading(queryAtMs)
                val completedAtMs = SystemClock.elapsedRealtime()
                precondition(completedAtMs >= actualNowMs &&
                    completedAtMs - requireNotNull(beforeQuery.rotationReceiptAtMs) <= MAXIMUM_EVENT_GAP_MS,
                    "Query comparison exceeded the existing actual-receipt watchdog deadline; " +
                        "resource changes cannot be attributed only to the query clock")
                QueryObservation(actualNowMs, completedAtMs, queryAtMs, control.reason, result.reason,
                    beforeQuery, fields.snapshot(tracker))
            }
            report(case, "NATIVE_PRECONDITION_READY", "actual_ms=${observation.actualAtMs} " +
                "control_reason=${observation.controlReason} ${observation.before.summary()}")
            report(case, "QUERY_RESOURCE_OBSERVED", "query_ms=${observation.queryAtMs} " +
                "completed_actual_ms=${observation.completedAtMs} " +
                "query_reason=${observation.queryReason} before={${observation.before.summary()}} " +
                "after={${observation.after.summary()}}")
            assertEquals("QUERY_CLOCK_REGRESSION: query changed registration generation",
                observation.before.generation, observation.after.generation)
            assertEquals("QUERY_CLOCK_REGRESSION: query changed State generation",
                observation.before.stateGeneration, observation.after.stateGeneration)
            assertSame("QUERY_CLOCK_REGRESSION: query replaced the registered native listener",
                observation.before.listener, observation.after.listener)
            assertEquals("QUERY_CLOCK_REGRESSION: query consumed a re-registration attempt",
                observation.before.attempts, observation.after.attempts)
            assertEquals("QUERY_CLOCK_REGRESSION: query changed the recovery anchor",
                observation.before.lastAttemptAtMs, observation.after.lastAttemptAtMs)
            assertEquals("QUERY_CLOCK_REGRESSION: query changed the registration grace anchor",
                observation.before.graceAtMs, observation.after.graceAtMs)
            assertEquals("QUERY_CLOCK_REGRESSION: query changed the registration epoch",
                observation.before.registrationAtNanos, observation.after.registrationAtNanos)
            assertEquals("QUERY_CLOCK_REGRESSION: query changed registered streams",
                observation.before.registeredStreams, observation.after.registeredStreams)
            assertEquals("QUERY_CLOCK_REGRESSION: query changed actual callback receipts",
                observation.before.receipts, observation.after.receipts)

            // Leave the monitor so new real native callbacks can enter; no event is retained/replayed.
            val continued = awaitNativeProgress(tracker, fields,
                requireNotNull(observation.before.measurementAtMs),
                requireNotNull(observation.before.rotationReceiptAtMs))
            assertEquals("NATIVE_CONTINUATION_CONTROL: generation changed during fresh native progress",
                observation.after.generation, continued.generation)
            assertSame("NATIVE_CONTINUATION_CONTROL: listener changed during fresh native progress",
                observation.after.listener, continued.listener)
            assertEquals("NATIVE_CONTINUATION_CONTROL: fresh native progress consumed a recovery attempt",
                observation.after.attempts, continued.attempts)
            report(case, "QUERY_CLOCK_REGRESSION_PASSED", "continued={${continued.summary()}}")
        } catch (failure: Throwable) {
            primaryFailure = failure
            val marker = when {
                failure.message?.startsWith("QUERY_CLOCK_REGRESSION:") == true -> "QUERY_CLOCK_REGRESSION_ASSERTION_FAILED"
                failure.message?.startsWith("NATIVE_PRECONDITION_NOT_ESTABLISHED:") == true -> "NATIVE_PRECONDITION_NOT_ESTABLISHED"
                else -> "NATIVE_CONTROL_OR_LINKAGE_FAILED"
            }
            try {
                report(case, marker, "failure_type=${failure.javaClass.simpleName} detail=${failure.message}")
            } catch (reportFailure: Throwable) {
                failure.addSuppressed(reportFailure)
            }
            throw failure
        } finally {
            try {
                val listener = synchronized(tracker) { fields.currentListener.get(tracker) as? SensorEventListener }
                val thread = ownedThread ?: synchronized(tracker) { fields.sensorThread.get(tracker) as? HandlerThread }
                var cleanupFailure: Throwable? = null
                fun retainCleanupFailure(failure: Throwable) {
                    val first = cleanupFailure
                    if (first == null) cleanupFailure = failure else first.addSuppressed(failure)
                }
                try {
                    tracker.stop()
                } catch (stopFailure: Throwable) {
                    retainCleanupFailure(stopFailure)
                    // Only this Tracker's exact listener is eligible for fallback unregister.
                    try {
                        val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
                        listener?.let(manager::unregisterListener)
                    } catch (unregisterFailure: Throwable) {
                        stopFailure.addSuppressed(unregisterFailure)
                    }
                } finally {
                    try {
                        thread?.quitSafely()
                    } catch (quitFailure: Throwable) {
                        retainCleanupFailure(quitFailure)
                    }
                    try {
                        thread?.join(THREAD_FINISH_WAIT_MS)
                    } catch (joinFailure: Throwable) {
                        retainCleanupFailure(joinFailure)
                    }
                }
                cleanupFailure?.let { throw it }
                assertFalse("NATIVE_CLEANUP_FAILED: Tracker callback thread did not finish", thread?.isAlive == true)
                synchronized(tracker) {
                    assertNull("NATIVE_CLEANUP_FAILED: listener remains after stop", fields.currentListener.get(tracker))
                    assertFalse("NATIVE_CLEANUP_FAILED: Tracker remains started", fields.started.getBoolean(tracker))
                    val stopped = tracker.latestRouteCompassHeading(SystemClock.elapsedRealtime())
                    assertEquals(RouteCompassHeadingReason.ORIENTATION_MISSING, stopped.reason)
                    assertNull(stopped.observation)
                    assertNull(stopped.checkEvidence)
                }
                report(case, "NATIVE_CLEANUP_FINISHED", "listener_cleared=true thread_observed=${thread != null} " +
                    "thread_finished=${thread?.isAlive?.not()}")
            } catch (cleanupFailure: Throwable) {
                try {
                    report(case, "NATIVE_CLEANUP_FAILED", "failure_type=${cleanupFailure.javaClass.simpleName} " +
                        "detail=${cleanupFailure.message}")
                } catch (reportFailure: Throwable) {
                    cleanupFailure.addSuppressed(reportFailure)
                }
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(cleanupFailure)
                } else {
                    throw cleanupFailure
                }
            }
        }
    }

    private fun awaitNativeProgress(
        tracker: AndroidEarthOrientationTracker,
        fields: TrackerFields,
        afterMeasurementAtMs: Long,
        afterReceiptAtMs: Long,
    ): Snapshot {
        val deadlineMs = SystemClock.elapsedRealtime() + DELIVERY_WAIT_MS
        var last: Snapshot? = null
        while (SystemClock.elapsedRealtime() <= deadlineMs) {
            val snapshot = synchronized(tracker) { fields.snapshot(tracker) }
            last = snapshot
            val nowMs = SystemClock.elapsedRealtime()
            if (snapshot.hasFreshNativeRotation(nowMs) &&
                snapshot.measurementAtMs!! > afterMeasurementAtMs &&
                snapshot.rotationReceiptAtMs!! > afterReceiptAtMs
            ) return snapshot
            SystemClock.sleep(POLL_MS)
        }
        throw AssertionError("NATIVE_PRECONDITION_NOT_ESTABLISHED: no new fresh/current-generation " +
            "real RV measurement and receipt within ${DELIVERY_WAIT_MS}ms; ${last?.summary()}. " +
            "Check native RV availability/registration/foreground delivery/admission/recovery budget; " +
            "this failure is not a query-clock regression verdict and is not a skipped pass.")
    }

    private fun precondition(condition: Boolean, detail: String) {
        if (!condition) throw AssertionError("NATIVE_PRECONDITION_NOT_ESTABLISHED: $detail")
    }

    private fun report(case: String, marker: String, detail: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
            putString("stream", "EARTH_QUERY_CLOCK case=$case marker=$marker $detail\n")
        })
    }

    private data class QueryObservation(
        val actualAtMs: Long,
        val completedAtMs: Long,
        val queryAtMs: Long,
        val controlReason: RouteCompassHeadingReason,
        val queryReason: RouteCompassHeadingReason,
        val before: Snapshot,
        val after: Snapshot,
    )

    private data class Snapshot(
        val generation: Int,
        val stateGeneration: Int,
        val trackerStarted: Boolean,
        val stateStarted: Boolean,
        val listener: SensorEventListener?,
        val registeredStreams: Set<RouteCompassStream>,
        val stateRegisteredStreams: Set<RouteCompassStream>,
        val registrationAtNanos: Long,
        val graceAtMs: Long,
        val attempts: Int,
        val lastAttemptAtMs: Long?,
        val firstRotationReceiptAtMs: Long?,
        val receipts: Map<RouteCompassStream, Long>,
        val measurementAtMs: Long?,
    ) {
        val rotationReceiptAtMs: Long? get() = receipts[RouteCompassStream.ROTATION_VECTOR]

        fun hasFreshNativeRotation(nowMs: Long): Boolean {
            val receipt = rotationReceiptAtMs ?: return false
            val first = firstRotationReceiptAtMs ?: return false
            val measured = measurementAtMs ?: return false
            return trackerStarted && stateStarted && listener != null && generation == stateGeneration &&
                RouteCompassStream.ROTATION_VECTOR in registeredStreams && registeredStreams == stateRegisteredStreams &&
                registrationAtNanos > 0L && measured > registrationAtNanos / 1_000_000L &&
                first > 0L && receipt >= first && nowMs >= receipt && nowMs - receipt <= FRESHNESS_MS &&
                measured > 0L && measured <= receipt && nowMs >= measured && nowMs - measured <= FRESHNESS_MS &&
                attempts in 0 until MAXIMUM_REREGISTRATIONS_PER_START
        }

        fun hasSameResources(other: Snapshot): Boolean = generation == other.generation &&
            stateGeneration == other.stateGeneration && listener === other.listener && attempts == other.attempts &&
            graceAtMs == other.graceAtMs && lastAttemptAtMs == other.lastAttemptAtMs &&
            registrationAtNanos == other.registrationAtNanos && registeredStreams == other.registeredStreams &&
            stateRegisteredStreams == other.stateRegisteredStreams && receipts == other.receipts

        fun summary(): String = "generation=$generation state_generation=$stateGeneration " +
            "listener_id=${listener?.let(System::identityHashCode)} started=$trackerStarted state_started=$stateStarted " +
            "registered=$registeredStreams state_registered=$stateRegisteredStreams epoch_ns=$registrationAtNanos " +
            "grace_ms=$graceAtMs attempts=$attempts attempt_ms=$lastAttemptAtMs " +
            "rv_first_receipt_ms=$firstRotationReceiptAtMs rv_last_receipt_ms=$rotationReceiptAtMs " +
            "rv_measurement_ms=$measurementAtMs"
    }

    /** Cached read-only handles to unchanged app-owned fields, never to SDK-private data. */
    private class TrackerFields {
        private fun field(owner: Class<*>, name: String): Field = owner.getDeclaredField(name).apply { isAccessible = true }
        private val trackerClass = AndroidEarthOrientationTracker::class.java
        private val state = field(trackerClass, "routeCompassState")
        private val stateClass = state.type
        private val generation = field(trackerClass, "registrationGeneration")
        val currentListener = field(trackerClass, "currentListener")
        val sensorThread = field(trackerClass, "sensorThread")
        val started = field(trackerClass, "started")
        private val registered = field(trackerClass, "registeredRouteStreams")
        private val registrationAt = field(trackerClass, "registrationStartedAtNanos")
        private val stateGeneration = field(stateClass, "generation")
        private val stateStarted = field(stateClass, "started")
        private val stateRegistered = field(stateClass, "registeredStreams")
        private val grace = field(stateClass, "receiptGraceStartedAtMs")
        private val attempts = field(stateClass, "reregistrationCount")
        private val lastAttempt = field(stateClass, "lastReregistrationAtMs")
        private val receipts = field(stateClass, "lastReceiptAtMs")
        private val firstReceipts = field(stateClass, "firstReceiptAtMs")
        private val measurement = field(stateClass, "latestRotationEventAtMs")

        @Suppress("UNCHECKED_CAST")
        fun snapshot(tracker: AndroidEarthOrientationTracker): Snapshot {
            val value = state.get(tracker)
            return Snapshot(generation.getInt(tracker), stateGeneration.getInt(value), started.getBoolean(tracker),
                stateStarted.getBoolean(value), currentListener.get(tracker) as? SensorEventListener,
                (registered.get(tracker) as Set<RouteCompassStream>).toSet(),
                (stateRegistered.get(value) as Set<RouteCompassStream>).toSet(),
                registrationAt.getLong(tracker), grace.getLong(value), attempts.getInt(value),
                lastAttempt.get(value) as Long?,
                (firstReceipts.get(value) as Map<RouteCompassStream, Long>)[RouteCompassStream.ROTATION_VECTOR],
                (receipts.get(value) as Map<RouteCompassStream, Long>).toMap(), measurement.get(value) as Long?)
        }
    }

    private companion object {
        // Existing production freshness/watchdog values; other values are observation/cleanup budgets.
        const val FRESHNESS_MS = 500L
        const val MAXIMUM_EVENT_GAP_MS = 2_000L
        const val MAXIMUM_REREGISTRATIONS_PER_START = 2
        const val FUTURE_QUERY_OFFSET_MS = 30_001L
        const val DELIVERY_WAIT_MS = 8_000L
        const val POLL_MS = 20L
        const val THREAD_FINISH_WAIT_MS = 2_000L
    }
}
