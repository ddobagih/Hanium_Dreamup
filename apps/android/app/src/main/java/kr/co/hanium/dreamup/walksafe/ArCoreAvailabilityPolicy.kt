package kr.co.hanium.dreamup.walksafe

import com.google.ar.core.ArCoreApk

internal enum class ArCoreStartGate {
    READY,
    CAMERA_FALLBACK,
    RETRY_LATER,
}

internal fun resolveArCoreStartGate(availability: ArCoreApk.Availability): ArCoreStartGate {
    return when (availability) {
        ArCoreApk.Availability.SUPPORTED_INSTALLED,
        ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
        ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED,
        -> ArCoreStartGate.READY
        ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE -> ArCoreStartGate.CAMERA_FALLBACK
        ArCoreApk.Availability.UNKNOWN_CHECKING,
        ArCoreApk.Availability.UNKNOWN_ERROR,
        ArCoreApk.Availability.UNKNOWN_TIMED_OUT,
        -> ArCoreStartGate.RETRY_LATER
    }
}

internal fun shouldRecheckArCoreAvailability(availability: ArCoreApk.Availability): Boolean {
    return availability.isUnknown
}
