package kr.co.hanium.dreamup.walksafe.navigation

import kr.co.hanium.dreamup.walksafe.session.WalkSessionState

internal data class ApprovedWakeWordProfile(
    val approvalId: String,
    val exactKoreanPhrase: String,
) {
    init {
        require(approvalId.matches(Regex("[A-Za-z0-9._-]{1,96}")))
        require(exactKoreanPhrase.matches(Regex("[가-힣]{2,16}")))
    }
}

/** Default-off eligibility gate. It never opens audio or loads a recognition model. */
internal class WakeWordController(
    private val approvedProfile: ApprovedWakeWordProfile? = PRODUCTION_WAKE_WORD_PROFILE,
    private val stopCallback: () -> Unit = {},
    private val startCallback: (ApprovedWakeWordProfile) -> Unit,
) {
    private var started = false

    @Synchronized
    fun startIfEligible(
        walkState: WalkSessionState,
        microphonePermissionGranted: Boolean,
        onDeviceRecognitionAvailable: Boolean,
    ): Boolean {
        val profile = approvedProfile ?: return false
        if (
            walkState != WalkSessionState.ACTIVE ||
            !microphonePermissionGranted ||
            !onDeviceRecognitionAvailable
        ) {
            stop()
            return false
        }
        if (started) return true
        val invoked = runCatching { startCallback(profile) }.isSuccess
        if (invoked) started = true
        return invoked
    }

    @Synchronized
    fun stop(): Boolean {
        if (!started) return true
        val stopped = runCatching(stopCallback).isSuccess
        if (stopped) started = false
        return stopped
    }
}

internal val PRODUCTION_WAKE_WORD_PROFILE: ApprovedWakeWordProfile? = null
