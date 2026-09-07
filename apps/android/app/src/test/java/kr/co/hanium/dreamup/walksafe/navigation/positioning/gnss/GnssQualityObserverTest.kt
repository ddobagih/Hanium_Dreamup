package kr.co.hanium.dreamup.walksafe.navigation.positioning.gnss

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GnssQualityObserverTest {
    @Test
    fun registersStatusAndMeasurementsIndependently() {
        val source = FakeGnssObservationSource(measurementMode = RegistrationMode.REJECT)
        val snapshots = mutableListOf<GnssQualitySnapshot>()
        val observer = GnssQualityObserver(source = source, onSnapshot = snapshots::add)

        val result = observer.start()
        source.emitStatus(dualFrequencySignals(timestampNanos = 1L))

        assertTrue(result.statusRegistered)
        assertFalse(result.measurementsRegistered)
        assertNull(result.statusFailure)
        assertEquals(GnssRegistrationFailure.REGISTRATION_REJECTED, result.measurementsFailure)
        assertEquals(
            DualFrequencyObservationState.DUAL_FREQUENCY_OBSERVED,
            snapshots.single().frequencySummary.state,
        )
        assertEquals(1, snapshots.single().frequencySummary.usedInFixSatelliteCount)
    }

    @Test
    fun isolatesSecurityExceptionFromTheOtherOptionalFeed() {
        val source = FakeGnssObservationSource(statusMode = RegistrationMode.SECURITY_EXCEPTION)
        val snapshots = mutableListOf<GnssQualitySnapshot>()
        val observer = GnssQualityObserver(source = source, onSnapshot = snapshots::add)

        val result = observer.start()
        source.emitMeasurements(epoch(1L, 30.0))

        assertFalse(result.statusRegistered)
        assertTrue(result.measurementsRegistered)
        assertEquals(GnssRegistrationFailure.SECURITY_EXCEPTION, result.statusFailure)
        assertEquals(1, snapshots.single().finiteSignalCount)
        assertFalse(snapshots.single().frequencySummary.usedInFixKnown)
    }

    @Test
    fun retriesAfterBothRegistrationsInitiallyFail() {
        val source = FakeGnssObservationSource(
            statusMode = RegistrationMode.REJECT,
            measurementMode = RegistrationMode.SECURITY_EXCEPTION,
        )
        val observer = GnssQualityObserver(source = source, onSnapshot = {})

        val failed = observer.start()
        source.statusMode = RegistrationMode.SUCCESS
        source.measurementMode = RegistrationMode.SUCCESS
        val recovered = observer.start()

        assertFalse(failed.statusRegistered)
        assertFalse(failed.measurementsRegistered)
        assertTrue(recovered.statusRegistered)
        assertTrue(recovered.measurementsRegistered)
        assertEquals(2, source.statusSubscribeCount)
        assertEquals(2, source.measurementSubscribeCount)
    }

    @Test
    fun startStopAreIdempotentAndStopClearsStateBeforeCancellation() {
        val source = FakeGnssObservationSource()
        val snapshots = mutableListOf<GnssQualitySnapshot>()
        val observer = GnssQualityObserver(source = source, onSnapshot = snapshots::add)

        observer.start()
        val repeated = observer.start()
        source.emitMeasurements(epoch(1L, 30.0))
        val deliveriesBeforeStop = snapshots.size
        observer.stop()
        observer.stop()
        source.emitAllRetainedMeasurements(epoch(2L, 10.0))
        val cleared = observer.snapshot(3L)

        assertTrue(repeated.alreadyStarted)
        assertEquals(1, source.statusSubscribeCount)
        assertEquals(1, source.measurementSubscribeCount)
        assertEquals(1, source.statusCancelCount)
        assertEquals(1, source.measurementCancelCount)
        assertEquals(deliveriesBeforeStop, snapshots.size)
        assertEquals(0, cleared.epochCount)
        assertEquals(SignalEnvironmentRisk.UNKNOWN, cleared.signalEnvironmentRisk)
        assertEquals(1.0, cleared.measurementNoiseMultiplier, 0.0)
    }

    @Test
    fun restartRejectsCallbacksFromThePreviousGeneration() {
        val source = FakeGnssObservationSource()
        val snapshots = mutableListOf<GnssQualitySnapshot>()
        val observer = GnssQualityObserver(source = source, onSnapshot = snapshots::add)

        observer.start()
        val oldListener = source.latestMeasurementListener()
        observer.stop()
        observer.start()
        val newListener = source.latestMeasurementListener()
        oldListener.onObservation(epoch(1L, 5.0))
        newListener.onObservation(epoch(2L, 35.0))

        assertEquals(1, snapshots.size)
        assertEquals(35.0, snapshots.single().medianCn0DbHz!!, 0.0)
    }

    @Test
    fun retriesFailedUnregistrationBeforeStartingNewCallbacks() {
        val source = FakeGnssObservationSource(measurementMode = RegistrationMode.REJECT)
        source.statusCancelFailuresRemaining = 1
        val snapshots = mutableListOf<GnssQualitySnapshot>()
        val observer = GnssQualityObserver(source = source, onSnapshot = snapshots::add)

        observer.start()
        observer.stop()
        source.emitAllRetainedStatus(dualFrequencySignals(timestampNanos = 1L))
        val restarted = observer.start()

        assertEquals(0, snapshots.size)
        assertTrue(restarted.statusRegistered)
        assertEquals(2, source.statusCancelCount)
        assertEquals(2, source.statusSubscribeCount)
    }

    @Test
    fun closeIsIdempotentAndPreventsRestart() {
        val source = FakeGnssObservationSource()
        val observer = GnssQualityObserver(source = source, onSnapshot = {})
        observer.start()

        observer.close()
        observer.close()
        val afterClose = observer.start()

        assertTrue(afterClose.observerClosed)
        assertFalse(afterClose.statusRegistered)
        assertFalse(afterClose.measurementsRegistered)
        assertEquals(1, source.statusSubscribeCount)
        assertEquals(1, source.measurementSubscribeCount)
        assertEquals(1, source.statusCancelCount)
        assertEquals(1, source.measurementCancelCount)
    }

    private fun dualFrequencySignals(timestampNanos: Long) = GnssSignalEpoch(
        elapsedRealtimeNanos = timestampNanos,
        signals = listOf(
            signal(1, GnssFrequencyClassifier.L1_CENTER_HZ, 35.0, usedInFix = true),
            signal(1, GnssFrequencyClassifier.L5_CENTER_HZ, 32.0, usedInFix = true),
        ),
    )

    private fun epoch(timestampNanos: Long, cn0DbHz: Double) = GnssSignalEpoch(
        elapsedRealtimeNanos = timestampNanos,
        signals = listOf(
            signal(1, GnssFrequencyClassifier.L1_CENTER_HZ, cn0DbHz).copy(usedInFixKnown = false),
        ),
    )

    private fun signal(
        svid: Int,
        frequencyHz: Double,
        cn0DbHz: Double,
        usedInFix: Boolean = false,
    ) = GnssSatelliteSignal(
        constellation = 1,
        svid = svid,
        carrierFrequencyHz = frequencyHz,
        cn0DbHz = cn0DbHz,
        usedInFix = usedInFix,
    )
}

