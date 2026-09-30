package kr.co.hanium.dreamup.walksafe

import kr.co.hanium.dreamup.walksafe.network.GatewayWalkAuthorityController
import kr.co.hanium.dreamup.walksafe.session.WalkSessionLifecycle
import kr.co.hanium.dreamup.walksafe.session.WalkSessionRecoveryStage
import kr.co.hanium.dreamup.walksafe.session.WalkSessionState

/**
 * S03-19 (FP-018-DETAIL-05, SP-14-POLICY-001): hands the logical walk from an Activity destroyed
 * for a configuration change (font size, language, theme, multi-window) to the Activity the same
 * process re-creates. It lives only in memory, so a restarted process still gets a new walk.
 *
 * Only a PAUSED walk that is waiting for a recheck is handed over: the walk id and server lease
 * continue, while camera, voice and sensor evidence stay with the old Activity and the new one
 * must recheck and ask before resuming. The hand-off is one-shot and expires after [maxAgeMs].
 */
internal class WalkConfigurationChangeRetention(private val maxAgeMs: Long) {
    init {
        require(maxAgeMs > 0L) { "maxAgeMs must be positive" }
    }

    class Retained(
        val lifecycle: WalkSessionLifecycle,
        val authority: GatewayWalkAuthorityController,
        val retainedAtElapsedRealtimeMs: Long,
    )

    private var retained: Retained? = null

    /** Returns true only when [lifecycle] is paused for a recheck and was stored. */
    @Synchronized
    fun retain(
        lifecycle: WalkSessionLifecycle,
        authority: GatewayWalkAuthorityController,
        nowElapsedRealtimeMs: Long,
    ): Boolean {
        retained = null
        if (!lifecycle.waitsForRecheck()) return false
        retained = Retained(lifecycle, authority, nowElapsedRealtimeMs)
        return true
    }

    /** One-shot: a stale, expired or no longer paused hand-off is dropped, never reused. */
    @Synchronized
    fun take(nowElapsedRealtimeMs: Long): Retained? {
        val current = retained ?: return null
        retained = null
        val ageMs = nowElapsedRealtimeMs - current.retainedAtElapsedRealtimeMs
        if (ageMs !in 0L..maxAgeMs) return null
        return current.takeIf { it.lifecycle.waitsForRecheck() }
    }

    @Synchronized
    fun clear() {
        retained = null
    }

    private fun WalkSessionLifecycle.waitsForRecheck(): Boolean {
        val snapshot = snapshot()
        return snapshot.state == WalkSessionState.PAUSED &&
            snapshot.recoveryStage == WalkSessionRecoveryStage.RECHECK_REQUIRED
    }
}

/** Process-scoped; bounded by the existing readiness-confirmation lifetime. */
internal val processWalkConfigurationChangeRetention =
    WalkConfigurationChangeRetention(maxAgeMs = WalkSessionLifecycle.CONFIRMATION_TOKEN_TTL_MS)
