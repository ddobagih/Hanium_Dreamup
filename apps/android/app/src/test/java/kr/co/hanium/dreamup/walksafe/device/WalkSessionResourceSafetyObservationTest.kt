package kr.co.hanium.dreamup.walksafe.device

import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** S14-47: an unknown resource cannot become current evidence that the resource is healthy. */
class WalkSessionResourceSafetyObservationTest {
    private val epoch = WalkRuntimeEpoch("resource-observation", 4L)
    private val healthy = WalkSessionDeviceResourceSnapshot(
        batteryNotLow = true,
        privateStorageAboveSystemLow = true,
        thermalBelowCritical = true,
        thermalThrottled = false,
    )

    @Test
    fun unknownBatteryRemainsUnknownInSafetyEvidence() {
        val observation = observe(healthy.copy(batteryNotLow = null))

        assertNull(observation.batteryCritical)
        assertEquals(false, observation.storageCritical)
        assertEquals(false, observation.thermalCritical)
    }

    @Test
    fun unknownStorageRemainsUnknownInSafetyEvidence() {
        val observation = observe(healthy.copy(privateStorageAboveSystemLow = null))

        assertNull(observation.storageCritical)
        assertEquals(false, observation.batteryCritical)
        assertEquals(false, observation.thermalCritical)
    }

    @Test
    fun unknownThermalRemainsUnknownInSafetyEvidence() {
        val observation = observe(healthy.copy(thermalBelowCritical = null, thermalThrottled = null))

        assertNull(observation.thermalCritical)
        assertEquals(false, observation.batteryCritical)
        assertEquals(false, observation.storageCritical)
    }

    @Test
    fun knownHealthyResourcesRemainNonCritical() {
        val observation = observe(healthy)

        assertEquals(false, observation.batteryCritical)
        assertEquals(false, observation.storageCritical)
        assertEquals(false, observation.thermalCritical)
    }

    @Test
    fun knownFailedResourceChecksRemainCritical() {
        val observation = observe(healthy.copy(
            batteryNotLow = false,
            privateStorageAboveSystemLow = false,
            thermalBelowCritical = false,
        ))

        assertEquals(true, observation.batteryCritical)
        assertEquals(true, observation.storageCritical)
        assertEquals(true, observation.thermalCritical)
    }

    @Test
    fun conversionPreservesTheExplicitEpochAndSourceAndReceiptTimes() {
        val observedAtMs = 1_000L
        val receivedAtMs = 1_250L
        val observation = healthy.toRuntimeSafetyObservation(
            epoch = epoch,
            observedAtElapsedRealtimeMs = observedAtMs,
            receivedAtElapsedRealtimeMs = receivedAtMs,
        )

        assertEquals(epoch, observation.epoch)
        assertEquals(observedAtMs, observation.observedAtElapsedRealtimeMs)
        assertEquals(receivedAtMs, observation.receivedAtElapsedRealtimeMs)
    }

    @Test
    fun resourceConversionDoesNotInventOtherHealthOrDetectorEvidence() {
        val observation = observe(healthy)

        assertNull(observation.cameraTrusted)
        assertNull(observation.depthTrusted)
        assertNull(observation.gpsTrusted)
        assertNull(observation.riskTrusted)
        assertNull(observation.ttsTrusted)
        assertNull(observation.frameCapturedAtElapsedRealtimeMs)
        assertNull(observation.inferenceLatencyMs)
    }

    private fun observe(snapshot: WalkSessionDeviceResourceSnapshot) =
        snapshot.toRuntimeSafetyObservation(
            epoch = epoch,
            observedAtElapsedRealtimeMs = 1_000L,
            receivedAtElapsedRealtimeMs = 1_000L,
        )
}
