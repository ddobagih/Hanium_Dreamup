package kr.co.hanium.dreamup.walksafe.runtime.metric

import com.google.ar.core.ArCoreApk
import kr.co.hanium.dreamup.walksafe.shouldRecheckArCoreAvailability
import kr.co.hanium.dreamup.walksafe.depth.DepthAcquisitionFailureReason
import kr.co.hanium.dreamup.walksafe.depth.DepthFrameSnapshot
import kr.co.hanium.dreamup.walksafe.device.*
import kr.co.hanium.dreamup.walksafe.runtime.camera.lifecycle.RuntimeArPlatform
import kr.co.hanium.dreamup.walksafe.runtime.vision.ar.RuntimeArSessionOwner
import kr.co.hanium.dreamup.walksafe.session.WalkRuntimeEpoch

/** Preflight SDK/publication orchestration around the actual metric/native/lifecycle owners. */
internal class RuntimeMetricPreflightCoordinator<L, Q, S : Any, P : Any, F>(
    private val stateLock: Any,
    private val metric: () -> RuntimeMetricSessionController<L, Q, S>,
    private val native: () -> RuntimeArSessionOwner<S, P, Q>,
    private val platform: RuntimeArPlatform<S, P>,
    private val probe: RuntimeMetricDepthSupportProbe,
    private val frames: RuntimeMetricPreflightFrameEvidence<F>,
    private val admission: RuntimeMetricPreflightAdmission,
    private val renderer: RuntimeMetricPreflightRenderer,
    private val presentation: RuntimeMetricPreflightPresentation,
    private val loss: RuntimeMetricLossLifecycle,
    private val preflightPurpose: Q,
    private val runtimePurpose: Q,
    private val elapsedRealtimeMs: () -> Long,
    private val debug: Boolean,
    private val diagnostic: (String) -> Unit,
) {
    private var supportRequestGeneration = 0L
    private var pendingSupportAttempt: Pair<Long, Int>? = null

    /** Retires callback authority before asking the remote owner to release its process. */
    fun cancelSupportCheck() {
        ++supportRequestGeneration
        pendingSupportAttempt = null
        probe.cancel()
    }
    fun updateOutputGate(frame: F, snapshot: DepthFrameSnapshot, observedAtMs: Long,
        expectedGeneration: Long, expectedEpoch: WalkRuntimeEpoch,
        acquisitionFailure: DepthAcquisitionFailureReason? = null, recognitionCameraTimestampNs: Long? = null,
    ) {
        metric().updateRuntimeMetricFrameEvidence(expectedGeneration, expectedEpoch, acquisitionFailure,
            observeFrameEvidence = { frames.observe(frame, snapshot, observedAtMs, expectedGeneration, recognitionCameraTimestampNs) })
    }

    fun handleRuntimeMetricLoss(expectedArSessionGeneration: Long) {
        if (native().observe().purpose != runtimePurpose ||
            native().observe().generation != expectedArSessionGeneration ||
            admission.metricDistanceAllowed() != true) return
        val availability = runCatching { platform.availability() }.getOrDefault(ArCoreApk.Availability.UNKNOWN_ERROR)
        if (debug) {
            val activationResult = synchronized(stateLock) { metric().activationEvaluator?.result() }
            diagnostic("event=RUNTIME_METRIC_LOST activation=${activationResult?.status?.name ?: "COMPLETE"} " +
                "reason=${activationResult?.reason?.name ?: "FRAME_OUTPUT_LOST"} " +
                "frames=${activationResult?.distinctFrameCount ?: -1} " +
                "passing=${activationResult?.passingFrameCount ?: -1} " +
                "span_ms=${activationResult?.observationSpanMs ?: -1L}")
        }
        loss.stopDepth()
        // The original fallback request owns its safety-stop/recheck notice.
        loss.requestFallback(availability)
    }

    fun startRuntimeMetricPreflight(generation: Long, lifecycleGeneration: Int) {
        if (!metric().isRuntimeMetricPreflightAttemptCurrent(generation, lifecycleGeneration)) return
        val availability = platform.availability()
        if (shouldRecheckArCoreAvailability(availability)) {
            presentation.availabilityPending()
            platform.availabilityAsync { resolved ->
                presentation.onUi {
                    if (metric().isRuntimeMetricPreflightAttemptCurrent(generation, lifecycleGeneration)) {
                        continueRuntimeMetricPreflightStart(resolved, generation, lifecycleGeneration)
                    }
                }
            }
            return
        }
        continueRuntimeMetricPreflightStart(availability, generation, lifecycleGeneration)
    }

    fun continueRuntimeMetricPreflightStart(availability: ArCoreApk.Availability, generation: Long, lifecycleGeneration: Int) {
        if (!metric().isRuntimeMetricPreflightAttemptCurrent(generation, lifecycleGeneration)) return
        val immediate = when {
            !admission.cameraPermission() -> {
                RuntimeMetricDepthSupport.UNKNOWN to "CAMERA_PERMISSION_MISSING"
            }
            availability == ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE ->
                RuntimeMetricDepthSupport.UNSUPPORTED to availability.name
            availability != ArCoreApk.Availability.SUPPORTED_INSTALLED ->
                RuntimeMetricDepthSupport.UNKNOWN to availability.name
            else -> null
        }
        if (immediate != null) {
            cancelSupportCheck()
            metric().finishDeviceMetricDepthSupportCheck(generation, lifecycleGeneration, immediate.first, immediate.second)
            return
        }
        val attempt = generation to lifecycleGeneration
        if (pendingSupportAttempt == attempt) return
        cancelSupportCheck()
        val request = ++supportRequestGeneration
        pendingSupportAttempt = attempt
        val receive: (Result<Boolean>) -> Unit = { result ->
            presentation.onUi {
                if (request == supportRequestGeneration && pendingSupportAttempt == attempt &&
                    metric().isRuntimeMetricPreflightAttemptCurrent(generation, lifecycleGeneration)) {
                    // Consume authority before publication; duplicate/reentrant completions are stale.
                    pendingSupportAttempt = null
                    ++supportRequestGeneration
                    val permission = admission.cameraPermission()
                    if (metric().isRuntimeMetricPreflightAttemptCurrent(generation, lifecycleGeneration)) {
                        val support = if (!permission || result.isFailure) RuntimeMetricDepthSupport.UNKNOWN
                            else if (result.getOrNull() == true) RuntimeMetricDepthSupport.SUPPORTED
                            else RuntimeMetricDepthSupport.UNSUPPORTED
                        val reason = if (!permission) "CAMERA_PERMISSION_MISSING" else result.fold(
                            { if (it) "DEPTH_MODE_AUTOMATIC_SUPPORTED" else "DEPTH_MODE_AUTOMATIC_UNSUPPORTED" },
                            { (it as? MetricSupportProbeException)?.reason?.name ?: it.javaClass.simpleName })
                        metric().finishDeviceMetricDepthSupportCheck(generation, lifecycleGeneration, support, reason)
                    }
                }
            }
        }
        try { probe.query(receive) } catch (error: Exception) { receive(Result.failure(error)) }
    }

    fun startRuntimeMetricPreflightSession(preflightSession: S, provider: P, generation: Long, lifecycleGeneration: Int) {
        if (!metric().isRuntimeMetricPreflightAttemptCurrent(generation, lifecycleGeneration)) {
            platform.closeSession(preflightSession)
            return
        }
        val startedAtMs = elapsedRealtimeMs()
        val evaluator = RuntimeMetricPreflightSession(generation, startedAtMs,
            RuntimeMetricDepthSupport.SUPPORTED, admission.profile())
        val resumeRenderer = renderer.pauseForClose()
        try {
            val textureBound = platform.resumeBeforePublish(preflightSession)
            synchronized(stateLock) {
                native().publish(preflightSession, provider, preflightPurpose) { arGeneration ->
                    metric().publishPreflightEvaluator(evaluator, arGeneration)
                    renderer.textureBound(textureBound)
                }
            }
        } finally {
            renderer.resumeAfterClose(resumeRenderer)
        }
        renderer.publishSupported()
        presentation.status("미터 거리 기능 확인 중", "보행 기능과 신고는 시작하지 않고 실제 Depth 프레임만 확인합니다.")
        presentation.refreshUi()
        presentation.postExpiry({ metric().expirePreflightIfCurrent(generation, lifecycleGeneration) }, RuntimeMetricPreflightPolicy.MAX_DURATION_MS)
    }
}
