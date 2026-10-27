package kr.co.hanium.dreamup.walksafe.voice

import android.app.Application
import kr.co.hanium.dreamup.walksafe.report.ReportQueueCleanupJobService

internal class WalkSafeApplication : Application(), WalkVoiceSessionControllerProvider {
    private val voiceStartFailureRelay = WalkVoiceStartFailureRelay()
    private val voiceUserStopRelay = WalkVoiceUserStopRelay()

    /** Process-scoped so an Activity re-creation cannot forget the user's stop (S04-30). */
    val walkVoiceUserStopHold = WalkVoiceUserStopHold()

    fun setWalkVoiceUserStopListener(listener: ((WalkVoiceSessionController) -> Unit)?) {
        voiceUserStopRelay.setListener(listener)
    }

    /** The notification's "음성 입력 중지": tells the app owner before the service stops the controller. */
    fun reportWalkVoiceUserStop(stopped: WalkVoiceSessionController?): Boolean =
        voiceUserStopRelay.report(stopped, walkVoiceSessionController)

    override fun onCreate() {
        super.onCreate()
        // The decoder shares this UID/cache but must not clean the microphone owner's audio.
        val processName = if (android.os.Build.VERSION.SDK_INT >= 28) {
            Application.getProcessName()
        } else {
            getSystemService(android.app.ActivityManager::class.java).runningAppProcesses
                ?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName
        }
        if (processName != applicationInfo.processName) return
        val cleanup = TemporaryVoiceAudio.cleanupAtProcessStart(cacheDir)
        if (cleanup.failures > 0) android.util.Log.w("WalkSafeVoice", "temporary_audio_cleanup_incomplete count=${cleanup.failures}")
        ReportQueueCleanupJobService.schedule(this)
    }

    @Volatile
    override var walkVoiceSessionController: WalkVoiceSessionController? = null
        private set

    fun attachWalkVoiceSessionController(controller: WalkVoiceSessionController) {
        walkVoiceSessionController = controller
    }

    fun detachWalkVoiceSessionController(controller: WalkVoiceSessionController) {
        if (walkVoiceSessionController === controller) {
            voiceStartFailureRelay.invalidateController(controller)
            walkVoiceSessionController = null
        }
    }

    fun registerWalkVoiceStartRequest(
        controller: WalkVoiceSessionController,
        onFailure: (Long, WalkVoiceSessionController, WalkVoiceStartFailure) -> Unit,
    ): Long = voiceStartFailureRelay.register(controller, onFailure)

    fun acceptsWalkVoiceStartRequest(requestId: Long, controller: WalkVoiceSessionController): Boolean =
        voiceStartFailureRelay.accepts(requestId, controller)

    fun invalidateWalkVoiceStartRequest(requestId: Long, controller: WalkVoiceSessionController) {
        voiceStartFailureRelay.invalidate(requestId, controller)
    }

    fun invalidateWalkVoiceStartRequestOnSessionEnd(
        requestId: Long,
        controller: WalkVoiceSessionController,
    ): Boolean = voiceStartFailureRelay.invalidateOnSessionEnded(requestId, controller)

    fun startWalkVoiceController(requestId: Long, controller: WalkVoiceSessionController): Boolean =
        voiceStartFailureRelay.startController(requestId, controller)

    fun reportWalkVoiceStartFailure(
        requestId: Long,
        controller: WalkVoiceSessionController,
        failure: WalkVoiceStartFailure,
    ): Boolean = voiceStartFailureRelay.report(requestId, controller, failure)
}

internal class WalkVoiceStartFailureRelay {
    private data class Request(
        val id: Long,
        val controller: WalkVoiceSessionController,
        val onFailure: (Long, WalkVoiceSessionController, WalkVoiceStartFailure) -> Unit,
    )

    private var nextId = 0L
    private var current: Request? = null
    private var starting: Request? = null

    fun register(
        controller: WalkVoiceSessionController,
        onFailure: (Long, WalkVoiceSessionController, WalkVoiceStartFailure) -> Unit,
    ): Long {
        val id = ++nextId
        current = Request(id, controller, onFailure)
        return id
    }

