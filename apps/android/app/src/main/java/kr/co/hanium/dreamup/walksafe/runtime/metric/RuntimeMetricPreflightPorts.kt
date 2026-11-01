package kr.co.hanium.dreamup.walksafe.runtime.metric

import com.google.ar.core.ArCoreApk
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.device.RuntimeMetricFrameEvidence
import kr.co.hanium.dreamup.walksafe.device.RuntimeMetricPreflightProfile

internal interface RuntimeMetricPreflightAdmission {
    fun cameraPermission(): Boolean
    fun metricDistanceAllowed(): Boolean?
    fun profile(): RuntimeMetricPreflightProfile?
}
internal interface RuntimeMetricPreflightRenderer {
    fun pauseForClose(): Boolean
    fun resumeAfterClose(resume: Boolean)
    fun textureBound(bound: Boolean)
    fun publishSupported()
}
internal interface RuntimeMetricPreflightPresentation {
    fun availabilityPending()
    fun onUi(action: () -> Unit)
    fun status(title: String, detail: String)
    fun refreshUi()
    fun postExpiry(action: () -> Unit, delayMs: Long)
}
internal interface RuntimeMetricLossLifecycle {
    fun stopDepth()
    fun requestFallback(availability: ArCoreApk.Availability)
}
internal fun interface RuntimeMetricDepthSupportProbe {
    fun automaticDepthSupported(): Boolean
    /** Production implementations are asynchronous and never allocate ARCore in the caller. */
    fun query(onResult: (Result<Boolean>) -> Unit) { onResult(runCatching(::automaticDepthSupported)) }
    fun cancel() {}
}
/** Synchronous SDK observation only. Implementations must not retain the borrowed frame. */
internal fun interface RuntimeMetricPreflightFrameEvidence<F> {
    fun observe(frame: F, snapshot: DepthFrameSnapshot, observedAtMs: Long,
        generation: Long, recognitionCameraTimestampNs: Long?): RuntimeMetricFrameEvidence
}
