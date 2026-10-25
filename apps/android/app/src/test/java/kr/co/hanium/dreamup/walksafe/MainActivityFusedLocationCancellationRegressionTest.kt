package kr.co.hanium.dreamup.walksafe

import com.google.android.gms.location.LocationCallback
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import java.util.concurrent.Executor
import kr.co.hanium.dreamup.walksafe.device.WalkRuntimeStopCleanupCheckpoints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Actual Main cancellation registry delegate and Google Tasks; no SDK or OS registration. */
class MainActivityFusedLocationCancellationRegressionTest {
    @Test
    fun pendingRemovalIsNotAcknowledgedAndSameCallbackDoesNotSubmitTwice() {
        val main = MainActivity()
        val callback = object : LocationCallback() {}
        val completion = TaskCompletionSource<Void>()
        val submitted = mutableListOf<LocationCallback>()
        val remove = { target: LocationCallback -> submitted += target; completion.task }

        val first = main.cancelFusedLocationUpdates(callback, remove, DIRECT_EXECUTOR)
        val repeated = main.cancelFusedLocationUpdates(callback, remove, DIRECT_EXECUTOR)

        assertFalse(completion.task.isComplete)
        submitted.forEach { assertSame(callback, it) }
        assertTrue(
            "Pending removal must return false twice and submit once: first=$first repeated=$repeated submissions=${submitted.size}",
            !first && !repeated && submitted.size == 1,
        )
    }

    @Test
    fun lateTaskFailureCannotBeAcknowledgedByTheNextExplicitCleanup() {
        val main = MainActivity()
        val callback = object : LocationCallback() {}
        val completion = TaskCompletionSource<Void>()
        val submitted = mutableListOf<LocationCallback>()
        val remove = { target: LocationCallback -> submitted += target; completion.task }
        val first = main.cancelFusedLocationUpdates(callback, remove, DIRECT_EXECUTOR)
        assertFalse(completion.task.isComplete)
        val originalFailure = IllegalStateException("fused native removal failed asynchronously")

        completion.setException(originalFailure)
        assertTrue(completion.task.isComplete)
        assertFalse(completion.task.isSuccessful)
        assertSame(originalFailure, completion.task.exception)
        val afterFailure = main.cancelFusedLocationUpdates(callback, remove, DIRECT_EXECUTOR)

        // Check the observed late result first: an initial pending-result failure must not hide it.
        assertFalse("The failed task must not become cleanup success; initial result was $first", afterFailure)
        assertFalse("The earlier pending task was not an acknowledgement either", first)
        assertTrue(submitted.isNotEmpty())
        submitted.forEach { assertSame(callback, it) }
    }

    @Test
    fun cancelledGoogleTaskDoesNotAcknowledgeNativeRemoval() {
        val main = MainActivity()
        val callback = object : LocationCallback() {}
        // forCanceled<TResult>() is present in the app's actual play-services-tasks classes.jar.
        val cancelled = Tasks.forCanceled<Void>()
        val submitted = mutableListOf<LocationCallback>()
        assertTrue(cancelled.isComplete)
        assertTrue(cancelled.isCanceled)
        assertFalse(cancelled.isSuccessful)

        val acknowledged = main.cancelFusedLocationUpdates(
            callback, { target -> submitted += target; cancelled }, DIRECT_EXECUTOR,
        )

        assertEquals(1, submitted.size)
        assertSame(callback, submitted.single())
        assertFalse("A cancelled Task is not successful callback removal", acknowledged)
    }

    @Test
    fun synchronousRemoverRuntimeExceptionReturnsFalse() {
        val main = MainActivity()
        val callback = object : LocationCallback() {}
        val submitted = mutableListOf<LocationCallback>()
        val originalFailure = IllegalStateException("fused native removal threw synchronously")

        // No test-side catch: an unexpected exception or Android mock failure remains a test error.
        val acknowledged = main.cancelFusedLocationUpdates(
            callback, { target -> submitted += target; throw originalFailure }, DIRECT_EXECUTOR,
        )

        assertEquals(1, submitted.size)
        assertSame(callback, submitted.single())
        assertFalse("Catching the original RuntimeException does not acknowledge cleanup", acknowledged)
    }

