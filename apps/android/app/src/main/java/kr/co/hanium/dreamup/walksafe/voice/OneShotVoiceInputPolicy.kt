package kr.co.hanium.dreamup.walksafe.voice

import kr.co.hanium.dreamup.walksafe.device.WalkSafeStartupCapabilityDecision
import kr.co.hanium.dreamup.walksafe.device.WalkSafeStartupRequirement

/** Input readiness is independent of output and walking-runtime capability failures. */
internal object OneShotVoiceInputPolicy {
    fun isAvailable(
        contextAvailable: Boolean,
        microphoneGranted: Boolean,
        capabilityDecision: WalkSafeStartupCapabilityDecision?,
    ): Boolean {
        if (!contextAvailable || !microphoneGranted) return false
        if (capabilityDecision == null) return true
        return INPUT_REQUIREMENTS.none {
            it in capabilityDecision.unavailableRequirements ||
                it in capabilityDecision.pendingRequirements
        }
    }

    private val INPUT_REQUIREMENTS = setOf(
        WalkSafeStartupRequirement.MICROPHONE,
        WalkSafeStartupRequirement.ON_DEVICE_STT,
    )
}
