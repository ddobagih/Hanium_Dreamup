package kr.co.hanium.dreamup.walksafe.navigation.location

import com.google.android.gms.location.LocationCallback
import kr.co.hanium.dreamup.walksafe.device.WalkRuntimeStopCleanupCheckpoints

/** Owns native callback-removal attempts and their exact asynchronous completion checkpoints. */
internal class FusedLocationCancellationRegistry {
    private class FusedLocationCancellationOwner(
        val callback: LocationCallback,
        val remove: (LocationCallback) -> com.google.android.gms.tasks.Task<Void>,
        val executor: java.util.concurrent.Executor,
    ) {
        var task: com.google.android.gms.tasks.Task<Void>? = null
        var submitting = true
        var completed: Boolean? = null
        var failure: RuntimeException? = null
        val waiters = mutableListOf<(Boolean) -> Unit>()
    }
    private val fusedLocationCancellationLock = Any()
    private val fusedLocationCancellationOwners =
        java.util.IdentityHashMap<LocationCallback, FusedLocationCancellationOwner>()
    private val locationStopCheckpointLock = Any()
    private val locationStopCheckpointWaiters =
        java.util.IdentityHashMap<WalkRuntimeStopCleanupCheckpoints, MutableList<(Boolean) -> Unit>>()

    internal fun cancelFusedLocationUpdates(
        registeredCallback: LocationCallback,
        remove: (LocationCallback) -> com.google.android.gms.tasks.Task<Void>,
        completionExecutor: java.util.concurrent.Executor,
    ): Boolean {
        val (previous, previousTask) = synchronized(fusedLocationCancellationLock) {
            val retained = fusedLocationCancellationOwners[registeredCallback]
            retained to retained?.task
        }
        // A listener may be queued behind this explicit cleanup; observe the actual Task too.
        if (previous != null && previousTask?.isComplete == true) {
            finishFusedLocationCancellation(previous, previousTask, previousTask.isSuccessful, fusedTaskFailure(previousTask))
            val acknowledged = synchronized(fusedLocationCancellationLock) {
                if (previous.completed == true) fusedLocationCancellationOwners[registeredCallback] == null else null
            }
            if (acknowledged != null) return acknowledged
        }
        val owner = synchronized(fusedLocationCancellationLock) {
            val retained = fusedLocationCancellationOwners[registeredCallback]
            if (retained != null && (retained.submitting || retained.completed == null)) return false
            FusedLocationCancellationOwner(
                registeredCallback, retained?.remove ?: remove, retained?.executor ?: completionExecutor,
            ).also {
                it.failure = retained?.failure
                fusedLocationCancellationOwners[registeredCallback] = it
            }
        }
        val task = try {
            owner.remove(registeredCallback)
        } catch (failure: RuntimeException) {
            finishFusedLocationCancellation(owner, null, false, failure)
            return false
        }
        synchronized(fusedLocationCancellationLock) {
            if (fusedLocationCancellationOwners[registeredCallback] !== owner) return false
            owner.task = task
            owner.submitting = false
        }
        try {
            task.addOnCompleteListener(owner.executor) { completed ->
                finishFusedLocationCancellation(owner, completed, completed.isSuccessful, fusedTaskFailure(completed))
            }
        } catch (failure: RuntimeException) {
            // Listener scheduling failure cannot authorize another native removal while Task is pending.
            synchronized(fusedLocationCancellationLock) {
                if (fusedLocationCancellationOwners[registeredCallback] === owner) {
                    owner.failure = owner.failure ?: failure
                }
            }
            if (task.isComplete) {
                finishFusedLocationCancellation(owner, task, task.isSuccessful, fusedTaskFailure(task))
            }
        }
        return synchronized(fusedLocationCancellationLock) { owner.completed == true }
    }

    private fun fusedTaskFailure(task: com.google.android.gms.tasks.Task<Void>): RuntimeException? {
        if (task.isSuccessful) return null
        val cause = task.exception
        return cause as? RuntimeException ?: IllegalStateException(
            if (task.isCanceled) "fused_cancellation_cancelled" else "fused_cancellation_failed", cause,
        )
    }

