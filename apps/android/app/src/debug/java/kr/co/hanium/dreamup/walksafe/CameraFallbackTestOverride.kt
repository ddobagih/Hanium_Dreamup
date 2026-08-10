package kr.co.hanium.dreamup.walksafe

import android.content.Intent

internal object CameraFallbackTestOverride {
    const val EXTRA_FORCE_CAMERA_NON_METRIC_FALLBACK =
        "kr.co.hanium.dreamup.walksafe.debug.FORCE_CAMERA_NON_METRIC_FALLBACK"

    fun consumeForceCameraNonMetricFallback(intent: Intent?): Boolean {
        if (intent?.getBooleanExtra(EXTRA_FORCE_CAMERA_NON_METRIC_FALLBACK, false) != true) return false
        intent.removeExtra(EXTRA_FORCE_CAMERA_NON_METRIC_FALLBACK)
        return true
    }
}
