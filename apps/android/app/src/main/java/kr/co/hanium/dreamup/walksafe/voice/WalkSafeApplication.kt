package kr.co.hanium.dreamup.walksafe.voice

import android.app.Application

internal class WalkSafeApplication : Application(), WalkVoiceSessionControllerProvider {
    @Volatile
    override var walkVoiceSessionController: WalkVoiceSessionController? = null
        private set

    fun attachWalkVoiceSessionController(controller: WalkVoiceSessionController) {
        walkVoiceSessionController = controller
    }

    fun detachWalkVoiceSessionController(controller: WalkVoiceSessionController) {
        if (walkVoiceSessionController === controller) {
            walkVoiceSessionController = null
        }
    }
}
