package kr.co.hanium.dreamup.walksafe

import android.content.Intent

internal object CameraFallbackTestOverride {
    @Suppress("UNUSED_PARAMETER")
    fun consumeForceCameraNonMetricFallback(intent: Intent?): Boolean = false
}
