package kr.co.hanium.dreamup.walksafe.navigation.session

import android.content.Context
import android.content.SharedPreferences
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import kr.co.hanium.dreamup.walksafe.navigation.AndroidEarthOrientationTracker
import kr.co.hanium.dreamup.walksafe.navigation.RouteCompassObservationEvent
import kr.co.hanium.dreamup.walksafe.navigation.location.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.*
import kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss.*
import java.util.concurrent.Executor

/** Original Android source construction/queries; no SDK access in the constructor. */
internal class AndroidNavigationSources(
    private val context: Context,
    private val fusedAvailableNow: () -> Boolean,
    private val fusedClient: () -> FusedLocationProviderClient,
    private val prefsAvailableNow: () -> Boolean,
    private val preferences: () -> SharedPreferences,
    private val orientationAvailableNow: () -> Boolean,
    private val orientation: () -> AndroidEarthOrientationTracker,
    private val orientationCanStopNow: () -> Boolean,
    private val runOnUiThread: (Runnable) -> Unit,
    private val locationIntervalMs: Long,
    private val fastestIntervalMs: Long,
    private val mockLocation: (Location) -> Boolean,
) : PositioningSessionController.Sources, RouteCompassSession.Tracker {
    override val fusedAvailable get() = fusedAvailableNow()
    override fun fusedSource(current: (LocationCallback) -> Boolean,
        onEvent: (LocationCallback, FusedLocationUpdateEvent) -> Unit) =
        FusedLocationUpdateSource(fusedClient(), { android.os.SystemClock.elapsedRealtime() }, current, onEvent)
    override fun requestUpdates(source: FusedLocationUpdateSource) {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, locationIntervalMs)
            .setMinUpdateIntervalMillis(fastestIntervalMs)
            .setGranularity(Granularity.GRANULARITY_FINE).setMaxUpdateAgeMillis(0L).build()
        source.requestUpdates(request, Looper.getMainLooper(), Executor { runOnUiThread(it) })
    }
    override fun captureRemoval(): (LocationCallback) -> com.google.android.gms.tasks.Task<Void> {
        val client = fusedClient()
        return { client.removeLocationUpdates(it) }
    }
    override val completionExecutor get() = Executor { runOnUiThread(it) }
    override fun gnssObserver(onSnapshot: (GnssQualitySnapshot) -> Unit): GnssQualityObserver {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return GnssQualityObserver(AndroidGnssObservationSource(manager, ContextCompat.getMainExecutor(context)), onSnapshot = onSnapshot)
    }
    override fun pedestrianTracker(onMotion: (PedestrianStationaryDecision) -> Unit) =
        AndroidPedestrianMotionTracker(context, onDecision = onMotion)
    override val profileStoreAvailable get() = prefsAvailableNow()
    override fun profileStore() = AndroidPedestrianProfileStore(preferences())
    override fun mock(location: Location) = mockLocation(location)
    override val orientationAvailable get() = orientationAvailableNow()
    override val orientationCanStop get() = orientationCanStopNow()
    override fun clearGeomagneticReference() = orientation().clearGeomagneticReference()
    override fun stopOrientation() { orientation().stop() }
    override fun geomagneticReference(location: Location) {
        orientation().updateGeomagneticReference(location.latitude, location.longitude,
            location.altitude.takeIf { location.hasAltitude() && it.isFinite() } ?: 0.0, System.currentTimeMillis())
    }
    override fun chestHeading(timestampMs: Long, maximumAgeMs: Long) = orientation().chestMountedHeadingAt(timestampMs, maximumAgeMs)
    override val available get() = orientationAvailableNow()
    override fun listener(listener: (RouteCompassObservationEvent) -> Unit) = orientation().setRouteObservationListener(listener)
    override fun start() { orientation().start() }
    override fun stop() { orientation().stop() }
    override fun current(event: RouteCompassObservationEvent) = orientation().isRouteObservationCurrent(event)
    override fun heading(nowMs: Long) = orientation().latestRouteCompassHeading(nowMs)
}