    @Test
    fun alreadyCompletedSuccessfulGoogleTaskAcknowledgesNativeRemoval() {
        val main = MainActivity()
        val callback = object : LocationCallback() {}
        val completion = TaskCompletionSource<Void>()
        val submitted = mutableListOf<LocationCallback>()
        completion.setResult(null)
        assertTrue(completion.task.isComplete)
        assertTrue(completion.task.isSuccessful)
        assertFalse(completion.task.isCanceled)

        val acknowledged = main.cancelFusedLocationUpdates(
            callback, { target -> submitted += target; completion.task }, DIRECT_EXECUTOR,
        )

        assertEquals(1, submitted.size)
        assertSame(callback, submitted.single())
        assertTrue(acknowledged)
    }

    @Test
    fun delayedTaskSuccessNotifiesItsAwaiterOnceWithoutAnotherRemoval() {
        val main = MainActivity()
        val callback = object : LocationCallback() {}
        val completion = TaskCompletionSource<Void>()
        val submitted = mutableListOf<LocationCallback>()
        val results = mutableListOf<Boolean>()

        assertFalse(main.cancelFusedLocationUpdates(
            callback, { target -> submitted += target; completion.task }, DIRECT_EXECUTOR,
        ))
        main.awaitFusedLocationCancellation { results += it }
        assertTrue(results.isEmpty())

        completion.setResult(null)

        assertEquals(listOf(true), results)
        assertFalse(completion.trySetResult(null))
        assertTrue(main.retryPendingFusedLocationCancellations())
        assertEquals(listOf(true), results)
        assertEquals(1, submitted.size)
        assertSame(callback, submitted.single())
        assertNull(main.fusedLocationCleanupFailure)
    }

    @Test
    fun lateFailureNeedsExplicitRetryUsingTheOriginalRemoverAndNeverRetriesAutomatically() {
        val main = MainActivity()
        val callback = object : LocationCallback() {}
        val failedAttempt = TaskCompletionSource<Void>()
        val retryAttempt = TaskCompletionSource<Void>()
        val submitted = mutableListOf<LocationCallback>()
        val firstResults = mutableListOf<Boolean>()
        val retryResults = mutableListOf<Boolean>()
        val originalFailure = IllegalStateException("late native cancellation failure")
        val originalRemover = { target: LocationCallback ->
            submitted += target
            if (submitted.size == 1) failedAttempt.task else retryAttempt.task
        }

        assertFalse(main.cancelFusedLocationUpdates(callback, originalRemover, DIRECT_EXECUTOR))
        main.awaitFusedLocationCancellation { firstResults += it }
        failedAttempt.setException(originalFailure)

        assertEquals(listOf(false), firstResults)
        assertSame(originalFailure, main.fusedLocationCleanupFailure)
        assertEquals("Failure must not automatically call the native remover again", 1, submitted.size)
        assertFalse(main.retryPendingFusedLocationCancellations())
        assertEquals(2, submitted.size)
        submitted.forEach { assertSame(callback, it) }
        main.awaitFusedLocationCancellation { retryResults += it }
        assertTrue(retryResults.isEmpty())

        retryAttempt.setResult(null)

        assertEquals(listOf(true), retryResults)
        assertEquals(listOf(false), firstResults)
        assertNull(main.fusedLocationCleanupFailure)
        assertTrue(main.retryPendingFusedLocationCancellations())
        assertEquals(2, submitted.size)
    }

    @Test
    fun queuedCompletionOfOldCallbackCannotDiscardANewerPendingCallback() {
        val main = MainActivity()
        val oldCallback = object : LocationCallback() {}
        val newCallback = object : LocationCallback() {}
        val oldTask = TaskCompletionSource<Void>()
        val newTask = TaskCompletionSource<Void>()
        val executor = QueuedExecutor()
        val oldSubmissions = mutableListOf<LocationCallback>()
        val newSubmissions = mutableListOf<LocationCallback>()
        val newRemover = { target: LocationCallback -> newSubmissions += target; newTask.task }
        val results = mutableListOf<Boolean>()

        assertFalse(main.cancelFusedLocationUpdates(
            oldCallback, { target -> oldSubmissions += target; oldTask.task }, executor,
        ))
        oldTask.setResult(null)
        assertTrue("The real Task's completion must be waiting on the supplied executor", executor.hasPending())
        assertFalse(main.cancelFusedLocationUpdates(newCallback, newRemover, executor))
        main.awaitFusedLocationCancellation { results += it }

        executor.drain()

        assertTrue("Old success cannot acknowledge the newer pending task", results.isEmpty())
        assertFalse(main.cancelFusedLocationUpdates(newCallback, newRemover, executor))
        assertEquals(1, newSubmissions.size)
        assertSame(newCallback, newSubmissions.single())
        assertEquals(1, oldSubmissions.size)
        assertSame(oldCallback, oldSubmissions.single())
        newTask.setResult(null)
        assertTrue(results.isEmpty())
        executor.drain()
        assertEquals(listOf(true), results)
    }

