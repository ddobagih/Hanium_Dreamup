package kr.co.hanium.dreamup.walksafe.startup.interaction

import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.device.postlogin.PostLoginLocationFixTerminalFailure
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.voice.OfflineKoreanSpeechUnavailableCause

internal interface StartupConfirmationFacts {
    val decision: WalkSafeStartupCapabilityDecision?
    val foreground: Boolean
    val feedbackGeneration: Int
    val actorId: String?
    val bypass: Boolean
    val quickStart: Boolean
    val resourceProbeInitialized: Boolean
    fun feedbackCurrent(generation: Int): Boolean
    fun onboardingComplete(): Boolean
    fun currentReporter(): String?
    fun resourceSnapshot(): WalkSessionDeviceResourceSnapshot
    fun profileMatch(): ApprovedDeviceProfileMatch
}
internal interface StartupConfirmationEffects {
    fun prepareResumeRetry()
    fun startFreshWalk(reason: String)
    fun refresh()
    fun logResources(reason: String, resources: WalkSessionDeviceResourceSnapshot)
    fun resumePermissionRecovery(): Boolean
    fun voiceResumeAvailable(): Boolean
    fun confirmResumeButton()
    fun readinessBlock(decision: WalkSafeStartupCapabilityDecision): String?
    fun requestMissingPermissions(decision: WalkSafeStartupCapabilityDecision)
    fun status(title: String, detail: String)
    fun onUi(action: () -> Unit)
    /** Only the existing speech output owner's actual completion invokes onCompleted. */
    fun speakNotice(message: String, onCompleted: () -> Unit, onFailed: () -> Unit,
        onCancelled: () -> Unit, onRetired: () -> Unit, additionalCurrent: () -> Boolean): Boolean
}
internal fun interface StartupDecisionStorage {
    fun write(decision: WalkSafeStartupCapabilityDecision, profile: ApprovedDeviceProfileMatch)
}

internal interface PostLoginDeviceFacts {
    val snapshot: PostLoginDeviceCheckSnapshot
    val quickStart: Boolean
    val bypass: Boolean
    val cameraChecksDeferred: Boolean
    val obstacleOverride: Boolean?
    val metricOverride: Boolean?
    val offlineTtsOverride: Boolean?
    val probeInitialized: Boolean
    val metricDepth: PostLoginMetricDepthState
    val cameraPipelineActive: Boolean
    val resourceWaitNotice: String?
    fun cameraPermission(): Boolean
    fun locationPermission(): Boolean
    fun locationServiceEnabled(): Boolean
    fun recordAudioPermission(): Boolean
    fun notificationPermission(): Boolean
    fun disclosureAccepted(): Boolean
    fun activityRecognitionPermission(): Boolean
    fun startupSnapshot(): WalkSafeStartupCapabilityInput
    fun passesFeatureGate(): Boolean
    fun detectorLoadAttempted(): Boolean
    fun detectorAvailable(): Boolean
    fun observation(): PostLoginDeviceCheckObservation
    fun screenReaderActive(): Boolean
}
internal interface PostLoginDeviceLiveStatusView {
    fun published(): Boolean
    fun liveRegionPolite()
    fun liveRegionNone()
    fun show()
    fun text(message: String)
    fun post(action: () -> Unit)
    fun shown(): Boolean
    fun requestAccessibilityFocus()
}

internal interface VoiceDataProbePort {
    val initialized: Boolean
    var started: Boolean
    fun snapshot(): WalkSafeStartupCapabilityInput
    fun unavailableCause(): OfflineKoreanSpeechUnavailableCause?
    fun recheck()
    fun close()
    fun replace(onChanged: () -> Unit)
    fun start()
}
internal interface VoiceSettingsFacts {
    val snapshot: PostLoginDeviceCheckSnapshot
    val locationTerminalFailure: PostLoginLocationFixTerminalFailure?
    val locationReason: DeviceCheckLocationFixReason?
    fun requiredPermissions(): List<String>
    fun locationServiceEnabled(): Boolean
}
internal interface VoiceSettingsEffects {
    var offlineTtsOverride: Boolean?
    fun refresh()
    fun status(title: String, detail: String)
    fun speak(message: String)
    fun recoverPassedGate(): Boolean
    fun recoverPassedGate(onCompleted: (Boolean) -> Unit): Boolean {
        onCompleted(recoverPassedGate())
        return true
    }
}
internal enum class StartupSettingsTarget { APPLICATION, LOCATION, DEVELOPMENT }
internal interface StartupSettingsNative<I> {
    fun installVoiceData(): I
    fun chooseVoiceEngine(): I
    fun applicationSettings(): I
    fun locationSettings(): I
    fun developmentSettings(): I
    fun start(intent: I)
}

internal interface ResourceSafetyFacts {
    val lifecycleInitialized: Boolean
    val probeInitialized: Boolean
    val bypass: Boolean
    fun currentRuntimeEpoch(): WalkRuntimeEpoch?
    fun resourceSnapshot(): WalkSessionDeviceResourceSnapshot
    fun epochCurrent(epoch: WalkRuntimeEpoch): Boolean
}
internal fun interface ResourceSafetyThermalObservation { fun record(throttled: Boolean?) }