    fun accepts(requestId: Long, controller: WalkVoiceSessionController): Boolean =
        current?.let { it.id == requestId && it.controller === controller } == true

    fun invalidate(requestId: Long, controller: WalkVoiceSessionController) {
        if (accepts(requestId, controller)) current = null
    }

    fun invalidateController(controller: WalkVoiceSessionController) {
        if (current?.controller === controller) current = null
    }

    fun invalidateOnSessionEnded(requestId: Long, controller: WalkVoiceSessionController): Boolean {
        if (accepts(requestId, controller) &&
            starting?.let { it.id == requestId && it.controller === controller } == true
        ) return false
        invalidate(requestId, controller)
        return true
    }

    fun startController(requestId: Long, controller: WalkVoiceSessionController): Boolean {
        val request = current?.takeIf { it.id == requestId && it.controller === controller }
            ?: return false
        starting = request
        try {
            val started = controller.start()
            if (started) invalidate(requestId, controller)
            else report(requestId, controller, WalkVoiceStartFailure.CONTROLLER_REJECTED)
            return started
        } finally {
            if (starting === request) starting = null
        }
    }

    fun report(
        requestId: Long,
        controller: WalkVoiceSessionController,
        failure: WalkVoiceStartFailure,
    ): Boolean {
        val request = current?.takeIf { it.id == requestId && it.controller === controller }
            ?: return false
        current = null
        try {
            request.onFailure(requestId, controller, failure)
        } catch (_: RuntimeException) {
            // A failing UI callback must not prevent the service from cleaning up.
        }
        return true
    }
}

/** S04-30: delivers a user stop only for the controller that is actually attached now. */
internal class WalkVoiceUserStopRelay {
    private var listener: ((WalkVoiceSessionController) -> Unit)? = null

    fun setListener(listener: ((WalkVoiceSessionController) -> Unit)?) {
        this.listener = listener
    }

    fun report(stopped: WalkVoiceSessionController?, attached: WalkVoiceSessionController?): Boolean {
        if (stopped == null || stopped !== attached) return false
        val owner = listener ?: return false
        try {
            owner(stopped)
        } catch (_: RuntimeException) {
            // The service must still stop the microphone even if the UI owner fails.
        }
        return true
    }
}

/**
 * S04-30 (FP-025 lifecycle): a user's stop is not undone by a UI refresh, screen re-entry or Activity
 * re-creation. It holds automatic hands-free starts off for that walk; a new walk (new id) releases it.
 */
internal class WalkVoiceUserStopHold {
    private var stoppedWalkSessionId: String? = null

    @Synchronized
    fun recordUserStop(walkSessionId: String) {
        stoppedWalkSessionId = walkSessionId
    }

    @Synchronized
    fun blocksAutomaticStart(currentWalkSessionId: String): Boolean {
        val stopped = stoppedWalkSessionId ?: return false
        if (stopped != currentWalkSessionId) {
            stoppedWalkSessionId = null
            return false
        }
        return true
    }
}

internal data class WalkVoiceStartFailurePresentation(
    val statusToken: String,
    val guidance: String,
)

/** A failed platform start must not be retried by incidental runtime refreshes. */
internal class WalkVoiceStartRecovery {
    private var retryBlocked = false
    private var stoppedAfterFailure = false

    val blocksAutomaticRetry: Boolean get() = retryBlocked

    /**
     * S04-27-F1: the system silenced capture after the app had already left the foreground (a call
     * UI usually takes the screen first, so onStop ran before the failure). That leave counts, so
     * the next foreground return is the explicit retry instead of requiring a second leave.
     */
    val retriesOnNextForegroundReturn: Boolean get() = retryBlocked && stoppedAfterFailure