    @Test
    fun synchronousNativeFailurePreservesItsOriginalCauseAndDoesNotAutomaticallyRetry() {
        val main = MainActivity()
        val callback = object : LocationCallback() {}
        val originalFailure = IllegalStateException("original synchronous removal failure")
        val submitted = mutableListOf<LocationCallback>()
        val results = mutableListOf<Boolean>()

        assertFalse(main.cancelFusedLocationUpdates(
            callback, { target -> submitted += target; throw originalFailure }, DIRECT_EXECUTOR,
        ))
        assertSame(originalFailure, main.fusedLocationCleanupFailure)
        main.awaitFusedLocationCancellation { results += it }

        assertEquals(listOf(false), results)
        assertSame(originalFailure, main.fusedLocationCleanupFailure)
        assertEquals(1, submitted.size)
        assertSame(callback, submitted.single())
    }

    @Test
    fun locationStopCheckpointRemainsInProgressUntilTheRealTaskSucceeds() {
        val main = MainActivity()
        val callback = object : LocationCallback() {}
        val completion = TaskCompletionSource<Void>()
        val checkpoints = WalkRuntimeStopCleanupCheckpoints()
        val results = mutableListOf<Boolean>()
        var tailCalls = 0
        assertFalse(main.cancelFusedLocationUpdates(callback, { completion.task }, DIRECT_EXECUTOR))

        assertTrue(main.runLocationStopCleanupCheckpoint(
            checkpoints, onResult = { results += it }, stopTail = { tailCalls += 1; true },
        ))

        assertEquals(1, tailCalls)
        assertTrue(results.isEmpty())
        assertFalse(checkpoints.isComplete(LOCATION_STOP_KEY))
        assertFalse(checkpoints.isUncertain(LOCATION_STOP_KEY))
        assertFalse("The existing checkpoint owns the IN_PROGRESS stage", checkpoints.beginAsync(LOCATION_STOP_KEY))
        completion.setResult(null)
        assertEquals(listOf(true), results)
        assertTrue(checkpoints.isComplete(LOCATION_STOP_KEY))
        assertFalse(checkpoints.isUncertain(LOCATION_STOP_KEY))
        assertEquals(1, tailCalls)
    }

    @Test
    fun locationStopCheckpointBecomesUncertainWhenTheRealTaskFailsLate() {
        val main = MainActivity()
        val callback = object : LocationCallback() {}
        val completion = TaskCompletionSource<Void>()
        val checkpoints = WalkRuntimeStopCleanupCheckpoints()
        val results = mutableListOf<Boolean>()
        val originalFailure = IllegalStateException("checkpoint native cancellation failure")
        var tailCalls = 0
        assertFalse(main.cancelFusedLocationUpdates(callback, { completion.task }, DIRECT_EXECUTOR))

        assertTrue(main.runLocationStopCleanupCheckpoint(
            checkpoints, onResult = { results += it }, stopTail = { tailCalls += 1; true },
        ))

        assertTrue(results.isEmpty())
        assertFalse(checkpoints.isComplete(LOCATION_STOP_KEY))
        assertFalse(checkpoints.isUncertain(LOCATION_STOP_KEY))
        assertFalse(checkpoints.beginAsync(LOCATION_STOP_KEY))
        completion.setException(originalFailure)
        assertEquals(listOf(false), results)
        assertFalse(checkpoints.isComplete(LOCATION_STOP_KEY))
        assertTrue(checkpoints.isUncertain(LOCATION_STOP_KEY))
        assertSame(originalFailure, main.fusedLocationCleanupFailure)
        assertEquals(1, tailCalls)
    }

