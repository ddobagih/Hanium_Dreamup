package kr.co.hanium.dreamup.walksafe

import com.google.ar.core.ArCoreApk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ArCoreAvailabilityPolicyTest {
    @Test
    fun mapsEveryArCoreAvailabilityToTheBoundedStartGate() {
        val expected = mapOf(
            ArCoreApk.Availability.SUPPORTED_INSTALLED to ArCoreStartGate.READY,
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD to ArCoreStartGate.READY,
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED to ArCoreStartGate.READY,
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE to ArCoreStartGate.CAMERA_FALLBACK,
            ArCoreApk.Availability.UNKNOWN_CHECKING to ArCoreStartGate.RETRY_LATER,
            ArCoreApk.Availability.UNKNOWN_ERROR to ArCoreStartGate.RETRY_LATER,
            ArCoreApk.Availability.UNKNOWN_TIMED_OUT to ArCoreStartGate.RETRY_LATER,
        )

        assertEquals(ArCoreApk.Availability.entries.toSet(), expected.keys)
        expected.forEach { (availability, gate) ->
            assertEquals(availability.name, gate, resolveArCoreStartGate(availability))
        }
    }

    @Test
    fun rechecksOnlyUnknownAvailabilityStatesAsynchronously() {
        val unknown = setOf(
            ArCoreApk.Availability.UNKNOWN_CHECKING,
            ArCoreApk.Availability.UNKNOWN_ERROR,
            ArCoreApk.Availability.UNKNOWN_TIMED_OUT,
        )
        ArCoreApk.Availability.entries.forEach { availability ->
            if (availability in unknown) {
                assertTrue(availability.name, shouldRecheckArCoreAvailability(availability))
            } else {
                assertFalse(availability.name, shouldRecheckArCoreAvailability(availability))
            }
        }
    }
}