    private fun finishFusedLocationCancellation(
        owner: FusedLocationCancellationOwner,
        task: com.google.android.gms.tasks.Task<Void>?,
        succeeded: Boolean,
        failure: RuntimeException?,
    ) {
        val waiters = synchronized(fusedLocationCancellationLock) {
            if (fusedLocationCancellationOwners[owner.callback] !== owner || owner.task !== task ||
                owner.completed != null
            ) return
            owner.submitting = false
            owner.completed = succeeded
            owner.failure = if (succeeded) null else owner.failure ?: failure
            if (succeeded) fusedLocationCancellationOwners.remove(owner.callback)
            owner.waiters.toList().also { owner.waiters.clear() }
        }
        waiters.forEach { it(succeeded) }
    }

    internal val fusedLocationCleanupFailure: RuntimeException?
        get() = synchronized(fusedLocationCancellationLock) {
            fusedLocationCancellationOwners.values.firstNotNullOfOrNull { it.failure }
        }

    internal fun observePendingFusedLocationCancellations(): Boolean {
        val retained = synchronized(fusedLocationCancellationLock) { fusedLocationCancellationOwners.values.toList() }
        retained.forEach { owner ->
            val task = synchronized(fusedLocationCancellationLock) { owner.task }
            if (task?.isComplete == true) {
                finishFusedLocationCancellation(owner, task, task.isSuccessful, fusedTaskFailure(task))
            }
        }
        return synchronized(fusedLocationCancellationLock) { fusedLocationCancellationOwners.isEmpty() }
    }

    internal fun retryPendingFusedLocationCancellations(): Boolean {
        val retained = synchronized(fusedLocationCancellationLock) { fusedLocationCancellationOwners.values.toList() }
        var complete = true
        retained.forEach { owner ->
            if (!cancelFusedLocationUpdates(owner.callback, owner.remove, owner.executor)) complete = false
        }
        return complete
    }

    internal fun awaitFusedLocationCancellation(completion: (Boolean) -> Unit) {
        val resultLock = Any()
        var remaining = 0
        var succeeded = true
        var reported = false
        val observe: (Boolean) -> Unit = { success ->
            val result = synchronized(resultLock) {
                succeeded = succeeded && success
                remaining -= 1
                if (remaining == 0 && !reported) {
                    reported = true
                    succeeded
                } else null
            }
            result?.let(completion)
        }
        val (ownerCount, immediate) = synchronized(fusedLocationCancellationLock) {
            val owners = fusedLocationCancellationOwners.values.toList()
            remaining = owners.size
            owners.size to owners.mapNotNull { owner ->
                owner.completed ?: run { owner.waiters += observe; null }
            }
        }
        if (ownerCount == 0) completion(true) else immediate.forEach(observe)
    }

    internal fun runLocationStopCleanupCheckpoint(
        checkpoints: WalkRuntimeStopCleanupCheckpoints,
        onResult: (Boolean) -> Unit,
        stopTail: () -> Boolean,
    ): Boolean {
        val key = "outputs:location_stop"
        var terminal: Boolean? = null
        val begin = synchronized(locationStopCheckpointLock) {
            val begins = checkpoints.beginAsync(key)
            terminal = when {
                checkpoints.isComplete(key) -> true
                checkpoints.isUncertain(key) -> false
                else -> null
            }
            if (terminal == null) locationStopCheckpointWaiters.getOrPut(checkpoints) { mutableListOf() } += onResult
            begins
        }
        if (begin) {
            val tailSucceeded = try { stopTail() } catch (_: RuntimeException) { false }
            awaitFusedLocationCancellation { removed ->
                val succeeded = tailSucceeded && removed
                val waiters = synchronized(locationStopCheckpointLock) {
                    if (checkpoints.finishAsync(key, succeeded)) {
                        locationStopCheckpointWaiters.remove(checkpoints)?.toList().orEmpty()
                    } else emptyList()
                }
                waiters.forEach { it(succeeded) }
            }
            return tailSucceeded && !checkpoints.isUncertain(key)
        }
        terminal?.let(onResult)
        return terminal != false
    }


    /** Snapshot only; observing it never completes or retries a pending native Task. */
    val hasPendingCancellations: Boolean
        get() = synchronized(fusedLocationCancellationLock) { fusedLocationCancellationOwners.isNotEmpty() }

    /** The caller preserves its existing error-state updates before notifying detached waiters. */
    fun failLocationStopCleanupCheckpoint(
        checkpoints: WalkRuntimeStopCleanupCheckpoints,
    ): List<(Boolean) -> Unit> = synchronized(locationStopCheckpointLock) {
        checkpoints.finishAsync("outputs:location_stop", false)
        locationStopCheckpointWaiters.remove(checkpoints)?.toList().orEmpty()
    }
}