    @Test
    fun locationStopCheckpointCompletesImmediatelyAfterSuccessfulTailWithoutNativeTasks() {
        val main = MainActivity()
        val checkpoints = WalkRuntimeStopCleanupCheckpoints()
        val results = mutableListOf<Boolean>()
        var tailCalls = 0

        assertTrue(main.runLocationStopCleanupCheckpoint(
            checkpoints, onResult = { results += it }, stopTail = { tailCalls += 1; true },
        ))

        assertEquals(1, tailCalls)
        assertEquals(listOf(true), results)
        assertTrue(checkpoints.isComplete(LOCATION_STOP_KEY))
        assertFalse(checkpoints.isUncertain(LOCATION_STOP_KEY))
        assertNull(main.fusedLocationCleanupFailure)
    }

    @Test
    fun failedSynchronousTailCannotBeOverriddenByLateNativeSuccessOrReplayedByTheCheckpoint() {
        val main = MainActivity()
        val callback = object : LocationCallback() {}
        val completion = TaskCompletionSource<Void>()
        val checkpoints = WalkRuntimeStopCleanupCheckpoints()
        val submitted = mutableListOf<LocationCallback>()
        val results = mutableListOf<Boolean>()
        val repeatedResults = mutableListOf<Boolean>()
        var tailCalls = 0
        val failedTail: () -> Boolean = {
            tailCalls += 1
            main.cancelFusedLocationUpdates(
                callback, { target -> submitted += target; completion.task }, DIRECT_EXECUTOR,
            )
            false
        }

        assertFalse(main.runLocationStopCleanupCheckpoint(
            checkpoints, onResult = { results += it }, stopTail = failedTail,
        ))
        assertFalse(completion.task.isComplete)
        assertNull(main.fusedLocationCleanupFailure)
        assertTrue(results.isEmpty())
        assertFalse(checkpoints.isComplete(LOCATION_STOP_KEY))
        assertFalse(checkpoints.isUncertain(LOCATION_STOP_KEY))

        completion.setResult(null)

        assertTrue(completion.task.isSuccessful)
        assertEquals(listOf(false), results)
        assertFalse(checkpoints.isComplete(LOCATION_STOP_KEY))
        assertTrue(checkpoints.isUncertain(LOCATION_STOP_KEY))
        assertFalse(completion.trySetResult(null))
        assertEquals(listOf(false), results)
        assertFalse(main.runLocationStopCleanupCheckpoint(
            checkpoints, onResult = { repeatedResults += it }, stopTail = failedTail,
        ))
        assertEquals(listOf(false), repeatedResults)
        assertEquals("The original asynchronous completion stays single", listOf(false), results)
        assertEquals("An uncertain checkpoint must not replay the synchronous tail", 1, tailCalls)
        assertEquals("Redelivery must not submit native removal again", 1, submitted.size)
        assertSame(callback, submitted.single())
        assertFalse(checkpoints.isComplete(LOCATION_STOP_KEY))
        assertTrue(checkpoints.isUncertain(LOCATION_STOP_KEY))
    }

    @Test
    fun automaticObservationKeepsAFailedOwnerBlockedWithoutRetryingItsNativeRemoval() {
        val main = MainActivity()
        val callback = object : LocationCallback() {}
        val completion = TaskCompletionSource<Void>()
        val submitted = mutableListOf<LocationCallback>()
        val originalFailure = IllegalStateException("failed owner awaiting explicit cleanup")
        assertFalse(main.cancelFusedLocationUpdates(
            callback, { target -> submitted += target; completion.task }, DIRECT_EXECUTOR,
        ))
        completion.setException(originalFailure)

        repeat(3) {
            assertFalse("Automatic observation must keep new registration blocked", main.observePendingFusedLocationCancellations())
            assertSame(originalFailure, main.fusedLocationCleanupFailure)
            assertEquals("Observation must not resubmit removal for a failed owner", 1, submitted.size)
        }
        assertSame(callback, submitted.single())
    }

