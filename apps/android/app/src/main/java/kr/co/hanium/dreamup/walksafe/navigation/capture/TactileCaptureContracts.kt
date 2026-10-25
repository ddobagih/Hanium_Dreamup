package kr.co.hanium.dreamup.walksafe.navigation.capture

import kr.co.hanium.dreamup.walksafe.device.DeviceGateState
import kr.co.hanium.dreamup.walksafe.device.WalkRuntimeSafetyDecision
import kr.co.hanium.dreamup.walksafe.feedback.AndroidFeedbackActuator
import kr.co.hanium.dreamup.walksafe.feedback.TactileDotReferenceDeliveryOwner
import kr.co.hanium.dreamup.walksafe.navigation.TactileActiveRouteProgress
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

/** Immutable borrowed capture facts; the original source data class/equality remains the caller's. */
internal interface TactileCaptureSourceFacts {
    val owner: TactileDotReferenceDeliveryOwner
    val frameId: Long
    val capturedAtElapsedRealtimeMs: Long
    val cpuImageTimestampNs: Long?
    val geometryVersion: Long
    val routeId: String?
    val routeProgress: TactileActiveRouteProgress?
}

/** One exact original receipt identity, never a value-equal substitute. */
internal interface TactileCaptureReceiptFacts<S : TactileCaptureSourceFacts> {
    val source: S
    val maskUpgrade: Boolean
}

internal interface TactileCaptureRouteFacts {
    fun navigationActiveNow(): Boolean
    fun tmapOnRouteNow(): Boolean
}

/** Existing source/safety/metric/startup authorities, queried in the original short-circuit order. */
internal interface TactileDotDispatchAuthority<L> {
    fun feedbackLifecycleCurrent(generation: Int): Boolean
    fun frameCurrent(generation: Int, epoch: WalkRuntimeEpoch): Boolean
    fun leaseCurrent(lease: L, observe: () -> L): Boolean
    fun safety(nowMs: Long): WalkRuntimeSafetyDecision
    fun metricOutputAllowed(arGeneration: Long, observe: () -> L): Boolean
    fun deviceGate(arGeneration: Long, observe: () -> L): DeviceGateState
    fun voiceFeatureEnabled(): Boolean
    fun offlineTtsAllowed(): Boolean
    fun guidanceVoiceInputActive(): Boolean
}

internal fun interface TactileDotDispatchActor {
    fun ensureActor(): AndroidFeedbackActuator
}