private enum class RegistrationMode {
    SUCCESS,
    REJECT,
    SECURITY_EXCEPTION,
}

private class FakeGnssObservationSource(
    var statusMode: RegistrationMode = RegistrationMode.SUCCESS,
    var measurementMode: RegistrationMode = RegistrationMode.SUCCESS,
) : GnssObservationSource {
    var statusCancelFailuresRemaining = 0
    var statusSubscribeCount = 0
        private set
    var measurementSubscribeCount = 0
        private set
    var statusCancelCount = 0
        private set
    var measurementCancelCount = 0
        private set
    private val statusListeners = mutableListOf<GnssObservationListener>()
    private val measurementListeners = mutableListOf<GnssObservationListener>()
    private var activeStatusListener: GnssObservationListener? = null
    private var activeMeasurementListener: GnssObservationListener? = null

    override fun subscribeStatus(listener: GnssObservationListener): GnssObservationSubscription? {
        statusSubscribeCount += 1
        if (statusMode == RegistrationMode.SECURITY_EXCEPTION) throw SecurityException("fine location revoked")
        if (statusMode == RegistrationMode.REJECT) return null
        statusListeners += listener
        activeStatusListener = listener
        return GnssObservationSubscription {
            statusCancelCount += 1
            if (statusCancelFailuresRemaining > 0) {
                statusCancelFailuresRemaining -= 1
                throw IllegalStateException("temporary unregister failure")
            }
            if (activeStatusListener === listener) activeStatusListener = null
        }
    }

    override fun subscribeMeasurements(listener: GnssObservationListener): GnssObservationSubscription? {
        measurementSubscribeCount += 1
        if (measurementMode == RegistrationMode.SECURITY_EXCEPTION) throw SecurityException("fine location revoked")
        if (measurementMode == RegistrationMode.REJECT) return null
        measurementListeners += listener
        activeMeasurementListener = listener
        return GnssObservationSubscription {
            measurementCancelCount += 1
            if (activeMeasurementListener === listener) activeMeasurementListener = null
        }
    }

    fun emitStatus(epoch: GnssSignalEpoch) {
        activeStatusListener?.onObservation(epoch)
    }

    fun emitMeasurements(epoch: GnssSignalEpoch) {
        activeMeasurementListener?.onObservation(epoch)
    }

    fun emitAllRetainedMeasurements(epoch: GnssSignalEpoch) {
        measurementListeners.forEach { it.onObservation(epoch) }
    }

    fun emitAllRetainedStatus(epoch: GnssSignalEpoch) {
        statusListeners.forEach { it.onObservation(epoch) }
    }

    fun latestMeasurementListener(): GnssObservationListener = measurementListeners.last()
}
