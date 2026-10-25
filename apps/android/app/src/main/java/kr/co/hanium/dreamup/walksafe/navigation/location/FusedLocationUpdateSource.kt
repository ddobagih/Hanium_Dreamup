package kr.co.hanium.dreamup.walksafe.navigation.location

import android.annotation.SuppressLint
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationAvailability
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.tasks.Task
import java.util.concurrent.Executor

/** SDK facts only. A registration completion does not create a location observation. */
internal sealed interface FusedLocationUpdateEvent {
    data class LocationReceived(val location: Location, val receivedAtMs: Long) : FusedLocationUpdateEvent

    data class AvailabilityChanged(val availability: LocationAvailability) : FusedLocationUpdateEvent

    data class RegistrationFailed(val failure: Exception) : FusedLocationUpdateEvent
}

/**
 * Preserves one native callback's identity and received Location. Owner, generation and binding
 * admission stay with the caller; neither navigation policy nor measurement quality lives here.
 */
internal class FusedLocationUpdateSource(
    private val client: FusedLocationProviderClient,
    private val receivedAtMs: () -> Long,
    private val callbackIsCurrent: (LocationCallback) -> Boolean,
    private val onEvent: (LocationCallback, FusedLocationUpdateEvent) -> Unit,
) {
    val callback: LocationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val receivedAt = receivedAtMs()
            if (!callbackIsCurrent(this)) return
            result.lastLocation?.let { location ->
                onEvent(this, FusedLocationUpdateEvent.LocationReceived(location, receivedAt))
            }
        }

        override fun onLocationAvailability(availability: LocationAvailability) {
            if (!callbackIsCurrent(this)) return
            onEvent(this, FusedLocationUpdateEvent.AvailabilityChanged(availability))
        }
    }

    @SuppressLint("MissingPermission")
    fun requestUpdates(
        request: LocationRequest,
        looper: Looper,
        completionExecutor: Executor,
    ): Task<Void> = client.requestLocationUpdates(request, callback, looper)
        .addOnFailureListener(completionExecutor) { failure ->
            if (callbackIsCurrent(callback)) {
                onEvent(callback, FusedLocationUpdateEvent.RegistrationFailed(failure))
            }
        }
}