    @Test
    fun retainedFailureDuringExplicitRetryCannotFailTheSuccessfulLocationStopCheckpoint() {
        val main = MainActivity()
        val callback = object : LocationCallback() {}
        val firstTask = TaskCompletionSource<Void>()
        val retryTask = TaskCompletionSource<Void>()
        val submitted = mutableListOf<LocationCallback>()
        val originalFailure = IllegalStateException("diagnostic cause retained during pending retry")
        val checkpoints = WalkRuntimeStopCleanupCheckpoints()
        val results = mutableListOf<Boolean>()
        var tailCalls = 0
        val originalRemover = { target: LocationCallback ->
            submitted += target
            if (submitted.size == 1) firstTask.task else retryTask.task
        }
        assertFalse(main.cancelFusedLocationUpdates(callback, originalRemover, DIRECT_EXECUTOR))
        firstTask.setException(originalFailure)
        assertFalse(main.retryPendingFusedLocationCancellations())
        assertSame(originalFailure, main.fusedLocationCleanupFailure)
        assertEquals(2, submitted.size)

        assertTrue(main.runLocationStopCleanupCheckpoint(
            checkpoints, onResult = { results += it }, stopTail = { tailCalls += 1; true },
        ))
        assertTrue(results.isEmpty())
        assertFalse(checkpoints.isComplete(LOCATION_STOP_KEY))
        assertFalse(checkpoints.isUncertain(LOCATION_STOP_KEY))
        assertSame(originalFailure, main.fusedLocationCleanupFailure)
        retryTask.setResult(null)

        assertEquals(listOf(true), results)
        assertTrue(checkpoints.isComplete(LOCATION_STOP_KEY))
        assertFalse(checkpoints.isUncertain(LOCATION_STOP_KEY))
        assertNull(main.fusedLocationCleanupFailure)
        assertEquals(1, tailCalls)
        assertEquals(2, submitted.size)
        submitted.forEach { assertSame(callback, it) }
    }

    @Test
    fun reentrantCheckpointDriverWithNoNativeOwnersWaitsUntilTheOriginalTailFinishes() {
        val main = MainActivity()
        val checkpoints = WalkRuntimeStopCleanupCheckpoints()
        val firstResults = mutableListOf<Boolean>()
        val secondResults = mutableListOf<Boolean>()
        var firstTailCalls = 0
        var secondTailCalls = 0
        var secondDriverAccepted: Boolean? = null
        var firstEarlyCallbacks = -1
        var secondEarlyCallbacks = -1

        assertTrue(main.runLocationStopCleanupCheckpoint(
            checkpoints,
            onResult = { firstResults += it },
            stopTail = {
                firstTailCalls += 1
                secondDriverAccepted = main.runLocationStopCleanupCheckpoint(
                    checkpoints,
                    onResult = { secondResults += it },
                    stopTail = { secondTailCalls += 1; true },
                )
                firstEarlyCallbacks = firstResults.size
                secondEarlyCallbacks = secondResults.size
                true
            },
        ))

        assertEquals(true, secondDriverAccepted)
        assertEquals(0, firstEarlyCallbacks)
        assertEquals(0, secondEarlyCallbacks)
        assertEquals(1, firstTailCalls)
        assertEquals(0, secondTailCalls)
        assertEquals(listOf(true), firstResults)
        assertEquals(listOf(true), secondResults)
        assertTrue(checkpoints.isComplete(LOCATION_STOP_KEY))
        assertFalse(checkpoints.isUncertain(LOCATION_STOP_KEY))
    }

