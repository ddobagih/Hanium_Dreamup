package kr.co.hanium.dreamup.walksafe.navigation

/** Test guidance may use an imprecise real fix, without upgrading positioning evidence. */
object NavigationTestLocationPolicy {
    fun freshOrNull(
        location: TrustedLocation?,
        nowElapsedRealtimeMs: Long,
        mock: Boolean = false,
    ): TrustedLocation? {
        val fix = location ?: return null
        if (mock || !fix.latitude.isFinite() || fix.latitude !in -90.0..90.0 ||
            !fix.longitude.isFinite() || fix.longitude !in -180.0..180.0 ||
            !fix.accuracyM.isFinite() || fix.accuracyM < 0f || fix.elapsedRealtimeMs < 0L
        ) return null
        return fix.takeIf { nowElapsedRealtimeMs - it.elapsedRealtimeMs in 0L..10_000L }
    }
}
