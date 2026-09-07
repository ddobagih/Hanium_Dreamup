package kr.co.hanium.dreamup.walksafe.device

import com.google.ar.core.ArCoreApk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeMetricDistanceAvailabilityTest {
    @Test
    fun supportedArCoreStatesRequireLiveSessionDepthCheck() {
        assertNull(resolveRuntimeMetricDistanceAvailability(ArCoreApk.Availability.SUPPORTED_INSTALLED))
        assertNull(resolveRuntimeMetricDistanceAvailability(ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD))
        assertNull(resolveRuntimeMetricDistanceAvailability(ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED))
    }

    @Test
    fun explicitlyIncapableDeviceIsUnavailable() {
        assertFalse(
            requireNotNull(
                resolveRuntimeMetricDistanceAvailability(
                    ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE,
                ),
            ),
        )
    }
}