    @Test
    fun sourceOnlyStopChecksTheCancellationHelperAndPreservesGnssMotionAndPositionCleanupTail() {
        val owned = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/PositioningSessionController.kt")
        fun method(name: String, occurrence: Int = 0) = MainActivityRefactorSource.member(owned, name, occurrence)
        val source = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt")
        assertTrue(method("stopLocationUpdates").contains("stopLocationUpdatesInternal(stopFieldSession, retryRetained = true)"))
        val stop = method("stopLocationUpdatesInternal")
        val registeredCallback = ReportStaticSourceInspector.blockAfter(stop, "callback?.let")
        assertTrue(registeredCallback.contains("attempt {"))
        assertTrue(registeredCallback.contains("fusedRemoved = cancelFusedLocationUpdates("))
        assertTrue(registeredCallback.contains("registeredCallback = registeredCallback"))
        assertTrue(stop.contains("val removeUpdates = sources.captureRemoval()"))
        assertTrue(registeredCallback.contains("remove = removeUpdates"))
        assertTrue(registeredCallback.contains("completionExecutor = sources.completionExecutor"))
        val wire = MainActivityRefactorSource.composition(source, "positioningSessionController")
        assertTrue(wire.contains("val sources = positions.sources()"))
        assertTrue(wire.contains("PositioningSessionController(run, scope, sources, mounting,"))
        val assembly = MainActivityRefactorSource.factory(source, "createNavigationSessions")
        assertTrue(assembly.contains("positions = PositioningAssemblyBindings("))
        assertTrue(assembly.contains("sources = { createNavigationAndroidSources() }"))
        val androidWire = MainActivityRefactorSource.factory(source, "createNavigationAndroidSources")
        assertTrue(androidWire.contains("AndroidNavigationSources("))
        assertTrue(androidWire.contains("fusedAvailableNow = { ::fusedLocationClient.isInitialized }, fusedClient = { fusedLocationClient }"))
        assertTrue(androidWire.contains("runOnUiThread = { action -> runOnUiThread(action) }"))
        val androidSources = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/session/AndroidNavigationSources.kt")
        val removal = MainActivityRefactorSource.member(androidSources, "captureRemoval")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(removal,
            "val client = fusedClient()", "return { client.removeLocationUpdates(it) }"))
        assertTrue(androidSources.contains("override val completionExecutor get() = Executor { runOnUiThread(it) }"))
        val retained = ReportStaticSourceInspector.blockAfter(stop, "if (callback == null)")
        assertTrue(retained.contains("fusedRemoved = if (retryRetained) retryPendingFusedLocationCancellations()"))
        assertTrue(retained.contains("else observePendingFusedLocationCancellations()"))
        assertTrue(stop.contains("(lastLocationCleanupFailure ?: fusedLocationCleanupFailure)?.let"))
        assertFalse(stop.contains("fusedLocationCleanupFailure?.let { failure -> attempt { throw failure } }"))
        assertTrue(stop.contains("return lastLocationCleanupFailure == null && fusedRemoved"))
        assertTrue(ReportStaticSourceInspector.appearsInOrder(stop,
            "locationCallbackGeneration += 1", "locationCallback = null", "cancelFusedLocationUpdates(",
            "check(closeGnssQualityObserver())", "pedestrianMotionTracker?.close()",
            "compass().clearLocationOwner()", "positioningCoordinator.reset()", "clearTrustedLocation()"))
        val start = method("startLocationUpdatesIfAllowed")
        assertTrue(ReportStaticSourceInspector.appearsInOrder(start,
            "stopLocationUpdatesInternal(stopFieldSession = false, retryRetained = false)",
            "if (!observePendingFusedLocationCancellations()) return", "sources.requestUpdates(fusedLocationSource)"))
        assertTrue(start.contains("stopLocationUpdatesInternal(stopFieldSession = true, retryRetained = false)"))
        assertFalse(start.contains("retryPendingFusedLocationCancellations()"))
        val cleanup = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/session/runtime/WalkSessionCleanupController.kt")
        assertTrue(MainActivityRefactorSource.member(cleanup, "cancelWalkSessionOutputsInternal").contains("navigation.runLocationStopCleanupCheckpoint("))
        assertTrue(method("runLocationStopCleanupCheckpoint").contains("fusedLocationCancellations.runLocationStopCleanupCheckpoint("))
        val cancellation = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/location/FusedLocationCancellationRegistry.kt")
        val checkpoint = MainActivityRefactorSource.member(cancellation, "runLocationStopCleanupCheckpoint")
        assertTrue(checkpoint.contains("awaitFusedLocationCancellation { removed ->"))
        assertTrue(checkpoint.contains("checkpoints.finishAsync(key, succeeded)"))
        val sdk = ReportStaticSourceInspector.read("src/main/java/kr/co/hanium/dreamup/walksafe/navigation/location/FusedLocationUpdateSource.kt")
        assertTrue(sdk.contains("client.requestLocationUpdates(request, callback, looper)"))
        assertTrue(MainActivityRefactorSource.member(source, "runLocationStopCleanupCheckpoint").contains("positioningSessionController.runLocationStopCleanupCheckpoint("))
        // Source wiring only: SDK removal and late Android callbacks are not executed here.
    }

    private companion object {
        const val LOCATION_STOP_KEY = "outputs:location_stop"
        val DIRECT_EXECUTOR = Executor { it.run() }
    }

    /** Only queues native Task listener scheduling; cancellation state stays in Main's actual registry. */
    private class QueuedExecutor : Executor {
        private val pending = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) { pending.addLast(command) }

        fun hasPending(): Boolean = pending.isNotEmpty()

        fun drain() {
            while (pending.isNotEmpty()) pending.removeFirst().run()
        }
    }
}
