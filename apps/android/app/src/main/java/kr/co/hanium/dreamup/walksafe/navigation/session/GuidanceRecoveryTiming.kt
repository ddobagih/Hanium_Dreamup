package kr.co.hanium.dreamup.walksafe.navigation.session

/** Recheck time for an already admitted walk; measurement freshness remains source-owned. */
internal object GuidanceRecoveryTiming {
    const val GPS_RECHECK_PERIOD_MS = 3_000L
    const val GPS_RECHECK_PERIODS = 6
    const val GPS_GRACE_MS = GPS_RECHECK_PERIOD_MS * GPS_RECHECK_PERIODS
    const val DIRECTION_GRACE_MS = 9_000L
    const val CAMERA_GRACE_MS = 4_500L
}
