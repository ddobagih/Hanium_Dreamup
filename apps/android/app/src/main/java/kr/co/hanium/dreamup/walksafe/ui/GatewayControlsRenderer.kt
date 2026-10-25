package kr.co.hanium.dreamup.walksafe.ui

/** Applies gateway-control presentation; admission and session ownership stay with their owners. */
internal object GatewayControlsRenderer {
    interface Target {
        fun showToken(visible: Boolean)
        fun showAction(visible: Boolean)
        fun clearToken()
        fun setCaption(message: String)
        fun setActionEnabled(enabled: Boolean)
        fun actionEnabled(): Boolean
        fun setTokenEnabled(enabled: Boolean)
    }
    fun render(target: Target, surfaceVisible: Boolean, requestInFlight: Boolean,
        recoverySessionReady: Boolean, recoverySessionReserved: Boolean, recoveryOnly: Boolean,
        hasGatewaySession: () -> Boolean) {
        target.showToken(surfaceVisible)
        target.showAction(surfaceVisible)
        if (!surfaceVisible) target.clearToken()
        target.setCaption(when {
            requestInFlight -> "Gateway 처리 중"
            recoverySessionReady -> "삭제 복구 로그인 완료"
            recoverySessionReserved -> "계정 삭제 처리 로그인 유지"
            recoveryOnly -> "삭제 복구 다시 로그인"
            hasGatewaySession() -> "Gateway 로그아웃"
            else -> "Gateway 로그인"
        })
        target.setActionEnabled(surfaceVisible && !requestInFlight && !recoverySessionReady && !recoverySessionReserved)
        target.setTokenEnabled(target.actionEnabled())
    }
}
