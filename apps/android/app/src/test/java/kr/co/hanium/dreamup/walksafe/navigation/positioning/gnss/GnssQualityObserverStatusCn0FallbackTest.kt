package kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** S09-5: status C/N0 is the explicit fallback only while raw measurements are unavailable. */
class GnssQualityObserverStatusCn0FallbackTest {
    @Test
    fun weakStatusCn0RaisesRiskWhenRawMeasurementsAreNotRegistered() {
        val source = StatusFallbackSource(rawRegistered = false)
        val observer = GnssQualityObserver(source = source, onSnapshot = {})
        assertFalse(observer.start().measurementsRegistered)

        (1..3).forEach { source.status(epoch(it * SECOND, cn0DbHz = 15.0)) }
        val snapshot = observer.snapshot(3 * SECOND)

        assertTrue(snapshot.cn0FromSatelliteStatus)
        assertEquals(SignalEnvironmentRisk.MEDIUM, snapshot.signalEnvironmentRisk)
        assertTrue(snapshot.measurementNoiseMultiplier > 1.0)
        assertEquals(15.0, requireNotNull(snapshot.medianCn0DbHz), 1e-9)
    }

    @Test
    fun rawMeasurementsWinAndStatusCn0IsNotCountedTwice() {
        val withStatus = StatusFallbackSource(rawRegistered = true)
        val rawOnly = StatusFallbackSource(rawRegistered = true)
        val observer = GnssQualityObserver(source = withStatus, onSnapshot = {})
        val reference = GnssQualityObserver(source = rawOnly, onSnapshot = {})
        observer.start()
        reference.start()

        (1..3).forEach {
            withStatus.status(epoch(it * SECOND, cn0DbHz = 15.0))
            withStatus.raw(epoch(it * SECOND, cn0DbHz = 42.0))
            rawOnly.raw(epoch(it * SECOND, cn0DbHz = 42.0))
        }
        val snapshot = observer.snapshot(3 * SECOND)
        val expected = reference.snapshot(3 * SECOND)

        assertFalse(snapshot.cn0FromSatelliteStatus)
        assertEquals(expected.signalEnvironmentRiskScore, snapshot.signalEnvironmentRiskScore)
        assertEquals(expected.medianCn0DbHz, snapshot.medianCn0DbHz)
        assertEquals(expected.measurementNoiseMultiplier, snapshot.measurementNoiseMultiplier, 0.0)
    }

    @Test
    fun staleStatusCn0DoesNotKeepAFallbackRiskAlive() {
        val source = StatusFallbackSource(rawRegistered = false)
        val observer = GnssQualityObserver(source = source, onSnapshot = {})
        observer.start()
        (1..3).forEach { source.status(epoch(it * SECOND, cn0DbHz = 15.0)) }

        val later = observer.snapshot(9 * SECOND)

        assertFalse(later.cn0FromSatelliteStatus)
        assertEquals(SignalEnvironmentRisk.UNKNOWN, later.signalEnvironmentRisk)
        assertEquals(1.0, later.measurementNoiseMultiplier, 0.0)
    }

    @Test
    fun stopClearsTheStatusCn0Window() {
        val source = StatusFallbackSource(rawRegistered = false)
        val observer = GnssQualityObserver(source = source, onSnapshot = {})
        observer.start()
        (1..3).forEach { source.status(epoch(it * SECOND, cn0DbHz = 15.0)) }
        observer.stop()
        observer.start()

        source.status(epoch(4 * SECOND, cn0DbHz = 15.0))

        assertEquals(SignalEnvironmentRisk.UNKNOWN, observer.snapshot(4 * SECOND).signalEnvironmentRisk)
    }

    private fun epoch(atNanos: Long, cn0DbHz: Double) = GnssSignalEpoch(
        elapsedRealtimeNanos = atNanos,
        signals = (1..6).map { svid ->
            GnssSatelliteSignal(
                constellation = 1,
                svid = svid,
                carrierFrequencyHz = GnssFrequencyClassifier.L1_CENTER_HZ,
                cn0DbHz = cn0DbHz,
                usedInFix = true,
            )
        },
    )

    private class StatusFallbackSource(private val rawRegistered: Boolean) : GnssObservationSource {
        private var statusListener: GnssObservationListener? = null
        private var rawListener: GnssObservationListener? = null

        override fun subscribeStatus(listener: GnssObservationListener): GnssObservationSubscription {
            statusListener = listener
            return GnssObservationSubscription { if (statusListener === listener) statusListener = null }
        }

        override fun subscribeMeasurements(listener: GnssObservationListener): GnssObservationSubscription? {
            if (!rawRegistered) return null
            rawListener = listener
            return GnssObservationSubscription { if (rawListener === listener) rawListener = null }
        }

        fun status(epoch: GnssSignalEpoch) {
            statusListener?.onObservation(epoch)
        }

        fun raw(epoch: GnssSignalEpoch) {
            rawListener?.onObservation(epoch)
        }
    }

    private companion object {
        const val SECOND = 1_000_000_000L
    }
}
