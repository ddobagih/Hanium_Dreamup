package kr.co.hanium.dreamup.walksafe.startup.interaction

import java.util.Locale
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.session.*
import kr.co.hanium.dreamup.walksafe.voice.*
import kr.co.hanium.dreamup.walksafe.ui.DeviceCheckPresenter

/** Derives current feature restrictions and owns only the displayed live-status deduplication. */
internal class PostLoginDeviceInteractionController(
    private val facts: PostLoginDeviceFacts,
    private val view: PostLoginDeviceLiveStatusView,
) {
    private var lastLiveStatus: String? = null
    fun liveStatus() = lastLiveStatus
    fun writeLiveStatus(value: String?) { lastLiveStatus = value }

    fun announcePostLoginDeviceCheckProgress(message: String) {
        if (
            !view.published() ||
            lastLiveStatus == message
        ) return
        lastLiveStatus = message
        view.liveRegionPolite()
        view.show()
        view.text(message)
    }

    fun announcePostLoginDeviceCheckTerminal(message: String) {
        if (!view.published()) return
        lastLiveStatus = message
        // Terminal text owns accessibility focus, so it must not also emit a live-region
        // announcement of the same sentence.
        view.liveRegionNone()
        view.show()
        view.text(message)
        if (!facts.screenReaderActive()) return
        view.post {
            if (
                facts.snapshot.state != PostLoginDeviceCheckState.FAIL ||
                !view.shown()
            ) return@post
            view.requestAccessibilityFocus()
        }
    }

    fun postLoginDeviceCheckDisabledFeatureText(): String {
        val disabled = currentPostLoginDisabledFeatures()
        val passes = facts.snapshot.passesFeatureGate
        val voiceCommandsUnavailable = PostLoginDeviceCheckFeature.HANDS_FREE_VOICE in disabled && (
            PostLoginDeviceCheckFeature.HANDS_FREE_VOICE in facts.snapshot.disabledFeatures ||
                !facts.recordAudioPermission() ||
                (facts.probeInitialized && facts.startupSnapshot().onDeviceSpeechRecognitionAvailable == false)
        )
        return DeviceCheckPresenter.postLoginDeviceCheckDisabledFeatureText(DeviceCheckPresenter.DisabledFacts(
            disabledFeatures = disabled.toSet(),
            passesFeatureGate = passes,
            voiceCommandsUnavailable = voiceCommandsUnavailable,
            metricFeatureEnabled = !passes || postLoginDeviceFeatureEnabled(PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE),
            locationFeatureEnabled = !passes || postLoginDeviceFeatureEnabled(PostLoginDeviceCheckFeature.LOCATION_GUIDANCE),
            activityRecognitionPermission = !passes || facts.activityRecognitionPermission(),
        ))
    }

    fun hasCurrentPostLoginDeviceRestrictions(): Boolean =
        currentPostLoginDisabledFeatures().isNotEmpty() ||
            (
                facts.snapshot.passesFeatureGate &&
                    !facts.activityRecognitionPermission()
            )

    fun currentPostLoginDisabledFeatures(): Set<PostLoginDeviceCheckFeature> = buildSet {
        if (!facts.quickStart) {
            addAll(facts.snapshot.disabledFeatures)
            if (!facts.snapshot.passesFeatureGate) return@buildSet
        }
        if (facts.cameraChecksDeferred) {
            add(PostLoginDeviceCheckFeature.OBSTACLE_DETECTION)
            add(PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE)
        }
        if (facts.obstacleOverride == false) {
            add(PostLoginDeviceCheckFeature.OBSTACLE_DETECTION)
        }
        if (facts.metricOverride == false) {
            add(PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE)
        }
        if (!facts.cameraPermission()) {
            add(PostLoginDeviceCheckFeature.OBSTACLE_DETECTION)
            add(PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE)
        }
        if (!facts.locationPermission() || !facts.locationServiceEnabled()) {
            add(PostLoginDeviceCheckFeature.LOCATION_GUIDANCE)
        }
        if (
            facts.probeInitialized &&
            facts.startupSnapshot().onDeviceSpeechRecognitionAvailable == false
        ) {
            add(PostLoginDeviceCheckFeature.HANDS_FREE_VOICE)
        }
        if (
            facts.offlineTtsOverride == false ||
            (
                facts.probeInitialized &&
                    facts.startupSnapshot().offlineKoreanTextToSpeechAvailable == false
            )
        ) {
            add(PostLoginDeviceCheckFeature.VOICE_GUIDANCE)
        }
        if (
            !facts.recordAudioPermission() ||
            !facts.notificationPermission() ||
            !facts.disclosureAccepted()
        ) {
            add(PostLoginDeviceCheckFeature.HANDS_FREE_VOICE)
        }
    }

    fun postLoginDeviceFeatureEnabled(feature: PostLoginDeviceCheckFeature): Boolean =
        facts.bypass ||
            ((facts.quickStart || facts.passesFeatureGate()) &&
                feature !in currentPostLoginDisabledFeatures())

    fun deferredCameraDependentChecksCanBeRetried(): Boolean =
        facts.snapshot.passesFeatureGate &&
            (
                facts.cameraChecksDeferred ||
                    // S03-6: obstacle detection gates every walk, so a stored limit stays re-checkable.
                    PostLoginDeviceCheckFeature.OBSTACLE_DETECTION in
                    facts.snapshot.disabledFeatures ||
                    // S03-6: a runtime model-load (or camera fallback) failure blocks every walk as
                    // well, so it must stay re-checkable instead of lasting for the process lifetime.
                    facts.obstacleOverride == false
            ) &&
            facts.cameraPermission()

    fun cameraAnalysisFeaturesEnabled(): Boolean =
        postLoginDeviceFeatureEnabled(PostLoginDeviceCheckFeature.OBSTACLE_DETECTION) ||
            postLoginDeviceFeatureEnabled(
                PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE,
            )

    fun terminalCameraDetectorStatusText(): String? {
        if (!facts.snapshot.passesFeatureGate) return null
        return when {
            facts.cameraChecksDeferred ->
                if (facts.cameraPermission()) "재점검 필요" else "권한 허용 후 재점검 필요"
            !postLoginDeviceFeatureEnabled(PostLoginDeviceCheckFeature.OBSTACLE_DETECTION) ->
                "제한"
            !facts.detectorLoadAttempted() -> "통과 · 보행 시작 시 모델 재확인"
            facts.detectorAvailable() -> "통과"
            else -> "제한"
        }
    }

    fun terminalMetricDistanceStatusText(): String? {
        if (!facts.snapshot.passesFeatureGate) return null
        return when {
            facts.cameraChecksDeferred ->
                if (facts.cameraPermission()) "재점검 필요" else "권한 허용 후 재점검 필요"
            facts.metricDepth == PostLoginMetricDepthState.UNKNOWN ->
                "확인 불가(AR 서비스·권한 확인)"
            !postLoginDeviceFeatureEnabled(
                PostLoginDeviceCheckFeature.METRIC_DISTANCE_GUIDANCE,
            ) -> "제한(거리 제한 모드)"
            facts.metricDepth == PostLoginMetricDepthState.SUPPORTED ->
                "지원"
            facts.metricDepth == PostLoginMetricDepthState.PENDING ->
                "지원 확인 대기"
            else -> null
        }
    }

    fun postLoginDeviceCheckItemsMessage(): String {
        val state = facts.snapshot.state
        if (state == PostLoginDeviceCheckState.NOT_RUN || state == PostLoginDeviceCheckState.REQUESTING_PERMISSIONS) {
            return DeviceCheckPresenter.postLoginDeviceCheckItemsMessage(DeviceCheckPresenter.ItemsFacts(state))
        }
        val observation = facts.observation()
        val startup = facts.startupSnapshot()
        val microphonePermission = facts.recordAudioPermission()
        val locationPermission = facts.locationPermission()
        val terminalCameraText = terminalCameraDetectorStatusText()
        val terminalMetricText = terminalMetricDistanceStatusText()
        return DeviceCheckPresenter.postLoginDeviceCheckItemsMessage(DeviceCheckPresenter.ItemsFacts(
            state = state, observation = observation,
            microphoneAvailable = startup.microphoneAvailable, gpsAvailable = startup.gpsAvailable,
            cameraAvailable = startup.cameraAvailable,
            microphonePermission = microphonePermission, locationPermission = locationPermission,
            cameraPermission = facts.cameraChecksDeferred &&
                (terminalCameraText == null || terminalMetricText == null) && facts.cameraPermission(),
            cameraPipelineActive = facts.cameraPipelineActive,
            cameraChecksDeferred = facts.cameraChecksDeferred,
            terminalCameraText = terminalCameraText, terminalMetricText = terminalMetricText,
            resourceWaitNotice = facts.resourceWaitNotice,
        ))
    }
}
