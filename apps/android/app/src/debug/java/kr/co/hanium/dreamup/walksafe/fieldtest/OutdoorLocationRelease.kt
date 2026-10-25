package kr.co.hanium.dreamup.walksafe.fieldtest

import com.google.android.gms.location.LocationCallback
import com.google.android.gms.tasks.Task
import java.util.concurrent.Executor
import kr.co.hanium.dreamup.walksafe.navigation.location.FusedLocationCancellationRegistry

/** Removal must also cover a registration which finishes after the first remove request. */
internal class OutdoorLocationRelease(
    private val callback: LocationCallback,
    private val registration: Task<Void>?,
    private val remove: (LocationCallback) -> Task<Void>,
    private val executor: Executor,
    private val onRemovalFinished: (Boolean) -> Unit,
) {
    private val cancellations = FusedLocationCancellationRegistry()
    private var initialRemovalSubmitted = false
    private var registrationRetired = false
    private var awaitingRemoval = false
    private var released = false

    fun retry(): Boolean {
        if (released) return true
        val registrationComplete = registration?.isComplete != false
        val previousRemovalComplete = cancellations.observePendingFusedLocationCancellations()
        if (!initialRemovalSubmitted || (registrationComplete && !registrationRetired &&
                (previousRemovalComplete || cancellations.fusedLocationCleanupFailure != null))) {
            initialRemovalSubmitted = true
            registrationRetired = registrationComplete
            cancellations.cancelFusedLocationUpdates(callback, remove, executor)
        } else cancellations.retryPendingFusedLocationCancellations()
        val removed = cancellations.observePendingFusedLocationCancellations()
        if (!removed && !awaitingRemoval && cancellations.fusedLocationCleanupFailure == null) {
            awaitingRemoval = true
            cancellations.awaitFusedLocationCancellation { succeeded ->
                awaitingRemoval = false
                onRemovalFinished(succeeded)
            }
        }
        released = registrationComplete && registrationRetired && removed
        return released
    }
}