    fun recordFailure(
        failure: WalkVoiceStartFailure,
        microphoneGranted: Boolean,
        appInForeground: Boolean = true,
    ): WalkVoiceStartFailurePresentation {
        retryBlocked = (failure == WalkVoiceStartFailure.SECURITY && microphoneGranted) ||
            failure == WalkVoiceStartFailure.FOREGROUND_START_RESTRICTED ||
            failure == WalkVoiceStartFailure.INPUT_SILENCED
        stoppedAfterFailure = retryBlocked && !appInForeground && failure == WalkVoiceStartFailure.INPUT_SILENCED
        if (stoppedAfterFailure) {
            return WalkVoiceStartFailurePresentation(
                SILENCED_AFTER_LEAVING_TOKEN,
                "통화나 다른 앱이 마이크를 사용하는 동안 호출어와 음성 명령을 듣지 못했습니다. " +
                    "마이크 사용이 끝난 뒤 화면으로 돌아오면 호출어 대기를 다시 시도합니다. " +
                    "화면 버튼은 계속 사용할 수 있습니다.",
            )
        }
        return when (failure) {
            WalkVoiceStartFailure.SECURITY -> if (microphoneGranted) {
                WalkVoiceStartFailurePresentation(
                    "microphone_access_restricted_retry_required",
                    "마이크 권한은 허용되어 있지만 Android가 음성 입력을 거부했습니다. " +
                        "자동 재시도를 중단했습니다. 기기의 마이크 사용 제한을 확인하고 " +
                        "앱을 나갔다 다시 열어 재시도하세요.",
                )
            } else {
                WalkVoiceStartFailurePresentation(
                    "microphone_permission_missing",
                    "마이크 권한이 없어 호출어를 시작하지 못했습니다. " +
                        "앱 설정에서 마이크 권한을 허용한 뒤 다시 시도하세요.",
                )
            }
            WalkVoiceStartFailure.FOREGROUND_START_RESTRICTED ->
                WalkVoiceStartFailurePresentation(
                    "foreground_start_restricted_retry_required",
                    "Android가 음성 서비스 시작을 제한했습니다. 자동 재시도를 중단했습니다. " +
                        "앱을 나갔다 다시 화면으로 돌아와 재시도하세요.",
                )
            WalkVoiceStartFailure.INPUT_SILENCED ->
                WalkVoiceStartFailurePresentation(
                    "microphone_input_silenced_retry_required",
                    "통화나 다른 앱이 마이크를 사용하고 있어 호출어와 음성 명령을 들을 수 없습니다. " +
                        "자동 재시도를 중단했습니다. 통화나 녹음을 끝낸 뒤 앱을 나갔다 다시 화면으로 돌아와 " +
                        "재시도하세요. 화면 버튼은 계속 사용할 수 있습니다.",
                )
            WalkVoiceStartFailure.INPUT_ROUTE_CHANGED -> WalkVoiceStartFailurePresentation(
                "microphone_input_route_changed", MICROPHONE_INPUT_ROUTE_CHANGED_GUIDANCE,
            )
            WalkVoiceStartFailure.CONTROLLER_REJECTED,
            WalkVoiceStartFailure.RUNTIME,
            -> WalkVoiceStartFailurePresentation(
                "service_start_failed",
                "호출어 음성 입력을 시작하지 못했습니다. " +
                    "다시 시도하거나 화면의 음성 명령을 사용하세요.",
            )
        }
    }

    fun onStopped() {
        if (retryBlocked) stoppedAfterFailure = true
    }

    fun onForegroundReturn() {
        if (!stoppedAfterFailure) return
        retryBlocked = false
        stoppedAfterFailure = false
    }

    /**
     * S04-27-F1: the notice spoken during the call may not have been heard, so the outcome is said
     * again when the silenced-after-leaving notice is shown on return. Null for every other notice.
     */
    fun spokenNoticeOnReturn(presentation: WalkVoiceStartFailurePresentation): String? {
        if (presentation.statusToken != SILENCED_AFTER_LEAVING_TOKEN) return null
        return if (retryBlocked) {
            "아직 통화나 다른 앱이 마이크를 쓰고 있어 호출어를 듣지 않습니다. " +
                "마이크 사용이 끝난 뒤 앱을 나갔다 돌아오면 다시 시도합니다."
        } else {
            "통화나 다른 앱의 마이크 사용으로 멈췄던 호출어 대기를 다시 시도합니다."
        }
    }

    companion object {
        const val SILENCED_AFTER_LEAVING_TOKEN = "microphone_input_silenced_retry_on_return"
    }
}
