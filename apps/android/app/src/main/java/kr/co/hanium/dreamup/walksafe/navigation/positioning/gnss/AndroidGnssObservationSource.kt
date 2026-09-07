package kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss

import android.Manifest
import android.location.GnssMeasurementsEvent
import android.location.LocationManager
import android.os.SystemClock
import androidx.annotation.RequiresPermission
import androidx.core.location.GnssStatusCompat
import androidx.core.location.LocationManagerCompat
import java.util.concurrent.Executor

/**
 * Thin Android boundary. The caller must hold ACCESS_FINE_LOCATION while subscribing.
 */
internal class AndroidGnssObservationSource(
    private val locationManager: LocationManager,
    private val executor: Executor,
    private val elapsedRealtimeNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) : GnssObservationSource {
    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    override fun subscribeStatus(listener: GnssObservationListener): GnssObservationSubscription? {
        val callback = object : GnssStatusCompat.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatusCompat) {
                val signals = buildList(status.satelliteCount) {
                    repeat(status.satelliteCount) { index ->
                        add(
                            GnssSatelliteSignal(
                                constellation = status.getConstellationType(index),
                                svid = status.getSvid(index),
                                carrierFrequencyHz = if (status.hasCarrierFrequencyHz(index)) {
                                    status.getCarrierFrequencyHz(index).toDouble()
                                } else {
                                    null
                                },
                                cn0DbHz = status.getCn0DbHz(index).toDouble(),
                                usedInFix = status.usedInFix(index),
                                usedInFixKnown = true,
                            ),
                        )
                    }
                }
                listener.onObservation(GnssSignalEpoch(elapsedRealtimeNanos(), signals))
            }
        }
        if (!LocationManagerCompat.registerGnssStatusCallback(locationManager, executor, callback)) {
            return null
        }
        return OnceGnssSubscription {
            LocationManagerCompat.unregisterGnssStatusCallback(locationManager, callback)
        }
    }

    @RequiresPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    override fun subscribeMeasurements(listener: GnssObservationListener): GnssObservationSubscription? {
        val callback = object : GnssMeasurementsEvent.Callback() {
            override fun onGnssMeasurementsReceived(eventArgs: GnssMeasurementsEvent) {
                val signals = eventArgs.measurements.map { measurement ->
                    GnssSatelliteSignal(
                        constellation = measurement.constellationType,
                        svid = measurement.svid,
                        carrierFrequencyHz = if (measurement.hasCarrierFrequencyHz()) {
                            measurement.carrierFrequencyHz.toDouble()
                        } else {
                            null
                        },
                        cn0DbHz = measurement.cn0DbHz,
                        usedInFix = false,
                        usedInFixKnown = false,
                    )
                }
                listener.onObservation(GnssSignalEpoch(elapsedRealtimeNanos(), signals))
            }
        }
        if (!LocationManagerCompat.registerGnssMeasurementsCallback(locationManager, executor, callback)) {
            return null
        }
        return OnceGnssSubscription {
            locationManager.unregisterGnssMeasurementsCallback(callback)
        }
    }
}

private class OnceGnssSubscription(
    private val cancelAction: () -> Unit,
) : GnssObservationSubscription {
    @Volatile
    private var cancelled = false

    @Synchronized
    override fun cancel() {
        if (cancelled) return
        cancelAction()
        cancelled = true
    }
}
