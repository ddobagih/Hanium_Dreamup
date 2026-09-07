package kr.co.hanium.dreamup.walksafe.navigation

import android.annotation.SuppressLint
import android.os.Build
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.Granularity
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource

class AndroidDestinationSearchLocationSource(
    private val client: FusedLocationProviderClient,
) : DestinationSearchLocationSource {
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    override fun request(onResult: (DestinationSearchLocationFix?) -> Unit): () -> Unit {
        val cancellation = CancellationTokenSource()
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setGranularity(Granularity.GRANULARITY_FINE)
            .setMaxUpdateAgeMillis(DestinationSearchLocationPolicy.MAX_AGE_MS)
            .setDurationMillis(DestinationSearchLocationPolicy.REQUEST_TIMEOUT_MS)
            .build()
        try {
            client.getCurrentLocation(request, cancellation.token).addOnCompleteListener { task ->
                val location = if (task.isSuccessful) task.result else null
                onResult(location?.let {
                    DestinationSearchLocationFix(
                        latitude = it.latitude,
                        longitude = it.longitude,
                        accuracyM = if (it.hasAccuracy()) it.accuracy else null,
                        elapsedRealtimeMs = it.elapsedRealtimeNanos / 1_000_000L,
                        mock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) it.isMock else it.isFromMockProvider,
                    )
                })
            }
        } catch (_: SecurityException) {
            onResult(null)
        }
        return { cancellation.cancel() }
    }
}
